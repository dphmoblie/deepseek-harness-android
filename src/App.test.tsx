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
  connectShizuku: vi.fn(),
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
  installedVersion: '2026.08.17',
  updateAvailable: false,
  downloadedBytes: 640 * 1024 * 1024,
  totalBytes: 640 * 1024 * 1024,
  runnerAvailable: true,
}

const runningState: RuntimeState = {
  ...readyState,
  phase: 'running',
  harnessUrl: 'http://127.0.0.1:3080/',
}

const notInstalledState: RuntimeState = {
  phase: 'not-installed',
  architecture: 'arm64-v8a',
  updateAvailable: false,
  downloadedBytes: 0,
  totalBytes: readyState.totalBytes,
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
  bridge.addRuntimeProgressListener.mockResolvedValue({ remove: vi.fn().mockResolvedValue(undefined) })
  bridge.saveSettings.mockImplementation((value: RuntimeSettings) => Promise.resolve(value))
  bridge.install.mockResolvedValue(undefined)
  bridge.startHarness.mockResolvedValue({ ...runningState })
  bridge.openHarness.mockResolvedValue(undefined)
  bridge.stopRuntime.mockResolvedValue({ ...readyState })
  bridge.reset.mockResolvedValue({ ...notInstalledState })
  bridge.requestShizukuPermission.mockResolvedValue({ ...shizuku, permission: 'granted', connected: true })
  bridge.connectShizuku.mockResolvedValue({ ...shizuku, permission: 'granted', connected: true })
  bridge.openShizuku.mockResolvedValue(undefined)
})

describe('App conversation gate', () => {
  it('blocks the old Harness until the bundled runtime update is explicitly confirmed', async () => {
    bridge.getState
      .mockResolvedValueOnce({ ...readyState, updateAvailable: true })
      .mockResolvedValueOnce({ ...readyState })

    render(<App />)

    const updateButton = await screen.findByRole('button', { name: '更新运行环境' })
    expect(bridge.startHarness).not.toHaveBeenCalled()
    expect(bridge.openHarness).not.toHaveBeenCalled()

    fireEvent.click(updateButton)
    expect(await screen.findByRole('dialog', { name: '更新 Ubuntu 运行环境' })).toHaveTextContent('本地修改和未导出的文件将被清除')
    fireEvent.click(screen.getByRole('button', { name: '确认更新' }))

    await waitFor(() => expect(bridge.install).toHaveBeenCalledWith({ manifestUrl: '', manifestSha256: '' }))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
  })

  it('starts a ready runtime and opens Harness automatically in order', async () => {
    render(<App />)

    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    expect(bridge.startHarness).toHaveBeenCalledTimes(1)
    expect(bridge.startHarness.mock.invocationCallOrder[0]).toBeLessThan(bridge.openHarness.mock.invocationCallOrder[0] ?? 0)
    expect(await screen.findByRole('heading', { name: '设置' })).toBeInTheDocument()
    expect(document.querySelector('iframe')).not.toBeInTheDocument()
  })

  it('opens an already-running Harness without starting it again', async () => {
    bridge.getState.mockResolvedValueOnce({ ...runningState })

    render(<App />)

    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    expect(bridge.startHarness).not.toHaveBeenCalled()
  })

  it('keeps first run on setup, then installs and enters the conversation', async () => {
    bridge.getState
      .mockResolvedValueOnce({ ...notInstalledState })
      .mockResolvedValueOnce({ ...readyState })

    render(<App />)

    const install = await screen.findByRole('button', { name: '安装并进入对话' })
    expect(bridge.openHarness).not.toHaveBeenCalled()
    fireEvent.click(install)

    await waitFor(() => expect(bridge.install).toHaveBeenCalledWith({
      manifestUrl: settings.manifestUrl,
      manifestSha256: settings.manifestSha256,
    }))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    expect(bridge.startHarness).toHaveBeenCalledTimes(1)
  })

  it('waits for an in-progress install to become ready before launching', async () => {
    let progressListener: ((event: RuntimeProgress) => void) | undefined
    bridge.getState.mockResolvedValueOnce({ ...notInstalledState, phase: 'downloading', downloadedBytes: 32 })
    bridge.addRuntimeProgressListener.mockImplementationOnce((listener: (event: RuntimeProgress) => void) => {
      progressListener = listener
      return Promise.resolve({ remove: vi.fn().mockResolvedValue(undefined) })
    })

    render(<App />)
    expect((await screen.findAllByText('下载中')).length).toBeGreaterThan(0)
    expect(bridge.startHarness).not.toHaveBeenCalled()

    act(() => {
      progressListener?.({
        phase: 'ready',
        downloadedBytes: readyState.totalBytes,
        totalBytes: readyState.totalBytes,
      })
    })

    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
  })

  it('does not let an optional Shizuku failure block Harness startup', async () => {
    bridge.getShizukuState.mockRejectedValueOnce(new Error('Shizuku unavailable'))

    render(<App />)

    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    expect(bridge.startHarness).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('Shizuku unavailable')).not.toBeInTheDocument()
  })

  it('shows a remote network failure instead of a completed install state', async () => {
    const failed: RuntimeState = {
      ...notInstalledState,
      phase: 'error',
      errorCode: 'DOWNLOAD_NETWORK_UNAVAILABLE',
    }
    bridge.getState
      .mockResolvedValueOnce({ ...notInstalledState })
      .mockResolvedValueOnce(failed)
    bridge.install.mockRejectedValueOnce(new Error('网络不可用或下载连接已中断，可稍后继续'))

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '安装并进入对话' }))

    expect(await screen.findByText('网络不可用或下载连接已中断，可稍后继续。')).toBeInTheDocument()
    expect(screen.queryByText('正在安装')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试安装' })).toBeEnabled()
    expect(bridge.openHarness).not.toHaveBeenCalled()
  })

  it('keeps terminal and Shizuku controls inside settings', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: /终端与设备 Shell/ }))
    expect(await screen.findByRole('heading', { name: '终端' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: '设备 Shell' }))
    fireEvent.click(screen.getByRole('button', { name: '请求授权' }))

    await waitFor(() => expect(bridge.requestShizukuPermission).toHaveBeenCalledTimes(1))
    expect(await screen.findByTestId('terminal-panel')).toBeInTheDocument()
  })

  it('saves source and terminal preferences from settings', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    expect(screen.getByText('官方包已固定下载源；仅内嵌开发包可留空')).toBeInTheDocument()
    const fontSlider = await screen.findByRole('slider', { name: /字号/ })
    fireEvent.change(fontSlider, { target: { value: '17' } })
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))

    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({ ...settings, terminalFontSize: 17 }))
    expect(await screen.findByText('设置已保存')).toBeInTheDocument()
  })

  it('requires an explicit bounded confirmation before resetting Ubuntu', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: /Ubuntu 运行时/ }))
    fireEvent.click(await screen.findByRole('button', { name: '重置' }))
    const confirmation = screen.getByLabelText('输入 RESET_RUNTIME 确认')
    fireEvent.change(confirmation, { target: { value: ' reset_runtime ' } })
    fireEvent.click(screen.getByRole('button', { name: '确认重置' }))

    await waitFor(() => expect(bridge.reset).toHaveBeenCalledWith('RESET_RUNTIME'))
    expect(await screen.findByRole('button', { name: '安装并进入对话' })).toBeInTheDocument()
  })

  it('does not reopen Harness when MainActivity regains focus', async () => {
    bridge.getState.mockResolvedValueOnce({ ...runningState })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    fireEvent.focus(window)
    await waitFor(() => expect(bridge.getShizukuState).toHaveBeenCalled())
    expect(bridge.openHarness).toHaveBeenCalledTimes(1)
  })

  it('renders bounded launch errors as text without injecting markup', async () => {
    const unsafePrefix = '<img src=x onerror=alert(1)>'
    bridge.startHarness.mockRejectedValueOnce(new Error(`${unsafePrefix}\n${'x'.repeat(500)}`))
    render(<App />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(unsafePrefix)
    const message = alert.querySelector('span')?.textContent ?? ''
    expect(Array.from(message)).toHaveLength(240)
    expect(message).not.toContain('\n')
    expect(alert.querySelector('img')).toBeNull()
  })
})
