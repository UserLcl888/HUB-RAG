package edu.lcl.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 父块只存放阅读/生成上下文，不生成向量，也不参与检索。
 * @author Codex
 * @since 2026-10-12
 */
@Entity
@Table(name = "kb_doc_parent")
@Data
public class DocParentChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "parent_index", nullable = false)
    private Integer parentIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "page_num")
    private Integer pageNum;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Column(name = "doc_version", nullable = false)
    private Integer docVersion;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
