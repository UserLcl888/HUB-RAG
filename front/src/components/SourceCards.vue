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
</script>

<template>
  <div v-if="cards.length" class="source-cards">
    <div class="source-cards-title">参考来源</div>
    <!-- 刻意不挂 title：原生悬浮提示是个黑色框，太长且压住正文（章节/摘要只在悬浮框里出现过，
         现在需要看内容就等后续的"来源预览"，不再用悬浮框展示） -->
    <div
      v-for="(source, i) in cards"
      :key="i"
      class="source-card"
    >
      <FileText :size="14" />
      <span class="source-card-name">{{ source.docName }}</span>
      <span v-if="source.pageNum" class="source-card-page">第 {{ source.pageNum }} 页</span>
    </div>
  </div>
</template>
