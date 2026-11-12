package edu.lcl.rag.assistant;

import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.service.ChatSessionService;
import edu.lcl.rag.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/** 只在 RAG 分支运行，非 RAG 不校验也不变更会话的检索范围。 */
@Service
@RequiredArgsConstructor
public class RagScopeService {

    private final ChatSessionService sessions;
    private final KnowledgeBaseService knowledgeBases;

    @Value("${rag.chat.require-document-scope:false}")
    private boolean requireDocumentScope;

    @Value("${rag.chat.default-kb-id:2}")
    private long defaultKbId;

    public ChatSessionService.RetrievalScope resolve(String sessionId, List<Long> kbIds, Long docId) {
        var scope = sessions.resolveRetrievalScope(sessionId, kbIds, docId);
        if (scope.kbIds().isEmpty()) {
            var bases = knowledgeBases.listAll();
            Long fallback = bases.stream().filter(base -> base.getId() == defaultKbId)
                    .map(base -> base.getId()).findFirst()
                    .orElseGet(() -> bases.isEmpty() ? null : bases.getFirst().getId());
            if (fallback == null) {
                throw new IllegalArgumentException("文档问答需要有效知识库，请先上传并解析资料，再提出文档问题。");
            }
            scope = new ChatSessionService.RetrievalScope(List.of(fallback), scope.docId());
        }
        knowledgeBases.requireKnowledgeBases(scope.kbIds());
        if (requireDocumentScope && scope.docId() == null) {
            throw new IllegalArgumentException("文档问答需要选择一份有效的已解析文档。");
        }
        if (scope.docId() != null) {
            validateDocument(scope);
        }
        sessions.bindRetrievalScopeIfEmpty(sessionId, scope);
        return scope;
    }

    private void validateDocument(ChatSessionService.RetrievalScope scope) {
        for (Long kbId : scope.kbIds()) {
            try {
                KbDocument document = knowledgeBases.requireDocument(kbId, scope.docId());
                if (document.getStatus() == KbDocument.DocumentStatus.DONE) {
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // 不跨出本会话范围寻找文档。
            }
        }
        throw new IllegalArgumentException("当前会话文档已不可用，请新建会话并选择已解析文档。"
                + "这不影响继续闲聊或提出业务数据查询。");
    }
}
