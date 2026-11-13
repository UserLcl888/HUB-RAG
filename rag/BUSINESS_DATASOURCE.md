# 业务数据源

原 `spring.datasource` 继续负责 RAG 数据、向量和聊天历史。新增
`business.datasource` 只连接业务 PostgreSQL，不注册 JPA 实体、不建表、不迁移。
已增加项目及关联标段、考勤组配置及成员两个只读查询工具，
接口说明见 `PROJECT_QUERY_API.md`、`ATTENDANCE_GROUP_QUERY_API.md`。

## 配置

连接参数位于 `src/main/resources/application.yml`，支持环境变量覆盖：

- `BUSINESS_DB_ENABLED`：默认 `true`，设为 `false` 不注册业务数据源。
- `BUSINESS_DB_URL`：业务 PostgreSQL JDBC 地址及 `currentSchema`。
- `BUSINESS_DB_USERNAME`、`BUSINESS_DB_PASSWORD`：业务库账号和密码。

业务库地址、账号和密码统一保存在 `src/main/resources/application.yml`，
随应用打入 JAR；部署时无需携带额外的业务库配置文件，也无需额外设置密码。
需要更换连接信息时，可用上述环境变量覆盖。
注意：JAR 中包含明文连接凭据，不要公开分发应用包或包含真实密码的配置文件。

连接池参数统一位于 `business.datasource.hikari`：

- `min-idle: 1`：连接池启动后维持的最小空闲连接数。
- `max-pool-size: 5`：最大连接数。
- `connection-test-query: SELECT 1`：连接有效性检测语句。
- `connection-timeout: 5000`：等待获取连接的超时，单位毫秒。

连接池延迟到首次使用才启动，此后才会维护最小空闲连接。
获取连接超时 5 秒，查询超时 10 秒，锁等待超时 3 秒。
修改配置后需要重新启动后端；本次未启动服务或验证远程连接。

## 后续查询接入

必须使用 `@Qualifier` 显式注入具名组件：

- `businessJdbcTemplate`：参数化业务查询。
- `businessReadOnlyTransactionTemplate`：执行只读业务事务。
- `businessTransactionManager`：如果使用注解事务，必须指定
  `@Transactional(transactionManager = "businessTransactionManager", readOnly = true)`。

原库的数据源、JdbcTemplate 和 JPA 事务仍由 Spring Boot 自动配置。
业务组件均设置 `defaultCandidate=false`，不成为默认依赖候选。
不要把业务表挂到原 JPA Repository，不要在未指定管理器的 `@Transactional` 中查询业务库。

## 只读边界

- Hikari 连接默认 `readOnly=true`。
- PostgreSQL JDBC 设置 `readOnlyMode=always`。
- 每条物理连接启动时设置 `default_transaction_read_only=on`，不修改账号或数据库全局配置。
- 业务只读事务管理器启用 `SET TRANSACTION READ ONLY`。
- 不接入数据库初始化、建表、迁移，也不提供大模型 SQL 执行入口。

这些是应用连接和事务级限制，不等于数据库授权隔离。当前提供的账号是否为超级用户尚未检查。
正式业务查询仍建议换成只授予必要表/视图 SELECT 权限的专用账号。
不要通过修改连接只读属性、显式切换读写事务或执行会话指令绕过限制。
