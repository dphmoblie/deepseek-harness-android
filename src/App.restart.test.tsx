import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

describe('停止后重新打开 Harness', () => {
  beforeEach(beforeEachAppTest)

  it('返回设置后再次打开运行中的 Harness，不重复启动服务', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    const starts = bridge.startHarness.mock.calls.length
    const open = await screen.findByRole('button', { name: '打开 Harness' })
    await waitFor(() => expect(open).toBeEnabled())
    bridge.getKeepAliveState.mockImplementation(() => new Promise(() => {}))

    fireEvent.click(open)

    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(open).toBeEnabled())
    expect(bridge.startHarness).toHaveBeenCalledTimes(starts)
  })

  it('再次打开无响应且状态查询卡住时释放按钮，旧回调不解锁新一次打开', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      render(<App />)
      await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
      let finishOldOpen!: () => void
      bridge.openHarness.mockImplementationOnce(() => new Promise<void>(resolve => { finishOldOpen = resolve }))
      const open = await screen.findByRole('button', { name: '打开 Harness' })
      await waitFor(() => expect(open).toBeEnabled())
      fireEvent.click(open)
      await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(2))
      bridge.getState.mockImplementation(() => new Promise(() => {}))

      await act(async () => { await vi.advanceTimersByTimeAsync(45_000) })
      expect(await screen.findByRole('alert')).toHaveTextContent('设备没有在预期时间内响应')
      fireEvent.click(screen.getByRole('button', { name: '设置' }))
      const retry = screen.getByRole('button', { name: '打开 Harness' })
      expect(retry).toBeEnabled()

      let finishRetry!: () => void
      bridge.openHarness.mockImplementationOnce(() => new Promise<void>(resolve => { finishRetry = resolve }))
      fireEvent.click(retry)
      await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(3))
      await act(async () => { finishOldOpen(); await Promise.resolve() })
      expect(retry).toBeDisabled()
      await act(async () => { finishRetry(); await Promise.resolve() })
      await waitFor(() => expect(retry).toBeEnabled())
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    } finally {
      vi.useRealTimers()
    }
  })

  it.each(['pending', 'rejected'] as const)('辅助状态查询 %s 不影响停止完成与再次启动', async outcome => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    if (outcome === 'pending') {
      bridge.getKeepAliveState.mockImplementation(() => new Promise(() => {}))
    } else {
      bridge.getKeepAliveState.mockRejectedValue(new Error('SHIZUKU_UNAVAILABLE'))
    }

    // 停止键统一在首页启动卡片：夹具自动启动后落在设置首页，先切回首页。
    fireEvent.click(await screen.findByRole('button', { name: '首页' }))
    fireEvent.click(await screen.findByRole('button', { name: '停止' }))
    await waitFor(() => expect(bridge.stopRuntime).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('运行环境已停止')).toBeVisible()

    const open = screen.getByRole('button', { name: '打开 Harness' })
    await waitFor(() => expect(open).toBeEnabled())
    const previousStarts = bridge.startHarness.mock.calls.length
    fireEvent.click(open)
    await waitFor(() => expect(bridge.startHarness).toHaveBeenCalledTimes(previousStarts + 1))
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.getByRole('button', { name: '打开 Harness' })).toBeEnabled())
  })

  it('原生停止超时会释放按钮并给出恢复指引，不谎报成功', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    bridge.stopRuntime.mockRejectedValue(Object.assign(new Error('停止超时'), { code: 'SHIZUKU_UNBIND_TIMEOUT' }))

    // 停止键统一在首页启动卡片：夹具自动启动后落在设置首页，先切回首页。
    fireEvent.click(await screen.findByRole('button', { name: '首页' }))
    fireEvent.click(screen.getByRole('button', { name: '停止' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('请在 Shizuku 中重启服务后重试')
    expect(screen.queryByText('运行环境已停止')).not.toBeInTheDocument()
    // 停止失败时视图留在首页；「打开 Harness」按钮在设置首页头部。
    fireEvent.click(screen.getByRole('button', { name: '设置' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '打开 Harness' })).toBeEnabled())
  })

  /**
   * 桥调用**永不返回**时的自救。
   *
   * 这是 `run()` 互斥锁唯一的单点故障：`finally` 不执行，忙碌状态就永远不结束，
   * 用户面对的是一个全部按钮禁用、也没有任何提示的界面，只能杀进程。
   * 真机上 provider 掉线、原生线程池打满、WebView 回调丢失都会造成这种形态，
   * 因此在假时钟上精确复现「promise 永不 settle」，钉死看门狗的两条语义：
   * 到点前不许提前放行，到点后必须放行**且如实说明「没有响应」**。
   */
  it('桥调用卡死时看门狗释放互斥并提示超时，界面不永久停在处理中', async () => {
    // 45 秒的看门狗只能靠假时钟推进；shouldAdvanceTime 保留 RTL 的 waitFor 可用的真实时间。
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      render(<App />)
      await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
      bridge.stopRuntime.mockImplementation(() => new Promise(() => {}))

      fireEvent.click(await screen.findByRole('button', { name: '首页' }))
      fireEvent.click(screen.getByRole('button', { name: '停止' }))
      await waitFor(() => expect(bridge.stopRuntime).toHaveBeenCalledTimes(1))

      // 还没到看门狗时间：必须仍然是忙碌态，不能提前把按钮放出来造成重复提交。
      await vi.advanceTimersByTimeAsync(30_000)
      expect(screen.getByRole('button', { name: '停止' })).toBeDisabled()

      // 到点：放开互斥，并且只说「没有响应」——底层请求可能仍在原生侧执行，
      // 绝不能顺手写成「已停止」（那就成了界面在替原生侧撒谎）。
      await vi.advanceTimersByTimeAsync(15_000)
      await waitFor(() => expect(screen.getByRole('button', { name: '停止' })).toBeEnabled())
      expect(screen.queryByText('运行环境已停止')).not.toBeInTheDocument()
      expect(await screen.findByRole('alert')).toHaveTextContent('设备没有在预期时间内响应')
    } finally {
      vi.useRealTimers()
    }
  })
})
