import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Onboarding } from './Onboarding'
import type { RuntimeSettings, RuntimeState, ShizukuState } from '../platform/types'

const runtime: RuntimeState = {
  phase: 'not-installed',
  architecture: 'arm64-v8a',
  downloadedBytes: 0,
  totalBytes: 0,
  runnerAvailable: true,
}

const settings: RuntimeSettings = {
  manifestUrl: 'https://downloads.example.invalid/runtime.json',
  manifestSha256: 'a'.repeat(64),
  keepScreenAwake: true,
  terminalFontSize: 14,
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
