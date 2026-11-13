package edu.lcl.rag.business.project;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static edu.lcl.rag.business.project.ProjectQueryModels.*;
import edu.lcl.rag.business.ReturnPolicy;

/**
 * 独立业务查询服务：所有数据语句均为固定 SELECT，参数绑定，不接受 SQL、表名或数据源。
 * 仅查询 farmland.project / farmland.cp_bid_section；不依赖登录角色、考勤或默认 RAG 数据源。
 */
@Service
@ConditionalOnProperty(prefix = "business.datasource", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ProjectQueryProperties.class)
public class ProjectQueryService {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    /** 未明确要求返回范围时的示例数量，不是分页大小。 */
    public static final int DETAIL_LIMIT = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate readOnlyTransaction;
    private final ProjectQueryProperties properties;
    private final ProjectNameMatcher matcher;

    public ProjectQueryService(@Qualifier("businessJdbcTemplate") JdbcTemplate jdbc,
                               @Qualifier("businessReadOnlyTransactionTemplate") TransactionTemplate transaction,
                               ProjectQueryProperties properties, ProjectNameMatcher matcher) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.readOnlyTransaction = new TransactionTemplate(
                Objects.requireNonNull(transaction.getTransactionManager()), transaction);
        this.readOnlyTransaction.setReadOnly(true);
        // 总数、项目和标段处于同一只读快照，不出现 JOIN 使项目数翻倍或前后统计不一致。
        this.readOnlyTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.properties = properties;
        this.matcher = matcher;
        if (!Double.isFinite(properties.getFuzzyMinScore())
                || properties.getFuzzyMinScore() <= 0 || properties.getFuzzyMinScore() > 1) {
            throw new IllegalArgumentException("项目查询配置无效");
        }
    }

    public Response query(Request request) {
        Request normalized = normalize(request);
        return Objects.requireNonNull(readOnlyTransaction.execute(status -> fetch(normalized)));
    }

    /** 考勤组查询的标段选择阶段只需真实轻量候选，不提前读取所有标段详情。 */
    public List<SectionCandidate> sectionCandidates(int projectYear, String projectId) {
        Request scope = normalize(new Request(projectYear, null, List.of(projectId), null, null));
        return Objects.requireNonNull(readOnlyTransaction.execute(status -> jdbc.query("""
                SELECT s.id::text AS id, s.project_id::text AS project_id, s.section_name, s.section_no
                FROM farmland.cp_bid_section s
                JOIN farmland.project p ON p.id=s.project_id
                WHERE s.del_flag='0' AND p.del_flag='0' AND p.project_stage='组织实施'
                  AND p.nf=:year AND p.id=:id
                ORDER BY s.project_id, s.section_no NULLS LAST, s.id
                """, new MapSqlParameterSource("year", scope.projectYear())
                        .addValue("id", Long.valueOf(projectId)),
                (rs, index) -> new SectionCandidate(rs.getString("id"), rs.getString("project_id"),
                        rs.getString("section_name"), rs.getString("section_no")))));
    }

    private Request normalize(Request request) {
        Request value = request == null ? new Request(null, null, null, null, null) : request;
        int year = value.projectYear() == null ? Year.now(BUSINESS_ZONE).getValue() : value.projectYear();
        if (year < 1900 || year > 2199) {
            throw new IllegalArgumentException("立项年度应为 1900 至 2199 的四位年份");
        }
        String name = value.projectName() == null || value.projectName().isBlank()
                ? null : value.projectName().strip();
        if (name != null && (name.length() > 200 || matcher.normalize(name).length() < 2)) {
            throw new IllegalArgumentException("请提供至少两个有效字符的项目名称，且不超过 200 字");
        }
        String sectionName = value.sectionName() == null || value.sectionName().isBlank()
                ? null : value.sectionName().strip();
        if (sectionName != null && (sectionName.length() > 200 || matcher.normalize(sectionName).isBlank())) {
            throw new IllegalArgumentException("请提供有效标段名称，且不超过 200 字");
        }
        return new Request(year, name, normalizeIds(value.projectIds()), sectionName,
                normalizeIds(value.sectionIds()), value.projectReturn(), value.sectionReturn());
    }

    private List<String> normalizeIds(List<String> ids) {
        if (ids != null) {
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("项目或标段 ID 列表不能为空");
            }
            ids = ids.stream().map(id -> {
                if (id == null || !id.matches("[1-9]\\d{0,18}")
                        || new BigInteger(id).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                    throw new IllegalArgumentException("项目或标段 ID 必须是有效的正 bigint 字符串");
                }
                return id;
            }).distinct().toList();
        }
        return ids;
    }

    private Response fetch(Request request) {
        MapSqlParameterSource args = new MapSqlParameterSource("year", request.projectYear());
        String idsFilter = "";
        if (request.projectIds() != null) {
            idsFilter = " AND p.id IN (:projectIds)";
            args.addValue("projectIds", request.projectIds().stream().map(Long::valueOf).toList());
        }
        // 年度内只取轻量候选字段，在内存做名称匹配，不读取几何、附件或大 JSON。
        List<ProjectCandidate> candidates = jdbc.query("""
                SELECT p.id::text AS id, p.xmmc, p.xzqhdm, p.xzqhmc
                FROM farmland.project p
                WHERE p.del_flag='0' AND p.project_stage='组织实施' AND p.nf=:year
                """ + idsFilter + " ORDER BY p.id DESC", args,
                (rs, index) -> new ProjectCandidate(rs.getString("id"), rs.getString("xmmc"),
                        rs.getString("xzqhdm"), rs.getString("xzqhmc")));
        Match match = match(candidates, request.projectName());
        List<ProjectCandidate> matched = match.items();
        List<String> allIds = matched.stream().map(ProjectCandidate::id).toList();
        long total = matched.size();
        long totalSections = allIds.isEmpty() ? 0 : Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM farmland.cp_bid_section
                WHERE del_flag='0' AND project_id IN (:ids)
                """, idArgs(allIds), Long.class));
        Map<District, Long> districts = new LinkedHashMap<>();
        matched.forEach(item -> districts.merge(new District(item.districtCode(), item.districtName()), 1L, Long::sum));
        List<DistrictCount> distribution = districts.entrySet().stream()
                .map(entry -> new DistrictCount(entry.getKey().code(), entry.getKey().name(), entry.getValue())).toList();
        boolean ambiguous = total > 0 && request.projectIds() == null
                && request.projectName() != null
                && (total > 1 || "FUZZY".equals(match.mode()));
        List<ProjectCandidate> visible = ReturnPolicy.visible(matched, request.projectReturn());
        List<Candidate> examples = visible.stream()
                .map(item -> new Candidate(item.id(), item.name(), item.districtName())).toList();
        boolean projectChoice = ReturnPolicy.needsChoice(total, request.projectReturn());
        // 未获用户确认时不加载超过三条的详情；明确全部/前 N 条后才加载指定范围。
        List<ProjectInfo> items = List.of();
        SectionResult sectionResult = new SectionResult(null, List.of(), false);
        if (total > 0 && !projectChoice) {
            List<String> visibleIds = visible.stream().map(ProjectCandidate::id).toList();
            Map<String, ProjectInfo> details = loadDetails(visibleIds);
            items = visibleIds.stream().map(details::get).toList();
            sectionResult = loadSections(items, visibleIds, request, !ambiguous);
        }
        Selection selection = projectChoice
                ? new Selection("PROJECT_DISPLAY", "先告知匹配总数并举三个真实项目例子，询问全部返回还是返回前几条；也可选择项目或补充名称。")
                : ambiguous ? new Selection("PROJECT", "请按真实候选的名称或序号确认项目；也可补充更具体的名称。")
                : sectionResult.needsSelection()
                    ? new Selection(sectionResult.displayChoice() ? "SECTION_DISPLAY" : "SECTION",
                            "请根据真实标段确认；超过三个且尚未指定返回范围时，先告知总数并举例，询问全部返回还是返回前几条。")
                    : null;
        List<String> notices = new ArrayList<>();
        notices.add("仅查询组织实施阶段且未删除的项目与标段；阶段不等同于建设状态，不据此推断已实际开工。");
        if ("FUZZY".equals(match.mode())) {
            notices.add("名称未直接匹配，返回的是同年度组织实施阶段的相近名称候选，不代表已确认用户所指项目。");
        }
        if (request.projectIds() != null) {
            notices.add("已选项目仍须满足当前年度和组织实施阶段条件；不存在、已删除或不满足条件的项目不会返回。");
        }
        return new Response(total == 0 ? "EMPTY" : selection != null ? "NEEDS_SELECTION" : "OK", match.mode(),
                new AppliedFilters(request.projectYear(), request.projectName(),
                        request.projectIds() == null ? List.of() : request.projectIds(), BUSINESS_ZONE.getId(),
                        "组织实施", "0", request.sectionName(),
                        request.sectionIds() == null ? List.of() : request.sectionIds(),
                        request.projectReturn(), request.sectionReturn()),
                new Summary(total, totalSections, sectionResult.matchedCount(), distribution), items,
                examples, sectionResult.examples(), selection,
                notices, OffsetDateTime.now(BUSINESS_ZONE).toString());
    }

    private Match match(List<ProjectCandidate> candidates, String query) {
        if (query == null) return new Match("YEAR", candidates);
        String normalized = matcher.normalize(query);
        List<ProjectCandidate> exact = candidates.stream()
                .filter(item -> item.name() != null && item.name().strip().equals(query)).toList();
        if (!exact.isEmpty()) return new Match("EXACT", exact);
        List<ProjectCandidate> contained = candidates.stream()
                .filter(item -> matcher.normalize(item.name()).contains(normalized)).toList();
        if (!contained.isEmpty()) return new Match("CONTAINS", contained);
        List<ScoredCandidate> scored = candidates.stream()
                .map(item -> new ScoredCandidate(item,
                        matcher.fuzzyScore(normalized, matcher.normalize(item.name()))))
                .filter(item -> item.score() >= properties.getFuzzyMinScore())
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparing(item -> new BigInteger(item.item().id()), Comparator.reverseOrder()))
                .toList();
        return new Match("FUZZY", scored.stream().map(ScoredCandidate::item).toList());
    }

    private Map<String, ProjectInfo> loadDetails(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        List<ProjectInfo> details = jdbc.query("""
                SELECT p.id::text AS id, p.xmmc, p.nf, p.xmbm, p.xmdm, p.xzqhdm, p.xzqhmc,
                       p.project_stage, p.jszt, p.xmzt, p.ghmj, p.xzjsmj, p.gztsmj, p.ztz, p.zjd,
                       p.jsdd, p.main_dep
                FROM farmland.project p
                WHERE p.del_flag='0' AND p.project_stage='组织实施' AND p.id IN (:ids)
                """, idArgs(ids), (rs, index) -> project(rs));
        Map<String, ProjectInfo> result = new HashMap<>();
        details.forEach(item -> result.put(item.getProjectId(), item));
        return result;
    }

    private SectionResult loadSections(List<ProjectInfo> items, List<String> ids, Request request,
                                       boolean projectConfirmed) {
        MapSqlParameterSource args = idArgs(ids);
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("""
                SELECT project_id::text AS project_id, count(*) AS total
                FROM farmland.cp_bid_section WHERE del_flag='0' AND project_id IN (:ids)
                GROUP BY project_id
                """, args, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                counts.put(rs.getString("project_id"), rs.getLong("total")));
        items.forEach(item -> item.setSectionCount(counts.getOrDefault(item.getProjectId(), 0L)));
        List<SectionCandidate> candidates = jdbc.query("""
                SELECT id::text AS id, project_id::text AS project_id, section_name, section_no
                FROM farmland.cp_bid_section WHERE del_flag='0' AND project_id IN (:ids)
                ORDER BY project_id, section_no NULLS LAST, id
                """, args, (rs, index) -> new SectionCandidate(rs.getString("id"),
                rs.getString("project_id"), rs.getString("section_name"), rs.getString("section_no")));
        Map<String, List<SectionCandidate>> byProject = new LinkedHashMap<>();
        candidates.forEach(section -> byProject.computeIfAbsent(section.projectId(), key -> new ArrayList<>())
                .add(section));
        items.forEach(project -> {
            List<SectionCandidate> names = byProject.getOrDefault(project.getProjectId(), List.of());
            project.setSectionNames(names.stream().limit(DETAIL_LIMIT).map(SectionCandidate::segmentName).toList());
            project.setSectionNamesTruncated(names.size() > DETAIL_LIMIT);
        });
        // 仅补充项目简介中的标段名称，不改变项目确认及标段详情的范围规则。
        if (!projectConfirmed) return new SectionResult(null, List.of(), false);
        if (request.sectionIds() != null) {
            candidates = candidates.stream().filter(item -> request.sectionIds().contains(item.segmentId())).toList();
        }
        boolean fuzzy = false;
        if (request.sectionName() != null) {
            String query = matcher.normalize(request.sectionName());
            List<SectionCandidate> direct = candidates.stream()
                    .filter(item -> matcher.normalize(item.segmentName()).contains(query)).toList();
            if (direct.isEmpty()) {
                fuzzy = true;
                direct = candidates.stream()
                        .filter(item -> matcher.fuzzyScore(query, matcher.normalize(item.segmentName()))
                                >= properties.getFuzzyMinScore())
                        .sorted(Comparator.comparingDouble((SectionCandidate item) ->
                                matcher.fuzzyScore(query, matcher.normalize(item.segmentName()))).reversed())
                        .toList();
            }
            candidates = direct;
        }
        List<SectionCandidate> examples = ReturnPolicy.visible(candidates, request.sectionReturn());
        long total = candidates.size();
        boolean ambiguous = total > 0 && request.sectionIds() == null && request.sectionName() != null
                && (total > 1 || fuzzy);
        boolean displayChoice = ReturnPolicy.needsChoice(total, request.sectionReturn());
        if (total == 0 || displayChoice || ambiguous) {
            return new SectionResult(total, examples, displayChoice || ambiguous, displayChoice);
        }
        args.addValue("sectionIds", examples.stream().map(item -> Long.valueOf(item.segmentId())).toList());
        List<SectionInfo> sections = jdbc.query("""
                SELECT s.id::text AS id, s.project_id::text AS project_id, s.section_no, s.section_name,
                       s.status::text AS status, s.start_date, s.completion_date, s.winning_bid_unit_name,
                       s.implementation_unit_name, s.supervision_unit_name, s.contract_amount,
                       s.winning_bid_amount, s.completed_area
                FROM farmland.cp_bid_section s
                WHERE s.del_flag='0' AND s.project_id IN (:ids) AND s.id IN (:sectionIds)
                ORDER BY s.project_id, s.section_no NULLS LAST, s.id
                """, args, (rs, index) -> section(rs));
        Map<String, List<SectionInfo>> grouped = new HashMap<>();
        sections.forEach(item -> grouped.computeIfAbsent(item.getProjectId(), key -> new ArrayList<>()).add(item));
        items.forEach(item -> {
            List<SectionInfo> projectSections = grouped.getOrDefault(item.getProjectId(), List.of());
            item.setSections(projectSections);
        });
        return new SectionResult(total, examples, false);
    }

    private MapSqlParameterSource idArgs(List<String> ids) {
        return new MapSqlParameterSource("ids", ids.stream().map(Long::valueOf).toList());
    }

    private ProjectInfo project(ResultSet rs) throws SQLException {
        ProjectInfo item = new ProjectInfo();
        item.setProjectId(rs.getString("id"));
        item.setProjectName(rs.getString("xmmc"));
        item.setProjectYear(rs.getObject("nf", Integer.class));
        item.setProjectCode(rs.getString("xmbm"));
        item.setProjectExternalCode(rs.getString("xmdm"));
        item.setDistrictCode(rs.getString("xzqhdm"));
        item.setDistrictName(rs.getString("xzqhmc"));
        item.setProjectStage(rs.getString("project_stage"));
        String status = rs.getString("jszt");
        item.setConstructionStatusCode(status);
        item.setConstructionStatusName(switch (status == null ? "" : status) {
            case "0" -> "未开工";
            case "1" -> "在建";
            case "2" -> "已完工";
            default -> null;
        });
        item.setBusinessStatus(rs.getString("xmzt"));
        item.setConstructionAreaMu(rs.getBigDecimal("ghmj"));
        item.setNewConstructionAreaMu(rs.getBigDecimal("xzjsmj"));
        item.setRenovationAreaMu(rs.getBigDecimal("gztsmj"));
        item.setTotalInvestmentWanYuan(rs.getBigDecimal("ztz"));
        item.setProgressPercent(rs.getBigDecimal("zjd"));
        item.setConstructionAddress(rs.getString("jsdd"));
        item.setSupervisingDepartment(rs.getString("main_dep"));
        return item;
    }

    private SectionInfo section(ResultSet rs) throws SQLException {
        SectionInfo item = new SectionInfo();
        item.setSegmentId(rs.getString("id"));
        item.setProjectId(rs.getString("project_id"));
        item.setSegmentCode(rs.getString("section_no"));
        item.setSegmentName(rs.getString("section_name"));
        item.setStatusCode(rs.getString("status"));
        item.setStartDate(rs.getObject("start_date", LocalDate.class));
        item.setCompletionDate(rs.getObject("completion_date", LocalDate.class));
        item.setWinningBidUnitName(rs.getString("winning_bid_unit_name"));
        item.setImplementationUnitName(rs.getString("implementation_unit_name"));
        item.setSupervisionUnitName(rs.getString("supervision_unit_name"));
        item.setContractAmountYuan(rs.getBigDecimal("contract_amount"));
        item.setWinningBidAmountYuan(rs.getBigDecimal("winning_bid_amount"));
        item.setCompletedAreaMu(rs.getBigDecimal("completed_area"));
        return item;
    }

    private record ProjectCandidate(String id, String name, String districtCode, String districtName) {}
    private record ScoredCandidate(ProjectCandidate item, double score) {}
    private record Match(String mode, List<ProjectCandidate> items) {}
    private record District(String code, String name) {}
    private record SectionResult(Long matchedCount, List<SectionCandidate> examples, boolean needsSelection,
                                 boolean displayChoice) {
        private SectionResult(Long matchedCount, List<SectionCandidate> examples, boolean needsSelection) {
            this(matchedCount, examples, needsSelection, false);
        }
    }
}
