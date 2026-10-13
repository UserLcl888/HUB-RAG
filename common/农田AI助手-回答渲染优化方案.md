# 农田 AI 助手 · 回答内容展示优化方案（评审稿）

| 项目 | 内容 |
|---|---|
| 目标工程 | `D:\C-D\Documents\ChatGPT\HBNT\front` |
| 影响范围 | 首页「智能问答」对话流的助手回答渲染 |
| 文档性质 | **方案评审稿，未改动任何源码** |
| 触发样例 | 提问「哪些关联工程设施需要采集调查？」 |
| 后端服务 | `edu.lcl.rag.RagApplication`（`F:\ai\agent\rag`，端口 8080） |

---

## 1. 问题现象

以截图那次回答为例，展示层暴露三个问题：

| 编号 | 现象 | 截图证据 |
|---|---|---|
| **P1** | Markdown 粗体标记裸露 | `**末端掉头点**`、`**农田防护与生态环境保护工程**` 原样显示星号 |
| **P2** | 结构完全塌陷 | `1. … 2. … 3. …` 编号项、`-` 子项、层级缩进被压成一整段连续文字，无法阅读层级 |
| **P3** | 引用降级为正文尾巴 | 末尾 `参考来源：【农田】GBT30600-2022高标准农田建设通则.pdf 第 18 页` 不可点击、无摘要、无出处区分 |

> 注意：**回答内容本身是合格的结构化文本**（编号 + 子项 + 粗体 + 页码出处）。问题 100% 出在前端展示层，不需要动 RAG 检索或 Prompt。

---

## 2. 根因定位

### 2.1 P1 + P2 同源：正文走的是纯文本插值

`front/src/App.vue:531-534`：

```html
<div v-for="(message, index) in chatMessages" :key="index" :class="['conversation-message', message.role]">
  <span class="conversation-avatar">…</span>
  <div>
    <span class="conversation-author">…</span>
    <p>{{ message.text }}</p>   <!-- ← 根因所在 -->
  </div>
</div>
```

`{{ }}` 是 Vue 的**文本插值**，它做两件事：

1. **不解析 Markdown** —— `**` 就是普通字符 → 直接导致 **P1**。
2. **原样塞进 HTML 文本节点** —— 浏览器对普通文本应用「空白折叠」规则（white-space: normal），`\n`、连续空格、`\t` **全部被压缩成一个空格** → 直接导致 **P2**。

`front/src/style.css:99` 未做任何补救：

```css
.conversation-author{color:#8ea49f;font-size:10px}
.conversation-message p{margin:6px 0 0;color:#d0dce0;font-size:12px;line-height:1.8}
/*                       ↑ 无 white-space: pre-wrap，无 word-break */
```

`front/package.json` 全文无任何 Markdown 依赖（仅 `vue` / `vue-router` / `@lucide/vue`），确认 P1 无解析器可用。

### 2.2 后端其实已经吐了换行——是前端把它吃掉了

`front/src/App.vue:92-96`：

```ts
function formatSources(sources: Pick<Source, 'docName' | 'pageNum'>[]) {
  const sourceNames = sources.map(source =>
    source.pageNum ? `${source.docName} 第 ${source.pageNum} 页` : source.docName)
  return sourceNames.length ? `\n\n参考来源：${[...new Set(sourceNames)].join('、')}` : ''
}
```

注意这里的 `\n\n`：**换行是真实存在于 `message.text` 里的**。截图里 `1. … 2. …` 之间那个空格，就是 `\n` 被折叠后的残留。这一条同时锁定了 P2 的责任方在前端。

### 2.3 P3 源于「来源被降维成字符串」

`front/src/api/rag.ts:34-39` 定义的来源结构信息量是**充足**的：

```ts
export interface Source {
  docId: number;      // 可跳转
  docName: string;
  pageNum: number | null;
  excerpt: string;    // 命中原文片段 ← 卡片最该展示的东西
}
```

但 `App.vue:92-96` 的 `formatSources` 通过 `Pick<Source, 'docName' | 'pageNum'>` **主动丢弃了 `docId` 与 `excerpt`**，把结构化数据拍扁成一个字符串，再用 `+=` 拼到正文尾部（`App.vue:343`）：

```ts
chatMessages.value[answerIndex].text += formatSources(result.sources)
```

一旦降维成字符串，引用就**永远不可能**再做点击跳转、悬浮摘要、分条展示——这是 P3 的结构性原因。

**更前一层还有一次丢失**：后端 `RagResponse.Source`（`SourceBuilder.java:70-78`）实际构建了 7 个字段——`chunkId / docId / docName / pageNum / sectionTitle / excerpt / score`，而前端 `front/src/api/rag.ts:34-39` 的 `Source` **只声明了 4 个**（`docId / docName / pageNum / excerpt`）。因此 `sectionTitle`（章节标题）与 `score` 在 **TypeScript 类型层就已经不可见**，与 `formatSources` 的 `Pick` 是两个**彼此独立**的丢弃点。

> 对 §5.5 的意义：引用卡片可以直接使用 `sectionTitle` 做定位（「第 18 页 · 第五章 田间道路工程」比「第 18 页」有用得多），**无需任何后端改动**。

### 2.4 附带脆弱点：`cleanCitationMarkers` 的正则

`front/src/App.vue:86-90`：

```ts
function cleanCitationMarkers(text: string) {
  return text
    .replace(/[（(]?[ \t]*来源[ \t]*[:：][ \t]*(?:\[参考\d+\][ \t]*)+[）)]?/g, '')
    .replace(/\[参考\d+\]/g, '')
}
```

该函数在**每个 token 事件**上被调用（`App.vue:314`）：

```ts
chatMessages.value[answerIndex].text = cleanCitationMarkers(rawAnswer)
```

流式场景下 `rawAnswer` 是不断增长的**前缀**，正则要求 `[参考\d+]` 必须闭合。当 `[参考1]` 刚好被切成 `…[参考` 时匹配失败，标记会闪现在界面上，下一次 token 到达才被抹掉。这是难复现的偶发 UI 抖动。

### 2.5 已证实：换行没有在 SSE 传输中丢失

这是整个方案的关键前提，已用字节码核实，**不是推断**。

**疑点**：`token` 事件的 data 是裸字符串（`StreamingRagService.java:75-77`，未做 JSON 包装）。若某个 token 内含 `\n`，SSE 帧将变成：

```
event:token
data:xxx
yyy
```

第二行没有 `字段:` 前缀，按 SSE 规范会被当作「无冒号的字段名」**直接丢弃**——那样换行乃至换行后的文本都会丢失，仅靠 CSS 无法修复。前端 `App.vue:296/313` 直接 `rawAnswer += data`，无法察觉这种丢失。

**核实方法**：解包 Spring Boot 3.5.11 对应的 `spring-webmvc-6.2.3.jar`，对 `SseEmitter$SseEventBuilderImpl.data(Object, MediaType)` 执行 `javap -p -c`，关键字节码：

```
46: aload_1
47: instanceof    java/lang/String
50: ifeq          67
58: aload_3
59: ldc           String \n
61: ldc           String \ndata:
63: invokestatic  StringUtils.replace
```

即 Spring **对 String 类型的 data** 会把每个 `\n` 替换为 `\ndata:`，为每一行补上 SSE 字段前缀；客户端 `EventSource` 再按规范把多行 `data:` 用 `\n` 拼回原样。

> 附带说明：该转义**只对 String 生效**（`instanceof java/lang/String` 分支）。若日后把 `token` 改成推送 DTO/JSON 对象，则**不会**走这个替换，多行字段需自行处理——这是后续改动的注意事项。

**结论**：`\n` **完整到达前端**，是浏览器的 HTML 空白折叠把它变成了空格。因此 §10 的最小修复（`white-space:pre-wrap`）确实足以恢复换行，**方案 A 有效**。

### 2.6 根因小结

| 现象 | 直接原因 | 代码位置 |
|---|---|---|
| P1 星号裸露 | 无 Markdown 解析 | `App.vue:533` + `package.json` |
| P2 结构塌陷 | `\n` 被 HTML 空白折叠 | `App.vue:533` + `style.css:99` |
| P3 引用不可用 | 来源被 `Pick` 降维成字符串 | `App.vue:92-96`、`App.vue:343` |
| 偶发标记闪烁 | 流式前缀上跑正则 | `App.vue:86-90`、`App.vue:314` |

---

## 3. 影响面

- **正面**：这是问答产品的「最后一公里」。检索质量再高，用户看到一坨文字也会判定为「AI 胡说八道」。P2 对可读性的伤害最大——它让层级消失，而高标准农田这类**强层级、强条款**的领域知识恰恰依赖层级表达。
- **改动面很小**：仅涉及前端 2 个文件（新增 1 个组件 + 改 `App.vue` 约 5 处 + `style.css` 追加规则），**不触碰后端、不触碰数据库、不触碰检索链路**。
- **回归风险低**：不改变任何网络请求、SSE 协议与数据结构（方案 B 会新增一个可选字段，向后兼容）。

---

## 4. 方案对比

| | **A · 止血** | **B · 轻量渲染组件（推荐）** | **C · 引入成熟库** |
|---|---|---|---|
| 做法 | `.conversation-message p` 加 `white-space:pre-wrap;word-break:break-word` | 手写 ~80 行 Markdown → 结构化节点解析 + `<v-for>` 渲染 | 引入 `markdown-it` + `dompurify`，`v-html` 输出 |
| 改动量 | 1 行 CSS | 新增 1 组件，改 `App.vue` ~5 处，`style.css` 追加 | 加 2 依赖 + `npm install` + `App.vue` 改造 |
| P1 粗体 | ❌ 仍裸露 `**` | ✅ | ✅ |
| P2 换行 | ✅ 恢复 | ✅ | ✅ |
| P2 层级 | ⚠️ 仅靠原始空格缩进，模型若不输出缩进则仍平铺 | ✅ 真正解析成 `<ol>/<ul>` 嵌套 | ✅ |
| P3 引用卡片 | ❌ | ✅（改数据模型） | ✅（需额外处理来源分离） |
| XSS 风险 | 无 | **无**（`v-for` 渲染节点，不走 `v-html`） | 需 `dompurify` 兜底 |
| 流式表现 | 无问题 | 需设计（见 5.4） | 每个 token 全量重解析，长回答会有卡顿 |
| 新依赖 | 0 | **0** | 2 |
| 支持表格/链接/代码高亮 | ❌ | 部分（按需扩展） | ✅ 全量 |

**推荐 B**，理由：

1. **零新依赖** —— 该工程刻意保持极简（`package.json` 只有 3 个运行时依赖），引入 `markdown-it` + `dompurify` 会打破这个基调，且 `node_modules` 已存在本地缓存目录 `.npm-cache`，说明离线安装是既定约束。
2. **不走 `v-html`** —— 直接把 `message.text` 交给 `v-html` 是最常见的 XSS 入口。RAG 场景下正文包含**从上传 PDF 里检索出来的原文**，属于不可信内容，一旦有人上传恶意文档就能注入脚本。B 方案用 `v-for` 渲染节点树，从根本上免疫。
3. **回答的结构是有限且可枚举的**（编号列表 / 项目符号 / 粗体 / 小标题 / 出处），不需要通用 Markdown 的全部能力。

---

## 5. 推荐方案 B · 详细设计

### 5.1 数据模型调整（关键，先做）

把「来源」从字符串尾巴里拿出来，变成消息对象的一等字段：

```ts
// App.vue:84 现状
const chatMessages = ref<{ role: 'user' | 'assistant'; text: string }[]>([])

// 建议
type SourceRef = { docId: number; docName: string; pageNum: number | null; excerpt: string }
type ChatItem = { role: 'user' | 'assistant'; text: string; sources?: SourceRef[]; streaming?: boolean }
const chatMessages = ref<ChatItem[]>([])
```

对应改动：

| 位置 | 现状 | 建议 |
|---|---|---|
| `App.vue:92-96` `formatSources` | 返回 `'\n\n参考来源：…'` | **删除**，改为直接保留 `Source[]` |
| `App.vue:343` | `text += formatSources(result.sources)` | `chatMessages.value[answerIndex].sources = result.sources` |
| `App.vue:98-117` `restoreChatMessage` | 拼字符串 | 返回 `{ role, text, sources }`，`sources` 沿用已有的 `JSON.parse` 逻辑 |
| `App.vue:290` | 占位文本 `'正在检索知识库…'` | 增加 `streaming: true` 标记 |
| `App.vue:337-353` `done` 回调 | — | 置 `streaming = false` |

> `restoreChatMessage` 现有的 `sources` 反序列化（`App.vue:99-111`）已经能把历史消息的 `sources` 解析成 `Source[]`，**只是解析完立刻又被拼成字符串丢了**。改动是「留而不拼」，成本极低。

### 5.2 新增解析器（建议 `front/src/utils/markdown.ts`）

输出**节点树**而非 HTML 字符串：

```ts
export type MdNode =
  | { type: 'heading'; level: 1|2|3; text: string }
  | { type: 'paragraph'; text: string }
  | { type: 'list'; ordered: boolean; items: { text: string; children: MdNode[] }[] }

export type Inline = { text: string; bold?: boolean; code?: boolean }
```

解析规则（按行扫描，覆盖截图实际用到的全部语法）：

1. `^\s*\d+[.、)]\s+` → 有序列表项
2. `^\s*[-*+]\s+` → 无序列表项
3. `^\s*#{1,3}\s+` → 标题
4. 行首缩进（2/4 空格）大于上一层 → **归入上一项的 `children`**（这是解决 P2 层级的关键）
5. 空行 → 段落/列表分界
6. 其余 → 段落

行内解析只需切出 `**加粗**` 与 `` `代码` ``，产出 `Inline[]`，**不生成 HTML**。

> 实现上建议用「缩进栈」而非递归下降——输入规模和语法都有限，栈式实现约 80 行且易于测试。

### 5.3 新增渲染组件（建议 `front/src/components/MessageBody.vue`）

```vue
<script setup lang="ts">
const props = defineProps<{ text: string; streaming?: boolean }>()
const nodes = computed(() => props.streaming ? null : parseMarkdown(props.text))
</script>

<template>
  <!-- 流式中：纯文本 + pre-wrap，避免半截 ** 闪跳 -->
  <p v-if="!nodes" class="md-plain">{{ text }}</p>
  <!-- 完成后：结构化渲染，全程不碰 v-html -->
  <div v-else class="md-body">
    <template v-for="(node, i) in nodes" :key="i">
      <h3 v-if="node.type === 'heading'">…</h3>
      <p v-else-if="node.type === 'paragraph'">
        <span v-for="(seg, j) in node.inline" :key="j" :class="{ 'md-bold': seg.bold, 'md-code': seg.code }">{{ seg.text }}</span>
      </p>
      <ol v-else-if="node.type === 'list' && node.ordered"> …递归渲染 items… </ol>
      <ul v-else> … </ul>
    </template>
  </div>
</template>
```

要点：

- **绝不使用 `v-html`**，所有文本仍走 `{{ }}`，Vue 自动转义，恶意文档无法注入。
- 列表嵌套用**递归子组件**或自引用（`<MessageBody>` 递归），避免模板里手写多层 `v-if`。
- 组件对 `.md-body` 的样式放在组件内 `<style scoped>`，与 `style.css` 的全局暗色主题变量保持一致。

### 5.4 流式策略（决定体验成败）

现状是**逐 token 重渲染**（`App.vue:310-316`）。若每个 token 都跑一次 Markdown 解析：

- 半截语法会持续闪跳（`**农田防护` 在闭合前会显示为加粗或字面星号，反复横跳）；
- 长回答（截图这种 500+ 字）会出现可见卡顿。

**建议**：`streaming: true` 期间只渲染 `md-plain`（`white-space: pre-wrap` 的纯文本，换行已经正常，用户感知流畅）；收到 `done` 事件（`App.vue:337`）后把 `streaming` 置 `false`，一次性切换为结构化渲染。

这个切换是**同一段文字从「朴素排版」变为「精致排版」**，视觉上是渐进增强，不会突兀。如果希望更平滑，可加 `.16s` 的 `opacity` 过渡（与既有 `page-fade` 风格一致）。

### 5.5 引用卡片（解决 P3）

在消息体下方独立渲染，取代字符串拼接：

```
┌ 参考来源 ─────────────────────────────┐
│ 📄 GB/T30600-2022 高标准农田建设通则   │
│    第 18 页 · 「…关联设施应采集…」      │  ← excerpt 摘要
└───────────────────────────────────────┘
```

- `docName` + `pageNum` 作主标题（沿用 `formatSources` 已有的展示格式，视觉语言统一）。
- `excerpt` 作为摘要行，`-webkit-line-clamp:2` 截断——**这个字段目前被完全浪费了**。
- `docId` 预留点击跳转（可复用 `openPreview(file)` 的弹窗，`App.vue:433-443`）。
- 样式建议复用 `.file-tags span` 的暗色卡片语汇（`style.css:10` 附近的薄荷绿描边），保持整站一致。

### 5.6 必需的最小样式补充

即使走 B 方案，`style.css` 仍需追加：

```css
/* 流式纯文本阶段 */
.conversation-message p{white-space:pre-wrap;word-break:break-word;overflow-wrap:anywhere}
/* 结构化阶段 */
.md-body{color:#d0dce0;font-size:12px;line-height:1.8}
.md-body p{margin:6px 0 0}
.md-body ol,.md-body ul{margin:6px 0 0;padding-left:18px}
.md-body li{margin:3px 0}
.md-body .md-bold{color:#e8f3ee;font-weight:650}
.md-body .md-code{font-family:ui-monospace,Consolas,monospace;background:#16232d;border:1px solid #26343e;border-radius:4px;padding:1px 4px;font-size:11px}
```

> `word-break:break-word` / `overflow-wrap:anywhere` 不可省：中文长串与 URL 会撑破 `.conversation-message>div` 的 `max-width:calc(100% - 44px)`（`style.css:98`）。

### 5.7 移除 `cleanCitationMarkers`（可选但建议）

保留 `[参考N]` 清洗能力，但**不要在流式 token 上执行**：改为在 `done` 回调里对完整文本执行一次（`App.vue:339` 已经有一次全量赋值，把清洗挪到那里即可），彻底消除 2.4 的闪烁问题。

---

## 6. 实施步骤（建议顺序）

| 步骤 | 内容 | 涉及文件 | 可独立验证 |
|---|---|---|---|
| 1 | 加 `white-space:pre-wrap` 等 3 行 CSS | `style.css` | ✅ 换行立即可见（等于方案 A） |
| 2 | 新增 `utils/markdown.ts` + 单元用例 | 新文件 | ✅ 纯函数，可用题目截图文本直接跑 |
| 3 | 新增 `components/MessageBody.vue` | 新文件 | ✅ |
| 4 | `App.vue` 接入组件，替换 `:533` 的 `<p>` | `App.vue` | ✅ |
| 5 | 数据模型改造：`sources` 独立字段，删 `formatSources` | `App.vue:84/92-96/98-117/343` | ✅ 历史会话回归 |
| 6 | 流式标记 `streaming`，`done` 后切换渲染 | `App.vue:290/337-353` | ✅ 重点测长回答 |
| 7 | 引用卡片组件 | 新文件 + `App.vue` | ✅ |
| 8 | 清洗时机后移 | `App.vue:314/339` | ✅ |

**1 → 4 是核心链路，做完就有 90% 的收益**；5 → 8 是体验精修。

---

## 7. 验收标准

用截图那次提问做基准用例（**「哪些关联工程设施需要采集调查？」**）：

- [ ] `**末端掉头点**` 渲染为加粗，而不是显示星号
- [ ] `1.` `2.` `3.` 渲染为**有序列表**，每项独立成行
- [ ] `农田防风林工程 / 梯田埂坎防护林 / 护路护沟护坡林` 等子项渲染为**缩进的嵌套列表**，层级一目了然
- [ ] 参考来源不再出现在正文尾部，而是独立的来源卡片，显示文件名、页码与摘要
- [ ] 流式输出过程中文字**不出现星号闪跳**
- [ ] 切换到历史会话（`App.vue:127-150`）后，上述格式**同样正确**（验证 `restoreChatMessage` 改造）
- [ ] 上传含 `<script>` 或 `<img onerror=…>` 的 TXT 文档并命中它，**页面无脚本执行**（XSS 回归）
- [ ] `npm run build`（`vue-tsc -b && vite build`）类型检查通过

---

## 8. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| 自研解析器覆盖不全（表格、引用块、链接） | 中 | 先只支持截图中实际出现的语法；解析失败的行**降级为原样段落**，绝不丢字 |
| 流式切换瞬间布局跳动 | 低 | 两阶段的行高/字号保持一致（都基于 12px/1.8），仅缩进与字重变化 |
| 历史消息 `sources` 格式异常 | 低 | `App.vue:108-110` 已有 `try/catch` 容错，保留即可 |
| `streaming` 标记在错误分支（`App.vue:317-336`）未复位 | 低 | 在 `error` 回调里同样置 `false`，否则回答会永久停在纯文本态 |

---

## 9. 涉及文件清单

| 文件 | 性质 | 说明 |
|---|---|---|
| `front/src/style.css` | 修改 | 追加 `pre-wrap` 与 `.md-body` 规则 |
| `front/src/utils/markdown.ts` | **新增** | Markdown → 节点树解析器（~80 行） |
| `front/src/components/MessageBody.vue` | **新增** | 结构化渲染组件，不使用 `v-html` |
| `front/src/components/SourceCards.vue` | **新增** | 引用卡片（利用被浪费的 `excerpt`） |
| `front/src/App.vue` | 修改 | 约 5 处：`:84` 类型、`:92-96` 删 `formatSources`、`:98-117` 保留 sources、`:290/337` streaming 标记、`:533` 换组件 |
| `front/src/api/rag.ts` | **不改** | `Source` 结构已足够，无需后端配合 |
| 后端 `F:\ai\agent\rag` | **不改** | 已正确输出换行与 sources |

---

## 10. 备选：方案 A 的最小改动

若只想先止血、不动结构，`front/src/style.css:99` 改为：

```css
.conversation-message p{margin:6px 0 0;color:#d0dce0;font-size:12px;line-height:1.8;white-space:pre-wrap;word-break:break-word}
```

**收益**：P2（结构塌陷）立即缓解，`\n` 恢复。
**不解决**：P1（`**` 仍裸露）、P3（引用仍是纯文本尾巴）、层级仍依赖模型自身输出缩进。

适合作为 B 方案第 1 步落地，之后增量推进。
