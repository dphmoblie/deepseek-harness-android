import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { arriveAtSettingsHome } from './__tests__/appTestHarness'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge } from './__tests__/appTestHarness'
import { APP_UPDATE_REMINDER_ENABLED_KEY } from './appUpdateReminder'
import type { AppUpdateState } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({ TerminalPanel: () => <div /> }))
import { App } from './App'

const cachedUpdate: AppUpdateState = {
  installedVersion: '0.2.0', installedVersionCode: 22, channel: 'stable', installAllowed: false,
  cached: true, checkedAt: 1_700_000_000_000,
  available: { version: '0.3.0', bytes: 4096, sha256: 'a'.repeat(64), notes: '公告正文'.repeat(100) },
}

async function openVersions(): Promise<void> {
  // 开机不再自动进设置首页：先导航，再进「版本管理」。
  await arriveAtSettingsHome()
  fireEvent.click(screen.getByRole('button', { name: /版本管理/ }))
  await screen.findByRole('heading', { name: '版本管理' })
}

describe('应用更新入口与缓存交互', () => {
  beforeEach(() => {
    beforeEachAppTest()
    bridge.getAppUpdateState.mockResolvedValue(cachedUpdate)
  })

  it('先提示再自动启动，查看更新仅导航且同日重挂不再弹窗', async () => {
    localStorage.removeItem(APP_UPDATE_REMINDER_ENABLED_KEY)
    const mounted = render(<App />)
    const dialog = await screen.findByRole('dialog', { name: '发现应用新版本' })
    expect(bridge.openHarness).not.toHaveBeenCalled()
    expect(within(dialog).queryByRole('button', { name: '展开完整更新公告' })).toBeNull()
    fireEvent.click(within(dialog).getByRole('button', { name: '查看更新' }))
    await screen.findByRole('heading', { name: '版本管理' })
    expect(await screen.findByText('0.3.0 · 4.0 KB')).toBeVisible()
    expect(bridge.getAppUpdateState).toHaveBeenLastCalledWith(false)
    expect(bridge.openHarness).not.toHaveBeenCalled()
    expect(bridge.downloadAppUpdate).not.toHaveBeenCalled()
    expect(bridge.installAppUpdate).not.toHaveBeenCalled()
    mounted.unmount()
    bridge.getAppUpdateState.mockClear()
    render(<App />)
  await arriveAtSettingsHome()
    expect(screen.queryByRole('dialog', { name: '发现应用新版本' })).toBeNull()
    expect(bridge.getAppUpdateState).not.toHaveBeenCalled()
  })

  it('检查进行中不自动打开 Harness，稍后关闭后恢复启动', async () => {
    localStorage.removeItem(APP_UPDATE_REMINDER_ENABLED_KEY)
    let finish!: (state: AppUpdateState) => void
    bridge.getAppUpdateState.mockImplementation(() => new Promise<AppUpdateState>(resolve => { finish = resolve }))
    render(<App />)
    await waitFor(() => expect(bridge.getAppUpdateState).toHaveBeenCalled())
    expect(bridge.openHarness).not.toHaveBeenCalled()
    await act(async () => { finish(cachedUpdate); await Promise.resolve() })
    fireEvent.click(await screen.findByRole('button', { name: '暂不更新' }))
  await arriveAtSettingsHome()
  })

  it('重入使用缓存读取，只有手动重新检查强刷，失败保留信息', async () => {
    render(<App />)
    await openVersions()
    await screen.findByText('0.3.0 · 4.0 KB')
    expect(bridge.getAppUpdateState).toHaveBeenCalledWith(false)
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    await openVersions()
    await screen.findByText('0.3.0 · 4.0 KB')
    expect(bridge.getAppUpdateState.mock.calls).toEqual([[false], [false]])
    bridge.getAppUpdateState.mockRejectedValueOnce(new Error('offline'))
    fireEvent.click(screen.getByRole('button', { name: '重新检查' }))
    expect(await screen.findByText('重新检查失败，仍显示上次缓存的更新信息。')).toBeVisible()
    expect(screen.getByText('0.3.0 · 4.0 KB')).toBeVisible()
    expect(bridge.getAppUpdateState).toHaveBeenLastCalledWith(true)
  })

  it('版本管理关闭提醒后持久保存，重启不提示而手动检查可用', async () => {
    localStorage.removeItem(APP_UPDATE_REMINDER_ENABLED_KEY)
    const mounted = render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '查看更新' }))
    const toggle = await screen.findByRole('switch', { name: '每日更新提醒' })
    expect(toggle).toBeChecked()
    fireEvent.click(toggle)
    expect(localStorage.getItem(APP_UPDATE_REMINDER_ENABLED_KEY)).toBe('false')
    mounted.unmount()
    bridge.getAppUpdateState.mockClear()
    render(<App />)
    await openVersions()
    expect(screen.getByRole('switch', { name: '每日更新提醒' })).not.toBeChecked()
    fireEvent.click(await screen.findByRole('button', { name: '重新检查' }))
    await waitFor(() => expect(bridge.getAppUpdateState).toHaveBeenLastCalledWith(true))
    expect(screen.queryByRole('dialog', { name: '发现应用新版本' })).toBeNull()
  })

  it('离线检查失败不阻断启动或提醒开关', async () => {
    localStorage.removeItem(APP_UPDATE_REMINDER_ENABLED_KEY)
    bridge.getAppUpdateState.mockRejectedValue(new Error('offline'))
    render(<App />)
    await openVersions()
    await screen.findByText('暂时读不到应用更新状态：请检查网络后重试。')
    fireEvent.click(screen.getByRole('switch', { name: '每日更新提醒' }))
    expect(localStorage.getItem(APP_UPDATE_REMINDER_ENABLED_KEY)).toBe('false')
    // 更新检查失败既不拦启动，也不替用户打开 Harness。
    expect(bridge.openHarness).not.toHaveBeenCalled()
  })

  it('打开系统安装设置失败给出错误提示，不产生未处理拒绝', async () => {
    bridge.openAppUpdateInstallSettings.mockRejectedValueOnce(new Error('设置无法打开'))
    render(<App />)
    await openVersions()
    fireEvent.click(await screen.findByRole('button', { name: '去系统设置允许安装' }))
    expect(await screen.findByText('设置无法打开')).toBeVisible()
  })

  it('历史版本与应用更新长公告分别折叠，展开后保留纯文本全文', async () => {
    const notes = '<img src=x onerror=alert(1)>' + '历史公告'.repeat(100)
    bridge.listRuntimeReleases.mockResolvedValue({ entries: [{ version: '0.2.9', notes }] })
    render(<App />)
    await openVersions()
    const toggles = await screen.findAllByRole('button', { name: '展开完整更新公告' })
    expect(toggles).toHaveLength(2)
    expect(screen.queryByText(notes)).toBeNull()
    expect(screen.queryByText(cachedUpdate.available!.notes)).toBeNull()
    fireEvent.click(toggles[0])
    expect(screen.getByText(notes)).toBeVisible()
    expect(screen.queryByText(cachedUpdate.available!.notes)).toBeNull()
    expect(document.querySelector('.release-notes img')).toBeNull()
    fireEvent.click(toggles[1])
    expect(screen.getByText(cachedUpdate.available!.notes)).toBeVisible()
  })

  it('切换频道未结束时阻止下载和安装，返回后采用新候选', async () => {
    let finish!: (state: AppUpdateState) => void
    bridge.setAppUpdateChannel.mockImplementation(() => new Promise<AppUpdateState>(resolve => { finish = resolve }))
    render(<App />)
    await openVersions()
    await screen.findByText('0.3.0 · 4.0 KB')
    fireEvent.click(screen.getByRole('switch', { name: '接收测试版本' }))
    const download = screen.getByRole('button', { name: '下载更新' })
    const install = screen.getByRole('button', { name: '安装更新' })
    expect(download).toBeDisabled()
    expect(install).toBeDisabled()
    fireEvent.click(download)
    fireEvent.click(install)
    expect(bridge.downloadAppUpdate).not.toHaveBeenCalled()
    expect(bridge.installAppUpdate).not.toHaveBeenCalled()
    await act(async () => {
      finish({ ...cachedUpdate, channel: 'beta', available: { ...cachedUpdate.available!, version: '0.3.1-mobile-401' } })
      await Promise.resolve()
    })
    expect(screen.getByText('0.3.1-mobile-401 · 4.0 KB')).toBeVisible()
    expect(screen.getByRole('switch', { name: '接收测试版本' })).toBeChecked()
    expect(download).toBeEnabled()
    expect(install).toBeEnabled()
  })

  it('切换异常后回读原生实际频道和候选', async () => {
    bridge.setAppUpdateChannel.mockRejectedValueOnce(new Error('切换响应丢失'))
    render(<App />)
    await openVersions()
    await screen.findByText('0.3.0 · 4.0 KB')
    bridge.getAppUpdateState.mockResolvedValueOnce({
      ...cachedUpdate,
      channel: 'beta',
      available: { ...cachedUpdate.available!, version: '0.3.1-mobile-401' },
    })
    fireEvent.click(screen.getByRole('switch', { name: '接收测试版本' }))
    expect(await screen.findByText('0.3.1-mobile-401 · 4.0 KB')).toBeVisible()
    expect(screen.getByRole('switch', { name: '接收测试版本' })).toBeChecked()
    expect(screen.queryByText('0.3.0 · 4.0 KB')).toBeNull()
    expect(bridge.getAppUpdateState).toHaveBeenLastCalledWith(false)
    expect(screen.getByRole('button', { name: '下载更新' })).toBeEnabled()
  })

  it('切换异常且回读失败时清除旧候选，重试后才能继续更新', async () => {
    bridge.setAppUpdateChannel.mockRejectedValueOnce(new Error('切换响应丢失'))
    render(<App />)
    await openVersions()
    await screen.findByText('0.3.0 · 4.0 KB')
    bridge.getAppUpdateState.mockRejectedValueOnce(new Error('offline'))
    fireEvent.click(screen.getByRole('switch', { name: '接收测试版本' }))
    expect(await screen.findByText('暂时读不到应用更新状态：请检查网络后重试。')).toBeVisible()
    expect(screen.queryByText('0.3.0 · 4.0 KB')).toBeNull()
    expect(screen.queryByRole('button', { name: '下载更新' })).toBeNull()
    expect(screen.queryByRole('button', { name: '安装更新' })).toBeNull()
    expect(screen.getByRole('switch', { name: '接收测试版本' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('0.3.0 · 4.0 KB')).toBeVisible()
    expect(bridge.getAppUpdateState).toHaveBeenLastCalledWith(true)
    expect(screen.getByRole('button', { name: '下载更新' })).toBeEnabled()
  })
})
