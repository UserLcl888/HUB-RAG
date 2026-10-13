/**
 * 由 style.css（深色）生成 style-light.css（白色主题）。
 *
 * 为什么用生成而不是手写：
 *   style.css 里有 450+ 处硬编码色值、只有 9 个 CSS 变量；两个视图组件（DocumentReview /
 *   KnowledgeGraph）还各自带 <style scoped>。手写浅色主题必然漏面。这里把每条规则原样复制一份、
 *   挂到 html[data-theme="white"] 下，并把其中的颜色做一次"保留色相、翻转明度"的映射，
 *   覆盖率 100%，不会留下深色斑块。
 *
 * 映射规则（kind 由属性名决定）：
 *   · 中性色（饱和度 < .18）        → 明度取反（近黑背景→近白，近白文字→近黑）
 *   · 文字色（color/fill/stroke）   → 近白文字压成近黑；彩色文字（品牌绿等）压到 L≤45% 保证浅底可读
 *   · 面/边框（background/border…） → 深色面变浅；本来就浅的面保持浅
 *   · 阴影与 filter                 → 原样保留（浅色主题里深色阴影才自然，反色会把阴影变成白光晕）
 *   · 纸面预览（.paper-*）与命中块  → 整条跳过（它本来就是"白纸黑字"）
 *   · 自带 --kg-* / --rv-* 变量的规则 → 整条跳过（知识图谱与文档审核两页手工维护浅色色板，
 *                                    见 style-light-tweaks.css；机械翻转只会做出浅底浅字）
 *
 * 用法：node scripts/build-light-theme.mjs
 */
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import postcss from 'postcss'

const srcDir = fileURLToPath(new URL('../src/', import.meta.url))
const srcPath = `${srcDir}style.css`
const outPath = `${srcDir}style-light.css`

const SKIP_SELECTORS = new Set(['html', 'body', ':root'])
const SKIP_PREFIXES = [
  '.paper-preview', '.paper-kicker', '.paper-rule', '.paper-lines', '.paper-page',
  '.preview-chunk', '.preview-hit',
]
/**
 * 自带主题变量的视图：这些规则的颜色由 --kg-* / --rv-* 提供，深色值写在组件里、
 * 白色值写在 style-light-tweaks.css。生成器必须原样放过，否则"翻转明度"的结果
 * 会以更高或相同的优先级盖掉手工调好的浅色色板（浅底浅字、发灰面板就是这么来的）。
 */
const TOKEN_PREFIXES = ['--kg-', '--rv-']
const TOKEN_VAR = new RegExp(`var\\(\\s*(?:${TOKEN_PREFIXES.map(p => p.replace(/-/g, '\\-')).join('|')})`)
/** 变量定义块（如 .graph-page { --kg-border: … }）本身也要整条保留 */
function definesTokens(rule) {
  let found = false
  rule.walkDecls(decl => {
    if (TOKEN_PREFIXES.some(prefix => decl.prop.startsWith(prefix))) found = true
  })
  return found
}
/** 该规则的颜色是否已经由主题变量提供 */
function usesTokens(rule) {
  let found = false
  rule.walkDecls(decl => {
    if (TOKEN_VAR.test(decl.value)) found = true
  })
  return found
}

const HEADER = `/*
 * 白色主题（由 scripts/build-light-theme.mjs 从 style.css 与各组件的 <style> 生成，请勿手改）。
 * 重新生成：node scripts/build-light-theme.mjs
 */
html{color-scheme:dark}
html[data-theme="white"]{
  color-scheme:light;
  --bg:#f5f8fb;
  --panel:#ffffff;
  --panel-2:#f2f6f9;
  --line:rgba(24,48,66,.10);
  --muted:#5d7183;
  --subtle:#8b9ba7;
  --mint:#12a97c;
  --mint-dark:#0c8a63;
  --white:#13212a;
}
html[data-theme="white"] body{
  color:#16232b;
  background:#f6f8fa;
}
/* 滚动条：深色下用细条，白色主题换成浅灰细条，避免出现系统默认的亮色粗条 */
html[data-theme="white"] ::-webkit-scrollbar{width:8px;height:8px}
html[data-theme="white"] ::-webkit-scrollbar-track{background:transparent}
html[data-theme="white"] ::-webkit-scrollbar-thumb{background:#c6d1d8;border-radius:8px}
html[data-theme="white"] ::-webkit-scrollbar-thumb:hover{background:#aab8c1}
html[data-theme="white"] *{scrollbar-color:#c6d1d8 transparent}
/* 侧边栏"历史对话"列表在白色主题下同样不显示滚动条（否则会被上面的 * 规则重新加上细条） */
html[data-theme="white"] .chat-session-list{scrollbar-width:none}
html[data-theme="white"] .chat-session-list::-webkit-scrollbar{width:0;height:0}
`

function kindOf(prop) {
  if (/shadow|filter/.test(prop)) return 'keep'
  if (prop === 'color' || /^(fill|stroke|caret-color|text-decoration-color|text-emphasis-color|accent-color)$/.test(prop)) return 'text'
  return 'surface'
}

function rgbToHsl(r, g, b) {
  const rn = r / 255, gn = g / 255, bn = b / 255
  const max = Math.max(rn, gn, bn), min = Math.min(rn, gn, bn)
  const l = (max + min) / 2
  if (max === min) return [0, 0, l]
  const d = max - min
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  let h
  if (max === rn) h = ((gn - bn) / d + (gn < bn ? 6 : 0)) / 6
  else if (max === gn) h = ((bn - rn) / d + 2) / 6
  else h = ((rn - gn) / d + 4) / 6
  return [h * 360, s, l]
}

function hslToRgb(h, s, l) {
  const c = (1 - Math.abs(2 * l - 1)) * s
  const hp = ((((h % 360) + 360) % 360) / 60)
  const x = c * (1 - Math.abs((hp % 2) - 1))
  let rgb
  if (hp < 1) rgb = [c, x, 0]
  else if (hp < 2) rgb = [x, c, 0]
  else if (hp < 3) rgb = [0, c, x]
  else if (hp < 4) rgb = [0, x, c]
  else if (hp < 5) rgb = [x, 0, c]
  else rgb = [c, 0, x]
  const m = l - c / 2
  return rgb.map(v => Math.max(0, Math.min(255, Math.round((v + m) * 255))))
}

function parseColor(token) {
  let m = /^#([0-9a-fA-F]{3,8})$/.exec(token)
  if (m) {
    const raw = m[1]
    const expand = raw.length <= 4 ? raw.split('').map(c => c + c).join('') : raw
    if (expand.length !== 6 && expand.length !== 8) return null
    return {
      r: parseInt(expand.slice(0, 2), 16),
      g: parseInt(expand.slice(2, 4), 16),
      b: parseInt(expand.slice(4, 6), 16),
      a: expand.length === 8 ? parseInt(expand.slice(6, 8), 16) / 255 : 1,
    }
  }
  m = /^rgba?\(([^)]+)\)$/.exec(token)
  if (m) {
    const parts = m[1].split(/[,/\s]+/).map(p => p.trim()).filter(Boolean)
    if (parts.length < 3) return null
    const channel = p => (p.endsWith('%') ? Math.round(parseFloat(p) * 2.55) : parseFloat(p))
    const [r, g, b] = parts.slice(0, 3).map(channel)
    let a = 1
    if (parts[3] !== undefined) {
      a = parts[3].endsWith('%') ? parseFloat(parts[3]) / 100 : parseFloat(parts[3])
    }
    if ([r, g, b].some(v => Number.isNaN(v))) return null
    return { r, g, b, a: Number.isNaN(a) ? 1 : a }
  }
  return null
}

function formatColor({ r, g, b, a }) {
  const hex = n => n.toString(16).padStart(2, '0')
  return a >= 0.999
    ? `#${hex(r)}${hex(g)}${hex(b)}`
    : `rgba(${r}, ${g}, ${b}, ${Math.round(a * 1000) / 1000})`
}

function transform(color, kind) {
  const [h, s, l] = rgbToHsl(color.r, color.g, color.b)
  let out
  if (kind === 'keep') {
    out = [h, s, l]
  } else if (s < 0.18) {
    out = [h, Math.round(s * 85) / 100, 1 - l]
  } else if (kind === 'text') {
    out = l >= 0.75
      ? [h, Math.round(s * 35) / 100, Math.min(0.22, 1 - l)]
      : [h, Math.min(s, 0.8), Math.min(l, 0.45)]
  } else {
    // 面 / 边框
    if (l < 0.30) {
      // 深色面（面板、深色底、深色描边）→ 变浅
      out = [h, Math.round(s * 85) / 100, 1 - l * 0.82]
    } else if (s >= 0.45) {
      // 饱和的中间调 = 品牌强调色（主按钮、图标底、选中态）。
      // 这类颜色在两套主题里都成立，早先"一律提亮"会把按钮洗成淡薄荷、文字发灰，这里保持原样。
      out = [h, s, l]
    } else {
      out = [h, Math.round(s * 85) / 100, 1 - l * 0.82]
    }
  }
  const [r, g, b] = hslToRgb(out[0], out[1], out[2])
  return { r, g, b, a: color.a }
}

const COLOR_TOKEN = /#[0-9a-fA-F]{3,8}\b|rgba?\([^)]*\)/g
const namedColors = new Set()

function remapValue(value, kind) {
  if (kind !== 'keep') {
    for (const m of value.matchAll(/\b(white|black|gray|grey|silver)\b/gi)) namedColors.add(m[1])
  }
  if (kind === 'keep') return value
  return value.replace(COLOR_TOKEN, token => {
    const parsed = parseColor(token)
    return parsed ? formatColor(transform(parsed, kind)) : token
  })
}

/** 把一棵 CSS 树整体转成白色主题版本（选择器加前缀、颜色重映射） */
function toLightTree(css, from) {
  const tree = postcss.parse(css, { from }).clone()
  tree.walkAtRules(at => {
    if (/keyframes|font-face|charset|import/i.test(at.name)) at.remove()
  })
  tree.walkRules(rule => {
    const selectors = rule.selectors.map(s => s.trim())
    // 幂等守卫：已经是白色主题前缀的规则（例如上一轮生成物被误留）不要再套一层
    if (!selectors.length
      || selectors.some(sel => sel.includes('[data-theme=')
        || SKIP_SELECTORS.has(sel)
        || SKIP_PREFIXES.some(p => sel.startsWith(p)))) {
      rule.remove()
      return
    }
    // 颜色已由 --kg-* / --rv-* 变量给出（含定义这些变量的规则）：原样保留，交给手工浅色色板
    if (definesTokens(rule) || usesTokens(rule)) return
    rule.selector = selectors.map(sel => `html[data-theme="white"] ${sel}`).join(',')
    rule.walkDecls(decl => {
      if (decl.prop.startsWith('--')) return
      decl.value = remapValue(decl.value, kindOf(decl.prop))
    })
  })
  tree.walkAtRules(at => {
    if (at.nodes && at.nodes.length === 0) at.remove()
  })
  return tree
}

/** 收集所有 .vue 的 <style> 块（这两个视图的样式是 scoped，但选择器都是普通类名，前缀后仍能命中） */
function collectVueStyles(dir) {
  const blocks = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = `${dir}${entry.name}`
    if (entry.isDirectory()) {
      blocks.push(...collectVueStyles(`${full}/`))
    } else if (entry.name.endsWith('.vue')) {
      const text = readFileSync(full, 'utf8')
      for (const m of text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)) {
        blocks.push({ from: full, css: m[1] })
      }
    }
  }
  return blocks
}

const lightRoot = postcss.root()
lightRoot.append(postcss.parse(HEADER))
lightRoot.append(toLightTree(readFileSync(srcPath, 'utf8'), srcPath))

const vueBlocks = collectVueStyles(srcDir)
for (const block of vueBlocks) {
  lightRoot.append(toLightTree(block.css, block.from))
}

const output = `${lightRoot.toString()}\n`
writeFileSync(outPath, output, 'utf8')

const rules = []
lightRoot.walkRules(() => rules.push(1))
console.log(`style-light.css 已生成：${(output.length / 1024).toFixed(1)} KB，规则 ${rules.length} 条，来源：style.css + ${vueBlocks.length} 个组件样式块`)
if (namedColors.size) console.log('未参与映射的命名颜色（如 mask-image 用的 black，属预期）→', [...namedColors].join(', '))
