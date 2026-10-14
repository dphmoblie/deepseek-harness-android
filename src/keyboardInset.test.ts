import { describe, expect, it, vi } from 'vitest'
import { keyboardInsetPx, observeKeyboardInset, type KeyboardViewport } from './keyboardInset'

/** 可控的假视口：改完读数手动触发一次 resize，模拟真机的视口变化。 */
class FakeViewport implements KeyboardViewport {
  height = 800
  offsetTop = 0
  private readonly listeners = new Set<() => void>()

  addEventListener(_type: 'resize' | 'scroll', listener: () => void): void {
    this.listeners.add(listener)
  }

  removeEventListener(_type: 'resize' | 'scroll', listener: () => void): void {
    this.listeners.delete(listener)
  }

  emit(): void {
    for (const listener of [...this.listeners]) listener()
  }

  get listenerCount(): number {
    return this.listeners.size
  }
}

describe('输入法遮挡高度', () => {
  it('布局视口没被键盘改变时算出要遮挡的高度', () => {
    expect(keyboardInsetPx({ innerHeight: 800, visualHeight: 500, visualOffsetTop: 0 })).toBe(300)
  })

  it('算上视觉视口的偏移，不把页面滚动当成键盘', () => {
    // 视口整体下移 40px（页面滚动），真正被挡住的是 800 - (500 + 40)。
    expect(keyboardInsetPx({ innerHeight: 800, visualHeight: 500, visualOffsetTop: 40 })).toBe(260)
  })

  it('布局视口已经收缩时不重复让位', () => {
    // adjustResize 生效：innerHeight 与视觉视口一起变小，遮挡高度就是 0。
    expect(keyboardInsetPx({ innerHeight: 500, visualHeight: 500, visualOffsetTop: 0 })).toBe(0)
  })

  it('小差值当噪声，负数归零', () => {
    expect(keyboardInsetPx({ innerHeight: 800, visualHeight: 750, visualOffsetTop: 0 })).toBe(0)
    expect(keyboardInsetPx({ innerHeight: 800, visualHeight: 860, visualOffsetTop: 0 })).toBe(0)
  })

  it('订阅视口变化，立即回报一次并在取消后停止', () => {
    const viewport = new FakeViewport()
    const apply = vi.fn<(inset: number) => void>()
    let innerHeight = 800

    const stop = observeKeyboardInset(viewport, apply, () => innerHeight)

    expect(apply).toHaveBeenLastCalledWith(0)

    viewport.height = 500
    viewport.emit()
    expect(apply).toHaveBeenLastCalledWith(300)

    // 键盘收起：遮挡高度回到 0。
    viewport.height = 800
    innerHeight = 800
    viewport.emit()
    expect(apply).toHaveBeenLastCalledWith(0)

    stop()
    expect(viewport.listenerCount).toBe(0)
    viewport.height = 300
    viewport.emit()
    expect(apply).toHaveBeenLastCalledWith(0)
  })

  it('没有视觉视口时只回报 0，不订阅', () => {
    const apply = vi.fn<(inset: number) => void>()
    const stop = observeKeyboardInset(undefined, apply, () => 800)

    expect(apply).toHaveBeenCalledTimes(1)
    expect(apply).toHaveBeenLastCalledWith(0)
    expect(() => stop()).not.toThrow()
  })
})
