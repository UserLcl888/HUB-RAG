package edu.lcl.rag.assistant;

import edu.lcl.rag.entity.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/** 防止路由模型把当前问题错误改写成无关历史问题。 */
@Component
public class QueryConsistencyValidator {

    private final ConversationMemorySelector memory;

    public QueryConsistencyValidator(ConversationMemorySelector memory) {
        this.memory = memory;
    }

    public void requireConsistent(String question, String resolvedQuery, List<ChatMessage> selectedHistory) {
        if (!memory.isConsistent(question, resolvedQuery, selectedHistory)) {
            throw new InconsistentQueryException(memory.clarification(question));
        }
    }

    public static class InconsistentQueryException extends RuntimeException {
        public InconsistentQueryException(String message) {
            super(message);
        }
    }
}
