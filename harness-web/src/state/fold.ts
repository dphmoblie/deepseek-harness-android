/**
 * 会话事件 → 可渲染条目的折叠。
 * 聊天视图只渲染派生消息条目；assistant/chunk 由草稿机制流式组装，
 * 边界事件（turn/step 起止、todo 快照）不直接上屏。
 */

import type { FinishReason, HistoryEntry, Message, SessionEvent, TokenUsage, ToolEventView } from '../api/types'
import { failureReason, finishFailureReason, turnEndFailureNotice } from './errorDisplay'

export type ChatEntry =
  | { kind: 'user'; seq: number; time: number; message: Message }
  | { kind: 'assistant'; seq: number; time: number; message: Message; usage?: TokenUsage }
  | { kind: 'tool-call'; seq: number; time: number; callId: string; name: string; arguments: string }
  | { kind: 'tool-result'; seq: number; time: number; callId: string; isError: boolean; message: Message; view?: ToolEventView }
  | { kind: 'notice'; seq: number; time: number; text: string }

/** 把一条历史事件折叠成渲染条目；不可渲染的事件返回 null。 */
export function foldHistoryEntry(entry: HistoryEntry): ChatEntry | null {
  return foldEvent(entry.event, entry.view)
}

/** 把一条增量事件折叠成渲染条目。 */
export function foldEvent(event: SessionEvent, view?: ToolEventView, finishReason?: FinishReason): ChatEntry | null {
  const data = event.data
  switch (event.type) {
    case 'user/message': {
      const message = data.message
      if (typeof message !== 'object' || message === null) return null
      return { kind: 'user', seq: event.seq, time: event.time, message: message as Message }
    }
    case 'assistant/message': {
      const message = data.message
      if (typeof message !== 'object' || message === null) return null
      return {
        kind: 'assistant',
        seq: event.seq,
        time: event.time,
        message: message as Message,
        usage: typeof data.usage === 'object' && data.usage !== null ? (data.usage as TokenUsage) : undefined,
      }
    }
    case 'tool/call': {
      const callId = data.callId
      const name = data.name
      if (typeof callId !== 'string' || typeof name !== 'string') return null
      return { kind: 'tool-call', seq: event.seq, time: event.time, callId, name, arguments: typeof data.arguments === 'string' ? data.arguments : '' }
    }
    case 'tool/result': {
      const callId = data.callId
      const message = data.message
      if (typeof callId !== 'string' || typeof message !== 'object' || message === null) return null
      return {
        kind: 'tool-result',
        seq: event.seq,
        time: event.time,
        callId,
        isError: data.error !== undefined && data.error !== null,
        message: message as Message,
        view,
      }
    }
    case 'turn/end': {
      const reason = data.reason as { kind?: string } | undefined
      if (reason === undefined) return null
      if (reason.kind === 'error' || reason.kind === 'aborted') {
        const abortReason = reason.kind === 'aborted'
          ? (reason as Record<string, unknown>).reason as Record<string, unknown> | undefined
          : undefined
        const userCancelled = abortReason?.kind === 'user'
        const abortedFailure = reason.kind === 'aborted' && !userCancelled
          ? failureReason((reason as Record<string, unknown>).failure) ?? finishFailureReason(finishReason)
          : null
        return {
          kind: 'notice',
          seq: event.seq,
          time: event.time,
          text: reason.kind === 'error'
            ? turnEndFailureNotice(event, finishReason) ?? '本轮运行失败：未提供详细原因'
            : abortedFailure === null ? '本轮已中止' : `本轮运行失败：${abortedFailure}`,
        }
      }
      if (reason.kind === 'interrupted') {
        return { kind: 'notice', seq: event.seq, time: event.time, text: '上轮因中断未能完成' }
      }
      return null
    }
    default:
      return null
  }
}

/** 把一批历史事件折叠为条目（忽略 chunk 等不可渲染事件）。 */
export function foldHistory(entries: HistoryEntry[]): ChatEntry[] {
  const result: ChatEntry[] = []
  let finishReason: FinishReason | undefined
  for (const entry of entries) {
    const event = entry.event
    if (event.type === 'turn/start') finishReason = undefined
    if (event.type === 'assistant/chunk') {
      const chunk = event.data.chunk as { type?: unknown; reason?: unknown } | undefined
      if (chunk?.type === 'finish' && typeof chunk.reason === 'object' && chunk.reason !== null) {
        finishReason = chunk.reason as FinishReason
      }
    }
    const folded = foldEvent(event, entry.view, finishReason)
    if (folded !== null) result.push(folded)
    if (event.type === 'turn/end') finishReason = undefined
  }
  return result
}
