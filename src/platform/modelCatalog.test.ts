import { describe, expect, it } from 'vitest'
import { CATALOG_DEFAULT_CONTEXT_WINDOW, CATALOG_DEFAULT_MAX_TOKENS, firstCatalogModelId, isValidModelId, mergeCatalogModels } from './modelCatalog'
import { MAX_CUSTOM_MODELS } from './customProviders'
import type { CustomProviderModel } from './types'

const model = (id: string, changes: Partial<CustomProviderModel> = {}): CustomProviderModel => ({
  id,
  name: id.toUpperCase(),
  contextWindow: 4096,
  maxTokens: 1024,
  ...changes,
})

describe('model catalog helpers', () => {
  it('picks the first upstream entry and treats an empty list as "no change"', () => {
    expect(firstCatalogModelId([{ id: 'first', name: 'First' }, { id: 'second', name: 'Second' }])).toBe('first')
    expect(firstCatalogModelId([])).toBe('')
  })

  it.each(['gpt-4o', 'org/model', 'models/gemini-2.5-pro', 'claude-3-5-sonnet:beta', 'a.b_c+d'])(
    'accepts model id %s',
    id => expect(isValidModelId(id)).toBe(true),
  )

  it('trims surrounding whitespace before judging', () => {
    expect(isValidModelId('  gpt-4o  ')).toBe(true)
  })

  it.each(['', '-dash', 'has space', 'tab\tchar'])('rejects model id %s', id => {
    expect(isValidModelId(id)).toBe(false)
  })

  it('adds every upstream entry to an empty model list with the shared defaults', () => {
    expect(mergeCatalogModels([], [{ id: 'alpha', name: 'Alpha' }, { id: 'beta', name: '' }])).toEqual([
      { id: 'alpha', name: 'Alpha', contextWindow: CATALOG_DEFAULT_CONTEXT_WINDOW, maxTokens: CATALOG_DEFAULT_MAX_TOKENS },
      // 上游没给显示名时用 id 兜底，不留一个空白的名称输入框。
      { id: 'beta', name: 'beta', contextWindow: CATALOG_DEFAULT_CONTEXT_WINDOW, maxTokens: CATALOG_DEFAULT_MAX_TOKENS },
    ])
  })

  it('moves the first upstream entry to the front and keeps user-edited fields intact', () => {
    const current = [model('kept'), model('promoted', { contextWindow: 200000, maxTokens: 64000 })]
    const merged = mergeCatalogModels(current, [{ id: 'promoted', name: 'Promoted' }, { id: 'newcomer', name: 'Newcomer' }])
    expect(merged.map(item => item.id)).toEqual(['promoted', 'kept', 'newcomer'])
    // 用户改过的上下文窗口不能被一次「获取模型」改回默认值。
    expect(merged[0]).toMatchObject({ contextWindow: 200000, maxTokens: 64000 })
    expect(merged[1]).toEqual(model('kept'))
  })

  it('deduplicates upstream entries that are already configured', () => {
    const merged = mergeCatalogModels([model('kept')], [{ id: 'kept', name: 'Kept' }, { id: 'kept', name: 'Kept again' }])
    expect(merged.map(item => item.id)).toEqual(['kept'])
    expect(merged).toHaveLength(1)
  })

  it('never exceeds the model limit', () => {
    const current = Array.from({ length: MAX_CUSTOM_MODELS }, (_, index) => model(`local-${index}`))
    const merged = mergeCatalogModels(current, [{ id: 'upstream-first', name: 'First' }, { id: 'upstream-second', name: 'Second' }])
    expect(merged).toHaveLength(MAX_CUSTOM_MODELS)
    // 首项置顶会挤掉排在最后的那一条，而不是让表单长出一份非法配置。
    expect(merged[0].id).toBe('upstream-first')
    expect(merged.some(item => item.id === `local-${MAX_CUSTOM_MODELS - 1}`)).toBe(false)
  })

  it('returns the current list unchanged when upstream sends nothing', () => {
    const current = [model('kept')]
    expect(mergeCatalogModels(current, [])).toBe(current)
  })
})
