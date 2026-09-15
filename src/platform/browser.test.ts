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

  it('浏览器预览的投递区始终报「不支持」，不编造可用状态与计数', async () => {
    const bridge = createBrowserBridge()

    const state = await bridge.getMailboxState()

    // 路径是文档里的固定路径（只用于展示），但可用性与计数必须是真实的「没有」。
    expect(state.availability).toBe('unsupported')
    expect(state.level).toBe('T0')
    expect(state.available).toBe(false)
    expect(state.supported).toBe(false)
    expect(state.granted).toBe(false)
    expect(state.inboxFileCount).toBe(0)
    expect(state.inboxTars).toEqual([])
    expect(state.inboxPath).toBe('/storage/emulated/0/Documents/DSH/inbox')
    expect(state.guestInboxPath).toBe('/mnt/inbox')

    const storage = await bridge.getStorageAccessState()
    expect(storage).toEqual({ mediaGranted: false, allFilesGranted: false, allFilesSupported: false, sdkInt: 0 })
    // 浏览器里没有可授权的系统权限：如实返回未授予，不假装已授权。
    await expect(bridge.requestMediaPermission()).resolves.toEqual({ granted: false })
    await expect(bridge.openAllFilesAccessSettings()).resolves.toEqual({ supported: false, granted: false })
  })

  it('浏览器预览没有真实文件系统：导入与导出明确拒绝，不编造条目数', async () => {
    const bridge = createBrowserBridge()

    await expect(bridge.importMailbox()).rejects.toThrow('浏览器预览不支持导入投递区')
    await expect(bridge.exportMailbox()).rejects.toThrow('浏览器预览不支持导出投递区')
    // 非法导出起点在离开前端之前就被拒绝（同步抛错，与平台层其他入参校验同构）。
    expect(() => bridge.exportMailbox('../outside')).toThrow('投递区导出起点格式无效')
  })

  it('浏览器预览的目录白名单如实报「空且不可用」，不编造条目', async () => {
    const bridge = createBrowserBridge()

    const state = await bridge.getStorageDirs()

    // 上限照实回传（文档里的固定值，界面要显示 0/8），但可用性必须是真实的「没有」。
    expect(state.maxDirectories).toBe(8)
    expect(state.entries).toEqual([])
    expect(state.count).toBe(0)
    expect(state.supported).toBe(false)
    expect(state.granted).toBe(false)
    expect(state.level).toBe('T0')
    expect(state.active).toBe(false)
    // 浏览器里没有可弹的 SAF 选择器：新增/移除都明确拒绝，不假装成功。
    await expect(bridge.addStorageDirectory()).rejects.toThrow('浏览器预览不支持选择存储目录')
    await expect(bridge.removeStorageDirectory('/storage/emulated/0/Download'))
      .rejects.toThrow('浏览器预览不支持移除存储目录')
    // 非法路径在离开前端之前就被拒绝（同步抛错）。
    expect(() => bridge.removeStorageDirectory('/data/data/x')).toThrow('存储目录路径格式无效')
  })
})
