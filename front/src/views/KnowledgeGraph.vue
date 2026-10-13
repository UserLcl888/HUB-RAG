<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight, BookOpen, FileText, Layers3, Network, Sparkles } from '@lucide/vue'

type GraphNode = {
  id: string
  label: string
  category: string
  description: string
  x: number
  y: number
  color: string
  documents: number
}

const nodes: GraphNode[] = [
  { id: 'construction', label: '农田建设', category: '核心主题', description: '连接农田建设政策、标准规范与项目实施知识。', x: 500, y: 270, color: 'mint', documents: 12 },
  { id: 'standard', label: '高标准农田', category: '建设领域', description: '高标准农田建设目标、实施要求及建设通则。', x: 500, y: 83, color: 'mint', documents: 6 },
  { id: 'design', label: '规划设计', category: '项目阶段', description: '项目初步设计、图纸编制与设计参考依据。', x: 725, y: 130, color: 'blue', documents: 4 },
  { id: 'quality', label: '质量验收', category: '项目阶段', description: '工程质量控制、验收准备和检查要点。', x: 814, y: 298, color: 'amber', documents: 3 },
  { id: 'archive', label: '档案管理', category: '业务管理', description: '建设项目资料归档、文件整理与保存规范。', x: 667, y: 448, color: 'violet', documents: 2 },
  { id: 'funds', label: '资金管理', category: '业务管理', description: '项目资金使用、拨付管理和监督要求。', x: 348, y: 448, color: 'amber', documents: 2 },
  { id: 'facility', label: '工程设施', category: '建设内容', description: '灌排设施、田间道路及相关配套工程。', x: 188, y: 299, color: 'blue', documents: 5 },
  { id: 'policy', label: '政策文件', category: '知识来源', description: '汇聚农田建设相关政策与公开规范资料。', x: 275, y: 130, color: 'violet', documents: 8 },
]

const selectedId = ref('construction')
const selectedNode = computed(() => nodes.find(node => node.id === selectedId.value) ?? nodes[0])
const satelliteNodes = nodes.filter(node => node.id !== 'construction')
const relatedLinks = [
  { from: 'standard', to: 'design' },
  { from: 'design', to: 'quality' },
  { from: 'quality', to: 'archive' },
  { from: 'facility', to: 'funds' },
  { from: 'policy', to: 'standard' },
]

function getNode(id: string): GraphNode {
  return nodes.find(node => node.id === id) ?? nodes[0]
}
</script>

<template>
  <main class="graph-page">
    <header class="graph-intro">
      <div>
        <div class="graph-eyebrow"><Sparkles :size="14" /> KNOWLEDGE NETWORK <span>· 静态演示</span></div>
        <h1>让知识之间的关联，一目了然。</h1>
        <p>从农田建设出发，探索政策、技术规范与项目管理之间的联系。</p>
      </div>
      <RouterLink class="graph-doc-link" to="/documents">浏览政策文件 <ArrowRight :size="16" /></RouterLink>
    </header>

    <div class="graph-layout">
      <section class="graph-canvas" aria-label="农田建设知识关系图">
        <div class="graph-canvas-top"><span class="graph-live-dot"></span> 农田建设知识网络 <span class="graph-canvas-demo">示意数据 · 非实时生成</span></div>
        <svg class="graph-svg" viewBox="0 0 1000 540" role="img" aria-labelledby="graph-title graph-description">
          <title id="graph-title">农田建设知识关系图</title>
          <desc id="graph-description">农田建设位于中心，向高标准农田、规划设计、质量验收、档案管理、资金管理、工程设施和政策文件七个主题延伸。</desc>
          <defs>
            <!-- 光晕与网格用 class + CSS 变量取色，这样深色/白色两套主题各用各的底色 -->
            <radialGradient id="graph-halo">
              <stop class="kg-halo-stop" offset="0%" />
              <stop class="kg-halo-stop kg-halo-stop-mid" offset="55%" />
              <stop class="kg-halo-stop kg-halo-stop-end" offset="100%" />
            </radialGradient>
            <pattern id="graph-grid" width="38" height="38" patternUnits="userSpaceOnUse">
              <circle class="kg-grid-dot" cx="2" cy="2" r="1" />
            </pattern>
            <radialGradient id="graph-glare" cx="34%" cy="26%" r="72%">
              <stop class="kg-glare-stop" offset="0%" stop-opacity=".46" />
              <stop class="kg-glare-stop" offset="58%" stop-opacity="0" />
            </radialGradient>
          </defs>
          <rect class="kg-grid-plane" width="1000" height="540" />
          <circle class="kg-halo" cx="500" cy="270" r="242" />
          <circle class="graph-orbit" cx="500" cy="270" r="176" />
          <circle class="graph-orbit graph-orbit-outer" cx="500" cy="270" r="309" />
          <line
            v-for="node in satelliteNodes"
            :key="`edge-${node.id}`"
            class="graph-edge"
            :class="{ 'graph-edge-active': selectedId === node.id }"
            x1="500"
            y1="270"
            :x2="node.x"
            :y2="node.y"
          />
          <line
            v-for="link in relatedLinks"
            :key="`${link.from}-${link.to}`"
            class="graph-related-edge"
            :x1="getNode(link.from).x"
            :y1="getNode(link.from).y"
            :x2="getNode(link.to).x"
            :y2="getNode(link.to).y"
          />
          <g
            v-for="node in nodes"
            :key="node.id"
            class="graph-node"
            :class="[`graph-node-${node.color}`, { 'graph-node-selected': selectedId === node.id, 'graph-node-center': node.id === 'construction' }]"
            :transform="`translate(${node.x}, ${node.y})`"
            role="button"
            tabindex="0"
            :aria-label="`查看${node.label}主题`"
            :aria-pressed="selectedId === node.id"
            @click="selectedId = node.id"
            @keydown.enter="selectedId = node.id"
            @keydown.space.prevent="selectedId = node.id"
          >
            <circle class="graph-node-pulse" :r="node.id === 'construction' ? 55 : 39" />
            <circle class="graph-node-circle" :r="node.id === 'construction' ? 48 : 34" />
            <circle class="graph-node-glare" :r="node.id === 'construction' ? 48 : 34" />
            <circle class="graph-node-dot" cy="-10" r="3" />
            <text class="graph-node-label" y="11" text-anchor="middle">{{ node.label }}</text>
          </g>
        </svg>
        <div class="graph-canvas-bottom"><span><i></i> 核心主题</span><span><i></i> 关联领域</span><span><i></i> 主题连线</span></div>
      </section>

      <aside class="graph-details" aria-label="主题详情">
        <div class="graph-details-heading"><Layers3 :size="18" /><span>主题概览</span><small>DEMO</small></div>
        <div class="graph-node-details">
          <div class="graph-details-icon"><Network :size="23" /></div>
          <span class="graph-details-category">{{ selectedNode.category }}</span>
          <h2>{{ selectedNode.label }}</h2>
          <p>{{ selectedNode.description }}</p>
          <div class="graph-details-stat"><FileText :size="15" />相关演示资料 <strong>{{ selectedNode.documents }} 份</strong></div>
        </div>
        <div class="graph-topic-list">
          <h3>探索其他主题</h3>
          <div class="graph-topic-buttons">
            <button v-for="node in nodes" :key="node.id" :class="{ active: selectedId === node.id }" :aria-pressed="selectedId === node.id" @click="selectedId = node.id">
              <span>{{ node.label }}</span><ArrowRight :size="13" />
            </button>
          </div>
        </div>
        <RouterLink class="graph-details-link" to="/documents"><BookOpen :size="16" /> 查看政策文件 <ArrowRight :size="15" /></RouterLink>
      </aside>
    </div>
    <p class="graph-page-note">当前图谱仅用于展示布局与动画，节点、关系及数量均为演示数据，后续接入真实文档后更新。</p>
  </main>
</template>

<style scoped>
/*
 * 本页"图谱本体"的颜色（画布、网格、连线、节点、详情卡、主题按钮）全部走 --kg-* 变量：
 * 深色与白色两套取值都在 style-light-tweaks.css（深色值挂在 html:not([data-theme="white"]) 下）。
 * 组件里既不留字面色值也不放默认值——本文件的规则打包后是全局样式，留默认值会参与覆盖白色主题。
 * 页头标题/副标题仍是字面色值，与文档审核页、首页保持一致，由生成器统一翻转明度。
 */
.graph-page { position: relative; z-index: 1; width: min(1360px, calc(100% - 48px)); margin: 0 auto; padding: 38px 0 46px; }
.graph-intro { display: flex; align-items: end; justify-content: space-between; gap: 20px; margin-bottom: 24px; }
.graph-eyebrow { display: flex; align-items: center; gap: 8px; color: #67d9ae; font-size: 10px; font-weight: 700; letter-spacing: .13em; }
.graph-eyebrow span { color: #748993; font-weight: 400; letter-spacing: 0; }
.graph-intro h1 { margin: 12px 0 7px; color: #edf5f4; font-size: clamp(25px, 3vw, 33px); letter-spacing: -.04em; }
.graph-intro p { margin: 0; color: #8599a2; font-size: 12px; }
.graph-doc-link { display: inline-flex; align-items: center; gap: 9px; padding: 10px 13px; border: 1px solid #316150; border-radius: 9px; background: #17372e; color: #9ee7c7; font-size: 11px; text-decoration: none; white-space: nowrap; }
.graph-doc-link:hover { border-color: #56d5a7; background: #1b4537; }
.graph-layout { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 16px; }
.graph-canvas, .graph-details { overflow: hidden; border: 1px solid var(--kg-border); border-radius: 16px; background: var(--kg-surface); box-shadow: var(--kg-shadow); }
.graph-canvas { position: relative; min-width: 0; background: var(--kg-canvas); border-color: var(--kg-canvas-border); }
.graph-canvas-top { position: absolute; z-index: 1; top: 19px; left: 22px; display: flex; align-items: center; gap: 8px; color: var(--kg-canvas-title); font-size: 11px; font-weight: 600; }
.graph-live-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--kg-live-dot); box-shadow: 0 0 12px var(--kg-live-glow); }
.graph-canvas-demo { margin-left: 7px; color: var(--kg-canvas-meta); font-size: 9px; font-weight: 400; }
.graph-svg { display: block; width: 100%; height: auto; min-height: 420px; }
.kg-grid-dot { fill: var(--kg-grid-dot); opacity: var(--kg-grid-dot-opacity); }
.kg-grid-plane { fill: url(#graph-grid); opacity: var(--kg-grid-plane-opacity); }
.kg-halo { fill: url(#graph-halo); }
.graph-orbit { fill: none; stroke: var(--kg-orbit); stroke-opacity: .12; stroke-dasharray: 4 9; }
.graph-orbit-outer { stroke-opacity: .07; }
.graph-edge { stroke: var(--kg-edge); stroke-width: 1.5; stroke-opacity: .48; stroke-dasharray: 7 8; animation: edge-flow 18s linear infinite; }
.graph-edge-active { stroke: var(--kg-edge-active); stroke-width: 2.5; stroke-opacity: .95; }
.graph-related-edge { stroke: var(--kg-related-edge); stroke-width: 1; stroke-opacity: .25; stroke-dasharray: 3 7; }
.graph-node { cursor: pointer; }
.graph-node-pulse { fill: var(--kg-node-pulse); opacity: var(--kg-node-pulse-opacity); transform-origin: center; animation: node-breathe 4s ease-in-out infinite; }
.graph-node:nth-of-type(3n) .graph-node-pulse { animation-delay: -1.5s; }
.graph-node-circle { fill: var(--kg-node-core-bg); stroke: var(--kg-node-core-border); stroke-width: 1.4; transition: fill .2s, stroke .2s; }
.graph-node-dot { fill: var(--kg-node-dot); }
.graph-node-glare { fill: url(#graph-glare); pointer-events: none; }
.kg-glare-stop { stop-color: var(--kg-node-glare); }
.kg-halo-stop { stop-color: var(--kg-halo); stop-opacity: var(--kg-halo-opacity); }
.kg-halo-stop-mid { stop-opacity: var(--kg-halo-opacity-mid); }
.kg-halo-stop-end { stop-opacity: 0; }
.graph-node-label { fill: var(--kg-node-label); font-size: 13px; font-weight: 600; pointer-events: none; }
.graph-node-blue .graph-node-circle { fill: var(--kg-node-blue-bg); stroke: var(--kg-node-blue-border); }
.graph-node-blue .graph-node-dot { fill: var(--kg-node-dot-blue); }
.graph-node-violet .graph-node-circle { fill: var(--kg-node-violet-bg); stroke: var(--kg-node-violet-border); }
.graph-node-violet .graph-node-dot { fill: var(--kg-node-dot-violet); }
.graph-node-violet .graph-node-label { fill: var(--kg-node-label-violet); }
.graph-node-amber .graph-node-circle { fill: var(--kg-node-amber-bg); stroke: var(--kg-node-amber-border); }
.graph-node-amber .graph-node-dot { fill: var(--kg-node-dot-amber); }
.graph-node-amber .graph-node-label { fill: var(--kg-node-label-amber); }
.graph-node-center .graph-node-label { font-size: 15px; font-weight: 700; }
.graph-node-selected .graph-node-circle, .graph-node:hover .graph-node-circle { stroke-width: 3; filter: drop-shadow(0 0 10px var(--kg-node-glow)); }
.graph-node-selected .graph-node-pulse { opacity: var(--kg-node-pulse-active); }
.graph-canvas-bottom { position: absolute; right: 20px; bottom: 16px; display: flex; gap: 15px; color: var(--kg-legend); font-size: 9px; }
.graph-canvas-bottom span { display: inline-flex; align-items: center; gap: 5px; }
.graph-canvas-bottom i { width: 6px; height: 6px; border-radius: 50%; background: var(--kg-legend-core); }
.graph-canvas-bottom span:nth-child(2) i { background: var(--kg-legend-field); }
.graph-canvas-bottom span:nth-child(3) i { width: 12px; height: 2px; border-radius: 1px; background: var(--kg-legend-edge); }
.graph-details { display: flex; flex-direction: column; min-width: 0; padding: 20px; }
.graph-details-heading { display: flex; align-items: center; gap: 8px; color: var(--kg-heading); font-size: 12px; font-weight: 650; }
.graph-details-heading svg { color: var(--kg-heading-icon); }
.graph-details-heading small { margin-left: auto; padding: 3px 6px; border: 1px solid var(--kg-demo-border); border-radius: 5px; color: var(--kg-demo-text); font-size: 9px; }
.graph-node-details { margin-top: 23px; padding: 16px; border: 1px solid var(--kg-detail-border); border-radius: 12px; background: var(--kg-detail-surface); }
.graph-details-icon { width: 40px; height: 40px; display: grid; place-items: center; margin-bottom: 17px; border-radius: 11px; background: var(--kg-detail-icon-bg); color: var(--kg-detail-icon); }
.graph-details-category { color: var(--kg-detail-category); font-size: 10px; }
.graph-node-details h2 { margin: 5px 0 9px; color: var(--kg-detail-title); font-size: 19px; }
.graph-node-details p { min-height: 44px; margin: 0 0 16px; color: var(--kg-detail-text); font-size: 10px; line-height: 1.7; }
.graph-details-stat { display: flex; align-items: center; gap: 6px; padding-top: 12px; border-top: 1px solid var(--kg-detail-rule); color: var(--kg-detail-meta); font-size: 10px; }
.graph-details-stat strong { margin-left: auto; color: var(--kg-detail-value); }
.graph-topic-list h3 { margin: 24px 0 11px; color: var(--kg-topic-title); font-size: 10px; font-weight: 550; }
.graph-topic-buttons { display: flex; flex-wrap: wrap; gap: 6px; }
.graph-topic-buttons button { display: inline-flex; align-items: center; gap: 5px; padding: 7px 8px; border: 1px solid var(--kg-chip-border); border-radius: 6px; background: var(--kg-chip-bg); color: var(--kg-chip-text); font-size: 9px; }
.graph-topic-buttons button.active, .graph-topic-buttons button:hover { border-color: var(--kg-chip-active-border); background: var(--kg-chip-active-bg); color: var(--kg-chip-active-text); }
.graph-details-link { display: flex; align-items: center; gap: 8px; margin-top: auto; padding-top: 21px; color: var(--kg-link); font-size: 10px; text-decoration: none; }
.graph-details-link svg:last-child { margin-left: auto; }
.graph-page-note { margin: 14px 2px 0; color: var(--kg-note); font-size: 10px; line-height: 1.6; }
@keyframes edge-flow { to { stroke-dashoffset: -180; } }
@keyframes node-breathe { 50% { transform: scale(1.15); opacity: .15; } }
@media (max-width: 980px) {
  .graph-layout { grid-template-columns: minmax(0, 1fr); }
  .graph-details { gap: 20px; }
  .graph-node-details { margin-top: 0; }
  .graph-topic-list h3 { margin-top: 0; }
  .graph-details-link { margin-top: 0; padding-top: 0; }
}
@media (max-width: 580px) {
  .graph-page { width: calc(100% - 30px); padding-top: 25px; }
  .graph-intro { align-items: start; flex-direction: column; }
  .graph-intro h1 { font-size: 24px; }
  .graph-canvas-top { top: 12px; left: 12px; font-size: 10px; }
  .graph-canvas-demo { display: none; }
  .graph-canvas { overflow-x: auto; }
  .graph-svg { width: 720px; max-width: none; min-height: 390px; }
  .graph-canvas-bottom { right: 10px; bottom: 10px; gap: 8px; font-size: 8px; }
}
@media (prefers-reduced-motion: reduce) {
  .graph-edge, .graph-node-pulse { animation: none; }
}
</style>
