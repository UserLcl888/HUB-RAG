/**
 * 一次性脚本：全站字号放大。
 *
 * 做三件事：
 *   1. 所有 `font-size: Npx` × SCALE
 *   2. 过小的间距（gap / padding 里 ≤10px 的值）× SCALE —— 字号放大后 6~9px 的空隙会显得挤
 *   3. 行高：px 行高 × SCALE；无单位行高（1.6 之类）本来就随字号走，不动
 *
 * 为什么写脚本而不是手改：186 处 font-size，大量写在单行内联格式里（`a{...}` 整条一行），
 * 手改必漏。脚本按属性名精确匹配，不会碰到同样以 px 结尾的 width/height/border-radius。
 *
 * 用法：node scripts/scale-fonts.mjs [--scale=1.25] [--dry]
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const SCALE = Number((process.argv.find(a => a.startsWith('--scale=')) ?? '--scale=1.25').split('=')[1])
const DRY = process.argv.includes('--dry')
/** 只放大字号，不动 gap / padding / px 行高（第二次加大字号时用，避免间距被重复放大） */
const FONTS_ONLY = process.argv.includes('--fonts-only')
const srcDir = fileURLToPath(new URL('../src/', import.meta.url))

/** 只改字号真正所在的三个文件（App.vue / MessageBody.vue 的样式全在 style.css 里） */
const FILES = ['style.css', 'views/KnowledgeGraph.vue', 'views/DocumentReview.vue']

/** 1.25 会算出 11.25px 这种值；保留一位小数，整数就不带 .0 */
const fmt = n => {
  const rounded = Math.round(n * 100) / 100
  return Number.isInteger(rounded) ? String(rounded) : String(rounded)
}

const stats = { fontSize: 0, gap: 0, padding: 0, lineHeight: 0, minFont: Infinity, maxFont: 0 }

/** font-size: 12.5px → 15.63px；clamp(25px, 3vw, 33px) 的三个值一起放大 */
function scaleFontSize(value) {
  let changed = false
  const out = value.replace(/(-?\d*\.?\d+)px/g, (match, num) => {
    changed = true
    const scaled = fmt(Number(num) * SCALE)
    stats.fontSize += 1
    return `${scaled}px`
  }).replace(/(-?\d*\.?\d+)vw/g, (match, num) => {
    changed = true
    return `${fmt(Number(num) * SCALE)}vw`
  })
  return changed ? out : null
}

/** gap / row-gap / column-gap：≤10px 的值才放大（大间距多是版面留白，放大反而松） */
function scaleGap(value) {
  let changed = false
  const out = value.replace(/(-?\d*\.?\d+)px/g, (match, num) => {
    const n = Number(num)
    if (n < 0 || n > 10) return match
    changed = true
    stats.gap += 1
    return `${fmt(n * SCALE)}px`
  })
  return changed ? out : null
}

/** padding：只放大 ≤10px 的段（`0 9px`、`4px 7px` 这种紧凑内边距） */
function scalePadding(value) {
  let changed = false
  const out = value.replace(/(-?\d*\.?\d+)px/g, (match, num) => {
    const n = Number(num)
    if (n < 0 || n > 10) return match
    changed = true
    stats.padding += 1
    return `${fmt(n * SCALE)}px`
  })
  return changed ? out : null
}

function scaleLineHeight(value) {
  const m = /^(\d*\.?\d+)px$/.exec(value.trim())
  if (!m) return null
  stats.lineHeight += 1
  return `${fmt(Number(m[1]) * SCALE)}px`
}

for (const relative of FILES) {
  const path = `${srcDir}${relative}`
  const before = readFileSync(path, 'utf8')
  let after = before
    // 只匹配声明体里的 `属性: 值`，值里不含 ; 和 }
    .replace(/(font-size\s*:\s*)([^;}]+)/g, (whole, head, value) => {
      const scaled = scaleFontSize(value.trim())
      return scaled ? `${head}${scaled}` : whole
    })
  if (!FONTS_ONLY) {
    after = after
      .replace(/((?:^|[{;\s])(?:row-|column-)?gap\s*:\s*)([^;}]+)/g, (whole, head, value) => {
        const scaled = scaleGap(value.trim())
        return scaled ? `${head}${scaled}` : whole
      })
      .replace(/((?:^|[{;\s])padding\s*:\s*)([^;}]+)/g, (whole, head, value) => {
        const scaled = scalePadding(value.trim())
        return scaled ? `${head}${scaled}` : whole
      })
      .replace(/((?:^|[{;\s])line-height\s*:\s*)([^;}]+)/g, (whole, head, value) => {
        const scaled = scaleLineHeight(value.trim())
        return scaled ? `${head}${scaled}` : whole
      })
  }

  // 统计放大后的字号区间，便于核对
  for (const m of after.matchAll(/font-size\s*:\s*(\d*\.?\d+)px/g)) {
    const n = Number(m[1])
    if (n < stats.minFont) stats.minFont = n
    if (n > stats.maxFont) stats.maxFont = n
  }

  if (DRY) {
    console.log(`[dry] ${relative}: ${before.length} → ${after.length} 字符`)
    continue
  }
  writeFileSync(path, after, 'utf8')
  console.log(`${relative}: 已写入（${before.length} → ${after.length} 字符）`)
}

console.log(`\n放大倍率 ${SCALE}`)
console.log(`  font-size 值 ${stats.fontSize} 个，放大后区间 ${stats.minFont} ~ ${stats.maxFont}px`)
console.log(`  gap 值 ${stats.gap} 个、padding 段 ${stats.padding} 个、px 行高 ${stats.lineHeight} 个`)
