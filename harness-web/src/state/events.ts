/**
 * 全局下行事件总线：维护 /api/events.mux 与 /api/events.host 两条只读
 * WebSocket，断线后指数退避重连，把解析后的帧分发给订阅者。
 * 单例，应用生命周期内保持连接。
 */

import { HOST_EVENTS_PATH, MUX_EVENTS_PATH, openEventStream, TransportError } from '../api/wire'
import type { HostFrame, MuxFrame, SessionId } from '../api/types'

/**
 * 分发的总线事件。rpcId 是外层 server-request 信封的回显令牌：
 * 审批/提问应答（/api/respond）必须用它路由回服务端 pending 表，
 * 帧 payload 自身并不携带该 id（question 的 id 即 envelope rpcId）。
 */
export type BusEvent = { stream: 'mux' | 'host'; rpcId: string; frame: MuxFrame | HostFrame }
export type BusListener = (event: BusEvent) => void

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
  private readonly stopController = new AbortController()

  /** 幂等启动：重复调用只保证连接在跑，不重复开流。 */
  start(): void {
    if (this.started) return
    this.started = true
    void this.runStream('mux', MUX_EVENTS_PATH)
    void this.runStream('host', HOST_EVENTS_PATH)
  }

  stop(): void {
    this.started = false
    this.stopController.abort()
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
      } catch (error) {
        // 订阅者异常不能影响其他订阅者；静默丢弃并记录
        console.warn('[harness-web] 事件订阅者异常', error)
      }
    })
  }

  private async runStream(stream: 'mux' | 'host', path: string): Promise<void> {
    const origin = window.location.origin
    let backoff = BASE_BACKOFF_MS
    while (this.started && !this.stopController.signal.aborted) {
      try {
        for await (const envelope of openEventStream(origin, path as typeof MUX_EVENTS_PATH, {
          signal: this.stopController.signal,
        })) {
          backoff = BASE_BACKOFF_MS
          this.dispatch(stream, envelope.rpcId, envelope.payload as MuxFrame | HostFrame)
        }
      } catch (error) {
        if (this.stopController.signal.aborted) return
        if (error instanceof TransportError) {
          console.warn(`[harness-web] ${stream} 事件流中断，${backoff}ms 后重连`)
        } else {
          console.warn('[harness-web] 事件流异常', error)
        }
      }
      await delay(backoff, this.stopController.signal)
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
