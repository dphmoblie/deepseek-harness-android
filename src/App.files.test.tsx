import { beforeEach, describe, expect, it, vi } from 'vitest'
import { arriveAtSettingsHome } from './__tests__/appTestHarness'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { availableMailbox, beforeEachAppTest, bridge, openFilesPage, storageAccess } from './__tests__/appTestHarness'
import type { StorageDirEntry, StorageDirsState } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 「文件管理」一级页：把投递区与共享目录统一到两张同款卡片，并实际合并工作区导入/导出功能。
 *
 * 这一组的判据：
 *   ① 卡片元素统一：外层只有两张 `files-card`（`外部文件与工作区` 与 `挂载目录`），权限入口全页只有一份；
 *   ② 功能真正合并：浏览投递区根（inbox/outbox）和浏览共享目录（`/mnt/user/<N>` 及子目录）都能直接导入/导出工作区归档；
 *   ③ 底层目录创建不串：投递区走 createMailboxFolder，共享目录走 createStorageFolder。
 */

/** 白名单状态夹具；`count`/`level`/`active` 从其余字段推导，手写等于埋一个非法载荷。 */
function dirsState(entries: StorageDirEntry[], overrides: Partial<StorageDirsState> = {}): StorageDirsState {
  const supported = overrides.supported ?? true
  const granted = overrides.granted ?? true
  return {
    entries,
    maxDirectories: 8,
    count: entries.length,
    supported,
    granted,
    level: supported && granted ? 'T2' : 'T0',
    active: entries.some(entry => entry.available),
    ...overrides,
  }
}

/** 单条目录夹具；序号与访客挂载点必须一致（校验会钉住 `/mnt/user/<序号>`）。 */
function dirEntry(overrides: Partial<StorageDirEntry> = {}): StorageDirEntry {
  const index = overrides.index ?? 1
  const availability = overrides.availability ?? 'available'
  return {
    index,
    path: `/storage/emulated/0/Documents/DSH-${index}`,
    displayName: `DSH-${index}`,
    guestPath: `/mnt/user/${index}`,
    availability,
    level: availability === 'available' ? 'T2' : 'T0',
    available: availability === 'available',
    ...overrides,
  }
}

describe('文件管理（投递区与共享目录同一个浏览器）', () => {
  beforeEach(beforeEachAppTest)

  it('是一个一级页：一个根选择器、两类根，返回键回设置首页', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([dirEntry()]))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    await openFilesPage()

    // 标题与统一卡片结构：只有两张统一卡片（外部文件与工作区、挂载目录）。
    expect(screen.getByRole('heading', { level: 1, name: '文件管理' })).toBeVisible()
    expect(screen.getByText(/这里的目录都挂载在访客中/)).toBeVisible()

    // 只有一个浏览器：投递区两个根 + 每条共享目录一个根，全在同一组标签页里。
    const tablists = screen.getAllByRole('tablist')
    expect(tablists).toHaveLength(1)
    const tabs = within(tablists[0]).getAllByRole('tab').map(tab => tab.textContent)
    expect(tabs).toEqual(['inbox · 用户放入', 'outbox · 产物取出', 'DSH-1'])

    // 两张统一卡片都在：内置投递目录与共享目录在「挂载目录」中复用同款条目元素。
    expect(screen.getByRole('region', { name: '外部文件与工作区' })).toBeVisible()
    const mountedCard = screen.getByRole('region', { name: '挂载目录' })
    expect(mountedCard).toBeVisible()
    expect(within(mountedCard).getAllByText('内置')).toHaveLength(2)
    expect(within(mountedCard).getAllByText('共享')).toHaveLength(1)

    // 返回键回到设置首页（它是一级页，不是设置里的二级页）。
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { level: 1, name: '设置' })).toBeVisible()
    expect(screen.queryByRole('heading', { level: 1, name: '文件管理' })).toBeNull()
  })

  it('投递区与共享目录合并支持工作区导入和导出，并在共享目录子目录生效', async () => {
    bridge.getMailboxState.mockResolvedValue({ ...availableMailbox })
    bridge.getStorageAccessState.mockResolvedValue({ ...storageAccess, allFilesGranted: true })
    bridge.getStorageDirs.mockResolvedValue(dirsState([dirEntry()]))
    bridge.getStorageDirectory.mockImplementation((guestPath: string, subdirectory?: string) => Promise.resolve({
      guestPath,
      path: subdirectory,
      entries: subdirectory === undefined
        ? [
          { name: 'backup', kind: 'directory' as const, bytes: 0 },
          { name: 'shared.tar', kind: 'file' as const, bytes: 4096 },
        ]
        : [{ name: 'nested.tar', kind: 'file' as const, bytes: 2048 }],
      truncated: false,
    }))
    bridge.importMailbox.mockResolvedValue({
      entryCount: 4,
      fileCount: 3,
      directoryCount: 1,
      symlinkCount: 0,
      hardlinkCount: 0,
      bytes: 2048,
      tarName: 'nested.tar',
      tarBytes: 2048,
      verified: true,
      manifestName: 'dsh-workspace.manifest.json',
      ignoredFiles: 0,
      target: 'mailbox-import',
    })
    bridge.exportMailbox.mockResolvedValue({
      entryCount: 6,
      bytes: 8192,
      tarName: 'dsh-workspace.tar',
      tarBytes: 10240,
      tarSha256: 'c'.repeat(64),
      manifestName: 'dsh-workspace.manifest.json',
      destinationDirectory: 'backup',
      skippedLinks: 0,
      skippedSpecial: 0,
    })

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    await openFilesPage()

    // 默认根是投递区 inbox：工作区导入/导出可用。
    expect(await screen.findByRole('button', { name: /导入到工作区/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /导出工作区/ })).toBeEnabled()

    // 切到共享目录 DSH-1 并进入子目录 backup：同样显示可导入 tar，且导入/导出直接面向该共享目录。
    fireEvent.click(screen.getByRole('tab', { name: 'DSH-1' }))
    await waitFor(() => expect(bridge.getStorageDirectory).toHaveBeenCalledWith('/mnt/user/1', undefined))
    expect(await screen.findByText(/可导入的 tar：shared\.tar/)).toBeVisible()

    fireEvent.click(screen.getByText('backup'))
    await waitFor(() => expect(bridge.getStorageDirectory).toHaveBeenCalledWith('/mnt/user/1', 'backup'))
    expect(await screen.findByText(/可导入的 tar：nested\.tar/)).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: /导入到工作区/ }))
    await waitFor(() => expect(bridge.importMailbox).toHaveBeenCalledWith({
      guestPath: '/mnt/user/1',
      sourceDirectory: 'backup',
    }))

    fireEvent.click(screen.getByRole('button', { name: /导出到当前目录/ }))
    await waitFor(() => expect(bridge.exportMailbox).toHaveBeenCalledWith(
      undefined,
      'backup',
      { guestPath: '/mnt/user/1' },
    ))
    // 导出后自动刷新当前共享目录列表，让新生成的 tar 立即可见。
    await waitFor(() => expect(bridge.getStorageDirectory).toHaveBeenCalledTimes(3))
  })

  it('共享目录用同一套面包屑与条目列表进入子目录', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([dirEntry()]))
    bridge.getStorageDirectory.mockImplementation((guestPath: string, subdirectory?: string) => Promise.resolve({
      guestPath,
      path: subdirectory,
      entries: subdirectory === undefined
        ? [
          { name: '照片', kind: 'directory' as const, bytes: 0 },
          { name: 'note.txt', kind: 'file' as const, bytes: 5 },
        ]
        : [],
      truncated: false,
    }))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    await openFilesPage()

    fireEvent.click(screen.getByRole('tab', { name: 'DSH-1' }))
    await waitFor(() => expect(bridge.getStorageDirectory).toHaveBeenCalledWith('/mnt/user/1', undefined))

    // 面包屑的根名用显示名（不是 /mnt/user/1），用户不需要记挂载点。
    const breadcrumb = await screen.findByRole('navigation', { name: '当前目录' })
    expect(within(breadcrumb).getByRole('button', { name: 'DSH-1' })).toBeVisible()
    // 条目只有一份实现：目录是可点的按钮，文件显示字节数。
    // 注：条目自带 role="listitem"，而 listitem 不支持「名字来自内容」，所以按文本取。
    expect(screen.getByText('照片')).toBeVisible()
    expect(within(screen.getByText('note.txt').closest('[role="listitem"]') as HTMLElement).getByText('5 B')).toBeVisible()

    fireEvent.click(screen.getByText('照片'))
    await waitFor(() => expect(bridge.getStorageDirectory).toHaveBeenCalledWith('/mnt/user/1', '照片'))
    expect(await screen.findByText('当前目录为空')).toBeVisible()
    expect(within(await screen.findByRole('navigation', { name: '当前目录' })).getByRole('button', { name: '照片' })).toBeVisible()
  })

  it('普通文件可以分享或交给其他应用打开，并按当前根传递受控相对路径', async () => {
    bridge.getMailboxState.mockResolvedValue({ ...availableMailbox })
    bridge.getMailboxDirectory.mockResolvedValue({
      root: 'inbox', path: undefined,
      entries: [{ name: 'report.pdf', kind: 'file', bytes: 12 }], truncated: false,
    })
    bridge.externalFileAction.mockResolvedValue(undefined)
    bridge.getStorageDirs.mockResolvedValue(dirsState([dirEntry()]))
    bridge.getStorageDirectory.mockResolvedValue({
      guestPath: '/mnt/user/1', path: undefined,
      entries: [{ name: 'summary.txt', kind: 'file', bytes: 7 }], truncated: false,
    })

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    await openFilesPage()

    fireEvent.click(screen.getByRole('button', { name: '其他应用打开 report.pdf' }))
    await waitFor(() => expect(bridge.externalFileAction).toHaveBeenCalledWith({
      source: 'mailbox', root: 'inbox', path: 'report.pdf', action: 'open',
    }))
    fireEvent.click(screen.getByRole('button', { name: '分享 report.pdf' }))
    await waitFor(() => expect(bridge.externalFileAction).toHaveBeenCalledWith({
      source: 'mailbox', root: 'inbox', path: 'report.pdf', action: 'share',
    }))

    fireEvent.click(screen.getByRole('tab', { name: 'DSH-1' }))
    await waitFor(() => expect(bridge.getStorageDirectory).toHaveBeenCalledWith('/mnt/user/1', undefined))
    expect(screen.getByRole('button', { name: '分享 summary.txt' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '分享 summary.txt' }))
    await waitFor(() => expect(bridge.externalFileAction).toHaveBeenCalledWith({
      source: 'storage', guestPath: '/mnt/user/1', path: 'summary.txt', action: 'share',
    }))
  })

  /**
   * 「文件不存在」的真身：列表快照过期（文件被移走，或工作区导出那一步没落地）。
   * 界面必须做两件事——把目录重读一遍，并给出能自助的说明；原样甩一句「文件不存在或不可用」
   * 等于让用户对着一个已经不存在的条目反复点。
   */
  it('文件在复制时已经不在目录里：重读当前目录并给出过期说明', async () => {
    bridge.getMailboxState.mockResolvedValue({ ...availableMailbox })
    bridge.getMailboxDirectory.mockResolvedValue({
      root: 'outbox', path: undefined,
      entries: [{ name: 'dsh-workspace.tar', kind: 'file', bytes: 12 }], truncated: false,
    })
    bridge.getStorageDirs.mockResolvedValue(dirsState([dirEntry()]))
    bridge.getStorageDirectory.mockResolvedValue({
      guestPath: '/mnt/user/1', path: undefined, entries: [], truncated: false,
    })
    bridge.externalFileAction.mockRejectedValue(
      Object.assign(new Error('文件不存在或不可用'), { code: 'MAILBOX_DIRECTORY_NOT_FOUND' }),
    )

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    await openFilesPage()
    fireEvent.click(screen.getByRole('tab', { name: /outbox · 产物取出/ }))
    await waitFor(() => expect(bridge.getMailboxDirectory).toHaveBeenCalledWith('outbox', undefined))
    const readsBefore = bridge.getMailboxDirectory.mock.calls.length

    fireEvent.click(screen.getByRole('button', { name: '分享 dsh-workspace.tar' }))

    // 过期条目：目录被重读（列表随之刷新），提示里说清「已不在当前目录」而不是只报错。
    await waitFor(() => expect(bridge.getMailboxDirectory.mock.calls.length).toBeGreaterThan(readsBefore))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('文件已不在当前目录')
  })

  it('其它失败（例如没有应用能打开）原样报出，不冒充成「文件没了」', async () => {
    bridge.getMailboxState.mockResolvedValue({ ...availableMailbox })
    bridge.getMailboxDirectory.mockResolvedValue({
      root: 'inbox', path: undefined,
      entries: [{ name: 'report.pdf', kind: 'file', bytes: 12 }], truncated: false,
    })
    bridge.getStorageDirs.mockResolvedValue(dirsState([dirEntry()]))
    bridge.getStorageDirectory.mockResolvedValue({
      guestPath: '/mnt/user/1', path: undefined, entries: [], truncated: false,
    })
    bridge.externalFileAction.mockRejectedValue(
      Object.assign(new Error('没有应用可以处理此文件'), { code: 'EXTERNAL_FILE_NO_HANDLER' }),
    )

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    await openFilesPage()
    const readsBefore = bridge.getMailboxDirectory.mock.calls.length

    fireEvent.click(screen.getByRole('button', { name: '其他应用打开 report.pdf' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('没有应用可以处理此文件')
    expect(alert).not.toHaveTextContent('文件已不在当前目录')
    // 不是过期条目：不该顺手把目录再读一遍。
    expect(bridge.getMailboxDirectory.mock.calls.length).toBe(readsBefore)
  })

  it('在共享目录里新建文件夹走 createStorageFolder，不会串到投递区', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([dirEntry()]))
    bridge.getStorageDirectory.mockResolvedValue({
      guestPath: '/mnt/user/1',
      path: undefined,
      entries: [{ name: '照片', kind: 'directory', bytes: 0 }],
      truncated: false,
    })
    bridge.createStorageFolder.mockResolvedValue({
      guestPath: '/mnt/user/1',
      path: undefined,
      entries: [{ name: '2026-09', kind: 'directory', bytes: 0 }],
      truncated: false,
    })

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    await openFilesPage()

    fireEvent.click(screen.getByRole('tab', { name: 'DSH-1' }))
    await waitFor(() => expect(bridge.getStorageDirectory).toHaveBeenCalledWith('/mnt/user/1', undefined))

    const prompt = vi.spyOn(window, 'prompt').mockReturnValue('2026-09')
    fireEvent.click(screen.getByRole('button', { name: /新建文件夹/ }))
    await waitFor(() => expect(bridge.createStorageFolder).toHaveBeenCalledWith('/mnt/user/1', '2026-09'))
    // 两条链路不串：共享目录下建文件夹绝不能碰投递区。
    expect(bridge.createMailboxFolder).not.toHaveBeenCalled()
    // 新快照要立刻生效，用户不该自己点一次刷新。
    expect(await screen.findByText('2026-09')).toBeVisible()
    prompt.mockRestore()
  })

  it('读共享目录失败时如实提示，并且重试真的再读一次', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([dirEntry()]))
    bridge.getStorageDirectory
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue({
        guestPath: '/mnt/user/1',
        path: undefined,
        entries: [{ name: '照片', kind: 'directory', bytes: 0 }],
        truncated: false,
      })

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    await openFilesPage()

    fireEvent.click(screen.getByRole('tab', { name: 'DSH-1' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('无法读取当前目录，请重试')
    // 读失败不能说成「目录是空的」：空目录与读不到是两件事。
    expect(screen.queryByText('当前目录为空')).toBeNull()

    fireEvent.click(within(alert).getByRole('button', { name: '重试' }))
    await waitFor(() => expect(bridge.getStorageDirectory).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('照片')).toBeVisible()
    expect(screen.queryByText('无法读取当前目录，请重试')).toBeNull()
  })
})

describe('Ubuntu 运行时设置', () => {
  beforeEach(beforeEachAppTest)

  it('不再在运行时设置页显示工作区文件管理入口', async () => {
    render(<App />)
  await arriveAtSettingsHome()
    fireEvent.click(screen.getByRole('button', { name: /Ubuntu 运行时/ }))
    expect(await screen.findByRole('heading', { name: 'Ubuntu 24.04' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '工作区文件' })).toBeNull()
    expect(screen.queryByRole('button', { name: '浏览文件' })).toBeNull()
  })
})
