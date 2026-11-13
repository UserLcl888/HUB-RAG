package edu.lcl.rag.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

/**
 * 只读业务数据库，不注册实体、Repository 或建表初始化器。
 * defaultCandidate=false 保留 Spring Boot 对原 RAG 数据源、JdbcTemplate 和 JPA 事务的自动配置。
 * 未来查询必须显式注入这里的具名组件，不能混用原库的默认事务管理器。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BusinessDataSourceProperties.class)
@ConditionalOnProperty(prefix = "business.datasource", name = "enabled", havingValue = "true")
public class BusinessDataSourceConfig {

    @Bean(name = "businessDataSource", defaultCandidate = false, destroyMethod = "close")
    public HikariDataSource businessDataSource(BusinessDataSourceProperties properties) {
        BusinessDataSourceProperties.Hikari hikari = properties.getHikari();
        if (properties.getUrl() == null || !properties.getUrl().startsWith("jdbc:postgresql:")
                || hikari == null || hikari.getMaxPoolSize() < 1
                || hikari.getMinIdle() < 0 || hikari.getMinIdle() > hikari.getMaxPoolSize()
                || hikari.getConnectionTimeout() < 250
                || properties.getQueryTimeoutSeconds() < 1) {
            throw new IllegalArgumentException("业务数据源配置无效");
        }
        // 无参构造不立即启动连接池，业务库暂不可达不影响原 RAG 数据源启动。
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("business-readonly-pool");
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setMaximumPoolSize(hikari.getMaxPoolSize());
        dataSource.setMinimumIdle(hikari.getMinIdle());
        dataSource.setConnectionTestQuery(hikari.getConnectionTestQuery());
        dataSource.setConnectionTimeout(hikari.getConnectionTimeout());
        dataSource.setReadOnly(true);

        // always 在自动提交的 SELECT 中也使用只读模式，而不只作用于显式事务。
        dataSource.addDataSourceProperty("readOnlyMode", "always");
        dataSource.addDataSourceProperty("connectTimeout", "5");
        // 查询超时后留出取消余量，避免网络异常时无限等待服务端响应。
        dataSource.addDataSourceProperty("socketTimeout",
                String.valueOf((long) properties.getQueryTimeoutSeconds() + 5));
        // 每条物理连接建立时就设置只读和服务端超时；不改变数据库或账号的全局设置。
        dataSource.addDataSourceProperty("options", "-c default_transaction_read_only=on"
                + " -c statement_timeout=" + TimeUnit.SECONDS.toMillis(properties.getQueryTimeoutSeconds())
                + " -c lock_timeout=3000");
        return dataSource;
    }

    @Bean(name = "businessJdbcTemplate", defaultCandidate = false)
    public JdbcTemplate businessJdbcTemplate(
            @Qualifier("businessDataSource") DataSource dataSource,
            BusinessDataSourceProperties properties) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(properties.getQueryTimeoutSeconds());
        return jdbcTemplate;
    }

    @Bean(name = "businessTransactionManager", defaultCandidate = false)
    public JdbcTransactionManager businessTransactionManager(
            @Qualifier("businessDataSource") DataSource dataSource) {
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        transactionManager.setEnforceReadOnly(true);
        transactionManager.setValidateExistingTransaction(true);
        return transactionManager;
    }

    @Bean(name = "businessReadOnlyTransactionTemplate", defaultCandidate = false)
    public TransactionTemplate businessReadOnlyTransactionTemplate(
            @Qualifier("businessTransactionManager") JdbcTransactionManager transactionManager,
            BusinessDataSourceProperties properties) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setTimeout(properties.getQueryTimeoutSeconds());
        return template;
    }
}
