import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginSettings } from './PluginSettings'
import { createBrowserBridge } from '../platform/browser'
import { saveLanguage } from '../i18n'
import type { PluginCatalog, RuntimeState } from '../platform/types'

const runtime: RuntimeState = { phase: 'error', installedVersion: '0.1.10', architecture: 'arm64-v8a', updateAvailable: false, downloadedBytes: 0, totalBytes: 0, runnerAvailable: true }
const catalog: PluginCatalog = { plugins: [
  { id: '@deepseek-ai/dsh-base', file: 'cordis.patch.yml', version: '1.0.0', enabled: true, protected: true, official: true, installed: true, readable: true, children: [] },
  { id: 'example-plugin', file: 'config/plugins.yml', version: '1.0.0', enabled: true, protected: false, official: false, installed: true, readable: true, children: [{ id: 'optional', name: 'example-plugin/optional', enabled: true, effectiveEnabled: true, protected: false }] },
] }
function setup(state = runtime) {
  const bridge = { ...createBrowserBridge(), managePlugins: vi.fn().mockResolvedValue(catalog), stopRuntime: vi.fn().mockResolvedValue({ ...runtime, phase: 'ready' }) }
  render(<PluginSettings bridge={bridge} runtime={state} onBack={vi.fn()} />)
  return bridge
}
beforeEach(() => { saveLanguage('zh-CN') })
describe('应用外层插件管理', () => {
  it('Harness 错误状态下仍可展开文件和禁用子插件', async () => {
    const bridge = setup()
    const summary = await screen.findByText('example-plugin')
    fireEvent.click(summary)
    expect(screen.getByRole('region', { name: '官方插件' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '第三方插件' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: '启用子插件 optional' }))
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'child', id: 'example-plugin', childId: 'optional', enabled: false }))
    expect(await screen.findByText('插件设置已保存，下次启动生效')).toBeInTheDocument()
  })
  it('运行中拒绝修改，显式停止后允许管理', async () => {
    const bridge = setup({ ...runtime, phase: 'running' })
    fireEvent.click(await screen.findByText('example-plugin'))
    expect(screen.getByRole('checkbox', { name: '启用文件 example-plugin' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '停止运行时' }))
    await waitFor(() => expect(bridge.stopRuntime).toHaveBeenCalledOnce())
    await waitFor(() => expect(screen.getByRole('checkbox', { name: '启用文件 example-plugin' })).toBeEnabled())
  })
  it('更新失败后显示受控错误，保留列表并允许重试', async () => {
    const bridge = setup()
    fireEvent.click(await screen.findByText('example-plugin'))
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_UPDATE_FAILED', message: 'private debug details' })
    const update = screen.getAllByRole('button', { name: '更新所属插件包' }).find(button => !(button as HTMLButtonElement).disabled)!
    fireEvent.click(update)
    expect(await screen.findByRole('alert')).toHaveTextContent('已保留原版本')
    expect(screen.queryByText('private debug details')).not.toBeInTheDocument()
    fireEvent.click(update)
    expect(await screen.findByText('插件包已更新，下次启动生效')).toBeInTheDocument()
  })
  it('英文界面翻译管理操作', async () => {
    saveLanguage('en')
    setup()
    fireEvent.click(await screen.findByText('example-plugin'))
    expect(screen.getByRole('heading', { name: 'Official plugins' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Enable plugin optional' })).toBeInTheDocument()
  })
})
