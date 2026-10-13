<script setup lang="ts">
import { computed, ref } from 'vue'
import { AlertTriangle, ArrowRight, CheckCircle2, Clock3, FileText, ShieldCheck, Sparkles } from '@lucide/vue'

type ReviewItem = {
  title: string
  detail: string
  status: 'pass' | 'warn'
}

const reviewItems: ReviewItem[] = [
  { title: '文件格式', detail: 'PDF 文件，可正常读取', status: 'pass' },
  { title: '项目名称', detail: '已识别：高标准农田建设项目', status: 'pass' },
  { title: '设计依据', detail: '检测到 4 条标准规范引用', status: 'pass' },
  { title: '验收材料', detail: '建议补充竣工验收相关附件', status: 'warn' },
  { title: '签章信息', detail: '未在当前页面识别到签章区域', status: 'warn' },
]

const selectedFile = '高标准农田建设项目初步设计技术规程.pdf'
const filter = ref<'all' | 'pass' | 'warn'>('all')
const visibleItems = computed(() => filter.value === 'all' ? reviewItems : reviewItems.filter(item => item.status === filter.value))
const passedCount = computed(() => reviewItems.filter(item => item.status === 'pass').length)
const warningCount = computed(() => reviewItems.filter(item => item.status === 'warn').length)
</script>

<template>
  <main class="review-page">
    <header class="review-intro">
      <div>
        <div class="review-eyebrow"><Sparkles :size="14" /> DOCUMENT REVIEW <span>· 静态演示</span></div>
        <h1>让文档检查，更快找到重点。</h1>
        <p>以固定示例展示文档审核结果、风险提示和待补充内容。</p>
      </div>
      <RouterLink class="review-upload-link" to="/documents"><FileText :size="16" />选择其他文档 <ArrowRight :size="15" /></RouterLink>
    </header>

    <section class="review-summary">
      <div class="review-file-card">
        <div class="review-file-icon"><FileText :size="22" /><span>PDF</span></div>
        <div><span class="review-label">演示审核文档</span><h2>{{ selectedFile }}</h2><p><Clock3 :size="13" /> 示例资料 · 195.3 KB · 86 页</p></div>
        <span class="review-demo-pill">示例文档</span>
      </div>
      <div class="review-score-card">
        <div class="review-score-ring"><strong>82</strong><span>综合得分</span></div>
        <div><span class="review-label">AI 初步检查</span><h2>基本符合要求</h2><p>建议处理 {{ warningCount }} 项提示后再次确认</p></div>
      </div>
    </section>

    <div class="review-layout">
      <section class="review-results">
        <div class="review-section-heading"><div><span class="review-label">CHECKLIST</span><h2>审核结果</h2></div><span class="review-count">{{ reviewItems.length }} 项检查</span></div>
        <div class="review-filter">
          <button :class="{ active: filter === 'all' }" :aria-pressed="filter === 'all'" @click="filter = 'all'">全部 <span>{{ reviewItems.length }}</span></button>
          <button :class="{ active: filter === 'pass' }" :aria-pressed="filter === 'pass'" @click="filter = 'pass'">通过 <span>{{ passedCount }}</span></button>
          <button :class="{ active: filter === 'warn' }" :aria-pressed="filter === 'warn'" @click="filter = 'warn'">待确认 <span>{{ warningCount }}</span></button>
        </div>
        <TransitionGroup name="review-item" tag="div" class="review-list">
          <article v-for="item in visibleItems" :key="item.title" :class="['review-item', item.status]">
            <span class="review-status-icon"><CheckCircle2 v-if="item.status === 'pass'" :size="17" /><AlertTriangle v-else :size="17" /></span>
            <div><h3>{{ item.title }}</h3><p>{{ item.detail }}</p></div>
            <span class="review-status-text">{{ item.status === 'pass' ? '已通过' : '待确认' }}</span>
            <ArrowRight :size="15" />
          </article>
        </TransitionGroup>
      </section>

      <aside class="review-side">
        <div class="review-side-heading"><ShieldCheck :size="17" />审核提示</div>
        <div class="review-advice"><span class="review-advice-icon"><AlertTriangle :size="16" /></span><div><strong>优先处理待确认项</strong><p>待确认内容不代表文档不合格，需要结合原文和人工核验。</p></div></div>
        <div class="review-advice"><span class="review-advice-icon green"><CheckCircle2 :size="16" /></span><div><strong>已识别主要结构</strong><p>文档标题、建设依据和大部分项目字段已完成识别。</p></div></div>
        <div class="review-side-foot"><span>审核规则</span><strong>演示版 v0.1</strong></div>
      </aside>
    </div>
    <p class="review-note">当前审核结果为固定演示数据，不会读取或修改真实文件；后续接入解析服务后再替换为实时结果。</p>
  </main>
</template>

<style scoped>
/*
 * 同 KnowledgeGraph：本页颜色全部走 --rv-* 变量，深色与白色两套取值都在
 * style-light-tweaks.css，组件里不写字面色值、也不放默认值。
 */
.review-page { position: relative; z-index: 1; width: min(1180px, calc(100% - 48px)); margin: 0 auto; padding: 38px 0 52px; }
.review-intro { display: flex; align-items: end; justify-content: space-between; gap: 20px; margin-bottom: 25px; }
.review-eyebrow { display: flex; align-items: center; gap: 8px; color: var(--rv-accent); font-size: 10px; font-weight: 700; letter-spacing: .13em; }
.review-eyebrow span { color: var(--rv-eyebrow-muted); font-weight: 400; letter-spacing: 0; }
.review-intro h1 { margin: 12px 0 7px; color: var(--rv-title); font-size: clamp(25px, 3vw, 33px); letter-spacing: -.04em; }
.review-intro p { margin: 0; color: var(--rv-subtitle); font-size: 12px; }
.review-upload-link { display: inline-flex; align-items: center; gap: 7px; padding: 10px 12px; border: 1px solid var(--rv-ghost-border); border-radius: 8px; color: var(--rv-ghost-text); font-size: 10px; text-decoration: none; white-space: nowrap; }
.review-upload-link:hover { border-color: var(--rv-ghost-hover-border); color: var(--rv-ghost-hover-text); }
.review-summary { display: grid; grid-template-columns: 1.45fr 1fr; gap: 13px; margin-bottom: 15px; }
.review-file-card, .review-score-card, .review-results, .review-side { border: 1px solid var(--rv-border); border-radius: 14px; background: var(--rv-surface); box-shadow: var(--rv-shadow); }
.review-file-card, .review-score-card { display: flex; align-items: center; min-width: 0; padding: 19px; gap: 13px; }
.review-file-icon { width: 48px; height: 48px; display: flex; flex-direction: column; align-items: center; justify-content: center; flex: none; border-radius: 12px; background: var(--rv-file-icon-bg); color: var(--rv-file-icon); }
.review-file-icon span { font-size: 7px; font-weight: 700; letter-spacing: .08em; }
.review-label { color: var(--rv-label); font-size: 9px; letter-spacing: .05em; }
.review-file-card h2, .review-score-card h2 { overflow: hidden; margin: 5px 0 5px; color: var(--rv-file-title); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.review-file-card p, .review-score-card p { display: flex; align-items: center; gap: 5px; margin: 0; color: var(--rv-file-meta); font-size: 9px; }
.review-demo-pill { align-self: start; margin-left: auto; padding: 4px 7px; border: 1px solid var(--rv-demo-border); border-radius: 5px; color: var(--rv-demo-text); font-size: 8px; white-space: nowrap; }
.review-score-ring { width: 66px; height: 66px; display: flex; flex-direction: column; align-items: center; justify-content: center; flex: none; border: 2px solid var(--rv-ring-border); border-radius: 50%; box-shadow: var(--rv-ring-glow); }
.review-score-ring strong { color: var(--rv-ring-score); font-size: 20px; line-height: 1; }.review-score-ring span { margin-top: 5px; color: var(--rv-ring-label); font-size: 8px; }
.review-layout { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 15px; }
.review-results { min-width: 0; padding: 20px; }.review-side { padding: 20px; }
.review-section-heading { display: flex; align-items: end; justify-content: space-between; }.review-section-heading h2 { margin: 4px 0 0; color: var(--rv-heading); font-size: 18px; }.review-count { color: var(--rv-count); font-size: 9px; }
.review-filter { display: flex; gap: 6px; margin: 17px 0 13px; padding-bottom: 13px; border-bottom: 1px solid var(--rv-rule); }.review-filter button { padding: 6px 9px; border: 1px solid transparent; border-radius: 6px; background: transparent; color: var(--rv-tab-text); font-size: 9px; }.review-filter button span { margin-left: 3px; color: var(--rv-tab-count); }.review-filter button.active, .review-filter button:hover { border-color: var(--rv-tab-active-border); background: var(--rv-tab-active-bg); color: var(--rv-tab-active-text); }.review-filter button.active span { color: var(--rv-tab-active-count); }
.review-list { display: flex; flex-direction: column; gap: 7px; }.review-item { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto 15px; align-items: center; gap: 9px; min-height: 63px; padding: 9px 10px; border: 1px solid var(--rv-line); border-radius: 9px; background: var(--rv-card-bg); }.review-item:hover { border-color: var(--rv-hover-border); background: var(--rv-hover-bg); }.review-status-icon { width: 27px; height: 27px; display: grid; place-items: center; border-radius: 8px; color: var(--rv-pass); background: var(--rv-pass-bg); }.review-item.warn .review-status-icon { color: var(--rv-warn); background: var(--rv-warn-bg); }.review-item h3 { margin: 0 0 4px; color: var(--rv-item-title); font-size: 11px; }.review-item p { margin: 0; overflow: hidden; color: var(--rv-item-text); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.review-status-text { color: var(--rv-status-pass); font-size: 9px; }.review-item.warn .review-status-text { color: var(--rv-status-warn); }.review-item>svg { color: var(--rv-chevron); }
.review-list { position: relative; min-height: 343px; }
.review-item-move, .review-item-enter-active, .review-item-leave-active { transition: transform .2s ease, opacity .2s ease; }
.review-item-leave-active { position: absolute; width: 100%; }
.review-item-enter-from, .review-item-leave-to { opacity: 0; transform: translateY(8px); }
.review-side-heading { display: flex; align-items: center; gap: 7px; padding-bottom: 14px; border-bottom: 1px solid var(--rv-rule); color: var(--rv-side-heading); font-size: 12px; font-weight: 650; }.review-side-heading svg { color: var(--rv-side-icon); }.review-advice { display: flex; gap: 9px; padding: 15px 0; border-bottom: 1px solid var(--rv-rule-soft); }.review-advice-icon { width: 28px; height: 28px; display: grid; place-items: center; flex: none; border-radius: 8px; color: var(--rv-warn); background: var(--rv-warn-bg); }.review-advice-icon.green { color: var(--rv-pass); background: var(--rv-pass-bg); }.review-advice strong { display: block; margin-bottom: 4px; color: var(--rv-advice-title); font-size: 10px; }.review-advice p { margin: 0; color: var(--rv-advice-text); font-size: 9px; line-height: 1.65; }.review-side-foot { display: flex; justify-content: space-between; margin-top: 18px; color: var(--rv-foot); font-size: 9px; }.review-side-foot strong { color: var(--rv-foot-value); font-weight: 500; }.review-note { margin: 14px 1px 0; color: var(--rv-note); font-size: 10px; line-height: 1.6; }
@media (max-width: 900px) { .review-layout, .review-summary { grid-template-columns: 1fr; }.review-side { display: grid; grid-template-columns: 1fr 1fr; gap: 0 18px; }.review-side-heading, .review-side-foot { grid-column: 1 / -1; }.review-advice { border-bottom: 0; } }
@media (max-width: 580px) { .review-page { width: calc(100% - 30px); padding-top: 25px; }.review-intro { align-items: start; flex-direction: column; }.review-intro h1 { font-size: 24px; }.review-file-card, .review-score-card { padding: 14px; }.review-file-card h2 { max-width: 200px; }.review-side { display: block; }.review-item { grid-template-columns: 28px minmax(0, 1fr) 15px; }.review-status-text { display: none; } }
@media (prefers-reduced-motion: reduce) { .review-item-move, .review-item-enter-active, .review-item-leave-active { transition: none; } }
</style>
