import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, readyState } from './__tests__/appTestHarness'
import type { RuntimeSessionSnapshotState } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 版本管理页在「双槽切换」之外的会话备份部分。
 *
 * 换掉运行时以后，旧 dsh 写的会话读不出来怎么办：先自动备份、能恢复，恢复是合并回填。
 * 与「运行时版本管理」「应用更新公告」同住一条导航链，所以按 view 单独成文件（登记册 5.6-J）。
 */

const snapshotId = 'snap-1700000001000-00000002'

const oneSnapshot: RuntimeSessionSnapshotState = {
  maxSnapshots: 3,
  maxBytes: 512 * 1024 * 1024,
  totalBytes: 4096,
  snapshots: [{
    id: snapshotId,
    createdAt: '2026-09-15T10:20:30Z',
    bytes: 4096,
    fileCount: 2,
    runtimeVersion: '2026.08.17',
    dshVersion: '0.1.5-rc.2',
  }],
}

/**
 * 进「版本管理」一级页：等启动链把视图推到设置首页，再点进去。
 *
 * 两步都用有界等待（同 `openSettingsPage` 的理由）：`findBy*` 默认只等 1 s，
 * 全量并行跑时这条链路会越过 1 s，失败信息还会伪装成「入口不存在」。
 */
async function openVersionsPage(): Promise<void> {
  render(<App />)
  await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
  await screen.findByRole('heading', { name: '设置' }, { timeout: 5_000 })
  fireEvent.click(await screen.findByRole('button', { name: /版本管理/ }, { timeout: 5_000 }))
  await screen.findByRole('heading', { name: '版本管理' }, { timeout: 5_000 })
}

describe('版本管理：安装前的自动备份结论', () => {
  beforeEach(beforeEachAppTest)

  /** 走到「点确认更新」那一步：后面的断言都只关心备份结论怎么显示。 */
  async function confirmInstall(autoSnapshot: unknown): Promise<void> {
    // 有可用更新时启动链不会自动进对话（`App.tsx:5807`），启动页上直接有「更新运行环境」。
    bridge.getState.mockResolvedValue({ ...readyState, updateAvailable: true })
    bridge.install.mockResolvedValue({ autoSnapshot })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '更新运行环境' }))
    fireEvent.click(await screen.findByRole('button', { name: '确认更新' }))
  }

  it('备份成功时报出快照标识', async () => {
    await confirmInstall({ status: 'created', snapshotId })

    expect(await screen.findByText(`安装前已自动备份会话（${snapshotId}）`)).toBeVisible()
  })

  it('淘汰了旧备份要说清淘汰了几份', async () => {
    await confirmInstall({ status: 'created', snapshotId, evictedIds: ['snap-a', 'snap-b'] })

    expect(await screen.findByText(`安装前已自动备份会话（${snapshotId}）；为满足保留限制，已删除 2 份最旧的备份。`)).toBeVisible()
  })

  it('没有会话可备份时用原生给的原因，不报成失败', async () => {
    await confirmInstall({ status: 'skipped', code: 'RUNTIME_SNAPSHOT_EMPTY', message: '当前没有可备份的会话数据，未生成快照' })

    expect(await screen.findByText('当前没有可备份的会话数据，未生成快照')).toBeVisible()
  })

  it('备份真的失败要单独报错，但安装本身仍算成功', async () => {
    await confirmInstall({ status: 'failed', code: 'RUNTIME_SNAPSHOT_FAILED', message: '磁盘空间不足' })

    expect(await screen.findByText('会话自动备份失败：磁盘空间不足')).toBeVisible()
    expect(bridge.install).toHaveBeenCalledTimes(1)
  })

  it('旧版原生桥不回 autoSnapshot 时不多说一句', async () => {
    bridge.getState.mockResolvedValue({ ...readyState, updateAvailable: true })
    bridge.install.mockResolvedValue({})
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '更新运行环境' }))
    fireEvent.click(await screen.findByRole('button', { name: '确认更新' }))

    await waitFor(() => expect(bridge.install).toHaveBeenCalled())
    expect(screen.queryByText(/自动备份会话/)).toBeNull()
  })
})

describe('版本管理：会话备份', () => {
  beforeEach(beforeEachAppTest)

  it('进页就读一次，没有备份时如实说还没有', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    await openVersionsPage()

    expect(await screen.findByText('暂无会话备份。你可以手动创建，也可以等应用在安装运行环境前自动备份。')).toBeVisible()
    expect(bridge.getRuntimeSessionSnapshotState).toHaveBeenCalledTimes(1)
    expect(screen.getByText(/最多保留 3 份备份，总大小不超过 512 MB/)).toBeVisible()
  })

  it('可以现在备份一次，备份完成后列表出现那一份', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.createRuntimeSessionSnapshot.mockResolvedValue(oneSnapshot)
    await openVersionsPage()

    fireEvent.click(await screen.findByRole('button', { name: '现在备份一次' }))

    expect(await screen.findByText('会话备份已创建')).toBeVisible()
    expect(bridge.createRuntimeSessionSnapshot).toHaveBeenCalledTimes(1)
    expect(screen.getByText(/2 个文件 · 4\.0 KB · dsh 0\.1\.5-rc\.2 · 2026\.08\.17/)).toBeVisible()
  })

  it('恢复是合并回填：跳过的文件数必须一起报出来', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.getRuntimeSessionSnapshotState.mockResolvedValue(oneSnapshot)
    bridge.restoreRuntimeSessionSnapshot.mockResolvedValue({
      restoredFileCount: 3,
      skippedFileCount: 2,
      state: oneSnapshot,
    })
    await openVersionsPage()

    fireEvent.click(await screen.findByRole('button', { name: '恢复' }))

    expect(await screen.findByText('会话恢复完成：恢复 3 个文件，跳过 2 个已存在的文件。')).toBeVisible()
    expect(bridge.restoreRuntimeSessionSnapshot).toHaveBeenCalledWith(snapshotId)
  })

  it('删除要二次确认，第一次点不会真删', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.getRuntimeSessionSnapshotState.mockResolvedValue(oneSnapshot)
    bridge.deleteRuntimeSessionSnapshot.mockResolvedValue({ ...oneSnapshot, totalBytes: 0, snapshots: [] })
    await openVersionsPage()

    fireEvent.click(await screen.findByRole('button', { name: '删除' }))

    expect(bridge.deleteRuntimeSessionSnapshot).not.toHaveBeenCalled()
    fireEvent.click(await screen.findByRole('button', { name: '确认删除' }))

    expect(await screen.findByText('会话备份已删除')).toBeVisible()
    expect(bridge.deleteRuntimeSessionSnapshot).toHaveBeenCalledWith(snapshotId)
  })

  it('读不到备份列表时提示并可重试', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.getRuntimeSessionSnapshotState.mockRejectedValue(new Error('boom'))
    await openVersionsPage()

    expect(await screen.findByText('暂时读不到会话备份列表')).toBeVisible()
    bridge.getRuntimeSessionSnapshotState.mockResolvedValue(oneSnapshot)
    fireEvent.click(screen.getByRole('button', { name: '重试' }))

    expect(await screen.findByText(/2 个文件 · 4\.0 KB/)).toBeVisible()
  })
})

describe('版本管理：应用自身更新', () => {
  beforeEach(beforeEachAppTest)

  it('没有新版本时说已是最新，下载与安装都不可点', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    await openVersionsPage()

    expect(await screen.findByText('已是最新')).toBeVisible()
    expect(screen.getByText('0.2.0（22）')).toBeVisible()
    expect(screen.getByText('发布页上没有更新的正式版本')).toBeVisible()
    expect(screen.getByRole('button', { name: '下载更新' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '安装更新' })).toBeDisabled()
  })

  it('未授权安装未知应用时如实引导去系统设置，回来再读一次状态', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    await openVersionsPage()

    expect(await screen.findByText(/系统还没有允许本应用安装其它应用。/)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '去系统设置允许安装' }))

    await waitFor(() => expect(bridge.openAppUpdateInstallSettings).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(bridge.getAppUpdateState).toHaveBeenCalledTimes(2))
  })

  it('有新版本时显示版本与体积、更新说明，下载与安装如实报结果', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.getAppUpdateState.mockResolvedValue({
      installedVersion: '0.2.0',
      installedVersionCode: 22,
      installAllowed: true,
      available: {
        version: '0.2.1-mobile-400',
        bytes: 12 * 1024 * 1024,
        sha256: 'b'.repeat(64),
        notes: '修好了安装与备份。',
      },
    })
    await openVersionsPage()

    expect(await screen.findByText('可更新')).toBeVisible()
    expect(screen.getByText('0.2.1-mobile-400 · 12 MB')).toBeVisible()
    expect(screen.getByText('修好了安装与备份。')).toBeVisible()
    // 已授权时不再显示「去系统设置」那一步，而是说明会跳到系统安装界面。
    expect(screen.queryByRole('button', { name: '去系统设置允许安装' })).toBeNull()
    expect(screen.getByText(/下载完成后交给系统安装器安装/)).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '下载更新' }))

    expect(await screen.findByText('更新包已下载并通过校验')).toBeVisible()
    expect(bridge.downloadAppUpdate).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: '安装更新' }))

    expect(await screen.findByText('已交给系统安装器')).toBeVisible()
    expect(bridge.installAppUpdate).toHaveBeenCalledTimes(1)
  })

  it('下载失败时报出失败原因，不假装已经可以安装', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.getAppUpdateState.mockResolvedValue({
      installedVersion: '0.2.0',
      installedVersionCode: 22,
      installAllowed: true,
      available: { version: '0.2.1-mobile-400', bytes: 12 * 1024 * 1024, sha256: 'b'.repeat(64), notes: '' },
    })
    bridge.downloadAppUpdate.mockRejectedValue(new Error('校验不通过'))
    await openVersionsPage()

    fireEvent.click(await screen.findByRole('button', { name: '下载更新' }))

    expect(await screen.findByText('校验不通过')).toBeVisible()
    expect(screen.queryByText('更新包已下载并通过校验')).toBeNull()
  })
})
