import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 旧 WebView 的 CSS 兼容门禁。
 *
 * 背景（真机缺陷）：`background: color-mix(in srgb, var(--card-color) var(--card-opacity), transparent)`
 * 这类声明在旧引擎上**不会**在解析阶段被丢弃——值里有 `var()`，要等到计算值阶段才判定
 * 无效（invalid at computed-value time）。该声明此时仍然赢下级联，效果等同 `unset`：
 * 背景变透明、`box-shadow` 变 `none`。表现就是「语言引导里没有继续按钮」（白字透明底）。
 *
 * 唯一有效的修法：把每条 `color-mix()` 声明关进 `@supports (color: color-mix(...))`，
 * 让它在旧引擎里压根不存在，原位那条纯色声明才会生效。这个文件把修法钉成两条：
 *   1. 任何带 `color-mix(` 的声明都必须位于 color-mix 的 `@supports` 块内；
 *   2. 该块里的选择器必须在同一文件里有同属性、且**不含** `color-mix` 的兜底声明。
 *
 * 放在 vitest 而不是 `scripts/*.test.mjs`：样式是前端资产，改样式的人跑的就是前端门禁。
 */
const ROOT = join(process.cwd(), 'src')
const FEATURE = 'color-mix(in srgb, red, blue)'

/** `src` 下所有 `.css`（含组件子目录）。 */
function styleFiles(dir: string, collected: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry)
    if (statSync(path).isDirectory()) {
      if (entry !== 'node_modules') styleFiles(path, collected)
      continue
    }
    if (entry.endsWith('.css')) collected.push(path)
  }
  return collected
}

interface Rule {
  /** 规范化空白后的选择器；`@supports` 里的规则记录它原本的选择器文本。 */
  selector: string
  declarations: string[]
  inColorMixSupports: boolean
}

const normalize = (text: string): string => text.replace(/\s+/g, ' ').trim()

/**
 * 极简 CSS 扫描：维护一个块栈，只回答「这条声明属于哪条规则、上层有没有 color-mix 的
 * `@supports`」。仓库里的样式是扁平规则 + `@media` / `@supports`，够用且无依赖。
 */
function scanRules(css: string): Rule[] {
  const source = css.replace(/\/\*[\s\S]*?\*\//g, '')
  const rules: Rule[] = []
  const stack: { atRule: boolean; colorMixSupports: boolean; selector: string; bodyStart: number }[] = []
  let start = 0
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index]
    if (char === '{') {
      const prelude = normalize(source.slice(start, index))
      const atRule = prelude.startsWith('@')
      const colorMixSupports = atRule
        && prelude.startsWith('@supports')
        && prelude.includes(FEATURE)
        && !prelude.startsWith('@supports not')
      stack.push({ atRule, colorMixSupports, selector: prelude, bodyStart: index + 1 })
      start = index + 1
    } else if (char === '}') {
      const frame = stack.pop()
      // 选择器在 `{` 那一刻才完整：`start` 到 `}` 之间是声明体，不是选择器。
      if (frame !== undefined && !frame.atRule) {
        const body = source.slice(frame.bodyStart, index)
        rules.push({
          selector: frame.selector,
          declarations: body.split(';').map(part => part.trim()).filter(part => part !== ''),
          inColorMixSupports: stack.some(item => item.colorMixSupports),
        })
      }
      start = index + 1
    }
  }
  return rules
}

/** 取声明的属性名（`background` / `border-color` / `box-shadow` …）。 */
function propertyOf(declaration: string): string {
  const colon = declaration.indexOf(':')
  return colon === -1 ? declaration : declaration.slice(0, colon).trim()
}

const relative = (file: string): string => file.slice(ROOT.length + 1).replace(/\\/g, '/')

describe('旧 WebView 的 CSS 兼容兜底', () => {
  const files = styleFiles(ROOT)

  it('样式文件都能被扫到', () => {
    expect(files.length).toBeGreaterThan(5)
  })

  it('所有 color-mix() 声明都关在 @supports 里', () => {
    const offenders: string[] = []
    for (const file of files) {
      for (const rule of scanRules(readFileSync(file, 'utf8'))) {
        if (rule.inColorMixSupports) continue
        for (const declaration of rule.declarations) {
          if (declaration.includes('color-mix(')) offenders.push(`${relative(file)} → ${declaration}`)
        }
      }
    }
    // 漏一条就够让旧机上的那个控件变透明，所以要求一条都不剩。
    expect(offenders).toEqual([])
  })

  it('@supports 里的每条声明都有同选择器、同属性的纯色兜底', () => {
    const missing: string[] = []
    for (const file of files) {
      const rules = scanRules(readFileSync(file, 'utf8'))
      const base = rules.filter(rule => !rule.inColorMixSupports)
      for (const rule of rules) {
        if (!rule.inColorMixSupports) continue
        for (const declaration of rule.declarations.filter(item => item.includes('color-mix('))) {
          const property = propertyOf(declaration)
          const fallback = base.some(item => item.selector === rule.selector
            && item.declarations.some(line => propertyOf(line) === property && !line.includes('color-mix(')))
          if (!fallback) missing.push(`${relative(file)} → ${rule.selector} { ${property} }`)
        }
      }
    }
    expect(missing).toEqual([])
  })

  it('样式文件里的花括号必须是平衡的', () => {
    /*
     * 少一个 `}` 不会让构建失败：浏览器从那一行起丢弃整份样式表的剩余部分，
     * 而界面上的表现只是「某些页面忽然没了样式」——真机上极难和缓存问题区分开。
     * 这次给 color-mix 加 @supports 时就踩过：`/* 注释 *\/ @media …` 被当成普通规则，
     * 四个媒体查询的闭合括号一起丢了。
     */
    const unbalanced: string[] = []
    for (const file of files) {
      const source = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
      let depth = 0
      let brokeAtLine = 0
      let line = 1
      for (const char of source) {
        if (char === '\n') line += 1
        else if (char === '{') depth += 1
        else if (char === '}') {
          depth -= 1
          if (depth < 0 && brokeAtLine === 0) brokeAtLine = line
        }
      }
      if (depth !== 0) unbalanced.push(`${relative(file)} → 多出 ${depth} 个 {（第一个多余 } 在第 ${brokeAtLine} 行）`)
    }
    expect(unbalanced).toEqual([])
  })

  it('扫描器没有把整个文件误判成 @supports 内部', () => {
    let inside = 0
    let outside = 0
    for (const file of files) {
      for (const rule of scanRules(readFileSync(file, 'utf8'))) {
        const count = rule.declarations.filter(declaration => declaration.includes('color-mix(')).length
        if (rule.inColorMixSupports) inside += count
        else outside += count
      }
    }
    expect(inside).toBeGreaterThan(0)
    expect(outside).toBe(0)
  })
})
