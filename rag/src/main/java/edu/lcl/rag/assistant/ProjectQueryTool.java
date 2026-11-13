package edu.lcl.rag.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.lcl.rag.business.project.ProjectQueryModels.*;
import edu.lcl.rag.business.project.ProjectQueryService;
import edu.lcl.rag.business.ReturnPolicy.Choice;
import edu.lcl.rag.entity.ChatMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型只提取用户原始条件，后端从当前会话最近的真实候选中解析选择。
 * 无分页、不接受模型生成的项目/标段 ID，也不把示例当成全部结果。
 */
@Component
public class ProjectQueryTool implements BusinessQueryTool {
    public static final String ID = "query_construction_projects";
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:19|20|21)\\d{2})(?:年|$)");
    private static final Pattern ORDINAL = Pattern.compile("第?([0-9一二两三四五六七八九十]+)(?:个|项|号)");

    private final ObjectProvider<ProjectQueryService> services;
    private final ObjectMapper mapper;
    private final ProjectReferenceResolver references;
    private final ReturnChoiceParser returns;
    private final BusinessSnapshots snapshots;

    public ProjectQueryTool(ObjectProvider<ProjectQueryService> services, ObjectMapper mapper,
                            ProjectReferenceResolver references, ReturnChoiceParser returns, BusinessSnapshots snapshots) {
        this.services = services;
        this.mapper = mapper;
        this.references = references;
        this.returns = returns;
        this.snapshots = snapshots;
    }

    @Override
    public Definition definition() {
        return new Definition(ID, "施工项目及关联标段查询", """
                只读查询真实项目、数量、所属区县及关联标段。所有项目强制 project_stage='组织实施'，
                项目和标段均限定 del_flag='0'，任何名称或 ID 查询不能取消边界。不额外筛 jszt=1。
                未指定年度按 Asia/Shanghai 当前年；无名称可正常查询年度项目，不先强制询问名称。
                支持简称、漏字或不准确名称，先完整/包含匹配，再召回同年度相近候选，不能猜正式名称。
                匹配不超过三个可展示详情；名称有歧义或仅模糊命中仍需确认。
                项目或标段超过三个且未指定范围时，先报总数并举最多三个真实例子，
                询问全部返回还是返回前几条；用户明确后按要求返回，不设默认页或翻页。
                用户可按示例序号、名称确认或补充新名称；选择和条件保存在当前会话，不跨会话引用。
                返回项目名称、年度、编号/代码、区县、阶段、状态、面积（亩）、投资（万元），
                关联标段名称/编号、单位、日期、金额（元）。不查考勤、政策或修改数据。
                """.strip(),
                List.of("帮我查查当前正在施工的项目", "帮我查查项目", "2025 年有多少项目和标段",
                        "2026年武汉经济区东荆街东荆项目", "第一个项目", "不是这些，我要东荆项目", "第二个标段"),
                List.of(
                        new Parameter("projectYear", "用户明确给出的四位年度或原词今年/去年/前年/明年；未给出时省略",
                                false, null),
                        new Parameter("projectName", "用户原文项目名或简称，保留错字，不补全正式名称；不在示例中时提取用户补充的新名称",
                                false, null),
                        new Parameter("projectSelection", "承接最近项目结果时的本轮原话，例如第一个项目、东荆那个、不是这些；不得生成项目 ID",
                                false, "请确认是否为示例中的项目，或补充具体项目名称。"),
                        new Parameter("sectionName", "用户原文中的标段名称或简称；承接当前项目范围，不得当成项目名",
                                false, null),
                        new Parameter("sectionSelection", "承接最近标段结果时的本轮选择原话，例如第二个标段、就是这个；不得生成标段 ID",
                                false, "请确认是否为示例中的标段，或补充具体标段名称。"),
                        new Parameter("returnScope", "用户本轮要求的返回范围原话，如全部返回、前五条；不生成数字或布尔值，非本轮要求时省略",
                                false, "请说明全部返回，还是返回前几条。"),
                        new Parameter("refresh", "本轮原文刷新、最新、重新查询要求", false, null),
                        new Parameter("displayPreference", "本轮原文不展示或恢复展示字段的要求", false, null)),
                services.getIfAvailable() != null);
    }

    @Override
    public boolean supportsContextReply(String question, Map<String, String> arguments, List<ChatMessage> history) {
        if (question == null || question.length() > 100 || arguments == null || references.previous(history) == null) return false;
        return List.of("projectSelection", "sectionSelection", "returnScope").stream().map(arguments::get)
                .anyMatch(value -> value != null && !value.isBlank() && question.contains(value.strip()));
    }

    @Override
    public Result query(String question, Map<String, String> arguments, List<ChatMessage> history) {
        ProjectQueryService service = services.getIfAvailable();
        if (service == null) return new Result(State.NOT_CONNECTED, "业务数据源已禁用，未查询项目或标段。");
        Map<String, String> args = arguments == null ? Map.of() : arguments;
        int year = year(args.get("projectYear"));
        String name = args.get("projectName");
        String sectionName = args.get("sectionName");
        String projectSelection = args.get("projectSelection");
        String sectionSelection = args.get("sectionSelection");
        Choice choice = returns.parse(args.get("returnScope"), question);
        Choice projectReturn = null;
        Choice sectionReturn = null;
        List<String> ids = null;
        List<String> sectionIds = null;
        Context previous = references.previous(history);
        boolean followup = projectSelection != null || sectionSelection != null || sectionName != null || choice != null
                || args.containsKey("refresh") || args.containsKey("displayPreference");
        if (followup && previous != null) {
            year = args.containsKey("projectYear") ? year : previous.projectYear();
            boolean yearChanged = year != previous.projectYear();
            boolean nameChanged = args.containsKey("projectName") && !Objects.equals(name, previous.projectName());
            String selection = sectionSelection != null ? sectionSelection : projectSelection;
            if (yearChanged && selection != null && ORDINAL.matcher(selection).find()) {
                return clarification("年度已改变，请重新查询该年度候选后再按序号选择。", previous, "PROJECT");
            }
            if (name == null) name = previous.projectName();
            if (!yearChanged && !nameChanged) {
                ids = previous.selectedProjectIds().isEmpty() ? null : previous.selectedProjectIds();
                // 已经明确过项目时保持范围；标段筛选不能扩大为年度全部项目。
                if (ids == null
                        && !String.valueOf(previous.selectionScope()).startsWith("PROJECT")) {
                    ids = previous.displayedCandidates().stream().map(Candidate::projectId).toList();
                    if (ids.isEmpty()) ids = null;
                }
                boolean explicitProjectChoice = projectSelection != null && projectSelection.contains("项目");
                boolean sectionAction = sectionSelection != null || (!explicitProjectChoice && sectionName != null)
                        || (String.valueOf(previous.selectionScope()).startsWith("SECTION") && projectSelection != null
                            && !explicitProjectChoice);
                if (choice != null) {
                    if (String.valueOf(previous.selectionScope()).startsWith("SECTION")) {
                        sectionReturn = choice;
                        sectionName = previous.sectionName();
                        sectionIds = previous.selectedSectionIds().isEmpty() ? null : previous.selectedSectionIds();
                    } else {
                        projectReturn = choice;
                    }
                } else if (sectionAction) {
                    if (sectionName == null) sectionName = previous.sectionName();
                    boolean sectionNameChanged = args.containsKey("sectionName")
                            && !Objects.equals(sectionName, previous.sectionName());
                    if (selection != null && !sectionNameChanged) {
                        List<SectionCandidate> chosen = references.select(selection, previous.displayedSections(),
                                SectionCandidate::segmentName, "标段");
                        if (chosen.isEmpty()) {
                            return clarification("请确认是否为示例中的标段；若不在，请补充具体标段名称。",
                                    previous, "SECTION");
                        }
                        sectionIds = chosen.stream().map(SectionCandidate::segmentId).toList();
                        sectionName = null;
                    }
                } else if (projectSelection != null) {
                    List<Candidate> chosen = references.select(projectSelection, previous.displayedCandidates(),
                            Candidate::projectName, "项目");
                    if (chosen.isEmpty()) {
                        return clarification("请确认是否为示例中的项目；若不在，请补充具体项目名称。",
                                previous, "PROJECT");
                    }
                    ids = chosen.stream().map(Candidate::projectId).toList();
                    name = null;
                    // 新的项目选择不重放上一轮的标段名称筛选。
                    sectionName = null;
                }
            }
        } else if (projectSelection != null || sectionSelection != null) {
            return new Result(State.NEEDS_CLARIFICATION,
                    "当前没有可安全承接的候选，请重新提供项目名称或年度查询条件。", "projectSelection");
        }
        if (choice != null && projectReturn == null && sectionReturn == null) {
            if (sectionName != null || sectionSelection != null) sectionReturn = choice;
            else projectReturn = choice;
        }
        if (ids != null && ids.size() > ProjectQueryService.DETAIL_LIMIT && projectReturn == null) {
            // 已明确展示或选择的真实项目集合，不因后续查询标段而再次缩回三个示例。
            projectReturn = new Choice(null, true);
        }
        if (sectionIds != null && sectionIds.size() > ProjectQueryService.DETAIL_LIMIT && sectionReturn == null) {
            sectionReturn = new Choice(null, true);
        }
        Request request = new Request(year, name, ids, sectionName, sectionIds, projectReturn, sectionReturn);
        ConversationState last = ConversationState.latestBusinessContext(history);
        Map<String, Object> saved = new LinkedHashMap<>(last == null ? Map.of() : last.context());
        if (previous != null && (year != previous.projectYear()
                || args.containsKey("projectName") && !Objects.equals(name, previous.projectName())
                || ids != null && !ids.equals(previous.selectedProjectIds()))) {
            saved.remove(BusinessSnapshots.KEY);
            saved.remove("attendanceScope");
        }
        boolean refresh = args.containsKey("refresh") && question.contains(args.get("refresh"));
        Response response = snapshots.find(saved, "projects", request, Response.class, refresh);
        if (response == null) {
            response = service.query(request);
            snapshots.remember(saved, "projects", request, response);
        }
        String scope = response.selection() == null ? null : response.selection().scope();
        Context context = new Context(response.appliedFilters().projectYear(), response.appliedFilters().projectName(),
                response.appliedFilters().projectIds(), response.candidates(),
                response.appliedFilters().sectionName(), response.appliedFilters().sectionIds(),
                response.sectionCandidates(), scope, response.summary().totalProjectCount());
        State state = "NEEDS_SELECTION".equals(response.resultStatus()) ? State.NEEDS_CLARIFICATION : State.SUCCESS;
        Map<String, String> resolved = new LinkedHashMap<>();
        resolved.put("projectYear", String.valueOf(context.projectYear()));
        if (context.projectName() != null) resolved.put("projectName", context.projectName());
        if (context.sectionName() != null) resolved.put("sectionName", context.sectionName());
        saved.putAll(references.contextMap(context));
        saved.put("activeBusinessTool", ID);
        return new Result(state, mapper.valueToTree(response).toString(),
                state == State.NEEDS_CLARIFICATION ? selectionParameter(scope) : null,
                resolved, saved);
    }

    @Override
    public Map<String, Object> modelContext(Map<String, Object> context) {
        return context.containsKey("projectQuery") ? Map.of("projectQuery", context.get("projectQuery")) : Map.of();
    }

    private String selectionParameter(String scope) {
        if (scope != null && scope.endsWith("_DISPLAY")) return "returnScope";
        return "SECTION".equals(scope) ? "sectionSelection" : "projectSelection";
    }

    private Result clarification(String reason, Context context, String scope) {
        Map<String, String> resolved = new LinkedHashMap<>();
        resolved.put("projectYear", String.valueOf(context.projectYear()));
        if (context.projectName() != null) resolved.put("projectName", context.projectName());
        if (context.sectionName() != null) resolved.put("sectionName", context.sectionName());
        return new Result(State.NEEDS_CLARIFICATION,
                mapper.valueToTree(Map.of("reason", reason, "candidates", context.displayedCandidates(),
                        "sectionCandidates", context.displayedSections(),
                        "selection", Map.of("scope", scope, "reason", reason),
                        "totalProjectCount", context.totalProjectCount())).toString(),
                selectionParameter(scope), resolved, references.contextMap(context));
    }

    private int year(String value) {
        int current = Year.now(ProjectQueryService.BUSINESS_ZONE).getValue();
        if (value == null || value.isBlank()) return current;
        switch (value.strip()) {
            case "今年", "当前年度", "本年" -> { return current; }
            case "去年" -> { return current - 1; }
            case "前年" -> { return current - 2; }
            case "明年" -> { return current + 1; }
        }
        Matcher match = YEAR.matcher(value.strip());
        if (!match.matches()) throw new IllegalArgumentException("立项年度格式无效");
        return Integer.parseInt(match.group(1));
    }

}
