import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { AutomationTask, AutomationTaskStep, RuntimeBridge } from '../platform/types'
import { AutomationTaskCard } from './AutomationTaskCard'

/**
 * 「自动化任务」卡片：当前任务、每一步的状态，以及人叫停它的那一下。
 *
 * 两条最要紧的边界在这里各有用例守着：**「读不到」不能说成「没有任务」**，
 * 以及**停止要如实说明它不撤回已经发出去的那一步**。
 *
 * 桥用假实现注入（同 `DeviceCapabilityCard.test.tsx`）：这些用例不碰任何真实设备状态。
 */
function step(id: string, overrides: Partial<AutomationTaskStep> = {}): AutomationTaskStep {
  return { id, action: 'click', status: 'pending', attempts: 1, code: null, ...overrides }
}

function task(overrides: Partial<AutomationTask> = {}): AutomationTask {
  return {
    taskId: 'send-message',
    status: 'running',
    packageName: 'com.example.chat',
    nextStep: 's2',
    settledSteps: 1,
    pendingSteps: 1,
    lastCode: null,
    lastMessage: null,
    steps: [step('s1', { status: 'ok' }), step('s2', { action: 'setText' })],
    ...overrides,
  }
}

function card(bridge: Partial<RuntimeBridge>): void {
  render(<AutomationTaskCard bridge={bridge as unknown as RuntimeBridge} />)
}

/**
 * 按整行文字找那一行。
 *
 * 行里有 `<strong>`，Testing Library 的 `getByText` 只拼**直接文本节点**，
 * 整段文字不会落在任何一个元素上；这里改用 `textContent` 全等匹配（同能力卡片用例）。
 */
function row(text: string): HTMLElement {
  const matches = screen.getAllByText((_, element) => element?.textContent === text)
  expect(matches).toHaveLength(1)
  return matches[0]
}

async function findRow(text: string): Promise<HTMLElement> {
  let found: HTMLElement | null = null
  await waitFor(() => { found = row(text) })
  return found as unknown as HTMLElement
}

describe('自动化任务卡片', () => {
  it('连着但没有任务时，说的是「没有正在执行的任务」', async () => {
    const bridge = { getAutomationTaskState: vi.fn().mockResolvedValue({ connected: true, task: null }), cancelAutomationTask: vi.fn() }
    card(bridge)

    await screen.findByText('现在没有正在执行的任务。')
    expect(screen.queryByText(/读不到任务状态/u)).toBeNull()
    expect(bridge.cancelAutomationTask).not.toHaveBeenCalled()
  })

  it('服务没连上时说读不到，并且明确这不是「没有任务」', async () => {
    card({ getAutomationTaskState: vi.fn().mockResolvedValue({ connected: false, task: null }) })

    await screen.findByText('无障碍服务没有连上，读不到任务状态。')
    screen.getByText('这不是「没有任务」：连接恢复后再读一次才知道现在是什么状态。')
    expect(screen.queryByText('现在没有正在执行的任务。')).toBeNull()
  })

  it('有任务时列出目标应用、状态、进度与每一步', async () => {
    card({ getAutomationTaskState: vi.fn().mockResolvedValue({ connected: true, task: task() }) })

    await findRow('目标应用：com.example.chat · 正在执行')
    row('进度：已收口 1 步，待执行 1 步，共 2 步。')
    row('点击 · 已完成')
    row('输入文字 · 待执行')
  })

  it('不确定的步骤写明「不要重发」并带上受控码', async () => {
    card({
      getAutomationTaskState: vi.fn().mockResolvedValue({
        connected: true,
        task: task({
          lastCode: 'AUTOMATION_TASK_NOT_VERIFIED',
          lastMessage: '这一步没看出效果，请先确认现场再决定',
          steps: [step('s1', { action: 'setText', status: 'unknown', code: 'AUTOMATION_TASK_NOT_VERIFIED', attempts: 2 })],
        }),
      }),
    })

    await findRow('输入文字 · 结果不确定，不要重发 · 已试 2 次（AUTOMATION_TASK_NOT_VERIFIED）')
    row('最近一次：这一步没看出效果，请先确认现场再决定（AUTOMATION_TASK_NOT_VERIFIED）')
  })

  it('认不出的动作与状态照原样显示，不编一句解释', async () => {
    card({
      getAutomationTaskState: vi.fn().mockResolvedValue({
        connected: true,
        task: task({ status: 'running', steps: [step('s1', { action: 'swipe', status: 'pending' })] }),
      }),
    })

    await findRow('swipe · 待执行')
  })

  it('「停止这个任务」调取消，回读的状态跟着变，并说明它不撤回已发出的那一步', async () => {
    const bridge = {
      getAutomationTaskState: vi.fn().mockResolvedValue({ connected: true, task: task() }),
      cancelAutomationTask: vi.fn().mockResolvedValue({
        connected: true,
        task: task({
          status: 'cancelled',
          settledSteps: 2,
          pendingSteps: 0,
          lastCode: 'AUTOMATION_TASK_CANCELLED',
          lastMessage: '任务已取消，剩下的步骤标成了跳过',
          steps: [step('s1', { status: 'ok' }), step('s2', { action: 'setText', status: 'skipped' })],
        }),
      }),
    }
    card(bridge)

    await findRow('目标应用：com.example.chat · 正在执行')
    row('停止只把剩下的步骤标成跳过，已经发出去的那一步不会撤回。')
    fireEvent.click(screen.getByRole('button', { name: '停止这个任务' }))

    await findRow('目标应用：com.example.chat · 已经停止')
    row('输入文字 · 已跳过（任务停止）')
    row('已经停止，剩下的步骤都标成了跳过。')
    expect(bridge.cancelAutomationTask).toHaveBeenCalledTimes(1)
  })

  it('停不下来时按受控码说原因，并给一次重试', async () => {
    const cancel = vi.fn()
      .mockRejectedValueOnce(new Error('AUTOMATION_TASK_NOT_PLANNED'))
      .mockResolvedValue({ connected: true, task: null })
    card({ getAutomationTaskState: vi.fn().mockResolvedValue({ connected: true, task: task() }), cancelAutomationTask: cancel })

    await findRow('目标应用：com.example.chat · 正在执行')
    fireEvent.click(screen.getByRole('button', { name: '停止这个任务' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('现在没有任务可停止')
    fireEvent.click(screen.getByRole('button', { name: '重试' }))

    await screen.findByText('现在没有正在执行的任务。')
    expect(cancel).toHaveBeenCalledTimes(2)
  })

  it('没登记过的取消失败码照原样显示，不编原因', async () => {
    card({
      getAutomationTaskState: vi.fn().mockResolvedValue({ connected: true, task: task() }),
      cancelAutomationTask: vi.fn().mockRejectedValue(new Error('SOMETHING_NEW')),
    })

    await findRow('目标应用：com.example.chat · 正在执行')
    fireEvent.click(screen.getByRole('button', { name: '停止这个任务' }))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('停止失败（SOMETHING_NEW）')
  })

  it('读不到当前任务时说读不到，并给一次重试', async () => {
    const read = vi.fn()
      .mockRejectedValueOnce(new Error('读不到'))
      .mockResolvedValue({ connected: true, task: task() })
    card({ getAutomationTaskState: read })

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('无法读取当前任务')
    expect(screen.queryByText('现在没有正在执行的任务。')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await findRow('目标应用：com.example.chat · 正在执行')
    expect(read).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('「重新读取」会再读一次，任务状态跟着变', async () => {
    const bridge = {
      getAutomationTaskState: vi.fn()
        .mockResolvedValueOnce({ connected: true, task: task() })
        .mockResolvedValueOnce({ connected: true, task: task({ status: 'blocked', lastCode: 'AUTOMATION_TASK_TARGET_LEFT', lastMessage: '前台已不是这个应用，任务停在这一步' }) }),
    }
    card(bridge)

    await findRow('目标应用：com.example.chat · 正在执行')
    fireEvent.click(screen.getByRole('button', { name: '重新读取' }))

    await findRow('目标应用：com.example.chat · 被挡住了：目标变了，要重新读页面再继续')
    row('最近一次：前台已不是这个应用，任务停在这一步（AUTOMATION_TASK_TARGET_LEFT）')
    expect(bridge.getAutomationTaskState).toHaveBeenCalledTimes(2)
  })
})
