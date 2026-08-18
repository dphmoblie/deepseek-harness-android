import { describe, expect, it } from 'vitest'
import {
  assertBase64Input,
  assertSessionId,
  assertTerminalKind,
  assertTerminalSize,
  validateRuntimeSource,
  validateRuntimeProgress,
  validateRuntimeState,
  validateSettings,
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
      autoLaunch: false,
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
    })).toThrow('字号')
  })

  it('allows saving bundled runtime settings', () => {
    expect(validateSettings({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: true,
      terminalFontSize: 16,
      autoLaunch: false,
    })).toEqual({
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: true,
      terminalFontSize: 16,
      autoLaunch: false,
    })
  })

  it('rejects non-boolean screen settings instead of silently coercing them', () => {
    expect(() => validateSettings({
      manifestUrl: 'https://downloads.example.invalid/runtime.json',
      manifestSha256: 'a'.repeat(64),
      keepScreenAwake: 'true',
      terminalFontSize: 14,
    } as unknown as Parameters<typeof validateSettings>[0])).toThrow('屏幕常亮')
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
      downloadedBytes: 100,
      totalBytes: 100,
      runnerAvailable: true,
      harnessUrl: 'http://127.0.0.1:3080/',
    }).phase).toBe('running')
    expect(() => validateRuntimeState({
      phase: 'running',
      architecture: 'arm64-v8a',
      downloadedBytes: 100,
      totalBytes: 100,
      runnerAvailable: true,
      harnessUrl: 'https://example.invalid/',
    })).toThrow('本机 HTTP')
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
