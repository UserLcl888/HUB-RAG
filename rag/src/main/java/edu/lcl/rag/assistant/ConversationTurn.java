package edu.lcl.rag.assistant;

import edu.lcl.rag.entity.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/** 一轮用户问题及其后续助手子回答；混合轮仍按一个用户轮次参与记忆选择。 */
public record ConversationTurn(ChatMessage user, List<ChatMessage> assistants) {

    public ConversationTurn {
        assistants = assistants == null ? List.of() : List.copyOf(assistants);
    }

    public List<ChatMessage> messages(boolean ragOnly) {
        List<ChatMessage> result = new ArrayList<>();
        if (user != null) {
            result.add(user);
        }
        assistants.stream()
                .filter(message -> !ragOnly || message.getIntent() == Intent.RAG)
                .forEach(result::add);
        return result;
    }

    public String userText() {
        return user == null || user.getContent() == null ? "" : user.getContent();
    }

    public boolean hasClarification() {
        return pendingState() != null;
    }

    public ConversationState pendingState() {
        return ConversationState.pending(assistants);
    }
}
