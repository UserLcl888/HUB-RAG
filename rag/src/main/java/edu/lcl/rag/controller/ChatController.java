package edu.lcl.rag.controller;

import edu.lcl.rag.dto.ApiResponse;
import edu.lcl.rag.dto.ChatRequest;
import edu.lcl.rag.dto.ChatSessionVO;
import edu.lcl.rag.dto.RagResponse;
import edu.lcl.rag.entity.ChatMessage;
import edu.lcl.rag.entity.ChatSession;
import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.repository.ChatMessageRepository;
import edu.lcl.rag.repository.ChatSessionRepository;
import edu.lcl.rag.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final StreamingRagService streamingRagService;
    private final ChatSessionService sessionService;
    private final KnowledgeBaseService kbService;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    // 专用线程池处理 SSE 推送（避免占用 Tomcat 线程池）
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(
            r -> {
                Thread t = new Thread(r);
                t.setName("sse-rag-" + t.getId());
                t.setDaemon(true);
                return t;
            }
    );

    /**
     * 流式问答接口（SSE）。
     * 前端用 EventSource 接收：
     *   const es = new EventSource('/api/v1/chat/stream?sessionId=xxx&kbIds=1,2&question=...')
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String question,
            @RequestParam List<Long> kbIds,
            @RequestParam(required = false) Long docId,
            @RequestParam(required = false) String sessionId) {

        if (sessionId == null || sessionId.isBlank()) {
            validateScope(kbIds, docId);
        }
        // 创建或获取会话
        String sid = sessionService.getOrCreateSession(sessionId, kbIds, docId);
        ChatSessionService.RetrievalScope scope = sessionService.resolveRetrievalScope(sid, kbIds, docId);
        requireDocumentScope(scope);
        validateScope(scope.kbIds(), scope.docId());

        SseEmitter emitter = new SseEmitter(60_000L);  // 60秒超时

        sseExecutor.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("session").data(sid));
                streamingRagService.streamQuery(question, scope.kbIds(), scope.docId(), sid, emitter);
            } catch (Exception e) {
                // 客户端已断开时 emitter 已结束：只记 DEBUG，避免 "ResponseBodyEmitter has already completed"
                // 与写流失败继续升级成 ERROR 堆栈
                if (sendQuietly(emitter, SseEmitter.event()
                        .name("error")
                        .data("{\"message\":\"系统内部错误，请稍后重试\"}"))) {
                    log.error("[Chat] SSE 执行异常：{}", e.getMessage(), e);
                    completeQuietly(emitter);
                } else {
                    log.debug("[Chat] SSE 已断开，忽略收尾推送：{}", e.getMessage());
                }
            }
        });

        return emitter;
    }

    /** 推送事件；流已结束或客户端已断开时返回 false，不抛异常。 */
    private boolean sendQuietly(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 流已结束，无需处理
        }
    }

    /**
     * 同步问答接口（测试用）。
     */
    @PostMapping
    public RagResponse syncChat(@RequestBody ChatRequest request) {
        String sid = sessionService.getOrCreateSession(
                request.getSessionId(), request.getKbIds(), request.getDocId());
        ChatSessionService.RetrievalScope scope = sessionService.resolveRetrievalScope(
                sid, request.getKbIds(), request.getDocId());
        requireDocumentScope(scope);
        validateScope(scope.kbIds(), scope.docId());
        return streamingRagService.syncQuery(request.getQuestion(), scope.kbIds(), scope.docId(), sid);
    }

    /** 获取所有未删除的会话（按最近活跃时间倒序）。 */
    @GetMapping("/sessions")
    public ApiResponse<List<ChatSessionVO>> listSessions() {
        List<ChatSessionVO> sessions = sessionRepository
                .findByIsDeletedFalseOrderByLastActiveAtDesc()
                .stream()
                .map(this::toSessionVO)
                .toList();
        return ApiResponse.ok(sessions);
    }

    /** 获取指定会话的消息列表 */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessage>> getMessages(@PathVariable String sessionId) {
        sessionRepository.findById(sessionId)
                .filter(session -> !Boolean.TRUE.equals(session.getIsDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + sessionId));
        return ApiResponse.ok(
                messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
    }

    private void validateScope(List<Long> kbIds, Long docId) {
        kbService.requireKnowledgeBases(kbIds);
        if (docId != null) {
            KbDocument doc = requireDocumentInScope(kbIds, docId);
            if (doc.getStatus() != edu.lcl.rag.entity.KbDocument.DocumentStatus.DONE) {
                throw new IllegalArgumentException("当前会话文档不可用，请重新新建会话并选择已解析文档");
            }
        }
    }

    private void requireDocumentScope(ChatSessionService.RetrievalScope scope) {
        if (scope.docId() == null) {
            throw new IllegalArgumentException("当前会话文档不可用，请重新新建会话并选择已解析文档");
        }
    }

    private KbDocument requireDocumentInScope(List<Long> kbIds, Long docId) {
        for (Long kbId : kbIds) {
            try {
                return kbService.requireDocument(kbId, docId);
            } catch (IllegalArgumentException ignored) {
                // 继续检查同一会话固定的其他知识库。
            }
        }
        throw new IllegalArgumentException("当前会话文档不可用，请重新新建会话并选择已解析文档");
    }

    private ChatSessionVO toSessionVO(ChatSession session) {
        return ChatSessionVO.builder()
                .id(session.getId())
                .title(session.getTitle())
                .messageCount(session.getMessageCount())
                .lastActiveAt(session.getLastActiveAt())
                .kbIds(parseKnowledgeBaseIds(session.getKbIds()))
                .docId(session.getDocId())
                .build();
    }

    private List<Long> parseKnowledgeBaseIds(String storedKbIds) {
        if (storedKbIds == null || storedKbIds.isBlank()) {
            return List.of();
        }
        String normalized = storedKbIds.replace("[", "").replace("]", "").trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(normalized.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(Long::valueOf)
                    .toList();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("会话知识库范围无效：" + storedKbIds, exception);
        }
    }
}
