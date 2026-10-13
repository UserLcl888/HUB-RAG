/**
 * 校验知识图谱 / 文档审核两页的主题色板是否成套。
 *
 * 这两页的颜色全部是 --kg-* / --rv-* 变量，深色与白色两套取值都在
 * src/style-light-tweaks.css。任何"只在一边定义了变量""组件引用了没定义的变量"
 * 都会让其中一套主题下取不到值（表现为颜色掉回继承值或串色），而且这类问题
 * 在上线前很难被发现——所以做成一条可执行的检查。
 *
 * 用法：node scripts/check-theme-tokens.mjs
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const srcDir = fileURLToPath(new URL('../src/', import.meta.url))
const tweaks = readFileSync(`${srcDir}style-light-tweaks.css`, 'utf8')

const VIEWS = [
  { key: 'graph', file: 'KnowledgeGraph.vue' },
  { key: 'review', file: 'DocumentReview.vue' },
]

function readPalette(selector) {
  const start = tweaks.indexOf(selector)
  if (start < 0) throw new Error(`style-light-tweaks.css 里找不到色板：${selector}`)
  const open = tweaks.indexOf('{', start)
  const close = tweaks.indexOf('}', open)
  const body = tweaks.slice(open + 1, close)
  return new Set([...body.matchAll(/(--[a-z0-9-]+)\s*:/g)].map(m => m[1]))
}

let failed = false
const fail = message => {
  failed = true
  console.error(`✗ ${message}`)
}

for (const view of VIEWS) {
  const text = readFileSync(`${srcDir}views/${view.file}`, 'utf8')
  const styleStart = text.indexOf('<style')
  if (styleStart < 0) {
    fail(`${view.file} 没有 <style> 块`)
    continue
  }
  const style = text.slice(styleStart)
  const used = new Set([...style.matchAll(/var\((--[a-z0-9-]+)/g)].map(m => m[1]))
  const dark = readPalette(`html:not([data-theme="white"]) .${view.key === 'graph' ? 'graph' : 'review'}-page`)
  const white = readPalette(`html[data-theme="white"] .${view.key === 'graph' ? 'graph' : 'review'}-page`)

  // 组件里不应再有变量定义：那会被打包成全局样式，进而盖掉白色主题的色板
  const localDefs = [...style.matchAll(/(--[a-z0-9-]+)\s*:/g)].map(m => m[1])
  if (localDefs.length) fail(`${view.file} 组件内仍有变量定义：${localDefs.join(', ')}`)

  for (const name of used) {
    if (!dark.has(name)) fail(`${view.file} 引用的 ${name} 在深色色板里没有定义`)
    if (!white.has(name)) fail(`${view.file} 引用的 ${name} 在白色色板里没有定义`)
  }
  for (const name of dark) if (!used.has(name)) fail(`${name} 在深色色板里多余（组件未引用）`)
  for (const name of white) if (!used.has(name)) fail(`${name} 在白色色板里多余（组件未引用）`)
  for (const name of dark) if (!white.has(name)) fail(`${name} 缺少白色主题取值`)
  for (const name of white) if (!dark.has(name)) fail(`${name} 缺少深色主题取值`)

  console.log(`· ${view.file}：引用 ${used.size} 个变量，深色/白色色板各 ${dark.size} / ${white.size} 个`)
}

if (failed) {
  console.error('主题色板校验未通过。')
  process.exit(1)
}
console.log('主题色板校验通过：两页的深色与白色取值一一对应。')
