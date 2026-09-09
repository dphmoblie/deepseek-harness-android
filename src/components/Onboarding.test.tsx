import { fireEvent, render, screen } from '@testing-library/react'
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
    onDone: vi.fn(),
    ...overrides,
  }
  render(<Onboarding {...props} />)
  return props
}

describe('Onboarding', () => {
  beforeEach(() => { saveLanguage('zh-CN') })

  it('renders the welcome step and navigates forward', () => {
    renderOnboarding()
    expect(screen.getByText('欢迎使用 DeepSeek Harness Android')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByText('安装 Ubuntu 运行时')).toBeDefined()
  })

  it('warns when the manifest pair is not configured', () => {
    renderOnboarding()
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByText(/尚未配置运行时下载地址/)).toBeDefined()
  })

  it('calls onInstall from the runtime step', () => {
    const props = renderOnboarding()
    fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    fireEvent.click(screen.getByRole('button', { name: /安装运行时/ }))
    expect(props.onInstall).toHaveBeenCalledOnce()
  })

  it.each([
    { language: 'zh-CN', next: '下一步', provider: '供应商', add: '添加自定义供应商', providerName: '供应商名称', modelId: '模型 ID', modelName: '模型名称', save: '保存自定义供应商' },
    { language: 'en', next: 'Next', provider: 'Provider', add: 'Add custom provider', providerName: 'Provider name', modelId: 'Model ID', modelName: 'Model name', save: 'Save custom provider' },
  ])('saves a custom provider from the model step in $language', labels => {
    saveLanguage(labels.language)
    const onSaveSettings = vi.fn()
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

  it('calls onOpenHarness from the final step', () => {
    const props = renderOnboarding({ runtime: { ...runtime, phase: 'running' } })
    for (let i = 0; i < 5; i += 1) fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    expect(screen.getByText('开始使用')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: /打开 Harness/ }))
    expect(props.onOpenHarness).toHaveBeenCalledOnce()
  })

  it('skips the wizard via the close button', () => {
    const props = renderOnboarding()
    fireEvent.click(screen.getByRole('button', { name: /跳过引导/ }))
    expect(props.onDone).toHaveBeenCalledOnce()
  })
})
