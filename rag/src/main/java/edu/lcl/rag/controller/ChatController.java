package edu.lcl.rag.controller;

import edu.lcl.rag.assistant.AssistantDispatchService;
import edu.lcl.rag.assistant.BusinessQueryHandler;
import edu.lcl.rag.assistant.BusinessQueryTool;
import edu.lcl.rag.dto.ApiResponse;
import edu.lcl.rag.dto.ChatRequest;
import edu.lcl.rag.dto.ChatSessionVO;
import edu.lcl.rag.dto.RagResponse;
import edu.lcl.rag.entity.ChatMessage;
import edu.lcl.rag.entity.ChatSession;
import edu.lcl.rag.repository.ChatMessageRepository;
import edu.lcl.rag.repository.ChatSessionRepository;
import edu.lcl.rag.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;

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
    private final AnswerCacheService answerCache;
    private final AssistantDispatchService assistant;
    private final BusinessQueryHandler businessQueries;
    private final HotQuestionCatalog hotQuestions;

    // 专用线程池处理 SSE 推送（避免占用 Tomcat 线程池）
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    public void shutdown() {
        sseExecutor.shutdownNow();
    }

    /**
     * 流式问答接口（SSE）。
     * 前端用 EventSource 接收：
     *   const es = new EventSource('/api/v1/chat/stream?sessionId=xxx&kbIds=1,2&question=...')
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String question,
            @RequestParam(required = false) List<Long> kbIds,
            @RequestParam(required = false) Long docId,
            @RequestParam(required = false) String sessionId,
            HttpServletResponse response) {

        String text = validateQuestion(question);
        // 禁用代理缓冲/转换，避免增量事件被攒成整段后才到达浏览器。
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        // 分类前只检查文字和会话。首次非 RAG 访问不能绑定请求附带的文档范围。
        String sid = sessionService.getOrCreateSession(sessionId, List.of(), null);

        SseEmitter emitter = new SseEmitter(60_000L);  // 60秒超时

        sseExecutor.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("session").data(sid));
                assistant.stream(text, kbIds, docId, sid, emitter);
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
        if (request == null) {
            throw new IllegalArgumentException("问题不能为空");
        }
        String text = validateQuestion(request.getQuestion());
        String sid = sessionService.getOrCreateSession(
                request.getSessionId(), List.of(), null);
        return assistant.sync(text, request.getKbIds(), request.getDocId(), sid);
    }

    /** 查询功能说明目录；只展示后端登记的功能，不开放用户动态注册或任意 URL 调用。 */
    @GetMapping("/capabilities")
    public ApiResponse<List<BusinessQueryTool.Definition>> capabilities() {
        return ApiResponse.ok(businessQueries.definitions());
    }

    /** 首页展示业务快捷问题及资料问题；答案缓存与预热仍只允许资料问题名单。 */
    @GetMapping("/hot-questions")
    public ApiResponse<List<String>> hotQuestions() {
        return ApiResponse.ok(hotQuestions.displayQuestions());
    }

    private String validateQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (question.length() > 2000) {
            throw new IllegalArgumentException("问题过长，请控制在 2000 字以内");
        }
        return question.strip();
    }

    /**
     * 预热答案缓存：把预设热门问题各生成一次并写入 Redis（默认 7 天）。
     * <p>
     * 用法：<code>POST /api/v1/chat/cache/warm?kbId=1</code>，
     * body 可用 {"questions":["...","..."]} 指定热门名单的子集，不接受名单外的问题。
     * 预热**不写会话记录**（persist=false），避免历史对话里多出一堆空会话。
     */
    @PostMapping("/cache/warm")
    public ApiResponse<List<WarmResult>> warmCache(@RequestParam(required = false) Long kbId,
                                                   @RequestBody(required = false) WarmRequest request) {
        List<Long> kbIds = resolveWarmKbIds(kbId);
        if (kbIds.isEmpty()) {
            throw new IllegalArgumentException("还没有知识库，无法预热");
        }
        List<String> questions = (request != null && request.questions() != null && !request.questions().isEmpty())
                ? request.questions() : hotQuestions.questions();
        if (questions.stream().anyMatch(question -> !hotQuestions.contains(question))) {
            throw new IllegalArgumentException("只能预热热门资料问题，业务查询问题不参与答案缓存");
        }
        questions = questions.stream().distinct().toList();

        List<WarmResult> results = new ArrayList<>();
        for (String question : questions) {
            long started = System.currentTimeMillis();
            try {
                RagResponse response = streamingRagService.syncQuery(question, kbIds, null, null, false);
                String answer = response.getAnswer();
                if (answer == null || answer.isBlank() || response.isNotFound()) {
                    results.add(new WarmResult(question, false, 0, 0, "检索为空，未缓存"));
                    continue;
                }
                List<RagResponse.Source> sources =
                        response.getSources() == null ? List.of() : response.getSources();
                answerCache.put(answerCache.buildKey(kbIds, null, question),
                        new AnswerCacheService.CachedAnswer(question, answer, sources,
                                LocalDateTime.now().toString()));
                results.add(new WarmResult(question, true, answer.length(), sources.size(),
                        (System.currentTimeMillis() - started) + "ms"));
            } catch (Exception e) {
                log.warn("[Chat] 预热失败：question={}，error={}", question, e.getMessage());
                results.add(new WarmResult(question, false, 0, 0, "失败：" + e.getMessage()));
            }
        }
        return ApiResponse.ok(results);
    }

    /** 手动清空答案缓存（正常情况下文档变化会自动失效，这里只留一个兜底入口）。 */
    @PostMapping("/cache/clear")
    public ApiResponse<String> clearCache() {
        long removed = answerCache.clearAllKeys();
        answerCache.invalidate();
        return ApiResponse.ok("已删除 " + removed + " 条答案缓存，并使缓存代 +1");
    }

    private List<Long> resolveWarmKbIds(Long kbId) {
        if (kbId != null) {
            kbService.requireKnowledgeBase(kbId);
            return List.of(kbId);
        }
        return kbService.listAll().stream().map(vo -> vo.getId()).findFirst()
                .map(List::of).orElse(List.of());
    }

    /** 预热请求体：不传则用当前配置的全部热门问题。 */
    public record WarmRequest(List<String> questions) {}

    /** 单个问题的预热结果 */
    public record WarmResult(String question, boolean cached, int answerChars, int sourceCount, String detail) {}

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
                messageRepository.findBySessionIdOrderByIdAsc(sessionId));
    }

    /**
     * 重命名会话（左侧历史对话行的"重命名"）：只改 title，检索范围与消息都不动。
     * 前端用法：{@code PATCH /api/v1/chat/sessions/{sessionId}}，body {@code {"title":"新标题"}}。
     */
    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<ChatSessionVO> renameSession(@PathVariable String sessionId,
                                                    @RequestBody(required = false) RenameRequest request) {
        ChatSession session = sessionService.renameSession(sessionId, request == null ? null : request.title());
        return ApiResponse.ok(toSessionVO(session));
    }

    /**
     * 删除会话（左侧历史对话行的"删除"）：软删除，会话从列表与消息接口消失，消息数据保留以便恢复。
     * 前端用法：{@code DELETE /api/v1/chat/sessions/{sessionId}}。
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return ApiResponse.ok(null);
    }

    /** 重命名请求体 */
    public record RenameRequest(String title) {}

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
