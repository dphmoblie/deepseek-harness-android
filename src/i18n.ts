import { useSyncExternalStore } from 'react'
import { english } from './locales/en'

export type Language = 'zh-CN' | 'en'
export const LANGUAGE_STORAGE_KEY = 'dsh-mobile-language-v1'
const LANGUAGE_EVENT = 'dsh-language-change'

/**
 * 存储写不进去时的进程内语言。
 *
 * 没有它，localStorage 被清空或被限制（隐私模式、WebView 存储配额满）的用户会在语言门禁里
 * 反复「选了但没记住」，永远进不了应用：门禁的判据是 readLanguage() === null。
 * 本次会话先记住选择，下次启动再由原生偏好回填（见 README 与 App.tsx 的启动探测）。
 */
let sessionLanguage: Language | null = null

/** 清掉进程内缓存的语言（测试用：真实运行里它随页面生命周期一起消失，不需要手动清）。 */
export function resetLanguageSession(): void {
  sessionLanguage = null
}

// 安全校验：仅接受支持的语言标识，不把存储内容作为资源路径或 HTML 使用。
export function readLanguage(): Language | null {
  try {
    const value = window.localStorage.getItem(LANGUAGE_STORAGE_KEY)
    if (value === 'zh-CN' || value === 'en') return value
  } catch {
    // 存储不可用：退回本次会话里选过的语言。
  }
  return sessionLanguage
}

/**
 * 保存语言并立刻生效；返回值只表示**是否落盘**。
 *
 * 即使落盘失败，界面语言也会切过去（调用方据此提示「下次启动可能再次询问」），
 * 否则用户会被一个点了没反应的门禁困住。
 */
export function saveLanguage(value: string): boolean {
  if (value !== 'zh-CN' && value !== 'en') return false
  sessionLanguage = value
  let persisted = true
  try {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, value)
  } catch {
    persisted = false
  }
  document.documentElement.lang = value
  window.dispatchEvent(new Event(LANGUAGE_EVENT))
  return persisted
}

function subscribe(listener: () => void): () => void {
  window.addEventListener(LANGUAGE_EVENT, listener)
  window.addEventListener('storage', listener)
  return () => {
    window.removeEventListener(LANGUAGE_EVENT, listener)
    window.removeEventListener('storage', listener)
  }
}

export function useLanguage(): Language | null {
  return useSyncExternalStore(subscribe, readLanguage, () => null)
}

// 仅对受控文案查表；插值仍由 React 转义，不使用 HTML 注入。
export function t(message: string, ...values: (string | number)[]): string {
  const text = readLanguage() === 'en' && Object.hasOwn(english, message) ? english[message] : message
  return text.replace(/\{(\d+)\}/g, (placeholder, index: string) => String(values[Number(index)] ?? placeholder))
}
