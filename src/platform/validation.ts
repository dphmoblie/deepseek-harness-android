import type {
  DeviceCommand,
  DeviceCommandResult,
  RuntimePhase,
  RuntimeProgress,
  RuntimeSettings,
  RuntimeSource,
  RuntimeState,
  ShizukuState,
  TerminalChunk,
  TerminalExit,
} from './types'

const SHA256_PATTERN = /^[a-f0-9]{64}$/
const SESSION_ID_PATTERN = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i
const IDENTIFIER_PATTERN = /^[A-Za-z0-9._-]+$/
const ERROR_CODE_PATTERN = /^[A-Z0-9_]+$/
const MAX_URL_LENGTH = 2048
const MAX_IDENTIFIER_LENGTH = 96
const MAX_ERROR_CODE_LENGTH = 96
const MAX_TERMINAL_OUTPUT_BYTES = 96 * 1024
const RUNTIME_PHASES = new Set<RuntimePhase>([
  'not-installed',
  'preparing',
  'downloading',
  'verifying',
  'extracting',
  'ready',
  'running',
  'stopping',
  'error',
])

function asRecord(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`${label}格式无效`)
  }
  return value as Record<string, unknown>
}

function requiredIdentifier(value: unknown, label: string, maximumLength = MAX_IDENTIFIER_LENGTH): string {
  if (typeof value !== 'string' || value.length === 0 || value.length > maximumLength || !IDENTIFIER_PATTERN.test(value)) {
    throw new Error(`${label}格式无效`)
  }
  return value
}

function optionalIdentifier(value: unknown, label: string, pattern = IDENTIFIER_PATTERN, maximumLength = MAX_IDENTIFIER_LENGTH): string | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'string' || value.length === 0 || value.length > maximumLength || !pattern.test(value)) {
    throw new Error(`${label}格式无效`)
  }
  return value
}

function byteCount(value: unknown, label: string): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw new Error(`${label}格式无效`)
  return value as number
}

function runtimePhase(value: unknown): RuntimePhase {
  if (typeof value !== 'string' || !RUNTIME_PHASES.has(value as RuntimePhase)) throw new Error('运行时阶段格式无效')
  return value as RuntimePhase
}

function containsControlCharacter(value: string): boolean {
  return Array.from(value).some(character => {
    const code = character.charCodeAt(0)
    return code <= 31 || code === 127
  })
}

function isBlockedIpv4(hostname: string): boolean {
  const parts = hostname.split('.').map(part => Number(part))
  if (parts.length !== 4 || parts.some(part => !Number.isInteger(part) || part < 0 || part > 255)) return false
  const [first, second] = parts as [number, number, number, number]
  return first === 0
    || first === 10
    || first === 127
    || (first === 169 && second === 254)
    || (first === 172 && second >= 16 && second <= 31)
    || (first === 192 && second === 168)
    || first >= 224
}

function isBlockedIpv6(hostname: string): boolean {
  if (!hostname.includes(':')) return false
  return hostname === '::'
    || hostname === '::1'
    || hostname.startsWith('::')
    || hostname.startsWith('fc')
    || hostname.startsWith('fd')
    || /^fe[89ab]/.test(hostname)
}

function isBlockedManifestHost(hostname: string): boolean {
  const normalized = hostname.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.+$/, '')
  return normalized === 'localhost'
    || normalized.endsWith('.localhost')
    || normalized.endsWith('.local')
    || isBlockedIpv4(normalized)
    || isBlockedIpv6(normalized)
}

export function validateRuntimeSource(source: RuntimeSource): RuntimeSource {
  const manifestUrl = source.manifestUrl.trim()
  const manifestSha256 = source.manifestSha256.trim().toLowerCase()

  if (manifestUrl.length === 0 && manifestSha256.length === 0) {
    return { manifestUrl: '', manifestSha256: '' }
  }
  if (manifestUrl.length === 0 || manifestSha256.length === 0) {
    throw new Error('运行时清单地址与 SHA-256 必须同时填写或同时留空')
  }
  if (manifestUrl.length > MAX_URL_LENGTH) {
    throw new Error('运行时清单地址长度无效')
  }
  if (containsControlCharacter(manifestUrl)) {
    throw new Error('运行时清单地址包含非法字符')
  }

  let parsed: URL
  try {
    parsed = new URL(manifestUrl)
  } catch {
    throw new Error('运行时清单地址格式无效')
  }

  if (parsed.protocol !== 'https:' || parsed.username !== '' || parsed.password !== '') {
    throw new Error('运行时清单必须使用不含凭据的 HTTPS 地址')
  }
  if (isBlockedManifestHost(parsed.hostname)) {
    throw new Error('运行时清单不能指向本机、私网或链路本地地址')
  }
  if (parsed.hash !== '') {
    throw new Error('运行时清单地址不能包含片段')
  }
  if (!SHA256_PATTERN.test(manifestSha256)) {
    throw new Error('清单 SHA-256 必须是 64 位小写十六进制')
  }

  return { manifestUrl: parsed.toString(), manifestSha256 }
}

export function validateSettings(settings: RuntimeSettings): RuntimeSettings {
  const source = validateRuntimeSource(settings)
  if (typeof settings.keepScreenAwake !== 'boolean') {
    throw new Error('屏幕常亮设置格式无效')
  }
  if (!Number.isInteger(settings.terminalFontSize) || settings.terminalFontSize < 11 || settings.terminalFontSize > 24) {
    throw new Error('终端字号必须是 11 到 24 之间的整数')
  }
  const apiKey = settings.apiKey === undefined ? undefined : String(settings.apiKey).trim().slice(0, 200) || undefined
  const autoLaunch = settings.autoLaunch === undefined ? true : settings.autoLaunch
  return {
    ...source,
    keepScreenAwake: settings.keepScreenAwake,
    terminalFontSize: settings.terminalFontSize,
    ...(apiKey === undefined ? {} : { apiKey }),
    autoLaunch,
  }
}

export function validateStoredSettings(value: unknown): RuntimeSettings {
  const settings = asRecord(value, '运行时设置')
  if (typeof settings.manifestUrl !== 'string' || typeof settings.manifestSha256 !== 'string') {
    throw new Error('运行时来源格式无效')
  }
  if (typeof settings.keepScreenAwake !== 'boolean') throw new Error('屏幕常亮设置格式无效')
  if (!Number.isInteger(settings.terminalFontSize) || (settings.terminalFontSize as number) < 11 || (settings.terminalFontSize as number) > 24) {
    throw new Error('终端字号必须是 11 到 24 之间的整数')
  }
  const apiKey = typeof settings.apiKey === 'string' && settings.apiKey.trim() !== ''
    ? settings.apiKey.trim().slice(0, 200)
    : undefined
  const autoLaunch = settings.autoLaunch === undefined ? true : settings.autoLaunch === true
  if (settings.manifestUrl === '' && settings.manifestSha256 === '') {
    return {
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: settings.keepScreenAwake,
      terminalFontSize: settings.terminalFontSize as number,
      ...(apiKey === undefined ? {} : { apiKey }),
      autoLaunch,
    }
  }
  const source = validateRuntimeSource({
    manifestUrl: settings.manifestUrl,
    manifestSha256: settings.manifestSha256,
  })
  return {
    ...source,
    keepScreenAwake: settings.keepScreenAwake,
    terminalFontSize: settings.terminalFontSize as number,
    ...(apiKey === undefined ? {} : { apiKey }),
    autoLaunch,
  }
}

export function assertSessionId(sessionId: string): string {
  if (!SESSION_ID_PATTERN.test(sessionId)) throw new Error('终端会话标识无效')
  return sessionId
}

export function assertTerminalSize(columns: number, rows: number): void {
  if (!Number.isInteger(columns) || columns < 20 || columns > 300) throw new Error('终端列数无效')
  if (!Number.isInteger(rows) || rows < 4 || rows > 150) throw new Error('终端行数无效')
}

export function assertTerminalKind(kind: string): asserts kind is 'ubuntu' | 'device' {
  if (kind !== 'ubuntu' && kind !== 'device') throw new Error('终端类型无效')
}

export function assertBase64Input(value: string, maximumBytes: number): void {
  if (
    value.length === 0
    || value.length % 4 !== 0
    || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)
  ) {
    throw new Error('终端输入编码无效')
  }
  const padding = value.endsWith('==') ? 2 : value.endsWith('=') ? 1 : 0
  const decodedBytes = (value.length / 4) * 3 - padding
  if (decodedBytes > maximumBytes) throw new Error('终端输入长度无效')
}

export function validateRuntimeState(value: unknown): RuntimeState {
  const state = asRecord(value, '运行时状态')
  const downloadedBytes = byteCount(state.downloadedBytes, '已处理字节数')
  const totalBytes = byteCount(state.totalBytes, '总字节数')
  if (totalBytes > 0 && downloadedBytes > totalBytes) throw new Error('运行时进度无效')
  if (typeof state.runnerAvailable !== 'boolean') throw new Error('本机运行器状态格式无效')

  const installedVersion = optionalIdentifier(state.installedVersion, '运行时版本')
  const errorCode = optionalIdentifier(state.errorCode, '运行时错误码', ERROR_CODE_PATTERN, MAX_ERROR_CODE_LENGTH)
  let harnessUrl: string | undefined
  if (state.harnessUrl !== undefined) {
    if (typeof state.harnessUrl !== 'string' || state.harnessUrl.length > MAX_URL_LENGTH) throw new Error('Harness 地址格式无效')
    let parsed: URL
    try {
      parsed = new URL(state.harnessUrl)
    } catch {
      throw new Error('Harness 地址格式无效')
    }
    if (parsed.protocol !== 'http:' || parsed.hostname !== '127.0.0.1' || parsed.username !== '' || parsed.password !== '' || parsed.hash !== '') {
      throw new Error('Harness 地址必须是无凭据的本机 HTTP 地址')
    }
    harnessUrl = parsed.toString()
  }

  return {
    phase: runtimePhase(state.phase),
    architecture: requiredIdentifier(state.architecture, '运行时架构', 64),
    downloadedBytes,
    totalBytes,
    runnerAvailable: state.runnerAvailable,
    ...(installedVersion === undefined ? {} : { installedVersion }),
    ...(harnessUrl === undefined ? {} : { harnessUrl }),
    ...(errorCode === undefined ? {} : { errorCode }),
  }
}

export function validateRuntimeProgress(value: unknown): RuntimeProgress {
  const progress = asRecord(value, '运行时进度')
  const downloadedBytes = byteCount(progress.downloadedBytes, '已处理字节数')
  const totalBytes = byteCount(progress.totalBytes, '总字节数')
  if (totalBytes > 0 && downloadedBytes > totalBytes) throw new Error('运行时进度无效')
  const errorCode = optionalIdentifier(progress.errorCode, '运行时错误码', ERROR_CODE_PATTERN, MAX_ERROR_CODE_LENGTH)
  return { phase: runtimePhase(progress.phase), downloadedBytes, totalBytes, ...(errorCode === undefined ? {} : { errorCode }) }
}

export function validateShizukuState(value: unknown): ShizukuState {
  const state = asRecord(value, 'Shizuku 状态')
  if (typeof state.installed !== 'boolean' || typeof state.running !== 'boolean' || typeof state.connected !== 'boolean') {
    throw new Error('Shizuku 状态格式无效')
  }
  if (state.permission !== 'granted' && state.permission !== 'denied' && state.permission !== 'undetermined') {
    throw new Error('Shizuku 权限状态格式无效')
  }
  if (state.connected && (!state.running || state.permission !== 'granted')) throw new Error('Shizuku 连接状态无效')
  const version = state.version === undefined
    ? undefined
    : optionalIdentifier(state.version, 'Shizuku 版本', IDENTIFIER_PATTERN, 32)
  return {
    installed: state.installed,
    running: state.running,
    permission: state.permission,
    connected: state.connected,
    ...(version === undefined ? {} : { version }),
  }
}

export function validateTerminalSession(value: unknown): { sessionId: string } {
  const session = asRecord(value, '终端会话')
  if (typeof session.sessionId !== 'string') throw new Error('终端会话标识无效')
  return { sessionId: assertSessionId(session.sessionId) }
}

export function validateTerminalChunk(value: unknown): TerminalChunk {
  const chunk = asRecord(value, '终端输出')
  if (typeof chunk.sessionId !== 'string' || typeof chunk.dataBase64 !== 'string') throw new Error('终端输出格式无效')
  const sessionId = assertSessionId(chunk.sessionId)
  assertBase64Input(chunk.dataBase64, MAX_TERMINAL_OUTPUT_BYTES)
  return { sessionId, dataBase64: chunk.dataBase64 }
}

export function validateTerminalExit(value: unknown): TerminalExit {
  const exit = asRecord(value, '终端退出状态')
  if (typeof exit.sessionId !== 'string') throw new Error('终端会话标识无效')
  if (!Number.isInteger(exit.exitCode) || (exit.exitCode as number) < -1 || (exit.exitCode as number) > 255) {
    throw new Error('终端退出码格式无效')
  }
  return { sessionId: assertSessionId(exit.sessionId), exitCode: exit.exitCode as number }
}

const DEVICE_COMMANDS = new Set<DeviceCommand>(['screenshot', 'uiDump', 'tap', 'inputText'])
const MAX_DEVICE_PARAM_CHARS = 4096

export function validateDeviceCommand(value: unknown): DeviceCommand {
  if (typeof value !== 'string' || !DEVICE_COMMANDS.has(value as DeviceCommand)) throw new Error('设备命令不支持')
  return value as DeviceCommand
}

export function validateDeviceCommandParam(value: unknown): string | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'string' || value.length > MAX_DEVICE_PARAM_CHARS) throw new Error('设备命令参数无效')
  return value
}

export function validateDeviceCommandResult(value: unknown): DeviceCommandResult {
  const result = asRecord(value, '设备命令结果')
  if (typeof result.ok !== 'boolean' || typeof result.text !== 'string' || typeof result.truncated !== 'boolean') {
    throw new Error('设备命令结果格式无效')
  }
  if (typeof result.exitCode !== 'number' || !Number.isInteger(result.exitCode) || result.exitCode < -1 || result.exitCode > 255) {
    throw new Error('设备命令退出码无效')
  }
  return {
    ok: result.ok,
    exitCode: result.exitCode,
    text: result.text,
    truncated: result.truncated,
  }
}
