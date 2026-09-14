import { beforeEach, describe, expect, it } from 'vitest'
import { createBrowserBridge } from './browser'

const baseSettings = {
  manifestUrl: '',
  manifestSha256: '',
  keepScreenAwake: true,
  terminalFontSize: 14,
  configuredModelProviders: [],
  autoLaunch: false,
  keepRuntimeInBackground: false,
}

describe('browser settings bridge', () => {
  beforeEach(() => localStorage.clear())

  it('preserves the stored overlay-ball setting when an unrelated save omits it', async () => {
    const bridge = createBrowserBridge()
    await bridge.saveSettings({ ...baseSettings, overlayBallEnabled: true })

    const saved = await bridge.saveSettings({ ...baseSettings, terminalFontSize: 18 })

    expect(saved.overlayBallEnabled).toBe(true)
    expect(saved.terminalFontSize).toBe(18)
    expect((await bridge.getSettings()).overlayBallEnabled).toBe(true)
  })

  it('applies an explicitly false overlay-ball setting', async () => {
    const bridge = createBrowserBridge()
    await bridge.saveSettings({ ...baseSettings, overlayBallEnabled: true })

    expect((await bridge.saveSettings({ ...baseSettings, overlayBallEnabled: false })).overlayBallEnabled).toBe(false)
  })
})
