package edu.lcl.rag.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.lcl.rag.dto.RagResponse;
import edu.lcl.rag.entity.ChatMessage;
import edu.lcl.rag.service.ChatSessionService;
import edu.lcl.rag.service.StreamingRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 统一文字入口；只由实际回答分支保存本轮及结束 SSE，不重复入库。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantDispatchService {

    private final IntentRouter router;
    private final RagScopeService scopes;
    private final StreamingRagService rag;
    private final ChatSessionService sessions;
    private final BusinessQueryHandler businessQueries;
    private final OtherAnswerHandler otherAnswers;
    private final AnswerComposer composer;
    private final ObjectMapper mapper;

    public void stream(String question, List<Long> kbIds, Long docId,
                       String sessionId, SseEmitter emitter) throws Exception {
        long start = System.currentTimeMillis();
        AtomicBoolean gone = new AtomicBoolean();
        emitter.onCompletion(() -> gone.set(true));
        emitter.onError(error -> gone.set(true));
        emitter.onTimeout(() -> gone.set(true));
        if (!send(emitter, "status", Map.of("type", "ROUTING", "message", "正在理解问题…"))) {
            return;
        }
        List<ChatMessage> history = sessions.getHistory(sessionId);
        IntentPlan plan = router.route(question, history);
        IntentDecision decision = plan.tasks().getFirst();
        if (gone.get() || !send(emitter, "intent",
                Map.of("intent", decision.intent(), "action", decision.action(),
                        "intents", plan.tasks().stream().map(IntentDecision::intent).toList()))) {
            return;
        }
        if (plan.tasks().size() == 2) {
            streamMixed(question, plan, kbIds, docId, sessionId, history, emitter, gone, start);
            return;
        }
        if (decision.intent() == Intent.RAG) {
            try {
                var scope = scopes.resolve(sessionId, kbIds, docId);
                if (gone.get() || !send(emitter, "scope", scope)) {
                    return;
                }
                rag.streamQuery(question, decision.query(), scope.kbIds(), scope.docId(), sessionId, emitter);
                return;
            } catch (IllegalArgumentException invalidScope) {
                reply(question, sessionId, decision.intent(), scopeAnswer(decision.query(), invalidScope.getMessage()),
                        emitter, gone, start, IntentDecision.Action.CLARIFY, null);
                return;
            }
        }
        String answer;
        IntentDecision.Action action = decision.action();
        ConversationState state = ConversationState.from(decision);
        if (decision.intent() == Intent.BUSINESS_QUERY) {
            if (!send(emitter, "status", Map.of("type", "BUSINESS_QUERY", "message", "正在处理业务查询…"))) {
                return;
            }
            var result = businessQueries.answer(decision, history);
            answer = result.text();
            state = ConversationState.from(decision, result);
            if (result.state() == BusinessQueryTool.State.NEEDS_CLARIFICATION) {
                action = IntentDecision.Action.CLARIFY;
            }
        } else {
            answer = otherAnswers.answer(decision, answerHistory(decision, history, sessionId));
        }
        reply(question, sessionId, decision.intent(), answer, emitter, gone, start, action, state);
    }

    public RagResponse sync(String question, List<Long> kbIds, Long docId, String sessionId) {
        long start = System.currentTimeMillis();
        List<ChatMessage> history = sessions.getHistory(sessionId);
        IntentPlan plan = router.route(question, history);
        IntentDecision decision = plan.tasks().getFirst();
        if (plan.tasks().size() == 2) {
            RagResponse documentAnswer = mixedDocumentAnswer(question, decision.query(), kbIds, docId, sessionId);
            var business = businessQueries.answer(plan.tasks().getLast(), history);
            String documentText = "【文档解答】\n" + documentAnswer.getAnswer();
            String businessText = "【业务查询】\n" + business.text();
            int elapsed = (int) (System.currentTimeMillis() - start);
            saveMixed(sessionId, question, documentText, documentAnswer.getSources(), businessText, elapsed,
                    ConversationState.from(plan.tasks().getLast(), business));
            return RagResponse.builder().answer(documentText + "\n\n" + businessText)
                    .sources(documentAnswer.getSources()).latencyMs(elapsed).sessionId(sessionId)
                    .intent(Intent.RAG).intents(List.of(Intent.RAG, Intent.BUSINESS_QUERY)).build();
        }
        String answer;
        ConversationState state = ConversationState.from(decision);
        if (decision.intent() == Intent.RAG) {
            try {
                var scope = scopes.resolve(sessionId, kbIds, docId);
                return rag.syncQuery(question, decision.query(), scope.kbIds(), scope.docId(), sessionId, true);
            } catch (IllegalArgumentException invalidScope) {
                answer = scopeAnswer(decision.query(), invalidScope.getMessage());
            }
        } else {
            if (decision.intent() == Intent.BUSINESS_QUERY) {
                var business = businessQueries.answer(decision, history);
                answer = business.text();
                state = ConversationState.from(decision, business);
            } else {
                answer = otherAnswers.answer(decision, answerHistory(decision, history, sessionId));
            }
        }
        int elapsed = (int) (System.currentTimeMillis() - start);
        saveReply(sessionId, question, answer, elapsed, decision.intent(), state);
        return RagResponse.builder().answer(answer).sources(List.of()).latencyMs(elapsed)
                .intent(decision.intent()).sessionId(sessionId).build();
    }

    private void streamMixed(String question, IntentPlan plan, List<Long> kbIds, Long docId, String sessionId,
                             List<ChatMessage> history, SseEmitter emitter, AtomicBoolean gone,
                             long start) throws Exception {
        // 先计算缺少的条件/未接入提示，文档完成后再作为独立段落展示，不能用文档内容补假数据。
        var business = businessQueries.answer(plan.tasks().getLast(), history);
        if (!send(emitter, "token", "【文档解答】\n")) {
            return;
        }
        AtomicBoolean finishing = new AtomicBoolean();
        StreamingRagService.StreamCompletion completion = new StreamingRagService.StreamCompletion() {
            @Override
            public void complete(RagResponse documentAnswer) {
                if (!finishing.compareAndSet(false, true)) {
                    return;
                }
                String businessText = "【业务查询】\n" + business.text();
                if (gone.get() || !send(emitter, "token", "\n\n" + businessText)) {
                    return;
                }
                int elapsed = (int) (System.currentTimeMillis() - start);
                saveMixed(sessionId, question, "【文档解答】\n" + documentAnswer.getAnswer(),
                        documentAnswer.getSources(), businessText, elapsed,
                        ConversationState.from(plan.tasks().getLast(), business));
                send(emitter, "done", Map.of("sources", documentAnswer.getSources(), "latencyMs", elapsed,
                        "intents", List.of(Intent.RAG, Intent.BUSINESS_QUERY),
                        "action", business.state() == BusinessQueryTool.State.NEEDS_CLARIFICATION
                                ? IntentDecision.Action.CLARIFY : IntentDecision.Action.ANSWER));
                emitter.complete();
            }

            @Override
            public void failed(String message) {
                // 收尾异常交给 SSE error 处理，不能再次追加业务段或重试整轮入库。
                if (finishing.get()) {
                    throw new IllegalStateException("混合回答收尾失败");
                }
                if (!gone.get() && send(emitter, "token", "\n" + message)) {
                    complete(RagResponse.builder().answer(message).sources(List.of()).build());
                }
            }
        };
        ChatSessionService.RetrievalScope scope;
        try {
            scope = scopes.resolve(sessionId, kbIds, docId);
        } catch (IllegalArgumentException invalidScope) {
            String message = scopeAnswer(plan.tasks().getFirst().query(), invalidScope.getMessage());
            if (send(emitter, "token", message)) {
                completion.complete(RagResponse.builder().answer(message).sources(List.of()).build());
            }
            return;
        } catch (RuntimeException documentFailure) {
            logDocumentFailure(documentFailure);
            completion.failed("文档解答失败：资料服务暂时不可用，请稍后重试。");
            return;
        }
        if (!send(emitter, "scope", scope)) {
            return;
        }
        rag.streamQuery(question, plan.tasks().getFirst().query(), scope.kbIds(), scope.docId(),
                sessionId, emitter, completion);
    }

    private RagResponse mixedDocumentAnswer(String question, String documentQuestion, List<Long> kbIds,
                                           Long docId, String sessionId) {
        ChatSessionService.RetrievalScope scope;
        try {
            scope = scopes.resolve(sessionId, kbIds, docId);
        } catch (IllegalArgumentException invalidScope) {
            return RagResponse.builder().answer(scopeAnswer(documentQuestion, invalidScope.getMessage()))
                    .sources(List.of()).build();
        } catch (RuntimeException documentFailure) {
            logDocumentFailure(documentFailure);
            return documentFailed();
        }
        try {
            return rag.syncQuery(question, documentQuestion, scope.kbIds(), scope.docId(), sessionId, false);
        } catch (RuntimeException documentFailure) {
            logDocumentFailure(documentFailure);
            return documentFailed();
        }
    }

    private void logDocumentFailure(RuntimeException error) {
        log.warn("[AssistantDispatch] 混合请求的文档解答失败：{}", error.getClass().getSimpleName());
    }

    private String scopeAnswer(String question, String issue) {
        return composer.compose(question, "RAG_SCOPE_INVALID", Map.of("scopeIssue", issue), List.of());
    }

    private RagResponse documentFailed() {
        return RagResponse.builder().answer("文档解答失败：暂时无法完成资料检索或回答生成，请稍后重试。")
                .sources(List.of()).build();
    }

    private void saveMixed(String sessionId, String question, String documentAnswer,
                           List<RagResponse.Source> sources, String businessAnswer, int elapsed,
                           ConversationState businessState) {
        sessions.saveTurn(sessionId, question, List.of(
                new ChatSessionService.AssistantReply(documentAnswer, mapper.valueToTree(sources).toString(), elapsed, Intent.RAG),
                new ChatSessionService.AssistantReply(businessAnswer, "[]", elapsed, Intent.BUSINESS_QUERY,
                        businessState)));
    }

    private List<ChatMessage> answerHistory(IntentDecision decision, List<ChatMessage> recentHistory,
                                            String sessionId) {
        return decision.kind() == IntentDecision.Kind.CONVERSATION_META
                ? sessions.getAllHistory(sessionId) : recentHistory;
    }

    private void reply(String question, String sessionId, Intent intent, String answer,
                       SseEmitter emitter, AtomicBoolean gone, long start,
                       IntentDecision.Action action, ConversationState state) {
        if (gone.get() || !send(emitter, "token", answer)) {
            return;
        }
        int elapsed = (int) (System.currentTimeMillis() - start);
        saveReply(sessionId, question, answer, elapsed, intent, state);
        send(emitter, "done", Map.of("sources", List.of(), "latencyMs", elapsed,
                "intent", intent, "action", action));
        emitter.complete();
    }

    private void saveReply(String sessionId, String question, String answer, int elapsed,
                           Intent intent, ConversationState state) {
        sessions.saveTurn(sessionId, question, List.of(
                new ChatSessionService.AssistantReply(answer, "[]", elapsed, intent, state)));
    }

    private boolean send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name)
                    .data(data instanceof String ? data : mapper.writeValueAsString(data)));
            return true;
        } catch (IOException | IllegalStateException error) {
            return false;
        }
    }
}
