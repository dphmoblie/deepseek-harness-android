import { useSyncExternalStore } from 'react'
import { runtimeBridge } from './platform/native'

/**
 * 外壳主题：三态选择 + 跟随系统。
 *
 * 与 `src/i18n.ts` 保持同一套模式（localStorage + useSyncExternalStore），
 * 区别只在于主题还要落到 DOM 上，并且多了一个「跟随系统」的输入源。
 * index.html 的 <head> 里有一段等价的极小内联脚本：它必须在样式表与模块加载
 * 之前就把 data-theme 定下来，否则首帧会先闪一下默认底色。两者是刻意的重复，
 * 改动存储键或解析规则时**必须同时改**（内联脚本靠 CSP 的 sha256 白名单放行，
 * 脚本一改就要重算 index.html 里的哈希，src/theme.test.ts 里有用例守着这件事）。
 */

/** 用户可选的三态：system 跟随系统，light/dark 为显式选择。 */
export type ThemeMode = 'system' | 'light' | 'dark'
/** 真正写到 DOM 上的只有两种取值，CSS 侧据此覆盖变量。 */
export type ResolvedTheme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'dsh-mobile-theme-v1'
/** 默认跟随系统：用户没表过态时，尊重系统的深色偏好比替他做决定更合理。 */
export const DEFAULT_THEME_MODE: ThemeMode = 'system'
export const THEME_MODES: readonly ThemeMode[] = ['system', 'light', 'dark']

/**
 * theme-color 与各自主题的页面背景取同一个值，否则 Android 状态栏会和页面顶部
 * 拼出一条明显的色带。深色沿用 index.html 里原有的 #111315（不借改主题顺手
 * 改掉状态栏观感），浅色取浅色背景 --bg。
 */
export const THEME_COLORS: Readonly<Record<ResolvedTheme, string>> = {
  light: '#f3f5f8',
  dark: '#111315',
}

const THEME_EVENT = 'dsh-theme-change'
const DARK_MEDIA_QUERY = '(prefers-color-scheme: dark)'

// 安全校验：只接受三个受控字面量。存储里的内容可能被任何脚本改写，
// 它只用于查表，不会被当成选择器、资源路径或 HTML 使用。
export function isThemeMode(value: unknown): value is ThemeMode {
  return typeof value === 'string' && (THEME_MODES as readonly string[]).includes(value)
}

/** 纯函数：三态 + 系统偏好在「跟随系统」时合成唯一的落地取值。 */
export function resolveTheme(mode: ThemeMode, systemPrefersDark: boolean): ResolvedTheme {
  if (mode === 'light' || mode === 'dark') return mode
  return systemPrefersDark ? 'dark' : 'light'
}

export function readThemeMode(): ThemeMode {
  try {
    const value = window.localStorage.getItem(THEME_STORAGE_KEY)
    // 非法值（含旧版本残留、被手改过的值）按默认值处理并如实回退，不抛错。
    return isThemeMode(value) ? value : DEFAULT_THEME_MODE
  } catch {
    // 无痕模式等场景下 localStorage 会直接抛错：按默认值继续，界面不能因此崩掉。
    return DEFAULT_THEME_MODE
  }
}

function darkMediaQuery(): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null
  try {
    return window.matchMedia(DARK_MEDIA_QUERY)
  } catch {
    return null
  }
}

/** matchMedia 缺失或抛错时按浅色处理：浅色是仓库原有外观，属于安全回退。 */
export function systemPrefersDark(): boolean {
  return darkMediaQuery()?.matches ?? false
}

// theme-color 决定 Android 状态栏配色；meta 缺失时静默跳过，不影响主题本身。
function syncThemeColorMeta(theme: ResolvedTheme): void {
  const meta = document.querySelector("meta[name='theme-color']")
  if (!meta) return
  meta.setAttribute('content', THEME_COLORS[theme])
}

/**
 * 把主题落到 DOM：data-theme 只写 light/dark（system 不是一种可直接渲染的外观，
 * 写进去会让 CSS 侧出现「第三套变量」），color-scheme 让原生控件与滚动条跟随。
 *
 * 同时把**模式**（不是解析结果）同步给原生：状态栏属于窗口，Web 改不了它，
 * 而 `meta[name=theme-color]` 只有 Chrome for Android 认、Android WebView 不认。
 * 传模式而非「深/浅」，是为了让原生在 system 模式下自己跟着系统切换。
 */
export function applyTheme(mode: ThemeMode): ResolvedTheme {
  const theme = resolveTheme(mode, systemPrefersDark())
  const root = document.documentElement
  root.dataset.theme = theme
  root.style.setProperty('color-scheme', theme)
  syncThemeColorMeta(theme)
  // 失败只影响状态栏配色，主题本身已经落地：因此不让它抛出、也不弹错——
  // 在这里把异常冒出去会让「切换主题」看起来失败了，而实际界面已经切好了。
  void runtimeBridge.setAppTheme(mode).catch(() => undefined)
  return theme
}

export function saveThemeMode(value: string): boolean {
  if (!isThemeMode(value)) return false
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, value)
  } catch {
    return false
  }
  applyTheme(value)
  window.dispatchEvent(new Event(THEME_EVENT))
  return true
}

function subscribe(listener: () => void): () => void {
  const media = darkMediaQuery()
  const onMediaChange = () => {
    // 只有「跟随系统」需要重画；显式选过 light/dark 的用户不跟随系统。
    if (readThemeMode() === 'system') applyTheme('system')
    listener()
  }
  // storage 事件来自其它文档，写入方已经改过自己的 DOM，这里要补落一次。
  const onStorage = () => {
    applyTheme(readThemeMode())
    listener()
  }
  window.addEventListener(THEME_EVENT, listener)
  window.addEventListener('storage', onStorage)
  media?.addEventListener('change', onMediaChange)
  return () => {
    window.removeEventListener(THEME_EVENT, listener)
    window.removeEventListener('storage', onStorage)
    media?.removeEventListener('change', onMediaChange)
  }
}

export function useThemeMode(): ThemeMode {
  return useSyncExternalStore(subscribe, readThemeMode, () => DEFAULT_THEME_MODE)
}

/**
 * 已解析的外观（system 下会随系统变化）。单独一个 hook 是因为 useSyncExternalStore
 * 只在快照变化时重渲染：只订阅 mode 的话，system 下系统切了主题，界面上的
 * 「当前显示」会停留在旧值。
 */
export function useResolvedTheme(): ResolvedTheme {
  return useSyncExternalStore(subscribe, () => resolveTheme(readThemeMode(), systemPrefersDark()), () => 'light')
}
