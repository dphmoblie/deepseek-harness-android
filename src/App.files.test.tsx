import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { availableMailbox, beforeEachAppTest, bridge, openFilesPage, storageAccess } from './__tests__/appTestHarness'
import type { StorageDirEntry, StorageDirsState } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 「文件管理」一级页：把投递区与共享目录收敛到**同一个浏览器**，但协议动作仍然只属于投递区。
 *
 * 这一组的判据是那三条边界（用户认可的方向，ref m01855）：
 *   ① 浏览层只有一个：一个根选择器、一份面包屑、一份条目列表，两类根共用；
 *   ② 「导入到工作区」「导出工作区」只在浏览投递区根时出现，进了共享目录必须消失；
 *   ③ 底层两条链路不串：投递区走 createMailboxFolder，共享目录走 createStorageFolder。
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

    // 标题与页首说明：这一页的存在理由要写在最前面。
    expect(screen.getByRole('heading', { level: 1, name: '文件管理' })).toBeVisible()
    expect(screen.getByText(/这里能看到所有对外目录/)).toBeVisible()

    // 只有一个浏览器：投递区两个根 + 每条共享目录一个根，全在同一组标签页里。
    const tablists = screen.getAllByRole('tablist')
    expect(tablists).toHaveLength(1)
    const tabs = within(tablists[0]).getAllByRole('tab').map(tab => tab.textContent)
    expect(tabs).toEqual(['inbox · 用户放入', 'outbox · 产物取出', 'DSH-1'])

    // 两类根各自的专属区块都在：浏览层合并了，语义没有合并。
    expect(screen.getByRole('region', { name: '投递区' })).toBeVisible()
    expect(screen.getByRole('region', { name: '共享目录' })).toBeVisible()

    // 返回键回到设置首页（它是一级页，不是设置里的二级页）。
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { level: 1, name: '设置' })).toBeVisible()
    expect(screen.queryByRole('heading', { level: 1, name: '文件管理' })).toBeNull()
  })

  it('搬运按钮只在投递区根上出现，进共享目录就消失', async () => {
    bridge.getMailboxState.mockResolvedValue({ ...availableMailbox })
    bridge.getStorageAccessState.mockResolvedValue({ ...storageAccess, allFilesGranted: true })
    bridge.getStorageDirs.mockResolvedValue(dirsState([dirEntry()]))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    await openFilesPage()

    // 默认根是投递区 inbox：协议动作在这里。
    expect(await screen.findByRole('button', { name: /导入到工作区/ })).toBeVisible()
    expect(screen.getByRole('button', { name: /导出工作区/ })).toBeVisible()

    fireEvent.click(screen.getByRole('tab', { name: 'DSH-1' }))
    await waitFor(() => expect(bridge.getStorageDirectory).toHaveBeenCalledWith('/mnt/user/1', undefined))

    // 共享目录是实时挂载：没有搬运这一步，按钮就不能摆在这里。
    expect(screen.queryByRole('button', { name: /导入到工作区/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /导出工作区/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /导出到当前目录/ })).toBeNull()
    expect(screen.queryByRole('region', { name: '投递区' })).toBeNull()
    // 但白名单管理仍然在这一页：发现目录不在列表里时不用跳回另一页。
    expect(screen.getByRole('region', { name: '共享目录' })).toBeVisible()
    expect(screen.getByRole('button', { name: /添加目录/ })).toBeVisible()

    // 切回投递区，按钮回来。
    fireEvent.click(screen.getByRole('tab', { name: /inbox · 用户放入/ }))
    expect(await screen.findByRole('button', { name: /导入到工作区/ })).toBeVisible()
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
    expect(alert).toHaveTextContent('无法读取当前共享目录，请重试')
    // 读失败不能说成「目录是空的」：空目录与读不到是两件事。
    expect(screen.queryByText('当前目录为空')).toBeNull()

    fireEvent.click(within(alert).getByRole('button', { name: '重试' }))
    await waitFor(() => expect(bridge.getStorageDirectory).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('照片')).toBeVisible()
    expect(screen.queryByText('无法读取当前共享目录，请重试')).toBeNull()
  })
})
