import { describe, expect, it } from 'vitest'
import { parseGoalProjection, parseTodoProjection } from './projections'

describe('goal projection parser', () => {
  it('accepts wrapped and direct snapshots', () => {
    expect(parseGoalProjection({ goal: { id: 'g1', revision: 2, objective: '发布应用', phase: 'active', maxGoalRounds: 8 } }))
      .toMatchObject({ id: 'g1', revision: 2, objective: '发布应用', phase: 'active' })
    expect(parseGoalProjection({ id: 'g2', revision: 1, objective: '验证', phase: 'paused' }))
      .toMatchObject({ id: 'g2', phase: 'paused' })
  })

  it('preserves explicit absence and rejects malformed values', () => {
    expect(parseGoalProjection({ goal: null })).toBeNull()
    expect(parseGoalProjection(null)).toBeNull()
    expect(parseGoalProjection({ goal: { id: '', revision: -1, objective: '', phase: 'active' } })).toBeUndefined()
    expect(parseGoalProjection('<script>')).toBeUndefined()
  })
})

describe('todo projection parser', () => {
  it('accepts a bounded whole snapshot', () => {
    expect(parseTodoProjection([
      { content: '检查类型', status: 'completed' },
      { content: '运行测试', status: 'in_progress' },
    ])).toEqual([
      { content: '检查类型', status: 'completed' },
      { content: '运行测试', status: 'in_progress' },
    ])
    expect(parseTodoProjection(null)).toEqual([])
  })

  it('rejects malformed, duplicate, or control-character content', () => {
    expect(parseTodoProjection([{ content: 'x', status: 'unknown' }])).toBeUndefined()
    expect(parseTodoProjection([{ content: 'x\u0000', status: 'pending' }])).toBeUndefined()
    expect(parseTodoProjection([
      { content: 'x', status: 'pending' },
      { content: 'x', status: 'completed' },
    ])).toBeUndefined()
  })
})
