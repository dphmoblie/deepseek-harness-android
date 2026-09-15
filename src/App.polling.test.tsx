import { render, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, keepAlive, settings } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 后台状态轮询（登记册 5.6-C）。
 *
 * 从 `App.test.tsx` 拆出来单独成文件：这一组是**唯一**直接对定时器行为下断言的地方
 * （停表 / 恢复 / 单飞），也是最容易被「真实计时器 + 机器负载」影响的一组，
 * 单独成文件后失败定位一眼就能看出是轮询逻辑而不是别的界面行为。
 *
 * 修复前的形态：`setInterval` 无条件每 5 秒触发一次，只有各刷新器内部自查
 * `visibilityState === 'hidden'` 才不至于发出桥调用——后台仍在每 5 秒唤醒一次 JS，
 * 且没有单飞保护，慢请求会一层层堆积。这三条用例分别钉住「停表」「恢复」「单飞」。
 */
describe('后台状态轮询（5.6-C）', () => {
  beforeEach(beforeEachAppTest)

  /**
   * 停在主视图、不自动启动运行时。
   *
   * 这三条用例都在数桥调用次数，而**启动链自己就有一处 `getKeepAliveState`**
   * （`App.tsx` 启动分支里「后台状态不参与打开页面的关键路径」那次读取）。
   * 它是否落在测量窗口里取决于启动链跑到哪一步 —— 单独跑这个文件时它总在窗口之前，
   * 六个文件并行跑（CPU 争抢让 React 的提交延后）时它就可能落进窗口，
   * 表现成「单飞失效」的假阳性。关掉自动启动把这类噪声整体移出测量窗口，
   * 断言本身一条都没有放宽。
   */
  const stayOnMainView = (): void => {
    bridge.getSettings.mockResolvedValue({ ...settings, autoLaunch: false })
  }

  const setVisibility = (value: 'visible' | 'hidden'): void => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => value })
    document.dispatchEvent(new Event('visibilitychange'))
  }

  it('页面隐藏时停表：定时器被清除且不再重新武装，桥调用也不再发生', async () => {
    // shouldAdvanceTime 必须开着：testing-library 的 waitFor 在这里走的是真实计时器路径
    // （vitest 没开 globals，RTL 认不出 jest 假定时器），假时钟不随真实时间前进就会把 waitFor 挂死。
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const setSpy = vi.spyOn(window, 'setInterval')
    const clearSpy = vi.spyOn(window, 'clearInterval')
    // 只认我们那个 5 秒轮询：testing-library 的 waitFor 自己也会用 setInterval，
    // 直接数总次数会把它的定时器算进来（第一版断言就是这么被污染的）。
    const POLL_MS = 5000
    const armed = (): number => setSpy.mock.calls.filter(call => call[1] === POLL_MS).length
    const ourTimerId = (): unknown => {
      const index = setSpy.mock.calls.findIndex(call => call[1] === POLL_MS)
      return index === -1 ? undefined : setSpy.mock.results[index]?.value
    }
    try {
      stayOnMainView()
      render(<App />)
      await waitFor(() => expect(bridge.getKeepAliveState).toHaveBeenCalled())
      // 挂载即武装轮询。
      expect(armed()).toBe(1)

      setVisibility('hidden')
      // 关键断言：必须**真的清掉**那个定时器。只断言「后台没有桥调用」是不够的——
      // 修复前各刷新器会自查 visibilityState 而提前返回，同样不会发调用，
      // 于是「后台仍在每 5 秒唤醒一次 JS」这个真问题会被测试漏掉。
      expect(clearSpy.mock.calls.some(call => call[0] === ourTimerId())).toBe(true)

      // 先让挂载期那些零散的初始读取落地（它们不来自轮询），再开始计桥调用：
      // 否则会把「初始加载晚到的调用」误判成「停表失效」。
      await vi.advanceTimersByTimeAsync(6_000)
      bridge.getKeepAliveState.mockClear()
      bridge.getShizukuState.mockClear()
      bridge.getOverlayBallState.mockClear()

      await vi.advanceTimersByTimeAsync(20_000)
      expect(armed()).toBe(1)
      expect(bridge.getKeepAliveState).not.toHaveBeenCalled()
      expect(bridge.getShizukuState).not.toHaveBeenCalled()
      expect(bridge.getOverlayBallState).not.toHaveBeenCalled()

      // 回到前台必须重新武装，否则轮询会永久停摆（这比不停表更糟）。
      setVisibility('visible')
      expect(armed()).toBe(2)
    } finally {
      setSpy.mockRestore()
      clearSpy.mockRestore()
      vi.useRealTimers()
      setVisibility('visible')
    }
  })

  it('回到前台立刻刷新一次，并恢复轮询', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      stayOnMainView()
      render(<App />)
      await waitFor(() => expect(bridge.getKeepAliveState).toHaveBeenCalled())
      setVisibility('hidden')
      await vi.advanceTimersByTimeAsync(10_000)
      bridge.getKeepAliveState.mockClear()
      setVisibility('visible')
      // 回到前台必须**立刻**读一次，而不是等下一个 5 秒：用户可能刚在系统设置里改过权限。
      await waitFor(() => expect(bridge.getKeepAliveState).toHaveBeenCalledTimes(1))
      await vi.advanceTimersByTimeAsync(5_000)
      await waitFor(() => expect(bridge.getKeepAliveState.mock.calls.length).toBeGreaterThanOrEqual(2))
    } finally {
      vi.useRealTimers()
      setVisibility('visible')
    }
  })

  it('上一轮还没结束时跳过这一轮，请求不堆积', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      stayOnMainView()
      render(<App />)
      await waitFor(() => expect(bridge.getKeepAliveState).toHaveBeenCalled())
      // 让这一轮永远悬着：单飞保护生效时，后续 tick 应当整体跳过（三路都不再发）。
      let releasePending: (value: typeof keepAlive) => void = () => undefined
      bridge.getKeepAliveState.mockImplementation(() => new Promise(resolve => { releasePending = resolve }))
      bridge.getShizukuState.mockClear()
      bridge.getOverlayBallState.mockClear()
      const before = bridge.getKeepAliveState.mock.calls.length
      await vi.advanceTimersByTimeAsync(25_000)
      expect(bridge.getKeepAliveState.mock.calls.length - before).toBe(1)
      expect(bridge.getShizukuState).toHaveBeenCalledTimes(1)
      expect(bridge.getOverlayBallState).toHaveBeenCalledTimes(1)
      releasePending({ ...keepAlive })
    } finally {
      vi.useRealTimers()
      setVisibility('visible')
    }
  })
})
