package edu.lcl.rag.assistant;

import edu.lcl.rag.entity.ChatMessage;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 后端确定的每条助手回复状态，不从模型生成的回答措辞反推流程。 */
public record ConversationState(
        Intent intent, IntentDecision.Action action, IntentDecision.Kind kind,
        String query, String toolId, Map<String, String> arguments,
        String missingParameter, String clarification, BusinessQueryTool.State executionState,
        Map<String, Object> context) {

    public ConversationState {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public static ConversationState from(IntentDecision decision) {
        return new ConversationState(decision.intent(), decision.action(), decision.kind(),
                decision.query(), decision.toolId(), decision.arguments(),
                null, decision.clarification(), null, Map.of());
    }

    public static ConversationState from(IntentDecision decision, BusinessQueryTool.Result result) {
        Map<String, String> arguments = new LinkedHashMap<>(
                !result.context().isEmpty() || decision.arguments() == null ? Map.of() : decision.arguments());
        arguments.putAll(result.resolvedArguments());
        return new ConversationState(decision.intent(),
                result.state() == BusinessQueryTool.State.NEEDS_CLARIFICATION
                        ? IntentDecision.Action.CLARIFY : IntentDecision.Action.ANSWER,
                decision.kind(), decision.query(), decision.toolId(), arguments,
                result.missingParameter(),
                result.state() == BusinessQueryTool.State.NEEDS_CLARIFICATION ? result.text() : null,
                result.state(), result.context());
    }

    public boolean pending() {
        return action == IntentDecision.Action.CLARIFY
                && kind != IntentDecision.Kind.UNAVAILABLE
                && (intent == Intent.BUSINESS_QUERY || intent == Intent.OTHER);
    }

    /** 只继续最近一轮的待处理事项；遇到已回答的新话题即停止，不复活旧追问。 */
    public static ConversationState pending(List<ChatMessage> history) {
        if (history == null) {
            return null;
        }
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessage message = history.get(index);
            if ("ASSISTANT".equals(message.getRole())) {
                ConversationState state = message.getConversationState();
                return state != null && state.pending() ? state : null;
            }
        }
        return null;
    }

    /** 路由失败、短澄清不清除已确认对象；明确切换话题/取消则阻断旧范围。 */
    public static ConversationState latestBusinessContext(List<ChatMessage> history) {
        if (history == null) return null;
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessage message = history.get(index);
            if ("ASSISTANT".equals(message.getRole())) {
                ConversationState state = message.getConversationState();
                if (state == null) return null;
                if (!state.context().isEmpty()) return state;
                if (state.kind() == IntentDecision.Kind.UNAVAILABLE
                        || state.action() == IntentDecision.Action.CLARIFY
                        || state.kind() == IntentDecision.Kind.ACKNOWLEDGEMENT) continue;
                return null;
            }
        }
        return null;
    }
}
