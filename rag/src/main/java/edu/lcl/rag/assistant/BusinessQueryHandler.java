package edu.lcl.rag.assistant;

import edu.lcl.rag.entity.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 白名单工具目录：模型按说明选择 ID，后端只调已登记且已接入的 Java 适配器。 */
@Service
@Slf4j
public class BusinessQueryHandler {

    private final Map<String, BusinessQueryTool> tools = new LinkedHashMap<>();
    private final AnswerComposer composer;

    public BusinessQueryHandler(List<BusinessQueryTool> registeredTools, AnswerComposer composer) {
        this.composer = composer;
        for (BusinessQueryTool tool : registeredTools) {
            var definition = tool.definition();
            if (definition.id() == null || !definition.id().matches("[a-z][a-z0-9_]{0,63}")
                    || definition.name() == null || definition.name().isBlank()
                    || definition.description() == null || definition.description().isBlank()
                    || tools.putIfAbsent(definition.id(), tool) != null) {
                throw new IllegalArgumentException("业务查询工具定义无效或 ID 重复");
            }
        }
    }

    public List<BusinessQueryTool.Definition> definitions() {
        return tools.values().stream().map(BusinessQueryTool::definition).toList();
    }

    public boolean contains(String toolId) {
        return toolId != null && tools.containsKey(toolId);
    }

    public List<BusinessQueryTool.Parameter> parameters(String toolId) {
        BusinessQueryTool tool = tools.get(toolId);
        return tool == null ? List.of() : tool.definition().parameters();
    }

    public BusinessQueryTool.Result answer(IntentDecision decision, List<ChatMessage> history) {
        BusinessQueryTool.Result result = execute(decision, history);
        BusinessQueryTool tool = tools.get(decision.toolId());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("state", result.state());
        facts.put("arguments", decision.arguments() == null ? Map.of() : decision.arguments());
        facts.put("evidence", result.text());
        if (tool != null) {
            facts.put("tool", tool.definition());
            tool.definition().parameters().stream()
                    .filter(parameter -> parameter.name().equals(result.missingParameter()))
                    .findFirst().ifPresent(parameter -> facts.put("missingParameter", parameter));
        }
        // 文案改写不能改变执行状态；只有后端真实查询返回 SUCCESS 才能报告实际数据。
        return new BusinessQueryTool.Result(result.state(),
                composer.compose(decision.query(), "BUSINESS_QUERY", facts, List.of()),
                result.missingParameter());
    }

    private BusinessQueryTool.Result execute(IntentDecision decision, List<ChatMessage> history) {
        BusinessQueryTool tool = tools.get(decision.toolId());
        if (tool == null) {
            return new BusinessQueryTool.Result(BusinessQueryTool.State.NOT_CONNECTED,
                    "该业务数据查询功能暂未接入，目前无法查询真实记录或统计结果。");
        }
        var definition = tool.definition();
        // 先演示完整多轮补条件；未接入也不能把模糊项目当成已满足参数。
        for (var parameter : definition.parameters()) {
            if (parameter.required() && (decision.arguments() == null
                    || !decision.arguments().containsKey(parameter.name()))) {
                return new BusinessQueryTool.Result(BusinessQueryTool.State.NEEDS_CLARIFICATION,
                        parameter.clarification(), parameter.name());
            }
        }
        if (!definition.available()) {
            return new BusinessQueryTool.Result(BusinessQueryTool.State.NOT_CONNECTED,
                    definition.name() + "暂未接入，目前无法查询实际记录、名单或人数。");
        }
        // 未来实现必须返回可核实的实际结果，或明确追问；禁止失败后转 RAG 猜测。
        try {
            BusinessQueryTool.Result result = tool.query(decision.query(), decision.arguments(), history);
            if (result == null || result.state() == null || result.text() == null || result.text().isBlank()) {
                throw new IllegalStateException("业务查询工具未返回有效结果");
            }
            if (result.missingParameter() != null
                    && (result.state() != BusinessQueryTool.State.NEEDS_CLARIFICATION
                    || definition.parameters().stream().noneMatch(
                            parameter -> parameter.name().equals(result.missingParameter())))) {
                throw new IllegalStateException("业务查询工具返回了未登记的待补参数");
            }
            if (result.state() == BusinessQueryTool.State.FAILED) {
                return failed(definition);
            }
            return result;
        } catch (RuntimeException error) {
            log.warn("[BusinessQuery] 查询失败：toolId={}，error={}",
                    definition.id(), error.getClass().getSimpleName());
            return failed(definition);
        }
    }

    private BusinessQueryTool.Result failed(BusinessQueryTool.Definition definition) {
        return new BusinessQueryTool.Result(BusinessQueryTool.State.FAILED,
                "业务查询失败：" + definition.name() + "暂时不可用，请稍后重试。未获得可核实的查询结果。");
    }
}
