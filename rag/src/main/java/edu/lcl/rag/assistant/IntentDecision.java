package edu.lcl.rag.assistant;

import java.util.Map;

/** 模型只提出路由建议；工具是否存在、是否接入及执行权限由后端校验。 */
public record IntentDecision(
        Intent intent, Action action, Kind kind, String query,
        String toolId, Map<String, String> arguments, String clarification,
        boolean continuesPending) {

    public IntentDecision(Intent intent, Action action, Kind kind, String query,
                          String toolId, Map<String, String> arguments, String clarification) {
        this(intent, action, kind, query, toolId, arguments, clarification, false);
    }

    public enum Action { ANSWER, CLARIFY }

    public enum Kind {
        DOCUMENT, BUSINESS_DATA, SMALL_TALK, CAPABILITIES, WEATHER,
        GUIDANCE, ACKNOWLEDGEMENT, CONVERSATION_META, OUT_OF_SCOPE,
        UNCLEAR, MULTIPLE, UNAVAILABLE, ARITHMETIC
    }

    public static IntentDecision clarify(String question) {
        return new IntentDecision(Intent.OTHER, Action.CLARIFY, Kind.UNCLEAR,
                "", null, Map.of(), question);
    }
}
