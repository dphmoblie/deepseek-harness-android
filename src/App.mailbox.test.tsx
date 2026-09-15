import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { availableMailbox, beforeEachAppTest, bridge, openSettingsPage, storageAccess, unavailableMailbox } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 投递区（「运行与后台」页里的工作区导入/导出）。
 *
 * 从原来的单文件 `App.test.tsx` 按 view 拆出（登记册 5.6-J）。
 * 这一组的共同前提是「档位如实显示」：不可用/不支持/读取失败三种降级各有各的文案与按钮状态，
 * 混在启动与设置用例中间时，改一处档位很容易漏测另一处。
 */
describe('投递区', () => {
  beforeEach(beforeEachAppTest)

  it('未授予存储权限时如实显示需要授权、禁用两个按钮并给出授权入口', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.getMailboxState).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')

    // 档位与原因都如实显示：不把「需要授权」写成「故障」。
    expect(await screen.findByText('需要授权')).toBeVisible()
    expect(screen.getByText('投递区不可用')).toBeVisible()
    expect(screen.getByText(/尚未授予「所有文件访问」/)).toBeVisible()

    // 两个路径固定写在界面里，用户照着往 inbox 放文件。
    expect(screen.getByText('/storage/emulated/0/Documents/DSH/inbox')).toBeVisible()
    expect(screen.getByText('/storage/emulated/0/Documents/DSH/outbox')).toBeVisible()
    expect(screen.getByText('/mnt/inbox · /mnt/outbox')).toBeVisible()

    // 不可用时一键按钮必须禁用，且不能因为「点了没反应」而消失。
    expect(screen.getByRole('button', { name: /导入到工作区/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /导出工作区/ })).toBeDisabled()
    // 授权入口可见（系统支持这一档），但文案不承诺一定授权成功。
    const permissionEntry = screen.getByRole('button', { name: /去开启「所有文件访问」/ })
    expect(permissionEntry).toBeEnabled()

    fireEvent.click(permissionEntry)
    await waitFor(() => expect(bridge.openAllFilesAccessSettings).toHaveBeenCalledTimes(1))
    expect(bridge.importMailbox).not.toHaveBeenCalled()
    expect(bridge.exportMailbox).not.toHaveBeenCalled()
  })

  it('系统不支持「所有文件访问」时不给授权入口，改为说明走控制台上传', async () => {
    bridge.getMailboxState.mockResolvedValue({
      ...unavailableMailbox,
      availability: 'unsupported',
      supported: false,
    })
    bridge.getStorageAccessState.mockResolvedValue({ ...storageAccess, allFilesSupported: false })

    render(<App />)
    await waitFor(() => expect(bridge.getMailboxState).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')

    expect(await screen.findByText('不支持')).toBeVisible()
    expect(screen.getByText(/不存在「所有文件访问」这一档/)).toBeVisible()
    // 系统根本没有这一档：不提供会跳到空页面的入口。
    expect(screen.queryByRole('button', { name: /去开启「所有文件访问」/ })).toBeNull()
    expect(screen.getByRole('button', { name: /导入到工作区/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /导出工作区/ })).toBeDisabled()
  })

  it('可用时两个按钮才可点，并且只有点击才搬运', async () => {
    bridge.getMailboxState.mockResolvedValue({ ...availableMailbox })
    bridge.getStorageAccessState.mockResolvedValue({ ...storageAccess, allFilesGranted: true })
    bridge.importMailbox.mockResolvedValue({
      entryCount: 3,
      fileCount: 2,
      directoryCount: 1,
      symlinkCount: 0,
      hardlinkCount: 0,
      bytes: 2048,
      tarName: 'dsh-workspace.tar',
      tarBytes: 4096,
      verified: true,
      manifestName: 'dsh-workspace.manifest.json',
      ignoredFiles: 1,
      target: 'mailbox-import',
    })
    bridge.exportMailbox.mockResolvedValue({
      entryCount: 5,
      bytes: 8192,
      tarName: 'dsh-workspace.tar',
      tarBytes: 10240,
      tarSha256: 'b'.repeat(64),
      manifestName: 'dsh-workspace.manifest.json',
      skippedLinks: 0,
      skippedSpecial: 0,
    })

    render(<App />)
    await waitFor(() => expect(bridge.getMailboxState).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')

    expect(await screen.findByText('可用')).toBeVisible()
    expect(screen.getByText(/可导入的 tar：dsh-workspace\.tar/)).toBeVisible()

    // 仅仅进页面不会搬运：任何自动执行都是不允许的。
    expect(bridge.importMailbox).not.toHaveBeenCalled()
    expect(bridge.exportMailbox).not.toHaveBeenCalled()

    const importButton = screen.getByRole('button', { name: /导入到工作区/ })
    const exportButton = screen.getByRole('button', { name: /导出工作区/ })
    expect(importButton).toBeEnabled()
    expect(exportButton).toBeEnabled()

    fireEvent.click(importButton)
    await waitFor(() => expect(bridge.importMailbox).toHaveBeenCalledTimes(1))
    // 最近一次结果：条目数、字节数与 manifest 文件名。
    expect(await screen.findByText(/最近一次导入：3 个条目 · 2\.0 KB · manifest dsh-workspace\.manifest\.json/)).toBeVisible()

    fireEvent.click(exportButton)
    await waitFor(() => expect(bridge.exportMailbox).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/最近一次导出：5 个条目 · 8\.0 KB · manifest dsh-workspace\.manifest\.json/)).toBeVisible()
  })

  it('缺少 manifest 的导入如实显示「未附带」而不是编造文件名', async () => {
    bridge.getMailboxState.mockResolvedValue({ ...availableMailbox })
    bridge.importMailbox.mockResolvedValue({
      entryCount: 1,
      fileCount: 1,
      directoryCount: 0,
      symlinkCount: 0,
      hardlinkCount: 0,
      bytes: 4,
      tarName: 'loose.tar',
      tarBytes: 10240,
      verified: false,
      ignoredFiles: 0,
      target: 'mailbox-import',
    })

    render(<App />)
    await waitFor(() => expect(bridge.getMailboxState).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')
    fireEvent.click(await screen.findByRole('button', { name: /导入到工作区/ }))

    await waitFor(() => expect(bridge.importMailbox).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/最近一次导入：1 个条目 · 4 B · manifest 未附带/)).toBeVisible()
  })

  it('状态读取失败时显示读取失败并可重试，不把失败当成不可用', async () => {
    bridge.getMailboxState.mockRejectedValue(new Error('投递区状态格式无效'))

    render(<App />)
    await waitFor(() => expect(bridge.getMailboxState).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')

    expect(await screen.findByText('无法读取投递区状态，请重试')).toBeVisible()
    expect(screen.getByText('未读取')).toBeVisible()
    // 状态未知时搬运按钮保持禁用：宁可让用户先重试，也不要出现「按钮可点但状态未知」。
    expect(screen.getByRole('button', { name: /导入到工作区/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /导出工作区/ })).toBeDisabled()

    bridge.getMailboxState.mockResolvedValue({ ...availableMailbox })
    bridge.getStorageAccessState.mockResolvedValue({ ...storageAccess, allFilesGranted: true })
    fireEvent.click(screen.getByRole('button', { name: /重新检查/ }))
    expect(await screen.findByText('可用')).toBeVisible()
    expect(screen.getByRole('button', { name: /导入到工作区/ })).toBeEnabled()
  })
})
