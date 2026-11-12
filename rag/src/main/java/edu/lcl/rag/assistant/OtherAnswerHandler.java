package edu.lcl.rag.assistant;

import edu.lcl.rag.service.DirectAnswerService;
import edu.lcl.rag.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 受限的其他类回答，不把非业务请求放开成通用创作或实时信息服务。 */
@Service
@RequiredArgsConstructor
public class OtherAnswerHandler {

    private final DirectAnswerService directAnswers;
    private final ConversationMetaHandler conversationMeta;
    private final AnswerComposer composer;
    private final BusinessQueryHandler businessQueries;
    private final ConversationMemorySelector memory;

    public String answer(IntentDecision decision, List<ChatMessage> history) {
        if (decision.kind() == IntentDecision.Kind.CONVERSATION_META
                && decision.action() != IntentDecision.Action.CLARIFY) {
            return conversationMeta.answer(decision.query(), history);
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("kind", decision.kind());
        facts.put("tools", businessQueries.definitions());
        facts.put("documentQa", true);
        facts.put("voiceInput", true);
        facts.put("conversationReview", true);
        facts.put("weatherConnected", false);
        facts.put("businessWritesAllowed", false);
        if (decision.action() == IntentDecision.Action.CLARIFY) {
            facts.put("clarification", decision.clarification() == null ? "" : decision.clarification());
        }
        if (decision.kind() == IntentDecision.Kind.ARITHMETIC) {
            facts.put("calculation", directAnswers.evaluate(decision.query()).orElse(""));
        }
        return composer.compose(decision.query(),
                decision.action() == IntentDecision.Action.CLARIFY ? "CLARIFY" : "OTHER",
                facts, memory.selectForRouter(decision.query(), history));
    }
}
