import { beforeEach, describe, expect, it } from 'vitest'
import { applyTheme, clearCustomAccentColor, saveAccentMode, saveCustomAccentColor } from './theme'
import { readAccentSource, saveAccentSource, saveAdaptiveAccent, type AdaptiveAccent } from './adaptiveAccent'

const ADAPTIVE: AdaptiveAccent = {
  accent: '#8a5cf6',
  onAccent: '#ffffff',
  background: '#12101a',
  surface: '#181423',
  surfaceOpacity: 86,
  source: 'image',
  extractedAt: 1700000000000,
}

function accentVariable(): string {
  return document.documentElement.style.getPropertyValue('--blue').trim()
}

beforeEach(() => {
  window.localStorage.clear()
  document.documentElement.removeAttribute('style')
  document.documentElement.removeAttribute('data-accent')
})

describe('强调色来源三态与外壳落地', () => {
  it('自适应来源优先使用提取出的颜色', () => {
    saveAdaptiveAccent(ADAPTIVE)
    expect(saveAccentSource('adaptive')).toBe(true)
    applyTheme('dark')
    expect(accentVariable()).toBe('#8a5cf6')
    expect(document.documentElement.dataset.accent).toBe('ocean')
  })

  it('切回自定义来源时用回用户颜色，自适应结果不丢', () => {
    saveAdaptiveAccent(ADAPTIVE)
    saveAccentSource('adaptive')
    saveCustomAccentColor('#123456')
    expect(readAccentSource()).toBe('custom')
    applyTheme('dark')
    expect(accentVariable()).toBe('#123456')
    saveAccentSource('adaptive')
    applyTheme('dark')
    expect(accentVariable()).toBe('#8a5cf6')
  })

  it('选预置色板等于回到 default，并清掉自定义覆盖', () => {
    saveCustomAccentColor('#123456')
    expect(readAccentSource()).toBe('custom')
    expect(saveAccentMode('mint')).toBe(true)
    expect(readAccentSource()).toBe('default')
    applyTheme('light')
    expect(accentVariable()).toBe('')
    expect(document.documentElement.dataset.accent).toBe('mint')
  })

  it('清掉自定义色时来源退回预置，界面不会停在已删除的颜色上', () => {
    saveCustomAccentColor('#123456')
    clearCustomAccentColor()
    expect(readAccentSource()).toBe('default')
    applyTheme('light')
    expect(accentVariable()).toBe('')
  })

  it('自适应没有结果时按预置处理，不写空变量', () => {
    saveAccentSource('adaptive')
    applyTheme('dark')
    expect(accentVariable()).toBe('')
    expect(document.documentElement.dataset.accent).toBe('ocean')
  })
})
