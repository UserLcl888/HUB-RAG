package edu.lcl.rag.service;

import edu.lcl.rag.entity.ChatMessage;
import edu.lcl.rag.entity.ChatSession;
import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.repository.KbDocumentRepository;
import edu.lcl.rag.repository.ChatMessageRepository;
import edu.lcl.rag.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final KbDocumentRepository documentRepository;
    /** 最多保留的历史轮数（超出后截断旧消息） */
    private static final int MAX_HISTORY_ROUNDS = 5;

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
     * 获取会话固定的知识库及文档范围；旧会话会自动补齐默认文档。
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
        if (docId == null) {
            docId = findFallbackDocument(session.getKbIds());
        }
        return new RetrievalScope(kbIds, docId);
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
        // 保存用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("USER");
        userMsg.setContent(question);
        messageRepository.save(userMsg);

        // 保存助手回答
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSessionId(sessionId);
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setContent(answer);
        assistantMsg.setSources(sourcesJson);
        assistantMsg.setLatencyMs(latencyMs);
        messageRepository.save(assistantMsg);

        // 更新会话消息数和活跃时间
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setMessageCount(s.getMessageCount() + 2);
            s.setLastActiveAt(LocalDateTime.now());
            if (s.getTitle() == null && question.length() > 0) {
                s.setTitle(question.substring(0, Math.min(50, question.length())));
            }
            sessionRepository.save(s);
        });
    }

    /**
     * 获取会话历史（用于多轮对话上下文）。
     * 最近 MAX_HISTORY_ROUNDS 轮，不含当前问题。
     */
    public List<ChatMessage> getHistory(String sessionId) {
        List<ChatMessage> all = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId);

        // 取最近的 N 轮（N * 2 条消息：用户 + 助手）
        int maxMessages = MAX_HISTORY_ROUNDS * 2;
        if (all.size() > maxMessages) {
            all = all.subList(all.size() - maxMessages, all.size());
        }
        return all;
    }
}
