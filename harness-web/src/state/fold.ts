/**
 * 会话事件 → 可渲染条目的折叠。
 * 聊天视图只渲染派生消息条目；assistant/chunk 由草稿机制流式组装，
 * 边界事件（turn/step 起止、todo 快照）不直接上屏。
 */

import type { HistoryEntry, Message, SessionEvent, TokenUsage, ToolEventView } from '../api/types'

export type ChatEntry =
  | { kind: 'user'; seq: number; message: Message }
  | { kind: 'assistant'; seq: number; message: Message; usage?: TokenUsage }
  | { kind: 'tool-call'; seq: number; callId: string; name: string; arguments: string }
  | { kind: 'tool-result'; seq: number; callId: string; isError: boolean; message: Message; view?: ToolEventView }
  | { kind: 'notice'; seq: number; text: string }

/** 把一条历史事件折叠成渲染条目；不可渲染的事件返回 null。 */
export function foldHistoryEntry(entry: HistoryEntry): ChatEntry | null {
  return foldEvent(entry.event, entry.view)
}

/** 把一条增量事件折叠成渲染条目。 */
export function foldEvent(event: SessionEvent, view?: ToolEventView): ChatEntry | null {
  const data = event.data
  switch (event.type) {
    case 'user/message': {
      const message = data.message
      if (typeof message !== 'object' || message === null) return null
      return { kind: 'user', seq: event.seq, message: message as Message }
    }
    case 'assistant/message': {
      const message = data.message
      if (typeof message !== 'object' || message === null) return null
      return {
        kind: 'assistant',
        seq: event.seq,
        message: message as Message,
        usage: typeof data.usage === 'object' && data.usage !== null ? (data.usage as TokenUsage) : undefined,
      }
    }
    case 'tool/call': {
      const callId = data.callId
      const name = data.name
      if (typeof callId !== 'string' || typeof name !== 'string') return null
      return { kind: 'tool-call', seq: event.seq, callId, name, arguments: typeof data.arguments === 'string' ? data.arguments : '' }
    }
    case 'tool/result': {
      const callId = data.callId
      const message = data.message
      if (typeof callId !== 'string' || typeof message !== 'object' || message === null) return null
      return {
        kind: 'tool-result',
        seq: event.seq,
        callId,
        isError: data.error !== undefined && data.error !== null,
        message: message as Message,
        view,
      }
    }
    case 'turn/end': {
      const reason = data.reason as { kind?: string } | undefined
      if (reason === undefined) return null
      if (reason.kind === 'error') {
        // 调试辅助：把错误 reason 的详情直接展示（dsh 会话事件携带 code/message/details）
        const detail = JSON.stringify(reason)
        const text = detail !== undefined && detail !== '{"kind":"error"}'
          ? '本轮因错误终止：' + detail.slice(0, 600)
          : '本轮因错误终止'
        return { kind: 'notice', seq: event.seq, text }
      }
      if (reason.kind === 'aborted') {
        return { kind: 'notice', seq: event.seq, text: '本轮已中止' }
      }
      if (reason.kind === 'interrupted') {
        return { kind: 'notice', seq: event.seq, text: '上轮因中断未能完成' }
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
  for (const entry of entries) {
    const folded = foldHistoryEntry(entry)
    if (folded !== null) result.push(folded)
  }
  return result
}
