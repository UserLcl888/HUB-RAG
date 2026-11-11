/**
 * 校验「知识图谱 / 文档审核」白色主题下关键配色的对比度。
 *
 * 为什么需要：这两页的浅色色板是手工定的（见 src/style-light-tweaks.css），
 * 底与字是"成对"设计的——只改其中一边很容易做出浅底浅字，而这类问题在深色主题
 * 下完全看不出来。这里把每组配色的最低对比度固定成断言，改坏就报错。
 *
 * 标准：正文/小字 >= 4.5:1（WCAG AA）；纯图形元素（描边、得分环）>= 3:1（WCAG 1.4.11）。
 * 取值直接读 CSS 变量，不重复维护一份色值。
 *
 * 用法：node scripts/check-theme-contrast.mjs
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const srcDir = fileURLToPath(new URL('../src/', import.meta.url))
const tweaks = readFileSync(`${srcDir}style-light-tweaks.css`, 'utf8')

function readPalette(selector) {
  const start = tweaks.indexOf(selector)
  if (start < 0) throw new Error(`style-light-tweaks.css 里找不到色板：${selector}`)
  const open = tweaks.indexOf('{', start)
  const body = tweaks.slice(open + 1, tweaks.indexOf('}', open))
  const palette = new Map()
  for (const m of body.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)) palette.set(m[1], m[2].trim())
  return palette
}

const white = { ...Object.fromEntries(readPalette('html[data-theme="white"] .graph-page')) }
Object.assign(white, Object.fromEntries(readPalette('html[data-theme="white"] .review-page')))

const hexToRgb = token => {
  const m = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.exec(token)
  if (!m) return null
  // #abc 与 #aabbcc 都支持：色板里两种写法都在用
  const raw = m[1].length === 3 ? m[1].split('').map(c => c + c).join('') : m[1]
  return [0, 2, 4].map(i => parseInt(raw.slice(i, i + 2), 16) / 255)
}
const luminance = token => {
  const rgb = hexToRgb(token)
  if (!rgb) throw new Error(`暂不支持的颜色写法（仅支持 hex）：${token}`)
  const [r, g, b] = rgb.map(c => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
const contrast = (fg, bg) => {
  const [hi, lo] = [luminance(fg), luminance(bg)].sort((a, b) => b - a)
  return (hi + 0.05) / (lo + 0.05)
}

/** [说明, 前景变量, 背景变量, 最低对比度] —— 前景是"字"，背景是它实际压着的那个面 */
const TEXT_PAIRS = [
  ['图谱 节点标签 / 核心节点底', '--kg-node-label', '--kg-node-core-bg', 4.5],
  ['图谱 节点标签 / 蓝色节点底', '--kg-node-label', '--kg-node-blue-bg', 4.5],
  ['图谱 节点标签 / 紫色节点底', '--kg-node-label-violet', '--kg-node-violet-bg', 4.5],
  ['图谱 节点标签 / 琥珀节点底', '--kg-node-label-amber', '--kg-node-amber-bg', 4.5],
  ['图谱 画布副标题 / 画布', '--kg-canvas-meta', '--kg-canvas:base', 4.5],
  ['图谱 图例 / 画布', '--kg-legend', '--kg-canvas:base', 4.5],
  ['图谱 详情正文 / 详情面', '--kg-detail-text', '--kg-detail-surface:base', 4.5],
  ['图谱 详情分类 / 详情面', '--kg-detail-category', '--kg-detail-surface:base', 4.5],
  ['图谱 主题按钮 / 按钮面', '--kg-chip-text', '--kg-chip-bg', 4.5],
  ['图谱 页脚说明 / 页面底', '--kg-note', '--kg-page-bg', 4.5],
  ['审核 项正文 / 卡片', '--rv-item-text', '--rv-card-bg', 4.5],
  ['审核 项标题 / 卡片', '--rv-item-title', '--rv-card-bg', 4.5],
  ['审核 已通过 / 卡片', '--rv-status-pass', '--rv-card-bg', 4.5],
  ['审核 待确认 / 卡片', '--rv-status-warn', '--rv-card-bg', 4.5],
  ['审核 栏目名 / 页面底', '--rv-accent', '--rv-page-bg', 4.5],
  ['审核 小标签 / 卡片面', '--rv-label', '--rv-surface', 4.5],
  ['审核 文件信息 / 卡片面', '--rv-file-meta', '--rv-surface', 4.5],
  ['审核 表格脚注 / 卡片面', '--rv-foot', '--rv-surface', 4.5],
  ['审核 建议正文 / 卡片面', '--rv-advice-text', '--rv-surface', 4.5],
  ['审核 得分数字 / 卡片面', '--rv-ring-score', '--rv-surface', 4.5],
  ['审核 激活标签 / 激活底', '--rv-tab-active-text', '--rv-tab-active-bg', 4.5],
]

/** 纯图形元素：WCAG 1.4.11 只要求 3:1 */
const GRAPHIC_PAIRS = [
  ['图谱 节点静息描边 / 画布', '--kg-node-core-border', '--kg-canvas:base', 3],
  ['图谱 节点选中描边 / 画布', '--kg-node-ring-active', '--kg-canvas:base', 3],
  ['图谱 主题连线 / 画布', '--kg-edge-active', '--kg-canvas:base', 3],
  ['审核 得分环 / 卡片面', '--rv-ring-border', '--rv-surface', 3],
]

/**
 * 渐变取首色当"面"来算：两页的渐变都是同色系微渐变，
 * 首色与末色明度差很小，取首色不会影响结论。
 */
function resolve(name) {
  if (name === '--kg-page-bg' || name === '--rv-page-bg') return '#f6f8fa'
  const base = name.endsWith(':base')
  const key = base ? name.replace(':base', '') : name
  const value = white[key]
  if (value === undefined) throw new Error(`色板里没有 ${key}`)
  if (!base) return value
  const gradient = /^linear-gradient\([^,]+,\s*(#[0-9a-fA-F]{6})/.exec(value)
  if (!gradient) throw new Error(`${key} 不是可解析的渐变：${value}`)
  return gradient[1]
}

let failed = 0
const check = (label, fgName, bgName, min) => {
  const fg = resolve(fgName)
  const bg = resolve(bgName)
  const ratio = contrast(fg, bg)
  const ok = ratio >= min
  if (!ok) failed++
  console.log(`${ok ? '✓' : '✗'} ${ratio.toFixed(2).padStart(5)}:1 (要求 ${min})  ${label}`)
}

console.log('正文 / 小字（WCAG AA >= 4.5:1）')
for (const [label, fg, bg, min] of TEXT_PAIRS) check(label, fg, bg, min)
console.log('\n图形元素（WCAG 1.4.11 >= 3:1）')
for (const [label, fg, bg, min] of GRAPHIC_PAIRS) check(label, fg, bg, min)

if (failed) {
  console.error(`\n白色主题对比度校验未通过：${failed} 组低于阈值。`)
  process.exit(1)
}
console.log('\n白色主题对比度校验通过。')
