import { beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { AppearanceSettings } from './AppearanceSettings'
import { ACCENT_SOURCE_KEY, ADAPTIVE_ACCENT_KEY, saveAdaptiveAccent } from '../adaptiveAccent'

beforeEach(() => {
  window.localStorage.clear()
  document.documentElement.removeAttribute('style')
})

describe('外观设置：自适应主题色与同步分区', () => {
  it('强调色来源是三个互斥选项，默认落在预置色板', () => {
    render(<AppearanceSettings />)
    const group = screen.getByRole('radiogroup', { name: '强调色来源' })
    // 来源组里只有这三个选项；页面别处还有主题三态与预置色板的单选，不能整页数。
    expect(within(group).getAllByRole('radio')).toHaveLength(3)
    expect(within(group).getByText('预置色板')).toBeTruthy()
    expect(within(group).getByText('自定义颜色')).toBeTruthy()
    expect(within(group).getByText('自适应背景色')).toBeTruthy()
    expect(window.localStorage.getItem(ACCENT_SOURCE_KEY)).toBeNull()
  })

  it('点选「自适应背景色」会把来源写进独立存储', () => {
    render(<AppearanceSettings />)
    fireEvent.click(screen.getByRole('radio', { name: '自适应背景色' }))
    expect(window.localStorage.getItem(ACCENT_SOURCE_KEY)).toBe('adaptive')
  })

  it('已有提取结果时显示颜色与重新提取入口', () => {
    saveAdaptiveAccent({
      accent: '#315fcf',
      onAccent: '#ffffff',
      background: '#101820',
      surface: '#18222c',
      surfaceOpacity: 86,
      source: 'image',
      extractedAt: 1700000000000,
    })
    render(<AppearanceSettings />)
    expect(screen.getByText(/#315fcf/u)).toBeTruthy()
    expect(screen.getByRole('button', { name: '重新提取' })).toBeTruthy()
    expect(window.localStorage.getItem(ADAPTIVE_ACCENT_KEY)).toContain('#315fcf')
  })

  it('同步分区给出两个显式按钮，未同步过时如实说明', () => {
    render(<AppearanceSettings />)
    expect(screen.getByRole('button', { name: '让 DSH 使用当前主题' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '恢复 DSH 默认主题' })).toBeTruthy()
    expect(screen.getByText('还没有同步过')).toBeTruthy()
  })
})
