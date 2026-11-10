import { Capacitor } from '@capacitor/core'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, settings } from './__tests__/appTestHarness'
import { FIRST_RUN_PERMISSIONS_STORAGE_KEY } from './components/FirstRunPermissions'
import { ONBOARDING_STORAGE_KEY } from './components/Onboarding'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({ TerminalPanel: () => <div /> }))
import { App } from './App'

describe('Android 首次权限说明', () => {
  beforeEach(() => {
    beforeEachAppTest()
    vi.spyOn(Capacitor, 'isNativePlatform').mockReturnValue(true)
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('首次自动显示详细说明，确认前不请求权限，也不遮叠配置引导', async () => {
    window.localStorage.removeItem(ONBOARDING_STORAGE_KEY)
    bridge.getSettings.mockResolvedValue({ ...settings, prewarmHarness: true })
    render(<App />)
    const dialog = await screen.findByRole('dialog', { name: '权限说明与授权' })
    expect(screen.queryByRole('dialog', { name: '免费声明' })).toBeNull()
    expect(dialog).toHaveTextContent('风险：')
    expect(dialog).toHaveTextContent('拒绝后：')
    expect(dialog).toHaveTextContent('模型供应商')
    expect(bridge.requestNotificationPermission).not.toHaveBeenCalled()
    expect(bridge.requestMediaPermission).not.toHaveBeenCalled()
    expect(bridge.requestShizukuPermission).not.toHaveBeenCalled()
    expect(bridge.startHarness).not.toHaveBeenCalled()
    expect(window.localStorage.getItem(FIRST_RUN_PERMISSIONS_STORAGE_KEY)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '暂不授权' }))
    expect(await screen.findByRole('dialog', { name: '免费声明' })).toBeInTheDocument()
    expect(window.localStorage.getItem(FIRST_RUN_PERMISSIONS_STORAGE_KEY)).toBe('1')
    expect(bridge.openOverlaySettings).not.toHaveBeenCalled()
  })

  it('拒绝后可进入应用，再次打开不自动打扰，并可从设置重新查看', async () => {
    const first = render(<App />)
    await screen.findByRole('dialog', { name: '权限说明与授权' })
    fireEvent.click(screen.getByRole('button', { name: '暂不授权' }))
    first.unmount()
    render(<App />)
    await screen.findByRole('heading', { name: '对话已就绪' })
    expect(screen.queryByRole('dialog', { name: '权限说明与授权' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '设置' }))
    fireEvent.click(await screen.findByRole('button', { name: /权限说明与授权/ }))
    expect(await screen.findByRole('dialog', { name: '权限说明与授权' })).toBeInTheDocument()
    expect(bridge.requestNotificationPermission).not.toHaveBeenCalled()
  })

  it('选择后拒绝系统权限，结果如实显示，完成时才保存首启标记', async () => {
    bridge.getKeepAliveState.mockResolvedValue({ ...await bridge.getKeepAliveState(), notificationPermission: 'prompt' })
    render(<App />)
    await screen.findByRole('button', { name: '确认并依次授权' })
    const notification = screen.getByRole<HTMLInputElement>('checkbox', { name: '通知权限' })
    for (const checkbox of screen.getAllByRole<HTMLInputElement>('checkbox')) {
      if (checkbox !== notification && !checkbox.disabled && checkbox.checked) fireEvent.click(checkbox)
    }
    // 每项控件都有准确标签；仅选择通知，让系统拒绝后结束本轮。
    if (!notification.checked) fireEvent.click(notification)
    bridge.requestNotificationPermission.mockResolvedValue({ supported: true, granted: false })
    fireEvent.click(screen.getByRole('button', { name: '确认并依次授权' }))
    await screen.findByRole('button', { name: '完成' })
    expect(screen.getByRole('dialog')).toHaveTextContent('通知权限：未授权')
    expect(window.localStorage.getItem(FIRST_RUN_PERMISSIONS_STORAGE_KEY)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '完成' }))
    expect(window.localStorage.getItem(FIRST_RUN_PERMISSIONS_STORAGE_KEY)).toBe('1')
  })

  it('在语言选择与启动状态就绪之后才显示权限说明', async () => {
    window.localStorage.removeItem('dsh-mobile-language-v1')
    // beforeEach 没有设内存语言，原生也未配置，先让用户选择语言。
    render(<App />)
    await screen.findByLabelText('语言 / Language')
    expect(screen.queryByRole('dialog', { name: '权限说明与授权' })).toBeNull()
    fireEvent.change(screen.getByLabelText('语言 / Language'), { target: { value: 'zh-CN' } })
    await screen.findByRole('dialog', { name: '权限说明与授权' })
  })

  it('存储不可写也能暂不授权并继续，不会误触发系统请求', async () => {
    render(<App />)
    await screen.findByRole('dialog', { name: '权限说明与授权' })
    const original = window.localStorage.setItem.bind(window.localStorage)
    vi.spyOn(window.Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
      if (key === FIRST_RUN_PERMISSIONS_STORAGE_KEY) throw new Error('存储不可用')
      return original.call(this, key, value)
    })
    fireEvent.click(screen.getByRole('button', { name: '暂不授权' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '权限说明与授权' })).toBeNull())
    expect(bridge.requestMediaPermission).not.toHaveBeenCalled()
  })

  it('浏览器预览不触发 Android 首次授权流程', async () => {
    vi.mocked(Capacitor.isNativePlatform).mockReturnValue(false)
    render(<App />)
    await screen.findByRole('heading', { name: '对话已就绪' })
    expect(screen.queryByRole('dialog', { name: '权限说明与授权' })).toBeNull()
    expect(bridge.getAppUpdateInstallPermission).not.toHaveBeenCalled()
  })
})
