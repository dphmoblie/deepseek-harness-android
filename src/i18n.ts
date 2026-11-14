import { useSyncExternalStore } from 'react'
import { english } from './locales/en'
import { japanese } from './locales/ja'
import { korean } from './locales/ko'
import { traditionalChinese } from './locales/zh-TW'

/**
 * 应用支持的界面语言。
 *
 * 简体中文是**源语言**：代码里的文案就是简体中文原文，其余语言按原文查词典。
 * 新增语言时，除了这里，还要同步 `src/locales/<语言>.ts`、原生 `AppLanguage` 的
 * 白名单与 `values-<语言>/strings.xml`，否则用户会看到中英混排。
 */
export const LANGUAGES = ['zh-CN', 'zh-TW', 'en', 'ja', 'ko'] as const
export type Language = typeof LANGUAGES[number]

export const LANGUAGE_STORAGE_KEY = 'dsh-mobile-language-v1'
const LANGUAGE_EVENT = 'dsh-language-change'

/** 安全校验：语言标识只作为词典键与 `lang` 属性使用，不接受任意字符串。 */
export function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (LANGUAGES as readonly string[]).includes(value)
}

const DICTIONARIES: Readonly<Record<Language, Readonly<Record<string, string>> | null>> = {
  'zh-CN': null,
  'zh-TW': traditionalChinese,
  en: english,
  ja: japanese,
  ko: korean,
}

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

export function readLanguage(): Language | null {
  try {
    const value = window.localStorage.getItem(LANGUAGE_STORAGE_KEY)
    if (isLanguage(value)) return value
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
  if (!isLanguage(value)) return false
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

/**
 * 查词典。插值仍由 React 转义，不使用 HTML 注入。
 *
 * 回退顺序：当前语言词典 → 英文 → 简体中文原文。英文作为第二级是为了让新增文案
 * 在日语/韩语词典补译之前仍有可读内容；简体中文原文是最后兜底，也是源语言本身。
 */
export function t(message: string, ...values: (string | number)[]): string {
  const language = readLanguage()
  let text = message
  if (language !== null && language !== 'zh-CN') {
    const dictionary = DICTIONARIES[language]
    if (dictionary !== null && Object.hasOwn(dictionary, message)) {
      text = dictionary[message]
    } else if (Object.hasOwn(english, message)) {
      text = english[message]
    }
  }
  return text.replace(/\{(\d+)\}/g, (placeholder, index: string) => String(values[Number(index)] ?? placeholder))
}
