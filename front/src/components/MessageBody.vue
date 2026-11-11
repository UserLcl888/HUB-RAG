<script setup lang="ts">
import { computed } from 'vue'
import { labelClauses, parseInline, parseMarkdown, splitStreamingTail, type Inline, type LabeledClause, type MdClause, type MdListItem, type MdNode } from '../utils/markdown'

// 模板里的 <MessageBody> 自引用依赖组件名
defineOptions({ name: 'MessageBody' })

const props = defineProps<{
  /** 顶层调用时传入回答正文 */
  text?: string
  /** 递归调用时传入子节点 */
  nodes?: MdNode[]
  /** 流式输出中 */
  streaming?: boolean
}>()

/**
 * 流式期间只解析"已经写完的完整行"，正在输入的那一行单独按纯文本显示
 * （见 splitStreamingTail）。这样流式和结束后用的是**同一套结构**，
 * 不会出现"输出时一串横线、结束后才变列表"的跳变。
 */
const streamParts = computed(() => splitStreamingTail(props.text ?? ''))

const tree = computed<MdNode[]>(() => {
  if (props.nodes) return props.nodes
  return parseMarkdown(props.streaming ? streamParts.value.stable : (props.text ?? ''))
})

/** 列表标记（-、*、•、1. …），与 markdown.ts 的 ITEM_RE 保持一致 */
const ITEM_MARKER = /^[ \u3000]*(\d{1,2}[.、)]|[-*+•])(?:\s+|(?=[\u4e00-\u9fff]))/
/** 正在输入的最后一行本身已经是列表项 → 直接按列表项渲染，避免先出现字面短横线再变清单 */
const streamTailIsItem = computed(() => ITEM_MARKER.test(streamParts.value.tail))
/** 正在输入那一行的正文（去掉列表标记） */
const streamTailInline = computed(() => parseInline(streamParts.value.tail.replace(ITEM_MARKER, '')))

/**
 * 渲染块：连续的条款条目（原文 6.7.1 / 6.7.2 …）合并成一个有序列表，
 * 由 <ol> 统一编号成 1./2./3.，条款号不再出现在回答里。
 */
type RenderBlock =
  | { kind: 'heading'; level: number; inline: Inline[] }
  | { kind: 'quote'; inline: Inline[] }
  | { kind: 'paragraph'; inline: Inline[] }
  | { kind: 'list'; ordered: boolean; items: MdListItem[] }
  | { kind: 'clauses'; items: LabeledClause[] }

const blocks = computed<RenderBlock[]>(() => {
  // 编号基准是整条回答：先把全部条款一次性编号，再按原顺序切块。
  // 按"连续块"分别编号时，条款之间夹一段说明文字就会重新从 1. 开始，出现多个 1.
  const labels = labelClauses(
    tree.value
      .filter((node): node is MdClause => node.type === 'clause')
      .map(node => ({ number: node.number, depth: node.depth, inline: node.inline, subitems: node.subitems })),
  )
  const out: RenderBlock[] = []
  let cursor = 0
  let runSize = 0
  const flushClauses = () => {
    if (!runSize) return
    out.push({ kind: 'clauses', items: labels.slice(cursor, cursor + runSize) })
    cursor += runSize
    runSize = 0
  }
  for (const node of tree.value) {
    switch (node.type) {
      case 'heading':
        flushClauses()
        out.push({ kind: 'heading', level: node.level, inline: node.inline })
        break
      case 'quote':
        flushClauses()
        out.push({ kind: 'quote', inline: node.inline })
        break
      case 'list':
        flushClauses()
        out.push({ kind: 'list', ordered: node.ordered, items: node.items })
        break
      case 'clause':
        runSize += 1          // 标签已按整条回答算好，这里只累计本组条数
        break
      default:
        flushClauses()
        out.push({ kind: 'paragraph', inline: node.inline })
    }
  }
  flushClauses()
  return out
})

const segClass = (seg: Inline) => ({
  'md-bold': seg.bold,
  'md-italic': seg.italic,
  'md-code': seg.code,
})

</script>

<template>
  <div class="md-body" :class="{ 'md-stream-body': streaming }">
    <template v-for="(block, i) in blocks" :key="i">
      <h3 v-if="block.kind === 'heading'" class="md-heading" :class="`md-h${block.level}`">
        <span v-for="(seg, j) in block.inline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
      </h3>

      <blockquote v-else-if="block.kind === 'quote'" class="md-quote">
        <span v-for="(seg, j) in block.inline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
      </blockquote>

      <ol v-else-if="block.kind === 'clauses'" class="md-list md-clause-list">
        <li v-for="(item, k) in block.items" :key="k" :class="{ 'is-solo': item.solo, 'is-solo-title': item.soloTitle, 'is-sub': item.level > 0, 'is-sub-deep': item.level > 1 }">
          <span class="md-clause-num" :class="{ 'is-dot': item.level > 0, 'is-dot-deep': item.level > 1 }">{{ item.label }}</span>
          <div class="md-clause-text">
            <span v-for="(seg, j) in item.inline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
            <div v-for="(sub, s) in item.subitems" :key="`s${s}`" class="md-clause-sub">
              <span v-for="(seg, j) in sub" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
            </div>
          </div>
        </li>
      </ol>

      <ol v-else-if="block.kind === 'list' && block.ordered" class="md-list">
        <li v-for="(item, k) in block.items" :key="k">
          <span v-for="(seg, j) in item.inline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
          <MessageBody v-if="item.children.length" :nodes="item.children" />
        </li>
      </ol>

      <ul v-else-if="block.kind === 'list'" class="md-list">
        <li v-for="(item, k) in block.items" :key="k">
          <span v-for="(seg, j) in item.inline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
          <MessageBody v-if="item.children.length" :nodes="item.children" />
        </li>
      </ul>

      <p v-else class="md-paragraph">
        <span v-for="(seg, j) in block.inline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
      </p>
    </template>

    <!-- 正在输入的最后一行：只做行内加粗 + 光标（半截 Markdown 参与结构解析会闪跳） -->
    <ul v-if="streaming && streamTailIsItem" class="md-list md-stream-tail">
      <li>
        <span v-for="(seg, j) in streamTailInline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
        <span class="stream-caret"></span>
      </li>
    </ul>
    <p v-else-if="streaming" class="md-paragraph md-stream-tail">
      <span v-for="(seg, j) in streamTailInline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
      <span class="stream-caret"></span>
    </p>
  </div>
</template>
