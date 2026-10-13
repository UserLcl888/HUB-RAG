package edu.lcl.rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "kb_chat_session")
@Data
public class ChatSession {

    @Id
    private String id;

    @Column(nullable = false)
    private String kbIds;

    @Column(name = "doc_id")
    private Long docId;

    private String title;

    @Column(nullable = false)
    private Integer messageCount = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastActiveAt = LocalDateTime.now();

    @Column(nullable = false)
    private Boolean isDeleted = false;
}
