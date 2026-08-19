// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type * as WireModule from '../api/wire'
import type { BusEvent } from './events'

const mocks = vi.hoisted(() => ({
  callUnary: vi.fn(),
  listener: null as ((event: BusEvent) => void) | null,
  unsubscribe: vi.fn(),
}))

vi.mock('../api/wire', async (importOriginal) => {
  const actual = await importOriginal<typeof WireModule>()
  return { ...actual, callUnary: mocks.callUnary }
})

vi.mock('./events', () => ({
  eventBus: {
    subscribe: vi.fn((listener: (event: BusEvent) => void) => {
      mocks.listener = listener
      return mocks.unsubscribe
    }),
  },
}))

import { RpcFailure, TransportError } from '../api/wire'
import { useSessions } from './sessions'

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function sessionsValue(sessionId = 'session-current') {
  return {
    items: [{ sessionId, updatedAt: 10, running: false, blank: true }],
  }
}

function workspacesValue(workspaceId = 'workspace-current') {
  return {
    items: [{
      workspaceId,
      path: `/work/${workspaceId}`,
      title: workspaceId,
      sessionIds: [],
      createdAt: '2026-08-17T00:00:00.000Z',
      updatedAt: '2026-08-17T00:00:00.000Z',
    }],
    archivedSessionIds: [],
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.listener = null
  mocks.callUnary.mockImplementation((_baseUrl: string, method: string) => {
    if (method === 'session.list') return Promise.resolve(sessionsValue())
    if (method === 'workspace.list') return Promise.resolve(workspacesValue())
    throw new Error(`Unexpected RPC: ${method}`)
  })
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('useSessions reload ownership', () => {
  it('监听 workspace removed 与 order changed，并节流刷新', async () => {
    const { result } = renderHook(() => useSessions())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(mocks.callUnary.mock.calls.filter((call) => call[1] === 'workspace.list')).toHaveLength(1)
    expect(mocks.listener).not.toBeNull()

    vi.useFakeTimers()
    act(() => {
      mocks.listener?.({
        stream: 'host',
        rpcId: 'workspace-removed',
        frame: { type: 'host/workspace-removed', workspaceId: 'workspace-old' },
      })
      vi.advanceTimersByTime(500)
    })
    await act(async () => { await Promise.resolve() })
    expect(mocks.callUnary.mock.calls.filter((call) => call[1] === 'workspace.list')).toHaveLength(2)

    act(() => {
      mocks.listener?.({
        stream: 'host',
        rpcId: 'workspace-order',
        frame: { type: 'host/workspace-order-changed', workspaceIds: ['workspace-current'] },
      })
      vi.advanceTimersByTime(500)
    })
    await act(async () => { await Promise.resolve() })
    expect(mocks.callUnary.mock.calls.filter((call) => call[1] === 'workspace.list')).toHaveLength(3)
  })

  it('中止旧 reload，迟到响应不能覆盖新 generation', async () => {
    const oldSessions = deferred<ReturnType<typeof sessionsValue>>()
    const oldWorkspaces = deferred<ReturnType<typeof workspacesValue>>()
    let sessionLoads = 0
    let workspaceLoads = 0
    mocks.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'session.list') {
        sessionLoads += 1
        return sessionLoads === 1 ? oldSessions.promise : Promise.resolve(sessionsValue('session-new'))
      }
      if (method === 'workspace.list') {
        workspaceLoads += 1
        return workspaceLoads === 1 ? oldWorkspaces.promise : Promise.resolve(workspacesValue('workspace-new'))
      }
      throw new Error(`Unexpected RPC: ${method}`)
    })

    const { result } = renderHook(() => useSessions())
    await act(async () => { await result.current.reload() })
    expect(result.current.items.map((item) => item.sessionId)).toEqual(['session-new'])
    expect(result.current.workspaces.map((item) => item.workspaceId)).toEqual(['workspace-new'])
    const firstSignal = (mocks.callUnary.mock.calls[0]?.[3] as { signal?: AbortSignal } | undefined)?.signal
    expect(firstSignal?.aborted).toBe(true)

    oldSessions.resolve(sessionsValue('session-old'))
    oldWorkspaces.resolve(workspacesValue('workspace-old'))
    await act(async () => { await Promise.all([oldSessions.promise, oldWorkspaces.promise]) })
    expect(result.current.items.map((item) => item.sessionId)).toEqual(['session-new'])
    expect(result.current.workspaces.map((item) => item.workspaceId)).toEqual(['workspace-new'])
  })

  it('创建失败返回可就地展示的脱敏原因', async () => {
    mocks.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'session.list') return Promise.resolve(sessionsValue())
      if (method === 'workspace.list') return Promise.resolve(workspacesValue())
      if (method === 'session.create') return Promise.reject(new RpcFailure({
        code: 'provider-rejected',
        message: 'api_key=sk-1234567890abcdef rejected',
        details: {},
      }))
      throw new Error(`Unexpected RPC: ${method}`)
    })

    const { result } = renderHook(() => useSessions())
    await waitFor(() => expect(result.current.loading).toBe(false))
    let outcome!: Awaited<ReturnType<typeof result.current.createSessionResult>>
    await act(async () => { outcome = await result.current.createSessionResult() })

    expect(outcome.sessionId).toBeNull()
    expect(outcome.error).toContain('[已隐藏]')
    expect(outcome.error).not.toContain('sk-1234567890abcdef')
    expect(result.current.error).toBe(outcome.error)
  })

  it('在任意页面保留 agent 与事件流的具体错误', async () => {
    const { result } = renderHook(() => useSessions())
    await waitFor(() => expect(result.current.loading).toBe(false))

    act(() => {
      mocks.listener?.({
        stream: 'host',
        rpcId: 'agent-error',
        frame: { type: 'host/agent-error', sessionId: 'session-background', message: '模型供应商配额不足' },
      })
    })
    expect(result.current.error).toBe('本轮运行失败：模型供应商配额不足')

    act(() => {
      mocks.listener?.({
        stream: 'mux',
        rpcId: 'stream-error',
        frame: {
          type: 'stream/error',
          error: { code: 'TRANSPORT_ERROR', message: '事件流异常关闭（代码 1006）', details: {} },
        },
      })
    })
    expect(result.current.error).toBe('本轮运行失败：模型供应商配额不足')

    act(() => result.current.dismissError())
    act(() => {
      mocks.listener?.({
        stream: 'mux',
        rpcId: 'stream-error-after-dismiss',
        frame: {
          type: 'stream/error',
          error: { code: 'TRANSPORT_ERROR', message: '事件流异常关闭（代码 1006）', details: {} },
        },
      })
    })
    expect(result.current.error).toBe('Harness 事件流异常：事件流异常关闭（代码 1006）')
  })

  it('事件流断开不覆盖更具体的初始 RPC 错误', async () => {
    mocks.callUnary.mockRejectedValue(new TransportError('Harness 后端返回 HTTP 503', 503))
    const { result } = renderHook(() => useSessions())
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('加载会话与工作区失败：Harness 后端返回 HTTP 503')

    act(() => {
      mocks.listener?.({
        stream: 'mux',
        rpcId: 'stream-error',
        frame: {
          type: 'stream/error',
          error: { code: 'TRANSPORT_ERROR', message: '事件流连接意外中断', details: {} },
        },
      })
    })
    expect(result.current.error).toBe('加载会话与工作区失败：Harness 后端返回 HTTP 503')
  })
})
