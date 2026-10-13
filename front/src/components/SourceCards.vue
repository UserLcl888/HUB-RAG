<script setup lang="ts">
import { computed } from 'vue'
import { FileText } from '@lucide/vue'
import type { Source } from '../api/rag'

const props = defineProps<{ sources: Source[] }>()

// 同一文档同一页可能被引用多次，按「文档 + 页」去重
const cards = computed(() => {
  const seen = new Set<string>()
  return props.sources.filter(source => {
    const key = `${source.docId}-${source.pageNum ?? 'x'}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
})

// 来源卡片只展示文本来源信息，不打开文档预览。
const hint = (source: Source) => {
  const parts = [source.docName]
  if (source.pageNum) parts.push(`第 ${source.pageNum} 页`)
  if (source.sectionTitle) parts.push(source.sectionTitle)
  if (source.excerpt) parts.push(source.excerpt)
  return parts.join('\n')
}
</script>

<template>
  <div v-if="cards.length" class="source-cards">
    <div class="source-cards-title">参考来源</div>
    <div
      v-for="(source, i) in cards"
      :key="i"
      class="source-card"
      :title="hint(source)"
    >
      <FileText :size="14" />
      <span class="source-card-name">{{ source.docName }}</span>
      <span v-if="source.pageNum" class="source-card-page">第 {{ source.pageNum }} 页</span>
    </div>
  </div>
</template>
