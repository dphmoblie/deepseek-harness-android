/**
 * 全局下行事件总线：维护 /api/events.mux 与 /api/events.host 两条只读
 * WebSocket，断线后指数退避重连，把解析后的帧分发给订阅者。
 * 单例，应用生命周期内保持连接。
 */

import { HOST_EVENTS_PATH, MUX_EVENTS_PATH, openEventStream, TransportError } from '../api/wire'
import type { HostFrame, MuxFrame, SessionId, ServerRequest } from '../api/types'
import { failureReason } from './errorDisplay'

/**
 * 分发的总线事件。rpcId 是外层 server-request 信封的回显令牌：
 * 审批/提问应答（/api/respond）必须用它路由回服务端 pending 表，
 * 帧 payload 自身并不携带该 id（question 的 id 即 envelope rpcId）。
 */
export type BusEvent = { stream: 'mux' | 'host'; rpcId: string; frame: MuxFrame | HostFrame }
export type BusListener = (event: BusEvent) => void

type StreamOpener = (
  baseUrl: string,
  path: typeof MUX_EVENTS_PATH | typeof HOST_EVENTS_PATH,
  options: { signal?: AbortSignal },
) => AsyncGenerator<ServerRequest>

const MAX_BACKOFF_MS = 30_000
const BASE_BACKOFF_MS = 1_000

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    signal.addEventListener(
      'abort',
      () => {
        clearTimeout(timer)
        resolve()
      },
      { once: true },
    )
  })
}

export class EventBus {
  private readonly listeners = new Set<BusListener>()
  private started = false
  /**
   * 每一代连接都必须有独立的 AbortController。
   * AbortSignal 一旦 aborted 永远不会恢复，复用旧 controller 会让 stop 后的
   * 第二次 start 立即退出，造成前端看似“只能连接一次”。
   */
  private stopController: AbortController | null = null

  constructor(
    private readonly openStream: StreamOpener = openEventStream,
    private readonly origin: () => string = () => window.location.origin,
  ) {}

  /** 幂等启动：重复调用只保证连接在跑，不重复开流。 */
  start(): void {
    if (this.started) return
    this.started = true
    const controller = new AbortController()
    this.stopController = controller
    void this.runStream('mux', MUX_EVENTS_PATH, controller)
    void this.runStream('host', HOST_EVENTS_PATH, controller)
  }

  stop(): void {
    this.started = false
    const controller = this.stopController
    this.stopController = null
    controller?.abort()
  }

  subscribe(listener: BusListener): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  private dispatch(stream: 'mux' | 'host', rpcId: string, frame: MuxFrame | HostFrame): void {
    this.listeners.forEach((listener) => {
      try {
        listener({ stream, rpcId, frame })
      } catch {
        // 订阅者异常不能影响其他订阅者，也不能把原始异常写入日志。
        console.warn('[harness-web] 事件订阅者异常，已隔离该回调')
      }
    })
  }

  private async runStream(
    stream: 'mux' | 'host',
    path: typeof MUX_EVENTS_PATH | typeof HOST_EVENTS_PATH,
    controller: AbortController,
  ): Promise<void> {
    const signal = controller.signal
    const origin = this.origin()
    let backoff = BASE_BACKOFF_MS
    let failureReported = false
    while (this.started && !signal.aborted) {
      try {
        for await (const envelope of this.openStream(origin, path, { signal })) {
          backoff = BASE_BACKOFF_MS
          failureReported = false
          this.dispatch(stream, envelope.rpcId, envelope.payload as MuxFrame | HostFrame)
        }
      } catch (error) {
        if (signal.aborted) return
        const reason = failureReason(error) ?? '事件流连接意外中断'
        if (!failureReported) {
          this.dispatch(stream, `frontend-${stream}-stream-error`, {
            type: 'stream/error',
            error: {
              code: error instanceof TransportError ? 'TRANSPORT_ERROR' : 'EVENT_STREAM_ERROR',
              message: reason,
              details: {},
            },
          })
          failureReported = true
        }
        console.warn(`[harness-web] ${stream} 事件流中断，${backoff}ms 后重连：${reason}`)
      }
      await delay(backoff, signal)
      backoff = Math.min(backoff * 2, MAX_BACKOFF_MS)
    }
  }
}

export const eventBus = new EventBus()

/** 便捷订阅：仅接收指定会话的 mux 帧（含外层 rpcId）。 */
export function subscribeSession(
  sessionId: SessionId,
  onFrame: (rpcId: string, frame: MuxFrame) => void,
): () => void {
  return eventBus.subscribe(({ stream, rpcId, frame }) => {
    if (stream !== 'mux') return
    const mux = frame as MuxFrame
    if ('sessionId' in mux && mux.sessionId === sessionId) onFrame(rpcId, mux)
  })
}
