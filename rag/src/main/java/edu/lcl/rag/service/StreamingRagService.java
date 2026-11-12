package edu.lcl.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.lcl.rag.assistant.Intent;
import edu.lcl.rag.assistant.AnswerComposer;
import edu.lcl.rag.assistant.ConversationMemorySelector;
import edu.lcl.rag.dto.RagResponse;
import edu.lcl.rag.entity.ChatMessage;
import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
    private final edu.lcl.rag.repository.KbDocumentRepository documentRepository;
    private final edu.lcl.rag.repository.KnowledgeBaseRepository knowledgeBaseRepository;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final AnswerCacheService answerCache;
    private final HotQuestionCatalog hotQuestions;
    private final ConversationMemorySelector memory;
    private final AnswerComposer composer;

    /**
     * 流式 RAG 查询，通过 SSE 推送结果。
     */
    public void streamQuery(String question, List<Long> kbIds, String sessionId, SseEmitter emitter) {
        streamQuery(question, kbIds, null, sessionId, emitter);
    }

    public void streamQuery(String question, List<Long> kbIds, Long docId,
                            String sessionId, SseEmitter emitter) {
        streamQuery(question, question, kbIds, docId, sessionId, emitter);
    }

    /** 检索可用明确承接上下文的完整问题，历史始终保存用户原话。 */
    public void streamQuery(String originalQuestion, String question, List<Long> kbIds, Long docId,
                            String sessionId, SseEmitter emitter) {
        streamQuery(originalQuestion, question, kbIds, docId, sessionId, emitter, null);
    }

    /** 混合请求由调度层统一落库和结束连接，此回调只接收已经完成的文档回答。 */
    public void streamQuery(String originalQuestion, String question, List<Long> kbIds, Long docId,
                            String sessionId, SseEmitter emitter, StreamCompletion completion) {
        long start = System.currentTimeMillis();
        // 客户端断开（关页面 / 刷新 / 切会话）是正常事件：标记后停止推送与生成，
        // 并以 INFO/DEBUG 记录，不再抛异常打断主流程（否则会级联出一串 ERROR 堆栈）。
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onCompletion(() -> clientGone.set(true));
        emitter.onError(e -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));

        try {
            List<ChatMessage> recentHistory = sessionService.getHistory(sessionId);
            // 公共答案仅用于无历史、未改写、非混合、全库范围的热门问题。
            // 保守跳过所有已有历史的会话，避免无法证明“独立提问”时复用公共答案。
            boolean cacheEligible = completion == null && recentHistory.isEmpty() && docId == null
                    && kbIds != null && !kbIds.isEmpty() && hotQuestions.contains(originalQuestion)
                    && AnswerCacheService.normalize(originalQuestion).equals(AnswerCacheService.normalize(question));
            String cacheKey = cacheEligible ? answerCache.buildKey(kbIds, docId, question) : null;
            if (cacheEligible) {
                var cachedAnswer = answerCache.get(cacheKey);
                if (cachedAnswer.isPresent()) {
                    replayCached(originalQuestion, sessionId, emitter, clientGone, start, cachedAnswer.get(), completion);
                    return;
                }
            }

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
                sendNotFound(originalQuestion, question, sessionId, emitter, start, completion);
                return;
            }

            var trimmed = parentContextService.selectForContext(question, parentContextService.restore(filtered));
            if (trimmed.isEmpty()) {
                sendNotFound(originalQuestion, question, sessionId, emitter, start, completion);
                return;
            }

            // Step 3：推送"生成中"状态
            if (!sendEvent(emitter, SseEmitter.event()
                    .name("status")
                    .data("{\"type\":\"GENERATING\",\"message\":\"已找到相关内容，正在生成回答...\"}"))) {
                return;
            }

            // Step 4：加载历史对话 + 构建 Prompt
            List<ChatMessage> history = memory.selectForRag(question, recentHistory);
            String context = parentContextService.buildContext(trimmed);
            String systemPrompt = RagPromptTemplate.buildSystemPrompt(
                    context, trimmed.size(), history, sessionInfo(docId, kbIds));

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
            if (answer.isBlank()) {
                throw new IllegalStateException("文档生成返回空内容");
            }

            List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
            // 只有独立热门问题允许写公共答案缓存；普通问题、追问和混合请求不读也不写。
            if (cacheEligible) {
                answerCache.put(cacheKey, new AnswerCacheService.CachedAnswer(
                        question, answer, sources, LocalDateTime.now().toString()));
            }
            sessionService.bindDocumentIfEmpty(sessionId, docId);
            finishResponse(originalQuestion, sessionId, answer, sources,
                    (int) (System.currentTimeMillis() - start), emitter, completion);

        } catch (Exception e) {
            if (clientGone.get()) {
                log.debug("[StreamRAG] 客户端已断开，忽略生成异常：{}", e.getMessage());
                return;
            }
            log.error("[StreamRAG] 流式生成失败：{}", e.getMessage(), e);
            if (completion != null) {
                try {
                    completion.failed("文档解答失败：本段回答未完成，请稍后重试。"
                            + "若上方已有生成片段，请勿将其作为完整结论。");
                    return;
                } catch (RuntimeException failedCompletion) {
                    log.error("[StreamRAG] 混合回答收尾失败：{}", failedCompletion.getClass().getSimpleName());
                }
            }
            String payload;
            try {
                payload = objectMapper.writeValueAsString(Map.of("message", "文档回答生成失败，请稍后重试。"));
            } catch (Exception ignored) {
                payload = "{\"message\":\"生成失败\"}";
            }
            sendEvent(emitter, SseEmitter.event().name("error").data(payload));
            completeQuietly(emitter);
        }
    }

    /**
     * 回放缓存答案：按小块推送 token 保留打字机观感，并照常写入对话记录（历史里能看到这次问答）。
     */
    private void replayCached(String question, String sessionId, SseEmitter emitter,
                              AtomicBoolean clientGone, long start,
                              AnswerCacheService.CachedAnswer cached, StreamCompletion completion) throws Exception {
        if (!sendEvent(emitter, SseEmitter.event()
                .name("status")
                .data("{\"type\":\"CACHE\",\"message\":\"正在读取已缓存的回答...\"}"))) {
            return;
        }
        String answer = cached.getAnswer() == null ? "" : cached.getAnswer();
        List<RagResponse.Source> sources = cached.getSources() == null ? List.of() : cached.getSources();

        final int chunkSize = 24;
        for (int index = 0; index < answer.length(); index += chunkSize) {
            if (clientGone.get()) {
                log.info("[StreamRAG] 缓存回放中客户端断开：sessionId={}", sessionId);
                return;
            }
            String chunk = answer.substring(index, Math.min(answer.length(), index + chunkSize));
            if (!sendEvent(emitter, SseEmitter.event().name("token").data(chunk))) {
                clientGone.set(true);
                return;
            }
            Thread.sleep(16L);   // 一小段延时换"逐字输出"的观感，整条仍在 1 秒内
        }

        int elapsed = (int) (System.currentTimeMillis() - start);
        finishResponse(question, sessionId, answer, sources, elapsed, emitter, completion);
        log.info("[StreamRAG] 命中答案缓存：sessionId={}，字数={}，耗时={}ms",
                sessionId, answer.length(), elapsed);
    }

    /**
     * 会话信息：让"我在问哪个文档 / 当前范围是什么"这类元问题有据可答。
     * docId 为空表示会话固定在"整个知识库"上，此时报出库名和已解析资料份数。
     */
    private String sessionInfo(Long docId, List<Long> kbIds) {
        if (docId != null) {
            return documentRepository.findById(docId)
                    .map(doc -> "当前文档：《" + doc.getFileName() + "》")
                    .orElse("");
        }
        if (kbIds == null || kbIds.isEmpty()) {
            return "";
        }
        String scope = knowledgeBaseRepository.findAllById(kbIds).stream()
                .map(KnowledgeBase::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining("》《"));
        if (scope.isEmpty()) {
            return "";
        }
        long parsed = 0;
        for (Long kbId : kbIds) {
            parsed += documentRepository.findByKbIdAndIsDeletedFalse(kbId).stream()
                    .filter(doc -> doc.getStatus() == KbDocument.DocumentStatus.DONE)
                    .count();
        }
        return "当前检索范围：《" + scope + "》整个知识库（共 " + parsed
                + " 份已解析资料），没有限定单个文件。";
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
        return syncQuery(question, kbIds, docId, sessionId, true);
    }

    /**
     * 同步问答。
     *
     * @param persist 是否写入会话记录。缓存预热传 false：只生成答案，不在历史对话里留下会话
     */
    public RagResponse syncQuery(String question, List<Long> kbIds, Long docId, String sessionId,
                                 boolean persist) {
        return syncQuery(question, question, kbIds, docId, sessionId, persist);
    }

    public RagResponse syncQuery(String originalQuestion, String question, List<Long> kbIds,
                                 Long docId, String sessionId, boolean persist) {
        long start = System.currentTimeMillis();
        String sid = sessionId != null
                ? sessionId
                : (persist ? sessionService.getOrCreateSession(null, kbIds, docId) : null);
        var candidates = enhancedRetriever.retrieveWithHyde(question, kbIds, docId, 40);
        var rerankOutcome = rerankerService.rerank(question, candidates, 15);
        var filtered = confidenceFilter.filter(rerankOutcome.chunks(), rerankOutcome.reranked());

        if (filtered.isEmpty()) {
            return persistNotFound(originalQuestion, question, sid, start, persist);
        }
        var trimmed = parentContextService.selectForContext(question, parentContextService.restore(filtered));
        if (trimmed.isEmpty()) {
            return persistNotFound(originalQuestion, question, sid, start, persist);
        }
        List<ChatMessage> history = memory.selectForRag(question, sessionService.getHistory(sid));
        String context = parentContextService.buildContext(trimmed);
        String systemPrompt = RagPromptTemplate.buildSystemPrompt(context, trimmed.size(), history);

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();
        if (answer == null || answer.isBlank()) {
            throw new IllegalStateException("文档生成返回空内容");
        }

        List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
        String sourcesJson = sourceBuilder.sourcesToJson(sources);
        int latencyMs = (int) (System.currentTimeMillis() - start);

        if (persist) {
            sessionService.saveMessage(sid, originalQuestion, answer, sourcesJson, latencyMs);
            sessionService.bindDocumentIfEmpty(sid, docId);
        }

        return RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .latencyMs(latencyMs)
                .intent(Intent.RAG)
                .sessionId(sid)
                .build();
    }

    private RagResponse persistNotFound(String originalQuestion, String question, String sessionId,
                                        long start, boolean persist) {
        RagResponse response = RagResponse.builder().answer(composer.noEvidence(question))
                .sources(List.of()).notFound(true).intent(Intent.RAG).sessionId(sessionId)
                .latencyMs((int) (System.currentTimeMillis() - start)).build();
        if (persist) {
            sessionService.saveMessage(sessionId, originalQuestion, response.getAnswer(), "[]", response.getLatencyMs());
        }
        return response;
    }

    private void sendNotFound(String originalQuestion, String question, String sessionId, SseEmitter emitter,
                              long start, StreamCompletion completion) throws Exception {
        String answer = composer.noEvidence(question);
        if (!sendEvent(emitter, SseEmitter.event().name("token").data(answer))) {
            return;
        }
        int elapsed = (int) (System.currentTimeMillis() - start);
        finishResponse(originalQuestion, sessionId, answer, List.of(), elapsed, emitter, completion);
    }

    private void finishResponse(String question, String sessionId, String answer,
                                List<RagResponse.Source> sources, int elapsed, SseEmitter emitter,
                                StreamCompletion completion) throws Exception {
        if (completion != null) {
            completion.complete(RagResponse.builder().answer(answer).sources(sources).latencyMs(elapsed)
                    .intent(Intent.RAG).sessionId(sessionId).build());
            return;
        }
        sessionService.saveMessage(sessionId, question, answer, sourceBuilder.sourcesToJson(sources), elapsed);
        sendEvent(emitter, SseEmitter.event().name("done").data(
                objectMapper.writeValueAsString(new DonePayload(sources, elapsed))));
        completeQuietly(emitter);
    }

    @FunctionalInterface
    public interface StreamCompletion {
        void complete(RagResponse response) throws Exception;

        default void failed(String message) {
            throw new IllegalStateException(message);
        }
    }

    record DonePayload(List<RagResponse.Source> sources, int latencyMs, Intent intent) {
        DonePayload(List<RagResponse.Source> sources, int latencyMs) {
            this(sources, latencyMs, Intent.RAG);
        }
    }
}
