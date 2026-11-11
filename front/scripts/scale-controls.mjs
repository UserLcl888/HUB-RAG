/**
 * 字号放大（1.25×）之后的配套改动：把"装文字的控件"高度一起提上去。
 *
 * 为什么要单独一步：字号变了，行高跟着变（.session-menu-item 的文字行高已 32.5px），
 * 但 height 还是原来的 26px，文字会被裁掉；搜索框、按钮同理。
 * 只调有文字/占位的控件，纯图形块（头像、图标底、色点）一律不动。
 *
 * 用法：node scripts/scale-controls.mjs [--dry]
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const DRY = process.argv.includes('--dry')
const path = `${fileURLToPath(new URL('../src/', import.meta.url))}style.css`
let css = readFileSync(path, 'utf8')

/** [规则选择器, 原 height, 新 height] —— 选择器必须与文件里完全一致 */
const TARGETS = [
  ['.new-chat-button', 40, 44],
  ['.chat-session-more', 24, 26],
  ['.search-box', 58, 60],
  ['.library-search', 40, 44],
  ['.filter-button,.sort-select', 40, 44],
  ['.view-toggle', 27, 28],
  ['.library-bottom button', 29, 32],
  ['.theme-toggle', 29, 32],
  ['.composer-footer button', 29, 32],
]

let changed = 0
for (const [selector, from, to] of TARGETS) {
  // 定位整条规则，只替换规则内的 height 值，避免误伤别处的同名属性
  const ruleRe = new RegExp(`(${selector.replace(/[.,]/g, '\\$&')}\\{[^}]*?height:)${from}px`)
  if (!ruleRe.test(css)) {
    console.warn(`⚠ 没找到 ${selector}{height:${from}px}，跳过`)
    continue
  }
  css = css.replace(ruleRe, `$1${to}px`)
  changed += 1
  console.log(`${selector}: height ${from}px → ${to}px`)
}

// 移动端（≤580px）里这些控件有单独覆盖的高度，一起跟上
const MOBILE = [
  ['.main-nav button', 34, 36],
  ['.search-box', 53, 56],
  ['.send-button', 37, 38],
  ['.mode-button', 34, 36],
]
for (const [selector, from, to] of MOBILE) {
  const ruleRe = new RegExp(`(${selector.replace(/[.,]/g, '\\$&')}\\{[^}]*?height:)${from}px`)
  if (!ruleRe.test(css)) {
    console.warn(`⚠ 移动端没找到 ${selector}{height:${from}px}，跳过`)
    continue
  }
  css = css.replace(ruleRe, `$1${to}px`)
  changed += 1
  console.log(`(≤580px) ${selector}: height ${from}px → ${to}px`)
}

if (DRY) {
  console.log(`\n[dry] 将修改 ${changed} 条规则`)
} else {
  writeFileSync(path, css, 'utf8')
  console.log(`\n已修改 ${changed} 条规则`)
}
