import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
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
  connected: false,
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
  bridge.requestShizukuPermission.mockResolvedValue({ ...shizuku, permission: 'granted', connected: true })
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

  it('shows a remote network failure instead of a completed install state', async () => {
    const notInstalled: RuntimeState = {
      phase: 'not-installed',
      architecture: 'arm64-v8a',
      downloadedBytes: 0,
      totalBytes: 0,
      runnerAvailable: true,
    }
    const failed: RuntimeState = {
      ...notInstalled,
      phase: 'error',
      totalBytes: readyState.totalBytes,
      errorCode: 'DOWNLOAD_NETWORK_UNAVAILABLE',
    }
    bridge.getState
      .mockResolvedValueOnce(notInstalled)
      .mockResolvedValueOnce(failed)
    bridge.install.mockRejectedValueOnce(new Error('网络不可用或下载连接已中断，可稍后继续'))

    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Ubuntu 环境' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '下载并安装' }))

    expect(await screen.findByText('网络不可用或下载连接已中断，可稍后继续。')).toBeInTheDocument()
    expect(screen.queryByText('正在安装')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下载并安装' })).toBeEnabled()
  })

  it('shows a safe archive-integrity message from a native error code', async () => {
    bridge.getState.mockResolvedValueOnce({
      phase: 'error',
      architecture: 'arm64-v8a',
      downloadedBytes: 0,
      totalBytes: readyState.totalBytes,
      runnerAvailable: true,
      errorCode: 'ARCHIVE_SOURCE_DIGEST_MISMATCH',
    })

    render(<App />)

    expect(await screen.findByText('解压时读取的运行时归档未通过完整性复核。')).toBeInTheDocument()
  })

  it('does not expose an unknown native error identifier', async () => {
    const internalCode = 'INTERNAL_RUNTIME_IMPLEMENTATION_DETAIL'
    bridge.getState.mockResolvedValueOnce({
      phase: 'error',
      architecture: 'arm64-v8a',
      downloadedBytes: 0,
      totalBytes: readyState.totalBytes,
      runnerAvailable: true,
      errorCode: internalCode,
    })

    render(<App />)

    expect(await screen.findByText('运行时操作失败，请稍后重试；如问题持续，请重置环境。')).toBeInTheDocument()
    expect(screen.queryByText(internalCode)).not.toBeInTheDocument()
  })

  it('keeps an installed runtime retryable after a PRoot startup failure', async () => {
    bridge.getState.mockResolvedValueOnce({
      ...readyState,
      phase: 'error',
      errorCode: 'PROOT_PTRACE_DENIED',
    })

    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Ubuntu 环境' })).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: 'Agent' })[0])

    expect(await screen.findByText('系统内核拒绝 PRoot 所需的 ptrace 操作，当前设备可能不兼容。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '启动 Harness' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '安装运行时' })).not.toBeInTheDocument()
  })

  it('accepts a normalized reset confirmation and invokes the native reset', async () => {
    render(<App />)
    await screen.findByText('Harness 可以启动')

    fireEvent.click(screen.getAllByRole('button', { name: '环境' })[0])
    fireEvent.click(screen.getByRole('button', { name: '重置' }))

    const confirmButton = screen.getByRole('button', { name: '确认重置' })
    expect(confirmButton).toBeDisabled()
    const confirmationInput = screen.getByLabelText('输入 RESET_RUNTIME 确认')
    expect(confirmationInput).not.toHaveFocus()
    fireEvent.change(confirmationInput, { target: { value: 'RESET-RUNTIME' } })
    fireEvent.submit(screen.getByRole('dialog'))
    expect(bridge.reset).not.toHaveBeenCalled()
    fireEvent.change(confirmationInput, { target: { value: ' reset_runtime ' } })
    expect(confirmButton).toBeEnabled()
    fireEvent.click(confirmButton)

    await waitFor(() => expect(bridge.reset).toHaveBeenCalledWith('RESET_RUNTIME'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect((await screen.findAllByText('未安装')).length).toBeGreaterThan(0)
  })

  it('submits reset through the mobile keyboard action', async () => {
    render(<App />)
    await screen.findByText('Harness 可以启动')

    fireEvent.click(screen.getAllByRole('button', { name: '环境' })[0])
    fireEvent.click(screen.getByRole('button', { name: '重置' }))
    fireEvent.change(screen.getByLabelText('输入 RESET_RUNTIME 确认'), { target: { value: 'RESET_RUNTIME' } })
    fireEvent.submit(screen.getByRole('dialog'))

    await waitFor(() => expect(bridge.reset).toHaveBeenCalledWith('RESET_RUNTIME'))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('shows reset progress and keeps the dialog retryable after a native failure', async () => {
    let rejectReset: (reason?: unknown) => void = () => undefined
    bridge.reset.mockReturnValueOnce(new Promise((_, reject) => { rejectReset = reject }))
    render(<App />)
    await screen.findByText('Harness 可以启动')

    fireEvent.click(screen.getAllByRole('button', { name: '环境' })[0])
    fireEvent.click(screen.getByRole('button', { name: '重置' }))
    const confirmationInput = screen.getByLabelText('输入 RESET_RUNTIME 确认')
    confirmationInput.focus()
    fireEvent.change(confirmationInput, { target: { value: 'RESET_RUNTIME' } })
    fireEvent.submit(screen.getByRole('dialog'))

    expect(confirmationInput).not.toHaveFocus()
    expect(await screen.findByRole('button', { name: '正在重置' })).toBeDisabled()
    act(() => { rejectReset(new Error('重置操作失败')) })

    expect(await screen.findByRole('alert')).toHaveTextContent('重置操作失败')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认重置' })).toBeEnabled()
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
    bridge.getShizukuState.mockResolvedValueOnce({ installed: false, running: false, permission: 'undetermined', connected: false })
    render(<App />)
    await screen.findByText('Harness 可以启动')

    fireEvent.click(screen.getAllByRole('button', { name: '终端' })[0])
    fireEvent.click(screen.getByRole('tab', { name: '设备 Shell' }))
    expect(screen.getByText('未安装 Shizuku')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '打开 Shizuku' }))
    await waitFor(() => expect(bridge.openShizuku).toHaveBeenCalledTimes(1))

    bridge.getShizukuState.mockResolvedValue({ ...shizuku, permission: 'granted', connected: true })
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
