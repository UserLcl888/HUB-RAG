package edu.lcl.rag.service;

import edu.lcl.rag.entity.DocChunk;
import edu.lcl.rag.entity.DocParentChunk;
import edu.lcl.rag.entity.IndexTask;
import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.repository.DocChunkRepository;
import edu.lcl.rag.repository.DocParentChunkRepository;
import edu.lcl.rag.repository.IndexTaskRepository;
import edu.lcl.rag.repository.KbDocumentRepository;
import edu.lcl.rag.service.splitter.ChunkResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 事务性写入完整的一版索引；失败时旧版本父子块不受影响。
 * @author Codex
 * @since 2026-10-12
 */
@Service
@RequiredArgsConstructor
public class IndexWriteService {
    private final DocParentChunkRepository parentRepository;
    private final DocChunkRepository childRepository;
    private final KbDocumentRepository documentRepository;
    private final IndexTaskRepository taskRepository;

    /**
     * 先写父块拿主键、再写带 parent_id 的子块，最后清理旧版并更新文档状态。
     * @param taskId 索引任务 ID
     * @param docId 文档 ID
     * @param expectedVersion 索引开始时的文档版本
     * @param parents 非向量化的父块
     * @param children 已向量化的子块
     */
    @Transactional
    public void replace(Long taskId, Long docId, Integer expectedVersion,
                        List<ParentChunkService.ParentBlock> parents, List<IndexedChild> children) {
        KbDocument doc = documentRepository.findByIdForIndexLock(docId).orElseThrow();
        if (!expectedVersion.equals(doc.getVersion()) || Boolean.TRUE.equals(doc.getIsDeleted())) {
            throw new IllegalStateException("索引任务已过期，文档已更新或删除");
        }
        List<DocParentChunk> savedParents = new ArrayList<>();
        for (int index = 0; index < parents.size(); index++) {
            var block = parents.get(index);
            DocParentChunk parent = new DocParentChunk();
            parent.setDocId(docId);
            parent.setKbId(doc.getKbId());
            parent.setParentIndex(index);
            parent.setContent(block.content());
            parent.setSectionTitle(block.sectionTitle());
            parent.setPageNum(block.pageNum());
            parent.setDocVersion(expectedVersion);
            savedParents.add(parentRepository.save(parent));
        }

        List<DocChunk> savedChildren = new ArrayList<>();
        int tokenCount = 0;
        for (IndexedChild indexed : children) {
            ChunkResult child = indexed.chunk();
            DocChunk row = new DocChunk();
            row.setDocId(docId);
            row.setKbId(doc.getKbId());
            row.setParentId(savedParents.get(indexed.parentIndex()).getId());
            row.setChunkIndex(savedChildren.size());
            row.setContent(child.getContent());
            row.setEmbedding(indexed.embedding());
            row.setPageNum(child.getPageNum());
            row.setSectionTitle(child.getSectionTitle());
            row.setTokenCount(child.getEstimatedTokens());
            row.setDocVersion(expectedVersion);
            savedChildren.add(row);
            tokenCount += child.getEstimatedTokens();
        }
        childRepository.saveAll(savedChildren);
        // 清理先子后父，保证兼容后续的 parent_id 外键。
        childRepository.deleteByDocIdAndDocVersionLessThan(docId, expectedVersion);
        parentRepository.deleteByDocIdAndDocVersionLessThan(docId, expectedVersion);

        doc.setStatus(KbDocument.DocumentStatus.DONE);
        doc.setChunkCount(savedChildren.size());
        doc.setTokenCount(tokenCount);
        doc.setIndexedAt(LocalDateTime.now());
        doc.setErrorMsg(null);
        documentRepository.save(doc);
        IndexTask task = taskRepository.findById(taskId).orElseThrow();
        task.setStatus(IndexTask.TaskStatus.DONE);
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);
    }

    /** 一个子块与其父块顺序号及向量。 */
    public record IndexedChild(int parentIndex, ChunkResult chunk, float[] embedding) {}
}
