import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginSettings } from './PluginSettings'
import { createBrowserBridge } from '../platform/browser'
import { saveLanguage } from '../i18n'
import type { PluginCatalog, PluginGroup, PluginRecoveryState, RuntimeState } from '../platform/types'

/**
 * 「应用升级迁移」这一条新回显通道的行为测试。
 *
 * 要钉住的是一条反直觉的规则：**插件列表一切正常时，迁移失败也必须看得见**。
 * 迁移（回放未提交事务、按代次自愈链接、按版本记录核对包目录）与「列表里有没有未安装的插件」
 * 是两件事 —— 迁移没做成时列表可能恰好是干净的，此时静默就等于用户永远不知道
 * 自己的插件账目没核对过。
 *
 * 反过来也要钉住：迁移一切正常时**不额外打扰** —— 每次应用升级都在设置页留一条提示，
 * 会把这个页面变成公告栏。
 */

const runtime: RuntimeState = { phase: 'error', installedVersion: '0.1.10', architecture: 'arm64-v8a', updateAvailable: false, downloadedBytes: 0, totalBytes: 0, runnerAvailable: true }

/** 一个随运行时走的官方包：既不算「未安装」，也不计入 unresolved。 */
const official: PluginGroup = { id: '@deepseek-ai/dsh-base', file: 'cordis.patch.yml', version: '0.2.13', rollback: null, enabled: true, protected: true, official: true, installed: true, readable: true, children: [] }

function state(extra: Partial<PluginRecoveryState>): PluginRecoveryState {
  return { status: 'none', restored: 0, relinked: 0, missing: 0, failed: 0, refused: 0, ...extra }
}

function setup(catalog: PluginCatalog) {
  const bridge = { ...createBrowserBridge(), managePlugins: vi.fn().mockResolvedValueOnce(catalog) }
  render(<PluginSettings bridge={bridge} runtime={runtime} onBack={vi.fn()} />)
  return bridge
}

/** 等待首屏列表到位，并先自检量具：初始那一次 `list` 必须真的被记录下来。 */
async function ready(bridge: { managePlugins: ReturnType<typeof vi.fn> }): Promise<void> {
  await screen.findByText('@deepseek-ai/dsh-base')
  expect(bridge.managePlugins).toHaveBeenCalledTimes(1)
  expect(bridge.managePlugins).toHaveBeenLastCalledWith({ operation: 'list' })
}

beforeEach(() => { saveLanguage('zh-CN') })

describe('应用升级迁移的回显', () => {
  it('插件列表一切正常时，迁移失败仍然回显', async () => {
    const bridge = setup({ plugins: [official], recovery: state({ migration: 'failed', lock: 'missing' }) })
    await ready(bridge)
    // 列表里没有未安装的插件（否则提示会由 unresolved 触发），这条提示只能来自迁移状态本身。
    expect(screen.getByText('插件迁移没有完成')).toBeInTheDocument()
    expect(screen.getByText('应用升级迁移没有成功，下次启动 Harness 时会自动重试。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '立即修复插件' })).toBeEnabled()
  })

  it('迁移只完成一部分时直接说清楚要重装', async () => {
    const bridge = setup({ plugins: [official], recovery: state({ migration: 'partial', lock: 'ok', checked: 2 }) })
    await ready(bridge)
    expect(screen.getByText('有插件需要重新安装')).toBeInTheDocument()
    expect(screen.getByText('应用升级迁移只完成了一部分，有插件的包已不在设备上，需要重新安装。')).toBeInTheDocument()
  })

  it('迁移正常完成时并入已有恢复文案，说清核对过几个插件', async () => {
    const bridge = setup({
      plugins: [official],
      recovery: state({ status: 'restored', restored: 1, relinked: 1, migration: 'done', lock: 'ok', checked: 3 }),
    })
    await ready(bridge)
    expect(screen.getByText('插件已恢复')).toBeInTheDocument()
    expect(screen.getByText(/应用升级迁移已完成，已按版本记录核对 3 个插件，包都在设备上。/)).toBeInTheDocument()
  })

  it('迁移正常完成、且没有别的可报时不留提示', async () => {
    const bridge = setup({ plugins: [official], recovery: state({ migration: 'done', lock: 'ok', checked: 1 }) })
    await ready(bridge)
    expect(screen.queryByText('插件已恢复')).not.toBeInTheDocument()
    expect(screen.queryByText(/应用升级迁移已完成/)).not.toBeInTheDocument()
    expect(screen.queryByText('插件版本记录有问题')).not.toBeInTheDocument()
  })

  it('版本记录损坏或写不进去时如实说明，不用内部文件名', async () => {
    setup({ plugins: [official], recovery: state({ lock: 'invalid' }) })
    expect(await screen.findByText('插件版本记录有问题')).toBeInTheDocument()
    expect(screen.getByText('已装插件的版本记录已损坏，无法据此核对插件是否还在设备上。')).toBeInTheDocument()
    // 界面上不出现内部文件名与字段名。
    expect(screen.queryByText(/installed-lock/)).not.toBeInTheDocument()
  })

  it('版本记录写不进去时说明原因与重试时机', async () => {
    setup({ plugins: [official], recovery: state({ migration: 'done', lock: 'unwritable', checked: 1 }) })
    expect(await screen.findByText('插件版本记录有问题')).toBeInTheDocument()
    // 同一段里会有多句（迁移完成 + 写不进去），按句子片段匹配，规则与上面那条迁移文案一致。
    expect(screen.getByText(/已装插件的版本记录写不进去（磁盘空间或权限问题），下次启动会再试一次。/)).toBeInTheDocument()
  })

  it('旧版原生侧不带迁移字段时行为与改动前一致', async () => {
    // `migration`/`lock`/`checked` 全部缺省：既不该出现新文案，也不该凭空冒出提示。
    setup({ plugins: [official], recovery: state({}) })
    await screen.findByText('@deepseek-ai/dsh-base')
    expect(screen.queryByText('插件迁移没有完成')).not.toBeInTheDocument()
    expect(screen.queryByText('插件版本记录有问题')).not.toBeInTheDocument()
  })

  it('英文界面翻译新增文案', async () => {
    saveLanguage('en')
    setup({ plugins: [official], recovery: state({ migration: 'failed' }) })
    expect(await screen.findByText('Plugin migration did not finish')).toBeInTheDocument()
    expect(screen.getByText('App upgrade migration did not succeed. It will be retried automatically the next time Harness starts.')).toBeInTheDocument()
  })
})
