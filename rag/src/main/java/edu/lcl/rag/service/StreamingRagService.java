package edu.lcl.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final DirectAnswerService directAnswerService;
    private final edu.lcl.rag.repository.KbDocumentRepository documentRepository;
    private final edu.lcl.rag.repository.KnowledgeBaseRepository knowledgeBaseRepository;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final AnswerCacheService answerCache;

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
            // Step 0：与知识库无关的简单问题（寒暄/身份/算术）直接回答，不检索、不给来源
            if (directAnswerService.shouldAnswerDirectly(question)) {
                streamDirectAnswer(question, sessionId, emitter, clientGone, start);
                return;
            }

            // Step 0.5：命中答案缓存就直接回放（预设热门问题基本都会命中，秒回且不消耗额度）
            String cacheKey = answerCache.buildKey(kbIds, docId, question);
            var cachedAnswer = answerCache.get(cacheKey);
            if (cachedAnswer.isPresent()) {
                replayCached(question, sessionId, emitter, clientGone, start, cachedAnswer.get());
                return;
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

            List<RagResponse.Source> sources = sourceBuilder.buildSources(answer, trimmed);
            String sourcesJson = sourceBuilder.sourcesToJson(sources);
            // 写入答案缓存（下次同一问题直接回放）；文档变化时缓存代 +1 会自动作废
            if (!answer.isBlank()) {
                answerCache.put(cacheKey, new AnswerCacheService.CachedAnswer(
                        question, answer, sources, LocalDateTime.now().toString()));
            }
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

    /**
     * 回放缓存答案：按小块推送 token 保留打字机观感，并照常写入对话记录（历史里能看到这次问答）。
     */
    private void replayCached(String question, String sessionId, SseEmitter emitter,
                              AtomicBoolean clientGone, long start,
                              AnswerCacheService.CachedAnswer cached) throws Exception {
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
        sessionService.saveMessage(sessionId, question, answer,
                sourceBuilder.sourcesToJson(sources), elapsed);
        sendEvent(emitter, SseEmitter.event().name("done").data(
                objectMapper.writeValueAsString(new DonePayload(sources, elapsed))));
        completeQuietly(emitter);
        log.info("[StreamRAG] 命中答案缓存：sessionId={}，字数={}，耗时={}ms",
                sessionId, answer.length(), elapsed);
    }

    /**
     * 直答：与知识库无关的简单问题（寒暄/身份/算术）。
     * 不检索、不构建上下文、不返回来源，但照常存会话记录，保证历史里能看到这条问答。
     */
    private void streamDirectAnswer(String question, String sessionId, SseEmitter emitter,
                                    AtomicBoolean clientGone, long start) throws Exception {
        StringBuilder answer = new StringBuilder();
        directAnswerService.streamAnswer(question)
                .doOnNext(token -> {
                    answer.append(token);
                    if (!sendEvent(emitter, SseEmitter.event().name("token").data(token))) {
                        clientGone.set(true);
                    }
                })
                .takeWhile(token -> !clientGone.get())
                .blockLast();

        if (clientGone.get()) {
            log.info("[StreamRAG] 客户端已断开，停止直答：sessionId={}", sessionId);
            return;
        }

        int elapsed = (int) (System.currentTimeMillis() - start);
        sessionService.saveMessage(sessionId, question, answer.toString(), "[]", elapsed);
        sendEvent(emitter, SseEmitter.event().name("done").data(
                objectMapper.writeValueAsString(new DonePayload(List.of(), elapsed))));
        completeQuietly(emitter);
        log.info("[StreamRAG] 直答完成（未检索知识库）：question={}，耗时={}ms", question, elapsed);
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
        long start = System.currentTimeMillis();
        var candidates = enhancedRetriever.retrieveWithHyde(question, kbIds, docId, 40);
        var rerankOutcome = rerankerService.rerank(question, candidates, 15);
        var filtered = confidenceFilter.filter(rerankOutcome.chunks(), rerankOutcome.reranked());

        if (filtered.isEmpty()) return RagResponse.notFound();

        String sid = sessionId != null
                ? sessionId
                : (persist ? sessionService.getOrCreateSession(null, kbIds, docId) : "warmup");
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

        if (persist) {
            sessionService.saveMessage(sid, question, answer, sourcesJson, latencyMs);
            sessionService.bindDocumentIfEmpty(sid, docId);
        }

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
