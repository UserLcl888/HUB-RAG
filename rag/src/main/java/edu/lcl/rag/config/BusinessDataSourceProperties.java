package edu.lcl.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 独立业务库的连接参数；只读限制在配置类中强制设置，不提供写入开关。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "business.datasource")
public class BusinessDataSourceProperties {

    private String url;
    private String username;
    private String password;
    private Hikari hikari = new Hikari();
    private int queryTimeoutSeconds = 10;

    /** 对应 business.datasource.hikari，由业务数据源配置类显式应用。 */
    @Getter
    @Setter
    public static class Hikari {
        private int minIdle = 1;
        private int maxPoolSize = 5;
        private String connectionTestQuery = "SELECT 1";
        private long connectionTimeout = 5000;
    }
}
