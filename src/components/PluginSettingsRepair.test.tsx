import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginSettings } from './PluginSettings'
import { createBrowserBridge } from '../platform/browser'
import { saveLanguage } from '../i18n'
import type { PluginCatalog, PluginGroup, RuntimeState } from '../platform/types'

/**
 * 「立即修复插件」这一条人工恢复通道的行为测试。
 *
 * 计数风格照 `src/__tests__/renderCounts.test.tsx`：**先证明量具本身在工作，再做精确计数** ——
 * 每个用例在点击之前都先断言 `managePlugins` 已经记录了那次初始 `list`（量具非空），
 * 之后一律用 `toHaveBeenCalledTimes` 的精确值而不是"至少调了一次"。
 * 若某次改动让量具失效（比如不再传 bridge），前置断言会先失败，而不是让计数悄悄恒真。
 */

const runtime: RuntimeState = { phase: 'error', installedVersion: '0.1.10', architecture: 'arm64-v8a', updateAvailable: false, downloadedBytes: 0, totalBytes: 0, runnerAvailable: true }

/** 升级后的典型形态：插件登记在清单里、仍然启用，但运行时里解析不到（版本为 null）。 */
const missing: PluginGroup = { id: 'dsh-web-mobile', file: 'config/plugins.yml', version: null, rollback: null, enabled: true, protected: false, official: false, installed: false, readable: true, children: [] }
/** 修复成功后同一个包：链接接回来了，版本号读得到。 */
const linked: PluginGroup = { ...missing, version: '3.0.3', installed: true }

const upgraded: PluginCatalog = { plugins: [missing] }
const repaired: PluginCatalog = {
  plugins: [linked],
  recovery: { status: 'restored', restored: 0, relinked: 1, missing: 0, failed: 0, refused: 0 },
}

function setup(state = runtime, first: PluginCatalog = upgraded) {
  const bridge = {
    ...createBrowserBridge(),
    managePlugins: vi.fn().mockResolvedValueOnce(first),
    stopRuntime: vi.fn().mockResolvedValue({ ...state, phase: 'ready' }),
  }
  render(<PluginSettings bridge={bridge} runtime={state} onBack={vi.fn()} />)
  return bridge
}

/** 等待首屏列表到位，并先自检量具：初始那一次 `list` 必须真的被记录下来。 */
async function ready(bridge: { managePlugins: ReturnType<typeof vi.fn> }): Promise<HTMLElement> {
  const button = await screen.findByRole('button', { name: '立即修复插件' })
  expect(bridge.managePlugins).toHaveBeenCalledTimes(1)
  expect(bridge.managePlugins).toHaveBeenLastCalledWith({ operation: 'list' })
  return button
}

beforeEach(() => { saveLanguage('zh-CN') })

describe('插件恢复的人工修复入口', () => {
  it('列表里有已启用却解析不到的插件时即给出入口，即便自愈还没跑过', async () => {
    const bridge = setup(runtime, { plugins: [
      // 官方包与已禁用的包都不计入：前者随运行时走，后者本来就不该加载。
      { id: '@deepseek-ai/dsh-base', file: 'cordis.patch.yml', version: null, rollback: null, enabled: true, protected: true, official: true, installed: false, readable: true, children: [] },
      missing,
      { ...missing, id: 'disabled-plugin', file: 'config/other.yml', enabled: false },
    ] })
    const button = await ready(bridge)
    expect(screen.getByText('有插件未接回运行时')).toBeInTheDocument()
    expect(screen.getByText('有 1 个已启用插件在当前运行时里解析不到，通常发生在运行时或应用升级之后。')).toBeInTheDocument()
    expect(button).toBeEnabled()
  })

  it('点击后只送一次 repair，并用返回的列表与计数覆盖界面', async () => {
    const bridge = setup()
    bridge.managePlugins.mockResolvedValueOnce(repaired)
    const button = await ready(bridge)
    expect(screen.getByText('config/plugins.yml · 未安装')).toBeInTheDocument()
    // 修复请求必须原样是 repair：不带 id、不带 enabled，原生侧只按运行时状态自己决定修什么。
    fireEvent.click(button)
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledTimes(2))
    expect(bridge.managePlugins).toHaveBeenLastCalledWith({ operation: 'repair' })
    expect(await screen.findByText('插件修复已执行，列表已刷新')).toBeInTheDocument()
    expect(screen.getByText('插件已恢复')).toBeInTheDocument()
    expect(screen.getByText('已恢复 0 个配置、重新链接 1 个插件。')).toBeInTheDocument()
    // 列表被这次调用的返回值替换：同一个包从「未安装」变成 3.0.3。
    expect(screen.getByText('config/plugins.yml · 3.0.3')).toBeInTheDocument()
    expect(screen.queryByText('有插件未接回运行时')).not.toBeInTheDocument()
  })

  it('修复进行中按钮禁用，出结果后恢复可用', async () => {
    const bridge = setup()
    const button = await ready(bridge)
    let finish: (value: PluginCatalog) => void = () => {}
    bridge.managePlugins.mockReturnValueOnce(new Promise<PluginCatalog>(resolve => { finish = resolve }))
    fireEvent.click(button)
    expect(button).toBeDisabled()
    await act(async () => { finish(repaired); await Promise.resolve() })
    await waitFor(() => expect(screen.getByRole('button', { name: '立即修复插件' })).toBeEnabled())
  })

  it('运行中的运行时不给修复入口，停止后才可用', async () => {
    const bridge = setup({ ...runtime, phase: 'running' })
    const button = await ready(bridge)
    expect(button).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '停止运行环境' }))
    await waitFor(() => expect(button).toBeEnabled())
    expect(bridge.managePlugins).toHaveBeenCalledTimes(1)
  })

  it('修复失败按受控错误码回显，不把原生的通用文案再糊一遍', async () => {
    const bridge = setup()
    bridge.managePlugins.mockRejectedValueOnce({ code: 'RUNTIME_BUSY', message: '请先停止 Harness 和 Ubuntu 终端' })
    fireEvent.click(await ready(bridge))
    // 受控码映射成自己的文案；同一条原生消息不再作为括号详情重复出现。
    expect(await screen.findByRole('alert')).toHaveTextContent('请先停止 Harness 和 Ubuntu 终端')
    expect(screen.queryByText(/（请先停止/)).not.toBeInTheDocument()
    // 原生无法给出更精确信息时用的那句通用文案：只出现一次，不重复。
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_OPERATION_FAILED', message: '插件操作失败，请检查运行时状态后重试' })
    fireEvent.click(screen.getByRole('button', { name: '立即修复插件' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('插件操作失败，请检查运行时状态后重试')
    expect(screen.queryByText(/（插件操作失败/)).not.toBeInTheDocument()
    expect(bridge.managePlugins).toHaveBeenCalledTimes(3)
  })

  it('英文界面翻译修复入口', async () => {
    saveLanguage('en')
    setup()
    expect(await screen.findByText('Plugins are not linked back into the runtime')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Repair plugins now' })).toBeInTheDocument()
    expect(screen.getByText('Relinks plugins back into the runtime from the preserved packages without downloading again. Plugins whose package files are gone cannot be repaired and must be reinstalled.')).toBeInTheDocument()
  })
})
