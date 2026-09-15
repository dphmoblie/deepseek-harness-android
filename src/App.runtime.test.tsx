import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { FOREGROUND_SERVICE_SETTLE_MS, beforeEachAppTest, bridge, keepAlive, notInstalledState, openSettingsPage, readyState, runningState, settings } from './__tests__/appTestHarness'
import type { RuntimeProgress } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 运行时与后台：「运行与后台」页承载的三件事。
 *
 *  1. 后台保持（前台服务、通知权限、进程被回收后的重新连接）；
 *  2. 运行时自检（按需触发、结论码到文案的映射、修复后自动复检）；
 *  3. 空闲自行停止的提示与「上次停止」记录。
 *
 * 从原来的单文件 `App.test.tsx` 按 view 拆出（登记册 5.6-J）。
 * 这一组里有全仓库唯一一处「必须真实等待应用侧定时器」的用例（前台服务宽限期复核），
 * 单独成文件后，它的超时预算不再与另外 80 条用例共享。
 */
describe('后台保持与恢复', () => {
  beforeEach(beforeEachAppTest)

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
    // 本用例要等应用侧 1.5 秒的「前台服务宽限期复核」（App.tsx 的 recheckForegroundServiceAfterSettle）：
    // 保存后立刻读原生状态只会看到「尚未生效」，必须等宽限期过去再复核一次。
    // 原先用真实计时器等它，单条用例实测 1.77 秒，占满 5 秒默认超时的三分之一还多——
    // 这正是登记册 5.6-J 记下的那次超时 flake。改成假时钟后由用例自己把时间推过宽限期，
    // 断言点不再随机器负载漂移，也不会真的睡掉 1.5 秒。
    // shouldAdvanceTime 必须开着：本仓库没开 vitest globals，RTL 的 waitFor 认不出假定时器，
    // 假时钟不随真实时间前进会把 waitFor 挂到用例超时。
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
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
      // 前台服务是异步拉起的：把假时钟推过宽限期，让应用按原生状态复核。
      await act(async () => { await vi.advanceTimersByTimeAsync(FOREGROUND_SERVICE_SETTLE_MS + 100) })
      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('设置已保存，但后台保持未生效')
      // 「已保存」不等于「已生效」：只留一句「设置已保存」是不够的。
      expect(screen.queryByText('设置已保存')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
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

/**
 * 运行时自检：不需要 bash 也能判断运行时断在哪一环。
 *
 * 自检面板与日志面板遵循同一条按需原则——进页面不自动跑，只有用户点了才调用；
 * 界面只渲染受控枚举（检查项、状态、结论码）映射出来的文案。
 */
describe('运行时自检', () => {
  beforeEach(beforeEachAppTest)

  it('兼容模式只在用户选择并保存后发送，明确提示旧会话和隔离变化', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')
    const select = screen.getByRole('combobox', { name: '启动默认权限' })
    expect(select).toHaveValue('workspace-write')
    fireEvent.change(select, { target: { value: 'danger-full-access' } })
    expect(bridge.saveSettings).not.toHaveBeenCalled()
    expect(screen.getByText('兼容模式会降低隔离能力')).toBeVisible()
    expect(screen.getByText(/此项设置启动默认值；Harness 内保存的默认权限/)).toHaveTextContent('/permission danger-full-access')
    fireEvent.click(screen.getByRole('button', { name: '保存设置' }))
    await waitFor(() => expect(bridge.saveSettings).toHaveBeenCalledWith(expect.objectContaining({ harnessPermissionMode: 'danger-full-access' })))
  })

  it('启动默认值已关闭沙箱时仍如实显示探测失败，不冒充当前会话权限', async () => {
    bridge.runRuntimeSelfCheck.mockResolvedValue({
      operation: 'check', availableBytes: 147 * 1024 ** 3, dshVersion: '0.1.5-rc.2',
      harnessPermissionMode: 'danger-full-access',
      checks: [{ id: 'sandbox_probe', status: 'fail', code: 'PROBE_UNUSABLE' }],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')
    fireEvent.click(screen.getByRole('button', { name: '运行自检' }))
    expect(await screen.findByText('自检时的启动默认权限：danger-full-access（会话权限可能不同）')).toBeVisible()
    expect(screen.getByRole('group', { name: '自检结果' })).toHaveTextContent('失败')
    expect(screen.getByText(/自检会单独测试 Landlock 能力/)).toBeVisible()
  })

  it.each([
    { id: 'sandbox_exec', status: 'fail', code: 'EXEC_LAUNCHER_FAILED' },
    { id: 'pty_sandbox', status: 'fail', code: 'PTY_EXIT_EARLY' },
  ])('单独的 $id 失败不推出 Landlock 不可用', async item => {
    bridge.runRuntimeSelfCheck.mockResolvedValue({
      operation: 'check', availableBytes: 1024 ** 3, dshVersion: null,
      checks: [{ id: 'sandbox_probe', status: 'ok' }, item],
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')
    fireEvent.click(screen.getByRole('button', { name: '运行自检' }))
    await screen.findByRole('group', { name: '自检结果' })
    expect(screen.queryByText('本机 Landlock 沙箱不可用')).toBeNull()
  })

  it('修复后的复检失败不显示旧结果，也不丢失已经完成的修复统计', async () => {
    bridge.runRuntimeSelfCheck
      .mockResolvedValueOnce({ operation: 'check', availableBytes: 1024 ** 3, dshVersion: null,
        checks: [{ id: 'rg', status: 'warn', code: 'RG_NOT_EXECUTABLE' }] })
      .mockResolvedValueOnce({ operation: 'repair', availableBytes: 1024 ** 3, repaired: 1, candidates: 3 })
      .mockRejectedValueOnce(new Error('SELF_CHECK_FAILED'))
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('运行与后台')
    fireEvent.click(screen.getByRole('button', { name: '运行自检' }))
    fireEvent.click(await screen.findByRole('button', { name: '修复运行时权限' }))
    expect(await screen.findByText('自检未完成，请稍后重试')).toBeVisible()
    expect(screen.getByText('已修复 1 项（检查 3 项）')).toBeVisible()
    expect(screen.queryByRole('group', { name: '自检结果' })).toBeNull()
    expect(screen.queryByText('修复未完成，请稍后重试')).toBeNull()
  })

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

    const summary = screen.getByText('本机 Landlock 沙箱不可用').closest('[role="alert"]')
    expect(summary).not.toBeNull()
    // 因果链要落到用户真正看到的那条报错上，并给出「怎么继续用」的动作，而不是只列三个失败码。
    expect(summary).toHaveTextContent('要求沙箱的会话无法执行命令')
    expect(summary).toHaveTextContent('/permission danger-full-access')
    // 关掉沙箱是显式的能力降级：必须如实说明代价，不能写成「已修复」。
    expect(summary).toHaveTextContent('关闭 dsh 文件系统沙箱和命令审批')
    expect(summary).toHaveTextContent('Android 应用沙箱仍在')

    // 汇总排在逐项列表之前：先读因果，再读每一项的细节。
    const pageText = document.body.textContent ?? ''
    expect(pageText.indexOf('本机 Landlock 沙箱不可用')).toBeGreaterThanOrEqual(0)
    expect(pageText.indexOf('本机 Landlock 沙箱不可用')).toBeLessThan(pageText.indexOf('沙箱探测判定为不可用'))
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
    expect(screen.queryByText('本机 Landlock 沙箱不可用')).toBeNull()
  })

  it('只有裸 PTY 失败或只支持部分 Landlock 能力时，不说成「沙箱后端不可用」', async () => {
    // 裸 pty 上的 PTY_EXIT_EARLY 说明断在 PTY 层；PROBE_PARTIAL 是「一般仍可用」。
    // 两者都不是「本机 Landlock 沙箱不可用」，汇总提示不能出现。
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

    expect(screen.queryByText('本机 Landlock 沙箱不可用')).toBeNull()
    expect(screen.getByText('PTY 子进程在就绪前退出——与 dsh bash 工具报的是同一现象')).toBeVisible()
    expect(screen.getByText('内核只支持部分 Landlock 能力（老 ABI）')).toBeVisible()
  })

  it('启动器缺失时如实显示跳过项，不提供无法恢复程序的权限修复入口', async () => {
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
    expect(results).toHaveTextContent('重新安装或更新运行环境；修复权限不能恢复缺失的程序')
    expect(results).toHaveTextContent('失败')
    expect(results).toHaveTextContent('跳过')
    expect(results).not.toHaveTextContent('内核不支持 Landlock')
    // 缺少程序不能靠 chmod 恢复，不提供无效的权限修复入口。
    expect(screen.queryByRole('button', { name: '修复运行时权限' })).toBeNull()
  })

  it('修复成功后自动复检，更新结果并保留修复统计', async () => {
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
    expect(screen.getByText('权限修复只补执行位和附件目录；修复后自动复检，不能安装内核沙箱能力。')).toBeVisible()
    expect(await screen.findByText('全部 2 项检查通过')).toBeVisible()
    expect(bridge.runRuntimeSelfCheck).toHaveBeenNthCalledWith(1, 'check')
    expect(bridge.runRuntimeSelfCheck).toHaveBeenNthCalledWith(2, 'repair')
    expect(bridge.runRuntimeSelfCheck).toHaveBeenNthCalledWith(3, 'check')
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
    fireEvent.click(screen.getByRole('button', { name: '运行自检' }))
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
  beforeEach(beforeEachAppTest)

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
