import { describe, expect, it, vi } from 'vitest'
import {
  callUnary,
  openEventStream,
  parseServerFrame,
  parseServerResponse,
  sendResponse,
  TransportError,
} from './wire'
import type { ServerRequest } from './types'

const BASE = 'http://127.0.0.1:3080/'

describe('parseServerResponse', () => {
  it('解析成功信封（含 void 结果缺失 value 的情况）', () => {
    const parsed = parseServerResponse(
      JSON.stringify({ type: 'server-response', rpcId: 'r1', result: { ok: true, value: { sessionId: 's1' } } }),
    )
    expect(parsed).not.toBeNull()
    expect(parsed?.rpcId).toBe('r1')
    const noValue = parseServerResponse(
      JSON.stringify({ type: 'server-response', rpcId: 'r2', result: { ok: true } }),
    )
    expect(noValue).not.toBeNull()
  })

  it('解析业务失败信封', () => {
    const parsed = parseServerResponse(
      JSON.stringify({
        type: 'server-response',
        rpcId: 'r1',
        result: { ok: false, error: { code: 'session-not-found', message: 'nope', details: { sessionId: 's1' } } },
      }),
    )
    expect(parsed).not.toBeNull()
  })

  it('拒绝畸形正文', () => {
    expect(parseServerResponse('not json')).toBeNull()
    expect(parseServerResponse(JSON.stringify({ type: 'client-request', rpcId: 'r', method: 'x', payload: {} }))).toBeNull()
    expect(parseServerResponse(JSON.stringify({ type: 'server-response', rpcId: 'r' }))).toBeNull()
    expect(parseServerResponse(JSON.stringify({ type: 'server-response', rpcId: 'r', result: { ok: 'yes' } }))).toBeNull()
    expect(parseServerResponse(JSON.stringify({ type: 'server-response', rpcId: 7, result: { ok: true } }))).toBeNull()
  })
})

describe('parseServerFrame', () => {
  it('解析服务端请求帧', () => {
    const parsed = parseServerFrame(
      JSON.stringify({ type: 'server-request', rpcId: 'r1', method: 'session/event', payload: { type: 'session/event' } }),
    )
    expect(parsed).not.toBeNull()
    expect(parsed?.method).toBe('session/event')
  })

  it('拒绝畸形与上行信封', () => {
    expect(parseServerFrame('oops')).toBeNull()
    expect(parseServerFrame(JSON.stringify({ type: 'server-response', rpcId: 'r', result: { ok: true } }))).toBeNull()
    expect(parseServerFrame(JSON.stringify({ type: 'server-request', method: 'x', payload: {} }))).toBeNull()
  })
})

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('callUnary', () => {
  it('发送 client-request 信封并返回业务值', async () => {
    const fetchFn = vi.fn((input: URL | RequestInfo, init?: RequestInit) => {
      expect(input).toBeInstanceOf(URL)
      expect((input as URL).href).toBe('http://127.0.0.1:3080/api/session.list')
      const body = JSON.parse(init?.body as string) as { type: string; rpcId: string; method: string; payload: unknown }
      expect(body.type).toBe('client-request')
      expect(body.method).toBe('session.list')
      return Promise.resolve(jsonResponse({ type: 'server-response', rpcId: body.rpcId, result: { ok: true, value: { items: [] } } }))
    })
    const value = await callUnary(BASE, 'session.list', {}, { deps: { fetchFn, randomId: () => 'r1' } })
    expect(value).toEqual({ items: [] })
  })

  it('业务失败抛 RpcFailure', async () => {
    const fetchFn = vi.fn(() =>
      Promise.resolve(jsonResponse({
        type: 'server-response',
        rpcId: 'r1',
        result: { ok: false, error: { code: 'session-not-found', message: 'no such session', details: { sessionId: 's1' } } },
      })),
    )
    await expect(
      callUnary(BASE, 'session.history', { sessionId: 's1' }, { deps: { fetchFn, randomId: () => 'r1' } }),
    ).rejects.toMatchObject({ code: 'session-not-found', details: { sessionId: 's1' } })
  })

  it('载体层错误抛 TransportError', async () => {
    await expect(
      callUnary(BASE, 'session.list', {}, { deps: { fetchFn: () => Promise.resolve(new Response('not found', { status: 404 })), randomId: () => 'r1' } }),
    ).rejects.toMatchObject({ status: 404 })
    await expect(
      callUnary(BASE, 'session.list', {}, { deps: { fetchFn: () => Promise.resolve(jsonResponse({ type: 'server-response', rpcId: 'other', result: { ok: true } })), randomId: () => 'r1' } }),
    ).rejects.toBeInstanceOf(TransportError)
  })
})

describe('sendResponse', () => {
  it('发送 client-response 信封到 /api/respond', async () => {
    const fetchFn = vi.fn(() => Promise.resolve(new Response('', { status: 200 })))
    await sendResponse(BASE, 'r1', { sessionId: 's1', approvalId: 'a1', outcome: 'allowed-once' }, { deps: { fetchFn } })
    const [url, init] = fetchFn.mock.calls[0] as unknown as [URL | RequestInfo, RequestInit | undefined]
    expect(url).toBeInstanceOf(URL)
    expect((url as URL).href).toBe('http://127.0.0.1:3080/api/respond')
    const body = JSON.parse(init?.body as string) as { type: string; rpcId: string; result: { ok: boolean; value: unknown } }
    expect(body.type).toBe('client-response')
    expect(body.rpcId).toBe('r1')
    expect(body.result.ok).toBe(true)
  })
})

/** 最小可用的 WebSocket 假件：手动触发事件以驱动生成器。 */
class FakeSocket {
  static CONNECTING = 0
  static OPEN = 1
  readyState = 0
  closed = false
  private listeners = new Map<string, Set<(event?: unknown) => void>>()

  addEventListener(type: string, listener: (event?: unknown) => void): void {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type)?.add(listener)
  }

  removeEventListener(type: string, listener: (event?: unknown) => void): void {
    this.listeners.get(type)?.delete(listener)
  }

  emit(type: string, event?: unknown): void {
    this.listeners.get(type)?.forEach((listener) => listener(event))
  }

  close(): void {
    if (!this.closed) {
      this.closed = true
      this.emit('close')
    }
  }
}

function collectFrames(socket: FakeSocket, deps: object): Promise<ServerRequest[]> {
  const generator = openEventStream(BASE, '/api/events.mux', { deps })
  const frames: ServerRequest[] = []
  return new Promise<ServerRequest[]>((resolve) => {
    void (async () => {
      try {
        for await (const frame of generator) frames.push(frame)
      } catch {
        // 错误路径由专门的测试覆盖；此处仅收集已产出的帧
      }
      resolve(frames)
    })()
  })
}

describe('openEventStream', () => {
  it('产出帧并在服务端关闭后正常结束', async () => {
    const socket = new FakeSocket()
    const promise = collectFrames(socket, {
      webSocketFactory: () => socket as unknown as WebSocket,
    })
    await Promise.resolve()
    socket.readyState = 1
    socket.emit('open')
    socket.emit('message', { data: JSON.stringify({ type: 'server-request', rpcId: 'r1', method: 'session/event', payload: { type: 'session/event', sessionId: 's1', event: {} } }) })
    socket.emit('message', { data: 'garbage' })
    socket.emit('message', { data: JSON.stringify({ type: 'server-request', rpcId: 'r2', method: 'session/jobs', payload: { type: 'session/jobs', sessionId: 's1', jobs: [] } }) })
    socket.close()
    const frames = await promise
    expect(frames.map((frame) => frame.method)).toEqual(['session/event', 'session/jobs'])
  })

  it('连接失败抛 TransportError', async () => {
    const socket = new FakeSocket()
    const run = async (): Promise<void> => {
      const generator = openEventStream(BASE, '/api/events.mux', {
        deps: { webSocketFactory: () => socket as unknown as WebSocket },
      })
      for await (const _frame of generator) void _frame
    }
    const promise = run()
    socket.emit('error')
    await expect(promise).rejects.toBeInstanceOf(TransportError)
  })
})
