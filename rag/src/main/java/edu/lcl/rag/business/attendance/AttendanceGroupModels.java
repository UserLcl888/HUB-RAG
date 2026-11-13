package edu.lcl.rag.business.attendance;

import edu.lcl.rag.business.ReturnPolicy.Choice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import edu.lcl.rag.business.project.ProjectQueryModels.SectionCandidate;

/** 完整成员保留于响应/会话，交给回答模型前按是否追问详细人员信息投影。 */
public final class AttendanceGroupModels {
    private AttendanceGroupModels() {}

    public record Request(Integer projectYear, String projectId, String segmentId, String groupName,
                          List<String> groupIds, LocalDate attendanceDate, String attendanceTime,
                          String location, Choice returnChoice) {}

    public record Filters(int projectYear, String projectId, String segmentId, String groupName,
                          List<String> groupIds, LocalDate attendanceDate, String attendanceTime,
                          String location, String status, String delFlag, String timezone) {}

    /** 列表示例也带日期、时间和地点，禁止仅凭同名组擅自选择一个。 */
    public record Group(String groupId, String groupName, String projectId, String projectName,
                        String segmentId, String segmentName, LocalDate startDay, LocalDate endDay,
                        BigDecimal requiredDays, LocalTime startTime, LocalTime endTime,
                        BigDecimal requiredHours, String status, String pointEnabled,
                        String pointCoordinate, String pointAddress, String projectAreaEnabled,
                        String buildAddress, Integer rangeMeters, String allowOut,
                        long memberCount, long requiredMemberCount, List<Member> memberUsers) {
        public Group withMembers(List<Member> members) {
            return new Group(groupId, groupName, projectId, projectName, segmentId, segmentName,
                    startDay, endDay, requiredDays, startTime, endTime, requiredHours, status,
                    pointEnabled, pointCoordinate, pointAddress, projectAreaEnabled, buildAddress,
                    rangeMeters, allowOut, members.size(), members.size(), members);
        }
    }

    public record Member(String id, String groupId, String userId, String userName, String subjectId,
                         String subjectType, String subjectTypeName, String unitName,
                         String userRole, String userRoleName) {}

    public record Response(String resultStatus, Filters appliedFilters, long totalCount, int returnedCount,
                           List<Group> examples, List<Group> items, String notice, String dataAsOf) {}

    public record Context(Filters filters, List<Group> displayedGroups, long totalCount,
                          boolean needsReturnChoice, boolean memberDetails) {}

    /** 第二版会话范围支持多个标段；完整查询结果另存在 querySnapshots，不重复塞进路由输入。 */
    public record Memory(int version, String projectId, List<SectionCandidate> sections,
                         List<String> selectedSectionIds, List<Group> displayedGroups,
                         List<String> selectedGroupIds, String dateExpression, String groupName,
                         String attendanceTime, String location, String pendingScope,
                         boolean memberDetails) {}

    public record SectionResult(String segmentId, String segmentName, Response result, boolean reused) {}
}
