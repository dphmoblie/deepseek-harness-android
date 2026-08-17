// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoryEntry, SessionHistoryRequest, SessionHistoryValue } from '../api/types'
import type * as WireModule from '../api/wire'

const wire = vi.hoisted(() => ({
  callUnary: vi.fn(),
  sendResponse: vi.fn(),
}))

vi.mock('../api/wire', async (importOriginal) => {
  const actual = await importOriginal<typeof WireModule>()
  return { ...actual, ...wire }
})

import { eventBus, useChat } from './chat'

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function historyPage(seq: number, text: string, hasMore: boolean): SessionHistoryValue {
  const event: HistoryEntry = {
    event: {
      type: 'user/message',
      seq,
      time: 0,
      data: {
        message: {
          id: `message-${seq}`,
          role: 'user',
          content: [{ type: 'text', text }],
          source: { kind: 'user' },
        },
      },
    },
  }
  return { events: [event], hasMore }
}

function mockHistoryRequests(
  sessionAOlder: Deferred<SessionHistoryValue>,
  sessionBOlder: Deferred<SessionHistoryValue>,
): void {
  wire.callUnary.mockImplementation((_baseUrl: string, method: string, payload: unknown) => {
    expect(method).toBe('session.history')
    const request = payload as SessionHistoryRequest
    if (request.sessionId === 'session-a' && request.beforeSeq === undefined) {
      return Promise.resolve(historyPage(10, 'A newest', true))
    }
    if (request.sessionId === 'session-a' && request.beforeSeq === 10) {
      return sessionAOlder.promise
    }
    if (request.sessionId === 'session-b' && request.beforeSeq === undefined) {
      return Promise.resolve(historyPage(20, 'B newest', true))
    }
    if (request.sessionId === 'session-b' && request.beforeSeq === 20) {
      return sessionBOlder.promise
    }
    return Promise.reject(new Error('Unexpected history request'))
  })
}

async function waitForSessionHead(
  result: { current: ReturnType<typeof useChat> },
  seq: number,
): Promise<void> {
  await waitFor(() => {
    expect(result.current.loading).toBe(false)
    expect(result.current.entries.map((entry) => entry.seq)).toEqual([seq])
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  cleanup()
})

describe('useChat history pagination', () => {
  it('ignores an old session page without ending the new session pagination', async () => {
    const sessionAOlder = deferred<SessionHistoryValue>()
    const sessionBOlder = deferred<SessionHistoryValue>()
    mockHistoryRequests(sessionAOlder, sessionBOlder)

    const { result, rerender } = renderHook(
      ({ sessionId }) => useChat(sessionId),
      { initialProps: { sessionId: 'session-a' as string | null } },
    )
    await waitForSessionHead(result, 10)

    let oldLoadMore!: Promise<void>
    act(() => {
      oldLoadMore = result.current.loadMore()
    })
    await waitFor(() => expect(result.current.loadingMore).toBe(true))

    rerender({ sessionId: 'session-b' })
    await waitForSessionHead(result, 20)
    expect(result.current.loadingMore).toBe(false)

    let currentLoadMore!: Promise<void>
    act(() => {
      currentLoadMore = result.current.loadMore()
    })
    await waitFor(() => expect(result.current.loadingMore).toBe(true))

    await act(async () => {
      sessionAOlder.resolve(historyPage(5, 'A older', false))
      await oldLoadMore
    })

    expect(result.current.entries.map((entry) => entry.seq)).toEqual([20])
    expect(result.current.hasMore).toBe(true)
    expect(result.current.loadingMore).toBe(true)
    expect(result.current.error).toBeNull()

    await act(async () => {
      sessionBOlder.resolve(historyPage(15, 'B older', false))
      await currentLoadMore
    })

    expect(result.current.entries.map((entry) => entry.seq)).toEqual([15, 20])
    expect(result.current.hasMore).toBe(false)
    expect(result.current.loadingMore).toBe(false)
  })

  it('ignores an old session pagination failure and its finalizer', async () => {
    const sessionAOlder = deferred<SessionHistoryValue>()
    const sessionBOlder = deferred<SessionHistoryValue>()
    mockHistoryRequests(sessionAOlder, sessionBOlder)

    const { result, rerender } = renderHook(
      ({ sessionId }) => useChat(sessionId),
      { initialProps: { sessionId: 'session-a' as string | null } },
    )
    await waitForSessionHead(result, 10)

    let oldLoadMore!: Promise<void>
    act(() => {
      oldLoadMore = result.current.loadMore()
    })
    await waitFor(() => expect(result.current.loadingMore).toBe(true))

    rerender({ sessionId: 'session-b' })
    await waitForSessionHead(result, 20)

    let currentLoadMore!: Promise<void>
    act(() => {
      currentLoadMore = result.current.loadMore()
    })
    await waitFor(() => expect(result.current.loadingMore).toBe(true))

    await act(async () => {
      sessionAOlder.reject(new Error('stale session failed'))
      await oldLoadMore
    })

    expect(result.current.error).toBeNull()
    expect(result.current.loadingMore).toBe(true)
    expect(result.current.entries.map((entry) => entry.seq)).toEqual([20])

    await act(async () => {
      sessionBOlder.resolve(historyPage(15, 'B older', false))
      await currentLoadMore
    })

    expect(result.current.entries.map((entry) => entry.seq)).toEqual([15, 20])
    expect(result.current.loadingMore).toBe(false)
  })
})

describe('useChat realtime validation', () => {
  it('把畸形 assistant chunk 转为可见的具体错误', async () => {
    wire.callUnary.mockResolvedValue({ events: [], hasMore: false })
    const { result } = renderHook(() => useChat('session-a'))
    await waitFor(() => expect(result.current.loading).toBe(false))

    act(() => {
      const bus = eventBus as unknown as {
        dispatch: (stream: 'mux', rpcId: string, frame: unknown) => void
      }
      bus.dispatch('mux', 'bad-chunk', {
        type: 'session/event',
        sessionId: 'session-a',
        event: {
          type: 'assistant/chunk',
          seq: 1,
          time: Date.now(),
          data: { turn: 1, step: 1 },
        },
      })
    })

    expect(result.current.error).toBe('Harness 事件流异常：assistant/chunk 载荷格式无效')
    expect(result.current.draft).toBeNull()
  })
})
