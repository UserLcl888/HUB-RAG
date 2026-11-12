package edu.lcl.rag.entity;

import edu.lcl.rag.assistant.Intent;
import edu.lcl.rag.assistant.ConversationState;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "kb_chat_message")
@Data
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Intent intent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conversation_state", columnDefinition = "jsonb")
    private ConversationState conversationState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String sources;

    private Integer latencyMs;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
