import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { arriveAtSettingsHome } from './__tests__/appTestHarness'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, FOREGROUND_SERVICE_SETTLE_MS, notInstalledState, openSettingsPage, readyState, settings } from './__tests__/appTestHarness'
import type { RuntimeSettings, RuntimeSettingsUpdate } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 设置区：悬浮球开关的运行状态复核，以及「未保存的输入必须活到保存为止」这条草稿契约。
 *
 * 从原来的单文件 `App.test.tsx` 按 view 拆出（登记册 5.6-J）。
 * 这两组共用同一套前提（进入二级页会重读设置、切页卸载的是页而不是草稿），
 * 放在一起才能一眼看出「重读」与「草稿」的相互作用。
 */
describe('悬浮球设置', () => {
  beforeEach(beforeEachAppTest)

  it('未授予系统权限时开关不可用并给出引导入口', async () => {
    // 权限未授予时不能给一个点了没反应的开关：必须禁用并给出「去开启」的入口。
    bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: false, serviceActive: false })
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('后台')
    expect(await screen.findByText('悬浮球')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: /悬浮球/ })).toBeDisabled()
    // 「系统权限已关闭」只在设置里记着开启时才出现：这里设置是关着的，不能只因为没权限就报它。
    expect(screen.queryByText('系统权限已关闭')).not.toBeInTheDocument()

    // 文案以源码中的当前写法为准（界面润色把「去系统设置开启」改成了「前往系统设置开启」）。
    fireEvent.click(screen.getByRole('button', { name: '前往系统设置开启' }))
    await waitFor(() => expect(bridge.openOverlaySettings).toHaveBeenCalledTimes(1))
  })

  it('已授权时可切换开关并自动提交', async () => {
    bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: true, serviceActive: false })
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('后台')
    const toggle = await screen.findByRole('switch', { name: /悬浮球/ })
    expect(toggle).not.toBeChecked()

    fireEvent.click(toggle)
    // 开关改动通过防抖自动保存。
    expect(toggle).toBeChecked()
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ overlayBallEnabled: true }),
    ))
  })

  it('开关已开启但系统权限被撤销时如实显示原因', async () => {
    // 设置里记着开启，但系统权限没了：必须把原因说出来，而不是静默失效。
    bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: false, serviceActive: false })
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('后台')
    expect(await screen.findByText(/系统权限已关闭/)).toBeInTheDocument()
  })

  it('系统权限被撤销但开关已开启时，仍可在应用内把它关掉', async () => {
    // 禁用只用于防「未授权时误开」：已开状态下权限消失时必须允许关闭，
    // 否则用户只能先去系统设置重新授权，才能回来关掉这个已经失效的功能。
    bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: false, serviceActive: false })
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('后台')
    const toggle = await screen.findByRole('switch', { name: /悬浮球/ })
    await waitFor(() => expect(toggle).toBeChecked())
    expect(toggle).toBeEnabled()

    fireEvent.click(toggle)
    expect(toggle).not.toBeChecked()

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
  await arriveAtSettingsHome()
    expect(bridge.getSettings).toHaveBeenCalledTimes(1)

    await openSettingsPage('后台')
    await waitFor(() => expect(bridge.getSettings).toHaveBeenCalledTimes(2))
    // 重读的结果必须真的进到开关上，而不只是多调了一次桥方法。
    await waitFor(() => expect(screen.getByRole('switch', { name: /悬浮球/ })).toBeChecked())
  })

  it('开启悬浮球但前台服务没起来时弹窗给出系统限制指引', async () => {
    // 本用例要等应用侧 1.5 秒的「前台服务宽限期复核」（App.tsx 的 recheckForegroundServiceAfterSettle）。
    // 真实等待会吃掉单条用例 5 秒预算里的一大半，登记册 5.6-J 记下的那次超时 flake 正是这一档；
    // 改成假时钟后由用例自己把时间推过宽限期，断言点不再随机器负载漂移。
    // shouldAdvanceTime 必须开着：本仓库没开 vitest globals，RTL 的 waitFor 认不出 jest 假定时器，
    // 假时钟不随真实时间前进就会把 waitFor 挂到用例超时（这也是仓库里既有假定时器用例的写法）。
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
      bridge.saveSettings.mockImplementation((value: RuntimeSettingsUpdate) => Promise.resolve({ ...value, overlayBallEnabled: true }))
      // 权限已授予、开关已是开启状态，但悬浮球前台服务没有运行：以原生状态为准。
      bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: true, serviceActive: false })
      render(<App />)

      fireEvent.click(await screen.findByRole('button', { name: '打开应用设置' }))
      await openSettingsPage('后台')

      const toggle = await screen.findByRole('switch', { name: /悬浮球/ })
      fireEvent.click(toggle)
      fireEvent.click(toggle)

      await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({
        ...settings,
        overlayBallEnabled: true,
      }))
      // 悬浮球前台服务同样是异步拉起的：等宽限期后按原生状态复核，确认仍未运行才弹窗。
      await act(async () => { await vi.advanceTimersByTimeAsync(FOREGROUND_SERVICE_SETTLE_MS + 100) })
      // 不是权限缺失（canDrawOverlays 为真）：按厂商后台拦截给出自启动 / 省电策略指引与跳转入口。
      const dialog = await screen.findByRole('dialog')
      expect(dialog).toHaveTextContent('前台服务被系统拦截')
      expect(dialog).toHaveTextContent('悬浮球')
      expect(dialog).toHaveTextContent('自启动')
      fireEvent.click(screen.getByRole('button', { name: /打开应用详情页/ }))
      await waitFor(() => expect(bridge.openApplicationSettings).toHaveBeenCalledTimes(1))
    } finally {
      vi.useRealTimers()
    }
  })

  it('进入设置时先读取最新值，再允许编辑，避免迟到响应覆盖输入', async () => {
    render(<App />)
  await arriveAtSettingsHome()
    let resolveSettings!: (value: RuntimeSettings) => void
    bridge.getSettings.mockImplementationOnce(() => new Promise<RuntimeSettings>(resolve => { resolveSettings = resolve }))

    fireEvent.click(screen.getByRole('button', { name: /主题/ }))
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
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(expect.objectContaining({ terminalFontSize: 18 })))
  })

  it('设置重读失败时允许重试且不能保存旧快照', async () => {
    render(<App />)
  await arriveAtSettingsHome()
    bridge.getSettings.mockRejectedValueOnce(new Error('read failed'))
    fireEvent.click(screen.getByRole('button', { name: /后台/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('无法读取最新设置，请重试')
    expect(screen.queryByRole('button', { name: '保存设置' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByRole('heading', { name: '后台' })).toBeVisible()
    expect(screen.queryByRole('button', { name: '保存设置' })).toBeNull()
  })

  it('页内用菜单隐藏悬浮球后，保存其他草稿不会重新开启它', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: true, serviceActive: true })
    render(<App />)
  await arriveAtSettingsHome()
    await openSettingsPage('主题')
    const slider = screen.getByRole('slider', { name: /字号/ })
    fireEvent.change(slider, { target: { value: '19' } })

    bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: true, serviceActive: false })
    await act(async () => {
      fireEvent.focus(window)
      await Promise.resolve()
    })
    expect(slider).toHaveValue('19')
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith({ ...settings, terminalFontSize: 19 }))
  })

  it('菜单关闭后即使还没收到轮询结果，也不会提交旧的悬浮球开关值', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockResolvedValue({ enabled: true, canDrawOverlays: true, serviceActive: true })
    render(<App />)
  await arriveAtSettingsHome()
    await openSettingsPage('后台')
    expect(screen.getByRole('switch', { name: /悬浮球/ })).toBeChecked()
    bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: true, serviceActive: false })
    // 没有编辑项时不应为了“保存”而提交旧的悬浮球开关值。
    expect(bridge.saveSettings).not.toHaveBeenCalled()
  })

  it('已修改的悬浮球草稿不被权限状态刷新覆盖', async () => {
    render(<App />)
  await arriveAtSettingsHome()
    await openSettingsPage('后台')
    const toggle = screen.getByRole('switch', { name: /悬浮球/ })
    fireEvent.click(toggle)
    await act(async () => {
      fireEvent.focus(window)
      await Promise.resolve()
    })
    expect(toggle).toBeChecked()
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(expect.objectContaining({ overlayBallEnabled: true })))
  })

  it('悬浮球查询失败显示未知状态，重试成功后恢复权限状态', async () => {
    bridge.getSettings.mockResolvedValue({ ...settings, overlayBallEnabled: true })
    bridge.getOverlayBallState.mockRejectedValue(new Error('overlay read failed'))
    render(<App />)
  await arriveAtSettingsHome()
    await openSettingsPage('后台')
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
    expect(screen.getByText('在其他应用上层显示悬浮球，点按展开 AI 对话小窗')).toBeVisible()
  })

  it('设置自动落盘后悬浮球查询失败不会误报保存失败', async () => {
    render(<App />)
  await arriveAtSettingsHome()
    await openSettingsPage('主题')
    bridge.getOverlayBallState.mockRejectedValue(new Error('overlay read failed'))
    fireEvent.change(screen.getByRole('slider', { name: /字号/ }), { target: { value: '20' } })
    expect(await screen.findByText('设置已保存，但部分状态暂时无法确认，请稍后重试')).toBeVisible()
    expect(screen.queryByText('overlay read failed')).toBeNull()
    expect(screen.getByRole('slider', { name: /字号/ })).toHaveValue('20')
  })

  it('主题页的字号落到整个壳子的根字号上', async () => {
    document.documentElement.style.removeProperty('--app-font-scale')
    render(<App />)
  await arriveAtSettingsHome()
    await openSettingsPage('主题')
    // 夹具默认字号就是基准值，挂载后应写回中性系数 1：不设置的人看到的是原来的界面。
    await waitFor(() => expect(document.documentElement.style.getPropertyValue('--app-font-scale')).toBe('1'))

    fireEvent.change(screen.getByRole('slider', { name: /字号/ }), { target: { value: '21' } })
    // 21 / 14 = 1.5：字号一动，壳子里以 rem 写的字号整体跟着变。
    await waitFor(() => expect(document.documentElement.style.getPropertyValue('--app-font-scale')).toBe('1.5'))
  })

  it('悬浮球保存成功但状态复核失败时仍显示已保存的开关值', async () => {
    render(<App />)
  await arriveAtSettingsHome()
    await openSettingsPage('后台')
    fireEvent.click(screen.getByRole('switch', { name: /悬浮球/ }))
    bridge.getOverlayBallState.mockRejectedValue(new Error('overlay read failed'))
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
  beforeEach(beforeEachAppTest)

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
  await arriveAtSettingsHome()

    await openSettingsPage('模型与密钥')
    fireEvent.click(screen.getByRole('button', { name: /^OpenAI / }))

    expect(screen.getByLabelText(/OpenAI API Key/)).toHaveAttribute('placeholder', '已在 Harness 中配置，留空保持不变')
    expect(screen.queryByRole('button', { name: '清除密钥' })).toBeNull()
  })

  it('模型供应商页提供 DeepSeek 账号登录入口，不要求先填 API Key', async () => {
    bridge.getSettings.mockResolvedValue({
      ...settings,
      configuredModelProviders: [],
      deepSeekAccountConfigured: false,
    })
    render(<App />)
    await arriveAtSettingsHome()
    await openSettingsPage('模型与密钥')

    fireEvent.click(screen.getByRole('button', { name: '登录 DeepSeek 账号' }))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
  })

  it.each([
    { name: '未安装', runtime: notInstalledState },
    { name: '待更新', runtime: { ...readyState, updateAvailable: true } },
    { name: '正在安装', runtime: { ...readyState, phase: 'extracting' as const } },
    { name: '正在停止', runtime: { ...readyState, phase: 'stopping' as const } },
  ])('$name 时模型页不允许启动账号登录或绕过凭据入口', async ({ runtime }) => {
    bridge.getState.mockResolvedValue(runtime)
    bridge.getSettings.mockResolvedValue({ ...settings, configuredModelProviders: [] })
    render(<App />)
    await arriveAtSettingsHome()
    await openSettingsPage('模型与密钥')

    const login = screen.getByRole('button', { name: '登录 DeepSeek 账号' })
    const bypass = screen.getByRole('button', { name: '我已在 Harness 内配置过，仍要打开' })
    expect(login).toBeDisabled()
    expect(bypass).toBeDisabled()
    fireEvent.click(login)
    fireEvent.click(bypass)
    expect(bridge.startHarness).not.toHaveBeenCalled()
    expect(bridge.openHarness).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('模型设置保存期间禁用内置和自定义密钥编辑，完成后恢复', async () => {
    let resolveSave!: (value: RuntimeSettings) => void
    const custom = {
      id: 'custom-1', name: 'Gateway', api: 'openai-completions' as const, baseUrl: 'https://gateway.example.invalid/v1',
      models: [{ id: 'local-model', name: 'Local Model', contextWindow: 131072, maxTokens: 8192 }],
    }
    bridge.getSettings.mockResolvedValue({ ...settings, customModelProviders: [custom] })
    bridge.saveSettings.mockImplementationOnce(() => new Promise<RuntimeSettings>(resolve => { resolveSave = resolve }))
    render(<App />)
    await arriveAtSettingsHome()
    await openSettingsPage('模型与密钥')

    fireEvent.change(screen.getByLabelText(/DeepSeek API Key/), { target: { value: 'unit-test-first-key' } })
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledTimes(1), { timeout: 2000 })

    expect(screen.getByLabelText(/DeepSeek API Key/)).toBeDisabled()
    expect(screen.getByLabelText('API Key')).toBeDisabled()
    expect(screen.getByLabelText('供应商名称')).toBeDisabled()
    expect(screen.getByRole('button', { name: '添加自定义供应商' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '登录 DeepSeek 账号' })).toBeDisabled()
    expect(screen.queryByText('已自动保存')).not.toBeInTheDocument()

    await act(async () => { resolveSave({ ...settings, customModelProviders: [custom] }); await Promise.resolve() })
    await waitFor(() => expect(screen.getByLabelText(/DeepSeek API Key/)).toBeEnabled())
    expect(screen.getByLabelText(/DeepSeek API Key/)).toHaveValue('')
    expect(screen.getByLabelText('API Key')).toBeEnabled()
  })

  it('直接点击供应商卡片切换编辑项，不再显示供应商下拉框或触发保存', async () => {
    bridge.getSettings.mockResolvedValue({
      ...settings,
      configuredModelProviders: ['deepseek'],
      harnessConfiguredModelProviders: ['openai'],
    })
    render(<App />)
  await arriveAtSettingsHome()
    await openSettingsPage('模型与密钥')

    const list = screen.getByRole('list', { name: '供应商状态' })
    const row = (name: string): HTMLElement => within(list).getByText(name).closest('.provider-status-row') as HTMLElement
    expect(row('DeepSeek')).toHaveTextContent('已配置')
    expect(row('OpenAI')).toHaveTextContent('已在 Harness 中配置')
    expect(row('Anthropic')).toHaveTextContent('未配置')

    expect(screen.queryByRole('combobox', { name: '供应商' })).not.toBeInTheDocument()
    expect(within(list).getAllByRole('button')).toHaveLength(8)
    expect(within(list).getByRole('button', { name: /^DeepSeek / })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(within(list).getByRole('button', { name: /^Anthropic / }))
    expect(within(list).getByRole('button', { name: /^Anthropic / })).toHaveAttribute('aria-pressed', 'true')
    expect(within(list).getByRole('button', { name: /^DeepSeek / })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByLabelText(/Anthropic API Key/)).toBeVisible()
    expect(within(row('Anthropic')).getByText('正在编辑')).toBeVisible()
    expect(within(row('DeepSeek')).queryByText('正在编辑')).toBeNull()
    expect(bridge.saveSettings).not.toHaveBeenCalled()
  })

  it.each([false, true])('未保存草稿时刷新账号状态（原状态 %s），保留密钥输入', async (signedIn) => {
    bridge.getSettings.mockResolvedValue({ ...settings, deepSeekAccountConfigured: signedIn })
    bridge.saveSettings.mockImplementation(() => new Promise<RuntimeSettings>(() => {}))
    render(<App />)
    await arriveAtSettingsHome()
    await openSettingsPage('模型与密钥')

    fireEvent.change(screen.getByLabelText(/DeepSeek API Key/), { target: { value: 'unit-test-unsaved-key' } })
    bridge.getSettings.mockResolvedValue({ ...settings, deepSeekAccountConfigured: !signedIn })
    fireEvent.focus(window)

    await screen.findByRole('button', { name: signedIn ? '登录 DeepSeek 账号' : '打开 Harness 管理账号' })
    expect(screen.getByLabelText(/DeepSeek API Key/)).toHaveValue('unit-test-unsaved-key')
  })

  it('前台刷新会更新 Harness 网页凭据状态，同时保留未保存的密钥草稿', async () => {
    bridge.getSettings.mockResolvedValue({
      ...settings,
      harnessConfiguredModelProviders: [],
    })
    render(<App />)
  await arriveAtSettingsHome()
    await openSettingsPage('模型与密钥')

    fireEvent.click(screen.getByRole('button', { name: /^Anthropic / }))
    fireEvent.change(screen.getByLabelText(/Anthropic API Key/), { target: { value: 'unit-test-anthropic-key' } })
    fireEvent.click(screen.getByRole('button', { name: /^OpenAI / }))
    expect(screen.getByLabelText(/OpenAI API Key/)).toHaveAttribute('placeholder', '输入 API Key')

    bridge.getSettings.mockResolvedValue({
      ...settings,
      harnessConfiguredModelProviders: ['openai'],
    })
    fireEvent.focus(window)

    await waitFor(() => expect(screen.getByLabelText(/OpenAI API Key/))
      .toHaveAttribute('placeholder', '已在 Harness 中配置，留空保持不变'))
    fireEvent.click(screen.getByRole('button', { name: /^Anthropic / }))
    expect(screen.getByLabelText(/Anthropic API Key/)).toHaveValue('unit-test-anthropic-key')
  })

  it('未保存的 API Key 在设置区内切页后仍然保留，并能随保存一起提交', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('模型与密钥')
    fireEvent.click(screen.getByRole('button', { name: /^OpenAI / }))
    fireEvent.change(screen.getByLabelText(/OpenAI API Key/), { target: { value: 'unit-test-openai-key' } })
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(expect.objectContaining({ providerApiKeys: { openai: 'unit-test-openai-key' } })))

    // 用户去别的设置页看一眼再回来：刚输入的密钥与正在编辑的供应商都必须还在。
    await backToSettingsHome()
    await openSettingsPage('主题')
    await backToSettingsHome()
    await openSettingsPage('模型与密钥')

    expect(screen.getByRole('button', { name: /^OpenAI / })).toHaveAttribute('aria-pressed', 'true')
    // 输入已经通过防抖自动保存；切页回来后密钥不会回显，只显示已配置占位符。
    expect(screen.getByLabelText(/OpenAI API Key/)).toHaveValue('')
    expect(screen.getByLabelText(/OpenAI API Key/)).toHaveAttribute('placeholder', '输入 API Key')

    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(expect.objectContaining({
      ...settings,
      providerApiKeys: { openai: 'unit-test-openai-key' },
    })))
  })

  it('后台重读设置不会覆盖未保存的输入', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('主题')
    // Keep the debounced write unresolved so the navigation below exercises the
    // dirty-draft path instead of racing the 500 ms auto-save to completion.
    let resolveSave!: (value: RuntimeSettings) => void
    bridge.saveSettings.mockImplementationOnce(() => new Promise<RuntimeSettings>(resolve => { resolveSave = resolve }))
    fireEvent.change(screen.getByRole('slider', { name: /字号/ }), { target: { value: '18' } })

    // 进入每个设置二级页都会重读设置；这里让重读带回一个不同的落盘值（22）。
    bridge.getSettings.mockResolvedValue({ ...settings, terminalFontSize: 22 })
    await backToSettingsHome()
    await openSettingsPage('模型与密钥')
    await backToSettingsHome()
    await openSettingsPage('主题')

    // 重读照旧发生，但草稿是「脏」的：用户输到一半的值优先。
    expect(await screen.findByRole('slider', { name: /字号/ })).toHaveValue('18')
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ terminalFontSize: 18 }),
    ))
    await act(async () => { resolveSave({ ...settings, terminalFontSize: 18 }); await Promise.resolve() })
  })

  it('保存成功后草稿与落盘值重新同步：密钥不回显、也不重复提交', async () => {
    render(<App />)
  await arriveAtSettingsHome()
    // 原生侧只回传落盘后的设置：凭据字段不回显（真实桥接同样会把它剥掉）。
    // 这里用一个不含凭据的返回值，避免把「密钥又一次随设置对象提交」误当成草稿没清。
    bridge.saveSettings.mockImplementationOnce(() =>
      Promise.resolve({ ...settings, configuredModelProviders: ['openai'] }))

    await openSettingsPage('模型与密钥')
    fireEvent.click(screen.getByRole('button', { name: /^OpenAI / }))
    fireEvent.change(screen.getByLabelText(/OpenAI API Key/), { target: { value: 'unit-test-openai-key' } })
    expect(await screen.findByText('设置已保存')).toBeVisible()

    // 密钥落盘后不回显：草稿里那份输入随保存一起清掉，「已配置」以落盘值为准。
    const keyInput = screen.getByLabelText(/OpenAI API Key/)
    expect(keyInput).toHaveValue('')
    expect(keyInput).toHaveAttribute('placeholder', '已配置，留空保持不变')

    // 自动保存完成后，密钥不会再次提交。
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledTimes(1))
    expect(bridge.saveSettings.mock.calls.at(-1)?.[0]).toHaveProperty('providerApiKeys')
  })

  it('离开设置区后未保存的输入被丢弃，下次进入以落盘值为准', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('主题')
    fireEvent.change(screen.getByRole('slider', { name: /字号/ }), { target: { value: '18' } })

    // 终端不属于设置区：离开即丢弃草稿，未保存的输入与内存里的密钥都不再保留。
    await backToSettingsHome()
    fireEvent.click(screen.getByRole('button', { name: /^设备 Shell 设备上的 Shell/ }))
    await screen.findByRole('heading', { name: '终端' })
    await backToSettingsHome()

    await openSettingsPage('主题')
    expect(await screen.findByRole('slider', { name: /字号/ })).toHaveValue('14')
  })

  it('底部按钮直接添加自定义供应商，字段与密钥在切页后同样保留', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('模型与密钥')
    const add = screen.getByRole('button', { name: '添加自定义供应商' })
    const section = screen.getByRole('region', { name: '模型供应商' })
    expect(section.lastElementChild?.lastElementChild).toBe(add)
    expect(screen.queryByLabelText('供应商名称')).not.toBeInTheDocument()
    fireEvent.click(add)
    expect(section.lastElementChild?.lastElementChild).toBe(add)
    fireEvent.change(screen.getByLabelText('供应商名称'), { target: { value: '本地网关' } })
    fireEvent.change(screen.getByLabelText('Base URL'), { target: { value: 'https://gateway.example.invalid/v1' } })
    fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'sk-custom-gateway' } })
    // 必填的模型字段：表单本身会拦住缺项的提交，这两项属于同一个草稿。
    fireEvent.change(screen.getByLabelText('模型 ID'), { target: { value: 'local-model' } })
    fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: 'Local Model' } })

    // Keep this first auto-save in flight: the provider fields and key must
    // survive the settings-page remount while the write is still pending.
    let resolveSave!: (value: RuntimeSettings) => void
    bridge.saveSettings.mockImplementationOnce(() => new Promise<RuntimeSettings>(resolve => { resolveSave = resolve }))

    await backToSettingsHome()
    await openSettingsPage('后台')
    await backToSettingsHome()
    await openSettingsPage('模型与密钥')

    expect(screen.queryByRole('combobox', { name: '供应商' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('供应商名称')).toHaveValue('本地网关')
    expect(screen.getByLabelText('Base URL')).toHaveValue('https://gateway.example.invalid/v1')
    // 尚未等到防抖保存落地就切页：草稿仍在内存里，密钥输入因此保留。
    // 保存成功后的清空行为由「保存成功后草稿与落盘值重新同步」那条用例覆盖。
    expect(screen.getByLabelText('API Key')).toHaveValue('sk-custom-gateway')

    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(expect.objectContaining({
      customModelProviders: [
        expect.objectContaining({ id: 'custom-1', name: '本地网关', baseUrl: 'https://gateway.example.invalid/v1' }),
      ],
      customProviderApiKeys: { 'custom-1': 'sk-custom-gateway' },
    })))
    await act(async () => { resolveSave({
      ...settings,
      customModelProviders: [{ id: 'custom-1', name: '本地网关', api: 'openai-completions', baseUrl: 'https://gateway.example.invalid/v1', models: [{ id: 'local-model', name: 'Local Model', contextWindow: 131072, maxTokens: 8192 }] }],
      configuredCustomModelProviders: ['custom-1'],
    }); await Promise.resolve() })
  })

  it('进入模型页即可编辑已有自定义供应商，切换内置供应商不会隐藏自定义配置', async () => {
    bridge.getSettings.mockResolvedValue({
      ...settings,
      customModelProviders: [{
        id: 'custom-1', name: '本地网关', api: 'openai-completions', baseUrl: 'https://gateway.example.invalid/v1',
        models: [{ id: 'local-model', name: 'Local Model', contextWindow: 131072, maxTokens: 8192 }],
      }],
      configuredCustomModelProviders: ['custom-1'],
    })
    render(<App />)
  await arriveAtSettingsHome()
    await openSettingsPage('模型与密钥')

    expect(screen.getByLabelText('供应商名称')).toHaveValue('本地网关')
    expect(screen.getByLabelText('API Key')).toHaveValue('')
    expect(screen.getByLabelText('API Key')).toHaveAttribute('placeholder', '已配置，留空保持不变')
    fireEvent.click(screen.getByRole('button', { name: /^OpenAI / }))
    expect(screen.getByLabelText(/OpenAI API Key/)).toBeVisible()
    expect(screen.getByLabelText('供应商名称')).toHaveValue('本地网关')
    expect(bridge.saveSettings).not.toHaveBeenCalled()
  })
})

/**
 * 外观入口的**接线**验证（登记册 5.4）。
 *
 * `AppearanceSettings` 自身的规则由 `src/components/AppearanceSettings.test.tsx` 与
 * `src/theme.test.ts` 覆盖；这里只钉住「它确实被挂进了应用」以及「选完真的落到 DOM 上」——
 * 组件写得再对，没挂上去用户就看不到，而那种情况类型检查与组件测试都不会报错。
 */
describe('外观设置接线', () => {
  beforeEach(beforeEachAppTest)

  afterEach(() => {
    // 主题是全局 DOM 状态：不清掉会污染同文件后续用例的首屏外观。
    delete document.documentElement.dataset.theme
  })

  it('设置里能看到「界面主题」，且选择深色后立刻落到根元素上', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('主题')
    const section = await screen.findByRole('region', { name: /界面主题/ })
    expect(section).toBeInTheDocument()
    // 三个选项都要在（渲染成 radiogroup 里的 radio），否则用户无法「跟着系统走」。
    for (const label of ['跟随系统', '浅色', '深色']) {
      expect(within(section).getByRole('radio', { name: new RegExp(label) })).toBeInTheDocument()
    }

    fireEvent.click(within(section).getByRole('radio', { name: /深色/ }))
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('dark'))
    // 持久化也要落：否则下次打开又回到默认。
    expect(window.localStorage.getItem('dsh-mobile-theme-v1')).toBe('dark')
  })
})

/**
 * 后台页只管「运行时从哪来」，装/启停/重置与设备命令各在自己的独立页；
 * 入口放在这里，才不会让人为了找运行时页翻遍设置首页。
 */
describe('后台页的运行时与设备 Shell 入口', () => {
  beforeEach(beforeEachAppTest)

  it('给出两个入口，并说明独立页各自管什么', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('后台')
    expect(await screen.findByRole('heading', { name: '运行时与设备 Shell' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Ubuntu 运行时' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '设备 Shell 设置' })).toBeEnabled()
    expect(screen.getByText('运行环境使用 APK 内置的受校验版本；需要其它版本时请在 Ubuntu 运行时的版本管理中下载并切换。')).toBeVisible()
  })

  it('两个入口分别进入运行时页与设备 Shell 页', async () => {
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('后台')
    fireEvent.click(screen.getByRole('button', { name: 'Ubuntu 运行时' }))
    expect(await screen.findByRole('heading', { name: 'Ubuntu 运行时' })).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    await openSettingsPage('后台')
    fireEvent.click(screen.getByRole('button', { name: '设备 Shell 设置' }))
    expect(await screen.findByRole('heading', { name: '设备 Shell 设置' })).toBeVisible()
  })

  it('更改设置后无需点击保存按钮，经过防抖自动保存并展示已自动保存徽标', async () => {
    bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: true, serviceActive: false })
    render(<App />)
  await arriveAtSettingsHome()

    await openSettingsPage('后台')
    const toggle = await screen.findByRole('switch', { name: /悬浮球/ })
    expect(toggle).not.toBeChecked()

    fireEvent.click(toggle)
    expect(toggle).toBeChecked()

    // 无需手动点击「保存设置」按钮，等待防抖自动落盘
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(
      expect.objectContaining({ overlayBallEnabled: true }),
    ), { timeout: 2000 })

    // 界面显示自动保存状态（顶部徽标与操作态均呈现已保存）
    const badges = await screen.findAllByText('已自动保存')
    expect(badges.length).toBeGreaterThanOrEqual(1)
  })

  it('自动保存失败或仍在途时不显示已自动保存', async () => {
    let resolveSave!: (value: RuntimeSettings) => void
    bridge.saveSettings.mockImplementationOnce(() => new Promise<RuntimeSettings>(resolve => { resolveSave = resolve }))
    render(<App />)
    await arriveAtSettingsHome()
    await openSettingsPage('后台')

    fireEvent.click(await screen.findByRole('switch', { name: /悬浮球/ }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledTimes(1), { timeout: 2000 })
    expect(screen.queryByText('已自动保存')).not.toBeInTheDocument()

    await act(async () => {
      resolveSave({ ...settings, overlayBallEnabled: true })
      await Promise.resolve()
    })
    expect((await screen.findAllByText('已自动保存')).length).toBeGreaterThan(0)
  })

  it('保存响应迟到时不会覆盖保存期间产生的新草稿', async () => {
    let resolveSave!: (value: RuntimeSettings) => void
    bridge.saveSettings.mockImplementationOnce(() => new Promise<RuntimeSettings>(resolve => { resolveSave = resolve }))
    render(<App />)
    await arriveAtSettingsHome()
    await openSettingsPage('模型与密钥')

    const key = screen.getByLabelText(/DeepSeek API Key/)
    fireEvent.change(key, { target: { value: 'first-key' } })
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledTimes(1), { timeout: 2500 })

    // The active save disables credential editing; a newer draft can still be
    // created by another settings control while the native response is pending.
    await openSettingsPage('主题')
    fireEvent.change(await screen.findByRole('slider', { name: /字号/ }), { target: { value: '18' } })
    await act(async () => {
      resolveSave({ ...settings, configuredModelProviders: ['deepseek'] })
      await Promise.resolve()
    })

    await openSettingsPage('模型与密钥')
    expect(screen.getByLabelText(/DeepSeek API Key/)).toHaveValue('first-key')
  })
})
