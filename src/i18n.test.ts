import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LANGUAGE_STORAGE_KEY, readLanguage, resetLanguageSession, saveLanguage, t } from './i18n'

describe('语言设置校验', () => {
  beforeEach(() => {
    window.localStorage.clear()
    resetLanguageSession()
  })

  it('拒绝无效或过长的语言值', () => {
    for (const value of ['fr', '../en', '<script>', 'en'.repeat(2000)]) {
      expect(saveLanguage(value)).toBe(false)
      window.localStorage.setItem(LANGUAGE_STORAGE_KEY, value)
      expect(readLanguage()).toBeNull()
    }
  })

  it('存储不可用时返回保存失败', () => {
    const storage = vi.spyOn(window.Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('存储不可用') })
    try { expect(saveLanguage('en')).toBe(false) } finally { storage.mockRestore() }
  })

  it('只翻译已知文案，插值保持数据本身', () => {
    expect(saveLanguage('en')).toBe(true)
    expect(t('运行时 {0}', 'v1')).toBe('Runtime v1')
    expect(t('用户输入')).toBe('用户输入')
    expect(t('constructor')).toBe('constructor')
    expect(saveLanguage('zh-CN')).toBe(true)
    expect(t('运行时 {0}', 'v1')).toBe('运行时 v1')
  })

  it('新增语言各自查自己的词典，缺键时回退英文再回退简体原文', () => {
    expect(saveLanguage('zh-TW')).toBe(true)
    expect(t('界面语言')).toBe('介面語言')
    // 词典没有的键：任何语言都保持原文，用户输入与动态数据不会被改写。
    expect(t('用户输入')).toBe('用户输入')
    expect(t('constructor')).toBe('constructor')
  })

  /**
   * 译文与原键完全相同的条目，绝大多数是中日/繁简同形的正常情况（「停止」「用途：」），
   * 但**含插值的格式化文案**不该照抄：中文的空格、助词和语序在日文里不成立。
   * 这里只钉住已确认会渲染出别扭中文格式的那几条。
   */
  it('日文的相对时间不用中文空格与量词衔接', () => {
    expect(saveLanguage('ja')).toBe(true)
    expect(t('{0} 分 {1} 秒前', 1, 5)).toBe('1分5秒前')
    expect(t('{0} 秒前', 5)).toBe('5秒前')
  })
})
