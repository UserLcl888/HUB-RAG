package edu.lcl.rag.business.project;

import lombok.Data;
import edu.lcl.rag.business.ReturnPolicy.Choice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** ID 均以字符串传输；金额字段在名称中明确单位，不返回几何、身份证等无关字段。 */
public final class ProjectQueryModels {
    private ProjectQueryModels() {}

    public record Request(Integer projectYear, String projectName, List<String> projectIds,
                          String sectionName, List<String> sectionIds,
                          Choice projectReturn, Choice sectionReturn) {
        public Request(Integer projectYear, String projectName, List<String> projectIds,
                       String sectionName, List<String> sectionIds) {
            this(projectYear, projectName, projectIds, sectionName, sectionIds, null, null);
        }
    }

    public record AppliedFilters(int projectYear, String projectName, List<String> projectIds,
                                 String timezone, String projectStage, String delFlag,
                                 String sectionName, List<String> sectionIds,
                                 Choice projectReturn, Choice sectionReturn) {}

    public record DistrictCount(String districtCode, String districtName, long projectCount) {}
    public record Summary(long totalProjectCount, long totalSectionCount, Long matchedSectionCount,
                          List<DistrictCount> districts) {}
    public record Selection(String scope, String reason) {}

    public record Response(String resultStatus, String matchMode, AppliedFilters appliedFilters,
                           Summary summary, List<ProjectInfo> items, List<Candidate> candidates,
                           List<SectionCandidate> sectionCandidates, Selection selection,
                           List<String> notices, String dataAsOf) {}

    /** 仅存本轮真实展示的候选，用于下轮按序号/名称安全解析，不信任模型生成的项目 ID。 */
    public record Candidate(String projectId, String projectName, String districtName) {}
    public record SectionCandidate(String segmentId, String projectId, String segmentName, String segmentCode) {}
    public record Context(int projectYear, String projectName,
                          List<String> selectedProjectIds, List<Candidate> displayedCandidates,
                          String sectionName, List<String> selectedSectionIds,
                          List<SectionCandidate> displayedSections, String selectionScope,
                          long totalProjectCount) {}

    @Data
    public static class ProjectInfo {
        private String projectId;
        private String projectName;
        private Integer projectYear;
        /** 对应 xmbm：项目编号，沿用业务文档的对外字段名。 */
        private String projectCode;
        /** 对应 xmdm：项目代码，不与项目编号合并。 */
        private String projectExternalCode;
        private String districtCode;
        private String districtName;
        private String projectStage;
        private String constructionStatusCode;
        private String constructionStatusName;
        private String businessStatus;
        private BigDecimal constructionAreaMu;
        private BigDecimal newConstructionAreaMu;
        private BigDecimal renovationAreaMu;
        private BigDecimal totalInvestmentWanYuan;
        private BigDecimal progressPercent;
        private String constructionAddress;
        private String supervisingDepartment;
        private long sectionCount;
        /** 项目尚待选择时也展示真实标段名称；超过三个只列示例，不提前加载完整标段详情。 */
        private List<String> sectionNames = List.of();
        private boolean sectionNamesTruncated;
        private List<SectionInfo> sections = List.of();
    }

    @Data
    public static class SectionInfo {
        private String segmentId;
        private String projectId;
        private String segmentCode;
        private String segmentName;
        private String statusCode;
        private LocalDate startDate;
        private LocalDate completionDate;
        private String winningBidUnitName;
        private String implementationUnitName;
        private String supervisionUnitName;
        private BigDecimal contractAmountYuan;
        private BigDecimal winningBidAmountYuan;
        private BigDecimal completedAreaMu;
    }
}
