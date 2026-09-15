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

  it('persists the selected permission mode and preserves it on unrelated saves', async () => {
    const bridge = createBrowserBridge()
    await bridge.saveSettings({ ...baseSettings, harnessPermissionMode: 'danger-full-access' })
    expect((await bridge.saveSettings(baseSettings)).harnessPermissionMode).toBe('danger-full-access')
    expect((await bridge.getSettings()).harnessPermissionMode).toBe('danger-full-access')
    await bridge.saveSettings({ ...baseSettings, harnessPermissionMode: 'workspace-write' })
    expect((await bridge.getSettings()).harnessPermissionMode).toBe('workspace-write')
  })

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

  it('keeps API keys out of the saved settings and out of storage', async () => {
    const bridge = createBrowserBridge()

    const saved = await bridge.saveSettings({
      ...baseSettings,
      providerApiKeys: { deepseek: 'sk-browser-preview-secret' },
      customModelProviders: [
        {
          id: 'gateway-1',
          name: '本地网关',
          api: 'openai-completions',
          baseUrl: 'https://gateway.example.invalid/v1',
          models: [{ id: 'local-model', name: 'Local', contextWindow: 8192, maxTokens: 1024 }],
        },
      ],
      customProviderApiKeys: { 'gateway-1': 'sk-custom-secret' },
    })

    // 密钥是敏感字段：原生侧落盘后不回显，浏览器预览也必须与生产同构。
    expect(saved).not.toHaveProperty('providerApiKeys')
    expect(saved).not.toHaveProperty('customProviderApiKeys')
    expect(saved.configuredModelProviders).toEqual(['deepseek'])
    expect(saved.configuredCustomModelProviders).toEqual(['gateway-1'])
    // 整个网页存储里都不该留痕：草稿与密钥只存在于内存。
    const stored = JSON.stringify(window.localStorage)
    expect(stored).not.toContain('sk-browser-preview-secret')
    expect(stored).not.toContain('sk-custom-secret')
    expect(stored).not.toContain('providerApiKeys')
  })

  it('浏览器预览没有运行时自检：如实拒绝，不编造一份「全部正常」', async () => {
    const bridge = createBrowserBridge()

    await expect(bridge.runRuntimeSelfCheck('check')).rejects.toThrow('浏览器预览不支持运行时自检')
    await expect(bridge.runRuntimeSelfCheck('repair')).rejects.toThrow('浏览器预览不支持运行时自检')
  })
})
