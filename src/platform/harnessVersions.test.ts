import { describe, expect, it } from 'vitest'
import { harnessVersionRequest, validateHarnessVersions, validateHarnessReleases } from './harnessVersions'
describe('Harness 版本桥接校验', () => {
  it('仅选择操作允许内置槽位，拒绝来源与命令参数', () => {
    expect(harnessVersionRequest('select', 'bundled')).toEqual({ operation: 'select', version: 'bundled' })
    for (const value of ['bundled', '../x', 'latest', '0.2.1;id', 'https://example.com/pkg']) expect(() => harnessVersionRequest('install', value)).toThrow()
  })
  it('拒绝默认版本缺失、重复及错误的激活状态', () => {
    const state = { active: '0.2.1-rc.2', bundledVersion: '0.2.0-rc.2', installed: [] }
    expect(() => validateHarnessVersions(state)).toThrow('默认版本缺失')
    const entry = { version: '0.2.1-rc.2', active: true, compatible: true, bytes: 1 }
    expect(validateHarnessVersions({ ...state, installed: [entry] }).active).toBe(entry.version)
    expect(() => validateHarnessVersions({ ...state, installed: [entry, entry] })).toThrow('重复')
    expect(() => validateHarnessVersions({ ...state, installed: [{ ...entry, active: false }] })).toThrow()
  })
  it('目录保留历史版本与受控发布标签，拒绝重复版本', () => {
    const entry = { version: '0.2.1-rc.2', tags: ['next'] }
    expect(validateHarnessReleases({ entries: [entry] }).entries).toEqual([entry])
    expect(() => validateHarnessReleases({ entries: [entry, entry] })).toThrow('重复')
    expect(() => validateHarnessReleases({ entries: [{ ...entry, tags: ['<script>'] }] })).toThrow()
  })
})
