import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES } from './platform/types'
import type {
  AccessibilityAutomationAppConfig,
  AccessibilityAutomationAppConfigDetail,
  AccessibilityAutomationRules,
  AccessibilityAutomationConfigCorrupt,
} from './platform/types'
import { AUTOMATION_DISCLAIMER_STORAGE_KEY } from './accessibilityDisclaimer'
import { beforeEachAppTest, bridge, openSettingsPage } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 无障碍白名单：自动包含本应用 + 按次验证密码。
 *
 * 这一组守两条用户可见的契约：
 *  1. 本应用（`io.deepseekharness.mobile`）**始终**在白名单里，不用用户维护；
 *  2. 一旦设置了验证密码，**每一次**修改白名单都要当场输入它，界面不保留输入内容。
 *
 * 密码本身归原生侧（只存盐与哈希），这里断言的是界面与桥的交互：
 * 什么时候必须带上密码、什么时候在本地就拦下来、以及密码不出现在任何提示文案里。
 */
describe('无障碍白名单与验证密码', () => {
  beforeEach(beforeEachAppTest)
  afterEach(() => vi.restoreAllMocks())

  /** 原生侧返回的白名单状态：有效白名单里已包含自动项，与真机语义一致。 */
  function accessibilityState(passwordConfigured: boolean, allowed: string[] = [], whitelistEnabled = true) {
    return {
      enabled: false,
      whitelistEnabled,
      allowedPackages: [...new Set([...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, ...allowed])],
      alwaysAllowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES],
      passwordConfigured,
    }
  }

  /** 进入「自动化设置」二级页——无障碍区块就在这一页。 */
  async function openAccessibilityPage(passwordConfigured: boolean, allowed: string[] = []): Promise<void> {
    bridge.getAccessibilityAutomationState.mockResolvedValue(accessibilityState(passwordConfigured, allowed))
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('自动化设置')
  }

  it('本应用始终在白名单里，并如实显示当前白名单', async () => {
    await openAccessibilityPage(false, ['com.example.reader'])

    expect(
      await screen.findByText('本应用（io.deepseekharness.mobile）始终在白名单里：服务重启、连接重建都不会掉，也不需要写进下面的列表。'),
    ).toBeInTheDocument()
    expect(screen.getByText('当前白名单：io.deepseekharness.mobile、com.example.reader')).toBeInTheDocument()
  })

  it('未设置密码时保存白名单不带密码', async () => {
    await openAccessibilityPage(false)

    // 没设置密码就不该出现密码输入框：多一个空框只会让人以为必须填。
    expect(screen.queryByLabelText('修改白名单的验证密码')).not.toBeInTheDocument()

    fireEvent.change(await screen.findByLabelText('目标应用包名'), { target: { value: 'com.example.reader' } })
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))

    await waitFor(() => expect(bridge.setAccessibilityAutomationPackages).toHaveBeenCalledWith(['com.example.reader'], undefined, true))
    expect(await screen.findByText('无障碍应用白名单已保存')).toBeInTheDocument()
  })

  it('已设置密码时，没输入密码就点保存会被本地拦下，桥一次都不调用', async () => {
    await openAccessibilityPage(true)

    fireEvent.change(await screen.findByLabelText('目标应用包名'), { target: { value: 'com.example.reader' } })
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))

    expect(await screen.findByText('修改无障碍白名单需要先输入验证密码')).toBeInTheDocument()
    expect(bridge.setAccessibilityAutomationPackages).not.toHaveBeenCalled()
  })

  it('已设置密码时，带上密码保存，并在提交后立刻清空输入框', async () => {
    await openAccessibilityPage(true)

    fireEvent.change(await screen.findByLabelText('目标应用包名'), { target: { value: 'com.example.reader\ncom.example.notes' } })
    // 用泛型参数而不是 as 断言：断言会被 eslint 的 no-unnecessary-type-assertion 挡下，
    // 而裸的 HTMLElement 读 .value 会过不了 tsc。
    const passwordInput = screen.getByLabelText<HTMLInputElement>('修改白名单的验证密码')
    fireEvent.change(passwordInput, { target: { value: 'passphrase-1' } })
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))

    await waitFor(() => expect(bridge.setAccessibilityAutomationPackages).toHaveBeenCalledWith(
      ['com.example.reader', 'com.example.notes'],
      'passphrase-1',
      true,
    ))
    // 密码只用于本次过桥：提交后界面不留存（原生侧也只保存盐与哈希）。
    await waitFor(() => expect(passwordInput.value).toBe(''))
  })

  it('默认开启限制，关闭并保存后保留名单，再开启恢复限制', async () => {
    await openAccessibilityPage(false, ['com.example.reader'])
    const toggle = screen.getByRole('switch', { name: '启用无障碍白名单限制' })
    const packages = [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, 'com.example.reader']
    expect(toggle).toBeChecked()
    fireEvent.click(toggle)
    expect(screen.getByText('当前生效：白名单限制已开启')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))
    await waitFor(() => expect(bridge.setAccessibilityAutomationPackages).toHaveBeenCalledWith(packages, undefined, false))
    expect(await screen.findByText('当前生效：白名单限制已关闭')).toBeInTheDocument()
    expect(screen.getByLabelText('目标应用包名')).toHaveValue(packages.join('\n'))
    fireEvent.click(toggle)
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))
    await waitFor(() => expect(bridge.setAccessibilityAutomationPackages).toHaveBeenLastCalledWith(packages, undefined, true))
    expect(await screen.findByText('当前生效：白名单限制已开启')).toBeInTheDocument()
  })

  it('关闭限制仍需密码；保存失败不改变当前生效状态', async () => {
    await openAccessibilityPage(true, ['com.example.reader'])
    fireEvent.click(screen.getByRole('switch', { name: '启用无障碍白名单限制' }))
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))
    expect(await screen.findByText('修改无障碍白名单需要先输入验证密码')).toBeInTheDocument()
    expect(bridge.setAccessibilityAutomationPackages).not.toHaveBeenCalled()
    bridge.setAccessibilityAutomationPackages.mockRejectedValueOnce(new Error('验证密码不正确'))
    fireEvent.change(screen.getByLabelText('修改白名单的验证密码'), { target: { value: 'wrong-passphrase' } })
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))
    expect(await screen.findByText('验证密码不正确')).toBeInTheDocument()
    expect(screen.getByText('当前生效：白名单限制已开启')).toBeInTheDocument()
    expect(screen.queryByText('当前生效：白名单限制已关闭')).not.toBeInTheDocument()
    expect(screen.getByLabelText('修改白名单的验证密码')).toHaveValue('')
    bridge.setAccessibilityAutomationPackages.mockResolvedValueOnce(accessibilityState(true, ['com.example.reader'], false))
    fireEvent.change(screen.getByLabelText('修改白名单的验证密码'), { target: { value: 'test-passphrase' } })
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))
    expect(await screen.findByText('当前生效：白名单限制已关闭')).toBeInTheDocument()
    expect(bridge.setAccessibilityAutomationPackages).toHaveBeenLastCalledWith(
      [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, 'com.example.reader'], 'test-passphrase', false,
    )
  })

  it('重新读取已关闭的配置时正确回显开关与名单', async () => {
    bridge.getAccessibilityAutomationState.mockResolvedValue(accessibilityState(false, ['com.example.reader'], false))
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('自动化设置')
    expect(screen.getByRole('switch', { name: '启用无障碍白名单限制' })).not.toBeChecked()
    expect(screen.getByText('当前生效：白名单限制已关闭')).toBeInTheDocument()
    expect(screen.getByLabelText('目标应用包名')).toHaveValue('io.deepseekharness.mobile\ncom.example.reader')
  })

  it('两次输入的新密码不一致时在本地报错，桥一次都不调用', async () => {
    await openAccessibilityPage(false)

    fireEvent.change(await screen.findByLabelText('新验证密码'), { target: { value: 'passphrase-1' } })
    fireEvent.change(screen.getByLabelText('确认新验证密码'), { target: { value: 'passphrase-2' } })
    fireEvent.click(screen.getByRole('button', { name: '设置密码' }))

    expect(await screen.findByText('两次输入的新密码不一致')).toBeInTheDocument()
    expect(bridge.setAccessibilityPassword).not.toHaveBeenCalled()
  })

  it('新密码不合规（太短）时也在本地报错', async () => {
    await openAccessibilityPage(false)

    fireEvent.change(await screen.findByLabelText('新验证密码'), { target: { value: 'short' } })
    fireEvent.change(screen.getByLabelText('确认新验证密码'), { target: { value: 'short' } })
    fireEvent.click(screen.getByRole('button', { name: '设置密码' }))

    // 复用平台层同一套规则：文案里出现的是字段名与长度要求，不是用户输入的内容。
    expect(await screen.findByText('新验证密码需要 6 到 64 个字符')).toBeInTheDocument()
    expect(bridge.setAccessibilityPassword).not.toHaveBeenCalled()
  })

  it('首次设置密码不带当前密码', async () => {
    await openAccessibilityPage(false)

    // 还没设置过：不该出现「当前密码」这一栏（没有东西可以验）。
    expect(screen.queryByLabelText('当前验证密码')).not.toBeInTheDocument()

    fireEvent.change(await screen.findByLabelText('新验证密码'), { target: { value: 'passphrase-1' } })
    fireEvent.change(screen.getByLabelText('确认新验证密码'), { target: { value: 'passphrase-1' } })
    fireEvent.click(screen.getByRole('button', { name: '设置密码' }))

    await waitFor(() => expect(bridge.setAccessibilityPassword).toHaveBeenCalledWith('passphrase-1', undefined))
  })

  it('已设置密码时修改密码，必须带上当前密码', async () => {
    await openAccessibilityPage(true)

    fireEvent.change(await screen.findByLabelText('当前验证密码'), { target: { value: 'passphrase-1' } })
    fireEvent.change(screen.getByLabelText('新验证密码'), { target: { value: 'passphrase-2' } })
    fireEvent.change(screen.getByLabelText('确认新验证密码'), { target: { value: 'passphrase-2' } })
    fireEvent.click(screen.getByRole('button', { name: '修改密码' }))

    await waitFor(() => expect(bridge.setAccessibilityPassword).toHaveBeenCalledWith('passphrase-2', 'passphrase-1'))
  })

  it('清除密码要带当前密码，缺了就不给点', async () => {
    await openAccessibilityPage(true)

    const clear = await screen.findByRole('button', { name: '清除密码' })
    expect(clear).toBeDisabled()

    fireEvent.change(screen.getByLabelText('当前验证密码'), { target: { value: 'passphrase-1' } })
    await waitFor(() => expect(clear).toBeEnabled())
    fireEvent.click(clear)

    await waitFor(() => expect(bridge.clearAccessibilityPassword).toHaveBeenCalledWith('passphrase-1'))
  })

  it('忘记密码时走系统生物识别重置', async () => {
    await openAccessibilityPage(true)

    fireEvent.click(await screen.findByRole('button', { name: '用生物识别重置' }))

    // 重置只清密码、保留白名单：这条路是「本人证明身份」，不是绕过保护。
    await waitFor(() => expect(bridge.resetAccessibilityPasswordWithBiometric).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('验证密码已重置')).toBeInTheDocument()
  })
})

/**
 * 自动化控制台：总开关、服务三态、本次会话的运行记录。
 *
 * 这一组守三条用户可见的契约：
 *  1. 总开关只能在这里改（AI 侧没有开启路径），按钮的可用性必须与当前状态一致；
 *  2. 「总开关」与「系统里的无障碍服务」是两件事，界面必须分开说；
 *  3. 运行记录读不到时要如实说明——**空列表不能被渲染成「什么都没发生」**。
 */
describe('无障碍自动化控制台', () => {
  beforeEach(beforeEachAppTest)
  afterEach(() => vi.restoreAllMocks())

  type ConsoleState = {
    automationRuntime: 'off' | 'running' | 'paused'
    serviceStatus: 'connected' | 'enabled_disconnected' | 'off'
  }

  const BASE_STATE = {
    enabled: false,
    whitelistEnabled: true,
    allowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES],
    alwaysAllowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES],
    passwordConfigured: false,
  }

  /** 进入控制台：`automationRuntime`/`serviceStatus` 是这一组唯一要摆布的两个读数。 */
  async function openConsole(target: ConsoleState): Promise<void> {
    bridge.getAccessibilityAutomationState.mockResolvedValue({ ...BASE_STATE, ...target })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('自动化设置')
  }

  it('默认关闭时只放行「开始」，并如实说明系统里的服务没开', async () => {
    await openConsole({ automationRuntime: 'off', serviceStatus: 'off' })

    expect(await screen.findByTestId('automation-runtime-chip')).toHaveTextContent('自动化已关闭')
    expect(screen.getByRole('button', { name: '开始' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '暂停' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '继续' })).toBeDisabled()
    // 关着的时候「紧急停止」没有对象可停：让它可点只会制造「我按了但什么都没变」。
    expect(screen.getByRole('button', { name: '紧急停止' })).toBeDisabled()
    expect(await screen.findByTestId('automation-service-status')).toHaveTextContent('系统里未开启')
  })

  it('服务在系统里开着但没连上时，明确说清这是两件事', async () => {
    await openConsole({ automationRuntime: 'running', serviceStatus: 'enabled_disconnected' })

    // 总开关开着、系统里也开着，但服务实例不在：这时动作不会发生，必须说清楚。
    expect(await screen.findByTestId('automation-runtime-chip')).toHaveTextContent('自动化运行中')
    expect(screen.getByTestId('automation-service-status')).toHaveTextContent('系统里已开启，但服务没连上')
    expect(screen.getByRole('button', { name: '暂停' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '开始' })).toBeDisabled()
  })

  it('按「开始」把动作交给桥，并按返回的运行态放行暂停', async () => {
    await openConsole({ automationRuntime: 'off', serviceStatus: 'connected' })
    // 免责说明已经确认过：这一例盯的是「按了之后桥收到什么」，闸门本身另有专门用例。
    window.localStorage.setItem(AUTOMATION_DISCLAIMER_STORAGE_KEY, '1')

    fireEvent.click(await screen.findByRole('button', { name: '开始' }))

    // 桥只收四个受控动作名之一：这里钉住「开始」就是 `start`，不是别的拼法。
    await waitFor(() => expect(bridge.setAccessibilityAutomationRuntime).toHaveBeenCalledWith('start'))
    expect(await screen.findByText('自动化总开关已更新')).toBeInTheDocument()
    // 夹具按原生同一套规则回 `running`，界面必须据此换按钮状态（而不是乐观点亮）。
    await waitFor(() => expect(screen.getByTestId('automation-runtime-chip')).toHaveTextContent('自动化运行中'))
    expect(screen.getByRole('button', { name: '暂停' })).toBeEnabled()
  })

  it('第一次按「开始」先摆免责说明：没勾选开不了，勾选确认后才发 start', async () => {
    await openConsole({ automationRuntime: 'off', serviceStatus: 'connected' })

    fireEvent.click(await screen.findByRole('button', { name: '开始' }))

    // 闸门没放行之前，桥是一次都不能调的：这里钉的是「问过人才动手」。
    const dialog = await screen.findByTestId('automation-disclaimer')
    expect(bridge.setAccessibilityAutomationRuntime).not.toHaveBeenCalled()
    expect(dialog).toHaveTextContent('关于自动化（无障碍）功能')
    // 六条要点逐条在：少一条都等于换了条款内容。
    for (const point of [
      '自动化操作可能误点',
      '一律会被跳过，且这一限制没有开关',
      '由使用者自行承担',
      '默认关闭',
      '不上传你的界面内容',
      '请勿将本功能用于违反应用服务条款',
    ]) {
      expect(within(dialog).getByText(new RegExp(point))).toBeInTheDocument()
    }

    const confirm = within(dialog).getByRole('button', { name: '我已了解，开启自动化' })
    expect(confirm).toBeDisabled()
    fireEvent.click(within(dialog).getByRole('checkbox'))
    expect(confirm).toBeEnabled()
    fireEvent.click(confirm)

    await waitFor(() => expect(bridge.setAccessibilityAutomationRuntime).toHaveBeenCalledWith('start'))
    await waitFor(() => expect(screen.queryByTestId('automation-disclaimer')).not.toBeInTheDocument())
    // 确认要落盘：否则每次冷启动都要再问一遍，等于闸门失效。
    expect(window.localStorage.getItem(AUTOMATION_DISCLAIMER_STORAGE_KEY)).toBe('1')
  })

  it('在说明里选「暂不开启」就什么都不做：不开总开关、也不写确认', async () => {
    await openConsole({ automationRuntime: 'off', serviceStatus: 'connected' })

    fireEvent.click(await screen.findByRole('button', { name: '开始' }))
    fireEvent.click(await within(await screen.findByTestId('automation-disclaimer')).findByRole('button', { name: '暂不开启' }))

    await waitFor(() => expect(screen.queryByTestId('automation-disclaimer')).not.toBeInTheDocument())
    expect(bridge.setAccessibilityAutomationRuntime).not.toHaveBeenCalled()
    // 没确认就下次还要问：这里必须仍然是空的。
    expect(window.localStorage.getItem(AUTOMATION_DISCLAIMER_STORAGE_KEY)).toBeNull()
  })

  it('确认过之后「继续」直接生效，不再重复弹说明', async () => {
    await openConsole({ automationRuntime: 'paused', serviceStatus: 'connected' })
    window.localStorage.setItem(AUTOMATION_DISCLAIMER_STORAGE_KEY, '1')

    fireEvent.click(await screen.findByRole('button', { name: '继续' }))

    await waitFor(() => expect(bridge.setAccessibilityAutomationRuntime).toHaveBeenCalledWith('resume'))
    expect(screen.queryByTestId('automation-disclaimer')).not.toBeInTheDocument()
  })

  it('说明只拦「开始 / 继续」：暂停与紧急停止直接发出去', async () => {
    await openConsole({ automationRuntime: 'running', serviceStatus: 'connected' })

    fireEvent.click(await screen.findByRole('button', { name: '暂停' }))
    await waitFor(() => expect(bridge.setAccessibilityAutomationRuntime).toHaveBeenCalledWith('pause'))
    // 等界面真的落到「已暂停」再点下一个：在途期间按钮是禁用的，抢着点会什么都没发生。
    await waitFor(() => expect(screen.getByTestId('automation-runtime-chip')).toHaveTextContent('自动化已暂停'))
    fireEvent.click(await screen.findByRole('button', { name: '紧急停止' }))

    await waitFor(() => expect(bridge.setAccessibilityAutomationRuntime).toHaveBeenCalledWith('emergency_stop'))
    // 收敛动作不能被一段文字挡住：急停尤其要一键生效。
    expect(screen.queryByTestId('automation-disclaimer')).not.toBeInTheDocument()
  })

  it('回看入口常驻：只打开同一段说明，不改总开关也不写确认', async () => {
    await openConsole({ automationRuntime: 'off', serviceStatus: 'off' })

    fireEvent.click(await screen.findByRole('button', { name: '查看完整免责说明' }))

    const dialog = await screen.findByTestId('automation-disclaimer')
    expect(within(dialog).getByText(/规则由你确认后长期生效/)).toBeInTheDocument()
    // 回看模式没有勾选、没有开启按钮，只有一个「知道了」（不叫「关闭」：右上角叉的 aria-label 是「关闭」）。
    expect(within(dialog).queryByRole('checkbox')).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: '我已了解，开启自动化' })).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: '知道了' }))

    await waitFor(() => expect(screen.queryByTestId('automation-disclaimer')).not.toBeInTheDocument())
    expect(bridge.setAccessibilityAutomationRuntime).not.toHaveBeenCalled()
    expect(window.localStorage.getItem(AUTOMATION_DISCLAIMER_STORAGE_KEY)).toBeNull()
  })

  it('紧急停止发的是受控动作名，并提醒用户这是「停到你再开」', async () => {
    await openConsole({ automationRuntime: 'running', serviceStatus: 'connected' })

    fireEvent.click(await screen.findByRole('button', { name: '紧急停止' }))

    await waitFor(() => expect(bridge.setAccessibilityAutomationRuntime).toHaveBeenCalledWith('emergency_stop'))
    expect(await screen.findByText('已紧急停止自动化')).toBeInTheDocument()
    // 紧急停止回落到关闭（不是暂停）：要再开就得显式点一次「开始」。
    await waitFor(() => expect(screen.getByTestId('automation-runtime-chip')).toHaveTextContent('自动化已关闭'))
    expect(screen.getByRole('button', { name: '开始' })).toBeEnabled()
  })

  it('读不到运行记录时说明原因，不把空列表说成什么都没发生', async () => {
    await openConsole({ automationRuntime: 'off', serviceStatus: 'off' })

    expect(await screen.findByTestId('automation-runs-disconnected')).toHaveTextContent('无障碍服务当前没连上，因此读不到运行记录。')
  })

  it('有记录时列出动作与拦截原因，未登记的码显示原始码', async () => {
    bridge.getAccessibilityAutomationRuns.mockResolvedValue({
      connected: true,
      limit: 50,
      dropped: 3,
      runs: [
        { atMs: 65_000, ruleId: 'rule-1', action: 'click', code: 'AUTOMATION_ACTION_PERFORMED', message: '已执行点击', ok: true, performed: true, degraded: false },
        { atMs: 5_000, ruleId: 'rule-2', action: 'click', code: 'NOT_A_CODE', message: null, ok: false, performed: false, degraded: false },
      ],
      blocked: [
        { code: 'AUTOMATION_SENSITIVE_WINDOW', count: 2, message: '页面涉及密码、支付或验证码，已跳过' },
        { code: 'NOT_A_CODE', count: 1, message: null },
      ],
    })
    await openConsole({ automationRuntime: 'running', serviceStatus: 'connected' })

    const runs = await screen.findByTestId('automation-run-list')
    expect(runs).toHaveTextContent('已执行点击')
    expect(runs).toHaveTextContent('已执行')
    expect(runs).toHaveTextContent('未执行')
    // `atMs` 是相对开机的毫秒数：渲染成日期时间会是彻头彻尾的假信息。
    expect(runs).toHaveTextContent('1 分 5 秒前')
    expect(runs).toHaveTextContent('5 秒前')
    // 未登记的码没有说明，界面显示原始码而不是编一句「已跳过」。
    expect(runs).toHaveTextContent('NOT_A_CODE')

    const blocked = await screen.findByTestId('automation-blocked-reasons')
    expect(blocked).toHaveTextContent('页面涉及密码、支付或验证码，已跳过')
    expect(blocked).toHaveTextContent('× 2 次')
    expect(blocked).toHaveTextContent('NOT_A_CODE')

    // 超出容量被丢弃的条数必须报出来：否则用户会以为这就是全部。
    expect(screen.getByText('更早的 3 条记录已超出容量被丢弃。')).toBeInTheDocument()
  })
})

describe('无障碍自动化规则卡片', () => {
  beforeEach(beforeEachAppTest)
  afterEach(() => vi.restoreAllMocks())

  const TARGETS = ['com.example.reader', 'com.example.writer']

  const RULES_STATE = {
    enabled: false,
    whitelistEnabled: true,
    allowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, ...TARGETS],
    alwaysAllowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES],
    passwordConfigured: false,
    automationRuntime: 'off' as const,
    serviceStatus: 'connected' as const,
  }

  function rulesFor(packageName: string, overrides: Record<string, unknown> = {}) {
    return {
      packageName,
      allowed: true,
      corrupt: null,
      rules: [{
        id: 'rule-1',
        enabled: true,
        actionKind: 'click',
        selectorCount: 2,
        maxActions: 3,
        actionCoolDownMs: 3000,
      }],
      ...overrides,
    }
  }

  /** 进入规则卡片：白名单里放两个目标应用，进页面时会自动读第一个。 */
  async function openRules(): Promise<void> {
    bridge.getAccessibilityAutomationState.mockResolvedValue(RULES_STATE)
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('自动化设置')
  }

  it('进页面自动读白名单里第一个应用的规则，切换应用时按包名重读', async () => {
    bridge.getAccessibilityAutomationRules.mockImplementation(
      (packageName: string) => Promise.resolve(rulesFor(packageName)),
    )
    await openRules()

    // 不提供「省略即前台包」的兜底：查询必须带明确包名。
    await waitFor(() => expect(bridge.getAccessibilityAutomationRules).toHaveBeenCalledWith('com.example.reader'))
    expect(await screen.findByTestId('automation-rule-list')).toHaveTextContent('rule-1')
    // 界面只显示动作类型与选择器条数，选择器的正文（文本 / viewId）不进前端。
    expect(screen.getByTestId('automation-rule-list')).toHaveTextContent('click')
    expect(screen.getByTestId('automation-rule-list')).toHaveTextContent('2 个选择器')

    fireEvent.click(screen.getByRole('button', { name: 'com.example.writer' }))
    await waitFor(() => expect(bridge.getAccessibilityAutomationRules).toHaveBeenCalledWith('com.example.writer'))
  })

  it('停用一条规则：只改「启不启用」，写成功后按重读结果更新，不做乐观更新', async () => {
    let enabled = true
    bridge.getAccessibilityAutomationRules.mockImplementation((packageName: string) => Promise.resolve(
      rulesFor(packageName, {
        rules: [{
          id: 'rule-1',
          enabled,
          actionKind: 'click',
          selectorCount: 2,
          maxActions: 3,
          actionCoolDownMs: 3000,
        }],
      }),
    ))
    bridge.setAccessibilityAutomationRuleEnabled.mockImplementation(() => {
      enabled = false
      return Promise.resolve(rulesFor('com.example.reader'))
    })
    await openRules()

    fireEvent.click(await screen.findByRole('button', { name: '停用' }))

    // 白名单校验在原生侧，前端只把三个参数原样递过去；规则内容（选择器、动作）一个字段都不送。
    await waitFor(() => expect(bridge.setAccessibilityAutomationRuleEnabled).toHaveBeenCalledWith({
      packageName: 'com.example.reader',
      ruleId: 'rule-1',
      enabled: false,
    }))
    expect(await screen.findByText('规则已停用')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('automation-rule-list')).toHaveTextContent('已停用'))
  })

  it('写失败时只说失败，界面不把开关先拨过去', async () => {
    bridge.getAccessibilityAutomationRules.mockImplementation((packageName: string) => Promise.resolve(
      rulesFor(packageName, {
        rules: [{
          id: 'rule-1',
          enabled: false,
          actionKind: 'click',
          selectorCount: 2,
          maxActions: 3,
          actionCoolDownMs: 3000,
        }],
      }),
    ))
    bridge.setAccessibilityAutomationRuleEnabled.mockRejectedValue(
      new Error('应用 com.example.reader 不在无障碍自动化白名单内，规则写入被拒绝'),
    )
    await openRules()

    fireEvent.click(await screen.findByRole('button', { name: '启用' }))

    expect(await screen.findByText('应用 com.example.reader 不在无障碍自动化白名单内，规则写入被拒绝')).toBeInTheDocument()
    // 原生拒绝了写入，界面就必须还是「已停用」：乐观更新会留下一个假的「已启用」。
    expect(screen.getByTestId('automation-rule-list')).toHaveTextContent('已停用')
    expect(screen.queryByText('规则已启用')).not.toBeInTheDocument()
  })

  it('读不到规则时显示失败而不是「还没有规则」', async () => {
    bridge.getAccessibilityAutomationRules.mockRejectedValue(new Error('读取失败'))
    await openRules()

    expect(await screen.findByText('规则读取失败，请稍后重试')).toBeInTheDocument()
    expect(screen.queryByText('这个应用还没有规则。')).not.toBeInTheDocument()
  })

  it('白名单外的应用与损坏的规则数据都如实说明，并禁掉开关按钮', async () => {
    bridge.getAccessibilityAutomationRules.mockImplementation((packageName: string) => Promise.resolve(
      rulesFor(packageName, { allowed: false }),
    ))
    await openRules()

    expect(await screen.findByTestId('automation-rules-not-allowed'))
      .toHaveTextContent('不在白名单里：这些规则不会被任何事件用到。')
    expect(screen.getByRole('button', { name: '停用' })).toBeDisabled()
  })

  it('规则数据损坏时给出受控码与说明，而不是当成空列表', async () => {
    bridge.getAccessibilityAutomationRules.mockResolvedValue({
      packageName: 'com.example.reader',
      allowed: true,
      corrupt: { code: 'AUTOMATION_RULE_CORRUPT', message: '规则文件读不出来，已清除' },
      rules: [],
    })
    await openRules()

    const corrupt = await screen.findByTestId('automation-rules-corrupt')
    expect(corrupt).toHaveTextContent('AUTOMATION_RULE_CORRUPT')
    expect(corrupt).toHaveTextContent('规则文件读不出来，已清除')
    expect(screen.queryByText('这个应用还没有规则。')).not.toBeInTheDocument()
  })

  it('点「编辑」先读回规则全文，成功后才打开表单并预填读回的选择器', async () => {
    bridge.getAccessibilityAutomationRules.mockImplementation(
      (packageName: string) => Promise.resolve(rulesFor(packageName)),
    )
    bridge.getAccessibilityAutomationRule.mockResolvedValue({
      packageName: 'com.example.reader',
      ruleId: 'rule-1',
      allowed: true,
      rule: {
        id: 'rule-1',
        packageName: 'com.example.reader',
        enabled: true,
        allowActivities: [],
        denyActivities: [],
        matchDelayMs: 0,
        maxActions: 1,
        actionCoolDownMs: 3000,
        resetOn: 'activity',
        selectors: [{ text: '开始', descContains: '冷门描述', minWidth: 320 }],
        action: { type: 'click' },
      },
    })
    await openRules()

    fireEvent.click(await screen.findByRole('button', { name: '编辑规则 rule-1' }))

    // 列表摘要是没有选择器正文的：编辑必须先单独读回全文，拿摘要预填就等于开一张空表。
    await waitFor(() => expect(bridge.getAccessibilityAutomationRule).toHaveBeenCalledWith({
      packageName: 'com.example.reader',
      ruleId: 'rule-1',
    }))
    expect(await screen.findByLabelText('选择器 1 · 文本')).toHaveValue('开始')
    expect(screen.getByLabelText('选择器 1 · 描述包含')).toHaveValue('冷门描述')
    expect(screen.getByLabelText('选择器 1 · 最小宽度')).toHaveValue(320)
    expect(screen.getByLabelText('规则标识')).toHaveValue('rule-1')
    // 表单打开期间同一条规则的其它写操作全部锁住：不允许两个写操作叠在一起。
    expect(screen.getByRole('button', { name: '停用' })).toBeDisabled()
  })

  it('读回规则失败时只显示原因，绝不打开一张空表单', async () => {
    bridge.getAccessibilityAutomationRules.mockImplementation(
      (packageName: string) => Promise.resolve(rulesFor(packageName)),
    )
    bridge.getAccessibilityAutomationRule.mockRejectedValue(
      new Error('规则全文读取失败，请稍后重试'),
    )
    await openRules()

    fireEvent.click(await screen.findByRole('button', { name: '编辑规则 rule-1' }))

    expect(await screen.findByTestId('automation-rule-write-error'))
      .toHaveTextContent('规则全文读取失败，请稍后重试')
    // 防抹除的核心断言：读不回来就不该有表单，连一个输入框都不能出现。
    expect(screen.queryByTestId('automation-rule-form')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('规则标识')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('选择器 1 · 文本')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存规则' })).not.toBeInTheDocument()
    // 原规则原地不动：这次失败没有改到列表。
    expect(screen.getByTestId('automation-rule-list')).toHaveTextContent('rule-1')
  })

  it('新建并保存：以 create 模式提交，列表按返回的整包读数刷新', async () => {
    bridge.getAccessibilityAutomationRules.mockImplementation(
      (packageName: string) => Promise.resolve(rulesFor(packageName, { rules: [] })),
    )
    bridge.saveAccessibilityAutomationRule.mockResolvedValue(rulesFor('com.example.reader', {
      rules: [{
        id: 'rule-new',
        enabled: true,
        actionKind: 'click',
        selectorCount: 1,
        maxActions: 1,
        actionCoolDownMs: 3000,
      }],
    }))
    await openRules()

    // 一条规则都没有时，「新建规则」照样在。
    fireEvent.click(await screen.findByTestId('automation-rule-create'))
    expect(await screen.findByTestId('automation-rule-form')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('规则标识'), { target: { value: 'rule-new' } })
    fireEvent.change(screen.getByLabelText('选择器 1 · 文本'), { target: { value: '开始' } })
    const readsBefore = bridge.getAccessibilityAutomationRules.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: '保存规则' }))

    await waitFor(() => expect(bridge.saveAccessibilityAutomationRule).toHaveBeenCalledTimes(1))
    expect(bridge.saveAccessibilityAutomationRule).toHaveBeenCalledWith({
      packageName: 'com.example.reader',
      mode: 'create',
      rule: {
        id: 'rule-new',
        packageName: 'com.example.reader',
        enabled: true,
        allowActivities: [],
        denyActivities: [],
        matchDelayMs: 0,
        maxActions: 1,
        actionCoolDownMs: 3000,
        resetOn: 'activity',
        selectors: [{ text: '开始' }],
        action: { type: 'click' },
      },
    })
    // 列表直接吃写完后返回的整包读数，写完不再补读一次。
    expect(await screen.findByTestId('automation-rule-list')).toHaveTextContent('rule-new')
    expect(screen.queryByTestId('automation-rule-form')).not.toBeInTheDocument()
    expect(bridge.getAccessibilityAutomationRules.mock.calls.length).toBe(readsBefore)
  })

  it('删除要先二次确认，确认后才真删，返回的整包读数决定列表', async () => {
    bridge.getAccessibilityAutomationRules.mockImplementation(
      (packageName: string) => Promise.resolve(rulesFor(packageName)),
    )
    await openRules()

    fireEvent.click(await screen.findByRole('button', { name: '删除规则 rule-1' }))

    // 第一下只问一句，说清是整条删除；这时桥一次都没调。
    const confirm = await screen.findByTestId('automation-rule-delete-confirm')
    expect(confirm).toHaveTextContent(
      '删除「rule-1」是整条删除：规则本身、它的全部选择器和动作都不会留下，删了就没了。',
    )
    expect(bridge.deleteAccessibilityAutomationRule).not.toHaveBeenCalled()

    // 取消能反悔，规则与桥都不动。
    fireEvent.click(within(confirm).getByRole('button', { name: '取消' }))
    expect(screen.queryByTestId('automation-rule-delete-confirm')).not.toBeInTheDocument()
    expect(bridge.deleteAccessibilityAutomationRule).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '删除规则 rule-1' }))
    const confirmed = await screen.findByTestId('automation-rule-delete-confirm')
    fireEvent.click(within(confirmed).getByRole('button', { name: '确认删除' }))

    await waitFor(() => expect(bridge.deleteAccessibilityAutomationRule).toHaveBeenCalledWith({
      packageName: 'com.example.reader',
      ruleId: 'rule-1',
    }))
    // 整条没了：返回的空读数直接决定列表，旧规则连名字都不留。
    expect(await screen.findByText('这个应用还没有规则。')).toBeInTheDocument()
    expect(screen.queryByText('rule-1')).not.toBeInTheDocument()
  })
})

/**
 * 「每应用自动化参数」：按应用的暂停开关 + 新建规则的三个默认值。
 *
 * 这一组守两条契约：
 *  1. **读数没回来之前界面不给任何输入框**。拿一份「没暂停、上限 1」的默认值顶上去，
 *     用户点一次「保存参数」就是用界面编出来的数字覆盖盘上的真参数；
 *  2. **界面显示的是原生返回的那份读数**，不是界面自己提交的那份。写入返回的是「写完之后」
 *     的整份读数，界面拿它刷新；做乐观更新只会在写入被拒时留下一屏假数字。
 */
describe(`每应用自动化参数`, () => {
  beforeEach(beforeEachAppTest)
  afterEach(() => vi.restoreAllMocks())

  const TARGETS = ['com.example.reader', 'com.example.writer']

  const CONFIG_STATE = {
    enabled: false,
    whitelistEnabled: true,
    allowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, ...TARGETS],
    alwaysAllowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES],
    passwordConfigured: false,
    automationRuntime: 'off' as const,
    serviceStatus: 'connected' as const,
  }

  /** 规则读数：这一组只关心参数，规则给一份空的就够。 */
  function emptyRules(packageName: string): AccessibilityAutomationRules {
    return { packageName, allowed: true, corrupt: null, rules: [] }
  }

  /**
   * 参数读数：四个字段**都不是原生默认值**（1 / 3000 / 0 / 未暂停），
   * 「到底读没读到」与「是不是被默认值顶上了」因此一眼分得开。
   */
  function appConfigFor(options: {
    packageName: string
    allowed?: boolean
    corrupt?: AccessibilityAutomationConfigCorrupt | null
    config?: Partial<AccessibilityAutomationAppConfig>
  }): AccessibilityAutomationAppConfigDetail {
    return {
      packageName: options.packageName,
      allowed: options.allowed ?? true,
      corrupt: options.corrupt ?? null,
      config: {
        paused: false,
        defaultMaxActions: 4,
        defaultActionCoolDownMs: 12000,
        defaultMatchDelayMs: 800,
        ...options.config,
      },
    }
  }

  /** 让读取按包名回一份可读读数。不调用它就落回夹具的默认读数（1 / 3000 / 0）。 */
  function readAppConfig(config: Partial<AccessibilityAutomationAppConfig> = {}): void {
    bridge.getAccessibilityAutomationAppConfig.mockImplementation((request: { packageName: string }) =>
      Promise.resolve(appConfigFor({ packageName: request.packageName, config })))
  }

  /** 进无障碍控制台：白名单里两个目标应用，进页面自动读第一个的规则与参数。 */
  async function openConsole(useDefaultRuleReader = true): Promise<void> {
    if (useDefaultRuleReader) {
      bridge.getAccessibilityAutomationRules.mockImplementation((packageName: string) =>
        Promise.resolve(emptyRules(packageName)))
    }
    bridge.getAccessibilityAutomationState.mockResolvedValue(CONFIG_STATE)
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('自动化设置')
  }

  it('快速换应用时迟到的旧规则与参数读数不会覆盖当前应用', async () => {
    const deferred = <T,>() => {
      let resolve!: (value: T) => void
      const promise = new Promise<T>(resolver => { resolve = resolver })
      return { promise, resolve }
    }
    const ruleRequests: Record<string, Array<ReturnType<typeof deferred<AccessibilityAutomationRules>>>> = {}
    const configRequests: Record<string, Array<ReturnType<typeof deferred<AccessibilityAutomationAppConfigDetail>>>> = {}
    bridge.getAccessibilityAutomationRules.mockImplementation((packageName: string) => {
      const request = deferred<AccessibilityAutomationRules>()
      ;(ruleRequests[packageName] ??= []).push(request)
      return request.promise
    })
    bridge.getAccessibilityAutomationAppConfig.mockImplementation((request: { packageName: string }) => {
      const pending = deferred<AccessibilityAutomationAppConfigDetail>()
      ;(configRequests[request.packageName] ??= []).push(pending)
      return pending.promise
    })
    await openConsole(false)
    await waitFor(() => expect(ruleRequests['com.example.reader']?.length ?? 0).toBeGreaterThan(0))
    const readerRuleRequests = ruleRequests['com.example.reader']
    const readerConfigRequests = configRequests['com.example.reader']

    fireEvent.click(screen.getByRole('button', { name: 'com.example.writer' }))
    await waitFor(() => expect(ruleRequests['com.example.writer']?.length ?? 0).toBeGreaterThan(0))
    ruleRequests['com.example.writer'].forEach(request => request.resolve(emptyRules('com.example.writer')))
    configRequests['com.example.writer'].forEach(request => request.resolve(appConfigFor({
      packageName: 'com.example.writer',
      config: { defaultMaxActions: 7 },
    })))
    expect(await screen.findByTestId('automation-app-config-effective')).toHaveTextContent('默认动作上限 7')

    // A 的两个慢响应最后才回来：它们不得把 B 的表单或规则卡片改回去。
    readerConfigRequests.forEach(request => request.resolve(appConfigFor({
      packageName: 'com.example.reader',
      config: { defaultMaxActions: 99 },
    })))
    readerRuleRequests.forEach(request => request.resolve({
      ...emptyRules('com.example.reader'),
      rules: [{
        id: 'stale-reader-rule',
        enabled: true,
        actionKind: 'click',
        selectorCount: 1,
        maxActions: 1,
        actionCoolDownMs: 0,
      }],
    }))
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(screen.getByTestId('automation-app-config-effective')).toHaveTextContent('默认动作上限 7')
    expect(screen.queryByText('stale-reader-rule')).not.toBeInTheDocument()
  })

  it(`选包后显示读回的参数，并预填进参数表单`, async () => {
    readAppConfig()
    await openConsole()

    // 进页面自动选第一个包：查询必须带明确包名，没有「省略即前台包」的兜底。
    await waitFor(() => expect(bridge.getAccessibilityAutomationAppConfig).toHaveBeenCalledWith({
      packageName: 'com.example.reader',
    }))
    expect(await screen.findByTestId('automation-app-config-effective')).toHaveTextContent(
      '当前生效：该应用未暂停 · 默认动作上限 4 · 默认冷却时间 12000 毫秒 · 默认匹配延迟 800 毫秒',
    )
    expect(screen.getByLabelText('默认动作上限')).toHaveValue(4)
    expect(screen.getByLabelText('默认冷却时间（毫秒）')).toHaveValue(12000)
    expect(screen.getByLabelText('默认匹配延迟（毫秒）')).toHaveValue(800)
    expect(screen.getByLabelText('暂停这个应用的自动化')).not.toBeChecked()

    // 换应用时两份读数一起换：只重读规则会让参数停留在上一个应用的数字上。
    fireEvent.click(screen.getByRole('button', { name: 'com.example.writer' }))
    await waitFor(() => expect(bridge.getAccessibilityAutomationAppConfig).toHaveBeenCalledWith({
      packageName: 'com.example.writer',
    }))
  })

  it(`点「刷新」把规则与参数两份读数一起重读`, async () => {
    readAppConfig()
    await openConsole()
    await screen.findByTestId('automation-app-config-effective')

    const readsBefore = bridge.getAccessibilityAutomationAppConfig.mock.calls.length
    const rulesBefore = bridge.getAccessibilityAutomationRules.mock.calls.length
    // 「运行记录」那一块也有一个同名的「刷新」：必须在规则这张卡片里找。
    const rulesCard = screen.getByRole('heading', { name: '自动化规则' }).closest('div.settings-subsection')
    expect(rulesCard).not.toBeNull()
    fireEvent.click(within(rulesCard as HTMLElement).getByRole('button', { name: '刷新' }))

    await waitFor(() => expect(bridge.getAccessibilityAutomationRules.mock.calls.length).toBe(rulesBefore + 1))
    expect(bridge.getAccessibilityAutomationAppConfig.mock.calls.length).toBe(readsBefore + 1)
  })

  it(`读取失败时只有原因，一个输入框都没有`, async () => {
    bridge.getAccessibilityAutomationAppConfig.mockRejectedValue(new Error('参数读取失败：桥超时'))
    await openConsole()

    expect(await screen.findByTestId('automation-app-config-read-failed'))
      .toHaveTextContent('参数读取失败，请稍后重试')
    expect(screen.getByTestId('automation-app-config-read-error')).toHaveTextContent('参数读取失败：桥超时')
    // 防抹除的核心断言：不拿默认值顶上去。给出输入框，用户就能凭界面编的数字覆盖真机参数。
    expect(screen.queryByTestId('automation-app-config-save')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('默认动作上限')).not.toBeInTheDocument()
    const section = screen.getByTestId('automation-app-config')
    expect(within(section).queryByRole('switch')).not.toBeInTheDocument()

    // 重试读回来之后输入框才出现，且值是重试读到的那份。
    bridge.getAccessibilityAutomationAppConfig.mockResolvedValueOnce(
      appConfigFor({ packageName: 'com.example.reader' }),
    )
    fireEvent.click(screen.getByTestId('automation-app-config-retry'))
    expect(await screen.findByLabelText('默认动作上限')).toHaveValue(4)
  })

  it(`保存成功后显示的是桥返回的那份读数，不是界面提交的那份`, async () => {
    readAppConfig()
    await openConsole()
    await screen.findByLabelText('默认动作上限')
    const readsBefore = bridge.getAccessibilityAutomationAppConfig.mock.calls.length

    fireEvent.change(screen.getByLabelText('默认动作上限'), { target: { value: '9' } })
    // 写入返回的读数刻意与提交值不同（暂停被原生按坏了处理、冷却时间被归一）：
    // 界面若做乐观更新，显示的就是提交的那份，而不是盘上真正生效的这份。
    bridge.setAccessibilityAutomationAppConfig.mockResolvedValueOnce(appConfigFor({
      packageName: 'com.example.reader',
      config: { paused: true, defaultMaxActions: 9, defaultActionCoolDownMs: 60000, defaultMatchDelayMs: 2500 },
    }))
    fireEvent.click(screen.getByTestId('automation-app-config-save'))

    // 提交的是**整条四个字段**：原生写入是整条替换，少发一个就等于把它写回默认值。
    await waitFor(() => expect(bridge.setAccessibilityAutomationAppConfig).toHaveBeenCalledWith({
      packageName: 'com.example.reader',
      paused: false,
      defaultMaxActions: 9,
      defaultActionCoolDownMs: 12000,
      defaultMatchDelayMs: 800,
    }))
    expect(await screen.findByTestId('automation-app-config-effective')).toHaveTextContent(
      '当前生效：该应用已暂停 · 默认动作上限 9 · 默认冷却时间 60000 毫秒 · 默认匹配延迟 2500 毫秒',
    )
    /*
      输入框里仍是用户提交的数字：草稿是「挂载时建立一次、之后不跟 props 走」的，
      为的是任何一次重读都不会冲掉用户正在敲的内容（见 AutomationAppConfigForm 文件头）。
      盘上真正生效的数字由上面那行「当前生效」负责显示；写入返回的读数从这里看得到。
    */
    expect(screen.getByLabelText('默认冷却时间（毫秒）')).toHaveValue(12000)
    // 写完不再补读一次：界面手里的就是这次写入返回的读数。
    expect(bridge.getAccessibilityAutomationAppConfig.mock.calls.length).toBe(readsBefore)
  })

  it(`保存失败时原样显示桥的文案，草稿一个字都不丢`, async () => {
    readAppConfig()
    await openConsole()
    await screen.findByLabelText('默认动作上限')

    bridge.setAccessibilityAutomationAppConfig.mockRejectedValueOnce(new Error('原生拒绝：参数写入失败'))
    fireEvent.change(screen.getByLabelText('默认匹配延迟（毫秒）'), { target: { value: '900' } })
    fireEvent.click(screen.getByTestId('automation-app-config-save'))

    const alert = await screen.findByTestId('automation-app-config-write-error')
    expect(alert).toHaveAttribute('role', 'alert')
    expect(alert).toHaveTextContent('原生拒绝：参数写入失败')
    // 草稿还在：用户要能改一处再点保存，而不是从头填一遍。
    expect(screen.getByLabelText('默认匹配延迟（毫秒）')).toHaveValue(900)
    expect(screen.getByLabelText('默认动作上限')).toHaveValue(4)
    // 失败不做乐观更新，读数行仍是原来那份。
    expect(screen.getByTestId('automation-app-config-effective')).toHaveTextContent('默认匹配延迟 800 毫秒')
  })

  it(`新建规则表单用读到的参数当初始值`, async () => {
    readAppConfig()
    await openConsole()
    await screen.findByLabelText('默认动作上限')

    fireEvent.click(await screen.findByTestId('automation-rule-create'))

    expect(await screen.findByLabelText('动作上限')).toHaveValue(4)
    expect(screen.getByLabelText('动作冷却（毫秒）')).toHaveValue(12000)
    expect(screen.getByLabelText('匹配延迟（毫秒）')).toHaveValue(800)
  })

  it(`没读到参数时新建规则回落到原生默认值`, async () => {
    // 读取一直不回来。这时不能把「没读到」当成「盘上就是默认值」来展示，但新建表单的**初值**
    // 可以回落到原生默认值：那是表单初值，不是任何一份读数的展示。
    bridge.getAccessibilityAutomationAppConfig.mockRejectedValue(new Error('读参数失败'))
    await openConsole()
    await screen.findByTestId('automation-app-config-read-failed')

    fireEvent.click(await screen.findByTestId('automation-rule-create'))

    expect(await screen.findByLabelText('动作上限')).toHaveValue(1)
    expect(screen.getByLabelText('动作冷却（毫秒）')).toHaveValue(3000)
    expect(screen.getByLabelText('匹配延迟（毫秒）')).toHaveValue(0)
  })
})
