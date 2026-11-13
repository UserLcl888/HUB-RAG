package edu.lcl.rag.assistant;

import edu.lcl.rag.entity.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 白名单工具目录：模型按说明选择 ID，后端只调已登记且已接入的 Java 适配器。 */
@Service
@Slf4j
public class BusinessQueryHandler {

    private final Map<String, BusinessQueryTool> tools = new LinkedHashMap<>();
    private final AnswerComposer composer;
    private final ProjectReferenceResolver projectReferences;
    private final BusinessAnswerProjection projection;

    public BusinessQueryHandler(List<BusinessQueryTool> registeredTools, AnswerComposer composer,
                                ProjectReferenceResolver projectReferences, BusinessAnswerProjection projection) {
        this.composer = composer;
        this.projectReferences = projectReferences;
        this.projection = projection;
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

    public boolean supportsContextReply(String toolId, String question, Map<String, String> arguments,
                                        List<ChatMessage> history) {
        BusinessQueryTool tool = tools.get(toolId);
        if (tool == null) return false;
        return tool.supportsContextReply(question, arguments, history)
                || (acceptsProjectReference(tool) && projectReferences.resolve(question, arguments, history).resolved());
    }

    public BusinessQueryTool.Result answer(IntentDecision decision, List<ChatMessage> history) {
        return answer(decision, history, null, () -> false);
    }

    public BusinessQueryTool.Result answer(IntentDecision decision, List<ChatMessage> history,
                                          Consumer<String> onToken, BooleanSupplier cancelled) {
        BusinessQueryTool.Result result = execute(decision, history);
        BusinessQueryTool tool = tools.get(decision.toolId());
        Map<String, Object> context = new LinkedHashMap<>(result.context());
        ConversationState previous = ConversationState.latestBusinessContext(history);
        if (context.isEmpty() && previous != null && (result.state() == BusinessQueryTool.State.FAILED
                || result.state() == BusinessQueryTool.State.NOT_CONNECTED)) {
            context.putAll(previous.context());
        }
        Object preference = previous == null ? null : previous.context().get("displayPreference");
        String proposed = decision.arguments() == null ? null : decision.arguments().get("displayPreference");
        if (proposed != null && decision.query().contains(proposed)) {
            preference = projection.mergePreference(preference, proposed);
        }
        if (preference != null) context.put("displayPreference", preference);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("state", result.state());
        facts.put("arguments", decision.arguments() == null ? Map.of() : decision.arguments());
        facts.put("appliedArguments", result.resolvedArguments());
        facts.put("queryContext", tool == null ? Map.of() : tool.modelContext(context));
        facts.put("evidence", result.text());
        if (tool != null) {
            // 回答模型不需要含技术字段/ID名称的工具 description；路由仍使用完整目录。
            facts.put("tool", Map.of("name", tool.definition().name(), "available", tool.definition().available()));
            tool.definition().parameters().stream()
                    .filter(parameter -> parameter.name().equals(result.missingParameter()))
                    .findFirst().ifPresent(parameter -> facts.put("missingParameter", parameter));
        }
        // 文案改写不能改变执行状态；只有后端真实查询返回 SUCCESS 才能报告实际数据。
        facts.put("displayPreference", preference == null ? "" : preference);
        return new BusinessQueryTool.Result(result.state(),
                composer.compose(decision.query(), "BUSINESS_QUERY",
                        projection.project(facts, preference == null ? "" : preference.toString()), List.of(),
                        onToken, cancelled),
                result.missingParameter(), result.resolvedArguments(), context);
    }

    private BusinessQueryTool.Result execute(IntentDecision decision, List<ChatMessage> history) {
        BusinessQueryTool tool = tools.get(decision.toolId());
        if (tool == null || !acceptsProjectReference(tool)) return executeTool(decision, history);
        var reference = projectReferences.resolve(decision.query(), decision.arguments(), history);
        if (!reference.resolved()) {
            if (reference.attempted() && tool.definition().available()) {
                String parameter = tool.definition().parameters().stream()
                        .anyMatch(item -> "projectSelection".equals(item.name())) ? "projectSelection" : "projectName";
                return new BusinessQueryTool.Result(BusinessQueryTool.State.NEEDS_CLARIFICATION,
                        "当前项目指代无法唯一对应最近展示的真实候选，请确认项目序号或名称。",
                        parameter, Map.of(), reference.context() == null
                        ? Map.of() : projectReferences.contextMap(reference.context()));
            }
            return executeTool(decision, history);
        }
        Map<String, String> arguments = new LinkedHashMap<>(
                decision.arguments() == null ? Map.of() : decision.arguments());
        arguments.put("projectName", reference.project().projectName());
        // 只把目标工具登记的条件交给适配器；候选 ID 放在服务端上下文，不作为模型参数。
        arguments.remove("projectSelection");
        IntentDecision resolved = new IntentDecision(decision.intent(), decision.action(), decision.kind(),
                decision.query(), decision.toolId(), Map.copyOf(arguments), decision.clarification(),
                decision.continuesPending());
        BusinessQueryTool.Result result = executeTool(resolved, history);
        Map<String, String> applied = new LinkedHashMap<>(result.resolvedArguments());
        applied.put("projectName", reference.project().projectName());
        Map<String, Object> context = new LinkedHashMap<>(projectReferences.contextMap(reference.context()));
        context.putAll(result.context());
        return new BusinessQueryTool.Result(result.state(), result.text(), result.missingParameter(), applied, context);
    }

    private boolean acceptsProjectReference(BusinessQueryTool tool) {
        return !ProjectQueryTool.ID.equals(tool.definition().id())
                && !AttendanceGroupQueryTool.ID.equals(tool.definition().id())
                && tool.definition().parameters().stream().anyMatch(parameter -> "projectName".equals(parameter.name()));
    }

    /** 路由只需选择范围，不需要将完整成员单位、岗位及名单重复塞进模型输入。 */
    public ConversationState modelState(ConversationState state) {
        if (state == null) return null;
        BusinessQueryTool tool = tools.get(state.toolId());
        if (tool == null && state.context().get("activeBusinessTool") instanceof String id) tool = tools.get(id);
        if (tool == null) return state;
        return new ConversationState(state.intent(), state.action(), state.kind(), state.query(), tool.definition().id(),
                state.arguments(), state.missingParameter(), state.clarification(), state.executionState(),
                tool.modelContext(state.context()));
    }

    private BusinessQueryTool.Result executeTool(IntentDecision decision, List<ChatMessage> history) {
        BusinessQueryTool tool = tools.get(decision.toolId());
        if (tool == null) {
            return new BusinessQueryTool.Result(BusinessQueryTool.State.NOT_CONNECTED,
                    "该业务数据查询功能暂未接入，目前无法查询真实记录或统计结果。");
        }
        var definition = tool.definition();
        // 未接入时补齐更多条件也无法执行，不以无意义追问掩盖能力边界。
        if (!definition.available()) {
            return new BusinessQueryTool.Result(BusinessQueryTool.State.NOT_CONNECTED,
                    definition.name() + "暂未接入，目前无法查询实际记录、名单或人数。");
        }
        for (var parameter : definition.parameters()) {
            if (parameter.required() && (decision.arguments() == null
                    || !decision.arguments().containsKey(parameter.name()))) {
                return new BusinessQueryTool.Result(BusinessQueryTool.State.NEEDS_CLARIFICATION,
                        parameter.clarification(), parameter.name());
            }
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
