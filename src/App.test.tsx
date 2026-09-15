import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DiagnosticLogState, KeepAliveState, RuntimeProgress, RuntimeSettings, RuntimeSettingsUpdate, RuntimeState, ShizukuState } from './platform/types'

const bridge = vi.hoisted(() => ({
  setAppLanguage: vi.fn(),
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
  getKeepAliveState: vi.fn(),
  getOverlayBallState: vi.fn(),
  openOverlaySettings: vi.fn(),
  requestNotificationPermission: vi.fn(),
  getHarnessLog: vi.fn(),
  runRuntimeSelfCheck: vi.fn(),
  managePlugins: vi.fn(),
  getDiagnosticLogState: vi.fn(),
  readDiagnosticLog: vi.fn(),
  setDiagnosticLogSettings: vi.fn(),
  shareDiagnosticLog: vi.fn(),
  shareRuntimeWorkspace: vi.fn(),
  clearDiagnosticLog: vi.fn(),
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
  // 默认视为「本机已保存过一次模型密钥」：没有密钥时应用会拦住「打开 Harness」，
  // 而这里多数用例关心的是设置与启动流程本身，门禁行为有专门用例覆盖。
  configuredModelProviders: ['deepseek'],
}

const shizuku: ShizukuState = {
  installed: true,
  running: true,
  permission: 'undetermined',
  connected: false,
}

const keepAlive: KeepAliveState = {
  keepRuntimeInBackground: false,
  foregroundServiceActive: false,
  notificationPermission: 'granted',
  deviceShellReady: false,
  reconnectRequired: false,
  lastIntent: 'stopped',
}

const diagnostic: DiagnosticLogState = {
  enabled: false,
  retentionDays: 3,
  fileCount: 0,
  totalBytes: 0,
  lastEntryAtMillis: 0,
}

/** 进入某个设置二级页：设置首页只保留分类入口。 */
async function openSettingsPage(name: string): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(name) }))
  await screen.findByRole('heading', { name })
}

beforeEach(() => {
  window.localStorage.clear()
  window.localStorage.setItem('dsh-mobile-language-v1', 'zh-CN')
  window.localStorage.setItem('dsh-mobile-onboarding-v1', '1')
  vi.clearAllMocks()
  bridge.setAppLanguage.mockResolvedValue(undefined)
  bridge.getState.mockResolvedValue({ ...readyState })
  bridge.getSettings.mockResolvedValue({ ...settings })
  bridge.getShizukuState.mockResolvedValue({ ...shizuku })
  bridge.getKeepAliveState.mockResolvedValue({ ...keepAlive })
  // 默认已授予「显示在其他应用上层」权限：与用例无关的测试不该被一个禁用开关影响。
  bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: true, serviceActive: false })
  bridge.openOverlaySettings.mockResolvedValue(undefined)
  bridge.requestNotificationPermission.mockResolvedValue({ granted: true, supported: true })
  bridge.getHarnessLog.mockResolvedValue({ available: true, text: 'Error: tool call failed\n    at run (dsh.js:1:1)', maxBytes: 8 * 1024 })
  // 默认是一次「全部正常」的自检：多数用例只关心设置页本身，不该被一个非 ok 项影响。
  bridge.runRuntimeSelfCheck.mockResolvedValue({
    operation: 'check',
    availableBytes: 4 * 1024 * 1024 * 1024,
    dshVersion: '0.1.5-rc.2',
    checks: [{ id: 'shell', status: 'ok' }, { id: 'node', status: 'ok' }],
  })
  bridge.managePlugins.mockResolvedValue({ plugins: [] })
  bridge.getDiagnosticLogState.mockResolvedValue({ ...diagnostic })
  bridge.readDiagnosticLog.mockResolvedValue({
    text: '2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE|phase=running\n2026-09-12T10:21:04Z|WARN|MODULE_GRAPH|result=failed|count=2|files=4\n',
    maxBytes: 64 * 1024,
    totalBytes: 4096,
    truncated: false,
  })
  bridge.setDiagnosticLogSettings.mockImplementation((enabled: boolean, retentionDays: number) =>
    Promise.resolve({ ...diagnostic, enabled, retentionDays }))
  bridge.clearDiagnosticLog.mockResolvedValue({ ...diagnostic })
  bridge.shareDiagnosticLog.mockResolvedValue({ ...diagnostic, fileName: 'dsh-diagnostic-20260912-102030.txt', exportedBytes: 512 })
  bridge.addRuntimeProgressListener.mockResolvedValue({ remove: vi.fn().mockResolvedValue(undefined) })
  bridge.saveSettings.mockImplementation((value: RuntimeSettingsUpdate) => Promise.resolve(value))
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
    expect(await screen.findByRole('dialog', { name: '更新 Ubuntu 运行环境' })).toHaveTextContent('会话、模型密钥、Harness 设置、附件、技能和你安装的插件会保留')
    fireEvent.click(screen.getByRole('button', { name: '确认更新' }))

    await waitFor(() => expect(bridge.install).toHaveBeenCalledWith({ manifestUrl: '', manifestSha256: '' }))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
  })

  it('没有保存过模型密钥时不打开 Harness，而是引导到「模型与密钥」', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, configuredModelProviders: [] })

    render(<App />)

    // 首次配置未完成：连启动都不做，直接落在密钥设置页并说明原因。
    expect(await screen.findByRole('heading', { name: '模型与密钥' })).toBeInTheDocument()
    expect(await screen.findByText('未检测到模型凭据：请先在「模型与密钥」保存一次 API Key 再打开 Harness')).toBeInTheDocument()
    expect(bridge.startHarness).not.toHaveBeenCalled()
    expect(bridge.openHarness).not.toHaveBeenCalled()
  })

  it('「模型与密钥」页的显式确认仍可打开 Harness', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, configuredModelProviders: [] })

    render(<App />)

    // 门禁先把用户送到「模型与密钥」；只有那里的放行按钮能跳过凭据检查。
    await screen.findByRole('heading', { name: '模型与密钥' })
    expect(bridge.openHarness).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '我已在 Harness 内配置过，仍要打开' }))
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

    // 运行时来源在「运行与后台」页。
    await openSettingsPage('运行与后台')
    expect(screen.getByText('两项留空表示使用 APK 内置的运行时（官方构建即内置，可离线安装）；填写清单地址与 SHA-256 则改为从该来源下载，两项必须成对。')).toBeInTheDocument()
    // 屏幕内返回按钮走的是历史回退，视图切换在 popstate 之后生效。
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()

    // 字号在「终端与外观」页。
    await openSettingsPage('终端与外观')
    const fontSlider = await screen.findByRole('slider', { name: /字号/ })
    fireEvent.change(fontSlider, { target: { value: '17' } })
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))

    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({ ...settings, terminalFontSize: 17 }))
    expect(await screen.findByText('设置已保存')).toBeInTheDocument()
  })

  it('saves a whitelisted provider credential update', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('模型与密钥')
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'openai' } })
    fireEvent.change(screen.getByLabelText(/OpenAI API Key/), { target: { value: 'unit-test-openai-key' } })
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))

    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({
      ...settings,
      providerApiKeys: { openai: 'unit-test-openai-key' },
    }))
  })

  it('requires an explicit bounded confirmation before resetting Ubuntu', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: /Ubuntu 运行时/ }))
    fireEvent.click(await screen.findByRole('button', { name: '重置环境' }))
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

  it('refreshes web-configured model credential status when MainActivity regains focus', async () => {
    window.localStorage.removeItem('dsh-mobile-onboarding-v1')
    bridge.getState.mockResolvedValueOnce({ ...runningState })
    bridge.getSettings
      .mockResolvedValueOnce({ ...settings, configuredModelProviders: [] })
      .mockResolvedValue({
        ...settings,
        configuredModelProviders: [],
        harnessConfiguredModelProviders: ['deepseek'],
      })
    render(<App />)

    await screen.findByRole('dialog', { name: '欢迎使用 DeepSeek Harness Android' })
    for (let index = 0; index < 5; index += 1) {
      fireEvent.click(screen.getByRole('button', { name: /下一步/ }))
    }
    expect(screen.getByText(/还没配置模型 API Key/)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /我已在 Harness 内配置过/ }))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    fireEvent.focus(window)

    await waitFor(() => expect(bridge.getSettings).toHaveBeenCalledTimes(2))
    const onboarding = screen.getByRole('dialog', { name: '开始使用' })
    expect(within(onboarding).getByRole('button', { name: /^打开 Harness$/ })).toBeVisible()
    expect(within(onboarding).queryByText(/还没配置模型 API Key/)).toBeNull()
  })

  it('keeps Shizuku disconnected until the user explicitly reconnects it', async () => {
    bridge.getShizukuState.mockResolvedValue({ ...shizuku, permission: 'granted', connected: false })
    render(<App />)

    fireEvent.focus(window)
    await waitFor(() => expect(bridge.getShizukuState).toHaveBeenCalled())
    expect(bridge.connectShizuku).not.toHaveBeenCalled()
  })

  it('shows the bounded authentication startup error without runtime details', async () => {
    bridge.getState.mockResolvedValueOnce({
      ...readyState,
      phase: 'error',
      errorCode: 'HARNESS_AUTH_UNAVAILABLE',
    })
    bridge.getSettings.mockResolvedValueOnce({ ...settings, autoLaunch: false })
    render(<App />)

    expect(await screen.findByText('Harness 未提供有效的网页认证入口，请更新运行环境后重试。')).toBeInTheDocument()
    expect(bridge.startHarness).not.toHaveBeenCalled()
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

describe('后台保持与恢复', () => {
  it('默认关闭后台保持，开启时申请通知权限并按当前值保存', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    const toggle = await screen.findByRole('switch', { name: /后台保持 Harness/ })
    expect(toggle).not.toBeChecked()
    expect(bridge.requestNotificationPermission).not.toHaveBeenCalled()

    fireEvent.click(toggle)
    expect(toggle).toBeChecked()
    await waitFor(() => expect(bridge.requestNotificationPermission).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({ ...settings, keepRuntimeInBackground: true }))
  })

  it('通知权限被拒时提示后台保持不会生效，不谎称前台服务仍在运行', async () => {
    // 不自动启动：停在主视图手动进设置，避免与启动流程抢忙碌状态。
    bridge.getSettings.mockResolvedValue({ ...settings, autoLaunch: false })
    bridge.requestNotificationPermission.mockResolvedValue({ granted: false, supported: true })
    bridge.getKeepAliveState.mockResolvedValue({ ...keepAlive, notificationPermission: 'prompt' })
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: '打开应用设置' }))
    await openSettingsPage('运行与后台')

    const toggle = await screen.findByRole('switch', { name: /后台保持 Harness/ })
    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.requestNotificationPermission).toHaveBeenCalledTimes(1))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('后台保持不会生效')
    // 旧文案声称「前台服务仍会运行」：缺少通知权限时服务根本起不来（部分 ROM 还会终结进程）。
    expect(alert).not.toHaveTextContent('仍会运行')
    // 状态行仍按原生回传如实显示未授予。
    expect(screen.getByText('未授予')).toBeVisible()
  })

  it('开启后台保持但前台服务没起来时提示未生效', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, autoLaunch: false, keepRuntimeInBackground: true })
    // 权限已授予、开关已是开启状态，但前台服务没有进入前台：以原生状态为准。
    bridge.getKeepAliveState.mockResolvedValue({
      ...keepAlive,
      keepRuntimeInBackground: true,
      foregroundServiceActive: false,
    })
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: '打开应用设置' }))
    await openSettingsPage('运行与后台')

    const saveButton = await screen.findByRole('button', { name: '保存设置' })
    await waitFor(() => expect(saveButton).toBeEnabled())
    fireEvent.click(saveButton)

    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({
      ...settings,
      autoLaunch: false,
      keepRuntimeInBackground: true,
    }))
    // 前台服务是异步拉起的：应用会等宽限期后按原生状态复核，确认仍未运行才提示未生效。
    const alert = await screen.findByRole('alert', {}, { timeout: 4000 })
    expect(alert).toHaveTextContent('设置已保存，但后台保持未生效')
    // 「已保存」不等于「已生效」：只留一句「设置已保存」是不够的。
    expect(screen.queryByText('设置已保存')).toBeNull()
  })

  it('显示前台服务、通知权限与设备 Shell 辅助状态', async () => {
    bridge.getKeepAliveState.mockResolvedValue({
      ...keepAlive,
      keepRuntimeInBackground: true,
      foregroundServiceActive: true,
      notificationPermission: 'prompt',
      deviceShellReady: true,
      lastIntent: 'running',
      lastPhase: 'running',
      lastUpdatedAtMillis: 1_700_000_000_000,
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    expect(await screen.findByText('前台服务运行中')).toBeVisible()
    expect(screen.getByText('未授予')).toBeVisible()
    // 设备 Shell 辅助只反映 Shizuku 授权状态，不代表保活能力。
    expect(screen.getByRole('button', { name: '申请通知权限' })).toBeVisible()
  })

  it('进程被系统回收后提示重新连接并可通过同一入口重启', async () => {
    bridge.getKeepAliveState.mockResolvedValue({
      ...keepAlive,
      keepRuntimeInBackground: true,
      reconnectRequired: true,
      lastIntent: 'running',
      lastPhase: 'running',
    })
    bridge.getSettings.mockResolvedValue({ ...settings, autoLaunch: false })
    render(<App />)

    expect(await screen.findByText('需要重新连接')).toBeVisible()
    // 未伪装成已恢复：残留会话不会被当作运行中，仍需用户显式重新连接。
    expect(bridge.startHarness).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '重新连接' }))
    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(1))
  })
})

describe('诊断与日志', () => {
  it('默认不收集，开关与保留天数按原生返回值更新', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    const toggle = await screen.findByRole('switch', { name: /收集诊断日志/ })
    await waitFor(() => expect(toggle).toBeEnabled())
    expect(toggle).not.toBeChecked()
    // 没有任何日志时可导出/清空按钮保持禁用，避免产生空文件。
    expect(screen.getByRole('button', { name: '导出并分享' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '清空日志' })).toBeDisabled()

    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.setDiagnosticLogSettings).toHaveBeenCalledWith(true, 3))
    expect(await screen.findByRole('switch', { name: /收集诊断日志/ })).toBeChecked()

    fireEvent.change(screen.getByRole('slider', { name: /保留天数/ }), { target: { value: '7' } })
    await waitFor(() => expect(bridge.setDiagnosticLogSettings).toHaveBeenCalledWith(true, 7))
  })

  it('有日志时可导出分享与清空，并显示计数', async () => {
    const withLogs: DiagnosticLogState = {
      enabled: true,
      retentionDays: 3,
      fileCount: 2,
      totalBytes: 4096,
      lastEntryAtMillis: 1_700_000_000_000,
    }
    bridge.getDiagnosticLogState.mockResolvedValue({ ...withLogs })
    // 导出成功返回的是导出后的状态：日志文件仍在，因此「清空日志」应保持可用。
    bridge.shareDiagnosticLog.mockResolvedValue({
      ...withLogs,
      fileName: 'dsh-diagnostic-20260912-102030.txt',
      exportedBytes: 512,
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    expect(await screen.findByText('收集中')).toBeVisible()
    expect(screen.getByText('2 个文件 · 4.0 KB')).toBeVisible()

    // 等待启动流程释放忙碌状态：run() 在忙碌时会直接忽略点击。
    const shareButton = screen.getByRole('button', { name: '导出并分享' })
    await waitFor(() => expect(shareButton).toBeEnabled())
    fireEvent.click(shareButton)
    await waitFor(() => expect(bridge.shareDiagnosticLog).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/诊断日志已导出/)).toBeVisible()

    const clearButton = screen.getByRole('button', { name: '清空日志' })
    await waitFor(() => expect(clearButton).toBeEnabled())
    fireEvent.click(clearButton)
    await waitFor(() => expect(bridge.clearDiagnosticLog).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('诊断日志已清空')).toBeVisible()
  })

  it('导出失败时保留状态并提示错误，不显示成功文案', async () => {
    bridge.getDiagnosticLogState.mockResolvedValue({
      enabled: true,
      retentionDays: 3,
      fileCount: 1,
      totalBytes: 128,
      lastEntryAtMillis: 1_700_000_000_000,
    })
    bridge.shareDiagnosticLog.mockRejectedValue(new Error('当前没有可导出的诊断日志'))
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: '导出并分享' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('当前没有可导出的诊断日志')
    expect(bridge.getDiagnosticLogState).toHaveBeenCalled()
  })

  it('运行日志折叠时不读取，展开后才按需读取并按纯文本渲染', async () => {
    const payload = '<img src=x onerror=alert(1)>\nError: tool call failed'
    bridge.getHarnessLog.mockResolvedValue({ available: true, text: payload })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    const toggle = await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    // 折叠状态不碰桥接：访客输出可能含会话内容，不展开就不带进界面。
    expect(bridge.getHarnessLog).not.toHaveBeenCalled()
    expect(document.querySelector('.harness-log-output')).toBeNull()

    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.getHarnessLog).toHaveBeenCalledTimes(1))
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    // 隐私边界必须在界面上写清，而不是只藏在文档里。
    expect(screen.getByText(/可能包含会话内容/)).toBeVisible()
    expect(screen.getByText(/不随诊断日志导出/)).toBeVisible()

    const output = document.querySelector('.harness-log-output')
    expect(output).not.toBeNull()
    // 访客输出按纯文本渲染：标签不解析，只作为文本出现，也不会产生 img 元素。
    expect(output?.querySelector('img')).toBeNull()
    expect(output?.textContent).toBe(payload)
  })

  it('没有可读取的运行日志时给出提示并隐藏复制按钮', async () => {
    bridge.getHarnessLog.mockResolvedValue({ available: false, text: '' })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ }))

    expect(await screen.findByText('当前没有可读取的运行日志')).toBeVisible()
    expect(screen.queryByRole('button', { name: '复制' })).toBeNull()
    expect(document.querySelector('.harness-log-output')).toBeNull()
  })

  it('展开后可用一键复制运行日志，复用系统剪贴板', async () => {
    const payload = 'Error: TOOL_CALL_FAILED\n    at handler (dsh.js:42:7)'
    bridge.getHarnessLog.mockResolvedValue({ available: true, text: payload })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ }))

    fireEvent.click(await screen.findByRole('button', { name: '复制' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(payload))
    expect(await screen.findByText('已复制')).toBeVisible()
  })

  it('运行日志可按关键字过滤，只渲染匹配行', async () => {
    const payload = 'INFO runtime ready\nError: TOOL_CALL_FAILED\nINFO idle'
    bridge.getHarnessLog.mockResolvedValue({ available: true, text: payload, maxBytes: 8 * 1024 })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ }))
    const output = await waitFor(() => {
      const node = document.querySelector('.harness-log-output')
      expect(node).not.toBeNull()
      return node as HTMLElement
    })
    expect(output.textContent).toBe(payload)

    fireEvent.change(screen.getByRole('searchbox', { name: '过滤日志' }), { target: { value: 'tool_call' } })
    await waitFor(() => expect(output.textContent).toBe('Error: TOOL_CALL_FAILED'))
    expect(screen.getByText('匹配 1 / 3 行')).toBeVisible()
  })

  it('展开运行日志后给出已确诊特征的判读提示', async () => {
    bridge.getHarnessLog.mockResolvedValue({
      available: true,
      text: "TypeError: Cannot read properties of undefined (reading 'prepare')",
      maxBytes: 8 * 1024,
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ }))

    const insights = await screen.findByRole('group', { name: '判读提示' })
    expect(insights).toHaveTextContent('工具调用全部失败（模块身份分裂）')
    expect(insights).toHaveTextContent('MODULE_GRAPH')
  })

  it('运行日志窗口可放大，并把窗口字节数交给原生侧', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ }))
    await waitFor(() => expect(bridge.getHarnessLog).toHaveBeenCalledWith({ maxBytes: 8 * 1024 }))

    fireEvent.change(screen.getByRole('combobox', { name: '读取窗口' }), { target: { value: String(64 * 1024) } })
    await waitFor(() => expect(bridge.getHarnessLog).toHaveBeenCalledWith({ maxBytes: 64 * 1024 }))
    // 标题跟着窗口走，用户一眼能看出自己正在看多大的一段。
    expect(await screen.findByRole('button', { name: /运行日志（最近 64 KB）/ })).toBeVisible()
  })

  it('诊断日志可在应用内查看，含截断说明与判读提示', async () => {
    bridge.readDiagnosticLog.mockResolvedValue({
      text: '2026-09-12T10:21:04Z|WARN|MODULE_GRAPH|result=failed|count=2|files=4\n',
      maxBytes: 64 * 1024,
      totalBytes: 8192,
      truncated: true,
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    const toggle = await screen.findByRole('button', { name: /诊断日志（最近 64 KB）/ })
    // 与运行日志同样按需读取：折叠时不碰桥接。
    expect(bridge.readDiagnosticLog).not.toHaveBeenCalled()

    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.readDiagnosticLog).toHaveBeenCalledWith({ maxBytes: 64 * 1024 }))
    const output = document.querySelector('.diagnostic-log-output')
    expect(output?.textContent).toBe('2026-09-12T10:21:04Z|WARN|MODULE_GRAPH|result=failed|count=2|files=4\n')
    expect(screen.getByText(/已按窗口截断/)).toBeVisible()
    expect(await screen.findByRole('group', { name: '判读提示' })).toHaveTextContent('已确认存在两份运行时模块')
  })

  it('没有诊断日志时如实说明并提示开启采集', async () => {
    bridge.readDiagnosticLog.mockResolvedValue({ text: '', maxBytes: 64 * 1024, totalBytes: 0, truncated: false })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /诊断日志（最近 64 KB）/ }))

    expect(await screen.findByText(/当前没有可查看的诊断日志/)).toBeVisible()
    expect(document.querySelector('.diagnostic-log-output')).toBeNull()
  })
})

describe('应用语言', () => {
  it('首次选择语言前不自动打开 Harness，选择英语后显示英文引导', async () => {
    window.localStorage.clear()
    render(<App />)
    expect(await screen.findByRole('heading', { name: '选择语言 / Choose your language' })).toBeVisible()
    await waitFor(() => expect(bridge.getState).toHaveBeenCalled())
    expect(bridge.startHarness).not.toHaveBeenCalled()
    expect(bridge.openHarness).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('语言 / Language'), { target: { value: 'en' } })
    fireEvent.click(screen.getByRole('button', { name: '继续 / Continue' }))
    expect(await screen.findByRole('dialog', { name: 'Welcome to DeepSeek Harness Android' })).toBeVisible()
    expect(bridge.setAppLanguage).toHaveBeenCalledWith('en')
    expect(window.localStorage.getItem('dsh-mobile-language-v1')).toBe('en')
    expect(document.documentElement.lang).toBe('en')
    expect(bridge.openHarness).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Skip setup' }))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
  })

  it('设置切换立即生效且重新挂载后保留语言', async () => {
    const view = render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '打开应用设置' }))
    fireEvent.change(screen.getByLabelText('语言'), { target: { value: 'en' } })
    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeVisible()
    expect(screen.getByText('Running', { selector: '.phase-badge' })).toBeVisible()
    // 设置首页现在只保留分类入口，保存按钮在可编辑的二级页里。
    fireEvent.click(screen.getByRole('button', { name: /Terminal and appearance/ }))
    expect(await screen.findByRole('heading', { name: 'Terminal and appearance' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Save settings' })).toBeVisible()
    view.unmount()
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Open app settings' }))
    expect(screen.getByLabelText('Language')).toHaveValue('en')
    fireEvent.change(screen.getByLabelText('Language'), { target: { value: 'zh-CN' } })
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()
    expect(document.documentElement.lang).toBe('zh-CN')
  })

  it('保存失败时保留语言选择页，不自动启动运行时', async () => {
    window.localStorage.clear()
    bridge.setAppLanguage.mockRejectedValue(new Error('LANGUAGE_SAVE_FAILED'))
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '继续 / Continue' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('无法保存语言')
    expect(window.localStorage.getItem('dsh-mobile-language-v1')).toBeNull()
    expect(bridge.openHarness).not.toHaveBeenCalled()
  })
})

describe('悬浮球设置', () => {
  it('未授予系统权限时开关不可用并给出引导入口', async () => {
    // 权限未授予时不能给一个点了没反应的开关：必须禁用并给出「去开启」的入口。
    bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: false, serviceActive: false })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    expect(await screen.findByText('悬浮球')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: /悬浮球/ })).toBeDisabled()
    // 「系统权限已关闭」只在设置里记着开启时才出现：这里设置是关着的，不能只因为没权限就报它。
    expect(screen.queryByText('系统权限已关闭')).not.toBeInTheDocument()

    // 文案以源码中的当前写法为准（界面润色把「去系统设置开启」改成了「前往系统设置开启」）。
    fireEvent.click(screen.getByRole('button', { name: '前往系统设置开启' }))
    await waitFor(() => expect(bridge.openOverlaySettings).toHaveBeenCalledTimes(1))
  })

  it('已授权时可切换开关，并随「保存设置」一起提交', async () => {
    bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: true, serviceActive: false })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    const toggle = await screen.findByRole('switch', { name: /悬浮球/ })
    expect(toggle).not.toBeChecked()

    fireEvent.click(toggle)
    // 与「后台保持 Harness」同一套模型：开关只改草稿，由「保存设置」统一提交。
    expect(toggle).toBeChecked()
    expect(bridge.saveSettings).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ overlayBallEnabled: true }),
    ))
  })

  it('开关已开启但系统权限被撤销时如实显示原因', async () => {
    // 设置里记着开启，但系统权限没了：必须把原因说出来，而不是静默失效。
    bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: false, serviceActive: false })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    expect(await screen.findByText(/系统权限已关闭/)).toBeInTheDocument()
  })

  it('系统权限被撤销但开关已开启时，仍可在应用内把它关掉', async () => {
    // 禁用只用于防「未授权时误开」：已开状态下权限消失时必须允许关闭，
    // 否则用户只能先去系统设置重新授权，才能回来关掉这个已经失效的功能。
    bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: false, serviceActive: false })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    const toggle = await screen.findByRole('switch', { name: /悬浮球/ })
    await waitFor(() => expect(toggle).toBeChecked())
    expect(toggle).toBeEnabled()

    fireEvent.click(toggle)
    expect(toggle).not.toBeChecked()

    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ overlayBallEnabled: false }),
    ))
  })

  it('进入设置二级页时重新读取设置，避免草稿把原生侧改动覆盖回去', async () => {
    // 用户可能刚用悬浮球菜单在原生侧关掉了球：不重读设置的话，进设置页看到的是旧值，
    // 一保存就把菜单的关闭动作覆盖回去。挂载读一次、进入设置页再读一次。
    bridge.getSettings.mockResolvedValueOnce({ ...settings })
    bridge.getSettings.mockResolvedValueOnce({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockResolvedValueOnce({ enabled: false, canDrawOverlays: true, serviceActive: false })
    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: true, serviceActive: true })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    expect(bridge.getSettings).toHaveBeenCalledTimes(1)

    await openSettingsPage('运行与后台')
    await waitFor(() => expect(bridge.getSettings).toHaveBeenCalledTimes(2))
    // 重读的结果必须真的进到开关上，而不只是多调了一次桥方法。
    await waitFor(() => expect(screen.getByRole('switch', { name: /悬浮球/ })).toBeChecked())
  })

  it('开启悬浮球但前台服务没起来时提示未生效', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, autoLaunch: false, overlayBallEnabled: true })
    bridge.saveSettings.mockImplementation((value: RuntimeSettingsUpdate) => Promise.resolve({ ...value, overlayBallEnabled: true }))
    // 权限已授予、开关已是开启状态，但悬浮球前台服务没有运行：以原生状态为准。
    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: true, serviceActive: false })
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: '打开应用设置' }))
    await openSettingsPage('运行与后台')

    const saveButton = await screen.findByRole('button', { name: '保存设置' })
    await waitFor(() => expect(saveButton).toBeEnabled())
    fireEvent.click(saveButton)

    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({
      ...settings,
      autoLaunch: false,
    }))
    // 悬浮球前台服务同样是异步拉起的：等宽限期后按原生状态复核，确认仍未运行才提示未生效。
    const alert = await screen.findByRole('alert', {}, { timeout: 4000 })
    expect(alert).toHaveTextContent('设置已保存，但悬浮球未生效')
    // 「已保存」不等于「已生效」：只留一句「设置已保存」是不够的。
    expect(screen.queryByText('设置已保存')).toBeNull()
  })

  it('进入设置时先读取最新值，再允许编辑，避免迟到响应覆盖输入', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    let resolveSettings!: (value: RuntimeSettings) => void
    bridge.getSettings.mockImplementationOnce(() => new Promise<RuntimeSettings>(resolve => { resolveSettings = resolve }))

    fireEvent.click(screen.getByRole('button', { name: /终端与外观/ }))
    expect(screen.getByText('正在读取设置')).toBeVisible()
    expect(screen.queryByRole('slider')).toBeNull()
    expect(screen.queryByRole('button', { name: '保存设置' })).toBeNull()

    await act(async () => {
      resolveSettings({ ...settings, terminalFontSize: 16 })
      await Promise.resolve()
    })
    const slider = await screen.findByRole('slider', { name: /字号/ })
    expect(slider).toHaveValue('16')
    fireEvent.change(slider, { target: { value: '18' } })
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(expect.objectContaining({ terminalFontSize: 18 })))
  })

  it('设置重读失败时允许重试且不能保存旧快照', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    bridge.getSettings.mockRejectedValueOnce(new Error('read failed'))
    fireEvent.click(screen.getByRole('button', { name: /运行与后台/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('无法读取最新设置，请重试')
    expect(screen.queryByRole('button', { name: '保存设置' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByRole('heading', { name: '运行与后台' })).toBeVisible()
    expect(screen.getByRole('button', { name: '保存设置' })).toBeEnabled()
  })

  it('页内用菜单隐藏悬浮球后，保存其他草稿不会重新开启它', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: true, serviceActive: true })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('终端与外观')
    const slider = screen.getByRole('slider', { name: /字号/ })
    fireEvent.change(slider, { target: { value: '19' } })

    bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: true, serviceActive: false })
    await act(async () => {
      fireEvent.focus(window)
      await Promise.resolve()
    })
    expect(slider).toHaveValue('19')
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({ ...settings, terminalFontSize: 19 }))
  })

  it('菜单关闭后即使还没收到轮询结果，也不会提交旧的悬浮球开关值', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: true, serviceActive: true })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')
    expect(screen.getByRole('switch', { name: /悬浮球/ })).toBeChecked()
    bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: true, serviceActive: false })
    // 原生菜单已关闭球，但不给前端 focus 或轮询事件，立即保存其他设置。
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(settings))
  })

  it('已修改的悬浮球草稿不被权限状态刷新覆盖', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')
    const toggle = screen.getByRole('switch', { name: /悬浮球/ })
    fireEvent.click(toggle)
    await act(async () => {
      fireEvent.focus(window)
      await Promise.resolve()
    })
    expect(toggle).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(expect.objectContaining({ overlayBallEnabled: true })))
  })

  it('悬浮球查询失败显示未知状态，重试成功后恢复权限状态', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockRejectedValue(new Error('overlay read failed'))
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')
    expect(screen.getByRole('alert')).toHaveTextContent('无法读取悬浮球状态，正在重试')
    expect(screen.queryByText('系统权限已关闭')).toBeNull()
    expect(screen.queryByRole('button', { name: '前往系统设置开启' })).toBeNull()
    const toggle = screen.getByRole('switch', { name: /悬浮球/ })
    expect(toggle).toBeChecked()
    expect(toggle).toBeEnabled()

    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: true, serviceActive: true })
    await act(async () => {
      fireEvent.focus(window)
      await Promise.resolve()
    })
    expect(screen.queryByText('无法读取悬浮球状态，正在重试')).toBeNull()
    expect(screen.getByText('在其他应用上层显示悬浮球，点按可快速回到对话')).toBeVisible()
  })

  it('设置已落盘后悬浮球查询失败不会误报保存失败', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('终端与外观')
    bridge.getOverlayBallState.mockRejectedValue(new Error('overlay read failed'))
    fireEvent.change(screen.getByRole('slider', { name: /字号/ }), { target: { value: '20' } })
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))

    expect(await screen.findByText('设置已保存，但部分状态暂时无法确认，请稍后重试')).toBeVisible()
    expect(screen.queryByText('overlay read failed')).toBeNull()
    expect(screen.getByRole('slider', { name: /字号/ })).toHaveValue('20')
  })

  it('悬浮球保存成功但状态复核失败时仍显示已保存的开关值', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')
    fireEvent.click(screen.getByRole('switch', { name: /悬浮球/ }))
    bridge.getOverlayBallState.mockRejectedValue(new Error('overlay read failed'))
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))

    expect(await screen.findByText('设置已保存，但部分状态暂时无法确认，请稍后重试')).toBeVisible()
    expect(screen.getByRole('switch', { name: /悬浮球/ })).toBeChecked()
  })
})

/**
 * 未保存的输入（典型是刚输入的 API Key）必须活到用户保存为止。
 *
 * 设置首页与五个二级页是不同组件，草稿原先存在页内、切页即随卸载消失；
 * 而重读设置又会用落盘值整体覆盖草稿 —— 两条路都会让用户「输入完做点别的，数据就没了」。
 */
describe('设置草稿与未保存的输入', () => {
  /** 从设置二级页或终端返回设置首页；屏幕内返回按钮走历史回退，视图切换在 popstate 之后生效。 */
  async function backToSettingsHome(): Promise<void> {
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    await screen.findByRole('heading', { name: '设置' })
  }

  it('区分 Harness 网页凭据与应用凭据，网页凭据不会显示为可由管理端清除', async () => {
    bridge.getSettings.mockResolvedValue({
      ...settings,
      configuredModelProviders: [],
      harnessConfiguredModelProviders: ['openai'],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('模型与密钥')
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'openai' } })

    expect(screen.getByLabelText(/OpenAI API Key/)).toHaveAttribute('placeholder', '已在 Harness 中配置，留空保持不变')
    expect(screen.queryByRole('button', { name: '清除密钥' })).toBeNull()
  })

  it('前台刷新会更新 Harness 网页凭据状态，同时保留未保存的密钥草稿', async () => {
    bridge.getSettings.mockResolvedValue({
      ...settings,
      harnessConfiguredModelProviders: [],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('模型与密钥')

    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'anthropic' } })
    fireEvent.change(screen.getByLabelText(/Anthropic API Key/), { target: { value: 'unit-test-anthropic-key' } })
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'openai' } })
    expect(screen.getByLabelText(/OpenAI API Key/)).toHaveAttribute('placeholder', '输入 API Key')

    bridge.getSettings.mockResolvedValue({
      ...settings,
      harnessConfiguredModelProviders: ['openai'],
    })
    fireEvent.focus(window)

    await waitFor(() => expect(screen.getByLabelText(/OpenAI API Key/))
      .toHaveAttribute('placeholder', '已在 Harness 中配置，留空保持不变'))
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'anthropic' } })
    expect(screen.getByLabelText(/Anthropic API Key/)).toHaveValue('unit-test-anthropic-key')
  })

  it('未保存的 API Key 在设置区内切页后仍然保留，并能随保存一起提交', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('模型与密钥')
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'openai' } })
    fireEvent.change(screen.getByLabelText(/OpenAI API Key/), { target: { value: 'unit-test-openai-key' } })

    // 用户去别的设置页看一眼再回来：刚输入的密钥与正在编辑的供应商都必须还在。
    await backToSettingsHome()
    await openSettingsPage('终端与外观')
    await backToSettingsHome()
    await openSettingsPage('模型与密钥')

    expect(screen.getByRole('combobox', { name: '供应商' })).toHaveValue('openai')
    expect(screen.getByLabelText(/OpenAI API Key/)).toHaveValue('unit-test-openai-key')

    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({
      ...settings,
      providerApiKeys: { openai: 'unit-test-openai-key' },
    }))
  })

  it('后台重读设置不会覆盖未保存的输入', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('终端与外观')
    fireEvent.change(screen.getByRole('slider', { name: /字号/ }), { target: { value: '18' } })

    // 进入每个设置二级页都会重读设置；这里让重读带回一个不同的落盘值（22）。
    bridge.getSettings.mockResolvedValue({ ...settings, terminalFontSize: 22 })
    await backToSettingsHome()
    await openSettingsPage('模型与密钥')
    await backToSettingsHome()
    await openSettingsPage('终端与外观')

    // 重读照旧发生，但草稿是「脏」的：用户输到一半的值优先。
    expect(await screen.findByRole('slider', { name: /字号/ })).toHaveValue('18')
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ terminalFontSize: 18 }),
    ))
  })

  it('保存成功后草稿与落盘值重新同步：密钥不回显、也不重复提交', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    // 原生侧只回传落盘后的设置：凭据字段不回显（真实桥接同样会把它剥掉）。
    // 这里用一个不含凭据的返回值，避免把「密钥又一次随设置对象提交」误当成草稿没清。
    bridge.saveSettings.mockImplementationOnce(() =>
      Promise.resolve({ ...settings, configuredModelProviders: ['openai'] }))

    await openSettingsPage('模型与密钥')
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'openai' } })
    fireEvent.change(screen.getByLabelText(/OpenAI API Key/), { target: { value: 'unit-test-openai-key' } })
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    expect(await screen.findByText('设置已保存')).toBeVisible()

    // 密钥落盘后不回显：草稿里那份输入随保存一起清掉，「已配置」以落盘值为准。
    const keyInput = screen.getByLabelText(/OpenAI API Key/)
    expect(keyInput).toHaveValue('')
    expect(keyInput).toHaveAttribute('placeholder', '已配置，留空保持不变')

    // 再保存一次：不会把内存里那份已经保存过的密钥重复提交上去。
    const saveButton = screen.getByRole('button', { name: '保存设置' })
    await waitFor(() => expect(saveButton).toBeEnabled())
    fireEvent.click(saveButton)
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledTimes(2))
    expect(bridge.saveSettings.mock.calls.at(-1)?.[0]).not.toHaveProperty('providerApiKeys')
  })

  it('离开设置区后未保存的输入被丢弃，下次进入以落盘值为准', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('终端与外观')
    fireEvent.change(screen.getByRole('slider', { name: /字号/ }), { target: { value: '18' } })

    // 终端不属于设置区：离开即丢弃草稿，未保存的输入与内存里的密钥都不再保留。
    await backToSettingsHome()
    fireEvent.click(screen.getByRole('button', { name: /终端与设备 Shell/ }))
    await screen.findByRole('heading', { name: '终端' })
    await backToSettingsHome()

    await openSettingsPage('终端与外观')
    expect(await screen.findByRole('slider', { name: /字号/ })).toHaveValue('14')
  })

  it('自定义供应商的字段与密钥在切页后同样保留', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('模型与密钥')
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'custom' } })
    fireEvent.click(screen.getByRole('button', { name: '添加自定义供应商' }))
    fireEvent.change(screen.getByLabelText('供应商名称'), { target: { value: '本地网关' } })
    fireEvent.change(screen.getByLabelText('Base URL'), { target: { value: 'https://gateway.example.invalid/v1' } })
    fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'sk-custom-gateway' } })
    // 必填的模型字段：表单本身会拦住缺项的提交，这两项属于同一个草稿。
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'local-model' } })
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: 'Local Model' } })

    await backToSettingsHome()
    await openSettingsPage('运行与后台')
    await backToSettingsHome()
    await openSettingsPage('模型与密钥')

    expect(screen.getByRole('combobox', { name: '供应商' })).toHaveValue('custom')
    expect(screen.getByLabelText('供应商名称')).toHaveValue('本地网关')
    expect(screen.getByLabelText('Base URL')).toHaveValue('https://gateway.example.invalid/v1')
    expect(screen.getByLabelText('API Key')).toHaveValue('sk-custom-gateway')

    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(expect.objectContaining({
      customModelProviders: [
        expect.objectContaining({ id: 'custom-1', name: '本地网关', baseUrl: 'https://gateway.example.invalid/v1' }),
      ],
      customProviderApiKeys: { 'custom-1': 'sk-custom-gateway' },
    })))
  })
})

/**
 * 运行时自检：不需要 bash 也能判断运行时断在哪一环。
 *
 * 自检面板与日志面板遵循同一条按需原则——进页面不自动跑，只有用户点了才调用；
 * 界面只渲染受控枚举（检查项、状态、结论码）映射出来的文案。
 */
describe('运行时自检', () => {
  it('进入页面不自动自检，点「运行自检」才按需调用', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    expect(await screen.findByRole('heading', { name: '运行时自检' })).toBeVisible()
    // 这句说明必须在界面上讲清「为什么不用 bash 也能查」。
    expect(screen.getByText(/不需要 bash 也能判断运行时哪一环断了/)).toBeVisible()
    expect(bridge.runRuntimeSelfCheck).not.toHaveBeenCalled()
    expect(screen.getByText('尚未自检')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '运行自检' }))

    await waitFor(() => expect(bridge.runRuntimeSelfCheck).toHaveBeenCalledWith('check'))
    expect(await screen.findByText('全部 2 项检查通过')).toBeVisible()
  })

  it('逐项显示结论与下一步，正常项默认折叠，先突出断掉的那一环', async () => {
    bridge.runRuntimeSelfCheck.mockResolvedValue({
      operation: 'check',
      availableBytes: 4 * 1024 * 1024 * 1024,
      dshVersion: null,
      checks: [
        { id: 'shell', status: 'ok' },
        { id: 'sandbox_launcher', status: 'fail', code: 'LAUNCHER_NOT_EXECUTABLE' },
        { id: 'rg', status: 'warn', code: 'RG_NOT_EXECUTABLE' },
      ],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    fireEvent.click(await screen.findByRole('button', { name: '运行自检' }))

    const results = await screen.findByRole('group', { name: '自检结果' })
    expect(results).toHaveTextContent('沙箱启动器 landlock-run')
    expect(results).toHaveTextContent('沙箱启动器没有执行位，任何被沙箱包裹的命令都无法启动')
    expect(results).toHaveTextContent('下一步：点「修复运行时权限」即可修好，不需要重新下载运行时')
    expect(results).toHaveTextContent('ripgrep 没有执行位，grep / glob 工具会失败')
    // 状态徽章如实区分 fail 与 warn，不把「注意」显示成「失败」。
    expect(results).toHaveTextContent('失败')
    expect(results).toHaveTextContent('注意')

    // 正常项默认收起：一眼看到的是断点，而不是一串「正常」。
    expect(screen.queryByText('Shell 环境（bash）')).toBeNull()
    const toggle = screen.getByRole('button', { name: '正常项（1）' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(await screen.findByText('Shell 环境（bash）')).toBeVisible()
    expect(screen.getByRole('button', { name: '收起正常项（1）' })).toBeVisible()
  })

  it('只在权限位或目录缺失这类可修的结论码上提供修复入口', async () => {
    bridge.runRuntimeSelfCheck.mockResolvedValue({
      operation: 'check',
      availableBytes: 4 * 1024 * 1024 * 1024,
      dshVersion: null,
      checks: [{ id: 'sandbox_probe', status: 'fail', code: 'PROBE_UNUSABLE' }],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    fireEvent.click(await screen.findByRole('button', { name: '运行自检' }))
    await screen.findByRole('group', { name: '自检结果' })

    // 内核不支持 Landlock 或启动器探测拿不到结果，都不是权限问题：给一个点了也没用的修复按钮等于骗用户。
    expect(screen.getByText('沙箱探测判定为不可用（内核不支持 Landlock，或启动器无法完成探测）')).toBeVisible()
    expect(screen.queryByRole('button', { name: '修复运行时权限' })).toBeNull()
  })

  it('沙箱三项失败时先给跨项汇总：说清 bash 工具为何起不来，并给出可执行指引', async () => {
    // 真机自检的沙箱三项：探测不可用、真实执行在启动器层失败、被沙箱包裹的 PTY 秒退。
    bridge.runRuntimeSelfCheck.mockResolvedValue({
      operation: 'check',
      availableBytes: 4 * 1024 * 1024 * 1024,
      dshVersion: '0.1.5-rc.2',
      checks: [
        { id: 'sandbox_launcher', status: 'ok' },
        { id: 'sandbox_probe', status: 'fail', code: 'PROBE_UNUSABLE' },
        { id: 'sandbox_exec', status: 'fail', code: 'EXEC_LAUNCHER_FAILED' },
        { id: 'pty_sandbox', status: 'fail', code: 'PTY_EXIT_EARLY' },
      ],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    fireEvent.click(await screen.findByRole('button', { name: '运行自检' }))
    await screen.findByRole('group', { name: '自检结果' })

    const summary = screen.getByText('本机没有可用的沙箱后端').closest('[role="alert"]')
    expect(summary).not.toBeNull()
    // 因果链要落到用户真正看到的那条报错上，并给出「怎么继续用」的动作，而不是只列三个失败码。
    expect(summary).toHaveTextContent('PTY shell exited during startup')
    expect(summary).toHaveTextContent('下一步：在 Harness 的权限预设里选择不启用沙箱的模式，然后重启运行环境')
    // 关掉沙箱是显式的能力降级：必须如实说明代价，不能写成「已修复」。
    expect(summary).toHaveTextContent('Landlock 的文件系统隔离')
    expect(summary).toHaveTextContent('PRoot 与 Android 应用沙箱仍然有效')

    // 汇总排在逐项列表之前：先读因果，再读每一项的细节。
    const pageText = document.body.textContent ?? ''
    expect(pageText.indexOf('本机没有可用的沙箱后端')).toBeGreaterThanOrEqual(0)
    expect(pageText.indexOf('本机没有可用的沙箱后端')).toBeLessThan(pageText.indexOf('沙箱探测判定为不可用'))
  })

  it('沙箱相关项全部正常时不给跨项汇总提示', async () => {
    bridge.runRuntimeSelfCheck.mockResolvedValue({
      operation: 'check',
      availableBytes: 4 * 1024 * 1024 * 1024,
      dshVersion: '0.1.5-rc.2',
      checks: [
        { id: 'sandbox_launcher', status: 'ok' },
        { id: 'sandbox_probe', status: 'ok' },
        { id: 'sandbox_exec', status: 'ok' },
        { id: 'pty_sandbox', status: 'ok' },
      ],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    fireEvent.click(await screen.findByRole('button', { name: '运行自检' }))

    expect(await screen.findByText('全部 4 项检查通过')).toBeVisible()
    expect(screen.queryByText('本机没有可用的沙箱后端')).toBeNull()
  })

  it('只有裸 PTY 失败或只支持部分 Landlock 能力时，不说成「沙箱后端不可用」', async () => {
    // 裸 pty 上的 PTY_EXIT_EARLY 说明断在 PTY 层；PROBE_PARTIAL 是「一般仍可用」。
    // 两者都不是「本机没有可用的沙箱后端」，汇总提示不能出现。
    bridge.runRuntimeSelfCheck.mockResolvedValue({
      operation: 'check',
      availableBytes: 4 * 1024 * 1024 * 1024,
      dshVersion: null,
      checks: [
        { id: 'sandbox_probe', status: 'warn', code: 'PROBE_PARTIAL' },
        { id: 'pty', status: 'fail', code: 'PTY_EXIT_EARLY' },
      ],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    fireEvent.click(await screen.findByRole('button', { name: '运行自检' }))
    await screen.findByRole('group', { name: '自检结果' })

    expect(screen.queryByText('本机没有可用的沙箱后端')).toBeNull()
    expect(screen.getByText('PTY 子进程在就绪前退出——与 dsh bash 工具报的是同一现象')).toBeVisible()
    expect(screen.getByText('内核只支持部分 Landlock 能力（老 ABI）')).toBeVisible()
  })

  it('启动器缺失时，跳过项与失败项显示同一条结论并给出修复入口', async () => {
    // 启动器不存在时后三项没有可测的前提，原生侧报「跳过」：界面不能把它显示成
    // 「内核不支持 Landlock」这类未经验证的结论，四行共用同一条原因与下一步。
    bridge.runRuntimeSelfCheck.mockResolvedValue({
      operation: 'check',
      availableBytes: 4 * 1024 * 1024 * 1024,
      dshVersion: null,
      checks: [
        { id: 'sandbox_launcher', status: 'fail', code: 'LAUNCHER_MISSING' },
        { id: 'sandbox_probe', status: 'skipped', code: 'LAUNCHER_MISSING' },
        { id: 'sandbox_exec', status: 'skipped', code: 'LAUNCHER_MISSING' },
        { id: 'pty_sandbox', status: 'skipped', code: 'LAUNCHER_MISSING' },
      ],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    fireEvent.click(await screen.findByRole('button', { name: '运行自检' }))

    const results = await screen.findByRole('group', { name: '自检结果' })
    expect(results).toHaveTextContent('找不到沙箱启动器 landlock-run')
    expect(results).toHaveTextContent('先点「修复运行时权限」，仍失败则重装运行时')
    expect(results).toHaveTextContent('失败')
    expect(results).toHaveTextContent('跳过')
    expect(results).not.toHaveTextContent('内核不支持 Landlock')
    // 断点在启动器本身：这是权限/缺失目录能覆盖的那一类，修复入口可用。
    expect(screen.getByRole('button', { name: '修复运行时权限' })).toBeEnabled()
  })

  it('修复成功后显示修复统计，并提示可重新自检确认', async () => {
    bridge.runRuntimeSelfCheck
      .mockResolvedValueOnce({
        operation: 'check',
        availableBytes: 4 * 1024 * 1024 * 1024,
        dshVersion: '0.1.5-rc.2',
        checks: [
          { id: 'attachments', status: 'fail', code: 'ATTACHMENTS_MISSING' },
          { id: 'pty_sandbox', status: 'fail', code: 'PTY_EXIT_EARLY' },
        ],
      })
      .mockResolvedValueOnce({
        operation: 'repair',
        availableBytes: 4 * 1024 * 1024 * 1024,
        repaired: 1,
        candidates: 2,
      })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    fireEvent.click(await screen.findByRole('button', { name: '运行自检' }))
    await screen.findByText('附件目录不存在，截图等附件无法落盘')

    fireEvent.click(screen.getByRole('button', { name: '修复运行时权限' }))

    await waitFor(() => expect(bridge.runRuntimeSelfCheck).toHaveBeenCalledWith('repair'))
    expect(await screen.findByText('已修复 1 项（检查 2 项）')).toBeVisible()
    expect(screen.getByText('可重新运行「运行自检」确认修复结果。')).toBeVisible()
  })

  it('显示运行时版本、dsh 版本与插件入口提示，不为插件数量调用插件桥接', async () => {
    bridge.runRuntimeSelfCheck.mockResolvedValue({
      operation: 'check',
      availableBytes: 300 * 1024 * 1024,
      dshVersion: '0.1.5-rc.2',
      checks: [{ id: 'shell', status: 'ok' }],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    // 版本一行不依赖自检：进页面就能看到已安装的运行时版本。
    expect(await screen.findByText('运行时版本')).toBeVisible()
    expect(screen.getByText('2026.08.17')).toBeVisible()
    expect(screen.getByText('运行自检后显示')).toBeVisible()
    expect(screen.getByText('已安装插件列表见「插件管理」')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '运行自检' }))

    expect(await screen.findByText('0.1.5-rc.2')).toBeVisible()
    expect(screen.getByText('300 MB')).toBeVisible()
    // 低于 512 MB：安装、解压与会话保存都可能失败，必须说出来。
    expect(await screen.findByText('可用空间不足')).toBeVisible()
    expect(screen.getByText(/设备可用空间低于 512 MB/)).toBeVisible()
    // 插件列表入口只是一行提示：为显示数量去进访客调用插件目录是重操作，不该发生。
    expect(bridge.managePlugins).not.toHaveBeenCalled()
  })

  it('自检失败时如实提示，不把上一次的结果继续挂在界面上', async () => {
    bridge.runRuntimeSelfCheck
      .mockResolvedValueOnce({
        operation: 'check',
        availableBytes: 4 * 1024 * 1024 * 1024,
        dshVersion: null,
        checks: [{ id: 'shell', status: 'ok' }],
      })
      .mockRejectedValueOnce(new Error('SELF_CHECK_FAILED'))
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('运行与后台')
    fireEvent.click(await screen.findByRole('button', { name: '运行自检' }))
    expect(await screen.findByText('全部 1 项检查通过')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '运行自检' }))

    expect(await screen.findByText('自检未完成，请稍后重试')).toBeVisible()
    // 要么显示本次真实结果，要么明确说读不到：旧结果不能冒充新结果。
    expect(screen.queryByText('全部 1 项检查通过')).toBeNull()
  })
})

/**
 * 空闲自动停止：运行环境会被 dsh 自己在空闲后停掉，随后又被自动拉起，
 * 端口与临时凭据都会变。没有提示时，用户会把它当成崩溃。
 */
describe('空闲自行停止', () => {
  /** 捕获原生侧推送阶段变化的监听器；应用只把这种实时信号当作「刚才还在运行」。 */
  function captureProgressListener(): (event: RuntimeProgress) => void {
    let listener: ((event: RuntimeProgress) => void) | undefined
    bridge.addRuntimeProgressListener.mockImplementationOnce((next: (event: RuntimeProgress) => void) => {
      listener = next
      return Promise.resolve({ remove: vi.fn().mockResolvedValue(undefined) })
    })
    return event => listener?.(event)
  }

  it('运行中自行停止时给出一次性提示，并在「上次停止」里留下记录', async () => {
    const emitProgress = captureProgressListener()
    bridge.getState.mockResolvedValueOnce({ ...runningState })
    // 停留在主视图：本用例要看的是提示本身，不掺启动流程。
    bridge.getSettings.mockResolvedValue({ ...settings, autoLaunch: false })
    render(<App />)
    await waitFor(() => expect(bridge.getState).toHaveBeenCalled())

    act(() => {
      emitProgress({ phase: 'ready', downloadedBytes: readyState.totalBytes, totalBytes: readyState.totalBytes })
    })

    expect(await screen.findByText('运行环境已自行停止（可能是空闲自动停止）；点「打开 Harness」会重新启动，旧会话需要重新连接。')).toBeVisible()
    expect(bridge.startHarness).not.toHaveBeenCalled()

    // 事后也能查到这次停止是谁发起的，而不是只看到「已就绪」。
    fireEvent.click(screen.getByRole('button', { name: '打开应用设置' }))
    await openSettingsPage('运行与后台')
    expect(await screen.findByText('自行停止（可能空闲自动停止）')).toBeVisible()
  })

  it('用户自己点停止时不提示自行停止，只记入「上次停止」', async () => {
    const emitProgress = captureProgressListener()
    bridge.getState.mockResolvedValueOnce({ ...runningState })
    bridge.getSettings.mockResolvedValue({ ...settings, autoLaunch: false })
    render(<App />)
    await waitFor(() => expect(bridge.getState).toHaveBeenCalled())

    fireEvent.click(await screen.findByRole('button', { name: '打开应用设置' }))
    await screen.findByRole('heading', { name: '设置' })
    fireEvent.click(screen.getByRole('button', { name: '停止' }))
    await waitFor(() => expect(bridge.stopRuntime).toHaveBeenCalledTimes(1))

    // 原生侧随后也推了一次阶段变化：用户主动停止不该被算成「自行停止」。
    act(() => {
      emitProgress({ phase: 'ready', downloadedBytes: readyState.totalBytes, totalBytes: readyState.totalBytes })
    })

    expect(screen.queryByText(/已自行停止/)).toBeNull()
    await openSettingsPage('运行与后台')
    expect(await screen.findByText('用户停止')).toBeVisible()
  })

  it('没有观察到「运行中」时，阶段变化不提示自行停止', async () => {
    const emitProgress = captureProgressListener()
    bridge.getState.mockResolvedValueOnce({ ...notInstalledState })
    render(<App />)
    await waitFor(() => expect(bridge.getState).toHaveBeenCalled())

    act(() => {
      emitProgress({ phase: 'ready', downloadedBytes: readyState.totalBytes, totalBytes: readyState.totalBytes })
    })

    expect(screen.queryByText(/已自行停止/)).toBeNull()
  })
})
