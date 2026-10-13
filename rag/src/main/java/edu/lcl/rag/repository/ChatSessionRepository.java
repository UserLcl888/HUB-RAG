package edu.lcl.rag.repository;

import edu.lcl.rag.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    List<ChatSession> findByIsDeletedFalseOrderByLastActiveAtDesc();
}
