import { describe, expect, it } from 'vitest'
import { foldEvent, foldHistory } from './fold'
import type { HistoryEntry, SessionEvent } from '../api/types'

function event(type: string, seq: number, data: Record<string, unknown>): SessionEvent {
  return { type, seq, time: 0, data }
}

const userMessage = {
  id: 'm1',
  role: 'user',
  content: [{ type: 'text', text: '你好' }],
  source: { kind: 'user' },
}

describe('foldEvent 事件折叠', () => {
  it('折叠用户消息', () => {
    const folded = foldEvent(event('user/message', 1, { message: userMessage }))
    expect(folded).toMatchObject({ kind: 'user', seq: 1 })
  })

  it('折叠助手消息与用量', () => {
    const message = {
      id: 'm2',
      role: 'assistant',
      content: [{ type: 'text', text: '回复' }],
      source: { kind: 'model', provider: 'p', model: 'm' },
    }
    const folded = foldEvent(event('assistant/message', 2, { turn: 1, step: 1, message, usage: { inputTokens: 3, outputTokens: 4 } }))
    expect(folded).toMatchObject({ kind: 'assistant', seq: 2, usage: { outputTokens: 4 } })
  })

  it('折叠工具调用与结果', () => {
    const call = foldEvent(event('tool/call', 3, { turn: 1, step: 1, callId: 'c1', name: 'read', arguments: '{}' }))
    expect(call).toMatchObject({ kind: 'tool-call', callId: 'c1', name: 'read' })

    const result = foldEvent(event('tool/result', 4, {
      turn: 1,
      step: 1,
      callId: 'c1',
      message: { id: 'm3', role: 'user', content: [], source: { kind: 'tool', callId: 'c1' } },
    }))
    expect(result).toMatchObject({ kind: 'tool-result', callId: 'c1', isError: false })
  })

  it('turn/end 错误折叠为通知', () => {
    expect(foldEvent(event('turn/end', 5, { turn: 1, reason: { kind: 'completed' } }))).toBeNull()
    expect(foldEvent(event('turn/end', 6, { turn: 1, reason: { kind: 'error', error: { message: 'x' } } })))
      .toMatchObject({ kind: 'notice', text: '本轮因错误终止' })
    expect(foldEvent(event('turn/end', 7, { turn: 1, reason: { kind: 'aborted', reason: { kind: 'user' } } })))
      .toMatchObject({ kind: 'notice', text: '本轮已中止' })
  })

  it('流分块与边界事件不上屏', () => {
    expect(foldEvent(event('assistant/chunk', 8, { turn: 1, step: 1, chunk: { type: 'text-delta', index: 0, text: 'x' } }))).toBeNull()
    expect(foldEvent(event('turn/start', 9, { turn: 1 }))).toBeNull()
    expect(foldEvent(event('todo/write', 10, { todos: [] }))).toBeNull()
  })
})

describe('foldHistory 批量折叠', () => {
  it('按序折叠并跳过不可渲染事件', () => {
    const entries: HistoryEntry[] = [
      { event: event('user/message', 1, { message: userMessage }) },
      { event: event('assistant/chunk', 2, { turn: 1, step: 1, chunk: { type: 'text-delta', index: 0, text: 'x' } }) },
      { event: event('assistant/message', 3, { turn: 1, step: 1, message: { ...userMessage, id: 'm2', role: 'assistant' } }) },
    ]
    const folded = foldHistory(entries)
    expect(folded.map((item) => item.kind)).toEqual(['user', 'assistant'])
    expect(folded.map((item) => item.seq)).toEqual([1, 3])
  })
})
