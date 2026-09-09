import { useSyncExternalStore } from 'react'
import { english } from './locales/en'

export type Language = 'zh-CN' | 'en'
export const LANGUAGE_STORAGE_KEY = 'dsh-mobile-language-v1'
const LANGUAGE_EVENT = 'dsh-language-change'

// 安全校验：仅接受支持的语言标识，不把存储内容作为资源路径或 HTML 使用。
export function readLanguage(): Language | null {
  try {
    const value = window.localStorage.getItem(LANGUAGE_STORAGE_KEY)
    return value === 'zh-CN' || value === 'en' ? value : null
  } catch {
    return null
  }
}

export function saveLanguage(value: string): boolean {
  if (value !== 'zh-CN' && value !== 'en') return false
  try {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, value)
  } catch {
    return false
  }
  document.documentElement.lang = value
  window.dispatchEvent(new Event(LANGUAGE_EVENT))
  return true
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
