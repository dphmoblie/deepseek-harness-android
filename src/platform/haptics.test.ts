import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { triggerHaptic } from './haptics'
describe('triggerHaptic 触感反馈', () => {
  // eslint-disable-next-line @typescript-eslint/unbound-method
  const originalVibrate = navigator.vibrate

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    Object.defineProperty(navigator, 'vibrate', {
      value: originalVibrate,
      configurable: true,
      writable: true,
    })
  })

  it('在支持 navigator.vibrate 的环境下调用对应档位', () => {
    const vibrateMock = vi.fn().mockReturnValue(true)
    Object.defineProperty(navigator, 'vibrate', {
      value: vibrateMock,
      configurable: true,
      writable: true,
    })

    triggerHaptic('light')
    expect(vibrateMock).toHaveBeenCalledWith(10)

    triggerHaptic('medium')
    expect(vibrateMock).toHaveBeenCalledWith(25)

    triggerHaptic('success')
    expect(vibrateMock).toHaveBeenCalledWith([15, 40, 15])

    triggerHaptic('warning')
    expect(vibrateMock).toHaveBeenCalledWith([25, 50, 25])

    triggerHaptic('error')
    expect(vibrateMock).toHaveBeenCalledWith([35, 60, 45, 60, 35])
  })

  it('在 navigator.vibrate 抛出异常时不向上抛错', () => {
    Object.defineProperty(navigator, 'vibrate', {
      value: vi.fn().mockImplementation(() => {
        throw new Error('NotAllowedError')
      }),
      configurable: true,
      writable: true,
    })

    expect(() => triggerHaptic('light')).not.toThrow()
  })

  it('在 navigator 缺失 vibrate 时安全空操作', () => {
    Object.defineProperty(navigator, 'vibrate', {
      value: undefined,
      configurable: true,
      writable: true,
    })

    expect(() => triggerHaptic('light')).not.toThrow()
  })
})
