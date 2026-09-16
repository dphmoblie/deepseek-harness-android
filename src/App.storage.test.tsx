import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEachAppTest, bridge, openSettingsPage } from './__tests__/appTestHarness'
import type { StorageDirEntry, StorageDirsState } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 共享目录（「运行与后台」页里的 ≤8 条目录白名单，登记册 §5.1）。
 *
 * 与投递区分开一个文件（`App.mailbox.test.tsx`）的理由与当初拆分的理由一样：这一组的
 * 共同前提是**「什么叫失败」**——用户取消不是失败、条目不可用不是失败、读取失败也不是
 * 「没有目录」。混在投递区用例里改一处档位很容易漏测另一处。
 *
 * 所有断言都限定在区块内（`getByRole('region', { name: '共享目录' })`）：这一页上
 * 「可用」「不可用」「需要授权」这些词在设备 Shell、投递区各有一份，全局查询会指错元素。
 */

/** 区块容器：`<section aria-labelledby>` 有可访问名，因此按 region 查是稳定的。 */
async function openStorageSection(): Promise<HTMLElement> {
  await openSettingsPage('运行与后台')
  return screen.findByRole('region', { name: '共享目录' })
}

/**
 * 白名单状态夹具。
 *
 * `count`/`level`/`active` 按契约从其余字段**推导**，不手写：这三条正是
 * `validateStorageDirsState` 会钉住的自洽关系，手写等于在夹具里埋一个非法载荷。
 */
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

/** 桥错误：Capacitor 过桥后码在 `error.code` 上（`native-bridge.js` 把 {message, code} 拷进 Error）。 */
function bridgeError(message: string, code?: string): Error {
  const error = new Error(message)
  if (code !== undefined) Object.assign(error, { code })
  return error
}

describe('共享目录（目录白名单）', () => {
  beforeEach(beforeEachAppTest)

  it('未授予「所有文件访问」时禁用添加，并给出可点的开启入口', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([], { supported: true, granted: false }))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    const section = await openStorageSection()

    // 数量与上限照实显示：上限来自平台层常量（与原生侧同一个数）。
    expect(within(section).getByText('0/8')).toBeVisible()
    expect(within(section).getByText('需要授权')).toBeVisible()
    expect(within(section).getByText('T0 · 未授予「所有文件访问」')).toBeVisible()
    expect(within(section).getByText('目录白名单当前不可用')).toBeVisible()
    expect(within(section).getByText(/还没有「所有文件访问」权限/)).toBeVisible()

    // 没有权限时不能选目录；入口必须可点，否则用户被锁死在原地。
    expect(within(section).getByRole('button', { name: /添加目录/ })).toBeDisabled()
    const permissionEntry = within(section).getByRole('button', { name: /去系统设置开启所有文件访问/ })
    expect(permissionEntry).toBeEnabled()

    fireEvent.click(permissionEntry)
    await waitFor(() => expect(bridge.openAllFilesAccessSettings).toHaveBeenCalledTimes(1))
    // 只是跳系统设置：不能顺手写白名单。
    expect(bridge.addStorageDirectory).not.toHaveBeenCalled()
    expect(bridge.removeStorageDirectory).not.toHaveBeenCalled()
  })

  it('系统没有「所有文件访问」这一档时说明代价：访客内不提供共享存储', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([], { supported: false, granted: false }))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    const section = await openStorageSection()

    expect(within(section).getByText('系统不支持')).toBeVisible()
    expect(within(section).getByText('T0 · 本机没有这一档')).toBeVisible()
    // 如实说明这是能力回退（不是故障），并且不承诺授权后可用。
    expect(within(section).getByText(/这些设备上访客内不提供共享存储/)).toBeVisible()
    expect(within(section).getByRole('button', { name: /添加目录/ })).toBeDisabled()
    // 系统根本没有这一档：不提供会跳到空页面的入口。
    expect(within(section).queryByRole('button', { name: /去系统设置开启所有文件访问/ })).toBeNull()
  })

  it('已授权且为空时显示 0/8，添加按钮可用', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([]))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    const section = await openStorageSection()

    expect(within(section).getByText('0/8')).toBeVisible()
    expect(within(section).getByText('已授权')).toBeVisible()
    expect(within(section).getByText('T2 · 所有文件访问（可读可写）')).toBeVisible()
    expect(within(section).getByRole('button', { name: /添加目录/ })).toBeEnabled()
  })

  it('添加目录：调用桥的新增方法，并用返回值刷新界面', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([]))
    bridge.addStorageDirectory.mockResolvedValue(dirsState([dirEntry({ index: 1 })]))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    const section = await openStorageSection()

    fireEvent.click(within(section).getByRole('button', { name: /添加目录/ }))
    await waitFor(() => expect(bridge.addStorageDirectory).toHaveBeenCalledTimes(1))

    // 界面以桥返回的最新状态为准：数量、显示名、访客内挂载点、宿主路径都来自它。
    expect(await within(section).findByText('1/8')).toBeVisible()
    expect(within(section).getByText('DSH-1')).toBeVisible()
    expect(within(section).getByText('/mnt/user/1')).toBeVisible()
    expect(within(section).getByText('/storage/emulated/0/Documents/DSH-1')).toBeVisible()
  })

  it('移除目录：按宿主路径调用桥，并用返回值刷新界面（失效条目留下的空号不重排）', async () => {
    const first = dirEntry({ index: 1 })
    const second = dirEntry({ index: 2 })
    bridge.getStorageDirs.mockResolvedValue(dirsState([first, second]))
    // 原生侧删掉第 1 条后返回的最新状态：第 2 条仍然是 /mnt/user/2（序号来自持久化顺序）。
    bridge.removeStorageDirectory.mockResolvedValue(dirsState([second]))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    const section = await openStorageSection()

    expect(within(section).getByText('2/8')).toBeVisible()
    const firstRow = within(section).getByText('/mnt/user/1').closest('.storage-dir-row')
    expect(firstRow).not.toBeNull()
    fireEvent.click(within(firstRow as HTMLElement).getByRole('button', { name: /移除/ }))

    // 定位用**宿主路径**而不是序号：序号会随增删变化，用序号可能删掉另一条。
    await waitFor(() => expect(bridge.removeStorageDirectory).toHaveBeenCalledWith(first.path))
    expect(await within(section).findByText('1/8')).toBeVisible()
    expect(within(section).getByText('/mnt/user/2')).toBeVisible()
    expect(within(section).queryByText('/mnt/user/1')).toBeNull()
  })

  it('用户在系统选择器里取消：不提示错误、界面保持原状', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([]))
    bridge.addStorageDirectory.mockRejectedValue(bridgeError('已取消目录选择', 'STORAGE_DIR_CANCELLED'))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    const section = await openStorageSection()

    fireEvent.click(within(section).getByRole('button', { name: /添加目录/ }))
    await waitFor(() => expect(bridge.addStorageDirectory).toHaveBeenCalledTimes(1))

    // 取消不是故障：一条提示都不该出现（错误提示是 role=alert 的 toast）。
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByText(/已取消目录选择/)).toBeNull()
    expect(within(section).getByText('0/8')).toBeVisible()
    // 取消后按钮要能再点（busy 必须已经清掉），否则用户以为界面卡住了。
    await waitFor(() => expect(within(section).getByRole('button', { name: /添加目录/ })).toBeEnabled())
  })

  it('每个受控错误码都给可操作的中文说明，且不把码甩给用户', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([]))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    const section = await openStorageSection()

    // 与 App.tsx 的 STORAGE_DIR_ERROR_MESSAGES 一一对应：改文案就要改这里，
    // 免得某天有人把某个码删掉、界面悄悄退回兜底文案而没人发现。
    const cases: Array<[string, RegExp]> = [
      ['STORAGE_DIR_UNSUPPORTED', /本机系统没有「所有文件访问」这一档/],
      ['STORAGE_DIR_NEEDS_PERMISSION', /现在不能选新目录/],
      ['STORAGE_DIR_PICKER_UNAVAILABLE', /没有可用的目录选择器/],
      ['STORAGE_DIR_DOCUMENT_ID_INVALID', /不是可识别的目录/],
      ['STORAGE_DIR_VOLUME_UNSUPPORTED', /请从「内部共享存储」进入后再选目录/],
      ['STORAGE_DIR_ROOT_REJECTED', /不能选择共享存储的根目录/],
      ['STORAGE_DIR_OUTSIDE_PUBLIC', /不在本机的内部共享存储范围内/],
      ['STORAGE_DIR_ANDROID_REJECTED', /不能选择 Android\/ 目录及其子目录/],
      ['STORAGE_DIR_PRIVATE_REJECTED', /不能选择应用私有目录/],
      ['STORAGE_DIR_NOT_A_DIRECTORY', /已经不是真实目录/],
      ['STORAGE_DIR_UNRESOLVED', /解析成手机上的真实路径/],
      ['STORAGE_DIR_UNREADABLE', /这个目录当前不可读/],
      ['STORAGE_DIR_UNBINDABLE', /无法绑定进访客/],
      ['STORAGE_DIR_DUPLICATE', /已经在列表里了/],
      ['STORAGE_DIR_LIMIT_REACHED', /最多只能添加 8 个目录/],
      ['STORAGE_DIR_SAVE_FAILED', /白名单保存失败/],
      ['STORAGE_DIR_PATH_REQUIRED', /没有拿到要移除的目录路径/],
      ['STORAGE_DIR_NOT_FOUND', /已经不在白名单里了/],
      ['STORAGE_DIR_PATH_INVALID', /目录路径格式不符合要求/],
      ['STORAGE_DIR_PREFERENCES_INVALID', /本地记录已损坏/],
    ]

    for (const [code, pattern] of cases) {
      bridge.addStorageDirectory.mockRejectedValue(bridgeError(code, code))
      fireEvent.click(within(section).getByRole('button', { name: /添加目录/ }))

      const alert = await screen.findByRole('alert')
      expect(alert, code).toHaveTextContent(pattern)
      // 受控码只用于维护者排查，不给用户看：文案里不能出现裸码。
      expect(alert.textContent, code).not.toContain(code)
      // 每次失败后都回到可点状态，否则第二条码根本没被验证到。
      await waitFor(() => expect(within(section).getByRole('button', { name: /添加目录/ })).toBeEnabled())
    }
    // 覆盖的是全部受控码，不是「至少两个」。
    expect(cases.length).toBe(20)
  })

  it('未知错误码走兜底文案（带上码便于排查），拿不到码时如实显示原生说明', async () => {
    bridge.getStorageDirs.mockResolvedValue(dirsState([]))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    const section = await openStorageSection()
    const addButton = () => within(section).getByRole('button', { name: /添加目录/ })

    // 未知码：不猜含义，但把码留在文案里（用户截图反馈时维护者能定位）。
    bridge.addStorageDirectory.mockRejectedValue(bridgeError('未来才有的失败', 'STORAGE_DIR_FUTURE_CODE'))
    fireEvent.click(addButton())
    expect(await screen.findByRole('alert')).toHaveTextContent(/目录操作失败（错误码 STORAGE_DIR_FUTURE_CODE）/)
    await waitFor(() => expect(addButton()).toBeEnabled())

    // 完全取不到码（例如浏览器预览桥）：如实转述原生说明，不编一个码出来。
    bridge.addStorageDirectory.mockRejectedValue(bridgeError('浏览器预览不支持选择存储目录'))
    fireEvent.click(addButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('浏览器预览不支持选择存储目录')
  })

  it('不可用条目：显示原因码对应的说明，移除按钮仍可用（用户要能清掉失效条目）', async () => {
    const unbindable = dirEntry({
      index: 1,
      availability: 'unavailable',
      reasonCode: 'STORAGE_DIR_UNBINDABLE',
    })
    // 校验允许不可用条目**不带**原因码：这种情况必须如实说「没给原因」，不能编。
    const noReason = dirEntry({ index: 2, availability: 'unavailable' })
    bridge.getStorageDirs.mockResolvedValue(dirsState([unbindable, noReason]))
    bridge.removeStorageDirectory.mockResolvedValue(dirsState([noReason]))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    const section = await openStorageSection()

    expect(within(section).getAllByText('不可用')).toHaveLength(2)
    expect(within(section).getByText(/目录名里有运行时不支持的字符/)).toBeVisible()
    expect(within(section).getByText(/原生侧没有返回原因码/)).toBeVisible()
    // 不可用条目仍然占序号：挂载点会跳号（1、2 都在，但都不可用）。
    expect(within(section).getByText('/mnt/user/1')).toBeVisible()
    expect(within(section).getByText('/mnt/user/2')).toBeVisible()

    const firstRow = within(section).getByText('/mnt/user/1').closest('.storage-dir-row') as HTMLElement
    const removeButton = within(firstRow).getByRole('button', { name: /移除/ })
    expect(removeButton).toBeEnabled()
    fireEvent.click(removeButton)
    await waitFor(() => expect(bridge.removeStorageDirectory).toHaveBeenCalledWith(unbindable.path))
    // 失效条目同样显示「不可用」而不是变成可点，界面不因移除而错位。
    expect(await within(section).findByText('1/8')).toBeVisible()
  })

  it('状态读取失败时显示读取失败并可重试，不把失败显示成「没有目录」', async () => {
    bridge.getMailboxState.mockRejectedValue(new Error('投递区状态格式无效'))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    const section = await openStorageSection()

    // 三路（投递区 / 权限档 / 白名单）是同一批读取：读取失败时不留半个屏幕的旧值，
    // 也不能把「不知道」显示成「0 条目录」。
    expect(within(section).getByText('无法读取共享目录状态，请重试')).toBeVisible()
    expect(within(section).queryByText('0/8')).toBeNull()
    expect(within(section).getByRole('button', { name: /添加目录/ })).toBeDisabled()
  })

  it('重新检查会连同白名单一起重读', async () => {
    bridge.getMailboxState.mockRejectedValue(new Error('投递区状态格式无效'))

    render(<App />)
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(1))
    await openStorageSection()

    bridge.getMailboxState.mockResolvedValue({
      availability: 'available',
      level: 'T2',
      available: true,
      supported: true,
      granted: true,
      inboxPath: '/storage/emulated/0/Documents/DSH/inbox',
      outboxPath: '/storage/emulated/0/Documents/DSH/outbox',
      guestInboxPath: '/mnt/inbox',
      guestOutboxPath: '/mnt/outbox',
      inboxFileCount: 0,
      inboxTars: [],
      exportTarName: 'dsh-workspace.tar',
      exportManifestName: 'dsh-workspace.manifest.json',
      importDirectory: 'mailbox-import',
    })
    bridge.getStorageDirs.mockResolvedValue(dirsState([]))

    fireEvent.click(screen.getByRole('button', { name: /重新检查/ }))
    await waitFor(() => expect(bridge.getStorageDirs).toHaveBeenCalledTimes(2))
    const section = await screen.findByRole('region', { name: '共享目录' })
    expect(within(section).getByText('0/8')).toBeVisible()
    expect(within(section).getByText('已授权')).toBeVisible()
  })
})
