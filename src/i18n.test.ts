import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LANGUAGE_STORAGE_KEY, readLanguage, saveLanguage, t } from './i18n'

describe('语言设置校验', () => {
  beforeEach(() => window.localStorage.clear())

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
})
