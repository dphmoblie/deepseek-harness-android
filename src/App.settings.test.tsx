import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FOREGROUND_SERVICE_SETTLE_MS, beforeEachAppTest, bridge, openSettingsPage, settings } from './__tests__/appTestHarness'
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
    // 本用例要等应用侧 1.5 秒的「前台服务宽限期复核」（App.tsx 的 recheckForegroundServiceAfterSettle）。
    // 真实等待会吃掉单条用例 5 秒预算里的一大半，登记册 5.6-J 记下的那次超时 flake 正是这一档；
    // 改成假时钟后由用例自己把时间推过宽限期，断言点不再随机器负载漂移。
    // shouldAdvanceTime 必须开着：本仓库没开 vitest globals，RTL 的 waitFor 认不出 jest 假定时器，
    // 假时钟不随真实时间前进就会把 waitFor 挂到用例超时（这也是仓库里既有假定时器用例的写法）。
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
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
      await act(async () => { await vi.advanceTimersByTimeAsync(FOREGROUND_SERVICE_SETTLE_MS + 100) })
      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('设置已保存，但悬浮球未生效')
      // 「已保存」不等于「已生效」：只留一句「设置已保存」是不够的。
      expect(screen.queryByText('设置已保存')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
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
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('终端与外观')
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
