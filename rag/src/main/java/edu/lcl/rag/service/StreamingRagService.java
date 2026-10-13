package edu.lcl.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.lcl.rag.dto.RagResponse;
import edu.lcl.rag.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class StreamingRagService {

    private final EnhancedRetrieverService enhancedRetriever;
    private final RerankerService rerankerService;
    private final ConfidenceFilter confidenceFilter;
    private final ParentContextService parentContextService;
    private final SourceBuilder sourceBuilder;
    private final ChatSessionService sessionService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    /**
     * 流式 RAG 查询，通过 SSE 推送结果。
     */
    public void streamQuery(String question, List<Long> kbIds, String sessionId, SseEmitter emitter) {
        streamQuery(question, kbIds, null, sessionId, emitter);
    }

    public void streamQuery(String question, List<Long> kbIds, Long docId,
                            String sessionId, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        // 客户端断开（关页面 / 刷新 / 切会话）是正常事件：标记后停止推送与生成，
        // 并以 INFO/DEBUG 记录，不再抛异常打断主流程（否则会级联出一串 ERROR 堆栈）。
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onCompletion(() -> clientGone.set(true));
        emitter.onError(e -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));

        try {
            // Step 1：推送"检索中"状态（推不动说明客户端已走，直接放弃，省掉后续检索与生成开销）
            if (!sendEvent(emitter, SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"RETRIEVING\",\"message\":\"正在检索知识库...\"}"))) {
                return;
            }

            // Step 2：执行检索管道
            var candidates = enhancedRetriever.retrieveWithHyde(question, kbIds, docId, 40);
            var rerankOutcome = rerankerService.rerank(question, candidates, 15);
            var filtered = confidenceFilter.filter(rerankOutcome.chunks(), rerankOutcome.reranked());

            if (filtered.isEmpty()) {
                sendNotFound(emitter);
                return;
            }

            var trimmed = parentContextService.selectForContext(question, parentContextService.restore(filtered));
            if (trimmed.isEmpty()) {
                sendNotFound(emitter);
                return;
            }

            // Step 3：推送"生成中"状态
            if (!sendEvent(emitter, SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"GENERATING\",\"message\":\"已找到相关内容，正在生成回答...\"}"))) {
                return;
            }

            // Step 4：加载历史对话 + 构建 Prompt
            List<ChatMessage> history = sessionService.getHistory(sessionId);
            String context = parentContextService.buildContext(trimmed);
            String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, trimmed.size(), history);

            // Step 5：流式生成，逐 Token 推送
            StringBuilder fullAnswer = new StringBuilder();

            chatClient.prompt()
                    .system(systemPrompt)
                    .user(question)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        fullAnswer.append(token);
                        if (!sendEvent(emitter, SseEmitter.event().name("token").data(token))) {
                            clientGone.set(true);
                        }
                    })
                    .takeWhile(token -> !clientGone.get())   // 客户端走了就取消上游 LLM 流，不再白花钱
                    .blockLast();  // 等待生成完成

            if (clientGone.get()) {
                log.info("[StreamRAG] 客户端已断开，停止流式生成：sessionId={}，已生成 {} 字",
                        sessionId, fullAnswer.length());
                return;
            }

            // Step 6：保存对话记录
            String answer = fullAnswer.toString();

            List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
            String sourcesJson = sourceBuilder.sourcesToJson(sources);
            sessionService.saveMessage(sessionId, question, answer, sourcesJson,
                    (int) (System.currentTimeMillis() - start));
            sessionService.bindDocumentIfEmpty(sessionId, docId);

            // Step 7：推送来源信息（done 事件）
            String doneData = objectMapper.writeValueAsString(
                    new DonePayload(sources, (int) (System.currentTimeMillis() - start)));
            sendEvent(emitter, SseEmitter.event().name("done").data(doneData));
            completeQuietly(emitter);

        } catch (Exception e) {
            if (clientGone.get()) {
                log.debug("[StreamRAG] 客户端已断开，忽略生成异常：{}", e.getMessage());
                return;
            }
            log.error("[StreamRAG] 流式生成失败：{}", e.getMessage(), e);
            String payload;
            try {
                payload = objectMapper.writeValueAsString(Map.of("message", "生成失败：" + e.getMessage()));
            } catch (Exception ignored) {
                payload = "{\"message\":\"生成失败\"}";
            }
            sendEvent(emitter, SseEmitter.event().name("error").data(payload));
            completeQuietly(emitter);
        }
    }

    /** 推送事件；客户端已断开时返回 false（不抛异常，避免把"用户关页面"打成 ERROR 堆栈）。 */
    private boolean sendEvent(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("[StreamRAG] SSE 推送失败（客户端已断开）：{}", e.getMessage());
            return false;
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 流已结束 / 连接已断开，无需处理
        }
    }

    /**
     * 同步版本（供测试使用）。
     */
    public RagResponse syncQuery(String question, List<Long> kbIds, String sessionId) {
        return syncQuery(question, kbIds, null, sessionId);
    }

    public RagResponse syncQuery(String question, List<Long> kbIds, Long docId, String sessionId) {
        long start = System.currentTimeMillis();
        var candidates = enhancedRetriever.retrieveWithHyde(question, kbIds, docId, 40);
        var rerankOutcome = rerankerService.rerank(question, candidates, 15);
        var filtered = confidenceFilter.filter(rerankOutcome.chunks(), rerankOutcome.reranked());

        if (filtered.isEmpty()) return RagResponse.notFound();

        String sid = sessionId == null
                ? sessionService.getOrCreateSession(null, kbIds, docId)
                : sessionId;
        var trimmed = parentContextService.selectForContext(question, parentContextService.restore(filtered));
        if (trimmed.isEmpty()) return RagResponse.notFound();
        List<ChatMessage> history = sessionService.getHistory(sid);
        String context = parentContextService.buildContext(trimmed);
        String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, trimmed.size(), history);

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();

        List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
        String sourcesJson = sourceBuilder.sourcesToJson(sources);
        int latencyMs = (int) (System.currentTimeMillis() - start);

        sessionService.saveMessage(sid, question, answer, sourcesJson, latencyMs);
        sessionService.bindDocumentIfEmpty(sid, docId);

        return RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .latencyMs(latencyMs)
                .build();
    }

    private void sendNotFound(SseEmitter emitter) {
        String msg = "在知识库中未找到与该问题相关的内容。请尝试用不同关键词提问。";
        sendEvent(emitter, SseEmitter.event().name("token").data(msg));
        sendEvent(emitter, SseEmitter.event().name("done").data("{\"sources\":[]}"));
        completeQuietly(emitter);
    }

    record DonePayload(List<RagResponse.Source> sources, int latencyMs) {}
}
