import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge } from './__tests__/appTestHarness'
import { APP_UPDATE_REMINDER_ENABLED_KEY } from './appUpdateReminder'
import type { AppUpdateState } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({ TerminalPanel: () => <div /> }))
import { App } from './App'

const cachedUpdate: AppUpdateState = {
  installedVersion: '0.2.0', installedVersionCode: 22, installAllowed: false,
  cached: true, checkedAt: 1_700_000_000_000,
  available: { version: '0.3.0', bytes: 4096, sha256: 'a'.repeat(64), notes: '公告正文'.repeat(100) },
}

async function openVersions(): Promise<void> {
  await screen.findByRole('heading', { name: '设置' })
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
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
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
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
  })

  it('两处公告独立折叠，长公告不掩盖更新按钮', async () => {
    bridge.listRuntimeReleases.mockResolvedValue({ entries: [{
      version: '0.3.0-runtime', notes: '历史公告'.repeat(100),
    }] })
    render(<App />)
    await openVersions()
    fireEvent.click(await screen.findByRole('button', { name: '检查可用版本' }))
    await waitFor(() => expect(screen.getAllByRole('button', { name: '展开完整更新公告' })).toHaveLength(2))
    const toggles = screen.getAllByRole('button', { name: '展开完整更新公告' })
    expect(toggles[0]).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggles[0])
    expect(screen.getByRole('region', { name: '完整更新公告' })).toHaveTextContent('历史公告'.repeat(100))
    expect(toggles[1]).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('button', { name: '下载更新' })).toBeEnabled()
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
    expect(bridge.openHarness).toHaveBeenCalledTimes(1)
  })

  it('打开系统安装设置失败给出错误提示，不产生未处理拒绝', async () => {
    bridge.openAppUpdateInstallSettings.mockRejectedValueOnce(new Error('设置无法打开'))
    render(<App />)
    await openVersions()
    fireEvent.click(await screen.findByRole('button', { name: '去系统设置允许安装' }))
    expect(await screen.findByText('设置无法打开')).toBeVisible()
  })
})
