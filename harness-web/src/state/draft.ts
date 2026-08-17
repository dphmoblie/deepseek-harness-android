/**
 * 流式草稿组装：把 assistant/chunk 事件序列折叠成一条正在生成的助手消息。
 * 纯函数设计便于单测；每次应用返回新对象（blocks 为新 Map），保证 React 重渲染。
 */

import type { ContentBlock, FinishReason, StreamChunk, TokenUsage } from '../api/types'

/** 一个正在组装的输出块槽位。 */
export type DraftBlock = {
  index: number
  blockType: string
  /** text-delta 累积的可见文本。 */
  text: string
  /** reasoning-delta 累积的思考文本。 */
  reasoning: string
  toolId?: string
  toolName?: string
  /** tool-call-delta 累积的原始参数 JSON 片段。 */
  toolArguments: string
  /** block-end 已到达（块定稿）。 */
  done: boolean
  /** block-end 携带的定稿内容块。 */
  final?: ContentBlock
}

export type AssistantDraft = {
  turn: number
  step: number
  blocks: Map<number, DraftBlock>
  usage?: TokenUsage
  finish?: FinishReason
}

export function createDraft(turn: number, step: number): AssistantDraft {
  return { turn, step, blocks: new Map() }
}

/** 应用一个流分块；未知类型（插件扩展）安全忽略。 */
export function applyChunk(draft: AssistantDraft, chunk: StreamChunk): AssistantDraft {
  switch (chunk.type) {
    case 'block-start': {
      const blocks = new Map(draft.blocks)
      blocks.set(chunk.index, {
        index: chunk.index,
        blockType: chunk.blockType,
        text: '',
        reasoning: '',
        toolArguments: '',
        done: false,
      })
      return { ...draft, blocks }
    }
    case 'text-delta':
    case 'reasoning-delta':
    case 'tool-call-delta': {
      const existing = draft.blocks.get(chunk.index)
      if (existing === undefined) return draft
      const blocks = new Map(draft.blocks)
      blocks.set(chunk.index, applyDelta(existing, chunk))
      return { ...draft, blocks }
    }
    case 'block-end': {
      const existing = draft.blocks.get(chunk.index)
      if (existing === undefined) return draft
      const blocks = new Map(draft.blocks)
      blocks.set(chunk.index, { ...existing, done: true, final: chunk.block })
      return { ...draft, blocks }
    }
    case 'usage':
      return { ...draft, usage: chunk.usage }
    case 'finish':
      return { ...draft, finish: chunk.reason }
    default:
      return draft
  }
}

function applyDelta(block: DraftBlock, chunk: Extract<StreamChunk, { type: 'text-delta' | 'reasoning-delta' | 'tool-call-delta' }>): DraftBlock {
  switch (chunk.type) {
    case 'text-delta':
      return { ...block, text: block.text + chunk.text }
    case 'reasoning-delta':
      return { ...block, reasoning: block.reasoning + chunk.text }
    case 'tool-call-delta':
      return {
        ...block,
        toolId: chunk.id ?? block.toolId,
        toolName: chunk.name ?? block.toolName,
        toolArguments: block.toolArguments + chunk.argumentsDelta,
      }
  }
}

/** 把草稿折叠成可渲染的内容块列表（含未完成的块）。 */
export function draftContent(draft: AssistantDraft): ContentBlock[] {
  return [...draft.blocks.values()]
    .sort((a, b) => a.index - b.index)
    .map((block) => {
      if (block.final !== undefined) return block.final
      switch (block.blockType) {
        case 'text':
          return { type: 'text', text: block.text }
        case 'reasoning':
          return { type: 'reasoning', text: block.reasoning }
        case 'tool-call':
          return {
            type: 'tool-call',
            id: block.toolId ?? '',
            name: block.toolName ?? '',
            arguments: block.toolArguments,
          }
        default:
          return { type: 'text', text: block.text }
      }
    })
}

/** 草稿是否仍在接收流式输出。 */
export function draftActive(draft: AssistantDraft): boolean {
  return draft.finish === undefined
}
