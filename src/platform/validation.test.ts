import { describe, expect, it } from 'vitest'
import {
  assertBase64Input,
  assertSessionId,
  assertTerminalKind,
  assertTerminalSize,
  validateKeepAliveState,
  validateNotificationPermissionResult,
  validateRuntimeProgress,
  validateRuntimeSource,
  validateRuntimeState,
  validateSettings,
  validateSettingsUpdate,
  validateShizukuState,
  validateStoredSettings,
  validateTerminalChunk,
  validateTerminalExit,
} from './validation'

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

  it('ignores retired frontend preferences and rejects invalid provider updates', () => {
    const base = {
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: true,
      terminalFontSize: 14,
      configuredModelProviders: [],
      autoLaunch: true,
      keepRuntimeInBackground: false,
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
