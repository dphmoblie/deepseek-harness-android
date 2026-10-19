import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Onboarding } from './Onboarding'
import { saveLanguage } from '../i18n'
import type { RuntimeSettings, RuntimeState, ShizukuState } from '../platform/types'

const runtime: RuntimeState = {
  phase: 'not-installed',
  architecture: 'arm64-v8a',
  updateAvailable: false,
  downloadedBytes: 0,
  totalBytes: 0,
  runnerAvailable: true,
}

const settings: RuntimeSettings = {
  manifestUrl: 'https://downloads.example.invalid/runtime.json',
  manifestSha256: 'a'.repeat(64),
  keepScreenAwake: true,
  terminalFontSize: 14,
  configuredModelProviders: [],
}

const shizuku: ShizukuState = { installed: true, running: true, permission: 'undetermined', connected: false }

function renderOnboarding(overrides: Partial<Parameters<typeof Onboarding>[0]> = {}) {
  const props = {
    busy: null,
    runtime,
    shizuku,
    settings,
    onInstall: vi.fn(),
    onAuthorize: vi.fn(),
    onOpenShizuku: vi.fn(),
    onOpenHarness: vi.fn(),
    onOpenHarnessConfirmed: vi.fn(),
    onDone: vi.fn(),
    onSaveSettings: vi.fn().mockResolvedValue(true),
    ...overrides,
  }
  const view = render(<Onboarding {...props} />)
  return { ...props, rerender: view.rerender }
}

describe('Onboarding', () => {
  beforeEach(() => { saveLanguage('zh-CN') })

  it('renders the welcome step and navigates forward', () => {
    renderOnboarding()
    expect(screen.getByText('欢迎使用 DeepSeek Harness Android')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByText('安装 Ubuntu 运行时')).toBeDefined()
  })

  it('配置错误的在线来源要明确点出来', () => {
    renderOnboarding()
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByText(/运行时下载地址不完整/)).toBeDefined()
  })

  it('只填一半的清单也算配置错误', () => {
    renderOnboarding({ settings: { ...settings, manifestSha256: '' } })
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByText(/运行时下载地址不完整/)).toBeDefined()
  })

  it('离线安装包说明内置镜像与补充在线来源的位置', () => {
    renderOnboarding({ settings: { ...settings, manifestUrl: '', manifestSha256: '' } })
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByText(/这个安装包没带运行时下载地址/)).toBeDefined()
    expect(screen.queryByText(/运行时下载地址不完整/)).toBeNull()
  })

  it('calls onInstall from the runtime step', () => {
    const props = renderOnboarding()
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    fireEvent.click(screen.getByRole('button', { name: /安装运行时/ }))
    expect(props.onInstall).toHaveBeenCalledOnce()
  })

  it.each([
    { name: '内置离线来源', settings: { ...settings, manifestUrl: '', manifestSha256: '' }, runtime },
    { name: '已安装运行时', settings, runtime: { ...runtime, phase: 'ready' as const } },
    { name: '有效在线来源', settings: { ...settings, manifestUrl: 'https://example.com/runtime.json' }, runtime },
  ])('$name 不报地址配置错误', value => {
    renderOnboarding(value)
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.queryByText(/运行时下载地址不完整/)).toBeNull()
  })

  it('安装忙碌时不会调用保存或清空密钥', () => {
    const props = renderOnboarding({ busy: 'install' })
    for (let i = 0; i < 2; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    const input = screen.getByLabelText('DeepSeek API Key')
    fireEvent.change(input, { target: { value: 'test-placeholder-key' } })
    expect(screen.getByRole('button', { name: '保存 API Key' })).toBeDisabled()
    fireEvent.submit(input.closest('form')!)
    expect(props.onSaveSettings).not.toHaveBeenCalled()
    expect(input).toHaveValue('test-placeholder-key')
  })

  it.each([false, new Error('SAVE_FAILED')])('保存未成功时保留密钥并允许重试：%s', async failure => {
    const onSaveSettings = failure instanceof Error ? vi.fn().mockRejectedValue(failure) : vi.fn().mockResolvedValue(failure)
    renderOnboarding({ onSaveSettings })
    for (let i = 0; i < 2; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    const input = screen.getByLabelText('DeepSeek API Key')
    fireEvent.change(input, { target: { value: 'test-placeholder-key' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 API Key' }))
    await screen.findByRole('alert')
    expect(input).toHaveValue('test-placeholder-key')
    expect(screen.getByRole('button', { name: '保存 API Key' })).toBeEnabled()
  })

  it('保存完成之前不清空密钥或重复提交，成功后清空', async () => {
    let finish!: (saved: boolean) => void
    const onSaveSettings = vi.fn(() => new Promise<boolean>(resolve => { finish = resolve }))
    renderOnboarding({ onSaveSettings, runtime: { ...runtime, phase: 'ready' } })
    for (let i = 0; i < 2; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    const input = screen.getByLabelText('DeepSeek API Key')
    fireEvent.change(input, { target: { value: 'test-placeholder-key' } })
    expect(screen.getByRole('button', { name: '登录 DeepSeek 账号' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '保存 API Key' }))
    fireEvent.submit(input.closest('form')!)
    expect(onSaveSettings).toHaveBeenCalledTimes(1)
    expect(input).toHaveValue('test-placeholder-key')
    expect(input).toBeDisabled()
    expect(screen.getByRole('button', { name: /下一步/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '登录 DeepSeek 账号' })).toBeDisabled()
    finish(true)
    await waitFor(() => expect(input).toHaveValue(''))
  })

  it('后台刷新设置保留内置与自定义供应商草稿及凭据', () => {
    const props = renderOnboarding()
    for (let i = 0; i < 2; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    fireEvent.change(screen.getByLabelText('DeepSeek API Key'), { target: { value: 'builtin-placeholder' } })
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'custom' } })
    fireEvent.click(screen.getByRole('button', { name: '添加自定义供应商' }))
    fireEvent.change(screen.getByRole('textbox', { name: '供应商名称' }), { target: { value: 'Draft provider' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Base URL' }), { target: { value: 'https://example.com/v1' } })
    fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'custom-placeholder' } })
    // 原实例接收全新设置引用，模拟恢复前台时的后台刷新。
    props.rerender(<Onboarding {...props} settings={{ ...settings }} />)
    expect(screen.getByRole('textbox', { name: '供应商名称' })).toHaveValue('Draft provider')
    expect(screen.getByLabelText('API Key')).toHaveValue('custom-placeholder')
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'deepseek' } })
    expect(screen.getByLabelText('DeepSeek API Key')).toHaveValue('builtin-placeholder')
    expect(props.onSaveSettings).not.toHaveBeenCalled()
  })

  it.each([
    { language: 'zh-CN', next: '下一步', provider: '供应商', add: '添加自定义供应商', providerName: '供应商名称', modelId: '模型 ID', modelName: '模型名称', save: '保存自定义供应商' },
    { language: 'en', next: 'Next', provider: 'Provider', add: 'Add custom provider', providerName: 'Provider name', modelId: 'Model ID', modelName: 'Model name', save: 'Save custom provider' },
  ])('saves a custom provider from the model step in $language', async labels => {
    saveLanguage(labels.language)
    const onSaveSettings = vi.fn().mockResolvedValue(true)
    renderOnboarding({ onSaveSettings })
    fireEvent.click(screen.getByRole('button', { name: labels.next }))
    fireEvent.click(screen.getByRole('button', { name: labels.next }))
    fireEvent.change(screen.getByRole('combobox', { name: labels.provider }), { target: { value: 'custom' } })
    fireEvent.click(screen.getByRole('button', { name: labels.add }))
    fireEvent.change(screen.getByRole('textbox', { name: labels.providerName }), { target: { value: 'Gateway' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Base URL' }), { target: { value: 'https://api.example.com/v1' } })
    fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'test-placeholder-key' } })
    fireEvent.change(screen.getByRole('textbox', { name: labels.modelId }), { target: { value: 'example/model' } })
    fireEvent.change(screen.getByRole('textbox', { name: labels.modelName }), { target: { value: 'Example Model' } })
    fireEvent.click(screen.getByRole('button', { name: labels.save }))

    expect(onSaveSettings).toHaveBeenCalledWith(expect.objectContaining({
      customModelProviders: [expect.objectContaining({
        id: 'custom-1',
        api: 'openai-completions',
        baseUrl: 'https://api.example.com/v1',
        models: [expect.objectContaining({ id: 'example/model' })],
      })],
      customProviderApiKeys: { 'custom-1': 'test-placeholder-key' },
    }))
    await waitFor(() => expect(screen.getByLabelText('API Key')).toHaveValue(''))
    expect(screen.getByRole('textbox', { name: labels.providerName })).toHaveValue('Gateway')
  })

  it.each([false, new Error('SAVE_FAILED')])('自定义供应商保存失败保留草稿和凭据：%s', async failure => {
    const onSaveSettings = failure instanceof Error ? vi.fn().mockRejectedValue(failure) : vi.fn().mockResolvedValue(failure)
    renderOnboarding({
      onSaveSettings,
      settings: { ...settings, customModelProviders: [{
        id: 'gateway', name: 'Draft gateway', api: 'openai-completions', baseUrl: 'https://example.com/v1',
        models: [{ id: 'example/model', name: 'Example model', contextWindow: 131072, maxTokens: 8192 }],
      }] },
    })
    for (let i = 0; i < 2; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'custom' } })
    fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'custom-placeholder' } })
    fireEvent.click(screen.getByRole('button', { name: '保存自定义供应商' }))
    await screen.findByRole('alert')
    expect(screen.getByLabelText('API Key')).toHaveValue('custom-placeholder')
    expect(screen.getByRole('textbox', { name: '供应商名称' })).toHaveValue('Draft gateway')
    expect(screen.getByRole('button', { name: '保存自定义供应商' })).toBeEnabled()
  })

  it('calls onAuthorize when Shizuku is available and not granted', () => {
    const props = renderOnboarding()
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByText('设备 Shell（可选）')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: /授权设备 Shell/ }))
    expect(props.onAuthorize).toHaveBeenCalledOnce()
  })

  // 已配置任一来源的凭据时，最后一步才提供「打开 Harness」。
  const configuredCases: { name: string; settings: RuntimeSettings }[] = [
    { name: '内置供应商密钥', settings: { ...settings, configuredModelProviders: ['deepseek'] } },
    { name: 'Harness 网页密钥', settings: { ...settings, harnessConfiguredModelProviders: ['deepseek'] } },
    { name: '自定义供应商密钥', settings: { ...settings, configuredCustomModelProviders: ['custom-1'] } },
    { name: 'Harness 自定义供应商密钥', settings: { ...settings, harnessConfiguredCustomModelProviders: ['custom-1'] } },
    { name: 'DeepSeek 账号', settings: { ...settings, deepSeekAccountConfigured: true } },
  ]

  it.each(configuredCases)('calls onOpenHarness from the final step with $name', ({ settings: configured }) => {
    const props = renderOnboarding({ runtime: { ...runtime, phase: 'running' }, settings: configured })
    for (let i = 0; i < 5; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByText('开始使用')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: /打开 Harness/ }))
    expect(props.onOpenHarness).toHaveBeenCalledOnce()
  })

  it('keeps the normal Harness button hidden until a model credential is configured', () => {
    const props = renderOnboarding({ runtime: { ...runtime, phase: 'running' } })
    for (let i = 0; i < 5; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByText(/尚未检测到模型凭据/)).toBeDefined()
    expect(screen.queryByRole('button', { name: /打开 Harness/ })).toBeNull()

    // 「去配置模型账号或 API Key」把用户送回模型步骤。
    fireEvent.click(screen.getByRole('button', { name: /去配置模型账号或 API Key/ }))
    expect(screen.getByText('配置模型账号或 API Key')).toBeDefined()
    expect(props.onOpenHarness).not.toHaveBeenCalled()
  })

  it('未配置凭据时可从最终步骤前往账号登录', () => {
    const props = renderOnboarding({ runtime: { ...runtime, phase: 'ready' } })
    for (let i = 0; i < 5; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    const login = screen.getByRole('button', { name: '登录 DeepSeek 账号' })
    expect(login).toBeEnabled()
    fireEvent.click(login)
    expect(props.onOpenHarnessConfirmed).toHaveBeenCalledOnce()
    expect(props.onOpenHarness).not.toHaveBeenCalled()
  })

  it.each(['ready', 'running'] as const)('模型步骤在 %s 状态下可登录且不要求 API Key', phase => {
    const props = renderOnboarding({ runtime: { ...runtime, phase } })
    for (let i = 0; i < 2; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    const login = screen.getByRole('button', { name: '登录 DeepSeek 账号' })
    expect(login).toBeEnabled()
    fireEvent.click(login)
    expect(props.onOpenHarnessConfirmed).toHaveBeenCalledOnce()
    expect(props.onOpenHarness).not.toHaveBeenCalled()
    expect(props.onSaveSettings).not.toHaveBeenCalled()
  })

  it('模型步骤已登录账号提供管理入口且随设置刷新', () => {
    const props = renderOnboarding({ runtime: { ...runtime, phase: 'ready' } })
    for (let i = 0; i < 2; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    props.rerender(<Onboarding {...props} settings={{ ...settings, deepSeekAccountConfigured: true }} />)
    expect(screen.queryByRole('button', { name: '登录 DeepSeek 账号' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '打开 Harness 管理账号' }))
    expect(props.onOpenHarnessConfirmed).toHaveBeenCalledOnce()
  })

  it.each([
    { name: '未安装', currentRuntime: runtime, busy: null },
    { name: '待更新', currentRuntime: { ...runtime, phase: 'ready' as const, updateAvailable: true }, busy: null },
    { name: '忙碌', currentRuntime: { ...runtime, phase: 'running' as const }, busy: 'install' },
  ])('模型与最终步骤在$name时均禁用账号登录', ({ currentRuntime, busy }) => {
    const props = renderOnboarding({ runtime: currentRuntime, busy })
    for (let i = 0; i < 2; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByRole('button', { name: '登录 DeepSeek 账号' })).toBeDisabled()
    for (let i = 0; i < 3; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByRole('button', { name: '登录 DeepSeek 账号' })).toBeDisabled()
    expect(props.onOpenHarnessConfirmed).not.toHaveBeenCalled()
  })

  it('未配置密钥时只能由用户显式确认后放行打开', () => {
    const props = renderOnboarding({ runtime: { ...runtime, phase: 'running' } })
    for (let i = 0; i < 5; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.queryByRole('button', { name: /^打开 Harness$/ })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /我已在 Harness 内配置过/ }))
    expect(props.onOpenHarnessConfirmed).toHaveBeenCalledOnce()
    // 显式确认走的是放行入口，不会顺手把常规入口也调起来。
    expect(props.onOpenHarness).not.toHaveBeenCalled()
  })

  it('skips the wizard via the close button', () => {
    const props = renderOnboarding()
    fireEvent.click(screen.getByRole('button', { name: /跳过引导/ }))
    expect(props.onDone).toHaveBeenCalledOnce()
  })
})
