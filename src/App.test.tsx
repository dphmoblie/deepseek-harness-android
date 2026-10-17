import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { arriveAtSettingsHome } from './__tests__/appTestHarness'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { arriveAtShell, beforeEachAppTest, bridge, notInstalledState, openSettingsPage, readyState, runningState, settings, shizuku } from './__tests__/appTestHarness'
import type { RuntimeProgress, RuntimeState } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 启动门禁与语言：应用从冷启动到「能进对话」这一段的前置条件。
 *
 * 覆盖：运行环境未安装 / 下载中 / 可更新 / 启动失败的错误呈现，模型凭据门禁，
 * 重置环境的二次确认，以及首次启动的语言选择与后续切换。
 *
 * 从原来的单文件 `App.test.tsx`（84 用例 / 43 秒）按 view 拆出（登记册 5.6-J）：
 * 这一组只关心「启动那一刻应用做了什么决定」，与设置页、诊断页的行为分开后，
 * 失败时不必在 1900 行里找上下文。共享的桥接 mock 与固定数据见 `./__tests__/appTestHarness`。
 */
describe('App conversation gate', () => {
  beforeEach(beforeEachAppTest)

  it('blocks the old Harness until the bundled runtime update is explicitly confirmed', async () => {
    bridge.getState
      .mockResolvedValueOnce({ ...readyState, updateAvailable: true })
      .mockResolvedValueOnce({ ...readyState })

    render(<App />)

    const updateButton = await screen.findByRole('button', { name: '更新运行环境' })
    expect(bridge.startHarness).not.toHaveBeenCalled()
    expect(bridge.openHarness).not.toHaveBeenCalled()

    fireEvent.click(updateButton)
    const updateDialog = await screen.findByRole('dialog', { name: '更新 Ubuntu 运行环境' })
    expect(updateDialog).toHaveTextContent('会话、模型密钥、Harness 设置、附件、技能、默认工作区，以及插件（包括终端安装的第三方插件）会自动备份并尝试恢复')
    expect(updateDialog).toHaveTextContent('若恢复发生冲突，请到插件管理检查')
    fireEvent.click(screen.getByRole('button', { name: '确认更新' }))

    await waitFor(() => expect(bridge.install).toHaveBeenCalledWith({ manifestUrl: '', manifestSha256: '' }))
  await arriveAtSettingsHome()
  })

  it('没有保存过模型密钥时点打开只会给出引导，不会启动运行时', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, configuredModelProviders: [] })

    render(<App />)
    await arriveAtSettingsHome()

    // 首次配置未完成：点「打开 Harness」既不启动也不开门，而是送到密钥设置页并说明原因。
    fireEvent.click(screen.getByRole('button', { name: /打开 Harness/ }))

    expect(await screen.findByRole('heading', { name: '模型与密钥' })).toBeInTheDocument()
    expect(await screen.findByText('未检测到模型凭据：请先在「模型与密钥」保存一次 API Key 再打开 Harness')).toBeInTheDocument()
    expect(bridge.startHarness).not.toHaveBeenCalled()
    expect(bridge.openHarness).not.toHaveBeenCalled()
  })

  it('「模型与密钥」页的显式确认仍可打开 Harness', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, configuredModelProviders: [] })

    render(<App />)
    await arriveAtSettingsHome()

    // 门禁先把用户送到「模型与密钥」；只有那里的放行按钮能跳过凭据检查。
    fireEvent.click(screen.getByRole('button', { name: /打开 Harness/ }))
    await screen.findByRole('heading', { name: '模型与密钥' })
    expect(bridge.openHarness).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '我已在 Harness 内配置过，仍要打开' }))

    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
  })

  it('开机不自动启动也不自动打开 Harness', async () => {
    render(<App />)
    await arriveAtShell()

    /*
     * 旧行为是「打开应用就自动进 Harness」，而且原生侧把 autoLaunch 写死成 true，
     * 于是没有开关也照进不误；冷启动还会占住忙锁，用户从 Harness 返回后点不回来。
     * 现在入口只认用户点击：开机既不启动也不开门。
     */
    expect(screen.getByRole('button', { name: /打开对话|打开 Harness/ })).toBeInTheDocument()
    expect(bridge.startHarness).not.toHaveBeenCalled()
    expect(bridge.openHarness).not.toHaveBeenCalled()
    expect(document.querySelector('iframe')).not.toBeInTheDocument()
  })

  it('点「打开 Harness」时先启动运行时，再打开页面', async () => {
    render(<App />)
    await arriveAtSettingsHome()

    fireEvent.click(screen.getByRole('button', { name: /打开 Harness/ }))

    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    expect(bridge.startHarness.mock.invocationCallOrder[0]).toBeLessThan(bridge.openHarness.mock.invocationCallOrder[0])
    expect(document.querySelector('iframe')).not.toBeInTheDocument()
  })

  it('运行时已在运行时就跳过启动，只打开页面', async () => {
    bridge.getState.mockResolvedValueOnce({ ...runningState })

    render(<App />)
    await arriveAtSettingsHome()

    fireEvent.click(screen.getByRole('button', { name: /打开 Harness/ }))

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
    // 安装完成回到对话门禁页，启动仍要用户点一下：不再有「装完自动开门」。
    const open = await screen.findByRole('button', { name: /打开对话|打开 Harness/ })
    expect(bridge.startHarness).not.toHaveBeenCalled()
    fireEvent.click(open)
    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(1))
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
    // 门屏进度条旁即时展示已用时间，下载进行中也能确认界面没有卡死。
    expect(screen.getByText(/已用 \d+ 秒/)).toBeVisible()
    expect(bridge.startHarness).not.toHaveBeenCalled()

    act(() => {
      progressListener?.({
        phase: 'ready',
        downloadedBytes: readyState.totalBytes,
        totalBytes: readyState.totalBytes,
      })
    })

  await arriveAtSettingsHome()
  })

  it('does not let an optional Shizuku failure block Harness startup', async () => {
    bridge.getShizukuState.mockRejectedValueOnce(new Error('Shizuku unavailable'))

    render(<App />)
    await arriveAtSettingsHome()

    fireEvent.click(screen.getByRole('button', { name: /打开 Harness/ }))

    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(1))
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
  await arriveAtSettingsHome()

    fireEvent.click(screen.getByRole('button', { name: /^设备 Shell 设备上的 Shell/ }))
    expect(await screen.findByRole('heading', { name: '终端' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: '设备 Shell' }))
    fireEvent.click(screen.getByRole('button', { name: '请求授权' }))

    await waitFor(() => expect(bridge.requestShizukuPermission).toHaveBeenCalledTimes(1))
    expect(await screen.findByTestId('terminal-panel')).toBeInTheDocument()
  })

  it('saves source and terminal preferences from settings', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    // 运行时来源在「后台」页。
    await openSettingsPage('后台')
    expect(screen.getByText('运行环境使用 APK 内置的受校验版本；需要其它版本时请在 Ubuntu 运行时的版本管理中下载并切换。')).toBeInTheDocument()
    // 屏幕内返回按钮走的是历史回退，视图切换在 popstate 之后生效。
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()

    // 字号在「终端与外观」页。
    await openSettingsPage('主题')
    const fontSlider = await screen.findByRole('slider', { name: /字号/ })
    fireEvent.change(fontSlider, { target: { value: '17' } })
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({ ...settings, terminalFontSize: 17 }))
    expect(await screen.findByText('设置已保存')).toBeInTheDocument()
  })

  it('saves a whitelisted provider credential update', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('模型与密钥')
    fireEvent.click(screen.getByRole('button', { name: /^OpenAI / }))
    fireEvent.change(screen.getByLabelText(/OpenAI API Key/), { target: { value: 'unit-test-openai-key' } })
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({
      ...settings,
      providerApiKeys: { openai: 'unit-test-openai-key' },
    }))
  })

  it('requires a bounded consideration time before resetting Ubuntu', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    fireEvent.click(screen.getByRole('button', { name: /Ubuntu 运行时/ }))
    fireEvent.click(await screen.findByRole('button', { name: '重置环境' }))

    // 考虑时间内按钮保持禁用：点它不会触发任何重置动作。
    const counting = await screen.findByRole('button', { name: /请稍候 \d 秒/ })
    expect(counting).toBeDisabled()
    fireEvent.click(counting)
    expect(bridge.reset).not.toHaveBeenCalled()

    fireEvent.click(await screen.findByRole('button', { name: '确认重置' }, { timeout: 5_000 }))

    await waitFor(() => expect(bridge.reset).toHaveBeenCalledWith('RESET_RUNTIME'))
    expect(await screen.findByRole('button', { name: '安装并进入对话' })).toBeInTheDocument()
  })

  it('does not reopen Harness when MainActivity regains focus', async () => {
    bridge.getState.mockResolvedValueOnce({ ...runningState })
    render(<App />)
    await arriveAtSettingsHome()

    fireEvent.focus(window)
    await waitFor(() => expect(bridge.getShizukuState).toHaveBeenCalled())
    // 回到前台只刷新状态，绝不替用户重开页面：开机不自动开，回前台也不自动开。
    expect(bridge.openHarness).not.toHaveBeenCalled()
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
    expect(screen.getByText(/尚未检测到模型 API Key/)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /我已在 Harness 内配置过/ }))
  await arriveAtSettingsHome()

    fireEvent.focus(window)

    await waitFor(() => expect(bridge.getSettings).toHaveBeenCalledTimes(2))
    const onboarding = screen.getByRole('dialog', { name: '开始使用' })
    expect(within(onboarding).getByRole('button', { name: /^打开 Harness$/ })).toBeVisible()
    expect(within(onboarding).queryByText(/尚未检测到模型 API Key/)).toBeNull()
  })

  it('leaves automatic Shizuku connection to the native lifecycle', async () => {
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
    bridge.getSettings.mockResolvedValueOnce({ ...settings})
    render(<App />)

    expect(await screen.findByText('Harness 未提供有效的网页认证入口，请更新运行环境后重试。')).toBeInTheDocument()
    expect(bridge.startHarness).not.toHaveBeenCalled()
  })

  it('renders bounded launch errors as text without injecting markup', async () => {
    const unsafePrefix = '<img src=x onerror=alert(1)>'
    bridge.startHarness.mockRejectedValueOnce(new Error(`${unsafePrefix}\n${'x'.repeat(500)}`))
    render(<App />)
    await arriveAtSettingsHome()

    // 错误只在用户点了「打开 Harness」之后才会出现。
    fireEvent.click(screen.getByRole('button', { name: /打开 Harness/ }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(unsafePrefix)
    const message = alert.querySelector('span')?.textContent ?? ''
    expect(Array.from(message)).toHaveLength(240)
    expect(message).not.toContain('\n')
    expect(alert.querySelector('img')).toBeNull()
  })
})

describe('应用语言', () => {
  beforeEach(beforeEachAppTest)

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
    // 引导结束后停在对话门禁页，仍然不会自动打开 Harness。
    expect(await screen.findByRole('button', { name: 'Open chat' })).toBeInTheDocument()
    expect(bridge.openHarness).not.toHaveBeenCalled()
  })

  it('设置切换立即生效且重新挂载后保留语言', async () => {
    const view = render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: '打开应用设置' }))
    fireEvent.change(screen.getByLabelText('语言'), { target: { value: 'en' } })
    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeVisible()
    // 不再有「开机自动启动」，因此徽章停在就绪/未启动，而不是 Running。
    expect(bridge.startHarness).not.toHaveBeenCalled()
    // 设置首页现在只保留分类入口，编辑项会自动保存。
    fireEvent.click(screen.getByRole('button', { name: /^Theme/ }))
    expect(await screen.findByRole('heading', { name: 'Theme' })).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Save settings' })).toBeNull()
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
