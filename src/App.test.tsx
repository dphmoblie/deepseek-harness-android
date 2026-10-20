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

/** 触发一次「切到后台」：页面隐藏时 App 自动保存当前设置草稿（页面已无保存按钮）。 */
async function flushOnHide(): Promise<void> {
  await act(async () => {
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    await Promise.resolve()
    // 只还原状态，不再发一次 visibilitychange：否则 App 会把可见事件当成『回前台』去重读设置，
    // 用夹具里的旧值覆盖刚保存的结果（真机上原生会回已落盘值，不会出现这个问题）。
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  })
}

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
    expect(await screen.findByText('未检测到模型凭据：请先在「模型与密钥」登录 DeepSeek 账号或保存一次 API Key 再打开 Harness')).toBeInTheDocument()
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

  it('DeepSeek 账号已登录时，无 API Key 也可直接打开 Harness', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, configuredModelProviders: [], deepSeekAccountConfigured: true })
    render(<App />)
    await arriveAtSettingsHome()

    fireEvent.click(screen.getByRole('button', { name: /打开 Harness/ }))

    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('heading', { name: '模型与密钥' })).not.toBeInTheDocument()
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

  it('空闲停在门屏时不说「正在打开」，只给出就绪提示', async () => {
    render(<App />)
    await arriveAtShell()

    /*
     * 用户报的原话是「启动器首页正在打开 harness 的表述不恰当」：自动启动移除后，
     * 开机停在门屏是常态，但卡片照旧写着「正在进入对话 / 正在打开 Harness 对话」，
     * 看上去像卡在启动流程里。这条用例把「空闲态不许出现进行时」钉死。
     */
    expect(screen.getByRole('heading', { name: '对话已就绪' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '点击「打开对话」开始' })).toBeInTheDocument()
    expect(screen.getByText('打开对话时才会启动本机服务。')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '正在进入对话' })).not.toBeInTheDocument()
    expect(screen.queryByText('正在打开 Harness 对话')).not.toBeInTheDocument()
  })

  it('服务已在运行时说明「正在运行」，而不是「打开时才启动」', async () => {
    bridge.getState.mockResolvedValue({ ...runningState })

    render(<App />)
    await arriveAtShell()

    // 「已在运行」与「已就绪」是两件事：前者打开不用等，后者打开要等启动。
    expect(screen.getByRole('heading', { name: '点击「打开对话」进入对话' })).toBeInTheDocument()
    expect(screen.getByText('本机服务正在运行，打开后即可继续对话。')).toBeInTheDocument()
    expect(screen.queryByText('打开对话时才会启动本机服务。')).not.toBeInTheDocument()
  })

  it('点开对话后门屏如实显示正在启动，直到桥返回', async () => {
    // 桥永不 settle：把 busy 固定在 'launch'，观察这段时间的文案。
    bridge.startHarness.mockImplementation(() => new Promise(() => {}))

    render(<App />)
    await arriveAtShell()

    fireEvent.click(screen.getByRole('button', { name: '打开对话' }))

    expect(await screen.findByRole('heading', { name: '正在进入对话' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '正在打开 Harness 对话' })).toBeInTheDocument()
    expect(screen.getByText('正在启动本机服务并进入对话…')).toBeInTheDocument()
  })

  it('停止过程中说明正在停止，而不是提示「点击打开」', async () => {
    // 停止要几秒，期间打开按钮禁用；此时说「点击「打开对话」开始」等于让用户去点一个点不动的按钮。
    bridge.getState.mockResolvedValue({ ...runningState })
    bridge.stopRuntime.mockImplementation(() => new Promise(() => {}))

    render(<App />)
    await arriveAtShell()

    fireEvent.click(screen.getByRole('button', { name: '停止' }))

    expect(await screen.findByRole('heading', { name: '正在停止运行环境' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '正在停止本机服务' })).toBeInTheDocument()
    expect(screen.getByText('停止完成后即可重新打开对话。')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '点击「打开对话」进入对话' })).not.toBeInTheDocument()
  })

  it('开启「打开应用时预启动服务」后：开机只拉起服务，不打开对话页', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, prewarmHarness: true })

    render(<App />)
    await arriveAtShell()

    // 预启动只调 startHarness：页面绝不由它打开，用户仍要自己点「打开对话」。
    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(1))
    expect(bridge.openHarness).not.toHaveBeenCalled()
    expect(document.querySelector('iframe')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '正在进入对话' })).not.toBeInTheDocument()
  })

  it('预启动还没回来时点打开对话：等它，不重复启动也不报错', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, prewarmHarness: true })
    let finishPrewarm!: (state: typeof runningState) => void
    bridge.startHarness.mockImplementationOnce(() => new Promise(resolve => { finishPrewarm = resolve }))

    render(<App />)
    await arriveAtShell()
    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: '打开对话' }))
    await act(async () => { await Promise.resolve() })

    // 关键：不能因为「预启动在飞」就再发一次 startHarness——原生会立刻回报未运行，
    // 紧接着的打开请求就会失败，用户明明点了打开却收到一条错误。
    expect(bridge.startHarness).toHaveBeenCalledTimes(1)

    await act(async () => { finishPrewarm({ ...runningState }); await Promise.resolve() })

    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    expect(bridge.startHarness).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('开机时服务已在运行，用户手动停止后不会被预启动再拉起来', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, prewarmHarness: true })
    bridge.getState.mockResolvedValue({ ...runningState })

    render(<App />)
    await arriveAtShell()
    // 开机那次判定看到「已在运行」，不预启动。
    await act(async () => { await Promise.resolve() })
    expect(bridge.startHarness).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '停止' }))
    await waitFor(() => expect(bridge.stopRuntime).toHaveBeenCalledTimes(1))

    // 关键：停完之后相位回到 ready，但预启动已经判定过，绝不能把用户刚停的服务重新拉起。
    await act(async () => { await Promise.resolve() })
    expect(bridge.startHarness).not.toHaveBeenCalled()
  })

  it('预启动成功后停止再打开：会重新启动，不会拿过期快照直接打开', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, prewarmHarness: true })

    render(<App />)
    await arriveAtShell()
    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(1))

    // 让预启动落定（默认夹具解析为 running），再手动停止：相位回到 ready。
    await act(async () => { await Promise.resolve() })
    fireEvent.click(screen.getByRole('button', { name: '停止' }))
    await waitFor(() => expect(bridge.stopRuntime).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: /打开对话|打开 Harness/ }))

    // 关键：必须重新启动一次。若预启动留下的已完成 promise 没被清空，这里会误判为
    // 「已在运行」而跳过启动，直接打开就会以 NOT_RUNNING 失败。
    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
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

    await openSettingsPage('设备 Shell 设置')
    fireEvent.click(await screen.findByRole('button', { name: '打开设备 Shell' }))
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
    await openSettingsPage('后台', '设备连接')
    expect(screen.getByText('运行环境使用 APK 内置的受校验版本；需要其它版本时请在 Ubuntu 运行时的版本管理中下载并切换。')).toBeInTheDocument()
    // 屏幕内返回按钮走的是历史回退，视图切换在 popstate 之后生效。
    // 设备连接是三级页：屏幕内返回按钮先回「后台」，再回设置首页。
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    await screen.findByRole('heading', { name: '后台' })
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()

    // 字号在「终端与外观」页。
    await openSettingsPage('主题', '字号与配色')
    const fontSlider = await screen.findByRole('slider', { name: /字号/ })
    fireEvent.change(fontSlider, { target: { value: '17' } })
    // 输入期间不落盘：切到后台才写。
    await flushOnHide()
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({ ...settings, terminalFontSize: 17 }))
    expect(await screen.findByText('设置已保存')).toBeInTheDocument()
  })

  it('saves a whitelisted provider credential update', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('模型与密钥')
    fireEvent.click(screen.getByRole('button', { name: /^OpenAI / }))
    fireEvent.change(screen.getByLabelText(/OpenAI API Key/), { target: { value: 'unit-test-openai-key' } })
    await flushOnHide()
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

  it('closes the reset dialog when reset succeeds even if keep-alive status hangs', async () => {
    render(<App />)
    await arriveAtSettingsHome()

    fireEvent.click(screen.getByRole('button', { name: /Ubuntu 运行时/ }))
    fireEvent.click(await screen.findByRole('button', { name: '重置环境' }))
    bridge.getKeepAliveState.mockImplementation(() => new Promise(() => {}))

    fireEvent.click(await screen.findByRole('button', { name: '确认重置' }, { timeout: 5_000 }))

    await waitFor(() => expect(bridge.reset).toHaveBeenCalledWith('RESET_RUNTIME'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
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

    // 首次配置窗口现在只有两步（配模型 → 装运行环境），没有旧版的「开始使用」收尾页：
    // 本用例关心的是「回到前台后凭据状态会不会刷新」，先把窗口关掉再去模型页看。
    const onboarding = await screen.findByRole('dialog', { name: '配置模型' })
    fireEvent.click(within(onboarding).getByRole('button', { name: '跳过引导' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '配置模型' })).toBeNull())
    await arriveAtSettingsHome()
    await openSettingsPage('模型与密钥')

    // 刷新前：落盘设置里没有任何凭据，供应商卡片显示未配置。
    expect(screen.getAllByText('未配置').length).toBeGreaterThan(0)

    // 在 Harness 网页里配好凭据后回到前台：只读状态要跟着原生读数更新。
    fireEvent.focus(window)

    expect(await screen.findByText('已在 Harness 中配置')).toBeVisible()
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
    expect(await screen.findByRole('dialog', { name: 'Set up a model' })).toBeVisible()
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
