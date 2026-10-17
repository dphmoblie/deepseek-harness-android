import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { arriveAtSettingsHome } from './__tests__/appTestHarness'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 「本地版本库」的锁定与淘汰提示。
 *
 * 这里守的是用户最在意的那件事：**我锁好的版本不会被悄悄删掉**。
 * 界面上「锁定 / 解锁」按钮与「满额且全部锁定」的提示，都是这条语义的出口；
 * 真正落盘的淘汰动作在原生侧（`RuntimeLibraryPolicyTest` 穷举规则）。
 */

const first = 'a'.repeat(64)
const second = 'b'.repeat(64)

/** 版本库条目：默认未锁定、非当前使用。 */
function entry(id: string, version: string, pinned = false, active = false) {
  return {
    id,
    version,
    dshVersion: '0.1.6',
    bytes: 310 * 1024 * 1024,
    active,
    pinned,
    downloadedAt: '2026-09-10T03:04:05Z',
  }
}

/** 空闲状态：能切换、能删除，版本库内容由用例给。 */
function versions(library: unknown[], libraryCapacity = 5) {
  return {
    versions: [
      { slot: 'current', version: '2026.09.15', dshVersion: '0.1.7-rc.2', runtimeId: 'rt-1', extractedBytes: 960 * 1024 * 1024, active: true },
      { slot: 'bundled', version: '2026.08.01', dshVersion: '0.1.0', runtimeId: 'rt-0', extractedBytes: 0, active: false },
    ],
    library,
    libraryCapacity,
    canSwitch: true,
    canDelete: true,
  }
}

async function openVersionsPage(): Promise<void> {
  render(<App />)
  await arriveAtSettingsHome()
    await screen.findByRole('heading', { name: '设置' }, { timeout: 5_000 })
  fireEvent.click(await screen.findByRole('button', { name: /版本管理/ }, { timeout: 5_000 }))
  await screen.findByRole('heading', { name: '版本管理' }, { timeout: 5_000 })
}

describe('版本管理：本地版本库的锁定与淘汰提示', () => {
  beforeEach(beforeEachAppTest)

  it('未锁定条目显示「锁定」，点一下就把 pinned=true 交给桥接并按回执改口', async () => {
    bridge.getRuntimeVersions.mockResolvedValue(versions([entry(first, '2026.09.10')]))
    bridge.setRuntimeVersionPinned.mockResolvedValue(versions([entry(first, '2026.09.10', true)]))

    await openVersionsPage()

    const pinButton = await screen.findByRole('button', { name: /锁定/ })
    expect(screen.queryByText('已锁定')).toBeNull()

    fireEvent.click(pinButton)

    // 目标只可能是版本库条目 ID：传槽位名等于锁了一个不存在的东西。
    await waitFor(() => expect(bridge.setRuntimeVersionPinned).toHaveBeenCalledWith(first, true))
    expect(await screen.findByText('已锁定')).toBeVisible()
    expect(await screen.findByText('已锁定该版本')).toBeVisible()
    expect(await screen.findByRole('button', { name: /解锁/ })).toBeVisible()
  })

  it('已锁定条目显示「已锁定」，解锁时把 pinned=false 交出去', async () => {
    bridge.getRuntimeVersions.mockResolvedValue(versions([entry(first, '2026.09.10', true)]))
    bridge.setRuntimeVersionPinned.mockResolvedValue(versions([entry(first, '2026.09.10', false)]))

    await openVersionsPage()

    expect(await screen.findByText('已锁定')).toBeVisible()
    fireEvent.click(await screen.findByRole('button', { name: /解锁/ }))

    await waitFor(() => expect(bridge.setRuntimeVersionPinned).toHaveBeenCalledWith(first, false))
    expect(await screen.findByText('已解锁该版本')).toBeVisible()
    // 解锁后再点就是重新锁上：按钮文字回到「锁定」，不能停在「解锁」。
    expect(await screen.findByRole('button', { name: /锁定/ })).toBeVisible()
  })

  it('满额且全部锁定时，提示下载会被明确拒绝，而不是说会自动替换', async () => {
    const full = [first, second, 'c'.repeat(64), 'd'.repeat(64), 'e'.repeat(64)]
      .map((id, index) => entry(id, `2026.09.1${index}`, true))
    bridge.getRuntimeVersions.mockResolvedValue(versions(full))

    await openVersionsPage()

    expect(await screen.findByText(/版本库已满（5 个），且全部已锁定/)).toBeVisible()
    expect(screen.queryByText(/下载新版本会给未锁定的最旧版本腾位置/)).toBeNull()
  })

  it('满额但有未锁定版本时，说的是给未锁定的最旧版本腾位置', async () => {
    const full = [first, second, 'c'.repeat(64), 'd'.repeat(64), 'e'.repeat(64)]
      .map((id, index) => entry(id, `2026.09.2${index}`, index < 4))
    bridge.getRuntimeVersions.mockResolvedValue(versions(full))

    await openVersionsPage()

    expect(await screen.findByText(/下载新版本会给未锁定的最旧版本腾位置/)).toBeVisible()
    expect(screen.queryByText(/且全部已锁定/)).toBeNull()
  })
})
