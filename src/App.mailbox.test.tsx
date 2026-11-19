import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { availableMailbox, beforeEachAppTest, bridge, openFilesPage, storageAccess, unavailableMailbox } from './__tests__/appTestHarness'

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

    await openFilesPage()

    // 档位与原因都如实显示，全页只有一份权限提示与一个授权按钮。
    const browserCard = screen.getByRole('region', { name: '外部文件与工作区' })
    expect(await screen.findByText('需要授予「所有文件访问」')).toBeVisible()
    expect(browserCard).toHaveTextContent('需要授权')
    expect(screen.getByText(/授权后即可浏览、搬运工作区并挂载共享目录/)).toBeVisible()

    // 两个内置路径在挂载目录卡片中清晰列出，用户照着往 inbox 放文件。
    const mountedCard = screen.getByRole('region', { name: '挂载目录' })
    expect(mountedCard).toHaveTextContent('/storage/emulated/0/Documents/DSH/inbox')
    expect(mountedCard).toHaveTextContent('/storage/emulated/0/Documents/DSH/outbox')
    expect(mountedCard).toHaveTextContent('/mnt/inbox')
    expect(mountedCard).toHaveTextContent('/mnt/outbox')

    // 不可用时一键按钮必须禁用，且不能因为「点了没反应」而消失。
    expect(screen.getByRole('button', { name: /导入到工作区/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /导出工作区/ })).toBeDisabled()
    // 授权入口全页唯一一份且可点。
    const permissionButtons = screen.getAllByRole('button', { name: /去开启「所有文件访问」/ })
    expect(permissionButtons).toHaveLength(1)
    expect(permissionButtons[0]).toBeEnabled()

    fireEvent.click(permissionButtons[0])
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
    await openFilesPage()

    expect(await screen.findByText('当前系统不支持「所有文件访问」')).toBeVisible()
    expect(screen.getByText(/Android 11 以下不支持共享存储/)).toBeVisible()
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
    await openFilesPage()

    expect(await screen.findByText(/可导入的 tar：dsh-workspace\.tar/)).toBeVisible()
    expect(screen.getByRole('region', { name: '外部文件与工作区' })).toHaveTextContent('可用')

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

  it('可在投递区根目录间切换、进入子目录并创建分类文件夹', async () => {
    bridge.getMailboxState.mockResolvedValue({ ...availableMailbox })
    bridge.getStorageAccessState.mockResolvedValue({ ...storageAccess, allFilesGranted: true })
    bridge.getMailboxDirectory.mockImplementation((root: 'inbox' | 'outbox', path?: string) => ({
      root,
      path,
      entries: path === undefined
        ? [{ name: 'weekly', kind: 'directory' as const, bytes: 0 }, { name: 'readme.txt', kind: 'file' as const, bytes: 5 }]
        : [],
      truncated: false,
    }))
    bridge.createMailboxFolder.mockResolvedValue({
      root: 'inbox',
      path: undefined,
      entries: [{ name: 'new-folder', kind: 'directory', bytes: 0 }],
      truncated: false,
    })

    render(<App />)
    await waitFor(() => expect(bridge.getMailboxDirectory).toHaveBeenCalledWith('inbox', undefined))
    await openFilesPage()

    expect(await screen.findByText('weekly')).toBeVisible()
    fireEvent.click(screen.getByText('weekly'))
    await waitFor(() => expect(bridge.getMailboxDirectory).toHaveBeenCalledWith('inbox', 'weekly'))

    fireEvent.click(screen.getByRole('tab', { name: /outbox · 产物取出/ }))
    await waitFor(() => expect(bridge.getMailboxDirectory).toHaveBeenCalledWith('outbox', undefined))

    const prompt = vi.spyOn(window, 'prompt').mockReturnValue('new-folder')
    fireEvent.click(screen.getByRole('button', { name: /新建文件夹/ }))
    await waitFor(() => expect(bridge.createMailboxFolder).toHaveBeenCalledWith('outbox', 'new-folder'))
    prompt.mockRestore()
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
    await openFilesPage()
    fireEvent.click(await screen.findByRole('button', { name: /导入到工作区/ }))

    await waitFor(() => expect(bridge.importMailbox).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/最近一次导入：1 个条目 · 4 B · manifest 未附带/)).toBeVisible()
  })

  it('状态读取失败时显示读取失败并可重试，不把失败当成不可用', async () => {
    bridge.getMailboxState.mockRejectedValue(new Error('投递区状态格式无效'))

    render(<App />)
    await waitFor(() => expect(bridge.getMailboxState).toHaveBeenCalledTimes(1))
    await openFilesPage()

    expect(await screen.findByText('文件与目录状态读取失败，请重试')).toBeVisible()
    expect(screen.getByRole('region', { name: '外部文件与工作区' })).toHaveTextContent('未读取')
    // 状态未知时搬运按钮保持禁用：宁可让用户先重试，也不要出现「按钮可点但状态未知」。
    expect(screen.getByRole('button', { name: /导入到工作区/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /导出工作区/ })).toBeDisabled()

    bridge.getMailboxState.mockResolvedValue({ ...availableMailbox })
    bridge.getStorageAccessState.mockResolvedValue({ ...storageAccess, allFilesGranted: true })
    fireEvent.click(screen.getByRole('button', { name: /重新检查/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /导入到工作区/ })).toBeEnabled())
    expect(screen.getByRole('region', { name: '外部文件与工作区' })).toHaveTextContent('可用')
  })
})
