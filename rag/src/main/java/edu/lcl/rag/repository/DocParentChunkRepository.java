package edu.lcl.rag.repository;

import edu.lcl.rag.entity.DocParentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 父块数据访问：仅按 ID、文档和版本读取，不参与向量/全文检索。
 * @author Codex
 * @since 2026-10-12
 */
public interface DocParentChunkRepository extends JpaRepository<DocParentChunk, Long> {
    List<DocParentChunk> findByDocIdAndDocVersionOrderByParentIndexAsc(Long docId, Integer docVersion);

    @Modifying
    @Query("DELETE FROM DocParentChunk p WHERE p.docId = :docId")
    void deleteByDocId(@Param("docId") Long docId);

    @Modifying
    @Query("DELETE FROM DocParentChunk p WHERE p.docId = :docId AND p.docVersion < :version")
    void deleteByDocIdAndDocVersionLessThan(@Param("docId") Long docId, @Param("version") Integer version);
}
