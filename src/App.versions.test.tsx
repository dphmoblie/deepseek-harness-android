import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, notInstalledState, openSettingsPage, readyState } from './__tests__/appTestHarness'
import type { RuntimeReleaseEntry, RuntimeSessionSnapshotState } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 版本管理页在「双槽切换」之外新增的三块：远端可用版本、会话备份、应用自身更新。
 *
 * 三块各自回答一个「用户会问的问题」：
 *  1. 除了 APK 内置的那份，还能从发布页装到哪些版本？（列表要手动触发，查一次要打 GitHub）
 *  2. 换掉运行时以后，旧 dsh 写的会话读不出来怎么办？（先备份、能恢复，恢复是合并回填）
 *  3. 应用本身（APK）怎么更新？（下载校验后交给系统安装器，未授权时如实引导去系统设置）
 *
 * 与「运行时版本管理」同住一条导航链，所以按 view 单独成文件（登记册 5.6-J）。
 */

/** 一份可安装的远端版本：清单地址与摘要成对出现。 */
const installableRelease: RuntimeReleaseEntry = {
  version: '0.2.1-mobile-400',
  dshVersion: '0.2.0-rc.2',
  manifestUrl: 'https://github.com/dphmoblie/deepseek-harness-android/releases/download/v0.2.1-mobile-400/runtime-manifest.json',
  manifestSha256: 'a'.repeat(64),
}

/** 出现过、但清单没通过校验的版本：只能列出来，不能安装。 */
const unverifiedRelease: RuntimeReleaseEntry = { version: '0.2.1-mobile-399' }

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

/** 打开远端版本列表：可用版本是手动触发的，所以每个相关用例都要先点一次。 */
async function checkReleases(): Promise<void> {
  fireEvent.click(await screen.findByRole('button', { name: '检查可用版本' }))
}

describe('版本管理：远端可用版本', () => {
  beforeEach(beforeEachAppTest)

  it('可用版本不会自动查询：不进发布页，列表也不假装是空的', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    await openVersionsPage()

    expect(bridge.listRuntimeReleases).not.toHaveBeenCalled()
    // 没查过 ≠ 没有版本：这时连「还没有」都不该说，更不能给出安装按钮。
    expect(screen.queryByText('发布页上还没有带运行时清单的版本。')).toBeNull()
    expect(screen.queryByRole('button', { name: '安装这个版本' })).toBeNull()
  })

  it('点了才查一次，并把带清单的与没过校验的分开呈现', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.listRuntimeReleases.mockResolvedValue({ entries: [installableRelease, unverifiedRelease] })
    await openVersionsPage()

    await checkReleases()

    expect(await screen.findByText(installableRelease.version)).toBeVisible()
    expect(screen.getByText('dsh 0.2.0-rc.2')).toBeVisible()
    expect(screen.getByText('这个版本的清单没通过校验，只能查看、不能安装。')).toBeVisible()
    const buttons = screen.getAllByRole('button', { name: '安装这个版本' })
    expect(buttons).toHaveLength(2)
    expect(buttons[0]).toBeEnabled()
    expect(buttons[1]).toBeDisabled()
    expect(bridge.listRuntimeReleases).toHaveBeenCalledTimes(1)
  })

  it('发布页上一个带清单的版本都没有时，如实说没有', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.listRuntimeReleases.mockResolvedValue({ entries: [] })
    await openVersionsPage()

    await checkReleases()

    expect(await screen.findByText('发布页上还没有带运行时清单的版本。')).toBeVisible()
  })

  it('查不到版本时报错，不把网络失败显示成「没有版本」', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.listRuntimeReleases.mockRejectedValue(new Error('offline'))
    await openVersionsPage()

    await checkReleases()

    expect(await screen.findByText('查不到可用版本：请检查网络后重试。')).toBeVisible()
    expect(screen.queryByText('发布页上还没有带运行时清单的版本。')).toBeNull()
  })

  it('装远端版本前先提醒会丢什么，并说明会先自动备份会话', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.listRuntimeReleases.mockResolvedValue({ entries: [installableRelease] })
    await openVersionsPage()
    await checkReleases()

    fireEvent.click(await screen.findByRole('button', { name: '安装这个版本' }))

    expect(await screen.findByRole('heading', { name: '更新 Ubuntu 运行环境' })).toBeVisible()
    expect(screen.getByText(/即将安装运行时 0\.2\.1-mobile-400。/)).toBeVisible()
    expect(screen.getByText(/开始前会先把当前会话自动备份一份/)).toBeVisible()
    expect(bridge.install).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '确认更新' }))
    await waitFor(() => expect(bridge.install).toHaveBeenCalledWith({
      manifestUrl: installableRelease.manifestUrl,
      manifestSha256: installableRelease.manifestSha256,
    }))
  })

  it('提醒里点「暂不更新」就什么都不装', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.listRuntimeReleases.mockResolvedValue({ entries: [installableRelease] })
    await openVersionsPage()
    await checkReleases()
    fireEvent.click(await screen.findByRole('button', { name: '安装这个版本' }))
    await screen.findByRole('heading', { name: '更新 Ubuntu 运行环境' })

    fireEvent.click(screen.getByRole('button', { name: '暂不更新' }))

    await waitFor(() => expect(screen.queryByRole('heading', { name: '更新 Ubuntu 运行环境' })).toBeNull())
    expect(bridge.install).not.toHaveBeenCalled()
  })

  it('还没装过运行时就直接装：首次安装没有会话可丢，不弹提醒', async () => {
    bridge.getState.mockResolvedValue({ ...notInstalledState })
    render(<App />)
    await waitFor(() => expect(bridge.getState).toHaveBeenCalled())
    // 还没装运行时的时候设置首页不是启动页：手机宽度下先走底部导航的「设置」。
    fireEvent.click(await screen.findByRole('button', { name: '设置' }, { timeout: 5_000 }))
    await screen.findByRole('heading', { name: '设置' }, { timeout: 5_000 })
    // 安装按钮在「Ubuntu 运行时」页（不是「运行与后台」）。
    await openSettingsPage('Ubuntu 运行时')

    // 标签取决于是不是配了远端来源，这里只关心「点了就装、不拦一道」。
    fireEvent.click(await screen.findByRole('button', { name: /安装内置环境|下载并安装/ }))

    await waitFor(() => expect(bridge.install).toHaveBeenCalled())
    expect(screen.queryByRole('heading', { name: '更新 Ubuntu 运行环境' })).toBeNull()
  })
})

describe('版本管理：安装前的自动备份结论', () => {
  beforeEach(beforeEachAppTest)

  /** 走到「点确认更新」那一步：后面的断言都只关心备份结论怎么显示。 */
  async function confirmInstall(autoSnapshot: unknown): Promise<void> {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.listRuntimeReleases.mockResolvedValue({ entries: [installableRelease] })
    bridge.install.mockResolvedValue({ autoSnapshot })
    await openVersionsPage()
    await checkReleases()
    fireEvent.click(await screen.findByRole('button', { name: '安装这个版本' }))
    fireEvent.click(await screen.findByRole('button', { name: '确认更新' }))
  }

  it('备份成功时报出快照标识', async () => {
    await confirmInstall({ status: 'created', snapshotId })

    expect(await screen.findByText(`安装前已自动备份会话（${snapshotId}）`)).toBeVisible()
  })

  it('淘汰了旧备份要说清淘汰了几份', async () => {
    await confirmInstall({ status: 'created', snapshotId, evictedIds: ['snap-a', 'snap-b'] })

    expect(await screen.findByText(`安装前已自动备份会话（${snapshotId}）；因超出保留上限，淘汰了 2 份最旧的备份。`)).toBeVisible()
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
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.listRuntimeReleases.mockResolvedValue({ entries: [installableRelease] })
    bridge.install.mockResolvedValue({})
    await openVersionsPage()
    await checkReleases()
    fireEvent.click(await screen.findByRole('button', { name: '安装这个版本' }))
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

    expect(await screen.findByText('还没有会话备份。可以现在拍一份，也可以在安装运行时前由应用自动拍。')).toBeVisible()
    expect(bridge.getRuntimeSessionSnapshotState).toHaveBeenCalledTimes(1)
    expect(screen.getByText(/最多保留 3 份、合计 512 MB/)).toBeVisible()
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

    expect(await screen.findByText('会话已恢复：回填 3 个文件，跳过 2 个已存在的文件。')).toBeVisible()
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
