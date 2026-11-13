package edu.lcl.rag.business.attendance;

import edu.lcl.rag.business.ReturnPolicy;
import edu.lcl.rag.business.project.ProjectQueryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static edu.lcl.rag.business.attendance.AttendanceGroupModels.*;

/**
 * 参考旧 getConfList/getGroupConf 的读取语义，不调用旧 HTTP 或写服务。
 * 每次查询必须有真实项目和标段 ID；日期、启用、删除、父级归属条件在 SQL 中执行。
 * 本服务按用户确认的项目/标段范围查询，不模拟旧系统登录角色或声称拥有租户授权隔离。
 */
@Service
@ConditionalOnProperty(prefix = "business.datasource", name = "enabled", havingValue = "true")
public class AttendanceGroupQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AttendanceGroupQueryService(@Qualifier("businessJdbcTemplate") JdbcTemplate jdbc,
                                      @Qualifier("businessReadOnlyTransactionTemplate") TransactionTemplate template) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(template.getTransactionManager()), template);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Response query(Request request) {
        if (request == null) throw new IllegalArgumentException("必须指定项目 ID 和标段 ID");
        validateId(request.projectId());
        validateId(request.segmentId());
        if (request.groupIds() != null && (request.groupIds().isEmpty()
                || request.groupIds().stream().anyMatch(value -> value == null || !value.matches("[A-Za-z0-9_-]{1,64}")))) {
            throw new IllegalArgumentException("考勤组 ID 无效");
        }
        int year = request.projectYear() == null ? Year.now(ProjectQueryService.BUSINESS_ZONE).getValue()
                : request.projectYear();
        if (year < 1900 || year > 2199) throw new IllegalArgumentException("立项年度无效");
        LocalDate date = request.attendanceDate() == null ? LocalDate.now(ProjectQueryService.BUSINESS_ZONE)
                : request.attendanceDate();
        String time = text(request.attendanceTime());
        if (time != null && !time.matches("\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[-~～至—]\\s*\\d{1,2}:\\d{2}(?::\\d{2})?)?")) {
            throw new IllegalArgumentException("考勤时间应为 HH:mm 或 HH:mm-HH:mm");
        }
        String[] times = time == null ? new String[0] : time.split("\\s*[-~～至—]\\s*");
        LocalTime parsedTime = times.length == 0 ? null : parseTime(times[0]);
        LocalTime endTime = times.length < 2 ? null : parseTime(times[1]);
        Filters filters = new Filters(year, request.projectId(), request.segmentId(), text(request.groupName()),
                request.groupIds() == null ? List.of() : List.copyOf(request.groupIds()), date, time,
                text(request.location()), "1", "0", ProjectQueryService.BUSINESS_ZONE.getId());
        return Objects.requireNonNull(transaction.execute(status -> fetch(filters, parsedTime, endTime, request)));
    }

    private LocalTime parseTime(String value) {
        return LocalTime.parse(value.matches("\\d:.*") ? "0" + value : value);
    }

    private Response fetch(Filters filters, LocalTime time, LocalTime endTime, Request request) {
        MapSqlParameterSource args = new MapSqlParameterSource("projectId", filters.projectId())
                .addValue("segmentId", filters.segmentId()).addValue("year", filters.projectYear())
                .addValue("date", filters.attendanceDate());
        String extra = "";
        if (filters.groupName() != null) {
            extra += " AND position(:name in coalesce(g.att_group_name,''))>0";
            args.addValue("name", filters.groupName());
        }
        if (!filters.groupIds().isEmpty()) {
            extra += " AND g.id IN (:groupIds)";
            args.addValue("groupIds", filters.groupIds());
        }
        if (filters.location() != null) {
            extra += """
                     AND (position(:location in coalesce(g.att_point_address,''))>0
                       OR position(:location in coalesce(g.build_area,''))>0)
                    """;
            args.addValue("location", filters.location());
        }
        if (endTime != null) {
            // 指定完整时段时匹配配置起止时刻；单个钟点才判断是否落入时段。
            extra += " AND g.att_start_time=:time AND g.att_end_time=:endTime";
            args.addValue("time", time).addValue("endTime", endTime);
        } else if (time != null) {
            // 跨午夜时段按 OR 处理；不传时间则绝不按当前钟点隐藏考勤组。
            extra += """
                     AND ((g.att_start_time<=g.att_end_time AND :time BETWEEN g.att_start_time AND g.att_end_time)
                       OR (g.att_start_time>g.att_end_time AND (:time>=g.att_start_time OR :time<=g.att_end_time)))
                    """;
            args.addValue("time", time);
        }
        List<Group> groups = jdbc.query("""
                SELECT g.id, g.att_group_name, g.project_id, p.xmmc AS project_name,
                       g.segment_id, s.section_name AS segment_name,
                       g.att_start_day::date AS start_day, g.att_end_day::date AS end_day,
                       g.att_days, g.att_start_time, g.att_end_time, g.att_duration, g.att_status,
                       g.att_point, g.att_point_coordinate, g.att_point_address, g.att_project_area,
                       g.build_area, g.att_range, g.can_out,
                       (SELECT count(*) FROM farmland.attendance_group_user m
                        WHERE m.att_group_id=g.id AND m.del_flag='0') AS member_count
                FROM farmland.attendance_group g
                JOIN farmland.project p ON p.id::text=g.project_id
                  AND p.del_flag='0' AND p.project_stage='组织实施' AND p.nf=:year
                JOIN farmland.cp_bid_section s ON s.id::text=g.segment_id
                  AND s.project_id=p.id AND s.del_flag='0'
                WHERE g.del_flag='0' AND g.att_status='1'
                  AND g.project_id=:projectId AND g.segment_id=:segmentId
                  AND g.att_start_day::date<=:date AND g.att_end_day::date>=:date
                """ + extra + " ORDER BY g.att_start_day, g.att_start_time, g.att_group_name, g.id",
                args, (rs, index) -> group(rs));
        long total = groups.size();
        boolean needsChoice = ReturnPolicy.needsChoice(total, request.returnChoice());
        List<Group> visible = ReturnPolicy.visible(groups, request.returnChoice());
        List<Group> items = List.of();
        if (!needsChoice && !visible.isEmpty()) {
            Map<String, List<Member>> members = members(visible.stream().map(Group::groupId).toList());
            items = visible.stream().map(group -> group.withMembers(
                    members.getOrDefault(group.groupId(), List.of()))).toList();
        }
        return new Response(total == 0 ? "EMPTY" : needsChoice ? "NEEDS_RETURN_CHOICE" : "OK",
                filters, total, items.size(), needsChoice ? visible : List.of(), items,
                "仅查询所选项目及标段下，指定日期有效且启用的未删除考勤组。默认日期为北京时间当天，起止日期均包含。"
                        + "纳入人数和需要打卡人数均按有效配置成员计数；成员列表不截断。"
                        + "同名组仍按日期、时间和地点区分，不自动合并。无有效日期配置的组不算日期有效。",
                OffsetDateTime.now(ProjectQueryService.BUSINESS_ZONE).toString());
    }

    private Map<String, List<Member>> members(List<String> groupIds) {
        // 先取成员再关联描述，岗位编码重复时聚合名称，防止 JOIN 放大人数。
        List<Member> members = jdbc.query("""
                SELECT m.id, m.att_group_id, m.user_id, m.user_name, m.subject_id, m.subject_type, m.user_role,
                       u.dwmc AS unit_name,
                       (SELECT string_agg(DISTINCT r.role_name,'、' ORDER BY r.role_name)
                        FROM farmland.medox_sys_role r
                        WHERE r.role_code=m.user_role AND r.del_flag='0') AS role_name
                FROM farmland.attendance_group_user m
                LEFT JOIN farmland.unit_management u ON u.id=m.subject_id AND u.del_flag='0'
                WHERE m.del_flag='0' AND m.att_group_id IN (:ids)
                ORDER BY m.att_group_id, m.subject_type, u.dwmc, m.user_name, m.id
                """, new MapSqlParameterSource("ids", groupIds), (rs, index) -> new Member(
                rs.getString("id"), rs.getString("att_group_id"), rs.getString("user_id"), rs.getString("user_name"),
                rs.getString("subject_id"), rs.getString("subject_type"), subjectName(rs.getString("subject_type")),
                rs.getString("unit_name"), rs.getString("user_role"), rs.getString("role_name")));
        Map<String, List<Member>> grouped = new HashMap<>();
        members.forEach(member -> grouped.computeIfAbsent(member.groupId(), key -> new ArrayList<>()).add(member));
        return grouped;
    }

    private Group group(ResultSet rs) throws SQLException {
        long count = rs.getLong("member_count");
        return new Group(rs.getString("id"), rs.getString("att_group_name"), rs.getString("project_id"),
                rs.getString("project_name"), rs.getString("segment_id"), rs.getString("segment_name"),
                rs.getObject("start_day", LocalDate.class), rs.getObject("end_day", LocalDate.class),
                rs.getBigDecimal("att_days"), rs.getObject("att_start_time", LocalTime.class),
                rs.getObject("att_end_time", LocalTime.class), rs.getBigDecimal("att_duration"),
                rs.getString("att_status"), rs.getString("att_point"), rs.getString("att_point_coordinate"),
                rs.getString("att_point_address"), rs.getString("att_project_area"), rs.getString("build_area"),
                rs.getObject("att_range", Integer.class), rs.getString("can_out"), count, count, List.of());
    }

    private String subjectName(String code) {
        return switch (code == null ? "" : code) {
            case "jsdw" -> "建设单位";
            case "jldw" -> "监理单位";
            case "sgdw" -> "施工单位";
            case "sjdw" -> "设计单位";
            case "dsfjcdw" -> "第三方检测单位";
            default -> code;
        };
    }

    private String text(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 200) throw new IllegalArgumentException("名称或地点不超过 200 字");
        return value.strip();
    }

    private void validateId(String value) {
        if (value == null || !value.matches("[1-9]\\d{0,18}")
                || new BigInteger(value).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("必须提供有效的项目和标段 bigint ID");
        }
    }
}
