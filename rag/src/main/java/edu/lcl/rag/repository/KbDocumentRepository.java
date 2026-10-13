package edu.lcl.rag.repository;

import edu.lcl.rag.entity.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM KbDocument d WHERE d.id = :docId")
    Optional<KbDocument> findByIdForIndexLock(@Param("docId") Long docId);

    @Modifying
    @Transactional
    @Query("UPDATE KbDocument d SET d.status = :status, d.errorMsg = :errorMsg "
            + "WHERE d.id = :docId AND d.version = :version AND d.isDeleted = false")
    int updateIndexStatus(@Param("docId") Long docId, @Param("version") Integer version,
                          @Param("status") KbDocument.DocumentStatus status,
                          @Param("errorMsg") String errorMsg);

    List<KbDocument> findByKbIdAndIsDeletedFalse(Long kbId);

    Optional<KbDocument> findFirstByKbIdInAndStatusAndIsDeletedFalseOrderByUploadedAtDesc(
            List<Long> kbIds, KbDocument.DocumentStatus status);

    @Query("SELECT COUNT(d) FROM KbDocument d WHERE d.status = :status")
    long countByStatus(KbDocument.DocumentStatus status);
}
