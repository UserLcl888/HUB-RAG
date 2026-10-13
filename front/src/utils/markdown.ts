/**
 * 极简 Markdown → 节点树解析器。
 *
 * 只覆盖 RAG 回答实际会输出的语法：标题 / 有序列表 / 无序列表 / 嵌套 / 段落 / 引用 / 行内加粗与代码。
 * 刻意不解析表格、链接、图片与内联 HTML —— 农田标准的问答里基本不出现，且解析 HTML 会引入安全面。
 * 解析不了的内容一律降级为普通段落，绝不丢字。
 *
 * 产出的是节点树而非 HTML 字符串，渲染层用 v-for + {{ }} 输出，由 Vue 负责转义（不使用 v-html）。
 */

export type Inline = {
  text: string
  bold?: boolean
  italic?: boolean
  code?: boolean
}

export type MdListItem = {
  inline: Inline[]
  children: MdNode[]
}

export type MdNode =
  | { type: 'heading'; level: number; inline: Inline[] }
  | { type: 'paragraph'; inline: Inline[] }
  | { type: 'quote'; inline: Inline[] }
  | { type: 'list'; ordered: boolean; items: MdListItem[] }
  | MdClause

/** 行首带条款编号的条目（6.6.4 …）：渲染成层级编号，条款号本身不再显示 */
export type MdClause = {
  type: 'clause'
  number: string
  /** 编号的点号层数：6.6.1 → 3、6.6.4.1 → 4，用于还原原文的父子层级 */
  depth: number
  inline: Inline[]
  /** "——" 分项，每条独立成行 */
  subitems: Inline[][]
}

export type ListNode = Extract<MdNode, { type: 'list' }>

/** 列表帧栈：记录当前打开的每一层列表及其缩进 */
type ListFrame = {
  indent: number
  ordered: boolean
  list: ListNode
}

const INLINE_RE = /(\*\*[^*]+\*\*|`[^`]+`|\*[^*\n]+\*)/g
const HEADING_RE = /^(#{1,4})\s+(.*)$/
const QUOTE_RE = /^\s*>\s?(.*)$/
const ITEM_RE = /^([ \u3000]*)(\d{1,2}[.、)]|[-*+•])\s+(.*)$/
/**
 * 行首条款编号：6.1.1 / 6.1.1.1（允许被 ** 包裹，也允许编号后直接接汉字）。
 * 这类行必须**独立成行**：否则模型的 "6.1.1 …\n6.1.2 …" 会被下面的段落聚合规则用空格拼成一段，
 * 看起来就像把两条条款写在了同一行。
 */
const CLAUSE_RE = /^(?:\*\*)?\d{1,2}(?:\.\d{1,3}){1,4}(?:\*\*)?(?=[\s、.．:：]|[\u4e00-\u9fa5]|$)/
/**
 * 章节条款编号的抽取：`6.7.1 正文…` / `**6.7.1** 正文…` / `6.7.1正文…`。
 * 编号只用于保留溯源信息（节点数据里），回答界面上不显示，统一由渲染层编号成 1./2./3.。
 */
const CLAUSE_HEAD_RE = /^(?:\*\*)?(\d{1,2}(?:\.\d{1,3}){1,4})(?:\*\*)?[\s、.．:：]*/
/** "——" 分项行（PDF 原文是 "———"，库里存成 "——"） */
const SUBITEM_RE = /^—{2,}\s*/
/**
 * 挤在同一行的多个 "——" 分项之间的边界：只有前面是句末标点（或行首，见 splitSubitemRuns）才算，
 * 避免把正文里破折号用法（如"高标准农田——通则"）误拆。
 */
const SUBITEM_BOUNDARY_RE = /(?<=[。；！？：])\s*(?=—{2,})/

/**
 * 把塞在同一行的多个 "——" 分项拆成多行，保证每条分项独立成行。
 * 例："…具体设计要满足以下技术要求。——泵站设计应符合GB 50265的规定。——机井设计应符合…"
 * → 拆成 "…技术要求。" / "——泵站…" / "——机井…" 三行。
 */
export function splitSubitemRuns(line: string): string[] {
  if (!line.includes('——')) return [line]
  const parts = line.split(SUBITEM_BOUNDARY_RE).map(part => part.trim()).filter(Boolean)
  return parts.length > 1 ? parts : [line]
}

/** 带编号层级的条款草稿（解析期与流式期共用） */
export type ClauseDraft = { number: string; depth: number; inline: Inline[]; subitems: Inline[][] }

/** 渲染用条款：顶层带显示编号（1. / 2.），子层只用层级（绿点标记），不再显示 4.1 */
export type LabeledClause = { label: string; level: number; inline: Inline[]; subitems: Inline[][] }

/**
 * 生成层级显示编号：以本组最浅层为顶层。
 * 顶层给编号（`1.` `2.`），子层不给编号，只标出层级（level 1/2…），由渲染层用绿点表示，
 * 这样回答里既不出现原文的 `6.6.4.1`，也不会出现"4.1"这种派生编号。
 */
export function labelClauses(drafts: ClauseDraft[]): LabeledClause[] {
  if (!drafts.length) return []
  const minDepth = Math.min(...drafts.map(draft => draft.depth))
  let topCount = 0
  return drafts.map(draft => {
    const level = Math.max(0, draft.depth - minDepth)
    const label = level === 0 ? `${++topCount}.` : ''
    return { label, level, inline: draft.inline, subitems: draft.subitems }
  })
}

/** 行内解析：只切出 **加粗**、*斜体*、`代码`，不生成 HTML */
export function parseInline(text: string): Inline[] {
  const out: Inline[] = []
  let cursor = 0
  for (const match of text.matchAll(INLINE_RE)) {
    const start = match.index ?? 0
    if (start > cursor) out.push({ text: text.slice(cursor, start) })
    const token = match[0]
    if (token.startsWith('**')) out.push({ text: token.slice(2, -2), bold: true })
    else if (token.startsWith('`')) out.push({ text: token.slice(1, -1), code: true })
    else out.push({ text: token.slice(1, -1), italic: true })
    cursor = start + token.length
  }
  if (cursor < text.length) out.push({ text: text.slice(cursor) })
  return out.length ? out : [{ text }]
}

/** 流式渲染块：连续的条款行合成一个层级列表，其余行保留原始换行 */
export type StreamingBlock =
  | { kind: 'clauses'; items: LabeledClause[] }
  | { kind: 'text'; inline: Inline[] }

/**
 * 流式输出用的行级分块：一边接收 token 一边就按"列表 + 段落"的形态渲染，
 * 不用等回答结束再做结构化解析（那样会出现"流式时是 6.7.1、结束后突然变成 1."的跳变）。
 * <p>
 * 行内标记（**加粗**、`代码`、*斜体*）在这里就用 {@link parseInline} 处理：它只认**成对**的标记，
 * 还没收到收尾符号的半截 `**` 按普通字符显示，配对一到立刻变粗体 —— 实时生效且不会闪烁。
 */
export function streamingBlocks(text: string): StreamingBlock[] {
  const blocks: StreamingBlock[] = []
  let pending: string[] = []
  let drafts: ClauseDraft[] = []

  const flushClauses = () => {
    if (!drafts.length) return
    blocks.push({ kind: 'clauses', items: labelClauses(drafts) })
    drafts = []
  }

  const flushText = () => {
    if (!pending.length) return
    // 首尾空行收掉，避免流式过程中列表前后出现多余空档
    const joined = pending.join('\n').replace(/^\n+|\n+$/g, '')
    pending = []
    if (joined) blocks.push({ kind: 'text', inline: parseInline(joined) })
  }

  const lines = text.split('\n').flatMap(splitSubitemRuns)

  for (const line of lines) {
    const trimmed = line.trim()
    const subitem = SUBITEM_RE.test(line)
    const head = subitem ? null : CLAUSE_HEAD_RE.exec(trimmed)
    if (subitem && drafts.length) {
      flushText()
      drafts[drafts.length - 1].subitems.push(parseInline(trimmed.replace(SUBITEM_RE, '')))
      continue
    }
    if (head && CLAUSE_RE.test(trimmed)) {
      flushText()
      drafts.push({
        number: head[1],
        depth: head[1].split('.').length,
        inline: parseInline(trimmed.slice(head[0].length)),
        subitems: [],
      })
      continue
    }
    if (drafts.length) flushClauses()
    pending.push(line)
  }
  flushText()
  flushClauses()
  return blocks
}

export function parseMarkdown(source: string): MdNode[] {
  const lines = source.replace(/\r\n?/g, '\n').replace(/\t/g, '  ').split('\n').flatMap(splitSubitemRuns)
  const blocks: MdNode[] = []
  const stack: ListFrame[] = []
  let paragraph: string[] = []
  let clause: { number: string; depth: number; parts: string[]; subitems: Inline[][] } | null = null

  // 段落归属：列表打开时挂到最内层最后一个列表项的 children，否则挂到顶层
  const container = (): MdNode[] => {
    const frame = stack[stack.length - 1]
    if (!frame) return blocks
    const item = frame.list.items[frame.list.items.length - 1]
    return item ? item.children : blocks
  }

  const flushParagraph = () => {
    if (!paragraph.length) return
    const text = paragraph.join(' ').replace(/\s+/g, ' ').trim()
    paragraph = []
    if (text) container().push({ type: 'paragraph', inline: parseInline(text) })
  }

  // 条款条目：换行但属于同一条的续行并回同一条；编号收进节点数据，界面不展示
  const flushClause = () => {
    if (!clause) return
    // 中文条款被排版换行时不能用空格拼接，否则会出现"施工进度计 划等"这类断词
    const text = clause.parts.join('').replace(/\s+/g, ' ').trim()
    const { number, depth, subitems } = clause
    clause = null
    if (text || subitems.length) {
      container().push({ type: 'clause', number, depth, inline: parseInline(text), subitems })
    }
  }

  const flushAll = () => {
    flushParagraph()
    flushClause()
  }

  for (const rawLine of lines) {
    const line = rawLine.replace(/\s+$/, '')
    if (!line.trim()) {
      flushAll()
      continue
    }

    const heading = HEADING_RE.exec(line)
    if (heading) {
      flushAll()
      stack.length = 0
      blocks.push({ type: 'heading', level: heading[1].length, inline: parseInline(heading[2].trim()) })
      continue
    }

    const quote = QUOTE_RE.exec(line)
    if (quote) {
      flushAll()
      container().push({ type: 'quote', inline: parseInline(quote[1].trim()) })
      continue
    }

    // 条款行（6.6.4 …）独立成一个条目：条款号从正文里摘掉，只留在节点数据里；
    // 渲染层按编号层数输出 1./2. 与 4.1/4.2 的层级编号。
    const clauseHead = CLAUSE_HEAD_RE.exec(line.trim())
    if (clauseHead && CLAUSE_RE.test(line.trim())) {
      flushAll()
      stack.length = 0
      clause = {
        number: clauseHead[1],
        depth: clauseHead[1].split('.').length,
        parts: [line.trim().slice(clauseHead[0].length)],
        subitems: [],
      }
      continue
    }

    // "——" 分项：挂在当前条款下，每条独立成行（没有归属条款时按普通段落处理）
    if (SUBITEM_RE.test(line) && clause) {
      clause.subitems.push(parseInline(line.trim().replace(SUBITEM_RE, '')))
      continue
    }

    const item = ITEM_RE.exec(line)
    if (item) {
      flushAll()
      const indent = item[1].replace(/\u3000/g, '  ').length
      const ordered = /^\d/.test(item[2])
      const inline = parseInline(item[3].trim())

      // 规则 1（缩进优先）：先收缩到不深于当前缩进的层级
      while (stack.length && stack[stack.length - 1].indent > indent) stack.pop()

      // 规则 2（标记类型变化兜底）：模型常不给子项缩进就换标记类型（1. 下面直接写 -）。
      // 此时若栈中存在「同缩进 + 同标记类型」的祖先层，就回退到那一层，
      // 保证 1. → - → - → 2. 能正确收尾，而不是无限往下嵌套。
      if (stack.length) {
        const top = stack[stack.length - 1]
        if (top.indent === indent && top.ordered !== ordered) {
          const back = [...stack].reverse()
            .find(frame => frame.indent === indent && frame.ordered === ordered)
          if (back) while (stack.length && stack[stack.length - 1] !== back) stack.pop()
        }
      }

      // 与栈顶同级同类 → 追加兄弟项
      const top = stack[stack.length - 1]
      if (top && top.indent === indent && top.ordered === ordered) {
        top.list.items.push({ inline, children: [] })
        continue
      }

      // 否则新开一层列表
      const list: ListNode = { type: 'list', ordered, items: [{ inline, children: [] }] }
      container().push(list)
      stack.push({ indent, ordered, list })
      continue
    }

    // 条款的续行（排版换行但属于同一条）并回上一条
    if (clause) {
      clause.parts.push(line.trim())
      continue
    }

    // 规则 3（顶格段落结束列表）：行首无缩进的普通文本行意味着此前的列表已结束，
    // 例如回答末尾的总结句。若不这样做，它会被错误地吞进最后一个 <li> 里。
    // 有缩进时则视为上一列表项的续行段落，保留在列表内部。
    // 这是对 CommonMark「懒惰续行」的有意偏离。
    if (!paragraph.length) {
      const lead = line.length - line.trimStart().length
      if (lead === 0) stack.length = 0
    }
    paragraph.push(line.trim())
  }

  flushAll()
  return blocks
}
