import { StrictMode } from 'react'
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppUpdateState } from './platform/types'
import type * as ReminderModule from './appUpdateReminder'

const release = { version: '0.3.0', bytes: 1024, sha256: 'a'.repeat(64), notes: '更新说明' }
const noUpdate: AppUpdateState = { installedVersion: '0.2.13', installedVersionCode: 23, channel: 'stable', installAllowed: false }
const update: AppUpdateState = { ...noUpdate, available: release }
let reminder: typeof ReminderModule
let visibility: DocumentVisibilityState = 'visible'

const settle = async (): Promise<void> => { await act(async () => { await vi.advanceTimersByTimeAsync(0) }) }
const focus = (): void => { act(() => { window.dispatchEvent(new Event('focus')) }) }
const setVisibility = (value: DocumentVisibilityState): void => {
  visibility = value
  act(() => { document.dispatchEvent(new Event('visibilitychange')) })
}

describe('每日应用更新提醒', () => {
  beforeEach(async () => {
    vi.resetModules()
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 9, 12, 9))
    window.localStorage.clear()
    visibility = 'visible'
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
    reminder = await import('./appUpdateReminder')
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('本地日历去重，布尔偏好异常时使用默认值', () => {
    expect(reminder.updateReminderDay(new Date(2026, 0, 2, 0, 1))).toBe('2026-01-02')
    expect(reminder.parseUpdateReminderEnabled('false')).toBe(false)
    for (const value of [null, '', 'true', 'False', '<script>', 'false'.repeat(100)]) {
      expect(reminder.parseUpdateReminderEnabled(value)).toBe(true)
    }
  })

  it('ready 前不请求且保持启动等待，新版本当天仅展示一次', async () => {
    const load = vi.fn().mockResolvedValue(update)
    const hook = renderHook(({ ready }) => reminder.useAppUpdateReminder(ready, load), { initialProps: { ready: false } })
    expect(hook.result.current.checking).toBe(true)
    expect(load).not.toHaveBeenCalled()
    hook.rerender({ ready: true })
    await settle()
    expect(load).toHaveBeenCalledTimes(1)
    expect(hook.result.current.available).toEqual(release)
    expect(hook.result.current.checking).toBe(false)
    expect(localStorage.getItem(reminder.APP_UPDATE_REMINDER_SHOWN_KEY)).toBe('2026-10-12')
    act(() => hook.result.current.dismiss())
    focus()
    setVisibility('visible')
    await settle()
    expect(hook.result.current.available).toBeNull()
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('当天已提示时重挂、刷新模块与更换版本均不再请求', async () => {
    localStorage.setItem(reminder.APP_UPDATE_REMINDER_SHOWN_KEY, '2026-10-12')
    const load = vi.fn().mockResolvedValue({ ...update, available: { ...release, version: '0.4.0' } })
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    hook.unmount()
    vi.resetModules()
    reminder = await import('./appUpdateReminder')
    const next = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    expect(next.result.current.checking).toBe(false)
    expect(next.result.current.available).toBeNull()
    expect(load).not.toHaveBeenCalled()
  })

  it('无更新的检查结果也持久去重，同日重新打开不请求', async () => {
    const load = vi.fn().mockResolvedValue(noUpdate)
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    expect(localStorage.getItem(reminder.APP_UPDATE_REMINDER_CHECKED_KEY)).toBe('2026-10-12')
    hook.unmount()
    vi.resetModules()
    reminder = await import('./appUpdateReminder')
    renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    expect(load).toHaveBeenCalledTimes(1)
  })

  it.each([noUpdate, update])('清除存储后不复用已处理的旧响应 %#', async initial => {
    const load = vi.fn().mockResolvedValueOnce(initial).mockResolvedValueOnce({
      ...update,
      available: { ...release, version: '0.4.0' },
    })
    const first = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    first.unmount()
    localStorage.clear()
    const next = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    expect(load).toHaveBeenCalledTimes(2)
    expect(next.result.current.available?.version).toBe('0.4.0')
  })

  it('跨日只在回到前台时检查，仍有更新则新一天提示一次', async () => {
    const load = vi.fn().mockResolvedValue(update)
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    act(() => hook.result.current.dismiss())
    setVisibility('hidden')
    vi.setSystemTime(new Date(2026, 9, 13, 9))
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    focus()
    expect(load).toHaveBeenCalledTimes(1)
    setVisibility('visible')
    await settle()
    expect(load).toHaveBeenCalledTimes(2)
    expect(hook.result.current.available).toEqual(release)
    expect(localStorage.getItem(reminder.APP_UPDATE_REMINDER_SHOWN_KEY)).toBe('2026-10-13')
  })

  it('关闭后不检查、不提示，持久保存且重新启用可检查', async () => {
    localStorage.setItem(reminder.APP_UPDATE_REMINDER_ENABLED_KEY, 'false')
    const load = vi.fn().mockResolvedValue(update)
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load))
    focus()
    await settle()
    expect(load).not.toHaveBeenCalled()
    expect(hook.result.current.checking).toBe(false)
    act(() => { expect(hook.result.current.setEnabled(true)).toBe(true) })
    await settle()
    expect(hook.result.current.available).toEqual(release)
    act(() => { expect(hook.result.current.setEnabled(false)).toBe(true) })
    expect(hook.result.current.available).toBeNull()
    expect(localStorage.getItem(reminder.APP_UPDATE_REMINDER_ENABLED_KEY)).toBe('false')
    hook.unmount()
    const next = renderHook(() => reminder.useAppUpdateReminder(true, load))
    expect(next.result.current.enabled).toBe(false)
    expect(next.result.current.checking).toBe(false)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('后台收到新版本不消耗提示，回到前台复用结果', async () => {
    let resolve: (state: AppUpdateState) => void = () => undefined
    const load = vi.fn(() => new Promise<AppUpdateState>(done => { resolve = done }))
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    setVisibility('hidden')
    await act(async () => { resolve(update); await Promise.resolve() })
    expect(hook.result.current.available).toBeNull()
    expect(localStorage.getItem(reminder.APP_UPDATE_REMINDER_SHOWN_KEY)).toBeNull()
    setVisibility('visible')
    await settle()
    expect(hook.result.current.available).toEqual(release)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('请求中卸载，重挂后复用请求且仅新挂载实例接收结果', async () => {
    let resolve: (state: AppUpdateState) => void = () => undefined
    const load = vi.fn(() => new Promise<AppUpdateState>(done => { resolve = done }))
    const first = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    first.unmount()
    const next = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await act(async () => { resolve(update); await Promise.resolve() })
    expect(load).toHaveBeenCalledTimes(1)
    expect(next.result.current.available).toEqual(release)
  })

  it('StrictMode 与连续 focus/visibilitychange 只进行一次请求', async () => {
    const load = vi.fn().mockResolvedValue(update)
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load), { wrapper: StrictMode })
    focus()
    setVisibility('visible')
    await settle()
    expect(load).toHaveBeenCalledTimes(1)
    expect(hook.result.current.available).toEqual(release)
  })

  it('请求中关闭不会展示晚到结果，重新开启复用已有结果', async () => {
    let resolve: (state: AppUpdateState) => void = () => undefined
    const load = vi.fn(() => new Promise<AppUpdateState>(done => { resolve = done }))
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    act(() => { hook.result.current.setEnabled(false) })
    await act(async () => { resolve(update); await Promise.resolve() })
    expect(hook.result.current.available).toBeNull()
    expect(localStorage.getItem(reminder.APP_UPDATE_REMINDER_SHOWN_KEY)).toBeNull()
    act(() => { hook.result.current.setEnabled(true) })
    await settle()
    expect(hook.result.current.available).toEqual(release)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it.each(['reject', 'throw'] as const)('请求 %s 时安静结束，同日不重试刷屏', async kind => {
    const load = vi.fn(() => {
      if (kind === 'throw') throw new Error('连接失败')
      return Promise.reject(new Error('连接失败'))
    })
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    focus()
    await settle()
    expect(hook.result.current.checking).toBe(false)
    expect(hook.result.current.available).toBeNull()
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('十秒超时解除自动启动阻塞，不接收超时后的结果', async () => {
    let resolve: (state: AppUpdateState) => void = () => undefined
    const load = vi.fn(() => new Promise<AppUpdateState>(done => { resolve = done }))
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(hook.result.current.checking).toBe(false)
    await act(async () => { resolve(update); await Promise.resolve() })
    focus()
    await settle()
    expect(hook.result.current.available).toBeNull()
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('存储写入失败时只在内存降级，并明确返回未持久化', async () => {
    const load = vi.fn().mockResolvedValue(update)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('存储不可用') })
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    expect(hook.result.current.storageAvailable).toBe(false)
    act(() => { expect(hook.result.current.setEnabled(false)).toBe(false) })
    hook.unmount()
    const next = renderHook(() => reminder.useAppUpdateReminder(true, load))
    expect(next.result.current.enabled).toBe(false)
    expect(next.result.current.storageAvailable).toBe(false)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('存储读取失败仍能显示提醒，未保存日期只用于当前会话去重', async () => {
    const load = vi.fn().mockResolvedValue(update)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('存储不可用') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('存储不可用') })
    const hook = renderHook(() => reminder.useAppUpdateReminder(true, load))
    await settle()
    expect(hook.result.current.available).toEqual(release)
    expect(hook.result.current.storageAvailable).toBe(false)
    act(() => hook.result.current.dismiss())
    focus()
    await settle()
    expect(hook.result.current.available).toBeNull()
    expect(load).toHaveBeenCalledTimes(1)
  })
})
