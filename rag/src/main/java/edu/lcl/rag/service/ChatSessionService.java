package edu.lcl.rag.service;

import edu.lcl.rag.assistant.Intent;
import edu.lcl.rag.assistant.ConversationState;
import edu.lcl.rag.entity.ChatMessage;
import edu.lcl.rag.entity.ChatSession;
import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.repository.KbDocumentRepository;
import edu.lcl.rag.repository.ChatMessageRepository;
import edu.lcl.rag.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final KbDocumentRepository documentRepository;

    /**
     * 旧会话 docId 为空时，是否自动补一份"最新上传的已解析文档"。
     * false（默认）：docId 为空就是"整个知识库"，不再把范围偷偷缩成一份文档。
     */
    @Value("${rag.chat.auto-bind-document:false}")
    private boolean autoBindDocument;
    /** 会话标题长度上限，与 kb_chat_session.title 的 VARCHAR(200) 对齐 */
    private static final int MAX_TITLE_LENGTH = 200;

    /**
     * 获取已有会话，或创建新会话。
     */
    @Transactional
    public String getOrCreateSession(String sessionId, List<Long> kbIds) {
        return getOrCreateSession(sessionId, kbIds, null);
    }

    /**
     * 获取或创建会话，并固定会话的知识库及文档检索范围。
     *
     * @param sessionId 已有会话 ID，可为空
     * @param kbIds 请求携带的知识库 ID
     * @param docId 请求携带的文档 ID，可为空
     * @return 有效会话 ID
     */
    @Transactional
    public String getOrCreateSession(String sessionId, List<Long> kbIds, Long docId) {
        if (sessionId != null && !sessionId.isBlank()) {
            ChatSession session = sessionRepository.findById(sessionId)
                    .filter(s -> !Boolean.TRUE.equals(s.getIsDeleted()))
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + sessionId));
            session.setLastActiveAt(LocalDateTime.now());
            sessionRepository.save(session);
            return sessionId;
        }

        // 创建新会话
        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setKbIds((kbIds == null ? List.<Long>of() : kbIds).toString());
        session.setDocId(docId);
        session.setMessageCount(0);
        sessionRepository.save(session);

        log.info("[ChatSession] 新建会话：sessionId={}", session.getId());
        return session.getId();
    }

    /**
     * 获取会话固定的知识库及文档范围。
     * docId 为空 = 检索整个知识库；只有显式开启 rag.chat.auto-bind-document 才会自动补一份文档。
     *
     * @param sessionId 会话 ID
     * @param requestKbIds 请求携带的知识库 ID，仅用于兼容旧调用方
     * @param requestDocId 请求携带的文档 ID，仅用于兼容旧调用方
     * @return 会话检索范围
     */
    @Transactional
    public RetrievalScope resolveRetrievalScope(String sessionId, List<Long> requestKbIds,
                                                 Long requestDocId) {
        ChatSession session = sessionRepository.findById(sessionId)
                .filter(item -> !Boolean.TRUE.equals(item.getIsDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + sessionId));
        List<Long> kbIds = parseKnowledgeBaseIds(session.getKbIds());
        Long docId = session.getDocId();
        // 先闲聊/追问的会话未绑定范围，第一次文档提问才接受页面选择。
        if (kbIds.isEmpty()) {
            kbIds = requestKbIds == null ? List.of() : List.copyOf(requestKbIds);
            docId = requestDocId;
        }
        if (docId == null && autoBindDocument) {
            docId = findFallbackDocument(kbIds.toString());
        }
        return new RetrievalScope(kbIds, docId);
    }

    /** 仅在 RAG 范围验证成功后绑定；非 RAG 不调用，不覆盖已绑定的范围。 */
    @Transactional
    public void bindRetrievalScopeIfEmpty(String sessionId, RetrievalScope scope) {
        ChatSession session = requireActiveSession(sessionId);
        if (parseKnowledgeBaseIds(session.getKbIds()).isEmpty()) {
            session.setKbIds(scope.kbIds().toString());
            session.setDocId(scope.docId());
            sessionRepository.save(session);
        }
    }

    /**
     * 在回答成功后为旧会话补齐自动选择的文档。
     *
     * @param sessionId 会话 ID
     * @param docId     文档 ID
     */
    @Transactional
    public void bindDocumentIfEmpty(String sessionId, Long docId) {
        if (docId == null) {
            return;
        }
        sessionRepository.findById(sessionId)
                .filter(session -> !Boolean.TRUE.equals(session.getIsDeleted()))
                .ifPresent(session -> {
                    if (session.getDocId() == null) {
                        session.setDocId(docId);
                        sessionRepository.save(session);
                    }
                });
    }

    /**
     * 重命名会话：只改标题，不动检索范围、文档绑定与消息。
     * 标题为空视为无效；超过列宽（{@code kb_chat_session.title} VARCHAR(200)）按列宽截断，避免写库报错。
     *
     * @param sessionId 会话 ID
     * @param title     新标题
     * @return 更新后的会话
     */
    @Transactional
    public ChatSession renameSession(String sessionId, String title) {
        ChatSession session = requireActiveSession(sessionId);
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("会话标题不能为空");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            normalized = normalized.substring(0, MAX_TITLE_LENGTH);
        }
        session.setTitle(normalized);
        ChatSession saved = sessionRepository.save(session);
        log.info("[ChatSession] 会话重命名：sessionId={}，title={}", sessionId, normalized);
        return saved;
    }

    /**
     * 删除会话：软删除（is_deleted = true）。
     * <p>
     * 消息表没有软删字段，这里**不物理删除消息**——会话已从列表和消息接口里消失，
     * 误删时数据仍在库里可恢复；如果确认要一并清理消息，再单独加一条 delete by sessionId。
     *
     * @param sessionId 会话 ID
     */
    @Transactional
    public void deleteSession(String sessionId) {
        ChatSession session = requireActiveSession(sessionId);
        session.setIsDeleted(true);
        sessionRepository.save(session);
        log.info("[ChatSession] 会话已删除（软删）：sessionId={}", sessionId);
    }

    /** 取未删除的会话，不存在（或已删除）一律按"会话不存在"处理。 */
    private ChatSession requireActiveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        return sessionRepository.findById(sessionId)
                .filter(item -> !Boolean.TRUE.equals(item.getIsDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + sessionId));
    }

    private Long findFallbackDocument(String storedKbIds) {
        List<Long> kbIds = parseKnowledgeBaseIds(storedKbIds);
        if (kbIds.isEmpty()) {
            return null;
        }
        return documentRepository
                .findFirstByKbIdInAndStatusAndIsDeletedFalseOrderByUploadedAtDesc(
                        kbIds, KbDocument.DocumentStatus.DONE)
                .map(KbDocument::getId)
                .orElse(null);
    }

    private List<Long> parseKnowledgeBaseIds(String storedKbIds) {
        if (storedKbIds == null || storedKbIds.isBlank()) {
            return new ArrayList<>();
        }
        String normalized = storedKbIds.replace("[", "").replace("]", "").trim();
        if (normalized.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return java.util.Arrays.stream(normalized.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(Long::valueOf)
                    .toList();
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("会话知识库范围无效：" + storedKbIds, exception);
        }
    }

    /**
     * 会话固定的知识库与文档检索范围。
     *
     * @param kbIds 知识库 ID
     * @param docId 文档 ID
     */
    public record RetrievalScope(List<Long> kbIds, Long docId) {
    }

    /**
     * 保存一轮对话（用户问题 + 助手回答）。
     */
    @Transactional
    public void saveMessage(String sessionId, String question, String answer,
                             String sourcesJson, int latencyMs) {
        saveMessage(sessionId, question, answer, sourcesJson, latencyMs, Intent.RAG);
    }

    @Transactional
    public void saveMessage(String sessionId, String question, String answer,
                            String sourcesJson, int latencyMs, Intent intent) {
        saveTurn(sessionId, question, List.of(new AssistantReply(answer, sourcesJson, latencyMs, intent)));
    }

    /** 混合轮只保存一次用户原话，各助手子回答分别保存 intent 和后端确定的会话状态。 */
    @Transactional
    public void saveTurn(String sessionId, String question, List<AssistantReply> replies) {
        // 每轮携带一份当前状态，避免十轮历史窗口挤掉已选对象；不覆盖历史消息。
        ConversationState active = ConversationState.latestBusinessContext(getHistory(sessionId));
        // 保存用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("USER");
        userMsg.setContent(question);
        messageRepository.save(userMsg);

        for (AssistantReply reply : replies) {
            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setSessionId(sessionId);
            assistantMsg.setRole("ASSISTANT");
            assistantMsg.setContent(reply.answer());
            assistantMsg.setIntent(reply.intent());
            ConversationState state = reply.conversationState();
            if (state != null && state.context().isEmpty() && active != null
                    && (state.kind() == edu.lcl.rag.assistant.IntentDecision.Kind.UNAVAILABLE
                    || state.action() == edu.lcl.rag.assistant.IntentDecision.Action.CLARIFY
                    || state.kind() == edu.lcl.rag.assistant.IntentDecision.Kind.ACKNOWLEDGEMENT)) {
                Map<String, Object> preserved = new LinkedHashMap<>(active.context());
                preserved.putIfAbsent("activeBusinessTool", active.toolId() == null ? "" : active.toolId());
                state = new ConversationState(state.intent(), state.action(), state.kind(), state.query(),
                        state.toolId(), state.arguments(), state.missingParameter(), state.clarification(),
                        state.executionState(), preserved);
            }
            assistantMsg.setConversationState(state);
            active = state != null && !state.context().isEmpty() ? state : null;
            assistantMsg.setSources(reply.intent() == Intent.RAG ? reply.sourcesJson() : "[]");
            assistantMsg.setLatencyMs(reply.latencyMs());
            messageRepository.save(assistantMsg);
        }

        // 更新会话消息数和活跃时间
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setMessageCount(s.getMessageCount() + 1 + replies.size());
            s.setLastActiveAt(LocalDateTime.now());
            if (s.getTitle() == null && question.length() > 0) {
                s.setTitle(question.substring(0, Math.min(50, question.length())));
            }
            sessionRepository.save(s);
        });
    }

    public record AssistantReply(String answer, String sourcesJson, int latencyMs, Intent intent,
                                 ConversationState conversationState) {
        public AssistantReply(String answer, String sourcesJson, int latencyMs, Intent intent) {
            this(answer, sourcesJson, latencyMs, intent, null);
        }
    }

    /**
     * 获取会话历史（用于多轮对话上下文）。
     * 最近十个用户轮次及其全部子回答，不含当前问题；混合轮不按“两条消息”错误截断。
     */
    public List<ChatMessage> getHistory(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        List<ChatMessage> questions = messageRepository.findTop10BySessionIdAndRoleOrderByIdDesc(sessionId, "USER");
        return questions.isEmpty() ? List.of() : messageRepository
                .findBySessionIdAndIdGreaterThanEqualOrderByIdAsc(sessionId, questions.getLast().getId());
    }

    /** 会话元问题读取完整历史；问题定位由后端处理、语言由模型组织，总结限制上下文预算。 */
    public List<ChatMessage> getAllHistory(String sessionId) {
        return sessionId == null ? List.of() : messageRepository.findBySessionIdOrderByIdAsc(sessionId);
    }

    /** 兼容旧调用：返回最近十轮中的 RAG 消息；新调用应按当前问题做关联记忆选择。 */
    public List<ChatMessage> getRagHistory(String sessionId) {
        List<ChatMessage> result = new ArrayList<>();
        ChatMessage user = null;
        for (ChatMessage message : getHistory(sessionId)) {
            if ("USER".equals(message.getRole())) {
                user = message;
            } else if ("ASSISTANT".equals(message.getRole()) && message.getIntent() == Intent.RAG) {
                if (user != null) {
                    result.add(user);
                }
                result.add(message);
                user = null;
            }
        }
        return result;
    }
}
