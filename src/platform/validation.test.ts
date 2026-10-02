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
  validateAppUpdateState,
  validateAccessibilityAutomationState,
  validateAccessibilityPasswordInput,
  validateDiagnosticLogExport,
  validateDiagnosticLogState,
  validateDiagnosticLogText,
  validateHarnessLog,
  validateKeepAliveState,
  validateMailboxExportResult,
  validateMailboxDirectoryState,
  validateMailboxImportResult,
  validateMailboxState,
  validateMediaPermissionResult,
  validateNotificationPermissionResult,
  validateOverlayBallState,
  validateRuntimeInstallResult,
  validateRuntimeProgress,
  validateRuntimeReleaseList,
  validateRuntimeSessionSnapshotRestoreResult,
  validateRuntimeSessionSnapshotState,
  validateRuntimeSource,
  validateRuntimeState,
  validateRuntimeVersions,
  validateSettings,
  validateSettingsUpdate,
  validateShizukuState,
  validateStorageAccessState,
  validateStorageDirectoryState,
  validateStorageDirsState,
  validateStoredSettings,
  validateTerminalChunk,
  validateTerminalExit,
} from './validation'
import {
  ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES,
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

  it('对话完成信号：时间戳与序号成对保留，缺省时不写回', () => {
    // 这两项是「任务完成 → 回到对话界面」的唯一依据：时间戳给用户看，序号用来判断"有没有新的一轮结束"。
    expect(validateKeepAliveState({
      ...base,
      lastTurnCompletedAtMillis: 1_700_000_000_000,
      turnCompletionSequence: 7,
    })).toEqual({
      ...base,
      lastTurnCompletedAtMillis: 1_700_000_000_000,
      turnCompletionSequence: 7,
    })
    // 从未收到时（旧原生、预览实现）整体缺省：外壳按 0 处理，不得当成"刚完成"。
    expect(validateKeepAliveState(base)).toEqual(base)
    expect(validateKeepAliveState({ ...base, turnCompletionSequence: 0 })).toEqual({ ...base, turnCompletionSequence: 0 })
  })

  it('拒绝非整数或负数的对话完成信号', () => {
    expect(() => validateKeepAliveState({ ...base, lastTurnCompletedAtMillis: -1 })).toThrow('对话完成时间')
    expect(() => validateKeepAliveState({ ...base, lastTurnCompletedAtMillis: 1.5 })).toThrow('对话完成时间')
    expect(() => validateKeepAliveState({ ...base, lastTurnCompletedAtMillis: '1700000000000' })).toThrow('对话完成时间')
    expect(() => validateKeepAliveState({ ...base, turnCompletionSequence: -1 })).toThrow('对话完成序号')
    expect(() => validateKeepAliveState({ ...base, turnCompletionSequence: 2.5 })).toThrow('对话完成序号')
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

  it('校验目录快照、相对路径和条目类型', () => {
    const snapshot = {
      root: 'outbox',
      path: 'exports/weekly',
      entries: [
        { name: 'reports', kind: 'directory', bytes: 0 },
        { name: 'result.tar', kind: 'file', bytes: 12 },
      ],
      truncated: false,
    }
    expect(validateMailboxDirectoryState(snapshot)).toEqual(snapshot)
    expect(() => validateMailboxDirectoryState({ ...snapshot, root: '/storage/emulated/0' })).toThrow()
    expect(() => validateMailboxDirectoryState({ ...snapshot, path: '../outside' })).toThrow()
    expect(() => validateMailboxDirectoryState({ ...snapshot, entries: [{ name: 'a', kind: 'symlink', bytes: 0 }] })).toThrow()
    expect(() => validateMailboxDirectoryState({ ...snapshot, truncated: 'false' })).toThrow()
  })

  it('校验共享目录快照、访客路径与条目类型', () => {
    const snapshot = {
      guestPath: '/mnt/user/2',
      path: 'projects/demo',
      entries: [
        { name: 'src', kind: 'directory', bytes: 0 },
        { name: 'notes.md', kind: 'file', bytes: 42 },
      ],
      truncated: false,
    }
    expect(validateStorageDirectoryState(snapshot)).toEqual(snapshot)
    // 根目录：原生回传的是 path: null，前端统一收敛成 undefined（与投递区同一口径）。
    expect(validateStorageDirectoryState({ ...snapshot, path: null, truncated: true }))
      .toEqual({ ...snapshot, path: undefined, truncated: true })
    // 序号从 1 起且没有前导零：以下写法都指不到白名单里的任何一条目录。
    const invalidGuestPaths: unknown[] = [
      '/mnt/user', '/mnt/user/', '/mnt/user/0', '/mnt/user/1/', '/mnt/user/01',
      '/mnt/user/+1', '/mnt/user/1/extra', '/mnt/user/1 ', '/storage/emulated/0', 1, null, undefined,
    ]
    for (const guestPath of invalidGuestPaths) {
      expect(() => validateStorageDirectoryState({ ...snapshot, guestPath })).toThrow()
    }
    expect(() => validateStorageDirectoryState({ ...snapshot, path: '../outside' })).toThrow()
    expect(() => validateStorageDirectoryState({ ...snapshot, path: '/etc' })).toThrow()
    expect(() => validateStorageDirectoryState({ ...snapshot, entries: [{ name: 'a', kind: 'symlink', bytes: 0 }] })).toThrow()
    // 条目数上限与投递区同一个常量（256）；超一条即视为载荷不符合契约。
    expect(() => validateStorageDirectoryState({
      ...snapshot,
      entries: Array.from({ length: 257 }, (_, index) => ({ name: `f${index}`, kind: 'file', bytes: 0 })),
    })).toThrow()
    expect(() => validateStorageDirectoryState({ ...snapshot, truncated: 'false' })).toThrow()
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

describe('无障碍自动化状态与验证密码校验', () => {
  const state = (overrides: Record<string, unknown> = {}) => ({
    enabled: true,
    allowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, 'com.example.target'],
    alwaysAllowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES],
    passwordConfigured: false,
    ...overrides,
  })

  it('合法状态原样通过（自动项与密码标记都被保留）', () => {
    const result = validateAccessibilityAutomationState(state())
    expect(result.enabled).toBe(true)
    expect(result.allowedPackages).toEqual([...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, 'com.example.target'])
    expect(result.alwaysAllowedPackages).toEqual([...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES])
    expect(result.passwordConfigured).toBe(false)
    // 已设密码的设备：只有这个布尔为 true，返回体里没有任何密码字段。
    expect(validateAccessibilityAutomationState(state({ passwordConfigured: true })).passwordConfigured).toBe(true)
    expect(Object.keys(validateAccessibilityAutomationState(state({ passwordConfigured: true }))).sort())
      .toEqual(['allowedPackages', 'alwaysAllowedPackages', 'enabled', 'passwordConfigured'])
  })

  it('缺 alwaysAllowedPackages 或 passwordConfigured 时抛错', () => {
    expect(() => validateAccessibilityAutomationState({
      enabled: false,
      allowedPackages: [],
      passwordConfigured: false,
    })).toThrow('无障碍自动白名单格式无效')
    expect(() => validateAccessibilityAutomationState({
      enabled: false,
      allowedPackages: [],
      alwaysAllowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES],
    })).toThrow('无障碍验证密码状态格式无效')
    expect(() => validateAccessibilityAutomationState(state({ passwordConfigured: 'true' })))
      .toThrow('无障碍验证密码状态格式无效')
    expect(() => validateAccessibilityAutomationState(state({ alwaysAllowedPackages: 'io.deepseekharness.mobile' })))
      .toThrow('无障碍自动白名单格式无效')
  })

  it('自动白名单拒绝空串、超 16 条与非数组', () => {
    expect(() => validateAccessibilityAutomationState(state({ alwaysAllowedPackages: [''] })))
      .toThrow('无障碍白名单第 1 项无效')
    expect(() => validateAccessibilityAutomationState(state({ alwaysAllowedPackages: ['com.example.'] })))
      .toThrow('无障碍白名单第 1 项无效')
    expect(() => validateAccessibilityAutomationState(state({ alwaysAllowedPackages: [' com.example.app'] })))
      .toThrow('无障碍白名单第 1 项无效')
    expect(() => validateAccessibilityAutomationState(state({ alwaysAllowedPackages: null })))
      .toThrow('无障碍自动白名单格式无效')
    const seventeen = Array.from({ length: 17 }, (_, index) => `com.example.app${index}`)
    expect(() => validateAccessibilityAutomationState(state({ alwaysAllowedPackages: seventeen })))
      .toThrow('无障碍自动白名单最多 16 项')
    expect(() => validateAccessibilityAutomationState(state({ alwaysAllowedPackages: ['com.example.app', 'com.example.app'] })))
      .toThrow('无障碍自动白名单包含重复项')
    // 16 条正好是上限，必须放过。
    expect(validateAccessibilityAutomationState(state({ alwaysAllowedPackages: seventeen.slice(0, 16) })).alwaysAllowedPackages)
      .toHaveLength(16)
  })

  it('验证密码输入：5 种边界拒绝、6 位与 64 位通过', () => {
    expect(validateAccessibilityPasswordInput('123456', '验证密码')).toBe('123456')
    expect(validateAccessibilityPasswordInput('a'.repeat(64), '验证密码')).toHaveLength(64)
    // 太短 / 太长。
    expect(() => validateAccessibilityPasswordInput('12345', '验证密码')).toThrow('验证密码需要 6 到 64 个字符')
    expect(() => validateAccessibilityPasswordInput('a'.repeat(65), '验证密码')).toThrow('验证密码需要 6 到 64 个字符')
    // 全空白 / 首尾带空白 / 含控制字符。
    expect(() => validateAccessibilityPasswordInput('      ', '验证密码')).toThrow('验证密码不能全是空白')
    expect(() => validateAccessibilityPasswordInput(' 123456', '验证密码')).toThrow('验证密码首尾不能有空白')
    expect(() => validateAccessibilityPasswordInput('12345\n6', '验证密码')).toThrow('验证密码包含不可用字符')
    expect(() => validateAccessibilityPasswordInput('12345\u007f6', '验证密码')).toThrow('验证密码包含不可用字符')
    // 类型不符与错误文案：文案里不出现用户输入的那串字符。
    expect(() => validateAccessibilityPasswordInput(123456, '验证密码')).toThrow('验证密码格式无效')
    expect(() => validateAccessibilityPasswordInput(undefined, '验证密码')).toThrow('验证密码格式无效')
    try {
      validateAccessibilityPasswordInput(' 123456', '验证密码')
      throw new Error('应当抛错')
    } catch (error) {
      expect((error as Error).message).not.toContain('123456')
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

describe('运行时可用版本校验', () => {
  const sha256 = 'a'.repeat(64)
  const manifestUrl = 'https://example.com/runtime.json'
  const list = (overrides: Record<string, unknown> = {}) => ({
    entries: [
      { version: '0.2.0-mobile-308', dshVersion: '0.1.5-rc.2', manifestUrl, manifestSha256: sha256 },
      { version: '0.1.9-mobile-300' },
    ],
    ...overrides,
  })

  it('接受带清单与只可展示的条目，并允许缺少内置 dsh 版本', () => {
    const parsed = validateRuntimeReleaseList(list())

    expect(parsed.entries).toHaveLength(2)
    expect(parsed.entries[0]).toEqual({
      version: '0.2.0-mobile-308',
      dshVersion: '0.1.5-rc.2',
      manifestUrl,
      manifestSha256: sha256,
    })
    // 没有清单的版本只可展示：解析结果里不该凭空长出地址与摘要，也不该多出 dshVersion。
    expect(parsed.entries[1]).toEqual({ version: '0.1.9-mobile-300' })
  })

  it('清单地址与摘要必须同时出现或同时缺失', () => {
    expect(() => validateRuntimeReleaseList(list({ entries: [{ version: '0.2.0', manifestUrl }] })))
      .toThrow('运行时版本清单地址与 SHA-256 必须同时出现或同时缺失')
    expect(() => validateRuntimeReleaseList(list({ entries: [{ version: '0.2.0', manifestSha256: sha256 }] })))
      .toThrow('运行时版本清单地址与 SHA-256 必须同时出现或同时缺失')
  })

  it('清单地址只认 https，长度上限 1024', () => {
    const base = { version: '0.2.0', manifestSha256: sha256 }
    for (const url of ['http://example.com/runtime.json', 'example.com/runtime.json', 'ftp://example.com/runtime.json']) {
      expect(() => validateRuntimeReleaseList(list({ entries: [{ ...base, manifestUrl: url }] })), url)
        .toThrow('运行时版本清单地址格式无效')
    }
    // 正好 1024 字符要放过，多一个字符就拒绝。
    const prefix = 'https://example.com/'
    expect(validateRuntimeReleaseList(list({ entries: [{ ...base, manifestUrl: prefix + 'a'.repeat(1024 - prefix.length) }] })).entries)
      .toHaveLength(1)
    expect(() => validateRuntimeReleaseList(list({ entries: [{ ...base, manifestUrl: prefix + 'a'.repeat(1025 - prefix.length) }] })))
      .toThrow('运行时版本清单地址格式无效')
    expect(() => validateRuntimeReleaseList(list({ entries: [{ ...base, manifestUrl: 7 }] })))
      .toThrow('运行时版本清单地址格式无效')
  })

  it('清单摘要必须是 64 位小写十六进制', () => {
    const base = { version: '0.2.0', manifestUrl }
    for (const value of [sha256.slice(0, 63), sha256.toUpperCase(), `${sha256}a`, 'A'.repeat(64), '', 7, null]) {
      expect(() => validateRuntimeReleaseList(list({ entries: [{ ...base, manifestSha256: value }] })), String(value))
        .toThrow('运行时版本清单 SHA-256 必须是 64 位小写十六进制')
    }
  })

  it('版本号与内置 dsh 版本都走标识符规则', () => {
    expect(() => validateRuntimeReleaseList(list({ entries: [{ version: '0.2.0 mobile 308' }] })))
      .toThrow('运行时可用版本号格式无效')
    expect(() => validateRuntimeReleaseList(list({ entries: [{ version: '' }] })))
      .toThrow('运行时可用版本号格式无效')
    expect(() => validateRuntimeReleaseList(list({ entries: [{ version: `v${'1'.repeat(96)}` }] })))
      .toThrow('运行时可用版本号格式无效')
    expect(() => validateRuntimeReleaseList(list({ entries: [{ version: '0.2.0', dshVersion: '0.1.5 rc.2' }] })))
      .toThrow('运行时内置 dsh 版本格式无效')
    expect(() => validateRuntimeReleaseList(list({ entries: [{ version: '0.2.0', dshVersion: 7 }] })))
      .toThrow('运行时内置 dsh 版本格式无效')
  })

  it('列表最多 40 条，且拒绝类型不符的整体载荷', () => {
    const entry = { version: '0.2.0-mobile-308' }
    expect(validateRuntimeReleaseList(list({ entries: new Array(40).fill(entry) })).entries).toHaveLength(40)
    expect(() => validateRuntimeReleaseList(list({ entries: new Array(41).fill(entry) })))
      .toThrow('运行时可用版本列表格式无效')
    expect(() => validateRuntimeReleaseList(null)).toThrow('运行时可用版本状态格式无效')
    expect(() => validateRuntimeReleaseList([])).toThrow('运行时可用版本状态格式无效')
    expect(() => validateRuntimeReleaseList({})).toThrow('运行时可用版本列表格式无效')
    expect(() => validateRuntimeReleaseList(list({ entries: 'two' }))).toThrow('运行时可用版本列表格式无效')
    expect(() => validateRuntimeReleaseList(list({ entries: [null] }))).toThrow('运行时可用版本条目格式无效')
  })
})

describe('应用更新状态校验', () => {
  const sha256 = 'b'.repeat(64)
  const state = (overrides: Record<string, unknown> = {}) => ({
    installedVersion: '0.2.0',
    installedVersionCode: 22,
    installAllowed: false,
    ...overrides,
  })
  const release = (overrides: Record<string, unknown> = {}) => ({
    version: '0.3.0',
    bytes: 48 * 1024 * 1024,
    sha256,
    notes: '修复窄屏下的按钮遮挡。',
    ...overrides,
  })

  it('无可用更新时只回已安装信息，不凭空造一条 available', () => {
    expect(validateAppUpdateState(state())).toEqual({
      installedVersion: '0.2.0',
      installedVersionCode: 22,
      installAllowed: false,
    })
    expect(validateAppUpdateState(state({ installAllowed: true })).installAllowed).toBe(true)
    // 版本代码边界：0 与上限本身都必须放过。
    expect(validateAppUpdateState(state({ installedVersionCode: 0 })).installedVersionCode).toBe(0)
    expect(validateAppUpdateState(state({ installedVersionCode: 2_100_000_000 })).installedVersionCode).toBe(2_100_000_000)
  })

  it('有可用更新时逐字段校验并原样保留', () => {
    const parsed = validateAppUpdateState(state({ available: release() }))

    expect(parsed.available).toEqual({ version: '0.3.0', bytes: 48 * 1024 * 1024, sha256, notes: '修复窄屏下的按钮遮挡。' })
    // 体积上限 4 GiB 本身要放过，多 1 字节就拒绝。
    expect(validateAppUpdateState(state({ available: release({ bytes: 4_294_967_296 }) })).available?.bytes).toBe(4_294_967_296)
    expect(() => validateAppUpdateState(state({ available: release({ bytes: 4_294_967_297 }) })))
      .toThrow('应用更新包大小格式无效')
    expect(() => validateAppUpdateState(state({ available: release({ bytes: 0 }) })))
      .toThrow('应用更新包大小格式无效')
    expect(() => validateAppUpdateState(state({ available: release({ bytes: 1.5 }) })))
      .toThrow('应用更新包大小格式无效')
  })

  it('安装权限状态缺失或非布尔一律拒绝', () => {
    for (const value of [undefined, null, 'true', 1, 0]) {
      expect(() => validateAppUpdateState(state({ installAllowed: value })), String(value))
        .toThrow('应用更新安装权限状态无效')
    }
  })

  it('已安装版本与版本代码走同一套规则', () => {
    for (const value of ['', 'v 1', 7, undefined, `v${'1'.repeat(96)}`]) {
      expect(() => validateAppUpdateState(state({ installedVersion: value })), String(value))
        .toThrow('已安装应用版本号格式无效')
    }
    for (const value of [-1, 2_100_000_001, 1.5, '22', null, Number.MAX_SAFE_INTEGER]) {
      expect(() => validateAppUpdateState(state({ installedVersionCode: value })), String(value))
        .toThrow('已安装应用版本代码格式无效')
    }
  })

  it('更新包的摘要与说明按契约校验，说明超长直接报错不截断', () => {
    expect(() => validateAppUpdateState(state({ available: release({ sha256: sha256.toUpperCase() }) })))
      .toThrow('应用更新包 SHA-256 必须是 64 位小写十六进制')
    expect(() => validateAppUpdateState(state({ available: release({ sha256: sha256.slice(0, 63) }) })))
      .toThrow('应用更新包 SHA-256 必须是 64 位小写十六进制')
    expect(() => validateAppUpdateState(state({ available: release({ notes: 7 }) })))
      .toThrow('应用更新说明格式无效')
    // 正好 4000 字符必须原样保留（截断会改变用户读到的更新内容）。
    expect(validateAppUpdateState(state({ available: release({ notes: '说'.repeat(4000) }) })).available?.notes)
      .toHaveLength(4000)
    expect(() => validateAppUpdateState(state({ available: release({ notes: '说'.repeat(4001) }) })))
      .toThrow('应用更新说明长度无效')
    expect(() => validateAppUpdateState(state({ available: release({ version: 'v 3' }) })))
      .toThrow('应用更新版本号格式无效')
  })

  it('拒绝类型不符的整体载荷与可用更新', () => {
    expect(() => validateAppUpdateState(null)).toThrow('应用更新状态格式无效')
    expect(() => validateAppUpdateState('0.2.0')).toThrow('应用更新状态格式无效')
    expect(() => validateAppUpdateState(state({ available: null }))).toThrow('可用应用更新格式无效')
    expect(() => validateAppUpdateState(state({ available: [] }))).toThrow('可用应用更新格式无效')
  })
})

describe('运行时会话快照校验', () => {
  // 下面这几个载荷是原生侧**真跑出来**的原文，逐字照抄：手写近似值会让校验与真实契约悄悄漂移。
  const liveSnapshotState = {
    maxSnapshots: 3,
    maxBytes: 536870912,
    totalBytes: 8,
    snapshots: [
      {
        id: 'snap-1700000001000-00000002',
        createdAt: '2023-11-14T22:13:21Z',
        bytes: 4,
        fileCount: 1,
        dshVersion: '0.2.0',
        runtimeVersion: '2026.01.01',
      },
      {
        id: 'snap-1700000000000-00000001',
        createdAt: '2023-11-14T22:13:20Z',
        bytes: 4,
        fileCount: 1,
        dshVersion: '0.2.0',
        runtimeVersion: '2026.01.01',
      },
    ],
  }
  const state = (overrides: Record<string, unknown> = {}) => ({ ...liveSnapshotState, ...overrides })
  const snapshot = (overrides: Record<string, unknown> = {}) => ({ ...liveSnapshotState.snapshots[0], ...overrides })
  const restore = (overrides: Record<string, unknown> = {}) => ({
    restoredFileCount: 0,
    skippedFileCount: 1,
    state: liveSnapshotState,
    ...overrides,
  })

  it('接受原生侧真跑出来的总览，新的在前且字段逐字保留', () => {
    const parsed = validateRuntimeSessionSnapshotState(liveSnapshotState)

    expect(parsed).toEqual(liveSnapshotState)
    expect(parsed.snapshots.map(item => item.id)).toEqual([
      'snap-1700000001000-00000002',
      'snap-1700000000000-00000001',
    ])
  })

  it('上限跟着载荷走：原生今天给 3，改天给 4 也不算非法', () => {
    const four = {
      maxSnapshots: 4,
      maxBytes: 536870912,
      totalBytes: 8,
      snapshots: [
        { ...liveSnapshotState.snapshots[0], id: 'snap-1700000003000-00000004' },
        { ...liveSnapshotState.snapshots[0], id: 'snap-1700000002000-00000003' },
        liveSnapshotState.snapshots[0],
        liveSnapshotState.snapshots[1],
      ],
    }
    expect(validateRuntimeSessionSnapshotState(four).snapshots).toHaveLength(4)
    // 但载荷自己说只能有 3 份、却回了 4 条，就是自相矛盾。
    expect(() => validateRuntimeSessionSnapshotState({ ...four, maxSnapshots: 3 }))
      .toThrow('运行时会话快照列表格式无效')
  })

  it('上限字段只接受正整数，且有防御性天花板', () => {
    for (const value of [0, -1, 1.5, '3', null, undefined]) {
      expect(() => validateRuntimeSessionSnapshotState(state({ maxSnapshots: value })), String(value))
        .toThrow('会话快照份数上限无效')
      expect(() => validateRuntimeSessionSnapshotState(state({ maxBytes: value })), String(value))
        .toThrow('会话快照空间上限无效')
    }
    expect(validateRuntimeSessionSnapshotState(state({ maxSnapshots: 32 })).maxSnapshots).toBe(32)
    expect(() => validateRuntimeSessionSnapshotState(state({ maxSnapshots: 33 }))).toThrow('会话快照份数上限无效')
    expect(validateRuntimeSessionSnapshotState(state({ maxBytes: 512 * 1024 * 1024 })).maxBytes)
      .toBe(512 * 1024 * 1024)
    expect(() => validateRuntimeSessionSnapshotState(state({ maxBytes: 512 * 1024 * 1024 + 1 })))
      .toThrow('会话快照空间上限无效')
  })

  it('已用空间必须在 0 与空间上限之间', () => {
    expect(validateRuntimeSessionSnapshotState(state({ totalBytes: 0 })).totalBytes).toBe(0)
    expect(validateRuntimeSessionSnapshotState(state({ totalBytes: 536870912 })).totalBytes).toBe(536870912)
    for (const value of [-1, 536870913, 1.5, '8', null]) {
      expect(() => validateRuntimeSessionSnapshotState(state({ totalBytes: value })), String(value))
        .toThrow('会话快照已用空间无效')
    }
  })

  it('快照条目：标识宽松、时间可解析、字节与文件数有界', () => {
    // 宽松形态：序号位数不写死，原生改格式不该把界面打死。
    for (const id of ['snap-1700000001000-00000002', 'snap-1700000001-a', 'snap-1700000001000-0A1b2C3d']) {
      expect(validateRuntimeSessionSnapshotState(state({ snapshots: [snapshot({ id })] })).snapshots[0].id).toBe(id)
    }
    for (const id of ['', 'snap-1-1', 'snap-1700000001000-', 'snap-1700000001000-00000002/x', 'snap-1700000001000-0000 0002', 7, null]) {
      expect(() => validateRuntimeSessionSnapshotState(state({ snapshots: [snapshot({ id })] })), String(id))
        .toThrow('运行时会话快照标识格式无效')
    }

    for (const createdAt of ['', '昨天', '1700000001000', 7, null]) {
      expect(() => validateRuntimeSessionSnapshotState(state({ snapshots: [snapshot({ createdAt })] })), String(createdAt))
        .toThrow('运行时会话快照创建时间格式无效')
    }
    // 只要 Date.parse 认得就行：带偏移量的写法同样合法。
    expect(validateRuntimeSessionSnapshotState(state({ snapshots: [snapshot({ createdAt: '2023-11-14T22:13:21+08:00' })] })).snapshots)
      .toHaveLength(1)

    for (const bytes of [0, -1, 1.5, 536870913, null]) {
      expect(() => validateRuntimeSessionSnapshotState(state({ snapshots: [snapshot({ bytes })] })), String(bytes))
        .toThrow('运行时会话快照大小格式无效')
    }
    for (const fileCount of [-1, 1.5, '1', null]) {
      expect(() => validateRuntimeSessionSnapshotState(state({ snapshots: [snapshot({ fileCount })] })), String(fileCount))
        .toThrow('运行时会话快照文件数格式无效')
    }
    for (const key of ['dshVersion', 'runtimeVersion']) {
      expect(() => validateRuntimeSessionSnapshotState(state({ snapshots: [snapshot({ [key]: '0.2.0 beta' })] })))
        .toThrow('格式无效')
    }
  })

  it('缺失的可选版本字段不产出空键，未知键一律忽略', () => {
    const parsed = validateRuntimeSessionSnapshotState(state({
      snapshots: [{ id: 'snap-1700000001000-00000002', createdAt: '2023-11-14T22:13:21Z', bytes: 4, fileCount: 1 }],
      // 原生侧以后加字段不该让整块界面失败（`evictedIds` 就是这么加进来的）。
      directory: '/data/user/0/io.deepseekharness.mobile/files/snapshots',
    }))

    expect(parsed).toEqual({
      maxSnapshots: 3,
      maxBytes: 536870912,
      totalBytes: 8,
      snapshots: [{ id: 'snap-1700000001000-00000002', createdAt: '2023-11-14T22:13:21Z', bytes: 4, fileCount: 1 }],
    })
    // 路径一类不该过桥的字段不许被带进来。
    expect('directory' in parsed).toBe(false)
    expect('dshVersion' in parsed.snapshots[0]).toBe(false)
  })

  it('拒绝类型不符的总览与快照条目', () => {
    for (const value of [null, undefined, '{}', [], 7]) {
      expect(() => validateRuntimeSessionSnapshotState(value), String(value)).toThrow('运行时会话快照状态格式无效')
    }
    expect(() => validateRuntimeSessionSnapshotState(state({ snapshots: 'none' })))
      .toThrow('运行时会话快照列表格式无效')
    expect(() => validateRuntimeSessionSnapshotState(state({ snapshots: [null] })))
      .toThrow('运行时会话快照条目格式无效')
  })

  it('恢复结果：两个计数都是非负整数，内嵌总览走同一套校验', () => {
    const parsed = validateRuntimeSessionSnapshotRestoreResult(restore())

    expect(parsed.restoredFileCount).toBe(0)
    // 同名文件被跳过必须如实计数：只报「恢复成功」会把跳过的文件藏起来。
    expect(parsed.skippedFileCount).toBe(1)
    expect(parsed.state).toEqual(liveSnapshotState)

    for (const key of ['restoredFileCount', 'skippedFileCount']) {
      for (const value of [-1, 1.5, '0', null, undefined]) {
        expect(() => validateRuntimeSessionSnapshotRestoreResult(restore({ [key]: value })), `${key}=${String(value)}`)
          .toThrow(key === 'restoredFileCount' ? '运行时会话快照恢复文件数格式无效' : '运行时会话快照跳过文件数格式无效')
      }
    }
    expect(() => validateRuntimeSessionSnapshotRestoreResult(restore({ state: { ...liveSnapshotState, totalBytes: 536870913 } })))
      .toThrow('会话快照已用空间无效')
    expect(() => validateRuntimeSessionSnapshotRestoreResult(null)).toThrow('运行时会话快照恢复结果格式无效')
  })

  it('安装结果：没有 autoSnapshot 的三个取值都合法，一律归一化为空对象', () => {
    for (const value of [undefined, null, {}]) {
      expect(validateRuntimeInstallResult(value)).toEqual({})
    }
    // 旧版原生桥接什么都不返回：结果对象里不该凭空长出 autoSnapshot。
    expect('autoSnapshot' in validateRuntimeInstallResult(undefined)).toBe(false)
    // 未知键忽略，但也不带进结果。
    expect(validateRuntimeInstallResult({ installedBytes: 7 })).toEqual({})
    expect(() => validateRuntimeInstallResult('ok')).toThrow('运行时安装结果格式无效')
    expect(() => validateRuntimeInstallResult([])).toThrow('运行时安装结果格式无效')
  })

  it('自动快照结论：created 必须带标识，skipped/failed 必须带错误码与说明', () => {
    // 原生侧真跑出来的三条载荷。
    expect(validateRuntimeInstallResult({
      autoSnapshot: { status: 'created', snapshotId: 'snap-1700000000000-00000001' },
    })).toEqual({ autoSnapshot: { status: 'created', snapshotId: 'snap-1700000000000-00000001' } })

    const skipped = { status: 'skipped', code: 'RUNTIME_SNAPSHOT_EMPTY', message: '当前没有可备份的会话数据，未生成快照' }
    expect(validateRuntimeInstallResult({ autoSnapshot: skipped })).toEqual({ autoSnapshot: skipped })

    const failed = { status: 'failed', code: 'RUNTIME_SNAPSHOT_FAILED', message: '磁盘空间不足，无法生成会话快照' }
    expect(validateRuntimeInstallResult({ autoSnapshot: failed })).toEqual({ autoSnapshot: failed })

    expect(() => validateRuntimeInstallResult({ autoSnapshot: { status: 'created' } }))
      .toThrow('运行时自动快照结论缺少快照标识')
    expect(() => validateRuntimeInstallResult({ autoSnapshot: { status: 'created', snapshotId: 'snap-1-1' } }))
      .toThrow('运行时会话快照标识格式无效')
    for (const autoSnapshot of [
      { status: 'skipped', message: '没有数据' },
      { status: 'failed', message: '出错了' },
      { status: 'skipped', code: '', message: '没有数据' },
    ]) {
      expect(() => validateRuntimeInstallResult({ autoSnapshot })).toThrow('运行时自动快照结论缺少错误码')
    }
    for (const autoSnapshot of [
      { status: 'skipped', code: 'RUNTIME_SNAPSHOT_EMPTY' },
      { status: 'failed', code: 'RUNTIME_SNAPSHOT_FAILED', message: '   ' },
      { status: 'failed', code: 'RUNTIME_SNAPSHOT_FAILED', message: 7 },
    ]) {
      expect(() => validateRuntimeInstallResult({ autoSnapshot })).toThrow('运行时自动快照结论缺少说明')
    }
    for (const status of ['ok', '', 'CREATED', null, undefined]) {
      expect(() => validateRuntimeInstallResult({ autoSnapshot: { status, snapshotId: 'snap-1700000000000-00000001' } }), String(status))
        .toThrow('运行时自动快照结论状态无效')
    }
    expect(() => validateRuntimeInstallResult({ autoSnapshot: null })).toThrow('运行时自动快照结论格式无效')
  })

  it('evictedIds 是契约之外的附加字段：合法时保留，非法时报错', () => {
    const created = {
      status: 'created',
      snapshotId: 'snap-1700000002000-00000003',
      evictedIds: ['snap-1700000000000-00000001'],
    }
    expect(validateRuntimeInstallResult({ autoSnapshot: created })).toEqual({ autoSnapshot: created })
    // 没有淘汰时原生侧不写这个键：结果里也不该多出空数组。
    expect(validateRuntimeInstallResult({
      autoSnapshot: { status: 'created', snapshotId: 'snap-1700000002000-00000003', evictedIds: [] },
    })).toEqual({ autoSnapshot: { status: 'created', snapshotId: 'snap-1700000002000-00000003', evictedIds: [] } })
    expect(validateRuntimeInstallResult({
      autoSnapshot: { status: 'skipped', code: 'RUNTIME_SNAPSHOT_EMPTY', message: '没有数据', evictedIds: ['snap-1700000000000-00000001'] },
    }).autoSnapshot?.evictedIds).toEqual(['snap-1700000000000-00000001'])

    // 整个字段不是数组：这是「淘汰列表」本身不合法。
    for (const evictedIds of ['snap-1700000000000-00000001', {}, null]) {
      expect(() => validateRuntimeInstallResult({
        autoSnapshot: { status: 'created', snapshotId: 'snap-1700000002000-00000003', evictedIds },
      }), JSON.stringify(evictedIds)).toThrow('运行时自动快照淘汰列表格式无效')
    }
    // 是数组但元素不是合法标识：报的是标识本身的形态问题，便于定位到具体哪一项。
    for (const evictedIds of [['nope'], [7]]) {
      expect(() => validateRuntimeInstallResult({
        autoSnapshot: { status: 'created', snapshotId: 'snap-1700000002000-00000003', evictedIds },
      }), JSON.stringify(evictedIds)).toThrow('运行时会话快照标识格式无效')
    }
  })
})
