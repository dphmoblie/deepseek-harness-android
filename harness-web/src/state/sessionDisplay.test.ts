import { describe, expect, it } from 'vitest'
import type { SessionSummary } from '../api/types'
import { selectRecentActiveSession, titleFromProjections } from './sessionDisplay'

function summary(sessionId: string, updatedAt: number): SessionSummary {
  return { sessionId, updatedAt, running: false, blank: false }
}

describe('session display state', () => {
  it('恢复最近的未归档会话', () => {
    const sessions = [summary('older', 10), summary('archived-newest', 30), summary('latest', 20)]
    expect(selectRecentActiveSession(sessions, ['archived-newest'])).toBe('latest')
    expect(selectRecentActiveSession(sessions, ['older', 'latest', 'archived-newest'])).toBeNull()
  })

  it('从兼容的投影格式提取并约束标题', () => {
    expect(titleFromProjections({ asOfSeq: 1, values: { title: '  修复\n构建  ' } })).toBe('修复 构建')
    expect(titleFromProjections({ asOfSeq: 1, values: { 'session.title': { value: '移动端会话' } } })).toBe('移动端会话')
    expect(titleFromProjections({ asOfSeq: 1, values: { title: 42 } })).toBeNull()
  })
})
