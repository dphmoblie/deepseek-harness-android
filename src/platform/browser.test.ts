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

  it('浏览器预览没有运行时版本管理：如实拒绝，不编造版本列表', async () => {
    const bridge = createBrowserBridge()

    await expect(bridge.getRuntimeVersions()).rejects.toThrow('浏览器预览不支持运行时版本管理')
    await expect(bridge.switchRuntimeVersion('previous')).rejects.toThrow('浏览器预览不支持运行时版本管理')
    await expect(bridge.deleteRuntimeVersion('previous')).rejects.toThrow('浏览器预览不支持运行时版本管理')

    // 目标校验先于「不支持」：参数写错时要说参数错，不能把它说成功能缺失。
    // 校验是同步抛（与原生桥接一致），所以这里断言同步抛出而不是 Promise 拒绝。
    const invalidTarget = 'current' as unknown as 'previous'
    expect(() => bridge.switchRuntimeVersion(invalidTarget)).toThrow('运行时版本操作目标无效')
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
    expect(() => bridge.exportMailbox(undefined, '../outside')).toThrow('投递区导出起点格式无效')
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

describe('浏览器桥的无障碍白名单与验证密码', () => {
  it('限制开关默认开启，密码验证后可关闭并恢复；旧调用和清密码保留开关', async () => {
    const bridge = createBrowserBridge()
    expect((await bridge.getAccessibilityAutomationState()).whitelistEnabled).toBe(true)
    const packages = ['com.example.reader']
    await bridge.setAccessibilityAutomationPackages(packages)
    const before = await bridge.setAccessibilityPassword('test-passphrase')
    await expect(bridge.setAccessibilityAutomationPackages(packages, undefined, false)).rejects.toThrow('需要验证密码')
    await expect(bridge.setAccessibilityAutomationPackages(packages, 'wrong-passphrase', false)).rejects.toThrow('验证密码不正确')
    expect(await bridge.getAccessibilityAutomationState()).toEqual(before)
    const disabled = await bridge.setAccessibilityAutomationPackages(packages, 'test-passphrase', false)
    expect(disabled.whitelistEnabled).toBe(false)
    expect(disabled.allowedPackages).toEqual(before.allowedPackages)
    expect((await bridge.setAccessibilityAutomationPackages(packages, 'test-passphrase')).whitelistEnabled).toBe(false)
    expect((await bridge.clearAccessibilityPassword('test-passphrase')).whitelistEnabled).toBe(false)
    const enabled = await bridge.setAccessibilityAutomationPackages(packages, undefined, true)
    expect(enabled.whitelistEnabled).toBe(true)
    expect(enabled.allowedPackages).toEqual(before.allowedPackages)
  })

  it('白名单永远带上自动项；条目数量不设上限，但包名格式仍然校验', async () => {
    const bridge = createBrowserBridge()

    const initial = await bridge.getAccessibilityAutomationState()
    expect(initial.alwaysAllowedPackages).toEqual(['io.deepseekharness.mobile'])
    expect(initial.allowedPackages).toEqual([])
    expect(initial.passwordConfigured).toBe(false)

    const saved = await bridge.setAccessibilityAutomationPackages(['com.example.target', 'io.deepseekharness.mobile'])
    // 用户即使没传自动项，结果里也必须还有它，且不重复。
    expect(saved.allowedPackages).toEqual(['io.deepseekharness.mobile', 'com.example.target'])
    expect(saved.alwaysAllowedPackages).toEqual(['io.deepseekharness.mobile'])

    // 条目数量不设上限（原生侧也从不限制条数）：40 条一律收下，另加恒在的自动项。
    const many = Array.from({ length: 40 }, (_, index) => `com.example.app${index}`)
    expect((await bridge.setAccessibilityAutomationPackages(many)).allowedPackages).toHaveLength(41)
    await expect(bridge.setAccessibilityAutomationPackages(['not a package'])).rejects.toThrow('无障碍白名单格式无效')
  })

  it('设过密码后改白名单必须带对密码；密码错误时明确拒绝', async () => {
    const bridge = createBrowserBridge()
    await bridge.setAccessibilityPassword('123456')

    await expect(bridge.setAccessibilityAutomationPackages(['com.example.target'])).rejects.toThrow('需要验证密码')
    await expect(bridge.setAccessibilityAutomationPackages(['com.example.target'], '654321')).rejects.toThrow('验证密码不正确')
    expect((await bridge.getAccessibilityAutomationState()).allowedPackages).toEqual([])

    const saved = await bridge.setAccessibilityAutomationPackages(['com.example.target'], '123456')
    expect(saved.allowedPackages).toEqual(['io.deepseekharness.mobile', 'com.example.target'])
    expect(saved.passwordConfigured).toBe(true)
  })

  it('清除密码必须带当前密码，且白名单原样保留', async () => {
    const bridge = createBrowserBridge()
    await bridge.setAccessibilityPassword('123456')
    await bridge.setAccessibilityAutomationPackages(['com.example.target'], '123456')

    await expect(bridge.clearAccessibilityPassword('654321')).rejects.toThrow('当前验证密码不正确')
    const cleared = await bridge.clearAccessibilityPassword('123456')
    expect(cleared.passwordConfigured).toBe(false)
    // 清掉的是密码，不是用户配好的目标清单。
    expect(cleared.allowedPackages).toEqual(['io.deepseekharness.mobile', 'com.example.target'])
    // 密码已清：此后改白名单不再需要密码。
    await expect(bridge.setAccessibilityAutomationPackages(['com.example.target'])).resolves.toBeTruthy()
  })

  it('未设置密码时不能清；浏览器没有系统生物识别，如实拒绝', async () => {
    const bridge = createBrowserBridge()

    await expect(bridge.clearAccessibilityPassword('123456')).rejects.toThrow('尚未设置验证密码')
    // 首次设置不需要当前密码：多带的 currentPassword 也不参与比对。
    expect((await bridge.setAccessibilityPassword('123456', '654321')).passwordConfigured).toBe(true)
    // 已设置过之后再改，就必须带对了。
    await expect(bridge.setAccessibilityPassword('654321')).rejects.toThrow('需要当前验证密码')
    // 浏览器预览里没有指纹/锁屏密码可验：不谎报「重置成功」。
    await expect(bridge.resetAccessibilityPasswordWithBiometric()).rejects.toThrow('浏览器预览不支持系统生物识别验证')
  })
})

describe('浏览器桥的自动化总开关与运行记录', () => {
  it('总开关按与原生同一套规则转移，且浏览器里服务状态恒为未开启', async () => {
    const bridge = createBrowserBridge()
    const initial = await bridge.getAccessibilityAutomationState()
    expect(initial.automationRuntime).toBe('off')
    // 浏览器里没有无障碍服务进程：谎报 connected 会让界面显示一个不存在的「已连接」。
    expect(initial.serviceStatus).toBe('off')

    expect((await bridge.setAccessibilityAutomationRuntime('start')).automationRuntime).toBe('running')
    expect((await bridge.setAccessibilityAutomationRuntime('pause')).automationRuntime).toBe('paused')
    expect((await bridge.setAccessibilityAutomationRuntime('resume')).automationRuntime).toBe('running')
    // 紧急停止回落到关闭，而不是暂停：要再开就得显式点一次「开始」。
    expect((await bridge.setAccessibilityAutomationRuntime('emergency_stop')).automationRuntime).toBe('off')
    // 关着的时候按暂停仍是关闭（与原生 `AutomationControlPolicy.next` 逐字一致）。
    expect((await bridge.setAccessibilityAutomationRuntime('pause')).automationRuntime).toBe('off')
  })

  it('动作名认不出来直接拒绝，不改动任何状态', async () => {
    const bridge = createBrowserBridge()
    await bridge.setAccessibilityAutomationRuntime('start')

    // 拼错的参数若能落到 `start`，就等于浏览器预览里也能把自动化打开。
    await expect((bridge.setAccessibilityAutomationRuntime as (action: string) => Promise<unknown>)('START'))
      .rejects.toThrow('自动化操作取值无效')
    expect((await bridge.getAccessibilityAutomationState()).automationRuntime).toBe('running')
  })

  it('没有无障碍服务进程：运行记录如实回「未连接 + 空」，但按应用查询直接拒绝', async () => {
    const bridge = createBrowserBridge()

    // 这里回空不是编造：`connected: false` 已经说清「读不到」，界面据此显示原因而不是「什么都没发生」。
    expect(await bridge.getAccessibilityAutomationRuns()).toEqual({
      connected: false, limit: 50, dropped: 0, runs: [], blocked: [],
    })
    await expect(bridge.getAccessibilityAutomationRuns('com.example.reader'))
      .rejects.toThrow('浏览器预览不支持按应用查询自动化运行记录')
  })

  it('没有规则存储：读取与写入都如实拒绝，不回空列表也不放内存规则', async () => {
    const bridge = createBrowserBridge()

    // 回一份空列表会被界面显示成「你还没有规则」，而真相是这里根本读不到——
    // 所以这里宁可失败，让卡片显示「规则读取失败」，也不编一份 0 条规则。
    await expect(bridge.getAccessibilityAutomationRules('com.example.reader'))
      .rejects.toThrow('浏览器预览没有无障碍规则存储')
    // 读全文同样如实拒绝：回一份空规则会被表单预填进去，点一次保存就把真机上那条规则整条清掉。
    await expect(bridge.getAccessibilityAutomationRule({ packageName: 'com.example.reader', ruleId: 'rule-1' }))
      .rejects.toThrow('浏览器预览没有无障碍规则存储')
    await expect(bridge.setAccessibilityAutomationRuleEnabled({
      packageName: 'com.example.reader',
      ruleId: 'rule-1',
      enabled: false,
    })).rejects.toThrow('浏览器预览不能修改无障碍规则')
    // 每应用参数同样不编：回一份默认值（没暂停、上限 1）看起来像真读了，
    // 而用户在这里点保存就是拿预览里的假数字覆盖真机。
    await expect(bridge.getAccessibilityAutomationAppConfig({ packageName: 'com.example.reader' }))
      .rejects.toThrow('浏览器预览没有每应用自动化参数')
    await expect(bridge.setAccessibilityAutomationAppConfig({
      packageName: 'com.example.reader',
      paused: true,
      defaultMaxActions: 2,
      defaultActionCoolDownMs: 1000,
      defaultMatchDelayMs: 200,
    })).rejects.toThrow('浏览器预览不能修改每应用自动化参数')
  })

  it('写规则前的入参自检照常生效：包名、规则标识与布尔值都在过桥前拦下', async () => {
    const bridge = createBrowserBridge()

    await expect(bridge.getAccessibilityAutomationRules('not a package'))
      .rejects.toThrow('无障碍白名单第 1 项无效')
    await expect(bridge.getAccessibilityAutomationRule({ packageName: 'com.example.reader', ruleId: 'rule 1' }))
      .rejects.toThrow('自动化规则标识无效')
    await expect(bridge.setAccessibilityAutomationRuleEnabled({
      packageName: 'com.example.reader',
      ruleId: 'rule 1',
      enabled: true,
    })).rejects.toThrow('自动化规则标识无效')
    await expect((bridge.setAccessibilityAutomationRuleEnabled as (options: unknown) => Promise<unknown>)({
      packageName: 'com.example.reader',
      ruleId: 'rule-1',
      enabled: 'yes',
    })).rejects.toThrow('规则启用状态格式无效')
    // 写参数的自检也照走：包名与四个字段都在过桥前拦下（预览里同样不该把明显越界的数字当合法）。
    await expect(bridge.getAccessibilityAutomationAppConfig({ packageName: 'not a package' }))
      .rejects.toThrow('无障碍白名单第 1 项无效')
    await expect(bridge.setAccessibilityAutomationAppConfig({
      packageName: 'com.example.reader',
      paused: true,
      defaultMaxActions: 100,
      defaultActionCoolDownMs: 1000,
      defaultMatchDelayMs: 200,
    })).rejects.toThrow('默认动作上限必须在 1 到 99 之间')
  })
})

describe('浏览器桥的更新渠道与应用自更新', () => {
  it('没有更新渠道：可用版本列表与应用更新状态都如实拒绝，不编造版本', async () => {
    const bridge = createBrowserBridge()

    await expect(bridge.listRuntimeReleases()).rejects.toThrow('浏览器模式下没有更新渠道')
    await expect(bridge.getAppUpdateState()).rejects.toThrow('浏览器模式下没有应用更新')
    await expect(bridge.getAppUpdateState(false)).rejects.toThrow('浏览器模式下没有应用更新')
    await expect(bridge.getAppUpdateState(true)).rejects.toThrow('浏览器模式下没有应用更新')
  })

  it('应用更新刷新标记只接受布尔，非法参数通过 rejected Promise 返回', async () => {
    const bridge = createBrowserBridge()
    const getState = bridge.getAppUpdateState as (forceRefresh: unknown) => Promise<unknown>
    for (const value of [null, 'true', 'false', 0, 1, {}, []]) {
      await expect(getState(value)).rejects.toThrow('应用更新刷新参数无效')
    }
  })

  it('没有可下载安装的 APK：下载、安装与授权页都如实拒绝，不假装成功', async () => {
    const bridge = createBrowserBridge()

    await expect(bridge.downloadAppUpdate()).rejects.toThrow('浏览器模式下不能下载安装包')
    await expect(bridge.installAppUpdate()).rejects.toThrow('浏览器模式下不能安装应用')
    await expect(bridge.openAppUpdateInstallSettings()).rejects.toThrow('浏览器模式下不能打开安装授权页面')
  })
})

describe('浏览器桥的运行时会话快照', () => {
  it('没有快照能力：四条都如实拒绝，不编造空快照', async () => {
    const bridge = createBrowserBridge()

    await expect(bridge.getRuntimeSessionSnapshotState()).rejects.toThrow('浏览器模式下没有运行时会话快照')
    await expect(bridge.createRuntimeSessionSnapshot()).rejects.toThrow('浏览器模式下不能创建运行时会话快照')
    await expect(bridge.restoreRuntimeSessionSnapshot('snap-1700000001000-00000002'))
      .rejects.toThrow('浏览器模式下不能恢复运行时会话快照')
    await expect(bridge.deleteRuntimeSessionSnapshot('snap-1700000001000-00000002'))
      .rejects.toThrow('浏览器模式下不能删除运行时会话快照')
  })

  it('拒绝时不给「还没有快照」的假状态：只会拿到错误，拿不到状态对象', async () => {
    const bridge = createBrowserBridge()

    // 返回 `{ maxSnapshots: 3, ..., snapshots: [] }` 会被界面渲染成「还没有快照」——那是编造一次成功。
    const outcome = await bridge.getRuntimeSessionSnapshotState().then(
      () => null,
      (reason: unknown) => reason,
    )

    if (!(outcome instanceof Error)) {
      throw new Error('浏览器桩不允许 resolve 出状态对象')
    }
    expect(outcome.message).toBe('浏览器模式下没有运行时会话快照')
  })
})

describe('浏览器桥的会话工作区元数据', () => {
  it('没有会话列表通道：如实拒绝，不编造一份列表', async () => {
    const bridge = createBrowserBridge()

    await expect(bridge.listSessions()).rejects.toThrow('浏览器模式下没有运行时会话列表')
  })

  it('拒绝时不给「暂无会话」的假状态：只会拿到错误，拿不到结果对象', async () => {
    const bridge = createBrowserBridge()

    // 返回 `{ status: 'ready', sessions: [], truncated: false }` 会被界面渲染成「暂无会话」——那是编造一次成功。
    const outcome = await bridge.listSessions().then(
      () => null,
      (reason: unknown) => reason,
    )

    if (!(outcome instanceof Error)) {
      throw new Error('浏览器桩不允许 resolve 出会话列表结果')
    }
    expect(outcome.message).toBe('浏览器模式下没有运行时会话列表')
  })
})

describe('浏览器桥的插件数据清理', () => {
  it('浏览器预览不支持插件数据清理（无参数）', async () => {
    const bridge = createBrowserBridge()
    await expect(bridge.purgePluginData()).rejects.toThrow('浏览器预览不支持插件数据清理')
  })

  it('浏览器预览不支持插件数据清理（有合法参数）', async () => {
    const bridge = createBrowserBridge()
    await expect(bridge.purgePluginData({ packageName: 'left-pad' })).rejects.toThrow('浏览器预览不支持插件数据清理')
  })

  it('插件标识格式无效在过桥前被拒绝', async () => {
    const bridge = createBrowserBridge()
    await expect(bridge.purgePluginData({ packageName: 'Left-Pad' })).rejects.toThrow('插件标识格式无效')
  })
})

describe('浏览器桥的设备能力查询', () => {
  it('浏览器预览不支持设备能力查询，如实拒绝而不是回一份假清单', async () => {
    const bridge = createBrowserBridge()
    await expect(bridge.getDeviceCapabilities()).rejects.toThrow('浏览器预览不支持设备能力查询')
  })
})
