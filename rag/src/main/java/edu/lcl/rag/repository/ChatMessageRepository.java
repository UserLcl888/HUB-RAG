package edu.lcl.rag.repository;

import edu.lcl.rag.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByIdAsc(String sessionId);

    List<ChatMessage> findTop10BySessionIdAndRoleOrderByIdDesc(String sessionId, String role);

    List<ChatMessage> findBySessionIdAndIdGreaterThanEqualOrderByIdAsc(String sessionId, Long firstId);
}
