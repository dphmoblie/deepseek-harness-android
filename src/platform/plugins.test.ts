import { describe, expect, it } from 'vitest'
import { validatePluginCatalog, validatePluginRequest } from './plugins'

describe('插件桥接校验', () => {
  it('拒绝路径穿越和非法操作标识', () => {
    expect(() => validatePluginRequest({ operation: 'enable', id: '../evil', enabled: false })).toThrow()
    expect(() => validatePluginRequest({ operation: 'child', id: 'valid', childId: '$(command)', enabled: false })).toThrow()
  })
  it('拒绝超量列表和不完整原生返回值', () => {
    expect(() => validatePluginCatalog({ plugins: Array.from({ length: 33 }, () => ({})) })).toThrow()
    expect(() => validatePluginCatalog({ plugins: [{ id: 'valid', file: '../../private.yml' }] })).toThrow()
    expect(validatePluginCatalog({ plugins: [] })).toEqual({ plugins: [] })
  })
})
