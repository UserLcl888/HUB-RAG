# RAG 知识库问答系统 — 实现计划(Python)

> 参考实现:[wmtwli/ai-rag](https://github.com/wmtwli/ai-rag)(Python/FastAPI + Vue)。
> 本文是一份**面向实施的建设计划**:技术选型、分阶段步骤、验收标准,以及最后**数据库设计总结**(每张表一个表格,含字段/类型/作用)。
> 文中不展开大量代码,关键处只给思路与参数。

---

## 一、目标

做一个「上传文档 → 自动解析/分块/向量化 → 对话问答(带引用溯源)」的 RAG 知识库系统。

核心能力:
1. 文档上传与管理(去重、异步索引、状态追踪)
2. 混合检索(向量 + 全文 + 重排)
3. 流式问答(可折叠思考 + 引用标注)
4. 多知识库 + 权限隔离 + 多轮会话

---

## 二、技术选型(Python 为主)

| 层 | 选型 | 说明 |
|---|---|---|
| 后端框架 | FastAPI + Uvicorn | SSE 流式、异步、声明式校验 |
| RAG 编排 | LangChain(或其拆分包) | 复用 `langchain-qwq` / `langchain-openai` / `langchain-text-splitters` |
| 数据库 | PostgreSQL 14+ | 一个库同时承担:关系数据 + 向量(pgvector)+ 全文(可选 pg_search) |
| 向量扩展 | pgvector | HNSW + cosine |
| 全文检索 | ParadeDB `pg_search`(BM25 + jieba) | 简化版可用 PostgreSQL 自带 `tsvector` |
| 对象存储 | MinIO | 存原始文件 + 解析出的图片资产 |
| 文档解析 | MinerU 云端 SDK | PDF/DOCX 图文混合;md/txt 本地解析 |
| 向量化 | DashScope `text-embedding-v4`(1536 维) | OpenAI 兼容协议 |
| 重排 | DashScope `qwen3-rerank` | 对召回子块精排 |
| 大模型 | `qwen3.7-max`(生成)/ `qwen3.7-flash`(路由/改写) | 一个 Key 驱动三处 |
| 依赖管理 | uv | 清华镜像源加速 |
| 前端 | Vue 3 + Vite + Element Plus | 可选,先做后端 API 也能验证 |

**简化替代**(想先跑通):PDF 用 `pymupdf`、DOCX 用 `python-docx`;全文用 `tsvector`;先不做意图路由/重排。

---

## 三、总体架构

```
前端(Vue3 SPA)
   │ HTTP + SSE
后端 API 层(FastAPI 路由 + DTO 校验 + JWT 鉴权 + trace_id)
   │
RAG 能力层
   ├─ 对话链路(Agentic RAG): 意图路由 → 查询改写 → 混合检索 → 重排 → 流式生成 → 引用后处理
   └─ 索引流水线: 上传 → MinIO → 解析 → 图片资产 → 父子分块 → 向量化 → 入库
   │
数据层: PostgreSQL(pgvector + 可选 pg_search) / MinIO
```

**最核心的设计思想**:检索是「按需」的——意图路由决定要不要查库;检索命中「子块」后还原到「父块」全文喂给大模型。

---

## 四、实施计划(分 6 个阶段)

### Phase 0 — 环境与依赖

- [ ] 建 PostgreSQL,启用 `vector` 扩展(可选再装 `pg_search`)。
- [ ] 起 MinIO,建存储桶。
- [ ] 准备 DashScope API Key(向量/重排/生成共用)。
- [ ] `uv` 初始化项目,锁定依赖。
- [ ] 配置骨架:`pydantic-settings` 集中管理配置 + `.env`(密钥不进库)。

**验收**:`/api/health` 返回 ok;能连通 DB 与 MinIO。

### Phase 1 — 数据库 + 工程骨架

- [ ] 按第八节建表(先建核心表:部门/用户/知识库/文件/父块/子块/会话/消息)。
- [ ] 搭分层骨架:`api`(路由/校验)→ `services`(业务)→ `indexer`(索引/检索)→ `core`(DB/异常/日志/鉴权)→ `models`(ORM)。
- [ ] 落地统一响应信封 `{code, message, trace_id, data}` + 全局异常处理器 + `trace_id` 中间件。
- [ ] JWT 登录 / 鉴权依赖 / 可见知识库解析(`get_visible_kb_ids`)。

**验收**:登录拿到 token;所有接口走统一信封。

### Phase 2 — 文档上传 + 索引流水线

- [ ] `POST /files/upload`:扩展名白名单(pdf/md/docx/txt)+ 大小上限(≤50MB)+ `content_hash`(SHA-256)全局去重。
- [ ] 原始文件存 MinIO,写文件元数据表(`status=PENDING`)。
- [ ] `POST /index`:标记 `PROCESSING` → 后台异步任务逐文件处理,状态推进 `DONE/FAILED`。
- [ ] 解析:PDF/DOCX 走 MinerU(或简化解析器)
- [ ] 图片资产:>10KB 上传 MinIO,按内容哈希去重,URL 回填父块。
- [ ] **父子分块**:父块按标题锚点聚合(软阈值 ~2000 字);子块按 800 字切(短碎片合并、表格结构保护)。
- [ ] 向量化:批量 embed(批大小 10)→ 幂等写入子块 + 父块。

**验收**:上传一份 PDF,几分钟内状态变 `DONE`,`/files/{id}/chunks` 能看到分块结果。

### Phase 3 — 检索 + 问答链路(最小闭环)

- [ ] 检索:`embed_query` → 向量 Top-K(`embedding <=> :vec`)。
- [ ] 拼 Prompt:系统指令(要求基于片段作答 + 标 `[n]`)+ 检索片段 + 用户问题。
- [ ] `POST /chat/stream` SSE:流式输出正文增量;结束事件带答案 + 引用。
- [ ] 引用后处理:`[n]` 越界校验 + 去重 + 重排。
- [ ] 会话/消息持久化,历史截断(最近 N 条)。

**验收**:能提问、能看到引用、能多轮对话。这是 MVP 的终点。

### Phase 4 — 检索与生成增强(质量提升)

按收益排序,建议依次加:

- [ ] **混合检索**:BM25 与向量双路并发召回(各 20)→ RRF 融合(k=60,等权)。
- [ ] **重排**:`qwen3-rerank` 对子块精排;失败降级为 RRF 序。
- [ ] **拒答阈值**:top1 相关性 < 0.3 → 固定文案拒答,不拼凑。
- [ ] **父块去重**:同父块多个子块命中只取最高分,content 换成父块全文,返回 4 条。
- [ ] **意图路由**:小模型分类 kb/general/chitchat/uncertain,按需检索,闲聊直答。
- [ ] **查询改写**:检索前做指代消解 + 名词化。
- [ ] **思考流**:`reasoning` 与正文 `delta` 双通道。

**验收**:检索质量与回答可追溯性显著提升;闲聊不再打库。

### Phase 5 — 权限、评估、前端、部署

- [ ] 权限:部门 → 知识库可见性;`visible_kb_ids` 下推为检索 SQL 的强制 WHERE。
- [ ] 评估:样本库 + Ragas 打分(faithfulness / context_recall / context_precision)+ 报告落库。
- [ ] 前端:对话页(思考折叠 + 引用溯源)、知识库/文件管理、检索调试、评估、后台看板。
- [ ] 部署:Dockerfile + docker-compose(PG/MinIO/api/web)+ nginx 反代。

**验收**:完整可用、可单机部署。

---

## 五、两条核心流程(步骤化)

### 5.1 文档上传流程

1. 前端提交 `file + kb_id`。
2. 校验扩展名、大小、`kb_id` 权限。
3. 算 SHA-256 → 查 `content_hash` 全局唯一:重复则幂等返回,不重复入库。
4. 原始文件存 MinIO(`{uuid}_{文件名}` 作对象键)。
5. 写文件表(`status=PENDING`)。
6. 用户触发「建立索引」→ 状态转 `PROCESSING`,后台异步任务。
7. 从 MinIO 取字节 → 按类型解析 → 图片资产入 MinIO。
8. 父子分块 → 向量化(批 10)→ 幂等写子块/父块。
9. 状态转 `DONE`(单份失败标 `FAILED`,继续下一份)。

### 5.2 问答流程(Agentic RAG)

1. 鉴权 + `kb_id` 权限校验。
2. 存用户消息(截断最近 10 条历史)。
3. **意图路由**:小模型结构化分类;不确定 → 改写后重路由一次 → 仍不确定则反问澄清(不检索)。
4. **查询改写**:需要检索时才改写(消指代 + 关键词化),失败降级原 query。
5. **检索**:向量 ‖ BM25 → RRF → 重排 → 低相关拒答 → 父块去重 → 返回 4 条。
6. 检索为空 → 固定文案「知识库中未找到相关内容」,不调 LLM。
7. **生成**:拼 `[System + 历史 + 检索上下文 + 问题]` → 流式输出(思考增量 + 正文增量)。
8. **引用后处理**:`[n]` 校验/去重/重排 → `done` 事件带 `final_content + references + citations`。
9. 收尾:assistant 答案 + 引用落库。

---

## 六、关键设计要点(可直接复用的套路)

1. **父子分块**:子块小粒度检索、父块大粒度阅读;命中子块后还原到父块全文。
2. **权限锚点**:`kb_id` 冗余进 chunk 表,`visible_kb_ids` 强制写进检索 SQL 的 WHERE。
3. **按需检索**:意图路由决定是否检索,闲聊/常识不查库。
4. **拒答不拼凑**:空结果固定文案 + 重排阈值,杜绝大模型瞎编。
5. **降级不中断**:重排、改写是增强切面,失败只降级;仅检索/生成主链路失败才报错。
6. **幂等**:文件 `content_hash` 唯一索引(查重)+ 入库 `ON CONFLICT DO NOTHING`。
7. **引用闭环**:Prompt 约束编号 → 后处理校验 → `citations` 映射 → 前端徽章。
8. **SSE 双通道**:思考与正文分开,前端可折叠。
9. **统一契约**:信封 + 错误码分段 + `trace_id` 全链路。

---

## 七、已知坑(复刻时应避开)

- **重索引双份数据**:ID 用 uuid4 + 幂等插入,失败重试会残留旧 chunk。正确做法:重索引前按 `file_id` 清旧索引,或 ID 用内容哈希。
- **软删文件幽灵召回**:删文件不动 chunk 会占召回名额。应在召回回查时 `JOIN` 文件表 `is_deleted=0` 兜底,或删文件时级联删 chunk。
- **阻塞调用卡死事件循环**:MinerU 解析、同步 LLM 调用、embedding 都要丢线程池(`asyncio.to_thread`)。
- **Navicat 导出的 DDL 会丢 HNSW/BM25 索引类型**:需按 pgvector / ParadeDB 语法手工还原索引。

---

## 八、数据库设计总结(PostgreSQL 单库)

> 一个数据库 `ai_rag`,下含扩展 + 13 张表。核心表 8 张,评估/日志表 5 张可后置。
> 通用约定:主键 `id bigint`(自增);软删统一 `is_deleted`(0/1)+ 时间戳;`create_time/update_time` 带时区。

### 8.0 扩展(先执行)

| 项 | 类型 | 作用 |
|---|---|---|
| `CREATE EXTENSION vector` | 扩展 | pgvector 向量类型与 HNSW 索引 |
| `CREATE EXTENSION pg_search`(ParadeDB) | 扩展 | BM25 全文检索;需 ParadeDB 镜像,简化版可改用 `tsvector` |

### 8.1 tb_department — 部门表

权限隔离的最小单位。

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| name | varchar(64) | 部门名称(唯一) |
| parent_id | bigint | 父部门 ID;仅组织展示,**权限不按树继承** |
| leader_user_id | bigint | 负责人 ID(冗余,仅组织信息) |
| create_time | timestamptz | 创建时间 |

### 8.2 tb_user — 用户表

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| username | varchar(64) | 登录名(未删内唯一) |
| password_hash | varchar(255) | 密码哈希(pbkdf2),不存明文 |
| department_id | bigint | 所属部门;NULL = 看不到任何知识库 |
| is_superuser | smallint | 是否超管(1 = 看全部知识库) |
| is_deleted | smallint | 软删标志 |
| create_time / update_time | timestamptz | 时间戳 |

### 8.3 tb_knowledge_base — 知识库表

chunk 的归属实体、权限锚点。

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| name | varchar(128) | 知识库名(未删内唯一) |
| description | text | 描述 |
| department_id | bigint | 所属部门;NULL = 仅超管可见 |
| is_deleted | smallint | 软删标志 |
| create_time / update_time | timestamptz | 时间戳 |

### 8.4 tb_file_info — 文件表

上传的原始文件元数据,索引的状态机载体。

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| kb_id | bigint | 所属知识库 ID |
| file_name | varchar(255) | 文件名(含扩展名) |
| file_type | varchar(16) | 类型 pdf/md/docx/txt |
| file_size | bigint | 文件大小(字节) |
| content_hash | varchar(64) | 内容 SHA-256,全局唯一标识(查重用) |
| mime_type | varchar(128) | MIME 类型 |
| storage_key | varchar(512) | MinIO 对象键 |
| storage_bucket | varchar(64) | MinIO 桶名 |
| file_url | varchar(512) | 文件访问 URL |
| status | varchar(16) | 状态 PENDING/PROCESSING/DONE/FAILED |
| is_deleted | smallint | 软删标志 |
| upload_time | timestamptz | 上传时间 |
| create_time / update_time / delete_time | timestamptz | 时间戳 |

> 关键索引:`uq_tb_file_info_hash_alive`(content_hash 唯一,`WHERE is_deleted=0`)。

### 8.5 tb_chunk_parent — 父块表

供大模型阅读的完整上下文。

| 字段 | 类型 | 作用 |
|---|---|---|
| parent_chunk_id | varchar(64) PK | 父块 ID(uuid4) |
| file_id | bigint | 所属文件 ID |
| kb_id | bigint | 所属知识库 ID(权限锚点) |
| content | text | 完整上下文文本(返回给 LLM 的正是它) |
| page_number | int | 页码(仅 PDF) |
| image_urls | text[] | 父块内图片资产 URL 列表 |
| is_deleted | smallint | 软删标志 |
| create_time | timestamptz | 创建时间 |

### 8.6 tb_chunk — 子块表

检索命中单元,向量 + BM25 双索引。

| 字段 | 类型 | 作用 |
|---|---|---|
| chunk_id | varchar(64) PK | 分块 ID(uuid4) |
| file_id | bigint | 所属文件 ID |
| kb_id | bigint | 所属知识库 ID(权限锚点) |
| content | text | 分块文本(BM25 索引目标) |
| parent_chunk_id | varchar(64) | 关联父块;扁平子块为 NULL |
| page_number | int | 页码(仅 PDF) |
| chunk_index | int | 父文档内序号(从 0 起) |
| total_chunks | int | 父文档分块总数 |
| embedding | vector(1536) | 向量(HNSW 索引) |
| is_deleted | smallint | 软删标志 |
| create_time | timestamptz | 创建时间 |

> 关键索引:`idx_tb_chunk_hnsw`(vector_cosine_ops)、`idx_tb_chunk_bm25`(pg_search + jieba)、`idx_tb_chunk_kb_file`(kb_id+file_id,部分索引)、`idx_tb_chunk_parent`。

### 8.7 tb_chat_session — 会话表

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| session_id | varchar(64) | 业务会话 ID(uuid4,SSE 回传前端) |
| user_id | bigint | 归属用户 |
| title | varchar(128) | 会话标题(首条消息前 30 字) |
| is_deleted | smallint | 软删标志 |
| create_time | timestamptz | 创建时间 |
| update_time | timestamptz | 最后活跃时间(列表排序) |
| delete_time | timestamptz | 软删时间 |

### 8.8 tb_chat_message — 消息表

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| session_id | varchar(64) | 关联会话(session_id) |
| role | varchar(16) | 角色 user/assistant/system |
| content | text | 消息文本 |
| references_json | text | assistant 引用片段 JSON(仅 AI 消息有) |
| seq | int | 同会话内递增序号(排序用) |
| create_time | timestamptz | 创建时间 |

### 8.9 tb_eval_sample — 评估样本表

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| sample_id | varchar(64) | 样本业务 ID(唯一) |
| question | text | 喂给链路生成答案的问题 |
| reference_answer | text | 标准答案(Ragas 判分用) |
| created_by | bigint | 维护人 user_id |
| create_time / update_time | timestamptz | 时间戳 |

### 8.10 tb_eval_run — 评估运行表

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| user_id | bigint | 发起用户 |
| kb_id | bigint | 评估目标知识库 |
| name | varchar(128) | 运行名称 |
| status | varchar(16) | PENDING/RUNNING/DONE/FAILED |
| sample_count | int | 样本总数 |
| done_count | int | 已完成数(前端轮询进度) |
| summary_json | text | 各指标聚合结果 JSON |
| error_msg | text | 失败信息 |
| create_time / update_time | timestamptz | 时间戳 |

### 8.11 tb_eval_item — 评估单题结果表

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| run_id | bigint | 关联 tb_eval_run.id |
| sample_id | varchar(64) | 样本 ID |
| question | text | 问题(冗余) |
| reference_answer | text | 标准答案(冗余) |
| answer | text | 生成的答案 |
| retrieved_json | text | 检索片段 JSON(LLM 模式为 NULL) |
| metrics_json | text | Ragas 各指标分数 JSON |
| create_time | timestamptz | 创建时间 |

### 8.12 tb_api_log — HTTP 请求日志表

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| method | varchar(8) | HTTP 方法 |
| path | varchar(256) | 请求路径 |
| status_code | int | 状态码 |
| duration_ms | int | 耗时(毫秒) |
| user_id | bigint | 用户 ID(未登录 NULL) |
| trace_id | varchar(64) | 追踪 ID |
| create_time | timestamptz | 创建时间 |

### 8.13 tb_llm_call_log — AI 调用日志表

| 字段 | 类型 | 作用 |
|---|---|---|
| id | bigint PK | 主键 |
| scene | varchar(32) | 场景 chat/rewrite/embedding_index/embedding_query/rerank/router/chitchat |
| user_id | bigint | 触发用户 |
| model | varchar(128) | 模型名 |
| prompt_tokens | int | 输入 token |
| completion_tokens | int | 输出 token |
| total_tokens | int | 总 token |
| duration_ms | int | 耗时(毫秒) |
| status | smallint | 0 成功 / 1 失败 |
| trace_id | varchar(64) | 追踪 ID |
| create_time | timestamptz | 创建时间 |

---

*本文档由 Claude Code 通读 `wmtwli/ai-rag` 源码整理,供实现同类 RAG 系统参考。*
