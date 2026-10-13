# 农田 AI 助手 · 块级检索与引用预览诊断（含实测证据）

| 项目 | 内容 |
|---|---|
| 目标工程 | `rag`（后端，本地 8080 实测）+ `front`（Vue3 前端） |
| 触发样例 | 《【郑处】附件：高标准农田建设项目初步设计技术规程.pdf》第 8 页 6.5 建设规划布局 |
| 实测坐标 | kbId=1，docId=6（该文档父块 124 个、子块 147 个） |
| 文档性质 | 诊断 + 方案；**第 9 节为本次已落地改动与实测验证** |

---

## 0. 结论速览

| # | 结论 | 证据 |
|---|---|---|
| 1 | **回答与期望差距大，主因不在"检索不到"，而在"分块把一节切成了碎片"。** 6.5 建设规划布局在库里是 **4 个父块**（307/308/309/310），其中 307 的内容只有 10 个字：`6.5 建设规划布局` | 实测父块清单 §1.2 |
| 2 | **问题的字面与"只有标题的那一块"几乎完全相同**，向量与精排都必然把它排第一（score 0.63~0.79，高于 min-score 0.5），模型拿到 10 个字只能复读标题 | 实测问答 §1.3 |
| 3 | **中文全文检索这一路实际不产生有效召回**：`TsQueryBuilder` 没有中文分词、`content_tsv` 用 `to_tsvector('simple', …)`，中文问题会变成一个超长 lexeme，几乎不可能命中 | §3.2 |
| 4 | **预览要"展示所属一整块"完全可行，而且数据已经具备**：预览接口返回的 `id` 就是父块 id，`Source` 里已经带 `parentId / matchedContent / parentContent`。只需前端 3 处小改，**后端 0 改动** | §4 |
| 5 | 但要让"一整块"真的等于 `6.5.1 ~ 6.5.5`，**必须先修分块规则并重建索引**，否则"整块"只是碎片之一 | §5 |

---

## 1. 实测证据（本地 8080 真实返回）

### 1.1 复现命令

```powershell
# 该文档的父块预览（= 库里真正的"块"）
Invoke-WebRequest 'http://127.0.0.1:8080/api/v1/kb/1/documents/6/preview' -OutFile preview.json

# 同步问答（复现截图提问；docId=6 表示"只检索这份文件"）
$body = @{ question='建设规划布局有什么？'; kbIds=@(1); docId=6 } | ConvertTo-Json
Invoke-WebRequest 'http://127.0.0.1:8080/api/v1/chat' -Method Post -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

### 1.2 6.5 这一节在库里被切成了 4 块

| 父块 id | 页码 | 字数 | sectionTitle | 内容要点 |
|---|---|---|---|---|
| 307 | 8 | **10** | `6 / 6.5 建设规划布局` | 只有标题行 `6.5 建设规划布局` |
| 308 | 8 | 50 | `…/ 6.5.1 项目区建设规划布局应包括指导思想及基本原则、目标任务、建设规模和内容、总体` | 只有 6.5.1 |
| 309 | 8 | 144 | `…/ 6.5.2 指导思想及基本原则应说明项目区规划布局的指导思想,根据相关法律、法规、政策、` | 6.5.2 + 6.5.3 + 6.5.4 |
| 310 | 8 | 199 | `…/ 6.5.5 总体布局应根据项目建设目标及存在问题,对农田基础设施建设工程(田块整治工程、` | 只有 6.5.5 |

同一页前后还有同样的碎片：`296`（`6 初步设计报告规定`，10 字）、`298`（`6.2 综合说明`，8 字）、`299`（`6.2.1 …`，400 字）、`303`（内容只有 1 个字符 `6`）、`304`、`306`、`311`~`315`……**这不是个例，是全文档性的**。

### 1.3 三个问题，三种结果（同一份文档、同一天）

| 提问 | 实际返回 | 引用的父块 |
|---|---|---|
| `建设规划布局有什么？` | **`建设规划布局有6 / 6.5 建设规划布局（来源：[参考1]）`** | 307（10 字标题块，score 0.79） |
| `建设规划布局说说这个内容` | `建设规划布局的内容包括：指导思想及基本原则、目标任务、建设规模和内容、总体布局等内容。（来源：[参考2]）`（**与你截图完全一致**） | 308（只有 6.5.1，score 0.58） |
| `6.5 建设规划布局这一节包括哪些条款？请逐条列出原文。` | 只列出了 `6.5`、`6.5.1`、`6.5.5`——**6.5.2 / 6.5.3 / 6.5.4 直接丢失** | 307 + 308 + 310 |
| `总体布局应如何进行规划和统筹安排？` | 正确、完整复述 6.5.5 全文 | 310（score 0.68） |

> 第 3 行是本次诊断最关键的一条：**用户明确要求"逐条列出这一节"，系统仍然凑不齐这一节**，因为"这一节"在库里根本不存在。

---

## 2. 检索链路（现状，逐层）

```
用户提问（docId 已绑定）
 └─ ChatController.streamChat → StreamingRagService.streamQuery      rag/.../StreamingRagService.java:36-116
     Step1  EnhancedRetrieverService.retrieveWithHyde(question, kbIds, docId, 40)
            ├─ 路线1 HybridRetrieverService.retrieve：向量 top-20 + 全文 top-20 → RRF(k=60)
            │    向量：kb_doc_chunk ORDER BY embedding <=> query（HNSW，余弦）
            │    全文：content_tsv @@ to_tsquery('simple', :tsQuery)
            └─ 路线2 HyDE：先让 qwen 编一段假设答案 → 向量化 → 再 top-20 → 两路 RRF 融合 → 40 条
     Step2  RerankerService.rerank(question, 40 条, topN=15)   # dashscope gte-rerank-v2，timeout-ms=800
     Step3  ConfidenceFilter.filter(min-score=0.5)             # 只在精排成功时生效
     Step4  ParentContextService.restore()：子块 → 父块，同父块只留最高分子块，最多 5 个父块
            ParentContextService.selectForContext()：按 token 预算 9000 逐个装入
     Step5  buildContext()：[参考1] 章节标题 + 父块全文 …
     Step6  RagPromptTemplate + qwen-plus（temperature 0.1, max-tokens 2048）流式生成
     Step7  SourceBuilder.buildSources()：解析正文里的 [参考N] → 组装 sources
```

关键点：**向量化的对象是"子块"，塞给模型的是"父块全文"，两者靠 `parent_id` 关联**（`IndexService.doIndex`，`ParentChunkService.children` 按页切子块）。所以**父块的边界 = 用户看到的"一个块" = 检索的基本语义单元**。父块切错，后面全错。

---

## 3. 为什么"我问的"和"它答的"差这么远

### 3.1 根因 A（P0）：条款编号被误判成标题 → 一节被切碎

`rag/src/main/java/edu/lcl/rag/service/ParentChunkService.java:226-229`

```java
private boolean isHeadingText(String text) {
    return !text.isEmpty() && text.length() <= 80 && !END_PUNCTUATION.matcher(text).find()
            && (Character.isLetter(text.codePointAt(0)) || text.startsWith("("));
}
```

`END_PUNCTUATION`（`:32`）是 `[，,。；;：:？！?!]$`，只检查**行尾**。而 PDF 抽取出来的长条款**一定会换行**，换行处自然没有句末标点，于是：

| 原文首行（PDF 实际断行） | 行尾字符 | 判定 | 后果 |
|---|---|---|---|
| `6.5 建设规划布局` | `局` | ✅ 标题（正确） | — |
| `6.5.1 项目区建设规划布局应包括指导思想及基本原则、目标任务、建设规模和内容、总体` | `体` | ❌ **误判为标题** | 6.5.1 单独成块 |
| `6.5.2 指导思想及基本原则应说明项目区规划布局的指导思想,根据相关法律、法规、政策、` | `、` | ❌ **误判为标题**（`、` 不在集合里） | 6.5.2 单独成块 |
| `6.5.3 …建设任务。` / `6.5.4 …建设内容。` | `。` | ✅ 正文（正确） | 留在上一块 |
| `6.5.5 总体布局应根据项目建设目标及存在问题,对农田基础设施建设工程(田块整治工程、` | `、` | ❌ **误判为标题** | 6.5.5 单独成块 |

副产物：误判出来的"标题"会被写进 `sectionTitle`，而 `IndexService` 又把它**拼在正文前参与向量化**（`:139-142`），于是向量语义被这句被截断的半句污染，同时预览里显示出一长串"标题"。

> 判定链条的完整效果：**父块从"章节"退化成了"条款 + 标题碎片"**，`6.5` 这一节在库里不存在，任何"这一节讲了什么/有哪些条款"的问题都不可能答全。

### 3.2 根因 B（P1）：中文全文检索这一路实际是空转

- 索引侧：`content_tsv` 由触发器 `to_tsvector('simple', NEW.content)` 生成（`rag/src/main/resources/schema.sql:109-120`）。PostgreSQL 默认解析器**不做中文分词**，只按空格与标点切 token。
- 查询侧：`TsQueryBuilder.build()`（`rag/src/main/java/edu/lcl/rag/service/TsQueryBuilder.java:33-55`）只按 `[\s\p{P}]+` 切分，**中文整句不切**：`建设规划布局有什么？` → 唯一 token = `建设规划布局有什么`，拼成 `to_tsquery('simple','建设规划布局有什么')`。
- 要命中，就必须在 `content_tsv` 里存在**完全相同**的 lexeme——而文档侧 lexeme 是 `6.5.1`、`项目区建设规划布局应包括指导思想及基本原则`、`目标任务`… 这类被标点切开的整段。

**结论**：日常中文提问的全文召回 ≈ 0，`rag.retrieval.fulltext-top-k: 20` 这一路基本白配，"混合检索"事实上是**纯向量检索**。要修需引入 `pg_jieba` / `zhparser`（库级改动）或在应用层用 jieba 生成 `&` 连接的词项。

### 3.3 根因 C（P2）：生成层没有"块级/节级完整性"的约束

`rag/src/main/java/edu/lcl/rag/service/RagPromptTemplate.java:45-54` 的 7 条规则里：

- 第 6、7 条要求"步骤、列表、编号条目必须完整列出"——但 6.5.1~6.5.5 在解析后**并不是列表项**，模型不认为受这条约束；
- 没有任何一条要求"回答要覆盖该章节下的全部条款""先回答再补充同级条款"。

于是当上下文里出现 6.5.1 那句自问自答式的定义句（"项目区建设规划布局应包括……"），模型把它当成完整答案就收工了——这正是你截图里那条回答的来源。temperature=0.1 又让同一问题的回答高度稳定（实测两次措辞几乎逐字相同）。

### 3.4 次级项（都不是主因，但会放大症状）

| 项 | 现状 | 影响 |
|---|---|---|
| `reranker.timeout-ms: 800` | 40 条候选一次远程调用 | 超时即降级为 RRF 排序且**跳过置信度过滤**，行为随网络波动 |
| `rag.retrieval.min-score: 0.5` | 只对精排分生效 | 10 字的标题碎片实测 0.63~0.79，稳稳通过 |
| `SourceBuilder.excerpt` | 子块前 200 字（`:77`） | 标题块场景下"摘要"就是标题本身 |
| `max-tokens: 2048` | 输出上限 | 想完整列 6.5.1~6.5.5 时余量偏紧 |

---

## 4. 预览能否"展示所属一整块"？——能，且数据已具备

### 4.1 现状

```ts
// front/src/App.vue:532-543
async function openSourcePreview(source: Source) {
  const document = ragDocuments.value.find(item => item.id === source.docId)
  await openPreview(toPolicyFile(document), source.pageNum)   // ← 只传了"页"，没传"块"
}
async function scrollPreviewToPage(pageNum: number) {
  document.querySelector(`.preview-chunk[data-page="${pageNum}"]`)?.scrollIntoView({ block: 'center' })
}
```

- 预览接口 `/api/v1/kb/{kbId}/documents/{docId}/preview` 返回的 `chunks[].id` **就是父块 id**、`content` 就是父块全文（`KnowledgeBaseController.java:99-123`）；
- 前端把它整篇平铺（`App.vue:735`），再按 `data-page` 滚到**该页第一个块**。第 8 页第一个块是 `304`（6.3.5），比真正的命中块 307 早 3 块——所以现在连"滚到命中处"都没做到。
- 而"块"本身又是碎的（§3.1），所以即便滚准了，看到的也只是 6.5.1 或 6.5.5 单条。

### 4.2 好消息：要的数据后端早就给了

`rag/src/main/java/edu/lcl/rag/dto/RagResponse.java:25-36` → 实测 `done`/同步返回里的 `Source`：

```json
{"chunkId":414,"parentId":310,"docId":6,"pageNum":8,
 "sectionTitle":"6 / 6.5 建设规划布局 / 6.5.5 …",
 "excerpt":"6.5.5 总体布局应…","matchedContent":"6.5.5 总体布局应…（命中子块）",
 "parentContent":"6.5.5 总体布局应…（父块全文）","score":0.68}
```

只是前端类型把它丢了：`front/src/api/rag.ts:34-41` 的 `Source` 只声明了 4 个字段（运行时其实还在，只是 TS 不可见、也没人用）。

### 4.3 改动清单（前端 3 处，后端 0 处）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `front/src/api/rag.ts:34-41` | `Source` 补 `parentId? / matchedContent? / parentContent? / score?` |
| 2 | `front/src/App.vue:532-543` | `openSourcePreview` 把 `source.parentId` 传下去；新增 `scrollPreviewToParent(parentId)`：`document.querySelector('.preview-chunk[data-parent-id="' + parentId + '"]')` 并高亮；`openPreview` 增加第 3 个参数 `targetParentId` |
| 3 | `front/src/App.vue:735` | 每个块加 `:data-parent-id="chunk.id"` 与 `:class="{ 'is-hit': chunk.id === previewFocusParentId }"`；顶部加一个「只看引用块」开关（`previewOnlyHit`，过滤 `chunks` 只留命中块）；命中块内对 `matchedContent` 做一次容错高亮（`parentContent.indexOf(matchedContent)`，失败则退化为整块高亮） |

`front/src/components/SourceCards.vue:40` 已经在 `emit('preview', source)` 里传了整个 `source`，无需改动。

### 4.4 改完的交互效果

点击来源卡片 → 弹窗直接**只显示这一块**（例如 6.5 整节），命中句高亮，块头显示 `第 8 页 · 6.5 建设规划布局`，并带「上一块 / 下一块 / 查看全文」——即你说的"像原 PDF 那样展示所属整块"。

---

## 5. 前置修复：让"一整块"真的是 6.5.1 ~ 6.5.5

`ParentChunkService.detectHeading / isHeadingText` 需把"条款正文"从标题里排除：

```java
// 行尾出现这些符号 → 一定不是标题（说明该行被排版截断）
private static final Pattern END_PUNCTUATION =
        Pattern.compile("[，,。；;：:、？！?!]$");
// 标题正文里不应出现任何句内标点（条款正文必然带逗号/顿号/括号）
private static final Pattern INLINE_PUNCTUATION =
        Pattern.compile("[，,。；;：:、（）()？！?!]");
// 编号标题的正文上限；"6.5 建设规划布局"=6、"6.5.1 一般规定"=4，条款正文远长于此
private static final int NUMBERED_TITLE_MAX = 20;

private boolean isHeadingText(String text) {
    return !text.isEmpty() && text.length() <= NUMBERED_TITLE_MAX
            && !INLINE_PUNCTUATION.matcher(text).find()
            && (Character.isLetter(text.codePointAt(0)) || text.startsWith("("));
}
```

效果（对本文档）：

- `6.5 建设规划布局` → 仍是标题 ✅
- `6.5.1 项目区建设规划布局应包括…`（39 字、含 `、`）、`6.5.2 …政策、`（行尾 `、`）、`6.5.5 …（田块整治工程、`（行尾 `、`）→ 全部回归正文 ✅
- 6.5 于是成为**一个父块 = 6.5 + 6.5.1 ~ 6.5.5（约 430 字）**，子块 = 第 8 页那一段，`parentContent` 恰好就是你想看到的那一整块。

回归影响：`ParentChunkServiceTest` 现有 4 个用例仍通过（`建设项目`/`高标准农田`/`附录 A 图纸要求` 都不含标点且 < 20 字）。潜在副作用是"带顿号的真标题"（如 `5.3 灌溉与排水工程、田间道路工程`）会被并入上一节——需要用真实文档回归确认，必要时把 `NUMBERED_TITLE_MAX` 放宽到 30 并只禁 `。，；：`。

重建索引（文件字节没变，必须显式触发）：

```
POST /api/v1/kb/1/documents/6/reindex-force
POST /api/v1/kb/1/documents/1/reindex-force
```
（`DocumentUpdateController.java:33-40`；走 `DocumentTxService.forceReindex` 生成新版本并重新向量化）

---

## 6. 建议落地顺序

| 步骤 | 内容 | 影响面 | 可独立验证 |
|---|---|---|---|
| 1 | 修 `ParentChunkService.isHeadingText` + 补 1 个针对"跨行条款"的单测 | 后端 1 文件 | ✅ 单测 + preview 里 6.5 变一块 |
| 2 | `reindex-force` 两份文档 | 数据（新版本） | ✅ 父块数从 124 降到约 30 量级 |
| 3 | 前端预览按 `parentId` 定位 + 高亮 + 「只看引用块」 | 前端 2 文件 | ✅ 点来源卡片即见 6.5 整块 |
| 4 | （可选）提示词补 1 条"章节级完整性"；`max-tokens` 2048→4096 | `RagPromptTemplate.java` | ✅ 复跑 §7 用例 |
| 5 | （可选，独立课题）中文分词接入 `zhparser`/`pg_jieba` | 数据库 + 检索 | 需建索引 |

---

## 7. 验收用例（改完后复跑，均可直接打接口）

| 提问（docId=6） | 现在 | 期望 |
|---|---|---|
| `建设规划布局有什么？` | `建设规划布局有6 / 6.5 建设规划布局` | 按 6.5.1~6.5.5 逐条回答，`parentContent` = 整节 |
| `6.5 建设规划布局这一节包括哪些条款？请逐条列出原文。` | 丢 6.5.2/6.5.3/6.5.4 | 6.5.1~6.5.5 一条不缺 |
| `总体布局应如何进行规划和统筹安排？` | 已正确 | 保持正确 |
| 点来源卡片「预览」 | 滚到第 8 页第一块（6.3.5） | 只展示 6.5 整块并高亮 6.5.5 |

---

## 8. 一句话总结

**问题不在"模型不听话"，在于"6.5 节"在索引里被切成了 4 块，其中一块只有 10 个字的标题，而它恰好和你问的字面一模一样——所以系统把标题当成答案还给了你。** 修分块（1 个方法）能让"一节"重新存在；前端补 3 处就能让预览按块展示、命中处高亮。

---

## 9. 落地记录（本次已实施）

### 9.1 改了什么

| 文件 | 改动 |
|---|---|
| `rag/src/main/java/edu/lcl/rag/service/ParentChunkService.java` | ① `isHeadingText` 增加"长度 ≤ 20 + 禁止句末标点"两道闸门；② `END_PUNCTUATION` 补 `、` 与左括号；③ `isPageFurniture` 增加**页脚页码**识别（整页最后一行的裸数字） |
| `rag/src/test/java/edu/lcl/rag/service/ParentChunkServiceTest.java` | 新增 4 个回归用例：跨行条款归入同一节、含顿号短标题仍是标题、真正的短条款标题仍独立成节、页脚页码不造块 |
| `front/src/api/rag.ts` | `Source` 补 `parentId / matchedContent / parentContent / score` |
| `front/src/App.vue` | 预览按 `parentId` 定位（`scrollPreviewToParent`）、命中块高亮（`segmentOf`）、「只看引用块」开关、`data-parent-id` |
| `front/src/style.css` | 追加 `.preview-chunk.is-hit` / `.preview-hit` / `.preview-scope-toggle` 规则 |

页脚页码这一条是跑真实 PDF 探针时才暴露的**同一家族缺陷**：整页最后一行的裸数字（`3`/`4`/`5`…）被当成"未写标题的编号"，凭空造出一个父块并把后续正文吞进去——`6.3 项目区概况`、`6.6 项目工程设计` 就是这样被切开的。

### 9.2 实测验证（对真实 PDF 跑解析+分块，不写库）

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 父块数 | **124** | **45** |
| 子块数 | 147 | 71 |
| `6.5 建设规划布局` | 4 块（10 / 50 / 144 / 199 字） | **1 块，406 字 = 6.5.1 ~ 6.5.5 全文** |
| `6.3 项目区概况` | 被页脚 `6` 切成 2 块 | 1 块（6.3.1 ~ 6.3.5） |
| `6.6 项目工程设计` | 被页脚 `7` 切成多块 | 1 块（1633 字） |

- 单测：`ParentChunkServiceTest` **8/8 通过**；全量 22 个用例中 `ChunkServiceTest`、`DocumentLoaderServiceTest` 的 2 失败 1 错误为**既有问题**（前者走未改动的 `StructureAwareChunkSplitter`，后者缺 `test-docs/policy.docx` 资源），与本次改动无关。
- 前端：`vue-tsc -b` 与 `vite build` 均通过。
- 已知残留：附录表格页（文本抽取把表格拍平成散行）仍会产生 `1 立方` 这类碎片块——属于 PDF 表格转文本的固有限制，需要表格识别能力才能解，本次不动。

### 9.3 生效步骤

1. **重启后端**（`edu.lcl.rag.RagApplication`）：新类已编译进 `rag/target/classes`，但运行中的 JVM 仍是旧类。
2. **重建索引**（文件字节没变，必须显式触发；会生成新版本并重新向量化）：
   ```
   POST /api/v1/kb/1/documents/6/reindex-force
   POST /api/v1/kb/1/documents/1/reindex-force
   ```
3. **前端**：dev server 下 HMR 直接生效；生产构建则重新 `npm run build`（或 `pnpm build`）。
4. **回归验收**：按 §7 的 4 条用例复跑；`preview` 接口里 `6.5` 应只剩一个块（`content` 含 6.5.1 ~ 6.5.5）。

> 未做的事：没有替你执行 `reindex-force`（会写库并消耗 embedding 调用），也没有重启后端。中文分词（§3.2）仍是独立课题，本次未接入。

