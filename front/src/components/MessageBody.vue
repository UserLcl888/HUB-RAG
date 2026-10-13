<script setup lang="ts">
import { computed } from 'vue'
import { labelClauses, parseMarkdown, streamingBlocks, type ClauseDraft, type Inline, type LabeledClause, type MdListItem, type MdNode } from '../utils/markdown'

// 模板里的 <MessageBody> 自引用依赖组件名
defineOptions({ name: 'MessageBody' })

const props = defineProps<{
  /** 顶层调用时传入回答正文 */
  text?: string
  /** 递归调用时传入子节点 */
  nodes?: MdNode[]
  /** 流式输出中：只按纯文本渲染，保留换行 */
  streaming?: boolean
}>()

// 流式期间不做 Markdown 解析：半截 ** 会反复闪跳，长回答每 token 全量重解析还会卡顿。
// 收到 done 后 streaming 置 false，一次性切换为结构化渲染 —— 同一段文字，属于渐进增强。
const tree = computed<MdNode[]>(() => {
  if (props.nodes) return props.nodes
  if (props.streaming) return []
  return parseMarkdown(props.text ?? '')
})

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
  const out: RenderBlock[] = []
  // 连续的条款条目累积成一组，组内按编号层数生成层级编号（1. / 4.1 / 4.2 …）
  let drafts: ClauseDraft[] = []
  const flushClauses = () => {
    if (!drafts.length) return
    out.push({ kind: 'clauses', items: labelClauses(drafts) })
    drafts = []
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
        drafts.push({
          number: node.number,
          depth: node.depth,
          inline: node.inline,
          subitems: node.subitems,
        })
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

// 流式期间同样按"列表 + 段落"渲染：条款行立刻就是 1./2./3. 列表项，不用等回答结束
const streamBlocks = computed(() => streamingBlocks(props.text ?? ''))
</script>

<template>
  <div v-if="streaming" class="md-body md-stream-body">
    <template v-for="(block, i) in streamBlocks" :key="i">
      <ol v-if="block.kind === 'clauses'" class="md-list md-clause-list">
        <li v-for="(item, k) in block.items" :key="k" :class="{ 'is-sub': item.level > 0, 'is-sub-deep': item.level > 1 }">
          <span class="md-clause-num" :class="{ 'is-dot': item.level > 0, 'is-dot-deep': item.level > 1 }">{{ item.label }}</span>
          <div class="md-clause-text">
            <span v-for="(seg, j) in item.inline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
            <span v-if="i === streamBlocks.length - 1 && k === block.items.length - 1" class="stream-caret"></span>
            <div v-for="(sub, s) in item.subitems" :key="`s${s}`" class="md-clause-sub">
              <span v-for="(seg, j) in sub" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
            </div>
          </div>
        </li>
      </ol>
      <p v-else class="md-plain">
        <span v-for="(seg, j) in block.inline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
        <span v-if="i === streamBlocks.length - 1" class="stream-caret"></span>
      </p>
    </template>
  </div>

  <div v-else class="md-body">
    <template v-for="(block, i) in blocks" :key="i">
      <h3 v-if="block.kind === 'heading'" class="md-heading" :class="`md-h${block.level}`">
        <span v-for="(seg, j) in block.inline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
      </h3>

      <blockquote v-else-if="block.kind === 'quote'" class="md-quote">
        <span v-for="(seg, j) in block.inline" :key="j" :class="segClass(seg)">{{ seg.text }}</span>
      </blockquote>

      <ol v-else-if="block.kind === 'clauses'" class="md-list md-clause-list">
        <li v-for="(item, k) in block.items" :key="k" :class="{ 'is-sub': item.level > 0, 'is-sub-deep': item.level > 1 }">
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
  </div>
</template>
