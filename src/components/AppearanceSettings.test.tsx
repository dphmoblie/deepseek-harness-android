import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { AppearanceSettings } from './AppearanceSettings'
import { saveLanguage } from '../i18n'
import { THEME_COLORS, THEME_STORAGE_KEY } from '../theme'

type MediaListener = (event: { matches: boolean }) => void

/** 系统深色偏好在 jsdom 里不可编程，用可翻转的替身代替 matchMedia。 */
function installMatchMedia(initialDark: boolean) {
  const listeners = new Set<MediaListener>()
  let dark = initialDark
  const list = {
    get matches() { return dark },
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_type: string, listener: MediaListener) => { listeners.add(listener) },
    removeEventListener: (_type: string, listener: MediaListener) => { listeners.delete(listener) },
  } as unknown as MediaQueryList
  window.matchMedia = ((query: string) => (
    query === '(prefers-color-scheme: dark)' ? list : ({ matches: false } as unknown as MediaQueryList)
  )) as typeof window.matchMedia
  return {
    setDark(value: boolean) {
      dark = value
      // 系统主题变化来自组件之外，必须包在 act 里才会同步刷新 React 的订阅结果。
      act(() => { for (const listener of [...listeners]) listener({ matches: value }) })
    },
  }
}

let media = installMatchMedia(false)

const themeColor = () => document.querySelector("meta[name='theme-color']")?.getAttribute('content')
const pick = (name: string) => fireEvent.click(screen.getByRole('radio', { name }))
const appearance = () => document.querySelector('section.appearance-settings')

describe('外观设置', () => {
  beforeEach(() => {
    window.localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    document.head.innerHTML = `<meta name="theme-color" content="${THEME_COLORS.dark}" />`
    media = installMatchMedia(false)
  })

  afterEach(() => { document.head.innerHTML = '' })

  it('无 props、自包含，并给出稳定的标题 id', () => {
    render(<AppearanceSettings />)
    expect(screen.getByRole('heading', { level: 2, name: '界面主题' })).toHaveAttribute('id', 'appearance-title')
    expect(appearance()).toHaveAttribute('aria-labelledby', 'appearance-title')
    expect(appearance()).toHaveClass('settings-section')
  })

  it('默认选中「跟随系统」，并按系统偏好落地浅色', () => {
    render(<AppearanceSettings />)
    expect(screen.getByRole('radio', { name: '跟随系统' })).toBeChecked()
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(themeColor()).toBe(THEME_COLORS.light)
    expect(screen.getByText('当前显示：浅色')).toBeInTheDocument()
  })

  it('选择深色后写入存储、切换 data-theme 与 theme-color', () => {
    render(<AppearanceSettings />)
    pick('深色')
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(themeColor()).toBe(THEME_COLORS.dark)
    expect(screen.getByRole('radio', { name: '深色' })).toBeChecked()
  })

  it('选择浅色时不跟随系统深色偏好', () => {
    media = installMatchMedia(true)
    render(<AppearanceSettings />)
    expect(document.documentElement.dataset.theme).toBe('dark')
    pick('浅色')
    expect(document.documentElement.dataset.theme).toBe('light')
    media.setDark(true)
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
  })

  it('跟随系统时系统切换实时生效，并刷新「当前显示」', () => {
    render(<AppearanceSettings />)
    expect(screen.getByText('当前显示：浅色')).toBeInTheDocument()
    media.setDark(true)
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(screen.getByText('当前显示：深色')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '跟随系统' })).toBeChecked()
  })

  it('存储非法值时回退到「跟随系统」而不是抛错', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, '../dark')
    render(<AppearanceSettings />)
    expect(screen.getByRole('radio', { name: '跟随系统' })).toBeChecked()
    expect(document.documentElement.dataset.theme).toBe('light')
  })

  it('存储不可用时给出失败提示，且不谎报已切换', () => {
    render(<AppearanceSettings />)
    const set = vi.spyOn(window.Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('存储不可用') })
    try {
      pick('深色')
      expect(screen.getByRole('alert')).toHaveTextContent('无法保存主题，请重试。')
      expect(document.documentElement.dataset.theme).toBe('light')
    } finally {
      set.mockRestore()
    }
  })

  it('英文语言下标题、选项与提示跟随', () => {
    expect(saveLanguage('en')).toBe(true)
    try {
      render(<AppearanceSettings />)
      expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Interface theme')
      expect(screen.getByRole('radio', { name: 'Follow system' })).toBeChecked()
      expect(screen.getByRole('radiogroup', { name: 'Interface theme' })).toBeInTheDocument()
      expect(screen.getByText('Currently showing: Light')).toBeInTheDocument()
    } finally {
      saveLanguage('zh-CN')
    }
  })
})
