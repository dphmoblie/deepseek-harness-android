import { describe, expect, it } from 'vitest'
import { applyChunk, createDraft, draftActive, draftContent } from './draft'
import type { StreamChunk } from '../api/types'

describe('applyChunk 流式草稿组装', () => {
  it('按 block 槽位累积 text/reasoning 并定稿', () => {
    let draft = createDraft(1, 2)
    draft = applyChunk(draft, { type: 'block-start', index: 0, blockType: 'text' })
    draft = applyChunk(draft, { type: 'text-delta', index: 0, text: '你好' })
    draft = applyChunk(draft, { type: 'text-delta', index: 0, text: '，世界' })
    draft = applyChunk(draft, { type: 'block-start', index: 1, blockType: 'reasoning' })
    draft = applyChunk(draft, { type: 'reasoning-delta', index: 1, text: '思考中' })
    draft = applyChunk(draft, { type: 'block-end', index: 0, block: { type: 'text', text: '你好，世界' } })

    const content = draftContent(draft)
    expect(content).toHaveLength(2)
    expect(content[0]).toEqual({ type: 'text', text: '你好，世界' })
    expect(content[1]).toEqual({ type: 'reasoning', text: '思考中' })
    // 未 finish → 仍在流式输出
    expect(draftActive(draft)).toBe(true)
  })

  it('tool-call-delta 累积参数并保留 id/name', () => {
    let draft = createDraft(1, 1)
    draft = applyChunk(draft, { type: 'block-start', index: 0, blockType: 'tool-call' })
    draft = applyChunk(draft, { type: 'tool-call-delta', index: 0, id: 'c1', name: 'read', argumentsDelta: '{"path"' })
    draft = applyChunk(draft, { type: 'tool-call-delta', index: 0, id: 'c1', argumentsDelta: ':"/etc"}' })

    const [block] = draftContent(draft)
    expect(block).toMatchObject({ type: 'tool-call', id: 'c1', name: 'read', arguments: '{"path":"/etc"}' })
  })

  it('usage 与 finish 记录到草稿', () => {
    let draft = createDraft(3, 4)
    draft = applyChunk(draft, { type: 'usage', usage: { inputTokens: 10, outputTokens: 5 } })
    draft = applyChunk(draft, { type: 'finish', reason: { kind: 'stop' } })

    expect(draft.usage).toEqual({ inputTokens: 10, outputTokens: 5 })
    expect(draft.finish).toEqual({ kind: 'stop' })
    expect(draftActive(draft)).toBe(false)
  })

  it('未知槽位的 delta 安全忽略', () => {
    let draft = createDraft(1, 1)
    draft = applyChunk(draft, { type: 'text-delta', index: 7, text: '孤儿增量' })
    expect(draftContent(draft)).toEqual([])
  })

  it('忽略未知分块类型', () => {
    let draft = createDraft(1, 1)
    draft = applyChunk(draft, { type: 'unknown-chunk' } as unknown as StreamChunk)
    expect(draftContent(draft)).toEqual([])
  })
})
