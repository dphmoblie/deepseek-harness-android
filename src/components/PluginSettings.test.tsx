import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginSettings } from './PluginSettings'
import { createBrowserBridge } from '../platform/browser'
import { saveLanguage } from '../i18n'
import type { PluginCatalog, RuntimeState } from '../platform/types'

const runtime: RuntimeState = { phase: 'error', installedVersion: '0.1.10', architecture: 'arm64-v8a', updateAvailable: false, downloadedBytes: 0, totalBytes: 0, runnerAvailable: true }
const catalog: PluginCatalog = { plugins: [
  { id: '@deepseek-ai/dsh-base', file: 'cordis.patch.yml', version: '1.0.0', enabled: true, protected: true, official: true, installed: true, readable: true, children: [] },
  { id: 'example-plugin', file: 'config/plugins.yml', version: '1.0.0', rollback: '0.9.0', enabled: true, protected: false, official: false, installed: true, readable: true, children: [{ id: 'optional', name: 'example-plugin/optional', enabled: true, effectiveEnabled: true, protected: false }] },
] }
function setup(state = runtime, groups: PluginCatalog = catalog) {
  const bridge = { ...createBrowserBridge(), managePlugins: vi.fn().mockResolvedValue(groups), stopRuntime: vi.fn().mockResolvedValue({ ...runtime, phase: 'ready' }) }
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
    fireEvent.click(screen.getByRole('button', { name: '停止运行环境' }))
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
describe('插件导入与回滚', () => {
  it('按包名导入：来源为空时按钮禁用，成功后提示并清空输入', async () => {
    const bridge = setup()
    const button = await screen.findByRole('button', { name: '导入' })
    expect(button).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: '插件来源' }), { target: { value: 'dsh-plugin-example' } })
    expect(button).toBeEnabled()
    fireEvent.click(button)
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'import', source: 'dsh-plugin-example' }))
    expect(await screen.findByText('插件已导入，下次启动生效')).toBeInTheDocument()
    // 成功后清空：同一条来源再点一次会命中既有安装，留着输入更容易被误解成"装了两遍"。
    expect(screen.getByRole('textbox', { name: '插件来源' })).toHaveValue('')
  })
  it('地址形态可带期望包名，随请求一起送给原生', async () => {
    const bridge = setup()
    await screen.findByRole('button', { name: '导入' })
    fireEvent.change(screen.getByRole('textbox', { name: '插件来源' }), { target: { value: 'https://example.com/plugin.tgz' } })
    fireEvent.change(screen.getByRole('textbox', { name: '期望的包名' }), { target: { value: 'dsh-plugin-example' } })
    fireEvent.click(screen.getByRole('button', { name: '导入' }))
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'import', source: 'https://example.com/plugin.tgz', id: 'dsh-plugin-example' }))
  })
  it('来源不合格时显示受控文案，不回显用户填的内容', async () => {
    const bridge = setup()
    const button = await screen.findByRole('button', { name: '导入' })
    fireEvent.change(screen.getByRole('textbox', { name: '插件来源' }), { target: { value: 'http://user:secret-payload@example.com/plugin.tgz' } })
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_SOURCE_INVALID', message: '' })
    fireEvent.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('这个来源地址不可用')
    expect(screen.queryByText(/secret-payload/)).not.toBeInTheDocument()
  })
  it('缺 git 与探测未确认是两条不同的提示，不许混为一谈', async () => {
    const bridge = setup()
    const button = await screen.findByRole('button', { name: '导入' })
    fireEvent.change(screen.getByRole('textbox', { name: '插件来源' }), { target: { value: 'git+https://example.com/repo.git' } })
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_GIT_MISSING', message: '' })
    fireEvent.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('当前运行时没有 git')
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_GIT_UNVERIFIED', message: '' })
    fireEvent.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('暂时无法确认运行环境是否支持 git')
  })
  it('回滚：有上一版时可点，提示上一版版本号并送回原生', async () => {
    const bridge = setup()
    const thirdParty = within(await screen.findByRole('region', { name: '第三方插件' }))
    fireEvent.click(thirdParty.getByText('example-plugin'))
    expect(thirdParty.getByText('可回滚到上一版本 0.9.0。')).toBeInTheDocument()
    fireEvent.click(thirdParty.getByRole('button', { name: '回滚到上一版本' }))
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'rollback', id: 'example-plugin' }))
    expect(await screen.findByText('已回滚到上一版本，下次启动生效')).toBeInTheDocument()
  })
  it('没有上一版时按钮禁用并说明原因；受保护包不出现回滚按钮', async () => {
    const bridge = setup(runtime, { plugins: [
      ...catalog.plugins,
      { id: 'never-updated', file: 'config/other.yml', version: '1.2.3', rollback: null, enabled: true, protected: false, official: false, installed: true, readable: true, children: [] },
    ] })
    const thirdParty = within(await screen.findByRole('region', { name: '第三方插件' }))
    fireEvent.click(thirdParty.getByText('never-updated'))
    const blocks = thirdParty.getAllByRole('button', { name: '回滚到上一版本' })
    expect(blocks.at(-1)).toBeDisabled()
    expect(thirdParty.getByText('没有可回滚的上一版本：每个插件只保留最近一次更新前的那一版。')).toBeInTheDocument()
    // 受保护的核心配置文件只能整体启用/禁用，不参与回滚：它的回滚按钮始终禁用。
    const official = within(screen.getByRole('region', { name: '官方插件' }))
    fireEvent.click(official.getByText('@deepseek-ai/dsh-base'))
    expect(official.getByRole('button', { name: '回滚到上一版本' })).toBeDisabled()
    expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'list' })
  })
})
