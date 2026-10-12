import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AUTOMATION_DISCLAIMER_STORAGE_KEY,
  acknowledgeAutomationDisclaimer,
  isAutomationDisclaimerAcknowledged,
} from './accessibilityDisclaimer'

/**
 * 免责说明的确认状态。
 *
 * 这一格的语义只有两种：**没确认过**（要把说明摆出来）与**确认过**（不再重复弹）。
 * 关键在第三态——存储读不出来时**不能**当成已确认，否则无痕模式下用户一次都没看过
 * 那段说明就被放过去了。
 */
describe('免责说明的确认状态', () => {
  beforeEach(() => window.localStorage.clear())

  it('没写过时按未确认处理', () => {
    expect(isAutomationDisclaimerAcknowledged()).toBe(false)
  })

  it('确认后写入固定的标记，再读就是已确认', () => {
    expect(acknowledgeAutomationDisclaimer()).toBe(true)
    expect(window.localStorage.getItem(AUTOMATION_DISCLAIMER_STORAGE_KEY)).toBe('1')
    expect(isAutomationDisclaimerAcknowledged()).toBe(true)
  })

  it('存了别的值不算确认过', () => {
    for (const value of ['true', 'yes', '0', '1 ', '']) {
      window.localStorage.setItem(AUTOMATION_DISCLAIMER_STORAGE_KEY, value)
      expect(isAutomationDisclaimerAcknowledged()).toBe(false)
    }
  })

  it('localStorage 抛错（无痕模式）时按未确认处理，且不抛出', () => {
    const get = vi.spyOn(window.Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('存储不可用') })
    try {
      expect(isAutomationDisclaimerAcknowledged()).toBe(false)
    } finally {
      get.mockRestore()
    }
    const set = vi.spyOn(window.Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('存储不可用') })
    try {
      // 写不进去就如实回 false：调用方照常往下走，只是下次还会再弹一次。
      expect(acknowledgeAutomationDisclaimer()).toBe(false)
    } finally {
      set.mockRestore()
    }
  })
})
