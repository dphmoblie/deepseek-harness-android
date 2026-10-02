import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, runningState } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * `RUNTIME_CLOSED` 的至多一次恢复重试。
 *
 * 真机日志里这个码**三连**出现：WebView 插件实例被销毁、进程级运行时控制器已释放，
 * 界面却还拿着失效句柄请求启动。修复前 `openHarness` 对它是零处理——不刷新、不重试，
 * 直接把错误甩给用户，于是用户看到连续三行失败，而其中至少第一次是可以自愈的。
 *
 * 这一组单独成文件，钉住三件事（对应 `openHarness` 里那段只放行一个码的重试）：
 *  1. **恰好重试一次**能自愈 —— 第二次成功时不弹错，也不再重试第三次；
 *  2. **重试上限** —— 两次都失败就照原样收尾并给出受控文案，绝不无限重连；
 *  3. **只对这一个码生效** —— 其他受控错误码一次都不重试，行为与修复前完全一致。
 *
 * 为什么放在 `App.runtime.test.tsx` 之外：那个文件覆盖「运行与后台」一整页的十余条用例，
 * 重试用例的成败取决于「启动序列跑了几次」这类计数断言，混在一起时失败信息指向不了重试逻辑本身。
 */
describe('运行时已关闭时的至多一次恢复重试', () => {
  beforeEach(beforeEachAppTest)

  /**
   * 构造原生侧真正的失败形状：错误码挂在异常对象上，而不是写在 message 里。
   *
   * 必须是这个形状，不能写成 `new Error('RUNTIME_CLOSED')`：界面侧判定走的是
   * `error.code`（`bridgedRuntimeCode`），把码写进 message 等于测了一条不存在的路径——
   * 那样用例即使「通过」也证明不了重试逻辑读对了字段。
   */
  const bridgedError = (code: string): Error => Object.assign(new Error(code), { code })

  /** 让挂载时读到的运行时是「已安装但未运行」，从而走 `startHarness` 分支。 */
  const readyButStopped = (): void => {
    bridge.getState.mockResolvedValue({ ...runningState, phase: 'ready', harnessUrl: undefined })
  }

  it('第一次以 RUNTIME_CLOSED 失败、重连后成功：恰好 2 次启动，且不弹错误提示', async () => {
    readyButStopped()
    // 记下「失败发生那一刻已经读过几次状态」，后面用增量断言重连前的刷新，不猜挂载链自己读了几次。
    let readsBeforeFailure = 0
    bridge.startHarness
      .mockImplementationOnce(() => {
        readsBeforeFailure = bridge.getState.mock.calls.length
        return Promise.reject(bridgedError('RUNTIME_CLOSED'))
      })
      .mockResolvedValue({ ...runningState })

    render(<App />)

    // 自动启动链走到这里说明重试已经成功：只调一次 startHarness 的修复前行为会永远等不到它。
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    // 关键计数：恰好两次，不能是 1（没重试）也不能是 3（循环了）。
    expect(bridge.startHarness).toHaveBeenCalledTimes(2)
    // 重连前恰好刷新一次状态：一次都不刷是拿着旧快照再问一遍同一个失效句柄；刷两次以上则是多余往返。
    expect(bridge.getState.mock.calls.length - readsBeforeFailure).toBe(1)
    // 成功路径上不该出现任何错误提示，尤其是那句「运行时已关闭」——它会让用户以为白重连了一次。
    expect(screen.queryAllByRole('alert')).toHaveLength(0)
    expect(screen.queryByText(/运行时已关闭/)).not.toBeInTheDocument()
  })

  it('两次都以 RUNTIME_CLOSED 失败：不会无限重试，并给出「运行时已关闭」的受控文案', async () => {
    readyButStopped()
    bridge.startHarness
      .mockRejectedValueOnce(bridgedError('RUNTIME_CLOSED'))
      .mockRejectedValueOnce(bridgedError('RUNTIME_CLOSED'))

    render(<App />)

    const alert = await screen.findByRole('alert')
    // 上限断言：第一次失败 → 重连一次 → 第二次失败即收尾，绝无第三次。
    expect(bridge.startHarness).toHaveBeenCalledTimes(2)
    expect(bridge.openHarness).not.toHaveBeenCalled()
    // 受控文案必须同时讲清「旧连接失效」与「下一步做什么」，否则用户只能反复点同一个按钮。
    expect(alert).toHaveTextContent('运行时已关闭，旧连接已失效；已尝试重新连接，请再试一次。')
  })

  it('其他受控错误码失败时行为不变：只启动一次、不重试', async () => {
    readyButStopped()
    /*
     * 用**增量**而不是绝对次数来钉「有没有为重试多刷新一次」。
     *
     * 挂载链自己会读一次状态（`App.tsx` 的启动读取），而失败收尾里还有一次尽力刷新
     * （`The launch error is the actionable failure; refresh is best effort.`）——
     * 后者是修复前就有的行为，不该被这条用例算成「重试的痕迹」。基线取「第一次
     * startHarness 被调用时已经发生的读取次数」，增量就只剩「失败之后还读了几次」。
     */
    let readsBeforeFailure = 0
    bridge.startHarness.mockImplementation(() => {
      readsBeforeFailure = bridge.getState.mock.calls.length
      return Promise.reject(bridgedError('RUNTIME_BUSY'))
    })

    render(<App />)

    // 文案来自同一张受控码表：证明走的是「有码的错误」这条路，而不是退化成原生 message。
    expect(await screen.findByRole('alert')).toHaveTextContent('请先停止 Harness 和 Ubuntu 终端。')
    expect(bridge.startHarness).toHaveBeenCalledTimes(1)
    expect(bridge.openHarness).not.toHaveBeenCalled()
    // 不是 RUNTIME_CLOSED 就不该为它刷新状态再试：重试一次只会得到同样的结果，白让用户多等一轮。
    // 失败后只允许**一次**尽力刷新（收尾那条），不允许「刷新 + 再启动」的重连痕迹。
    expect(bridge.getState.mock.calls.length - readsBeforeFailure).toBe(1)
  })
})
