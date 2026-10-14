import { describe, expect, it, vi } from 'vitest'
import {
  MAX_SHELL_FONT_PX,
  MIN_SHELL_FONT_PX,
  SHELL_FONT_BASELINE_PX,
  applyShellFontScale,
  shellFontScale,
} from './shellFont'

describe('壳字号缩放', () => {
  it('基准字号就是 1：用户不动设置时外观与改动前一致', () => {
    expect(shellFontScale(SHELL_FONT_BASELINE_PX)).toBe(1)
  })

  it('比基准大放大、比基准小缩小，比例就是字号之比', () => {
    expect(shellFontScale(20)).toBeCloseTo(20 / 14, 10)
    expect(shellFontScale(11)).toBeCloseTo(11 / 14, 10)
  })

  it('越界值收敛到两端，不把极端值放进 CSS', () => {
    expect(shellFontScale(5)).toBeCloseTo(MIN_SHELL_FONT_PX / 14, 10)
    expect(shellFontScale(99)).toBeCloseTo(MAX_SHELL_FONT_PX / 14, 10)
  })

  it('非数值回落到 1，小数按整数格取整', () => {
    expect(shellFontScale(Number.NaN)).toBe(1)
    expect(shellFontScale(Number.POSITIVE_INFINITY)).toBe(1)
    expect(shellFontScale(13.6)).toBe(1)
  })

  it('写自定义属性并回报实际生效的系数', () => {
    const setProperty = vi.fn()
    const root = { style: { setProperty } } as unknown as HTMLElement

    expect(applyShellFontScale(21, root)).toBeCloseTo(1.5, 10)
    expect(setProperty).toHaveBeenCalledTimes(1)
    expect(setProperty).toHaveBeenLastCalledWith('--app-font-scale', String(1.5))
  })
})
