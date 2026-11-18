import { fireEvent, render, screen } from '@testing-library/react'
import { arriveAtSettingsHome, beforeEachAppTest, bridge } from './__tests__/appTestHarness'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 回归保护：版本管理页已按决策移除「本地版本库」「在线可用版本」与「清理」卡片，
 * 即使旧桥返回了 library 或远端条目也不再渲染这些入口，且进页不会拉取远端运行时目录。
 */
describe('版本管理：不恢复已移除的运行时版本库与清理卡片', () => {
  beforeEach(beforeEachAppTest)

  it('即使原生返回 library 条目，版本管理页也仅展示槽位而不渲染版本库与清理卡', async () => {
    bridge.getRuntimeVersions.mockResolvedValue({
      versions: [
        { slot: 'current', version: '2026.09.15', dshVersion: '0.1.7-rc.2', runtimeId: 'rt-1', extractedBytes: 960 * 1024 * 1024, active: true },
        { slot: 'bundled', version: '2026.08.01', dshVersion: '0.1.0', runtimeId: 'rt-0', extractedBytes: 0, active: false },
      ],
      library: [
        {
          id: 'a'.repeat(64),
          version: '2026.09.10',
          dshVersion: '0.1.6',
          bytes: 310 * 1024 * 1024,
          active: false,
          pinned: false,
          downloadedAt: '2026-09-10T03:04:05Z',
        },
      ],
      libraryCapacity: 5,
      canSwitch: true,
      canDelete: true,
    })

    render(<App />)
    await arriveAtSettingsHome()
    fireEvent.click(await screen.findByRole('button', { name: /版本管理/ }, { timeout: 5_000 }))
    await screen.findByRole('heading', { name: '版本管理' }, { timeout: 5_000 })

    expect(await screen.findByRole('heading', { name: '运行时版本管理' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '本地版本库' })).toBeNull()
    expect(screen.queryByRole('heading', { name: '在线可用版本' })).toBeNull()
    expect(screen.queryByRole('heading', { name: '清理' })).toBeNull()
    expect(screen.queryByRole('button', { name: /锁定/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /解锁/ })).toBeNull()
    expect(bridge.listRuntimeReleases).not.toHaveBeenCalled()
  })
})
