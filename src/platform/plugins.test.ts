import { describe, expect, it } from 'vitest'
import { validatePluginCatalog, validatePluginRequest } from './plugins'

const group = (extra: Record<string, unknown>) => ({
  plugins: [{
    id: 'test-plugin', file: 'config/plugins.yml', version: '1.0.0', enabled: true, protected: false,
    official: false, installed: true, readable: true, children: [], ...extra,
  }],
})

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
  it('导入接受包名、https 直链与带 ref 的 git 地址', () => {
    expect(validatePluginRequest({ operation: 'import', source: 'dsh-plugin-demo' }))
      .toEqual({ operation: 'import', source: 'dsh-plugin-demo' })
    expect(validatePluginRequest({ operation: 'import', source: '@scope/plugin' }))
      .toEqual({ operation: 'import', source: '@scope/plugin' })
    expect(validatePluginRequest({ operation: 'import', source: 'https://example.com/plugin.tgz' }))
      .toEqual({ operation: 'import', source: 'https://example.com/plugin.tgz' })
    expect(validatePluginRequest({ operation: 'import', source: 'git+https://github.com/u/r.git#v1.2.3' }))
      .toEqual({ operation: 'import', source: 'git+https://github.com/u/r.git#v1.2.3' })
    expect(validatePluginRequest({ operation: 'import', source: 'https://example.com/p.tgz', id: 'demo' }))
      .toEqual({ operation: 'import', source: 'https://example.com/p.tgz', id: 'demo' })
  })
  it('导入来源非法时一律拒绝，且不回显输入', () => {
    const bad = [
      '', '-x', 'plugin..name', 'https://example.com/%2e%2e/x', 'http://example.com/p.tgz',
      'git://example.com/u/r.git', 'ssh://git@example.com/u/r.git', 'file:///etc/passwd', 'data:text/plain,x',
      'https://example.com/a b.tgz', 'https://example.com/"x".tgz', 'https://example.com/<x>.tgz',
      'https://example.com/`x`.tgz', 'https://example.com/x\ntgz', 'https://user:pass@example.com/p.tgz',
      'git+https://github.com/u/r.git#', 'npm:foo', `https://example.com/${'a'.repeat(600)}.tgz`,
    ]
    for (const source of bad) {
      let message = ''
      try { validatePluginRequest({ operation: 'import', source }) } catch (error) { message = (error as Error).message }
      expect(message).toBe('插件数据格式无效')
      if (source.length > 0) expect(message).not.toContain(source)
    }
    expect(() => validatePluginRequest({ operation: 'import', source: 'https://example.com/p.tgz', id: '../evil' })).toThrow()
    expect(() => validatePluginRequest({ operation: 'import' })).toThrow()
  })
  it('回滚只接受合法包名', () => {
    expect(validatePluginRequest({ operation: 'rollback', id: 'test-plugin' }))
      .toEqual({ operation: 'rollback', id: 'test-plugin' })
    expect(() => validatePluginRequest({ operation: 'rollback', id: '../evil' })).toThrow()
    expect(() => validatePluginRequest({ operation: 'rollback' })).toThrow()
  })
  it('目录条目如实转出可回滚版本，缺键按 null 处理', () => {
    expect(validatePluginCatalog(group({ rollback: '0.9.0' })).plugins[0].rollback).toBe('0.9.0')
    expect(validatePluginCatalog(group({ rollback: null })).plugins[0].rollback).toBeNull()
    expect(validatePluginCatalog(group({})).plugins[0].rollback).toBeNull()
    expect(() => validatePluginCatalog(group({ rollback: '../../etc' }))).toThrow()
  })
})
