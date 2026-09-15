import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, openSettingsPage } from './__tests__/appTestHarness'
import type { DiagnosticLogState } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 诊断与日志页：采集开关、日志导出/清空，以及「运行日志 / 诊断日志」两个按需读取的折叠面板。
 *
 * 从原来的单文件 `App.test.tsx` 按 view 拆出（登记册 5.6-J）。
 * 这一组的关键前提是**按需读取**（折叠状态不碰桥接）与**纯文本渲染**（访客输出不解析标签），
 * 单独成文件后不必与设置草稿、启动流程的用例共享一个 1900 行的上下文。
 */
describe('诊断与日志', () => {
  beforeEach(beforeEachAppTest)

  it('默认不收集，开关与保留天数按原生返回值更新', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    const toggle = await screen.findByRole('switch', { name: /收集诊断日志/ })
    await waitFor(() => expect(toggle).toBeEnabled())
    expect(toggle).not.toBeChecked()
    // 没有任何日志时可导出/清空按钮保持禁用，避免产生空文件。
    expect(screen.getByRole('button', { name: '导出并分享' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '清空日志' })).toBeDisabled()

    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.setDiagnosticLogSettings).toHaveBeenCalledWith(true, 3))
    expect(await screen.findByRole('switch', { name: /收集诊断日志/ })).toBeChecked()

    fireEvent.change(screen.getByRole('slider', { name: /保留天数/ }), { target: { value: '7' } })
    await waitFor(() => expect(bridge.setDiagnosticLogSettings).toHaveBeenCalledWith(true, 7))
  })

  it('有日志时可导出分享与清空，并显示计数', async () => {
    const withLogs: DiagnosticLogState = {
      enabled: true,
      retentionDays: 3,
      fileCount: 2,
      totalBytes: 4096,
      lastEntryAtMillis: 1_700_000_000_000,
    }
    bridge.getDiagnosticLogState.mockResolvedValue({ ...withLogs })
    // 导出成功返回的是导出后的状态：日志文件仍在，因此「清空日志」应保持可用。
    bridge.shareDiagnosticLog.mockResolvedValue({
      ...withLogs,
      fileName: 'dsh-diagnostic-20260912-102030.txt',
      exportedBytes: 512,
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    expect(await screen.findByText('收集中')).toBeVisible()
    expect(screen.getByText('2 个文件 · 4.0 KB')).toBeVisible()

    // 等待启动流程释放忙碌状态：run() 在忙碌时会直接忽略点击。
    const shareButton = screen.getByRole('button', { name: '导出并分享' })
    await waitFor(() => expect(shareButton).toBeEnabled())
    fireEvent.click(shareButton)
    await waitFor(() => expect(bridge.shareDiagnosticLog).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/诊断日志已导出/)).toBeVisible()

    const clearButton = screen.getByRole('button', { name: '清空日志' })
    await waitFor(() => expect(clearButton).toBeEnabled())
    fireEvent.click(clearButton)
    await waitFor(() => expect(bridge.clearDiagnosticLog).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('诊断日志已清空')).toBeVisible()
  })

  it('导出失败时保留状态并提示错误，不显示成功文案', async () => {
    bridge.getDiagnosticLogState.mockResolvedValue({
      enabled: true,
      retentionDays: 3,
      fileCount: 1,
      totalBytes: 128,
      lastEntryAtMillis: 1_700_000_000_000,
    })
    bridge.shareDiagnosticLog.mockRejectedValue(new Error('当前没有可导出的诊断日志'))
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: '导出并分享' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('当前没有可导出的诊断日志')
    expect(bridge.getDiagnosticLogState).toHaveBeenCalled()
  })

  it('运行日志折叠时不读取，展开后才按需读取并按纯文本渲染', async () => {
    const payload = '<img src=x onerror=alert(1)>\nError: tool call failed'
    bridge.getHarnessLog.mockResolvedValue({ available: true, text: payload })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    const toggle = await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    // 折叠状态不碰桥接：访客输出可能含会话内容，不展开就不带进界面。
    expect(bridge.getHarnessLog).not.toHaveBeenCalled()
    expect(document.querySelector('.harness-log-output')).toBeNull()

    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.getHarnessLog).toHaveBeenCalledTimes(1))
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    // 隐私边界必须在界面上写清，而不是只藏在文档里。
    expect(screen.getByText(/可能包含会话内容/)).toBeVisible()
    expect(screen.getByText(/不随诊断日志导出/)).toBeVisible()

    const output = document.querySelector('.harness-log-output')
    expect(output).not.toBeNull()
    // 访客输出按纯文本渲染：标签不解析，只作为文本出现，也不会产生 img 元素。
    expect(output?.querySelector('img')).toBeNull()
    expect(output?.textContent).toBe(payload)
  })

  it('没有可读取的运行日志时给出提示并隐藏复制按钮', async () => {
    bridge.getHarnessLog.mockResolvedValue({ available: false, text: '' })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ }))

    expect(await screen.findByText('当前没有可读取的运行日志')).toBeVisible()
    expect(screen.queryByRole('button', { name: '复制' })).toBeNull()
    expect(document.querySelector('.harness-log-output')).toBeNull()
  })

  it('展开后可用一键复制运行日志，复用系统剪贴板', async () => {
    const payload = 'Error: TOOL_CALL_FAILED\n    at handler (dsh.js:42:7)'
    bridge.getHarnessLog.mockResolvedValue({ available: true, text: payload })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ }))

    fireEvent.click(await screen.findByRole('button', { name: '复制' }))
    // 这里刻意只冲一次微任务就断言，不用 findBy/waitFor 轮询：
    // 「已复制」是 1.5 秒的临时反馈（App.tsx 的 copyResetTimer），而 waitFor 按真实时间轮询，
    // 机器一慢就可能等到提示自己消失之后才去查 —— 表现为一条与产品行为无关的偶发失败。
    // writeText 是在点击处理函数里同步调用的，setCopied(true) 只要一轮微任务即可落地。
    await act(async () => { await Promise.resolve() })
    expect(writeText).toHaveBeenCalledWith(payload)
    expect(screen.getByText('已复制')).toBeVisible()
  })

  it('运行日志可按关键字过滤，只渲染匹配行', async () => {
    const payload = 'INFO runtime ready\nError: TOOL_CALL_FAILED\nINFO idle'
    bridge.getHarnessLog.mockResolvedValue({ available: true, text: payload, maxBytes: 8 * 1024 })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ }))
    const output = await waitFor(() => {
      const node = document.querySelector('.harness-log-output')
      expect(node).not.toBeNull()
      return node as HTMLElement
    })
    expect(output.textContent).toBe(payload)

    fireEvent.change(screen.getByRole('searchbox', { name: '过滤日志' }), { target: { value: 'tool_call' } })
    await waitFor(() => expect(output.textContent).toBe('Error: TOOL_CALL_FAILED'))
    expect(screen.getByText('匹配 1 / 3 行')).toBeVisible()
  })

  it('展开运行日志后给出已确诊特征的判读提示', async () => {
    bridge.getHarnessLog.mockResolvedValue({
      available: true,
      text: "TypeError: Cannot read properties of undefined (reading 'prepare')",
      maxBytes: 8 * 1024,
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ }))

    const insights = await screen.findByRole('group', { name: '判读提示' })
    expect(insights).toHaveTextContent('工具调用全部失败（模块身份分裂）')
    expect(insights).toHaveTextContent('MODULE_GRAPH')
  })

  it('运行日志窗口可放大，并把窗口字节数交给原生侧', async () => {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /运行日志（最近 8 KB）/ }))
    await waitFor(() => expect(bridge.getHarnessLog).toHaveBeenCalledWith({ maxBytes: 8 * 1024 }))

    fireEvent.change(screen.getByRole('combobox', { name: '读取窗口' }), { target: { value: String(64 * 1024) } })
    await waitFor(() => expect(bridge.getHarnessLog).toHaveBeenCalledWith({ maxBytes: 64 * 1024 }))
    // 标题跟着窗口走，用户一眼能看出自己正在看多大的一段。
    expect(await screen.findByRole('button', { name: /运行日志（最近 64 KB）/ })).toBeVisible()
  })

  it('诊断日志可在应用内查看，含截断说明与判读提示', async () => {
    bridge.readDiagnosticLog.mockResolvedValue({
      text: '2026-09-12T10:21:04Z|WARN|MODULE_GRAPH|result=failed|count=2|files=4\n',
      maxBytes: 64 * 1024,
      totalBytes: 8192,
      truncated: true,
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    const toggle = await screen.findByRole('button', { name: /诊断日志（最近 64 KB）/ })
    // 与运行日志同样按需读取：折叠时不碰桥接。
    expect(bridge.readDiagnosticLog).not.toHaveBeenCalled()

    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.readDiagnosticLog).toHaveBeenCalledWith({ maxBytes: 64 * 1024 }))
    const output = document.querySelector('.diagnostic-log-output')
    expect(output?.textContent).toBe('2026-09-12T10:21:04Z|WARN|MODULE_GRAPH|result=failed|count=2|files=4\n')
    expect(screen.getByText(/已按窗口截断/)).toBeVisible()
    expect(await screen.findByRole('group', { name: '判读提示' })).toHaveTextContent('已确认存在两份运行时模块')
  })

  it('没有诊断日志时如实说明并提示开启采集', async () => {
    bridge.readDiagnosticLog.mockResolvedValue({ text: '', maxBytes: 64 * 1024, totalBytes: 0, truncated: false })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openSettingsPage('诊断与日志')
    fireEvent.click(await screen.findByRole('button', { name: /诊断日志（最近 64 KB）/ }))

    expect(await screen.findByText(/当前没有可查看的诊断日志/)).toBeVisible()
    expect(document.querySelector('.diagnostic-log-output')).toBeNull()
  })
})
