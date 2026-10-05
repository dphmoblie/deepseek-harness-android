import { describe, expect, it } from 'vitest'
import type { InstalledApplication } from '../platform/types'
import { MAX_QUERY_CHARS, isVisible, normalizeSearchQuery, orderForDisplay } from './applicationSearchQuery'

function app(label: string, packageName: string, extra: Partial<InstalledApplication> = {}): InstalledApplication {
  return { label, packageName, system: false, selectable: true, ...extra }
}

describe('应用选择器的纯查询规则', () => {
  it('搜索词规范化：全角空格归一、折叠空白、去首尾、抹掉看不见的控制符', () => {
    const cases: Array<[string, string]> = [
      ['', ''],
      ['   ', ''],
      ['微信', '微信'],
      ['  wx\u3000支付  ', 'wx 支付'],
      // 制表符/换行是空白不是需要删掉的控制符：删掉会把两个词粘成一个（回归点）。
      ['wx\t\t支付', 'wx 支付'],
      ['wx\n支付', 'wx 支付'],
      ['wx\r\n支付', 'wx 支付'],
      ['\u200bwx\ufeff', 'wx'],
      ['\u202a微信\u202c', '微信'],
      ['a\u0000b', 'ab'],
      ['a\u0007b', 'ab'],
      ['we-chat', 'we-chat'],
      ['we‑chat', 'we‑chat'],
    ]
    cases.forEach(([input, expected]) => expect(normalizeSearchQuery(input)).toBe(expected))
    // 超长输入截到桥允许的上限，剩下的部分不该让桥直接拒绝。
    expect(normalizeSearchQuery('a'.repeat(300))).toHaveLength(MAX_QUERY_CHARS)
  })

  it('已勾选前置，其余保持原生顺序（稳定排序）', () => {
    const list = [app('Alipay', 'com.eg.alipay'), app('微信', 'com.tencent.mm'), app('笔记', 'com.example.notes')]
    expect(orderForDisplay(list, ['com.example.notes']).map(item => item.packageName))
      .toEqual(['com.example.notes', 'com.eg.alipay', 'com.tencent.mm'])
    // 多个勾选时保持它们之间的相对顺序，未勾选部分同样保持相对顺序。
    expect(orderForDisplay(list, ['com.tencent.mm', 'com.example.notes']).map(item => item.packageName))
      .toEqual(['com.tencent.mm', 'com.example.notes', 'com.eg.alipay'])
    // 不修改入参。
    expect(list.map(item => item.packageName)).toEqual(['com.eg.alipay', 'com.tencent.mm', 'com.example.notes'])
    expect(orderForDisplay([], ['com.example.notes'])).toEqual([])
  })

  it('两个可见性开关的判定：默认全可见，只看可自动化 / 隐藏系统组件各自生效', () => {
    const user = app('Alipay', 'com.eg.alipay')
    const systemSelectable = app('系统日历', 'com.android.calendar', { system: true })
    const systemReserved = app('计算器', 'com.android.calculator2', { system: true, selectable: false })
    const noFilter = { automationOnly: false, hideSystem: false }
    expect([user, systemSelectable, systemReserved].filter(item => isVisible(item, noFilter))).toHaveLength(3)
    expect(isVisible(systemReserved, { automationOnly: true, hideSystem: false })).toBe(false)
    expect(isVisible(systemSelectable, { automationOnly: true, hideSystem: false })).toBe(true)
    expect(isVisible(systemSelectable, { automationOnly: false, hideSystem: true })).toBe(false)
    // 用户自己装的应用永远可见。
    expect(isVisible(user, { automationOnly: true, hideSystem: true })).toBe(true)
  })
})
