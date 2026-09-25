import { describe, expect, it } from 'vitest'
import {
  assertBase64Input,
  assertDiagnosticRetentionDays,
  assertMailboxSubdirectory,
  assertRuntimeVersionTarget,
  assertSessionId,
  assertStorageDirPath,
  assertTerminalKind,
  assertTerminalSize,
  validateAllFilesAccessResult,
  validateDiagnosticLogExport,
  validateDiagnosticLogState,
  validateDiagnosticLogText,
  validateHarnessLog,
  validateKeepAliveState,
  validateMailboxExportResult,
  validateMailboxImportResult,
  validateMailboxState,
  validateMediaPermissionResult,
  validateNotificationPermissionResult,
  validateOverlayBallState,
  validateRuntimeProgress,
  validateRuntimeSource,
  validateRuntimeState,
  validateRuntimeVersions,
  validateSettings,
  validateSettingsUpdate,
  validateShizukuState,
  validateStorageAccessState,
  validateStorageDirsState,
  validateStoredSettings,
  validateTerminalChunk,
  validateTerminalExit,
} from './validation'
import {
  DIAGNOSTIC_LOG_MAX_CHARS,
  DIAGNOSTIC_LOG_WINDOW_OPTIONS,
  DIAGNOSTIC_RETENTION_MAX,
  DIAGNOSTIC_RETENTION_MIN,
  HARNESS_LOG_MAX_CHARS,
  HARNESS_LOG_WINDOW_OPTIONS,
} from './types'
import type { RuntimeSettings } from './types'

const ipv4 = (...octets: number[]): string => octets.join('.')

describe('runtime source validation', () => {
  it('accepts an empty pair for the bundled runtime', () => {
    expect(validateRuntimeSource({ manifestUrl: ' ', manifestSha256: ' ' })).toEqual({
      manifestUrl: '',
      manifestSha256: '',
    })
  })

  it('rejects a partially configured remote source', () => {
    expect(() => validateRuntimeSource({
      manifestUrl: 'https://downloads.example.invalid/runtime.json',
      manifestSha256: '',
    })).toThrow('同时填写')
  })

  it('normalizes a valid HTTPS source and digest', () => {
    expect(validateRuntimeSource({
      manifestUrl: ' https://downloads.example.invalid/runtime.json ',
      manifestSha256: 'A'.repeat(64),
    })).toEqual({
      manifestUrl: 'https://downloads.example.invalid/runtime.json',
      manifestSha256: 'a'.repeat(64),
    })
  })

  it('does not mistake public hostnames with IPv6-like prefixes for private addresses', () => {
    expect(validateRuntimeSource({
      manifestUrl: 'https://fcdn.example.invalid/runtime.json',
      manifestSha256: 'a'.repeat(64),
    }).manifestUrl).toBe('https://fcdn.example.invalid/runtime.json')
  })

  it.each([
    'http://downloads.example.invalid/runtime.json',
    'https://user@downloads.example.invalid/runtime.json',
    'https://downloads.example.invalid/runtime.json#fragment',
  ])('rejects unsafe URL %s', manifestUrl => {
    expect(() => validateRuntimeSource({ manifestUrl, manifestSha256: 'a'.repeat(64) })).toThrow()
  })

  it.each([
    'https://localhost/runtime.json',
    'https://127.0.0.1/runtime.json',
    `https://${ipv4(10, 0, 0, 2)}/runtime.json`,
    'https://169.254.169.254/latest/meta-data',
    `https://${ipv4(192, 168, 1, 2)}/runtime.json`,
    'https://[::1]/runtime.json',
    'https://[fd00::1]/runtime.json',
    'https://[::ffff:127.0.0.1]/runtime.json',
    'https://localhost./runtime.json',
    'https://service.local./runtime.json',
  ])('rejects non-public destination %s', manifestUrl => {
    expect(() => validateRuntimeSource({ manifestUrl, manifestSha256: 'a'.repeat(64) })).toThrow('私网')
  })

  it('rejects malformed digests', () => {
    expect(() => validateRuntimeSource({
      manifestUrl: 'https://downloads.example.invalid/runtime.json',
      manifestSha256: 'not-a-digest',
    })).toThrow('SHA-256')
  })

  it('rejects URL control characters before parsing', () => {
    expect(() => validateRuntimeSource({
      manifestUrl: 'https://downloads.example.invalid/run\ntime.json',
      manifestSha256: 'a'.repeat(64),
    })).toThrow('非法字符')
  })
})

describe('settings validation', () => {
  it('accepts only the explicit unconfigured stored-source state', () => {
    expect(validateStoredSettings({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: false,
      terminalFontSize: 14,
    })).toEqual({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: false,
      terminalFontSize: 14,
      configuredModelProviders: [],
      autoLaunch: false,
      // 旧存储没有该键：按默认 false 迁移，不改变既有行为。
      keepRuntimeInBackground: false,
      // 同理：旧存储也没有悬浮球键，缺省按关闭处理。
      overlayBallEnabled: false,
    })
    expect(() => validateStoredSettings({
      manifestUrl: '',
      manifestSha256: 'a'.repeat(64),
      keepScreenAwake: false,
      terminalFontSize: 14,
    })).toThrow()
  })

  it('enforces terminal font limits', () => {
    expect(() => validateSettings({
      manifestUrl: 'https://downloads.example.invalid/runtime.json',
      manifestSha256: 'a'.repeat(64),
      keepScreenAwake: false,
      terminalFontSize: 25,
      configuredModelProviders: [],
    })).toThrow('字号')
  })

  it('allows saving bundled runtime settings', () => {
    expect(validateSettings({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: true,
      terminalFontSize: 16,
      configuredModelProviders: [],
      autoLaunch: false,
    })).toEqual({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: true,
      terminalFontSize: 16,
      configuredModelProviders: [],
      autoLaunch: false,
      keepRuntimeInBackground: false,
      // 缺省时回落 false：与原生侧「缺键按 false」一致，老用户不会突然多出一个悬浮球。
      overlayBallEnabled: false,
    })
  })

  it('默认关闭后台保持，并保留显式开启的设置', () => {
    expect(validateSettings({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: false,
      terminalFontSize: 14,
      configuredModelProviders: [],
    }).keepRuntimeInBackground).toBe(false)
    expect(validateSettings({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: false,
      terminalFontSize: 14,
      configuredModelProviders: [],
      keepRuntimeInBackground: true,
    }).keepRuntimeInBackground).toBe(true)
    expect(validateStoredSettings({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: false,
      terminalFontSize: 14,
      keepRuntimeInBackground: true,
    }).keepRuntimeInBackground).toBe(true)
  })

  it('拒绝非布尔的后台保持设置，不做静默转换', () => {
    const invalid = {
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: false,
      terminalFontSize: 14,
      configuredModelProviders: [],
      keepRuntimeInBackground: 'true',
    }
    expect(() => validateSettings(invalid as never)).toThrow('后台保持')
    expect(() => validateStoredSettings(invalid)).toThrow('后台保持')
    expect(() => validateSettingsUpdate(invalid as never)).toThrow('后台保持')
  })

  it('rejects non-boolean screen settings instead of silently coercing them', () => {
    expect(() => validateSettings({
      manifestUrl: 'https://downloads.example.invalid/runtime.json',
      manifestSha256: 'a'.repeat(64),
      keepScreenAwake: 'true',
      terminalFontSize: 14,
      configuredModelProviders: [],
    } as unknown as Parameters<typeof validateSettings>[0])).toThrow('屏幕常亮')
  })

  it('validates bounded provider credential updates', () => {
    expect(validateSettingsUpdate({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: true,
      terminalFontSize: 14,
      configuredModelProviders: ['deepseek'],
      providerApiKeys: { openai: 'unit-test-openai-key', google: 'unit-test-gemini-key' },
      clearProviderApiKeys: ['deepseek'],
      autoLaunch: true,
      keepRuntimeInBackground: true,
    })).toEqual({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: true,
      terminalFontSize: 14,
      configuredModelProviders: ['deepseek'],
      providerApiKeys: { openai: 'unit-test-openai-key', google: 'unit-test-gemini-key' },
      clearProviderApiKeys: ['deepseek'],
      autoLaunch: true,
      keepRuntimeInBackground: true,
    })
  })

  it('设置校验保留悬浮球字段', () => {
    // validateSettings 是保存路径、validateStoredSettings 是存储读取路径（前端拿到设置的那条路），
    // 两者都显式重建对象、不展开透传：漏掉字段不会报错，只会让前端永远读不到开关值
    // （表现为设置页开关永远显示关闭）。两条路径都要锁住 true 值的透传，
    // 否则把取值写死成 false 也不会有用例变红。
    const validSettings = {
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: false,
      terminalFontSize: 14,
      configuredModelProviders: [],
    }
    expect(validateSettings({ ...validSettings, overlayBallEnabled: true }).overlayBallEnabled).toBe(true)
    expect(validateStoredSettings({ ...validSettings, overlayBallEnabled: true }).overlayBallEnabled).toBe(true)
    // 字段缺席时回落 false：与原生侧「缺键按 false」一致，
    // 老版本升级上来的用户不会突然多出一个悬浮球。
    expect(validateSettings({ ...validSettings }).overlayBallEnabled).toBe(false)
    expect(validateStoredSettings({ ...validSettings }).overlayBallEnabled).toBe(false)
    // 保存请求中省略字段表示保留；不能在校验时补 false 后关闭原生侧的开关。
    expect(validateSettingsUpdate(validSettings)).not.toHaveProperty('overlayBallEnabled')
    expect(validateSettingsUpdate({ ...validSettings, overlayBallEnabled: undefined })).not.toHaveProperty('overlayBallEnabled')
    expect(validateSettingsUpdate({ ...validSettings, overlayBallEnabled: false }).overlayBallEnabled).toBe(false)
    expect(validateSettingsUpdate({ ...validSettings, overlayBallEnabled: true }).overlayBallEnabled).toBe(true)
  })

  it('保留并校验 Harness 网页凭据的只读状态', () => {
    const stored: RuntimeSettings = {
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: false,
      terminalFontSize: 14,
      configuredModelProviders: [],
      harnessConfiguredModelProviders: ['openai'],
      configuredCustomModelProviders: [],
      harnessConfiguredCustomModelProviders: ['gateway-1'],
    }

    expect(validateSettings(stored).harnessConfiguredModelProviders).toEqual(['openai'])
    expect(validateStoredSettings(stored).harnessConfiguredCustomModelProviders).toEqual(['gateway-1'])
    expect(() => validateStoredSettings({
      ...stored,
      harnessConfiguredModelProviders: ['openai', 'openai'],
    })).toThrow('重复供应商')
    expect(() => validateStoredSettings({
      ...stored,
      harnessConfiguredCustomModelProviders: ['gateway-1', 'gateway-1'],
    })).toThrow('自定义模型凭据状态')
  })

  it('ignores retired frontend preferences and rejects invalid provider updates', () => {
    const base = {
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: true,
      terminalFontSize: 14,
      configuredModelProviders: [],
      autoLaunch: true,
      keepRuntimeInBackground: false,
      overlayBallEnabled: false,
    }
    expect(validateStoredSettings({ ...base, defaultFrontend: 'workbench' })).toEqual(base)
    expect(() => validateSettingsUpdate({ ...base, providerApiKeys: { custom: 'key' } } as never)).toThrow('供应商')
    expect(() => validateSettingsUpdate({ ...base, providerApiKeys: { openai: 'bad key' } })).toThrow('非法字符')
    expect(() => validateSettingsUpdate({
      ...base,
      providerApiKeys: { openai: 'replacement-key' },
      clearProviderApiKeys: ['openai'],
    })).toThrow('同时更新和清除')
    expect(() => validateSettings({ ...base, autoLaunch: 'true' } as never)).toThrow('自动启动')
  })

  it('enforces terminal dimensions', () => {
    expect(() => assertTerminalSize(19, 24)).toThrow('列数')
    expect(() => assertTerminalSize(80, 151)).toThrow('行数')
  })

  it('enforces terminal kinds and encoded input length', () => {
    expect(() => assertTerminalKind('host')).toThrow('类型')
    expect(() => assertBase64Input('***=', 32)).toThrow('编码')
    expect(() => assertBase64Input('YQ==', 1)).not.toThrow()
    expect(() => assertBase64Input('YWE=', 1)).toThrow('长度')
  })
})

describe('native bridge output validation', () => {
  const sessionId = '123e4567-e89b-42d3-a456-426614174000'

  it('accepts a bounded runtime state and rejects unsafe Harness URLs', () => {
    expect(validateRuntimeState({
      phase: 'running',
      architecture: 'arm64-v8a',
      installedVersion: '2026.08.1',
      updateAvailable: false,
      downloadedBytes: 100,
      totalBytes: 100,
      runnerAvailable: true,
      harnessUrl: 'http://127.0.0.1:3080/',
    }).phase).toBe('running')
    expect(() => validateRuntimeState({
      phase: 'running',
      architecture: 'arm64-v8a',
      updateAvailable: false,
      downloadedBytes: 100,
      totalBytes: 100,
      runnerAvailable: true,
      harnessUrl: 'https://example.invalid/',
    })).toThrow('本机 HTTP')
    expect(() => validateRuntimeState({
      phase: 'ready',
      architecture: 'arm64-v8a',
      downloadedBytes: 100,
      totalBytes: 100,
      runnerAvailable: true,
    })).toThrow('更新状态')
  })

  it('rejects malformed state, Shizuku, session and terminal event values', () => {
    expect(() => validateRuntimeState({ phase: 'unknown' })).toThrow()
    expect(() => validateShizukuState({ installed: true, running: true, permission: 'root', connected: false })).toThrow('权限')
    expect(() => validateShizukuState({ installed: true, running: true, permission: 'denied', connected: true })).toThrow('连接')
    expect(validateShizukuState({ installed: true, running: true, permission: 'granted', connected: true, version: '13' }).version).toBe('13')
    expect(() => validateShizukuState({ installed: true, running: true, permission: 'granted', connected: true, version: '<script>' })).toThrow('版本')
    expect(() => assertSessionId('------------------------------------')).toThrow('会话')
    expect(() => validateTerminalChunk({ sessionId, dataBase64: '***=' })).toThrow('编码')
    expect(() => validateTerminalExit({ sessionId, exitCode: 999 })).toThrow('退出码')
  })

  it('accepts local preparation progress and preserves native error codes', () => {
    expect(validateRuntimeProgress({
      phase: 'preparing',
      downloadedBytes: 50,
      totalBytes: 100,
    }).phase).toBe('preparing')
    expect(validateRuntimeProgress({
      phase: 'error',
      downloadedBytes: 50,
      totalBytes: 100,
      errorCode: 'DOWNLOAD_INCOMPLETE',
    }).errorCode).toBe('DOWNLOAD_INCOMPLETE')
  })
})

describe('后台保持状态校验', () => {
  const base = {
    keepRuntimeInBackground: true,
    foregroundServiceActive: true,
    notificationPermission: 'granted',
    deviceShellReady: false,
    reconnectRequired: false,
    lastIntent: 'running',
  }

  it('接受完整状态并保留可选的最近记录', () => {
    expect(validateKeepAliveState({
      ...base,
      lastPhase: 'running',
      lastUpdatedAtMillis: 1_700_000_000_000,
    })).toEqual({
      ...base,
      lastPhase: 'running',
      lastUpdatedAtMillis: 1_700_000_000_000,
    })
    // 从未记录时两个可选字段整体缺省，界面据此隐藏该行。
    expect(validateKeepAliveState(base)).toEqual(base)
  })

  it('拒绝非法枚举、非布尔值与负数时间', () => {
    expect(() => validateKeepAliveState({ ...base, notificationPermission: 'root' })).toThrow('通知权限')
    expect(() => validateKeepAliveState({ ...base, lastIntent: 'paused' })).toThrow('运行意图')
    expect(() => validateKeepAliveState({ ...base, lastPhase: 'sleeping' })).toThrow('运行时阶段')
    expect(() => validateKeepAliveState({ ...base, reconnectRequired: 'yes' })).toThrow('后台保持状态')
    expect(() => validateKeepAliveState({ ...base, lastUpdatedAtMillis: -1 })).toThrow('更新时间')
    expect(() => validateKeepAliveState(null)).toThrow('后台保持状态')
  })

  it('只接受布尔型的通知权限结果', () => {
    expect(validateNotificationPermissionResult({ granted: true, supported: true })).toEqual({ granted: true, supported: true })
    expect(validateNotificationPermissionResult({ granted: false, supported: false })).toEqual({ granted: false, supported: false })
    expect(() => validateNotificationPermissionResult({ granted: 'yes', supported: true })).toThrow('通知权限结果')
    expect(() => validateNotificationPermissionResult({ granted: true })).toThrow('通知权限结果')
  })
})

describe('诊断日志校验', () => {
  const base = {
    enabled: false,
    retentionDays: 3,
    fileCount: 0,
    totalBytes: 0,
    lastEntryAtMillis: 0,
  }

  it('只接受开关、计数与时间戳', () => {
    expect(validateDiagnosticLogState(base)).toEqual(base)
    expect(validateDiagnosticLogState({ ...base, enabled: true, fileCount: 2, totalBytes: 4096, lastEntryAtMillis: 1_700_000_000_000 }))
      .toEqual({ ...base, enabled: true, fileCount: 2, totalBytes: 4096, lastEntryAtMillis: 1_700_000_000_000 })
  })

  it('拒绝越界保留天数与非计数值', () => {
    expect(() => validateDiagnosticLogState({ ...base, retentionDays: 0 })).toThrow('保留天数')
    expect(() => validateDiagnosticLogState({ ...base, retentionDays: 31 })).toThrow('保留天数')
    expect(() => validateDiagnosticLogState({ ...base, retentionDays: 3.5 })).toThrow('保留天数')
    expect(() => validateDiagnosticLogState({ ...base, fileCount: -1 })).toThrow('文件数')
    expect(() => validateDiagnosticLogState({ ...base, totalBytes: '1024' })).toThrow('总字节数')
    expect(() => validateDiagnosticLogState({ ...base, enabled: 'yes' })).toThrow('诊断日志状态')
    expect(() => validateDiagnosticLogState(null)).toThrow('诊断日志状态')
  })

  it('保留天数边界与原生侧一致', () => {
    expect(assertDiagnosticRetentionDays(DIAGNOSTIC_RETENTION_MIN)).toBe(DIAGNOSTIC_RETENTION_MIN)
    expect(assertDiagnosticRetentionDays(DIAGNOSTIC_RETENTION_MAX)).toBe(DIAGNOSTIC_RETENTION_MAX)
    expect(() => assertDiagnosticRetentionDays(DIAGNOSTIC_RETENTION_MAX + 1)).toThrow('保留天数')
    expect(() => assertDiagnosticRetentionDays(DIAGNOSTIC_RETENTION_MIN - 1)).toThrow('保留天数')
  })

  it('导出结果必须带有安全的文件名', () => {
    expect(validateDiagnosticLogExport({
      ...base,
      enabled: true,
      fileCount: 1,
      fileName: 'dsh-diagnostic-20260912-102030.txt',
      exportedBytes: 512,
    })).toEqual({
      ...base,
      enabled: true,
      fileCount: 1,
      fileName: 'dsh-diagnostic-20260912-102030.txt',
      exportedBytes: 512,
    })
    // 文件名不得包含路径分隔符或任何自由文本。
    expect(() => validateDiagnosticLogExport({ ...base, fileName: '../../etc/passwd' })).toThrow('文件名')
    expect(() => validateDiagnosticLogExport({ ...base, fileName: 'a b.txt' })).toThrow('文件名')
    expect(() => validateDiagnosticLogExport({ ...base, fileName: '' })).toThrow('文件名')
    expect(() => validateDiagnosticLogExport({ ...base, fileName: 'ok.txt' })).toThrow('导出字节数')
  })
})

describe('运行日志校验', () => {
  const window = HARNESS_LOG_WINDOW_OPTIONS[0]

  it('接受可用的尾部文本与不可用时的空内容', () => {
    const text = 'Error: tool call failed\n    at run (dsh.js:1:1)'
    expect(validateHarnessLog({ available: true, text, maxBytes: window })).toEqual({ available: true, text, maxBytes: window })
    // 运行时不持有 Harness 输出时如实返回不可用，且 text 为空串。
    expect(validateHarnessLog({ available: false, text: '', maxBytes: window }))
      .toEqual({ available: false, text: '', maxBytes: window })
  })

  it('拒绝类型错误与自相矛盾的载荷', () => {
    expect(() => validateHarnessLog(null)).toThrow('运行日志')
    expect(() => validateHarnessLog({ available: 'yes', text: '', maxBytes: window })).toThrow('可用状态')
    expect(() => validateHarnessLog({ available: true, text: 42, maxBytes: window })).toThrow('内容格式')
    // 「不可用却带内容」是异常载荷：不接受，避免界面按 available 判定后又渲染出文本。
    expect(() => validateHarnessLog({ available: false, text: '不该出现的内容', maxBytes: window })).toThrow('不一致')
    // 窗口只接受受控档位：否则界面会按一个缓冲区里根本不存在的窗口去解释内容。
    expect(() => validateHarnessLog({ available: true, text: 'x', maxBytes: 12345 })).toThrow('窗口')
    expect(() => validateHarnessLog({ available: true, text: 'x' })).toThrow('窗口')
  })

  it('拒绝超长文本，边界值按字符数放行', () => {
    expect(validateHarnessLog({ available: true, text: 'x'.repeat(HARNESS_LOG_MAX_CHARS), maxBytes: window }).text)
      .toHaveLength(HARNESS_LOG_MAX_CHARS)
    expect(() => validateHarnessLog({ available: true, text: 'x'.repeat(HARNESS_LOG_MAX_CHARS + 1), maxBytes: window }))
      .toThrow('长度')
  })

  it('三档窗口都接受，最大档也能通过校验', () => {
    HARNESS_LOG_WINDOW_OPTIONS.forEach(bytes => {
      expect(validateHarnessLog({ available: true, text: 'x', maxBytes: bytes }).maxBytes).toBe(bytes)
    })
  })
})

describe('诊断日志正文校验', () => {
  const payload = {
    text: '2026-09-12T10:21:04Z|WARN|MODULE_GRAPH|result=failed|count=2|files=4\n',
    maxBytes: DIAGNOSTIC_LOG_WINDOW_OPTIONS[0],
    totalBytes: 4096,
    truncated: true,
  }

  it('接受受控字段组成的正文与计数', () => {
    expect(validateDiagnosticLogText(payload)).toEqual(payload)
    expect(validateDiagnosticLogText({ ...payload, text: '', truncated: false }).text).toBe('')
  })

  it('拒绝异常形态与未知窗口', () => {
    expect(() => validateDiagnosticLogText(null)).toThrow('诊断日志内容')
    expect(() => validateDiagnosticLogText({ ...payload, text: 42 })).toThrow('内容格式')
    expect(() => validateDiagnosticLogText({ ...payload, truncated: 'yes' })).toThrow('截断状态')
    expect(() => validateDiagnosticLogText({ ...payload, maxBytes: 8192 })).toThrow('窗口')
    expect(() => validateDiagnosticLogText({ ...payload, totalBytes: -1 })).toThrow('总字节数')
    expect(() => validateDiagnosticLogText({ ...payload, text: 'x'.repeat(DIAGNOSTIC_LOG_MAX_CHARS + 1) }))
      .toThrow('长度')
  })
})

describe('悬浮球状态校验', () => {
  it('接受合法的悬浮球状态', () => {
    const state = validateOverlayBallState({
      enabled: true,
      canDrawOverlays: true,
      serviceActive: true,
    })
    expect(state).toEqual({ enabled: true, canDrawOverlays: true, serviceActive: true })
  })

  it('拒绝非布尔字段', () => {
    // 原生返回值不可信：字段缺失或类型不符时必须抛错，而不是静默降级成 false，
    // 否则界面会把「读取失败」显示成「开关是关的」，用户点了没反应也不知道为什么。
    expect(() => validateOverlayBallState({ enabled: 'true', canDrawOverlays: true, serviceActive: true }))
      .toThrow()
    expect(() => validateOverlayBallState({ enabled: true, canDrawOverlays: 1, serviceActive: false }))
      .toThrow()
    expect(() => validateOverlayBallState({ enabled: true, canDrawOverlays: true }))
      .toThrow()
  })
})

const mailboxState = {
  availability: 'available',
  level: 'T2',
  available: true,
  supported: true,
  granted: true,
  inboxPath: '/storage/emulated/0/Documents/DSH/inbox',
  outboxPath: '/storage/emulated/0/Documents/DSH/outbox',
  guestInboxPath: '/mnt/inbox',
  guestOutboxPath: '/mnt/outbox',
  inboxFileCount: 2,
  inboxTars: [{ name: 'dsh-workspace.tar', bytes: 2048 }],
  exportTarName: 'dsh-workspace.tar',
  exportManifestName: 'dsh-workspace.manifest.json',
  importDirectory: 'mailbox-import',
}

describe('投递区状态校验', () => {
  it('接受合法的投递区状态', () => {
    expect(validateMailboxState(mailboxState)).toEqual(mailboxState)
    expect(validateMailboxState({ ...mailboxState, availability: 'needsPermission', level: 'T0', available: false }).available)
      .toBe(false)
  })

  it('拒绝未知档位、缺失字段与相对路径', () => {
    // 未知档位不能回落到「可用」：把读不懂的状态显示成可用，用户点了才发现不可用。
    expect(() => validateMailboxState({ ...mailboxState, availability: 'maybe' })).toThrow('投递区可用性格式无效')
    expect(() => validateMailboxState({ ...mailboxState, level: 'T3' })).toThrow('投递区权限档位格式无效')
    expect(() => validateMailboxState({ ...mailboxState, inboxPath: 'Documents/DSH/inbox' })).toThrow()
    expect(() => validateMailboxState({ ...mailboxState, exportTarName: 'sub/dsh.tar' })).toThrow()
    expect(() => validateMailboxState({ ...mailboxState, inboxFileCount: -1 })).toThrow()
    expect(() => validateMailboxState({ ...mailboxState, inboxTars: new Array(6).fill({ name: 'a.tar', bytes: 1 }) }))
      .toThrow('投递区 tar 列表格式无效')
    const missing: Record<string, unknown> = { ...mailboxState }
    delete missing.guestOutboxPath
    expect(() => validateMailboxState(missing)).toThrow()
  })

  it('拒绝自相矛盾的可用性', () => {
    // available=true 但档位是「需要授权」：界面会出现「按钮可点 + 文案说没权限」的矛盾状态。
    expect(() => validateMailboxState({ ...mailboxState, availability: 'needsPermission' }))
      .toThrow('投递区状态自相矛盾')
    expect(() => validateMailboxState({ ...mailboxState, available: false }))
      .toThrow('投递区状态自相矛盾')
  })
})

describe('投递区结果校验', () => {
  it('接受合法的导入与导出结果', () => {
    expect(validateMailboxImportResult({
      entryCount: 3,
      fileCount: 2,
      directoryCount: 1,
      symlinkCount: 0,
      hardlinkCount: 0,
      bytes: 2048,
      tarName: 'dsh-workspace.tar',
      tarBytes: 4096,
      verified: true,
      manifestName: 'dsh-workspace.manifest.json',
      ignoredFiles: 0,
      target: 'mailbox-import',
    }).verified).toBe(true)

    // 没有 manifest 时不补默认文件名：字段缺失就是「未附带」。
    expect(validateMailboxImportResult({
      entryCount: 1,
      fileCount: 1,
      directoryCount: 0,
      symlinkCount: 0,
      hardlinkCount: 0,
      bytes: 4,
      tarName: 'loose.tar',
      tarBytes: 10240,
      verified: false,
      ignoredFiles: 0,
      target: 'mailbox-import',
    }).manifestName).toBeUndefined()

    expect(validateMailboxExportResult({
      entryCount: 5,
      bytes: 8192,
      tarName: 'dsh-workspace.tar',
      tarBytes: 10240,
      tarSha256: 'b'.repeat(64),
      manifestName: 'dsh-workspace.manifest.json',
      skippedLinks: 1,
      skippedSpecial: 0,
    }).skippedLinks).toBe(1)
  })

  it('拒绝负数计数、非法摘要与绝对路径文件名', () => {
    expect(() => validateMailboxImportResult({
      entryCount: -1, fileCount: 0, directoryCount: 0, symlinkCount: 0, hardlinkCount: 0,
      bytes: 0, tarName: 'a.tar', tarBytes: 1, verified: false, ignoredFiles: 0, target: 'mailbox-import',
    })).toThrow()
    expect(() => validateMailboxExportResult({
      entryCount: 1, bytes: 1, tarName: 'a.tar', tarBytes: 1, tarSha256: 'B'.repeat(64),
      manifestName: 'a.json', skippedLinks: 0, skippedSpecial: 0,
    })).toThrow('投递区导出摘要格式无效')
    expect(() => validateMailboxExportResult({
      entryCount: 1, bytes: 1, tarName: '/etc/passwd', tarBytes: 1, tarSha256: 'b'.repeat(64),
      manifestName: 'a.json', skippedLinks: 0, skippedSpecial: 0,
    })).toThrow()
  })
})

describe('存储访问校验', () => {
  it('接受合法载荷并拒绝缺失字段', () => {
    expect(validateStorageAccessState({ mediaGranted: false, allFilesGranted: true, allFilesSupported: true, sdkInt: 34 }))
      .toEqual({ mediaGranted: false, allFilesGranted: true, allFilesSupported: true, sdkInt: 34 })
    // allFilesSupported 缺失时不能补成 false：那会把 Android 14 显示成「系统不支持」。
    expect(() => validateStorageAccessState({ mediaGranted: false, allFilesGranted: true, sdkInt: 34 })).toThrow()
    expect(() => validateStorageAccessState({ mediaGranted: false, allFilesGranted: true, allFilesSupported: true, sdkInt: -1 }))
      .toThrow()
    expect(validateMediaPermissionResult({ granted: true })).toEqual({ granted: true })
    expect(() => validateMediaPermissionResult({ granted: 'true' })).toThrow()
    expect(validateAllFilesAccessResult({ supported: false, granted: false }))
      .toEqual({ supported: false, granted: false })
    expect(() => validateAllFilesAccessResult({ supported: true })).toThrow()
  })
})

describe('投递区导出起点判定', () => {
  it('省略与空白等价于整个工作区', () => {
    expect(assertMailboxSubdirectory(undefined)).toBeUndefined()
    expect(assertMailboxSubdirectory('')).toBeUndefined()
    expect(assertMailboxSubdirectory('   ')).toBeUndefined()
    expect(assertMailboxSubdirectory(' proj/src ')).toBe('proj/src')
  })

  it('拒绝绝对路径与越界分段', () => {
    for (const value of ['/abs', '../outside', 'proj/../../outside', 'proj/./src', 'proj//src', 'a\\b']) {
      expect(() => assertMailboxSubdirectory(value), value).toThrow('投递区导出起点格式无效')
    }
  })
})

describe('存储目录白名单校验', () => {
  const entry = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
    index: 1,
    path: '/storage/emulated/0/Download',
    displayName: 'Download',
    guestPath: '/mnt/user/1',
    availability: 'available',
    level: 'T2',
    available: true,
    ...overrides,
  })

  const state = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
    entries: [entry()],
    maxDirectories: 8,
    count: 1,
    supported: true,
    granted: true,
    level: 'T2',
    active: true,
    ...overrides,
  })

  it('接受合法载荷并保留受控字段', () => {
    expect(validateStorageDirsState(state())).toEqual({
      entries: [{
        index: 1,
        path: '/storage/emulated/0/Download',
        displayName: 'Download',
        guestPath: '/mnt/user/1',
        availability: 'available',
        level: 'T2',
        available: true,
        reasonCode: undefined,
      }],
      maxDirectories: 8,
      count: 1,
      supported: true,
      granted: true,
      level: 'T2',
      active: true,
    })
  })

  it('接受不可用条目并保留受控错误码', () => {
    const value = validateStorageDirsState(state({
      entries: [entry({
        availability: 'unavailable',
        level: 'T0',
        available: false,
        reasonCode: 'STORAGE_DIR_NOT_A_DIRECTORY',
      })],
      active: false,
    }))
    expect(value.entries[0].availability).toBe('unavailable')
    expect(value.entries[0].reasonCode).toBe('STORAGE_DIR_NOT_A_DIRECTORY')
    expect(value.active).toBe(false)
  })

  it('接受空白名单与 T0 档', () => {
    const value = validateStorageDirsState({
      entries: [],
      maxDirectories: 8,
      count: 0,
      supported: true,
      granted: false,
      level: 'T0',
      active: false,
    })
    expect(value.entries).toEqual([])
    expect(value.level).toBe('T0')
  })

  it('拒绝自相矛盾的载荷', () => {
    // count 与条目数不符、available 与 availability 不符、level 与权限不符、active 与实际不符。
    expect(() => validateStorageDirsState(state({ count: 0 }))).toThrow()
    expect(() => validateStorageDirsState(state({ entries: [entry({ available: false })] }))).toThrow()
    expect(() => validateStorageDirsState(state({ level: 'T0' }))).toThrow()
    expect(() => validateStorageDirsState(state({ active: false }))).toThrow()
    expect(() => validateStorageDirsState(state({ granted: false }))).toThrow()
    expect(() => validateStorageDirsState(state({ supported: false, granted: true }))).toThrow()
  })

  it('拒绝未知档位与越界的序号、挂载点', () => {
    expect(() => validateStorageDirsState(state({ entries: [entry({ availability: 'ok' })] }))).toThrow()
    // 序号必须等于持久化顺序（第 n 条的序号是 n）：重排会让 /mnt/user/<序号> 指向别的目录。
    expect(() => validateStorageDirsState(state({ entries: [entry({ index: 2 })] }))).toThrow()
    expect(() => validateStorageDirsState(state({ entries: [entry({ guestPath: '/mnt/user/2' })] }))).toThrow()
    expect(() => validateStorageDirsState(state({ count: 1, entries: [entry(), entry({ index: 2 })] }))).toThrow()
  })

  it('拒绝越出共享存储的路径与非法错误码', () => {
    for (const path of [
      '/data/data/io.deepseekharness.mobile/files',
      '/sdcard/Download',
      '/storage/emulated/0/../Download',
      '/storage/emulated/0/',
      'storage/emulated/0/Download',
    ]) {
      expect(() => validateStorageDirsState(state({ entries: [entry({ path })] })), path).toThrow()
    }
    expect(() => validateStorageDirsState(state({ entries: [entry({ reasonCode: 'lowercase_code' })] }))).toThrow()
    // 可用条目不该带错误码：那是自相矛盾的状态。
    expect(() => validateStorageDirsState(state({ entries: [entry({ reasonCode: 'STORAGE_DIR_UNREADABLE' })] })))
      .toThrow()
  })

  it('拒绝把别的东西冒充成白名单', () => {
    expect(() => validateStorageDirsState(null)).toThrow()
    expect(() => validateStorageDirsState(state({ maxDirectories: 9 }))).toThrow()
    expect(() => validateStorageDirsState(state({ entries: [entry({ displayName: '' })] }))).toThrow()
    expect(() => validateStorageDirsState(state({ entries: [entry({ displayName: 'a/b' })] }))).toThrow()
  })

  it('移除入参只接受共享存储下的绝对路径', () => {
    expect(assertStorageDirPath('/storage/emulated/0/Download')).toBe('/storage/emulated/0/Download')
    for (const path of ['', 'Download', '/sdcard/Download', '/storage/emulated/0/', '/storage/emulated/0/a/../b']) {
      expect(() => assertStorageDirPath(path), path).toThrow('存储目录路径格式无效')
    }
  })
})

describe('运行时版本校验', () => {
  const runtimeId = 'ubuntu-24.04-arm64-deepseek-harness'
  const state = (overrides: Record<string, unknown> = {}) => ({
    versions: [
      { slot: 'current', version: '2026.08.17', dshVersion: '0.1.5-rc.2', runtimeId, extractedBytes: 640 * 1024 * 1024, active: true },
      { slot: 'previous', version: '2026.09.01', dshVersion: '0.1.7-rc.2', runtimeId, extractedBytes: 660 * 1024 * 1024, active: false },
      { slot: 'bundled', version: '2026.09.15', runtimeId, extractedBytes: 672 * 1024 * 1024, active: false },
    ],
    canSwitch: true,
    canDelete: true,
    ...overrides,
  })

  it('接受三槽状态，并允许缺少 dsh 版本', () => {
    expect(validateRuntimeVersions(state()).versions).toHaveLength(3)
    // 内置版本只有清单声明，没有解压目录就读不到 dsh 版本：这是正常状态，不是错误。
    expect(validateRuntimeVersions(state()).versions[2].dshVersion).toBeUndefined()
  })

  it('拒绝未知槽位、非法版本号与凭空多出来的槽', () => {
    expect(() => validateRuntimeVersions(state({ versions: [{ ...state().versions[0], slot: 'retained' }] })))
      .toThrow('运行时版本槽位格式无效')
    expect(() => validateRuntimeVersions(state({ versions: [{ ...state().versions[0], version: 'v 1' }] })))
      .toThrow('运行时版本号格式无效')
    expect(() => validateRuntimeVersions(state({ versions: [{ ...state().versions[0], runtimeId: '' }] })))
      .toThrow('运行时标识格式无效')
    expect(() => validateRuntimeVersions(state({ versions: [{ ...state().versions[0], extractedBytes: -1 }] })))
      .toThrow('运行时体积格式无效')
    expect(() => validateRuntimeVersions(state({ versions: [{ ...state().versions[0], dshVersion: 'not a version' }] })))
      .toThrow('dsh 版本格式无效')
    expect(() => validateRuntimeVersions(state({ versions: [{ ...state().versions[0], active: 'true' }] })))
      .toThrow('运行时版本使用状态格式无效')
    // 槽位最多三个（当前 / 上一版本 / 内置）：多出来的条目说明原生侧回了一份读不懂的状态。
    expect(() => validateRuntimeVersions(state({ versions: new Array(4).fill(state().versions[0]) })))
      .toThrow('运行时版本列表格式无效')
  })

  it('拒绝缺失或类型不符的整体状态', () => {
    expect(() => validateRuntimeVersions(null)).toThrow('运行时版本状态格式无效')
    expect(() => validateRuntimeVersions(state({ versions: 'three' }))).toThrow('运行时版本列表格式无效')
    expect(() => validateRuntimeVersions(state({ canSwitch: 1 }))).toThrow('运行时版本切换状态格式无效')
    expect(() => validateRuntimeVersions(state({ canDelete: 'yes' }))).toThrow('运行时版本删除状态格式无效')
  })

  it('操作目标只认「上一版本」', () => {
    expect(assertRuntimeVersionTarget('previous')).toBe('previous')
    for (const target of ['current', 'bundled', 'retained', '', null, 7]) {
      expect(() => assertRuntimeVersionTarget(target), String(target)).toThrow('运行时版本操作目标无效')
    }
  })
})
