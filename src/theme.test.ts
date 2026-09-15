import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { act, renderHook } from '@testing-library/react'
import {
  DEFAULT_THEME_MODE,
  THEME_COLORS,
  THEME_STORAGE_KEY,
  applyTheme,
  isThemeMode,
  readThemeMode,
  resolveTheme,
  saveThemeMode,
  systemPrefersDark,
  useResolvedTheme,
} from './theme'

type MediaListener = (event: { matches: boolean }) => void

/** 系统深色偏好在真实浏览器里不可编程，测试里用一个可翻转的替身代替。 */
function installMatchMedia(initialDark: boolean) {
  const listeners = new Set<MediaListener>()
  let dark = initialDark
  const list: MediaQueryList = {
    get matches() { return dark },
    media: '(prefers-color-scheme: dark)',
    onchange: null,
    addEventListener: (_type: string, listener: MediaListener) => { listeners.add(listener) },
    removeEventListener: (_type: string, listener: MediaListener) => { listeners.delete(listener) },
    addListener: (listener: MediaListener) => { listeners.add(listener) },
    removeListener: (listener: MediaListener) => { listeners.delete(listener) },
    dispatchEvent: () => true,
  } as unknown as MediaQueryList
  window.matchMedia = ((query: string) => (
    query === '(prefers-color-scheme: dark)' ? list : ({ ...list, matches: false } as unknown as MediaQueryList)
  )) as typeof window.matchMedia
  return {
    setDark(value: boolean) {
      dark = value
      for (const listener of [...listeners]) listener({ matches: value })
    },
    listenerCount: () => listeners.size,
  }
}

let media = installMatchMedia(false)

describe('主题解析', () => {
  beforeEach(() => {
    window.localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    document.head.innerHTML = '<meta name="theme-color" content="#111315" />'
    media = installMatchMedia(false)
  })

  afterEach(() => { document.head.innerHTML = '' })

  it('resolveTheme 是纯函数：只有 system 才看系统偏好', () => {
    expect(resolveTheme('system', true)).toBe('dark')
    expect(resolveTheme('system', false)).toBe('light')
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('light', false)).toBe('light')
    expect(resolveTheme('dark', true)).toBe('dark')
    expect(resolveTheme('dark', false)).toBe('dark')
  })

  it('只接受三个白名单取值', () => {
    for (const value of ['system', 'light', 'dark']) expect(isThemeMode(value)).toBe(true)
    for (const value of ['', 'System', 'DARK', 'auto', '../dark', '<script>', 'dark'.repeat(500), null, 42, {}]) {
      expect(isThemeMode(value)).toBe(false)
    }
  })

  it('存储非法值或为空时回退到默认值且不抛错', () => {
    expect(readThemeMode()).toBe(DEFAULT_THEME_MODE)
    for (const value of ['auto', '../dark', '<script>alert(1)</script>', 'dark'.repeat(2000)]) {
      window.localStorage.setItem(THEME_STORAGE_KEY, value)
      expect(readThemeMode()).toBe(DEFAULT_THEME_MODE)
    }
    window.localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    expect(readThemeMode()).toBe('dark')
  })

  it('localStorage 抛错（无痕模式）时按默认值处理', () => {
    const get = vi.spyOn(window.Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('存储不可用') })
    try {
      expect(readThemeMode()).toBe(DEFAULT_THEME_MODE)
    } finally {
      get.mockRestore()
    }
    const set = vi.spyOn(window.Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('存储不可用') })
    try {
      expect(saveThemeMode('dark')).toBe(false)
    } finally {
      set.mockRestore()
    }
  })

  it('saveThemeMode 拒绝白名单以外的值，也不写入存储', () => {
    for (const value of ['auto', '../dark', '<script>', '']) {
      expect(saveThemeMode(value)).toBe(false)
      expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
    }
  })

  it('applyTheme 只写 light/dark，并同步 theme-color', () => {
    expect(applyTheme('system')).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(document.querySelector("meta[name='theme-color']")?.getAttribute('content')).toBe(THEME_COLORS.light)

    applyTheme('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.querySelector("meta[name='theme-color']")?.getAttribute('content')).toBe(THEME_COLORS.dark)

    // system 永远不落到 DOM 上：CSS 侧只有 light/dark 两套变量。
    applyTheme('system')
    expect(document.documentElement.dataset.theme).not.toBe('system')
  })

  it('跟随系统时系统切换实时生效，显式选择时不跟随系统', () => {
    // 订阅方就是 useResolvedTheme 的宿主（设置页）与 index.html 里的首帧脚本；
    // 这里用真实的订阅入口验证 change 事件确实推动了解析结果。
    const hook = renderHook(() => useResolvedTheme())
    const store = window.localStorage
    act(() => { saveThemeMode('system') })
    expect(hook.result.current).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')

    act(() => { media.setDark(true) })
    expect(hook.result.current).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')

    act(() => { saveThemeMode('light') })
    act(() => { media.setDark(true) })
    expect(hook.result.current).toBe('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(store.getItem(THEME_STORAGE_KEY)).toBe('light')

    act(() => { media.setDark(false) })
    act(() => { saveThemeMode('dark') })
    expect(hook.result.current).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('其它文档改过存储后（storage 事件）补落一次主题', () => {
    renderHook(() => useResolvedTheme())
    window.localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    // 写入方是别的文档，本页只能靠 storage 事件跟上。
    act(() => { window.dispatchEvent(new Event('storage')) })
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.querySelector("meta[name='theme-color']")?.getAttribute('content')).toBe(THEME_COLORS.dark)
  })

  it('matchMedia 缺失时按浅色处理', () => {
    const original = window.matchMedia
    // 老 WebView / 测试替身里可能没有 matchMedia：不能因此抛错。
    Object.defineProperty(window, 'matchMedia', { value: undefined, configurable: true, writable: true })
    try {
      expect(systemPrefersDark()).toBe(false)
      expect(applyTheme('system')).toBe('light')
    } finally {
      window.matchMedia = original
    }
  })
})

/**
 * index.html 里的首帧脚本是 src/theme.ts 的最小副本（CSP 只放行同源脚本，
 * 内联脚本无法 import），两处必须一致；而内联脚本又要靠 CSP 哈希白名单才放行，
 * 脚本一改哈希就失效，且失效时**不会有任何报错**——只会静默退回默认底色。
 * 这三条用例就是把这两个「静默失效」变成会失败的测试。
 */
describe('index.html 首帧脚本与主题模块一致', () => {
  // vitest 的 cwd 就是仓库根目录（vitest.config.ts 所在处），index.html 与它同级；
  // 这里不用 import.meta.url：jsdom 环境下它是 http URL，转不成文件路径。
  const html = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')

  it('CSP 里的 sha256 与首帧脚本正文一致', () => {
    // 注释里可能出现脚本标签字样，因此按内容（dataset.theme）挑出真正的脚本。
    const match = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].find(x => x[1].includes('dataset.theme'))
    expect(match).toBeDefined()
    // HTML 解析器会先把输入流里的 CRLF 归一成 LF，再做 CSP 哈希校验；
    // 直接对文件字节做哈希在 CRLF 检出下会算出另一个值（脚本被 CSP 拦下，
    // 且没有任何报错，只在真机上表现为一下白闪）。所以这里也必须先归一。
    const body = match![1].replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    const hash = 'sha256-' + createHash('sha256').update(body, 'utf8').digest('base64')
    const declared = (html.match(/'sha256-[A-Za-z0-9+/=]+'/) ?? [''])[0].replace(/'/g, '')
    // 断言失败时把正确哈希写进报错里，照着替换 script-src 那一段即可。
    if (declared !== hash) throw new Error(`CSP 里的 sha256 已过期，应替换为 ${hash}`)
    expect(declared).toBe(hash)
  })

  it('首帧脚本使用与模块相同的存储键、三态取值与颜色', () => {
    expect(html).toContain(THEME_STORAGE_KEY)
    for (const mode of ['system', 'light', 'dark']) expect(html).toContain("'" + mode + "'")
    expect(html).toContain(THEME_COLORS.light)
    expect(html).toContain(THEME_COLORS.dark)
    expect(html).toContain('prefers-color-scheme: dark')
  })

  it('首帧脚本读存储时容忍抛错（无痕模式）', () => {
    expect(html).toMatch(/try \{[\s\S]*localStorage\.getItem[\s\S]*\} catch/)
    expect(html).toContain("meta[name='theme-color']")
  })
})
