// @vitest-environment jsdom

import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type * as WireModule from '../api/wire'
import type { BusEvent, BusListener } from '../state/events'
import type { HistoryEntry } from '../api/types'

const wire = vi.hoisted(() => ({ callUnary: vi.fn() }))
const eventHarness = vi.hoisted(() => {
  const listeners = new Set<BusListener>()
  return {
    subscribe: vi.fn((listener: BusListener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    }),
    emit(event: BusEvent) {
      listeners.forEach((listener) => listener(event))
    },
    reset() {
      listeners.clear()
    },
  }
})

vi.mock('../api/wire', async (importOriginal) => {
  const actual = await importOriginal<typeof WireModule>()
  return { ...actual, ...wire }
})

vi.mock('../state/events', () => ({
  eventBus: { subscribe: eventHarness.subscribe },
}))

import { RpcFailure } from '../api/wire'
import { todosFromHistory } from '../state/projections'
import { TasksView } from './TasksView'

const SESSIONS = [
  { sessionId: 'session-a', updatedAt: 20, running: true, blank: false, cwd: '/work/a' },
  { sessionId: 'session-b', updatedAt: 10, running: false, blank: false, cwd: '/work/b' },
]

function historyEntry(type: string, seq: number, data: Record<string, unknown>): HistoryEntry {
  return { event: { type, seq, time: seq, data } }
}

beforeEach(() => {
  vi.clearAllMocks()
  eventHarness.reset()
  wire.callUnary.mockImplementation((_baseUrl: string, method: string) => {
    if (method === 'session.list') return Promise.resolve({ items: SESSIONS })
    if (method === 'session.history') {
      return Promise.resolve({ events: [], hasMore: false, projections: { asOfSeq: -1, values: {} } })
    }
    throw new Error(`Unexpected RPC: ${method}`)
  })
})

afterEach(cleanup)

describe('TasksView history and live state', () => {
  it('优先从 session.history 的 todos 投影恢复待办', async () => {
    wire.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'session.list') return Promise.resolve({ items: SESSIONS })
      if (method === 'session.history') {
        return Promise.resolve({
          events: [historyEntry('todo/write', 3, { todos: [{ content: '旧回放项', status: 'pending' }] })],
          hasMore: false,
          projections: {
            asOfSeq: 4,
            values: { todos: [{ content: '投影中的当前项', status: 'in_progress' }] },
          },
        })
      }
      throw new Error(`Unexpected RPC: ${method}`)
    })

    render(<TasksView onBack={vi.fn()} />)

    expect(await screen.findByText('投影中的当前项')).toBeInTheDocument()
    expect(screen.queryByText('旧回放项')).not.toBeInTheDocument()
    expect(wire.callUnary).toHaveBeenCalledWith(
      expect.any(String),
      'session.history',
      { sessionId: 'session-a', maxMessages: 100 },
      expect.anything(),
    )
    const historyCall = wire.callUnary.mock.calls.find((call) => call[1] === 'session.history')
    expect((historyCall?.[3] as { signal?: unknown } | undefined)?.signal).toBeInstanceOf(AbortSignal)
  })

  it('投影畸形时按 seq 回放 todo/write，并在 turn/start 清空旧计划', () => {
    const todos = todosFromHistory({
      hasMore: false,
      projections: { asOfSeq: 4, values: { todos: [{ content: 'bad', status: 'unknown' }] } },
      events: [
        historyEntry('todo/write', 4, { todos: [{ content: '当前计划', status: 'pending' }] }),
        historyEntry('todo/write', 2, { todos: [{ content: '上一轮计划', status: 'completed' }] }),
        historyEntry('turn/start', 3, { turn: 2 }),
      ],
    })

    expect(todos).toEqual([{ content: '当前计划', status: 'pending' }])
  })

  it('切换会话立即清空旧状态，忽略旧会话帧及迟到历史', async () => {
    let resolveSessionB: ((value: unknown) => void) | undefined
    const sessionBHistory = new Promise((resolve) => { resolveSessionB = resolve })
    wire.callUnary.mockImplementation((_baseUrl: string, method: string, payload: unknown) => {
      if (method === 'session.list') return Promise.resolve({ items: SESSIONS })
      if (method === 'session.history') {
        const sessionId = (payload as { sessionId: string }).sessionId
        if (sessionId === 'session-b') return sessionBHistory
        return Promise.resolve({
          events: [],
          hasMore: false,
          projections: { asOfSeq: 1, values: { todos: [{ content: '会话 A 待办', status: 'pending' }] } },
        })
      }
      throw new Error(`Unexpected RPC: ${method}`)
    })

    render(<TasksView onBack={vi.fn()} />)
    expect(await screen.findByText('会话 A 待办')).toBeInTheDocument()

    act(() => {
      eventHarness.emit({
        stream: 'mux',
        rpcId: 'jobs-a',
        frame: {
          type: 'session/jobs',
          sessionId: 'session-a',
          jobs: [{
            id: 'job-a',
            kind: 'shell',
            label: '会话 A 任务',
            status: 'running',
            startedAt: 10,
          }],
        },
      })
      eventHarness.emit({
        stream: 'mux',
        rpcId: 'todo-a',
        frame: {
          type: 'session/event',
          sessionId: 'session-a',
          event: historyEntry('todo/write', 5, {
            todos: [{ content: '会话 A 实时待办', status: 'in_progress' }],
          }).event,
        },
      })
    })
    expect(screen.getByText('会话 A 任务')).toBeInTheDocument()
    expect(screen.getByText('会话 A 实时待办')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('选择会话'), { target: { value: 'session-b' } })
    expect(screen.queryByText('会话 A 任务')).not.toBeInTheDocument()
    expect(screen.queryByText('会话 A 实时待办')).not.toBeInTheDocument()

    act(() => {
      eventHarness.emit({
        stream: 'mux',
        rpcId: 'late-a',
        frame: {
          type: 'session/projection',
          sessionId: 'session-a',
          key: 'todos',
          value: [{ content: '不应串入 B', status: 'pending' }],
          seq: 8,
        },
      })
      eventHarness.emit({
        stream: 'mux',
        rpcId: 'jobs-b',
        frame: {
          type: 'session/jobs',
          sessionId: 'session-b',
          jobs: [{
            id: 'job-b',
            kind: 'agent',
            label: '会话 B 任务',
            status: 'completed',
            startedAt: 11,
            finishedAt: 12,
          }],
        },
      })
      eventHarness.emit({
        stream: 'mux',
        rpcId: 'projection-b',
        frame: {
          type: 'session/projection',
          sessionId: 'session-b',
          key: 'todos',
          value: [{ content: '会话 B 实时待办', status: 'completed' }],
          seq: 9,
        },
      })
    })

    expect(screen.getByText('会话 B 任务')).toBeInTheDocument()
    expect(screen.getByText('会话 B 实时待办')).toBeInTheDocument()
    expect(screen.queryByText('不应串入 B')).not.toBeInTheDocument()

    await act(async () => {
      resolveSessionB?.({
        events: [],
        hasMore: false,
        projections: { asOfSeq: 2, values: { todos: [{ content: 'B 的迟到历史', status: 'pending' }] } },
      })
      await sessionBHistory
    })
    expect(screen.getByText('会话 B 实时待办')).toBeInTheDocument()
    expect(screen.queryByText('B 的迟到历史')).not.toBeInTheDocument()
  })

  it('历史 RPC 错误展示具体原因并脱敏', async () => {
    wire.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'session.list') return Promise.resolve({ items: SESSIONS })
      if (method === 'session.history') {
        return Promise.reject(new RpcFailure({
          code: 'internal',
          message: 'history unavailable: token=sk-1234567890abcdef',
          details: {},
        }))
      }
      throw new Error(`Unexpected RPC: ${method}`)
    })

    render(<TasksView onBack={vi.fn()} />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('内部错误')
    expect(alert).toHaveTextContent('history unavailable')
    expect(alert).toHaveTextContent('错误代码：internal')
    expect(alert).toHaveTextContent('[已隐藏]')
    expect(alert).not.toHaveTextContent('sk-1234567890abcdef')
  })
})
