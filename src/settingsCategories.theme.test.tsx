import { render, screen, waitFor, within } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 设置分类卡片的取色必须跟着用户选的配色走。
 *
 * 为什么要有这一组：用户报过「主题卡片记得适配全局，之前设置分类的卡片和副屏都没有主题色」。
 * 设置首页顶部那排分类芯片（「全部分类 / 模型连接 / 外观与显示…」）原来写死
 * `background: var(--surface)`，而同屏的入口卡片走的是 `--card-*` 令牌
 * （用户的卡片颜色/不透明度/模糊，由 `src/appearance.ts` 写在根元素上）。
 * 两者不同源，于是用户改了卡片配色之后：卡片换了色，芯片还是老底色。
 *
 * 为什么断言打在样式表文本上：jsdom 既不加载外部样式表，也不计算自定义属性，
 * `getComputedStyle` 拿不到 `color-mix()` 的结果。所以这里做两件互补的事：
 *  1. 解析 `src/styles.css`：芯片规则引用的令牌在深浅两套主题里都有值，
 *     换成用户卡片配色后取值随之变化，且对文字/底色算 WCAG 对比度；
 *  2. 渲染真实 `<App />`：确认首页那排芯片正是被 `.settings-categories button` 命中的元素。
 * 合起来就是「切换 `data-theme` / 卡片令牌后，分类芯片的颜色随之变化」，
 * 而且不会因为 jsdom 能力缺失而变成永远为真的假绿。
 */

const STYLESHEET = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')
const CHIP_SELECTOR = '.settings-categories button'
const LIGHT_BLOCK = ':root'
const DARK_BLOCK = ":root[data-theme='dark']"

/** 取出一条规则的声明体。选择器要写全（含 `{`），这样 `button` 与 `button[aria-selected]` 不会互相命中。 */
function ruleBody(selector: string): string {
  const head = `${selector} {`
  const start = STYLESHEET.indexOf(head)
  if (start < 0) throw new Error(`src/styles.css 里找不到规则：${selector}`)
  const end = STYLESHEET.indexOf('}', start)
  if (end < 0) throw new Error(`规则没有闭合：${selector}`)
  return STYLESHEET.slice(start + head.length, end)
}

/** 把声明体拆成「属性 → 值」。注释要先摘掉：注释可以出现在两条声明之间，留着会把下一行的属性名糊住。 */
function declarations(body: string): Map<string, string> {
  const map = new Map<string, string>()
  for (const chunk of body.replace(/\/\*[\s\S]*?\*\//g, '').split(';')) {
    const at = chunk.indexOf(':')
    if (at < 0) continue
    map.set(chunk.slice(0, at).trim(), chunk.slice(at + 1).trim())
  }
  return map
}

/** 读一个主题块的令牌表（只收 `--*`）。 */
function tokensOf(blockHeader: string): Map<string, string> {
  const tokens = new Map<string, string>()
  for (const [name, value] of declarations(ruleBody(blockHeader))) {
    if (name.startsWith('--')) tokens.set(name, value)
  }
  return tokens
}

/** 解析 `var()`，支持 `var(--name)` 与 `var(--name, 兜底值)`，兜底值里可以再嵌 `var()`。 */
function resolveValue(value: string, tokens: Map<string, string>): string {
  let current = value
  for (let round = 0; round < 10 && current.includes('var('); round += 1) {
    const start = current.indexOf('var(')
    let depth = 0
    let end = -1
    for (let i = start + 4; i < current.length; i += 1) {
      const char = current.charAt(i)
      if (char === '(') depth += 1
      else if (char === ')') {
        if (depth === 0) {
          end = i
          break
        }
        depth -= 1
      }
    }
    if (end < 0) throw new Error(`var() 括号不配对：${current}`)
    const inner = current.slice(start + 4, end)
    const comma = inner.indexOf(',')
    const name = (comma < 0 ? inner : inner.slice(0, comma)).trim()
    const fallback = comma < 0 ? '' : inner.slice(comma + 1).trim()
    const replacement = tokens.get(name) ?? fallback
    if (replacement === '') throw new Error(`令牌没有定义、也没有兜底值：${name}`)
    current = `${current.slice(0, start)}${replacement}${current.slice(end + 1)}`
  }
  return current.trim()
}

/** 分类芯片某个属性的最终取值（按给定主题/卡片令牌解析）。 */
function chipValue(property: string, tokens: Map<string, string>): string {
  const value = declarations(ruleBody(CHIP_SELECTOR)).get(property)
  if (value === undefined) throw new Error(`分类芯片规则里没有 ${property}`)
  return resolveValue(value, tokens)
}

/** WCAG 相对亮度；本仓库的颜色令牌都是 `#rrggbb`，其余形式直接报错而不是悄悄算错。 */
function luminance(color: string): number {
  const hex = color.trim()
  if (!/^#[0-9a-f]{6}$/i.test(hex)) throw new Error(`只支持 #rrggbb：${color}`)
  const linear = [hex.slice(1, 3), hex.slice(3, 5), hex.slice(5, 7)]
    .map(part => Number.parseInt(part, 16) / 255)
    .map(part => (part <= 0.03928 ? part / 12.92 : ((part + 0.055) / 1.055) ** 2.4))
  const [r = 0, g = 0, b = 0] = linear
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** 对比度（1:1 ~ 21:1）。 */
function contrastRatio(foreground: string, background: string): number {
  const a = luminance(foreground)
  const b = luminance(background)
  const lighter = Math.max(a, b)
  const darker = Math.min(a, b)
  return (lighter + 0.05) / (darker + 0.05)
}

const LIGHT = tokensOf(LIGHT_BLOCK)
const DARK = tokensOf(DARK_BLOCK)

describe('设置分类卡片：取色跟着主题令牌走', () => {
  it('深浅两套主题的颜色令牌一一对应，新增令牌不会只写一边', () => {
    const isColor = (value: string): boolean => /^(#|rgb|hsl)/.test(value)
    const lightColorTokens = [...LIGHT].filter(([, value]) => isColor(value)).map(([name]) => name)
    expect(lightColorTokens.length).toBeGreaterThan(0)
    expect(lightColorTokens.filter(name => !DARK.has(name))).toEqual([])
    expect(isColor(DARK.get('--bg-scrim') ?? '')).toBe(true)
    expect(isColor(DARK.get('--shadow-color') ?? '')).toBe(true)
  })

  it('芯片底色与文字来自卡片令牌，并且跟着 data-theme 变', () => {
    const lightBackground = chipValue('background', LIGHT)
    const darkBackground = chipValue('background', DARK)

    // 没设过自定义卡片配色时回落到主题基础色。
    expect(lightBackground).toContain(LIGHT.get('--surface'))
    expect(darkBackground).toContain(DARK.get('--surface'))
    expect(darkBackground).not.toBe(lightBackground)
    expect(chipValue('color', LIGHT)).toBe(LIGHT.get('--ink'))
    expect(chipValue('color', DARK)).toBe(DARK.get('--ink'))
  })

  it('用户改卡片配色时芯片跟着改（这是用户报的那一条）', () => {
    const custom = new Map(DARK)
    custom.set('--card-color', '#123456')
    custom.set('--card-opacity', '60%')
    custom.set('--card-blur', '8px')
    custom.set('--card-ink', '#f4f7fb')

    const background = chipValue('background', custom)
    expect(background).toContain('#123456')
    expect(background).toContain('60%')
    expect(chipValue('color', custom)).toBe('#f4f7fb')
    expect(chipValue('backdrop-filter', custom)).toBe('blur(8px)')
  })

  it('芯片规则里不再出现写死的颜色', () => {
    expect(ruleBody(CHIP_SELECTOR)).not.toMatch(/#[0-9a-f]{3}|rgb\(|rgba\(|hsl\(|\bwhite\b|\bblack\b/i)
  })

  it('选中态仍然是强调色（主题色）而不是卡片色', () => {
    const selected = ruleBody(`${CHIP_SELECTOR}[aria-selected="true"]`)
    expect(selected).toContain('var(--blue-soft)')
    expect(selected).toContain('var(--blue)')
  })

  it('深浅两套主题下文字都看得清（对比度 ≥ 4.5:1）', () => {
    for (const tokens of [LIGHT, DARK]) {
      const background = chipValue('background', tokens).replace(/^color-mix\(in srgb,\s*/, '').split(/\s+/)[0] ?? ''
      const ink = chipValue('color', tokens)
      expect(contrastRatio(ink, background)).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('令牌块之外的阴影与遮罩也只有主题令牌', () => {
    // 颜色字面量只允许出现在 `:root…` 令牌块里（那是令牌的定义处）；其余规则一律引用 var()。
    const outsideTokens = STYLESHEET.replace(/^:root[^{]*\{[\s\S]*?^\}/gm, '')
    expect(outsideTokens).not.toMatch(/#[0-9a-f]{3}|rgb\(/i)
    expect(ruleBody('.app-background::after')).toContain('var(--bg-scrim)')
    for (const shadow of [
      'box-shadow: 6px 0 24px var(--shadow-color)',
      'box-shadow: 0 8px 28px var(--shadow-color)',
      'box-shadow: 0 1px 1px var(--shadow-color)',
    ]) {
      expect(STYLESHEET).toContain(shadow)
    }
    expect(LIGHT.get('--bg-scrim')).not.toBe(DARK.get('--bg-scrim'))
    expect(LIGHT.get('--shadow-color')).not.toBe(DARK.get('--shadow-color'))
  })
})

describe('设置分类卡片：DOM 与样式表对得上', () => {
  beforeEach(beforeEachAppTest)

  afterEach(() => {
    vi.restoreAllMocks()
  })

  /**
   * 从真实界面上取那排芯片。等待上限与 `src/App.settingsHome.test.tsx:28` 同一个理由：
   * `findBy*` 默认只等 1 s，而启动链把视图推到设置首页要将近 1 s（并行跑更慢），
   * 放宽的是等待，不是断言。
   */
  it('首页的分类芯片正是被 .settings-categories button 命中的元素', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await screen.findByRole('heading', { name: '设置' }, { timeout: 5_000 })

    const listbox = screen.getByRole('listbox', { name: '筛选功能分类' })
    const chips = within(listbox).getAllByRole('option')
    expect(chips.length).toBeGreaterThan(1)

    for (const chip of chips) {
      expect(chip.tagName).toBe('BUTTON')
      // 样式表那条规则的命中范围 = 这些真实元素，而不是某个已经改名的类名。
      expect(chip.matches(CHIP_SELECTOR)).toBe(true)
    }
    // 选中态由 aria-selected 表达，样式表里选中态那条规则挂在同一个选择器上。
    expect(chips.filter(chip => chip.getAttribute('aria-selected') === 'true')).toHaveLength(1)
  })
})
