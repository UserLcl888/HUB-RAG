package edu.lcl.rag.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.lcl.rag.business.ReturnPolicy;
import edu.lcl.rag.business.ReturnPolicy.Choice;
import edu.lcl.rag.business.attendance.AttendanceGroupModels.*;
import edu.lcl.rag.business.attendance.AttendanceGroupQueryService;
import edu.lcl.rag.business.project.ProjectQueryModels;
import edu.lcl.rag.business.project.ProjectQueryModels.Candidate;
import edu.lcl.rag.business.project.ProjectQueryModels.SectionCandidate;
import edu.lcl.rag.business.project.ProjectQueryService;
import edu.lcl.rag.entity.ChatMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 后端持有当前项目、标段、组和完整查询快照。模型只提取原文条件，不生成 ID，
 * 不决定是否需要重复查询；业务库始终只执行固定 SELECT。
 */
@Component
public class AttendanceGroupQueryTool implements BusinessQueryTool {
    public static final String ID = "query_attendance_groups";
    private static final String MEMORY = "attendanceScope";
    private final ObjectProvider<AttendanceGroupQueryService> services;
    private final ObjectProvider<ProjectQueryService> projects;
    private final ObjectMapper mapper;
    private final ProjectReferenceResolver references;
    private final ReturnChoiceParser returns;
    private final BusinessSnapshots snapshots;

    public AttendanceGroupQueryTool(ObjectProvider<AttendanceGroupQueryService> services,
                                    ObjectProvider<ProjectQueryService> projects, ObjectMapper mapper,
                                    ProjectReferenceResolver references, ReturnChoiceParser returns,
                                    BusinessSnapshots snapshots) {
        this.services = services;
        this.projects = projects;
        this.mapper = mapper;
        this.references = references;
        this.returns = returns;
        this.snapshots = snapshots;
    }

    @Override
    public Definition definition() {
        return new Definition(ID, "考勤组配置及成员查询", """
                只读查询真实项目及其标段下的考勤组配置和成员。项目强制组织实施阶段，项目/标段/组/成员均未删除。
                必须先确定真实项目；内部项目/标段/组 ID 仅由后端映射，不能对用户输出。
                项目简介提供年度、区县、投资金额（万元）及相关信息；用户明确不展示的字段不展开。
                项目确定后，未指定标段且标段<=3个，自动逐标段查询，不问是否查询或是否确认。
                标段>3个时先报总数和最多3个真实例子，询问全部还是前N条；指定标段名或序号则按指定范围。
                每个标段的考勤组<=3个默认全部展示配置、人数和成员姓名；>3个先报总数和真实例子，
                询问全部还是前N条。不要求用户先指定唯一考勤组，也不在已有结果后再次问是否要查询。
                显式组名、日期、时段、地点条件优先，同名不能自动合并。组只查启用且指定日期有效的配置，
                未给日期默认北京时间今天，不按当前钟点隐藏组。显式时段匹配配置起止时刻。
                默认展示组名、日期、时段、地点、配置人数和全部成员姓名；单位/公司/岗位只在用户追问时展示。
                支持项目/标段/组序号、这个项目、全部返回、前五条、成员单位岗位等多轮追问。
                当前会话真实已选范围优先，不重新列项目。完整结果未过期时复用；改条件、刷新或过期才读取业务库。
                只处理配置及成员，不扩展其他功能。
                """.strip(), List.of("帮我分析这个项目考勤组的配置", "第一个项目的考勤组",
                "当前有几个考勤组", "标段二的早班组", "全部返回", "这些成员的单位和岗位"),
                List.of(
                        parameter("projectYear", "本轮用户原文四位年度或今年/去年/前年/明年，未指定省略"),
                        parameter("projectName", "本轮原文项目名或简称；指代不要填名称，不重放旧名称"),
                        parameter("projectSelection", "本轮原文项目选择，例如第一个项目、这个项目"),
                        parameter("sectionName", "本轮原文标段名称或编号"),
                        parameter("sectionSelection", "本轮原文标段选择，例如第二个标段"),
                        parameter("groupName", "本轮原文考勤组名称或关键词"),
                        parameter("groupSelection", "本轮原文考勤组选择，例如第二个组"),
                        parameter("attendanceDate", "本轮原文yyyy-MM-dd或今天/昨天/明天，不猜日期"),
                        parameter("attendanceTime", "本轮原文时刻或时段，例如08:00或08:00-18:00"),
                        parameter("location", "本轮原文打卡地点或地址关键词"),
                        parameter("returnScope", "本轮原文全部返回、前五条等，不生成数字/布尔值"),
                        parameter("memberDetails", "本轮原文成员单位、公司、岗位、人员详情或详细汇总诉求"),
                        parameter("refresh", "本轮原文刷新、重新查询、最新等，要求重新读数据"),
                        parameter("clearFilters", "本轮原文取消全部或名称/日期/时间/地点等指定筛选；仅清除指定条件，所有组清除组名/组选择，不取消有效/启用边界"),
                        parameter("displayPreference", "本轮原文不展示/恢复展示某字段的要求，仅影响展示不修改数据")),
                services.getIfAvailable() != null && projects.getIfAvailable() != null);
    }

    private Parameter parameter(String name, String description) {
        return new Parameter(name, description, false, switch (name) {
            case "projectSelection", "projectName" -> "请指定要查询的项目。";
            case "sectionSelection", "sectionName" -> "请指定匹配的标段。";
            case "returnScope" -> "请说明全部返回，还是返回前几条。";
            default -> "请补充明确的筛选条件。";
        });
    }

    @Override
    public boolean supportsContextReply(String question, Map<String, String> arguments, List<ChatMessage> history) {
        return question != null && arguments != null && references.previous(history) != null
                && arguments.values().stream().anyMatch(value -> value != null && question.contains(value));
    }

    @Override
    public Result query(String question, Map<String, String> arguments, List<ChatMessage> history) {
        if (!definition().available()) return new Result(State.NOT_CONNECTED, "考勤组配置查询当前不可用。");
        Map<String, String> args = arguments == null ? Map.of() : arguments;
        ConversationState last = ConversationState.latestBusinessContext(history);
        Map<String, Object> context = new LinkedHashMap<>(last == null ? Map.of() : last.context());
        ProjectQueryModels.Context previous = references.previous(history);
        Memory old = memory(context);
        boolean refresh = current(args, "refresh", question);
        Choice choice = returns.parse(args.get("returnScope"), question);
        int year = args.containsKey("projectYear") ? year(args.get("projectYear"))
                : previous == null ? Year.now(ProjectQueryService.BUSINESS_ZONE).getValue() : previous.projectYear();
        String name = args.get("projectName");
        List<String> projectIds = null;
        String selection = args.get("projectSelection");
        boolean yearChanged = previous != null && previous.projectYear() != year;
        if (selection == null && previous != null && !yearChanged && name == null) {
            var resolved = references.resolve(question, args, history);
            if (resolved.resolved()) selection = resolved.project().projectName();
        }
        if (selection != null) {
            if (previous == null || yearChanged) return finish(State.NEEDS_CLARIFICATION, "projectName",
                    Map.of("reason", "没有该年度可安全承接的项目候选，请提供项目名称。"), context, previous, old, false);
            List<Candidate> chosen = references.select(selection, previous.displayedCandidates(), Candidate::projectName, "项目");
            if (chosen.size() != 1) return finish(State.NEEDS_CLARIFICATION, "projectSelection",
                    Map.of("reason", "请从真实候选中选择一个项目。", "candidates", previous.displayedCandidates()),
                    context, previous, old, false);
            projectIds = List.of(chosen.getFirst().projectId());
            name = null;
        } else if (previous != null && !yearChanged && (name == null || name.equals(previous.projectName()))) {
            if (previous.selectedProjectIds().size() == 1) projectIds = previous.selectedProjectIds();
            else if (previous.totalProjectCount() == 1 && previous.displayedCandidates().size() == 1
                    && !"PROJECT".equals(previous.selectionScope())) {
                projectIds = List.of(previous.displayedCandidates().getFirst().projectId());
            } else if (name == null) name = previous.projectName();
        }
        boolean changed = previous == null || yearChanged || (projectIds != null
                ? !projectIds.equals(previous.selectedProjectIds()) : !Objects.equals(name, previous.projectName()));
        if (changed) {
            context.remove(MEMORY);
            context.remove("attendanceGroups"); // 不承接旧版不完整状态。
            context.remove(BusinessSnapshots.KEY);
            // 项目候选确认是原请求的继续，保留尚未落到具体项目的组筛选草稿。
            if (old == null || old.projectId() != null) old = null;
        }
        Choice projectChoice = previous == null || String.valueOf(previous.selectionScope()).startsWith("PROJECT")
                ? choice : null;
        var request = new ProjectQueryModels.Request(year, projectIds == null ? name : null, projectIds,
                null, null, projectChoice, null);
        ProjectQueryModels.Response projectResult = snapshots.find(context, "projects", request,
                ProjectQueryModels.Response.class, refresh);
        if (projectResult == null && projectIds != null) {
            var stored = snapshots.latest(context, "projects", ProjectQueryModels.Response.class, refresh);
            if (stored != null && stored.appliedFilters().projectYear() == year
                    && stored.summary().totalProjectCount() == 1
                    && stored.candidates().size() == 1
                    && projectIds.contains(stored.candidates().getFirst().projectId())) projectResult = stored;
        }
        if (projectResult == null) {
            projectResult = projects.getObject().query(request);
            snapshots.remember(context, "projects", request, projectResult);
        }
        // 原问题的筛选条件要跨“选择项目”保存，不把返回范围/序号当成永久条件。
        Memory draft = new Memory(2, null, List.of(), List.of(), List.of(), List.of(),
                filter(args, "attendanceDate", old == null ? null : old.dateExpression()),
                filter(args, "groupName", old == null ? null : old.groupName()),
                filter(args, "attendanceTime", old == null ? null : old.attendanceTime()),
                filter(args, "location", old == null ? null : old.location()), "PROJECT",
                current(args, "memberDetails", question) || old != null && old.memberDetails());
        if (projectResult.summary().totalProjectCount() == 0) return finish(State.SUCCESS, null,
                Map.of("result", projectResult), context, projectContext(projectResult, List.of(), "PROJECT"), null, false);
        boolean confirmed = projectIds != null || projectResult.summary().totalProjectCount() == 1
                && !"FUZZY".equals(projectResult.matchMode());
        if (!confirmed || projectResult.candidates().size() != 1) {
            boolean needsRange = ReturnPolicy.needsChoice(projectResult.summary().totalProjectCount(), projectChoice);
            return finish(State.NEEDS_CLARIFICATION, needsRange ? "returnScope" : "projectSelection",
                    Map.of("result", projectResult, "reason", "请先选择一个真实项目。"),
                    context, projectContext(projectResult, List.of(), needsRange ? "PROJECT_DISPLAY" : "PROJECT"),
                    draft, false);
        }
        Candidate project = projectResult.candidates().getFirst();
        String projectId = project.projectId();
        boolean sameProject = old != null && projectId.equals(old.projectId());
        SectionSnapshot sectionSnapshot = snapshots.find(context, "sections", List.of(year, projectId),
                SectionSnapshot.class, refresh);
        if (sectionSnapshot == null) {
            sectionSnapshot = new SectionSnapshot(projects.getObject().sectionCandidates(year, projectId));
            snapshots.remember(context, "sections", List.of(year, projectId), sectionSnapshot);
        }
        List<SectionCandidate> sections = sectionSnapshot.items();
        List<SectionCandidate> selected = List.of();
        String sectionName = args.get("sectionName");
        String sectionSelection = args.get("sectionSelection");
        boolean explicitSection = sectionName != null || sectionSelection != null;
        if (sectionName != null) {
            selected = sections.stream().filter(item -> sectionName.equals(item.segmentName())
                    || sectionName.equals(item.segmentCode())).toList();
            if (selected.isEmpty()) selected = references.select(sectionName, sections, SectionCandidate::segmentName, "标段");
        } else if (sectionSelection != null) {
            selected = references.select(sectionSelection, sameProject ? previous.displayedSections() : sections,
                    SectionCandidate::segmentName, "标段");
        } else if (sameProject && !old.selectedSectionIds().isEmpty()) {
            List<String> ids = old.selectedSectionIds();
            selected = sections.stream().filter(item -> ids.contains(item.segmentId())).toList();
        } else if (sections.size() <= 3) selected = sections;
        boolean sectionRange = (sameProject && "SECTION".equals(old.pendingScope())
                || !sameProject && sections.size() > 3 && projectChoice == null) && choice != null;
        if (sectionRange) selected = ReturnPolicy.visible(sections, choice);
        if (selected.isEmpty() && !sections.isEmpty()) {
            List<SectionCandidate> examples = ReturnPolicy.visible(sections, null);
            var scope = new ProjectQueryModels.Context(year, project.projectName(), List.of(projectId), List.of(project),
                    sectionName, List.of(), examples, explicitSection ? "SECTION" : "SECTION_DISPLAY", 1);
            Memory waiting = new Memory(2, projectId, sections, List.of(), List.of(), List.of(), draft.dateExpression(),
                    draft.groupName(), draft.attendanceTime(), draft.location(), "SECTION", draft.memberDetails());
            return finish(State.NEEDS_CLARIFICATION, explicitSection ? "sectionSelection" : "returnScope",
                    Map.of("project", projectResult.items(), "totalSectionCount", sections.size(),
                            "sectionCandidates", examples, "reason", explicitSection
                                    ? "未能明确匹配指定标段，请根据真实候选补充名称或序号。"
                                    : "标段超过三个，请选择全部、前几条或指定标段。"), context, scope, waiting, false);
        }
        boolean clear = current(args, "clearFilters", question);
        String clearExpression = clear ? args.get("clearFilters") : "";
        boolean allFilters = clear && (clearExpression.matches("(?s).*(?:全部筛选|所有筛选|取消筛选|清除筛选|重置筛选).*")
                || !clearExpression.matches("(?s).*(?:名称|组名|所有组|全部组|日期|时间|时段|地点|地址).*"));
        String dateExpression = filter(args, "attendanceDate",
                allFilters || clearExpression.contains("日期") ? null : draft.dateExpression());
        LocalDate date = dateExpression == null ? LocalDate.now(ProjectQueryService.BUSINESS_ZONE) : date(dateExpression);
        String groupName = filter(args, "groupName", allFilters
                || clearExpression.matches("(?s).*(?:名称|组名|所有组|全部组).*") ? null : draft.groupName());
        String time = filter(args, "attendanceTime", allFilters
                || clearExpression.matches("(?s).*(?:时间|时段).*") ? null : draft.attendanceTime());
        String location = filter(args, "location", allFilters
                || clearExpression.matches("(?s).*(?:地点|地址).*") ? null : draft.location());
        boolean sameFilters = sameProject && !explicitSection && !clear
                && Objects.equals(dateExpression, old.dateExpression()) && Objects.equals(groupName, old.groupName())
                && Objects.equals(time, old.attendanceTime()) && Objects.equals(location, old.location());
        List<String> groupIds = sameFilters ? old.selectedGroupIds() : List.of();
        if (args.containsKey("groupSelection")) {
            if (!sameProject) return finish(State.NEEDS_CLARIFICATION, "groupSelection",
                    Map.of("reason", "请根据当前项目查询结果选择考勤组。"), context, previous, draft, false);
            List<Group> chosen = references.select(args.get("groupSelection"), old.displayedGroups(), Group::groupName, "组");
            if (chosen.isEmpty()) return finish(State.NEEDS_CLARIFICATION, "groupSelection",
                    Map.of("reason", "请用考勤组序号、名称、时段或地点进一步指定。", "examples", old.displayedGroups()),
                    context, previous, old, false);
            groupIds = chosen.stream().map(Group::groupId).toList();
            List<String> sectionIds = chosen.stream().map(Group::segmentId).distinct().toList();
            selected = sections.stream().filter(item -> sectionIds.contains(item.segmentId())).toList();
        }
        boolean counting = question.matches("(?s).*(?:几个|多少|数量|总数).*");
        boolean countingGroups = counting && question.matches("(?s).*(?:考勤组|几个组|多少组).*")
                && !args.containsKey("groupSelection");
        if (countingGroups) groupIds = List.of();
        List<String> selectedGroupIds = groupIds;
        Choice groupChoice = projectChoice != null || sectionRange ? null : choice;
        if (!groupIds.isEmpty() && groupChoice == null) groupChoice = new Choice(null, true);
        if (sameFilters && !refresh && groupIds.isEmpty() && groupChoice == null && old.pendingScope() == null && !counting
                && !old.displayedGroups().isEmpty()) {
            groupIds = old.displayedGroups().stream().map(Group::groupId).toList();
            groupChoice = new Choice(null, true);
        }
        List<SectionResult> results = new ArrayList<>();
        List<Group> displayed = new ArrayList<>();
        boolean needsRange = false;
        long total = 0;
        for (SectionCandidate section : selected) {
            List<String> requestedIds = groupIds;
            List<String> selectedIds = requestedIds.isEmpty() ? null : old.displayedGroups().stream()
                    .filter(group -> section.segmentId().equals(group.segmentId())
                            && requestedIds.contains(group.groupId()))
                    .map(Group::groupId).toList();
            Request groupRequest = new Request(year, projectId, section.segmentId(), groupName, selectedIds,
                    date, time, location, groupChoice);
            Response response = snapshots.find(context, "groups:" + section.segmentId(), groupRequest, Response.class, refresh);
            boolean reused = response != null;
            if (response == null) {
                response = fromCompleteSnapshot(context, groupRequest, refresh);
                reused = response != null;
            }
            if (response == null) {
                response = services.getObject().query(groupRequest);
                snapshots.remember(context, "groups:" + section.segmentId(), groupRequest, response);
            }
            boolean waiting = "NEEDS_RETURN_CHOICE".equals(response.resultStatus());
            needsRange |= waiting;
            total += response.totalCount();
            displayed.addAll(waiting ? response.examples() : response.items());
            results.add(new SectionResult(section.segmentId(), section.segmentName(), response, reused));
        }
        var scope = new ProjectQueryModels.Context(year, project.projectName(), List.of(projectId), List.of(project),
                sectionName, selected.stream().map(SectionCandidate::segmentId).toList(), selected, null, 1);
        boolean details = current(args, "memberDetails", question)
                || old != null && old.pendingScope() != null && old.memberDetails();
        Memory next = new Memory(2, projectId, sections, scope.selectedSectionIds(), displayed, selectedGroupIds,
                dateExpression, groupName, time, location, needsRange ? "GROUP" : null, details);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("project", projectResult.items());
        evidence.put("totalSectionCount", sections.size());
        evidence.put("queriedSectionCount", selected.size());
        evidence.put("totalGroupCount", total);
        evidence.put("countScope", !groupIds.isEmpty() ? "已返回/已选考勤组"
                : selected.size() == sections.size() ? "整个项目（保留当前组名、日期、时段及地点筛选）"
                : "已选标段（保留当前组名、日期、时段及地点筛选）");
        evidence.put("sectionResults", results);
        evidence.put("memberDetails", details);
        evidence.put("nextAction", needsRange ? "ASK_GROUP_RETURN_SCOPE" : "DISPLAY_RESULTS_NO_CONFIRMATION");
        return finish(needsRange ? State.NEEDS_CLARIFICATION : State.SUCCESS, needsRange ? "returnScope" : null,
                evidence, context, scope, next, details);
    }

    private Response fromCompleteSnapshot(Map<String, Object> context, Request request, boolean refresh) {
        Response stored = snapshots.latest(context, "groups:" + request.segmentId(), Response.class, refresh);
        if (stored == null || "NEEDS_RETURN_CHOICE".equals(stored.resultStatus())) return null;
        boolean coveredSelection = request.groupIds() != null && stored.items().stream().map(Group::groupId)
                .toList().containsAll(request.groupIds());
        if (stored.items().size() != stored.totalCount() && !coveredSelection) return null;
        Filters f = stored.appliedFilters();
        if (!f.projectId().equals(request.projectId()) || f.projectYear() != request.projectYear()
                || !f.attendanceDate().equals(request.attendanceDate()) || !Objects.equals(f.groupName(), request.groupName())
                || !Objects.equals(f.attendanceTime(), request.attendanceTime()) || !Objects.equals(f.location(), request.location())
                || (!f.groupIds().isEmpty() && !coveredSelection
                    && !Objects.equals(f.groupIds(), request.groupIds()))) return null;
        List<Group> groups = stored.items().stream().filter(group -> request.groupIds() == null
                || request.groupIds().contains(group.groupId())).toList();
        boolean needs = ReturnPolicy.needsChoice(groups.size(), request.returnChoice());
        List<Group> visible = ReturnPolicy.visible(groups, request.returnChoice());
        return new Response(groups.isEmpty() ? "EMPTY" : needs ? "NEEDS_RETURN_CHOICE" : "OK",
                new Filters(request.projectYear(), request.projectId(), request.segmentId(), request.groupName(),
                        request.groupIds() == null ? List.of() : request.groupIds(), request.attendanceDate(),
                        request.attendanceTime(), request.location(), "1", "0", ProjectQueryService.BUSINESS_ZONE.getId()),
                groups.size(), needs ? 0 : visible.size(), needs ? visible : List.of(), needs ? List.of() : visible,
                stored.notice(), stored.dataAsOf());
    }

    private Result finish(State state, String missing, Object evidence, Map<String, Object> context,
                          ProjectQueryModels.Context project, Memory memory, boolean details) {
        if (project != null) context.putAll(references.contextMap(project));
        if (memory == null) context.remove(MEMORY); else context.put(MEMORY, memory);
        context.put("activeBusinessTool", ID);
        ObjectNode tree = mapper.valueToTree(Map.of("evidence", evidence, "memberDetails", details));
        if (!details) namesOnly(tree);
        Map<String, String> resolved = project == null ? Map.of() : Map.of("projectYear", String.valueOf(project.projectYear()));
        return new Result(state, tree.toString(), missing, resolved, context);
    }

    private void namesOnly(com.fasterxml.jackson.databind.JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.get("memberUsers") instanceof ArrayNode array) {
                for (var item : array) if (item instanceof ObjectNode member) member.retain("userName");
            }
            object.elements().forEachRemaining(this::namesOnly);
        } else if (node.isArray()) node.forEach(this::namesOnly);
    }

    @Override
    public Map<String, Object> modelContext(Map<String, Object> context) {
        Map<String, Object> visible = new LinkedHashMap<>();
        if (context.containsKey("projectQuery")) visible.put("projectQuery", context.get("projectQuery"));
        Memory memory = memory(context);
        if (memory != null) {
            ObjectNode tree = mapper.valueToTree(memory);
            tree.remove("sections"); // 已保存的全部候选只在后端；本轮展示顺序见 projectQuery。
            if (tree.get("displayedGroups") instanceof ArrayNode array) {
                for (var item : array) if (item instanceof ObjectNode group) group.remove("memberUsers");
            }
            visible.put(MEMORY, tree);
        }
        return visible;
    }

    private Memory memory(Map<String, Object> context) {
        try {
            Memory memory = mapper.convertValue(context.get(MEMORY), Memory.class);
            return memory != null && memory.version() == 2 ? memory : null;
        } catch (IllegalArgumentException incompatible) {
            return null;
        }
    }

    private ProjectQueryModels.Context projectContext(ProjectQueryModels.Response result, List<String> ids, String scope) {
        return new ProjectQueryModels.Context(result.appliedFilters().projectYear(), result.appliedFilters().projectName(),
                ids, result.candidates(), null, List.of(), result.sectionCandidates(), scope, result.summary().totalProjectCount());
    }

    private boolean current(Map<String, String> args, String key, String question) {
        return args.containsKey(key) && question != null && question.contains(args.get(key));
    }

    private String filter(Map<String, String> args, String key, String fallback) {
        return args.containsKey(key) ? args.get(key) : fallback;
    }

    private int year(String value) {
        int now = Year.now(ProjectQueryService.BUSINESS_ZONE).getValue();
        return switch (value) {
            case "今年", "当前年度", "本年" -> now;
            case "去年" -> now - 1;
            case "前年" -> now - 2;
            case "明年" -> now + 1;
            default -> {
                if (!value.matches("(?:19|20|21)\\d{2}年?")) throw new IllegalArgumentException("立项年度格式无效");
                yield Integer.parseInt(value.replace("年", ""));
            }
        };
    }

    private LocalDate date(String value) {
        LocalDate today = LocalDate.now(ProjectQueryService.BUSINESS_ZONE);
        return switch (value) {
            case "今天", "今日" -> today;
            case "昨天", "昨日" -> today.minusDays(1);
            case "明天" -> today.plusDays(1);
            default -> LocalDate.parse(value);
        };
    }

    public record SectionSnapshot(List<SectionCandidate> items) {}
}
