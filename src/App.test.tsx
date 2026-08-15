import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RuntimeProgress, RuntimeSettings, RuntimeState, ShizukuState } from './platform/types'

const bridge = vi.hoisted(() => ({
  getState: vi.fn(),
  getSettings: vi.fn(),
  saveSettings: vi.fn(),
  install: vi.fn(),
  startHarness: vi.fn(),
  openHarness: vi.fn(),
  stopRuntime: vi.fn(),
  reset: vi.fn(),
  createTerminal: vi.fn(),
  writeTerminal: vi.fn(),
  resizeTerminal: vi.fn(),
  closeTerminal: vi.fn(),
  getShizukuState: vi.fn(),
  requestShizukuPermission: vi.fn(),
  openShizuku: vi.fn(),
  addRuntimeProgressListener: vi.fn(),
  addTerminalOutputListener: vi.fn(),
  addTerminalExitListener: vi.fn(),
}))

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

const readyState: RuntimeState = {
  phase: 'ready',
  architecture: 'arm64-v8a',
  installedVersion: '2026.08.1',
  downloadedBytes: 640 * 1024 * 1024,
  totalBytes: 640 * 1024 * 1024,
  runnerAvailable: true,
}

const settings: RuntimeSettings = {
  manifestUrl: 'https://downloads.example.invalid/runtime.json',
  manifestSha256: 'a'.repeat(64),
  keepScreenAwake: true,
  terminalFontSize: 14,
}

const shizuku: ShizukuState = {
  installed: true,
  running: true,
  permission: 'undetermined',
}

beforeEach(() => {
  vi.clearAllMocks()
  bridge.getState.mockResolvedValue({ ...readyState })
  bridge.getSettings.mockResolvedValue({ ...settings })
  bridge.getShizukuState.mockResolvedValue({ ...shizuku })
  bridge.addRuntimeProgressListener.mockImplementation((listener: (event: RuntimeProgress) => void) => {
    void listener
    return Promise.resolve({ remove: vi.fn().mockResolvedValue(undefined) })
  })
  bridge.saveSettings.mockImplementation((value: RuntimeSettings) => Promise.resolve(value))
  bridge.install.mockResolvedValue(undefined)
  bridge.startHarness.mockResolvedValue({ ...readyState, phase: 'running', harnessUrl: 'http://127.0.0.1:3080/' })
  bridge.openHarness.mockResolvedValue(undefined)
  bridge.stopRuntime.mockResolvedValue({ ...readyState })
  bridge.reset.mockResolvedValue({
    phase: 'not-installed',
    architecture: 'arm64-v8a',
    downloadedBytes: 0,
    totalBytes: readyState.totalBytes,
    runnerAvailable: true,
  })
  bridge.requestShizukuPermission.mockResolvedValue({ ...shizuku, permission: 'granted' })
  bridge.openShizuku.mockResolvedValue(undefined)
})

describe('App', () => {
  it('opens on Agent when the Ubuntu runtime is ready and launches the in-app Harness view', async () => {
    render(<App />)

    expect(await screen.findByText('Harness 可以启动')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '启动 Harness' }))

    expect(await screen.findByRole('button', { name: '打开 Harness' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '打开 Harness' }))

    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    expect(document.querySelector('iframe')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '停止' }))
    await waitFor(() => expect(bridge.stopRuntime).toHaveBeenCalledTimes(1))
    expect(await screen.findByRole('button', { name: '启动 Harness' })).toBeInTheDocument()
  })

  it('installs the pinned Ubuntu source and exposes its terminal when ready', async () => {
    const notInstalled: RuntimeState = {
      phase: 'not-installed',
      architecture: 'arm64-v8a',
      downloadedBytes: 0,
      totalBytes: readyState.totalBytes,
      runnerAvailable: true,
    }
    bridge.getState
      .mockResolvedValueOnce(notInstalled)
      .mockResolvedValueOnce({ ...readyState })

    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Ubuntu 环境' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '下载并安装' }))

    await waitFor(() => expect(bridge.install).toHaveBeenCalledWith({
      manifestUrl: settings.manifestUrl,
      manifestSha256: settings.manifestSha256,
    }))
    expect(await screen.findByRole('button', { name: '启动' })).toBeInTheDocument()

    fireEvent.click(screen.getAllByRole('button', { name: '终端' })[0])
    expect(await screen.findByTestId('terminal-panel')).toBeInTheDocument()
  })

  it('requires the exact reset confirmation before invoking the native reset', async () => {
    render(<App />)
    await screen.findByText('Harness 可以启动')

    fireEvent.click(screen.getAllByRole('button', { name: '环境' })[0])
    fireEvent.click(screen.getByRole('button', { name: '重置' }))

    const confirmButton = screen.getByRole('button', { name: '确认重置' })
    expect(confirmButton).toBeDisabled()
    fireEvent.change(screen.getByLabelText('输入 RESET_RUNTIME 确认'), { target: { value: 'RESET_RUNTIME' } })
    expect(confirmButton).toBeEnabled()
    fireEvent.click(confirmButton)

    await waitFor(() => expect(bridge.reset).toHaveBeenCalledWith('RESET_RUNTIME'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect((await screen.findAllByText('未安装')).length).toBeGreaterThan(0)
  })

  it('saves runtime source and terminal settings through the validated bridge', async () => {
    render(<App />)
    await screen.findByText('Harness 可以启动')

    fireEvent.click(screen.getAllByRole('button', { name: '设置' })[0])
    const fontSlider = await screen.findByRole('slider', { name: /字号/ })
    fireEvent.change(fontSlider, { target: { value: '17' } })
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))

    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({ ...settings, terminalFontSize: 17 }))
    expect(await screen.findByText('设置已保存')).toBeInTheDocument()
  })

  it('refreshes Shizuku state after returning from its external activity', async () => {
    bridge.getShizukuState.mockResolvedValueOnce({ installed: false, running: false, permission: 'undetermined' })
    render(<App />)
    await screen.findByText('Harness 可以启动')

    fireEvent.click(screen.getAllByRole('button', { name: '终端' })[0])
    fireEvent.click(screen.getByRole('tab', { name: '设备 Shell' }))
    expect(screen.getByText('未安装 Shizuku')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '打开 Shizuku' }))
    await waitFor(() => expect(bridge.openShizuku).toHaveBeenCalledTimes(1))

    bridge.getShizukuState.mockResolvedValue({ ...shizuku, permission: 'granted' })
    fireEvent.focus(window)

    expect(await screen.findByTestId('terminal-panel')).toBeInTheDocument()
    expect(bridge.getShizukuState).toHaveBeenCalledTimes(2)
  })

  it('requests Shizuku permission before opening a device terminal', async () => {
    render(<App />)
    await screen.findByText('Harness 可以启动')

    fireEvent.click(screen.getAllByRole('button', { name: '终端' })[0])
    fireEvent.click(screen.getByRole('tab', { name: '设备 Shell' }))
    fireEvent.click(screen.getByRole('button', { name: '请求授权' }))

    await waitFor(() => expect(bridge.requestShizukuPermission).toHaveBeenCalledTimes(1))
    expect(await screen.findByTestId('terminal-panel')).toBeInTheDocument()
  })

  it('renders bounded native errors as text without injecting markup', async () => {
    const unsafePrefix = '<img src=x onerror=alert(1)>'
    bridge.startHarness.mockRejectedValue(new Error(`${unsafePrefix}\n${'x'.repeat(500)}`))
    render(<App />)
    await screen.findByText('Harness 可以启动')

    fireEvent.click(screen.getByRole('button', { name: '启动 Harness' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(unsafePrefix)
    const message = alert.querySelector('span')?.textContent ?? ''
    expect(Array.from(message)).toHaveLength(240)
    expect(message).not.toContain('\n')
    expect(alert.querySelector('img')).toBeNull()
  })
})
