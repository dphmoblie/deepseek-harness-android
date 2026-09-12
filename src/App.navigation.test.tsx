import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DiagnosticLogState, KeepAliveState, RuntimeSettings, RuntimeSettingsUpdate, RuntimeState, ShizukuState } from './platform/types'

/**
 * 返回键与视图历史。
 *
 * 外壳（MainActivity 里的 WebView）过去只改 React 状态：视图切换不产生历史记录，
 * WebView 的 canGoBack() 恒为 false，Android 的返回键/返回手势抵达时直接结束 Activity ——
 * 表现就是「在设置二级页按返回，应用直接退出」。
 *
 * 这里覆盖修复后的契约：
 *  1. 每次视图切换都写一条历史记录；
 *  2. 返回键/返回手势按历史逐级回退：二级设置页 → 设置一级 → 主视图；
 *  3. 主视图是历史栈底，再按返回没有任何可回退的记录（原生侧据此把任务退到后台，
 *     不结束应用），界面与历史都不再变化；
 *  4. 同一视图重复导航不写冗余记录；
 *  5. 历史状态异常时回落到主视图，并把地址校正回去，避免视图与地址不同步。
 */

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
  requestNotificationPermission: vi.fn(),
  getDiagnosticLogState: vi.fn(),
  setDiagnosticLogSettings: vi.fn(),
  shareDiagnosticLog: vi.fn(),
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

/** 关掉自动启动，用例才能停在主视图并按需逐级进入设置。 */
const settings: RuntimeSettings = {
  manifestUrl: 'https://downloads.example.invalid/runtime.json',
  manifestSha256: 'a'.repeat(64),
  keepScreenAwake: true,
  terminalFontSize: 14,
  configuredModelProviders: [],
  autoLaunch: false,
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

/** 主视图（对话门面）的标题。 */
function mainViewHeading(): HTMLElement {
  return screen.getByRole('heading', { name: '正在进入工作区' })
}

/** 等待首屏渲染完成并停在外壳主视图。 */
async function renderAtMainView(): Promise<void> {
  render(<App />)
  expect(await screen.findByRole('heading', { name: '正在进入工作区' })).toBeVisible()
}

/**
 * 模拟系统返回键/返回手势：原生侧先回退 WebView 历史，popstate 再把视图恢复成上一级。
 *
 * jsdom 的历史遍历是异步的（两级 setTimeout 任务），必须等它跑完再断言；
 * 断言放进 act 里也能避免 popstate 触发的更新落在 React 的批量更新之外。
 */
async function pressBack(): Promise<void> {
  await act(async () => {
    window.history.back()
    await new Promise(resolve => window.setTimeout(resolve, 0))
    await new Promise(resolve => window.setTimeout(resolve, 0))
  })
}

/** 模拟浏览器的「前进」按钮：与返回一样只改变历史位置，视图必须跟着历史走。 */
async function pressForward(): Promise<void> {
  await act(async () => {
    window.history.forward()
    await new Promise(resolve => window.setTimeout(resolve, 0))
    await new Promise(resolve => window.setTimeout(resolve, 0))
  })
}

beforeEach(() => {
  window.localStorage.clear()
  window.localStorage.setItem('dsh-mobile-language-v1', 'zh-CN')
  window.localStorage.setItem('dsh-mobile-onboarding-v1', '1')
  // jsdom 的会话历史在整个测试文件内共享：把当前记录复位成干净的根地址，
  // 保证每个用例的首屏都是主视图，也不会读到上一个用例留下的片段。
  window.history.replaceState(null, '', '/')
  vi.clearAllMocks()
  bridge.setAppLanguage.mockResolvedValue(undefined)
  bridge.getState.mockResolvedValue({ ...readyState })
  bridge.getSettings.mockResolvedValue({ ...settings })
  bridge.getShizukuState.mockResolvedValue({ ...shizuku })
  bridge.getKeepAliveState.mockResolvedValue({ ...keepAlive })
  bridge.getDiagnosticLogState.mockResolvedValue({ ...diagnostic })
  bridge.addRuntimeProgressListener.mockResolvedValue({ remove: vi.fn().mockResolvedValue(undefined) })
  bridge.saveSettings.mockImplementation((value: RuntimeSettingsUpdate) => Promise.resolve(value))
  bridge.startHarness.mockResolvedValue({ ...readyState, phase: 'running' })
  bridge.openHarness.mockResolvedValue(undefined)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('返回键与视图历史', () => {
  it('二级设置页逐级返回：回到设置一级，再回到主视图', async () => {
    await renderAtMainView()

    fireEvent.click(screen.getByRole('button', { name: '打开应用设置' }))
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /模型与凭据/ }))
    expect(await screen.findByRole('heading', { name: '模型与凭据' })).toBeVisible()
    expect(window.location.hash).toBe('#settings-models')

    // 二级 → 一级：视图回到设置首页，地址同步回一级页。
    await pressBack()
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()
    expect(window.location.hash).toBe('#settings')

    // 一级 → 主视图。
    await pressBack()
    expect(await screen.findByRole('heading', { name: '正在进入工作区' })).toBeVisible()
    expect(window.location.hash).toBe('')
    expect(window.history.state).toMatchObject({ dshView: 'conversation' })
  })

  it('退回主视图后历史已见底：再按返回不离开界面，也不新增记录', async () => {
    await renderAtMainView()

    fireEvent.click(screen.getByRole('button', { name: '打开应用设置' }))
    await screen.findByRole('heading', { name: '设置' })
    await pressBack()
    expect(await screen.findByRole('heading', { name: '正在进入工作区' })).toBeVisible()

    // 主视图是历史栈底：原生侧此时 canGoBack() 为 false，会把任务退到后台而不是结束应用。
    const pushState = vi.spyOn(window.history, 'pushState')
    await pressBack()

    expect(mainViewHeading()).toBeVisible()
    expect(pushState).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('')
  })

  it('浏览器的前进/后退与视图状态保持一致', async () => {
    await renderAtMainView()

    fireEvent.click(screen.getByRole('button', { name: '打开应用设置' }))
    fireEvent.click(await screen.findByRole('button', { name: /模型与凭据/ }))
    expect(await screen.findByRole('heading', { name: '模型与凭据' })).toBeVisible()

    await pressBack()
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()

    // 前进回到二级页：视图与地址一起前进，不会出现「界面在一级、地址停在二级」。
    await pressForward()
    expect(await screen.findByRole('heading', { name: '模型与凭据' })).toBeVisible()
    expect(window.location.hash).toBe('#settings-models')

    await pressBack()
    await pressBack()
    expect(await screen.findByRole('heading', { name: '正在进入工作区' })).toBeVisible()
    expect(window.location.hash).toBe('')
  })

  it('重复导航到同一视图不会写入冗余历史', async () => {
    await renderAtMainView()

    fireEvent.click(screen.getByRole('button', { name: '打开应用设置' }))
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()

    const pushState = vi.spyOn(window.history, 'pushState')
    // 设置页的「打开 Harness」在启动成功后还会再切到 settings（同一视图）：
    // 这属于同视图重复导航，不应再压一条历史记录。
    fireEvent.click(screen.getByRole('button', { name: '打开 Harness' }))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    expect(pushState).not.toHaveBeenCalled()
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()

    // 收尾：回到栈底，避免把历史位置留给下一个用例。
    await pressBack()
    expect(await screen.findByRole('heading', { name: '正在进入工作区' })).toBeVisible()
  })

  it('屏幕内返回按钮回退历史，不留下会被返回键重新进入的二级页记录', async () => {
    await renderAtMainView()

    fireEvent.click(screen.getByRole('button', { name: '打开应用设置' }))
    fireEvent.click(await screen.findByRole('button', { name: /模型与凭据/ }))
    expect(await screen.findByRole('heading', { name: '模型与凭据' })).toBeVisible()

    const pushState = vi.spyOn(window.history, 'pushState')
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()
    // 屏幕内的返回是「回退」，不能再压一条设置记录。
    expect(pushState).not.toHaveBeenCalled()

    // 因此此时按系统返回必须回主视图，而不是被送回二级页。
    await pressBack()
    expect(await screen.findByRole('heading', { name: '正在进入工作区' })).toBeVisible()
    expect(window.location.hash).toBe('')
  })

  it('历史状态无法识别时回落到主视图并校正地址', async () => {
    await renderAtMainView()

    fireEvent.click(screen.getByRole('button', { name: '打开应用设置' }))
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()

    act(() => {
      // 模拟 WebView 恢复历史或外部写入：当前记录的地址与状态都不是本应用认识的视图。
      window.history.replaceState(null, '', '#unknown-view')
      window.dispatchEvent(new PopStateEvent('popstate', { state: null }))
    })

    expect(await screen.findByRole('heading', { name: '正在进入工作区' })).toBeVisible()
    expect(window.location.hash).toBe('')

    // 收尾：回到栈底。
    await pressBack()
    expect(mainViewHeading()).toBeVisible()
  })
})
