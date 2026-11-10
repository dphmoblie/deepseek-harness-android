import { StrictMode } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { saveLanguage } from '../i18n'
import type { RuntimeBridge } from '../platform/types'
import { FirstRunPermissions } from './FirstRunPermissions'

const names = ['通知权限', '照片和视频读取', '读取应用列表', 'Shizuku 设备操作', '显示在其他应用上层', '所有文件访问', '无障碍服务', '安装未知应用']

function setup(strict = false) {
  const api = {
    getKeepAliveState: vi.fn().mockResolvedValue({ notificationPermission: 'prompt' }),
    requestNotificationPermission: vi.fn().mockResolvedValue({ supported: true, granted: false }),
    getStorageAccessState: vi.fn().mockResolvedValue({ mediaGranted: false, allFilesSupported: true, allFilesGranted: false }),
    requestMediaPermission: vi.fn().mockResolvedValue({ granted: false }),
    getInstalledApplicationsPermission: vi.fn().mockResolvedValue({ supported: true, granted: false }),
    requestInstalledApplicationsPermission: vi.fn().mockResolvedValue({ supported: true, granted: false }),
    getShizukuState: vi.fn().mockResolvedValue({ installed: true, running: true, permission: 'undetermined', connected: false }),
    requestShizukuPermission: vi.fn().mockResolvedValue({ permission: 'denied' }),
    getOverlayBallState: vi.fn().mockResolvedValue({ canDrawOverlays: false }),
    openOverlaySettings: vi.fn().mockResolvedValue(undefined),
    openAllFilesAccessSettings: vi.fn().mockResolvedValue({ supported: true, granted: false }),
    getAccessibilityAutomationState: vi.fn().mockResolvedValue({ serviceStatus: 'off' }),
    openAccessibilitySettings: vi.fn().mockResolvedValue(undefined),
    getAppUpdateInstallPermission: vi.fn().mockResolvedValue({ granted: false }),
    openAppUpdateInstallSettings: vi.fn().mockResolvedValue(undefined),
  }
  const done = vi.fn()
  const component = <FirstRunPermissions bridge={api as unknown as RuntimeBridge} onDone={done} />
  const view = render(strict ? <StrictMode>{component}</StrictMode> : component)
  return { api, done, ...view }
}

async function chooseOnly(...selected: string[]) {
  await screen.findByRole('button', { name: '确认并依次授权' })
  for (const name of names) {
    const checkbox = screen.getByRole<HTMLInputElement>('checkbox', { name })
    if (!selected.includes(name) && checkbox.checked && !checkbox.disabled) fireEvent.click(checkbox)
  }
}

function returnFromSettings() {
  fireEvent.blur(window)
  fireEvent.focus(window)
}

describe('FirstRunPermissions', () => {
  beforeEach(() => { saveLanguage('zh-CN') })
  afterEach(() => { vi.useRealTimers() })

  it('确认前说明全部用途、风险、拒绝影响，不提前申请任何权限', async () => {
    const { api, done } = setup()
    await screen.findByRole('button', { name: '确认并依次授权' })
    expect(screen.getAllByRole('checkbox')).toHaveLength(8)
    expect(screen.getByRole('dialog')).toHaveTextContent('模型供应商')
    expect(screen.getByRole('dialog')).toHaveTextContent('Root 和屏幕捕获只在你主动使用相关功能时另行说明并确认')
    expect(api.requestNotificationPermission).not.toHaveBeenCalled()
    expect(api.requestMediaPermission).not.toHaveBeenCalled()
    expect(api.requestInstalledApplicationsPermission).not.toHaveBeenCalled()
    expect(api.requestShizukuPermission).not.toHaveBeenCalled()
    expect(api.openOverlaySettings).not.toHaveBeenCalled()
    expect(api.openAllFilesAccessSettings).not.toHaveBeenCalled()
    expect(api.openAccessibilitySettings).not.toHaveBeenCalled()
    expect(api.openAppUpdateInstallSettings).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '暂不授权' }))
    expect(done).toHaveBeenCalledOnce()
  })

  it('运行时请求严格串行，连点不重复，拒绝和异常仍继续下一项', async () => {
    const { api, done } = setup()
    let release!: (result: { supported: boolean; granted: boolean }) => void
    api.requestNotificationPermission.mockImplementation(() => new Promise(resolve => { release = resolve }))
    api.requestMediaPermission.mockRejectedValue(new Error('failed'))
    await chooseOnly('通知权限', '照片和视频读取', '读取应用列表', 'Shizuku 设备操作')
    const start = screen.getByRole('button', { name: '确认并依次授权' })
    fireEvent.click(start)
    fireEvent.click(start)
    await waitFor(() => expect(api.requestNotificationPermission).toHaveBeenCalledOnce())
    expect(api.requestMediaPermission).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '继续下一项' })).toBeNull()
    await act(() => { release({ supported: true, granted: false }); return Promise.resolve() })
    await screen.findByRole('button', { name: '完成' })
    expect(api.requestMediaPermission).toHaveBeenCalledOnce()
    expect(api.requestInstalledApplicationsPermission).toHaveBeenCalledOnce()
    expect(api.requestShizukuPermission).toHaveBeenCalledOnce()
    expect(api.openOverlaySettings).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toHaveTextContent('照片和视频读取：检查或申请失败')
    expect(done).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '完成' }))
    expect(done).toHaveBeenCalledOnce()
  })

  it('设置页必须真正离开并返回，单纯focus和等待不会提前开下一页', async () => {
    const { api } = setup()
    await chooseOnly('显示在其他应用上层', '所有文件访问')
    fireEvent.click(screen.getByRole('button', { name: '确认并依次授权' }))
    await waitFor(() => expect(api.openOverlaySettings).toHaveBeenCalledOnce())
    fireEvent.focus(window)
    expect(api.openAllFilesAccessSettings).not.toHaveBeenCalled()
    vi.useFakeTimers()
    await act(() => { vi.advanceTimersByTime(60_000); return Promise.resolve() })
    expect(api.openAllFilesAccessSettings).not.toHaveBeenCalled()
    vi.useRealTimers()
    api.getOverlayBallState.mockResolvedValue({ canDrawOverlays: true })
    returnFromSettings()
    await waitFor(() => expect(api.openAllFilesAccessSettings).toHaveBeenCalledOnce())
    expect(screen.queryByRole('button', { name: '完成' })).toBeNull()
    returnFromSettings()
    await screen.findByRole('button', { name: '完成' })
    expect(screen.getByRole('dialog')).toHaveTextContent('显示在其他应用上层：已授权')
  })

  it('跳转调用内部发出的离开与返回事件也能被捕获，兜底继续会重新读真实状态', async () => {
    const { api } = setup()
    await chooseOnly('显示在其他应用上层', '所有文件访问')
    api.openOverlaySettings.mockImplementation(() => { returnFromSettings(); return Promise.resolve(undefined) })
    fireEvent.click(screen.getByRole('button', { name: '确认并依次授权' }))
    await waitFor(() => expect(api.openAllFilesAccessSettings).toHaveBeenCalledOnce())
    api.getStorageAccessState.mockResolvedValue({ mediaGranted: false, allFilesSupported: true, allFilesGranted: true })
    fireEvent.click(screen.getByRole('button', { name: '继续下一项' }))
    await screen.findByRole('button', { name: '完成' })
    expect(screen.getByRole('dialog')).toHaveTextContent('所有文件访问：已授权')
  })

  it('已授权、不支持和Shizuku未运行的权限跳过，不因连接未就绪重复申请', async () => {
    const { api } = setup(true)
    api.getKeepAliveState.mockResolvedValue({ notificationPermission: 'unsupported' })
    api.getStorageAccessState.mockResolvedValue({ mediaGranted: true, allFilesSupported: false, allFilesGranted: false })
    api.getInstalledApplicationsPermission.mockResolvedValue({ supported: false, granted: true })
    api.getShizukuState.mockResolvedValue({ installed: true, running: false, permission: 'granted', connected: false })
    api.getOverlayBallState.mockResolvedValue({ canDrawOverlays: true })
    api.getAccessibilityAutomationState.mockResolvedValue({ serviceStatus: 'enabled_disconnected' })
    api.getAppUpdateInstallPermission.mockResolvedValue({ granted: true })
    await screen.findByRole('button', { name: '确认并依次授权' })
    fireEvent.click(screen.getByRole('button', { name: '确认并依次授权' }))
    await screen.findByRole('button', { name: '完成' })
    expect(api.requestNotificationPermission).not.toHaveBeenCalled()
    expect(api.requestMediaPermission).not.toHaveBeenCalled()
    expect(api.requestInstalledApplicationsPermission).not.toHaveBeenCalled()
    expect(api.requestShizukuPermission).not.toHaveBeenCalled()
    expect(api.openOverlaySettings).not.toHaveBeenCalled()
    expect(api.openAllFilesAccessSettings).not.toHaveBeenCalled()
    expect(api.openAccessibilitySettings).not.toHaveBeenCalled()
    expect(api.openAppUpdateInstallSettings).not.toHaveBeenCalled()
  })

  it('卸载会清理设置页监听并中止剩余授权', async () => {
    const { api, unmount } = setup()
    const removeWindow = vi.spyOn(window, 'removeEventListener')
    const removeDocument = vi.spyOn(document, 'removeEventListener')
    await chooseOnly('显示在其他应用上层', '所有文件访问')
    fireEvent.click(screen.getByRole('button', { name: '确认并依次授权' }))
    await waitFor(() => expect(api.openOverlaySettings).toHaveBeenCalledOnce())
    unmount()
    await act(() => { returnFromSettings(); return Promise.resolve() })
    expect(api.openAllFilesAccessSettings).not.toHaveBeenCalled()
    expect(removeWindow).toHaveBeenCalledWith('blur', expect.any(Function))
    expect(removeWindow).toHaveBeenCalledWith('focus', expect.any(Function))
    expect(removeDocument).toHaveBeenCalledWith('visibilitychange', expect.any(Function))
  })
})
