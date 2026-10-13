import { useCallback, useEffect, useState } from 'react'
import type { AppUpdateRelease, AppUpdateState } from './platform/types'

export const APP_UPDATE_REMINDER_ENABLED_KEY = 'dsh-app-update-reminder-enabled-v1'
export const APP_UPDATE_REMINDER_CHECKED_KEY = 'dsh-app-update-reminder-checked-v1'
export const APP_UPDATE_REMINDER_SHOWN_KEY = 'dsh-app-update-reminder-shown-v1'
const CHECK_TIMEOUT_MS = 10_000

const memoryStorage = new Map<string, string>()
let storageAvailable = true
let dailyCheck: { day: string; result: Promise<AppUpdateState | null> } | null = null

export function updateReminderDay(date = new Date()): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

export function parseUpdateReminderEnabled(value: string | null): boolean {
  // 安全校验：仅接受精确的布尔字面量，异常存储值按默认开启处理。
  return value !== 'false'
}

function readStored(key: string): string | null {
  if (memoryStorage.has(key)) return memoryStorage.get(key) ?? null
  try { return window.localStorage.getItem(key) } catch {
    storageAvailable = false
    return null
  }
}

function writeStored(key: string, value: string): boolean {
  try {
    window.localStorage.setItem(key, value)
    memoryStorage.delete(key)
    return true
  } catch {
    // 存储失败仍遵守本次会话的关闭/去重选择；重启后的持久化由界面明确告知。
    memoryStorage.set(key, value)
    storageAvailable = false
    return false
  }
}

function checkOnce(day: string, loadUpdate: () => Promise<AppUpdateState>): Promise<AppUpdateState | null> {
  if (dailyCheck?.day === day) return dailyCheck.result
  if (readStored(APP_UPDATE_REMINDER_CHECKED_KEY) === day) return Promise.resolve(null)
  const result = new Promise<AppUpdateState | null>(resolve => {
    const timeout = window.setTimeout(() => resolve(null), CHECK_TIMEOUT_MS)
    // 同时处理同步抛错与 Promise 拒绝，自动提醒失败不能阻断进入应用。
    void Promise.resolve().then(loadUpdate).then(resolve, () => resolve(null)).finally(() => window.clearTimeout(timeout))
  }).then(state => {
    if (!state?.available) {
      writeStored(APP_UPDATE_REMINDER_CHECKED_KEY, day)
      if (dailyCheck?.result === result) dailyCheck = null
    }
    return state
  })
  // StrictMode 与同日重挂复用请求；只有日期标记写入 localStorage。
  dailyCheck = { day, result }
  return result
}

export function useAppUpdateReminder(ready: boolean, loadUpdate: () => Promise<AppUpdateState>) {
  const [enabled, setEnabledState] = useState(() => parseUpdateReminderEnabled(readStored(APP_UPDATE_REMINDER_ENABLED_KEY)))
  const [available, setAvailable] = useState<AppUpdateRelease | null>(null)
  const [checking, setChecking] = useState(true)
  const [persistent, setPersistent] = useState(storageAvailable)

  const setEnabled = useCallback((next: boolean): boolean => {
    const saved = writeStored(APP_UPDATE_REMINDER_ENABLED_KEY, String(next))
    setEnabledState(next)
    setAvailable(null)
    setPersistent(storageAvailable)
    return saved
  }, [])
  const dismiss = useCallback(() => setAvailable(null), [])

  useEffect(() => {
    if (!ready || !enabled) return
    let active = true
    let request = 0
    const check = (): void => {
      if (document.visibilityState === 'hidden') return
      const current = ++request
      const day = updateReminderDay()
      if (readStored(APP_UPDATE_REMINDER_SHOWN_KEY) === day) {
        setChecking(false)
        setPersistent(storageAvailable)
        return
      }
      setChecking(true)
      const result = checkOnce(day, loadUpdate)
      void result.then(state => {
        if (!active || current !== request) return
        setChecking(false)
        if (state?.available && document.visibilityState !== 'hidden' && day === updateReminderDay()) {
          writeStored(APP_UPDATE_REMINDER_SHOWN_KEY, day)
          writeStored(APP_UPDATE_REMINDER_CHECKED_KEY, day)
          if (dailyCheck?.result === result) dailyCheck = null
          setAvailable(state.available)
        }
        setPersistent(storageAvailable)
      })
    }
    check()
    document.addEventListener('visibilitychange', check)
    window.addEventListener('focus', check)
    return () => {
      active = false
      document.removeEventListener('visibilitychange', check)
      window.removeEventListener('focus', check)
    }
  }, [ready, enabled, loadUpdate])

  return { enabled, setEnabled, available: ready && enabled ? available : null, dismiss, checking: enabled && checking, storageAvailable: persistent }
}
