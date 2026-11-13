package edu.lcl.rag.business.project;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 名称模糊匹配阈值；默认最多三个示例，用户可指定全部或前 N 条，不提供分页配置。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "business.project-query")
public class ProjectQueryProperties {
    private double fuzzyMinScore = 0.55;
}
