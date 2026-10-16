import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

describe('停止后重新打开 Harness', () => {
  beforeEach(beforeEachAppTest)

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
})
