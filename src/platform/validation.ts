import type {
  AllFilesAccessResult,
  DeviceCommand,
  DeviceCommandResult,
  DiagnosticLogExport,
  DiagnosticLogState,
  DiagnosticLogText,
  HarnessLog,
  KeepAliveState,
  MailboxAvailability,
  MailboxExportResult,
  MailboxImportResult,
  MailboxState,
  MediaPermissionResult,
  ModelProviderId,
  NotificationPermission,
  NotificationPermissionResult,
  OverlayBallState,
  ProviderApiKeys,
  RuntimeIntent,
  RuntimePhase,
  RuntimeProgress,
  RuntimeSettings,
  RuntimeSettingsUpdate,
  RuntimeSource,
  RuntimeState,
  ShizukuState,
  StorageAccessState,
  TerminalChunk,
  TerminalExit,
} from './types'
import {
  DIAGNOSTIC_LOG_MAX_CHARS,
  DIAGNOSTIC_LOG_WINDOW_OPTIONS,
  DIAGNOSTIC_RETENTION_MAX,
  DIAGNOSTIC_RETENTION_MIN,
  HARNESS_LOG_MAX_CHARS,
  HARNESS_LOG_WINDOW_OPTIONS,
  MODEL_PROVIDER_IDS,
} from './types'
import { validateCustomCredentialIds, validateCustomCredentialUpdates, validateCustomModelProviders } from './customProviders'
import { validateSelfCheckReport, type SelfCheckReport } from '../runtimeSelfCheck'
import { validateHarnessPermissionMode } from '../harnessPermissionMode'

const SHA256_PATTERN = /^[a-f0-9]{64}$/
const SESSION_ID_PATTERN = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i
const IDENTIFIER_PATTERN = /^[A-Za-z0-9._-]+$/
const ERROR_CODE_PATTERN = /^[A-Z0-9_]+$/
const MAX_URL_LENGTH = 2048
const MAX_IDENTIFIER_LENGTH = 96
const MAX_ERROR_CODE_LENGTH = 96
const MAX_TERMINAL_OUTPUT_BYTES = 96 * 1024
const API_KEY_PATTERN = /^[\x21-\x7e]{1,200}$/
const MODEL_PROVIDER_ID_SET = new Set<string>(MODEL_PROVIDER_IDS)
/** 投递区可用性档位（与原生 `MailboxAvailability` 一一对应）。 */
const MAILBOX_AVAILABILITIES = new Set<MailboxAvailability>([
  'available',
  'needsPermission',
  'unsupported',
  'unwritable',
])
/** 投递区路径与文件名的字符上限；与原生侧的 240 保持一致。 */
const MAX_MAILBOX_PATH_LENGTH = 240
/** 原生侧最多列出的 inbox tar 候选数；超出即视为载荷不符合契约。 */
const MAX_MAILBOX_TARS = 5
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

/**
 * 校验布尔标量。
 *
 * 类型不符（含 undefined、null、字符串化的 'true'/'false' 与 1/0）一律抛错，
 * 不做静默转换；错误消息格式与文件内其他校验辅助一致：`${label}格式无效`。
 */
function requiredBoolean(value: unknown, label: string): boolean {
  if (typeof value !== 'boolean') throw new Error(`${label}格式无效`)
  return value
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

function modelProviderId(value: unknown): ModelProviderId {
  if (typeof value !== 'string' || !MODEL_PROVIDER_ID_SET.has(value)) throw new Error('模型供应商格式无效')
  return value as ModelProviderId
}

function configuredModelProviders(value: unknown, legacyApiKey: unknown): ModelProviderId[] {
  const providers: ModelProviderId[] = []
  if (value !== undefined) {
    if (!Array.isArray(value) || value.length > MODEL_PROVIDER_IDS.length) throw new Error('模型凭据状态格式无效')
    for (const item of value) {
      const provider = modelProviderId(item)
      if (providers.includes(provider)) throw new Error('模型凭据状态包含重复供应商')
      providers.push(provider)
    }
  }
  if (legacyApiKey !== undefined) {
    if (typeof legacyApiKey !== 'string') throw new Error('旧版模型凭据格式无效')
    const normalized = legacyApiKey.trim()
    if (normalized !== '') {
      if (!API_KEY_PATTERN.test(normalized)) throw new Error('旧版模型凭据包含非法字符或长度无效')
      if (!providers.includes('deepseek')) providers.unshift('deepseek')
    }
  }
  return MODEL_PROVIDER_IDS.filter(provider => providers.includes(provider))
}

function providerApiKeyUpdates(value: unknown): ProviderApiKeys {
  if (value === undefined) return {}
  const record = asRecord(value, '模型凭据更新')
  if (Object.keys(record).length > MODEL_PROVIDER_IDS.length) throw new Error('模型凭据更新数量无效')
  const result: ProviderApiKeys = {}
  for (const [rawProvider, rawKey] of Object.entries(record)) {
    const provider = modelProviderId(rawProvider)
    if (typeof rawKey !== 'string') throw new Error('模型凭据必须是字符串')
    const key = rawKey.trim()
    if (!API_KEY_PATTERN.test(key)) throw new Error('模型凭据包含非法字符或长度无效')
    result[provider] = key
  }
  return result
}

function clearedProviderApiKeys(value: unknown): ModelProviderId[] {
  if (value === undefined) return []
  if (!Array.isArray(value) || value.length > MODEL_PROVIDER_IDS.length) throw new Error('模型凭据清除列表格式无效')
  const result: ModelProviderId[] = []
  for (const item of value) {
    const provider = modelProviderId(item)
    if (result.includes(provider)) throw new Error('模型凭据清除列表包含重复供应商')
    result.push(provider)
  }
  return result
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
  const autoLaunch = settings.autoLaunch === undefined ? false : settings.autoLaunch
  if (typeof autoLaunch !== 'boolean') throw new Error('自动启动设置格式无效')
  const keepRuntimeInBackground = settings.keepRuntimeInBackground === undefined ? false : settings.keepRuntimeInBackground
  if (typeof keepRuntimeInBackground !== 'boolean') throw new Error('后台保持设置格式无效')
  const overlayBallEnabled = settings.overlayBallEnabled === undefined ? false : settings.overlayBallEnabled
  if (typeof overlayBallEnabled !== 'boolean') throw new Error('悬浮球设置格式无效')
  return {
    ...source,
    ...(settings.harnessPermissionMode === undefined ? {} : { harnessPermissionMode: validateHarnessPermissionMode(settings.harnessPermissionMode) }),
    keepScreenAwake: settings.keepScreenAwake,
    terminalFontSize: settings.terminalFontSize,
    configuredModelProviders: configuredModelProviders(settings.configuredModelProviders, settings.apiKey),
    ...(settings.harnessConfiguredModelProviders === undefined ? {} : {
      harnessConfiguredModelProviders: configuredModelProviders(settings.harnessConfiguredModelProviders, undefined),
    }),
    ...(settings.customModelProviders === undefined ? {} : { customModelProviders: validateCustomModelProviders(settings.customModelProviders) }),
    ...(settings.configuredCustomModelProviders === undefined ? {} : {
      configuredCustomModelProviders: validateCustomCredentialIds(settings.configuredCustomModelProviders, '自定义模型凭据状态'),
    }),
    ...(settings.harnessConfiguredCustomModelProviders === undefined ? {} : {
      harnessConfiguredCustomModelProviders: validateCustomCredentialIds(settings.harnessConfiguredCustomModelProviders, 'Harness 自定义模型凭据状态'),
    }),
    autoLaunch,
    keepRuntimeInBackground,
    overlayBallEnabled,
  }
}

export function validateSettingsUpdate(settings: RuntimeSettingsUpdate): RuntimeSettingsUpdate {
  const validated = validateSettings(settings)
  const providerApiKeys = providerApiKeyUpdates(settings.providerApiKeys)
  const clearProviderApiKeys = clearedProviderApiKeys(settings.clearProviderApiKeys)
  const allowedCustomIds = validated.customModelProviders === undefined ? undefined : new Set(validated.customModelProviders.map(provider => provider.id))
  const customProviderApiKeys = validateCustomCredentialUpdates(settings.customProviderApiKeys, allowedCustomIds)
  const clearCustomProviderApiKeys = validateCustomCredentialIds(settings.clearCustomProviderApiKeys, '自定义模型凭据清除列表', allowedCustomIds)
  if (clearProviderApiKeys.some(provider => providerApiKeys[provider] !== undefined)) {
    throw new Error('同一模型凭据不能同时更新和清除')
  }
  if (clearCustomProviderApiKeys.some(id => customProviderApiKeys[id] !== undefined)) throw new Error('同一自定义模型凭据不能同时更新和清除')
  const result: RuntimeSettingsUpdate = {
    ...validated,
    ...(Object.keys(providerApiKeys).length === 0 ? {} : { providerApiKeys }),
    ...(clearProviderApiKeys.length === 0 ? {} : { clearProviderApiKeys }),
    ...(Object.keys(customProviderApiKeys).length === 0 ? {} : { customProviderApiKeys }),
    ...(clearCustomProviderApiKeys.length === 0 ? {} : { clearCustomProviderApiKeys }),
  }
  if (settings.overlayBallEnabled === undefined) delete result.overlayBallEnabled
  return result
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
  const autoLaunch = settings.autoLaunch === undefined ? false : settings.autoLaunch === true
  if (settings.autoLaunch !== undefined && typeof settings.autoLaunch !== 'boolean') throw new Error('自动启动设置格式无效')
  const keepRuntimeInBackground = settings.keepRuntimeInBackground === undefined
    ? false
    : settings.keepRuntimeInBackground === true
  if (settings.keepRuntimeInBackground !== undefined && typeof settings.keepRuntimeInBackground !== 'boolean') {
    throw new Error('后台保持设置格式无效')
  }
  const overlayBallEnabled = settings.overlayBallEnabled === undefined ? false : settings.overlayBallEnabled
  if (typeof overlayBallEnabled !== 'boolean') throw new Error('悬浮球设置格式无效')
  const configuredProviders = configuredModelProviders(settings.configuredModelProviders, settings.apiKey)
  const customSettings = {
    ...(settings.harnessPermissionMode === undefined ? {} : { harnessPermissionMode: validateHarnessPermissionMode(settings.harnessPermissionMode) }),
    ...(settings.harnessConfiguredModelProviders === undefined ? {} : {
      harnessConfiguredModelProviders: configuredModelProviders(settings.harnessConfiguredModelProviders, undefined),
    }),
    ...(settings.customModelProviders === undefined ? {} : { customModelProviders: validateCustomModelProviders(settings.customModelProviders) }),
    ...(settings.configuredCustomModelProviders === undefined ? {} : {
      configuredCustomModelProviders: validateCustomCredentialIds(settings.configuredCustomModelProviders, '自定义模型凭据状态'),
    }),
    ...(settings.harnessConfiguredCustomModelProviders === undefined ? {} : {
      harnessConfiguredCustomModelProviders: validateCustomCredentialIds(settings.harnessConfiguredCustomModelProviders, 'Harness 自定义模型凭据状态'),
    }),
  }
  if (settings.manifestUrl === '' && settings.manifestSha256 === '') {
    return {
      manifestUrl: '',
      manifestSha256: '',
      keepScreenAwake: settings.keepScreenAwake,
      terminalFontSize: settings.terminalFontSize as number,
      configuredModelProviders: configuredProviders,
      ...customSettings,
      autoLaunch,
      keepRuntimeInBackground,
      overlayBallEnabled,
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
    configuredModelProviders: configuredProviders,
    ...customSettings,
    autoLaunch,
    keepRuntimeInBackground,
    overlayBallEnabled,
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
  if (typeof state.updateAvailable !== 'boolean') throw new Error('运行时更新状态格式无效')

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
    updateAvailable: state.updateAvailable,
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

const NOTIFICATION_PERMISSIONS = new Set<NotificationPermission>(['granted', 'prompt', 'unsupported'])
const RUNTIME_INTENTS = new Set<RuntimeIntent>(['running', 'stopped', 'unknown'])

/**
 * 校验后台保持与恢复状态。
 * 只接受布尔值、固定枚举与时间戳；任何额外字段都不会被回传使用。
 */
export function validateKeepAliveState(value: unknown): KeepAliveState {
  const state = asRecord(value, '后台保持状态')
  if (
    typeof state.keepRuntimeInBackground !== 'boolean' ||
    typeof state.foregroundServiceActive !== 'boolean' ||
    typeof state.deviceShellReady !== 'boolean' ||
    typeof state.reconnectRequired !== 'boolean'
  ) {
    throw new Error('后台保持状态格式无效')
  }
  if (typeof state.notificationPermission !== 'string' || !NOTIFICATION_PERMISSIONS.has(state.notificationPermission as NotificationPermission)) {
    throw new Error('通知权限状态格式无效')
  }
  if (typeof state.lastIntent !== 'string' || !RUNTIME_INTENTS.has(state.lastIntent as RuntimeIntent)) {
    throw new Error('运行意图格式无效')
  }
  const lastPhase = state.lastPhase === undefined ? undefined : runtimePhase(state.lastPhase)
  let lastUpdatedAtMillis: number | undefined
  if (state.lastUpdatedAtMillis !== undefined) {
    if (!Number.isSafeInteger(state.lastUpdatedAtMillis) || (state.lastUpdatedAtMillis as number) < 0) {
      throw new Error('状态更新时间格式无效')
    }
    lastUpdatedAtMillis = state.lastUpdatedAtMillis as number
  }
  return {
    keepRuntimeInBackground: state.keepRuntimeInBackground,
    foregroundServiceActive: state.foregroundServiceActive,
    notificationPermission: state.notificationPermission as NotificationPermission,
    deviceShellReady: state.deviceShellReady,
    reconnectRequired: state.reconnectRequired,
    lastIntent: state.lastIntent as RuntimeIntent,
    ...(lastPhase === undefined ? {} : { lastPhase }),
    ...(lastUpdatedAtMillis === undefined ? {} : { lastUpdatedAtMillis }),
  }
}

export function validateNotificationPermissionResult(value: unknown): NotificationPermissionResult {
  const result = asRecord(value, '通知权限结果')
  if (typeof result.granted !== 'boolean' || typeof result.supported !== 'boolean') {
    throw new Error('通知权限结果格式无效')
  }
  return { granted: result.granted, supported: result.supported }
}

function diagnosticCount(value: unknown, label: string): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw new Error(`${label}格式无效`)
  return value as number
}

/** 诊断日志保留天数：必须是 1–30 的整数，与原生侧夹取范围一致。 */
export function assertDiagnosticRetentionDays(value: number): number {
  if (!Number.isInteger(value) || value < DIAGNOSTIC_RETENTION_MIN || value > DIAGNOSTIC_RETENTION_MAX) {
    throw new Error(`诊断日志保留天数必须是 ${DIAGNOSTIC_RETENTION_MIN} 到 ${DIAGNOSTIC_RETENTION_MAX} 之间的整数`)
  }
  return value
}

/**
 * 校验诊断日志状态。
 * 只接受布尔值、计数与时间戳：日志正文永远不会经原生接口回传 WebView。
 */
export function validateDiagnosticLogState(value: unknown): DiagnosticLogState {
  const state = asRecord(value, '诊断日志状态')
  if (typeof state.enabled !== 'boolean') throw new Error('诊断日志状态格式无效')
  return {
    enabled: state.enabled,
    retentionDays: assertDiagnosticRetentionDays(state.retentionDays as number),
    fileCount: diagnosticCount(state.fileCount, '诊断日志文件数'),
    totalBytes: diagnosticCount(state.totalBytes, '诊断日志总字节数'),
    lastEntryAtMillis: diagnosticCount(state.lastEntryAtMillis, '诊断日志最近记录时间'),
  }
}

export function validateDiagnosticLogExport(value: unknown): DiagnosticLogExport {
  const state = validateDiagnosticLogState(value)
  const record = asRecord(value, '诊断日志导出结果')
  // 导出文件名由原生侧以 UTC 时间戳生成：不含路径分隔符，也不含设备或用户信息。
  if (typeof record.fileName !== 'string' || !/^[A-Za-z0-9._-]{1,128}$/.test(record.fileName)) {
    throw new Error('诊断日志导出文件名格式无效')
  }
  return {
    ...state,
    fileName: record.fileName,
    exportedBytes: diagnosticCount(record.exportedBytes, '诊断日志导出字节数'),
  }
}

/**
 * 校验运行日志尾部快照。
 *
 * 这是唯一会把访客输出带回 WebView 的通道，因此只接受严格形态：
 * `available` 必须是布尔值，`text` 必须是字符串且有长度上限（防异常载荷打爆界面）；
 * `available` 为 false 时按契约必须是空串，不接受「不可用却带内容」的自相矛盾载荷；
 * `maxBytes` 必须是受控档位之一，避免界面按一个根本不存在的窗口去解释内容。
 */
export function validateHarnessLog(value: unknown): HarnessLog {
  const log = asRecord(value, '运行日志')
  if (typeof log.available !== 'boolean') throw new Error('运行日志可用状态格式无效')
  if (typeof log.text !== 'string') throw new Error('运行日志内容格式无效')
  if (log.text.length > HARNESS_LOG_MAX_CHARS) throw new Error('运行日志内容长度无效')
  if (!log.available && log.text !== '') throw new Error('运行日志内容与可用状态不一致')
  return {
    available: log.available,
    text: log.text,
    maxBytes: assertLogWindow(log.maxBytes, HARNESS_LOG_WINDOW_OPTIONS, '运行日志窗口'),
  }
}

/**
 * 校验诊断日志的应用内查看结果。
 *
 * 与状态接口不同，这里会带回日志正文；正文只有受控字段（事件、级别、状态码、计数），
 * 因此仍然按「有上限的纯文本」校验：长度上限之外不接受其它形态。
 */
export function validateDiagnosticLogText(value: unknown): DiagnosticLogText {
  const record = asRecord(value, '诊断日志内容')
  if (typeof record.text !== 'string') throw new Error('诊断日志内容格式无效')
  if (record.text.length > DIAGNOSTIC_LOG_MAX_CHARS) throw new Error('诊断日志内容长度无效')
  if (typeof record.truncated !== 'boolean') throw new Error('诊断日志截断状态格式无效')
  return {
    text: record.text,
    maxBytes: assertLogWindow(record.maxBytes, DIAGNOSTIC_LOG_WINDOW_OPTIONS, '诊断日志窗口'),
    totalBytes: diagnosticCount(record.totalBytes, '诊断日志总字节数'),
    truncated: record.truncated,
  }
}

/**
 * 校验运行时自检结果。
 *
 * 契约（检查项 id、状态、结论码与形态规则）定义在 [runtimeSelfCheck] 里，
 * 那里同时被界面直接使用；这里只是把它接进平台层统一的校验入口，
 * 让桥接封装与其它方法保持同一种写法。
 */
export function validateRuntimeSelfCheckReport(value: unknown): SelfCheckReport {
  return validateSelfCheckReport(value)
}

/** 窗口字节数必须落在界面已知的档位里；原生侧与前端共用同一组取值。 */
function assertLogWindow(value: unknown, options: readonly number[], label: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || !options.includes(value)) {
    throw new Error(`${label}取值无效`)
  }
  return value
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

/**
 * 校验原生返回的悬浮球状态。
 *
 * 三个字段都必须存在且为布尔：字段缺失时抛错，而不是补成 false ——
 * 否则「读取失败」会被界面显示成「开关关闭」，用户点了没反应也查不出原因。
 */
export function validateOverlayBallState(value: unknown): OverlayBallState {
  const source = asRecord(value, '悬浮球状态')
  return {
    enabled: requiredBoolean(source.enabled, '悬浮球开关'),
    canDrawOverlays: requiredBoolean(source.canDrawOverlays, '悬浮球权限'),
    serviceActive: requiredBoolean(source.serviceActive, '悬浮球服务状态'),
  }
}

/**
 * 校验存储访问状态。
 *
 * 四个字段都必须存在：`allFilesSupported` 缺失时若补成 false，界面会把一台
 * Android 14 设备显示成「系统不支持」，用户再也不会去找那个入口。
 */
export function validateStorageAccessState(value: unknown): StorageAccessState {
  const source = asRecord(value, '存储访问状态')
  if (!Number.isSafeInteger(source.sdkInt) || (source.sdkInt as number) < 0) {
    throw new Error('存储访问状态格式无效')
  }
  return {
    mediaGranted: requiredBoolean(source.mediaGranted, '媒体读取权限'),
    allFilesGranted: requiredBoolean(source.allFilesGranted, '所有文件访问权限'),
    allFilesSupported: requiredBoolean(source.allFilesSupported, '所有文件访问支持状态'),
    sdkInt: source.sdkInt as number,
  }
}

/** 校验媒体权限申请结果；只认布尔，不做静默转换。 */
export function validateMediaPermissionResult(value: unknown): MediaPermissionResult {
  const source = asRecord(value, '媒体权限结果')
  return { granted: requiredBoolean(source.granted, '媒体权限结果') }
}

/** 校验「所有文件访问」设置跳转结果。 */
export function validateAllFilesAccessResult(value: unknown): AllFilesAccessResult {
  const source = asRecord(value, '所有文件访问结果')
  return {
    supported: requiredBoolean(source.supported, '所有文件访问支持状态'),
    granted: requiredBoolean(source.granted, '所有文件访问权限'),
  }
}

/**
 * 校验投递区可用性档位。
 *
 * 只接受四个受控取值：未知取值一律抛错，而不是回落到 `available` ——
 * 把「读不懂的状态」显示成「可用」会让用户点了按钮才发现不可用。
 */
function mailboxAvailability(value: unknown): MailboxAvailability {
  if (typeof value !== 'string' || !MAILBOX_AVAILABILITIES.has(value as MailboxAvailability)) {
    throw new Error('投递区可用性格式无效')
  }
  return value as MailboxAvailability
}

function mailboxPath(value: unknown, label: string): string {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_MAILBOX_PATH_LENGTH || !value.startsWith('/')) {
    throw new Error(`${label}格式无效`)
  }
  return value
}

function mailboxFileName(value: unknown, label: string): string {
  if (
    typeof value !== 'string' || value.length === 0 || value.length > MAX_MAILBOX_PATH_LENGTH ||
    value.startsWith('/') || value.includes('\\') || value.includes('/')
  ) {
    throw new Error(`${label}格式无效`)
  }
  return value
}

/**
 * 校验投递区状态。
 *
 * 路径只允许 `/` 开头的用户可见路径与访客挂载点：原生侧只回传这两类路径，
 * 任何相对路径或带反斜杠的取值都说明载荷不符合契约，按格式无效处理。
 * `available` 必须与 `availability` 自洽，避免界面出现「可用按钮 + 需要授权文案」的矛盾状态。
 */
export function validateMailboxState(value: unknown): MailboxState {
  const source = asRecord(value, '投递区状态')
  const availability = mailboxAvailability(source.availability)
  const available = requiredBoolean(source.available, '投递区可用性')
  if (available !== (availability === 'available')) {
    throw new Error('投递区状态自相矛盾')
  }
  const level = source.level
  if (level !== 'T2' && level !== 'T0') throw new Error('投递区权限档位格式无效')
  if (!Array.isArray(source.inboxTars) || source.inboxTars.length > MAX_MAILBOX_TARS) {
    throw new Error('投递区 tar 列表格式无效')
  }
  return {
    availability,
    level,
    available,
    supported: requiredBoolean(source.supported, '投递区支持状态'),
    granted: requiredBoolean(source.granted, '投递区授权状态'),
    inboxPath: mailboxPath(source.inboxPath, '投递区 inbox 路径'),
    outboxPath: mailboxPath(source.outboxPath, '投递区 outbox 路径'),
    guestInboxPath: mailboxPath(source.guestInboxPath, '访客 inbox 路径'),
    guestOutboxPath: mailboxPath(source.guestOutboxPath, '访客 outbox 路径'),
    inboxFileCount: byteCount(source.inboxFileCount, '投递区文件数'),
    inboxTars: source.inboxTars.map(item => {
      const candidate = asRecord(item, '投递区 tar 条目')
      return {
        name: mailboxFileName(candidate.name, '投递区 tar 名称'),
        bytes: byteCount(candidate.bytes, '投递区 tar 大小'),
      }
    }),
    exportTarName: mailboxFileName(source.exportTarName, '投递区导出归档名'),
    exportManifestName: mailboxFileName(source.exportManifestName, '投递区导出清单名'),
    importDirectory: mailboxFileName(source.importDirectory, '投递区导入落点'),
  }
}

/** 校验导入结果；缺 `manifestName` 表示这份归档没有附带 manifest（未逐条校验）。 */
export function validateMailboxImportResult(value: unknown): MailboxImportResult {
  const source = asRecord(value, '投递区导入结果')
  return {
    entryCount: byteCount(source.entryCount, '投递区导入条目数'),
    fileCount: byteCount(source.fileCount, '投递区导入文件数'),
    directoryCount: byteCount(source.directoryCount, '投递区导入目录数'),
    symlinkCount: byteCount(source.symlinkCount, '投递区导入链接数'),
    hardlinkCount: byteCount(source.hardlinkCount, '投递区导入硬链接数'),
    bytes: byteCount(source.bytes, '投递区导入字节数'),
    tarName: mailboxFileName(source.tarName, '投递区导入归档名'),
    tarBytes: byteCount(source.tarBytes, '投递区导入归档大小'),
    verified: requiredBoolean(source.verified, '投递区导入校验状态'),
    manifestName: source.manifestName === undefined
      ? undefined
      : mailboxFileName(source.manifestName, '投递区导入清单名'),
    ignoredFiles: byteCount(source.ignoredFiles, '投递区忽略文件数'),
    target: mailboxFileName(source.target, '投递区导入落点'),
  }
}

/** 校验导出结果；摘要必须是 64 位小写十六进制。 */
export function validateMailboxExportResult(value: unknown): MailboxExportResult {
  const source = asRecord(value, '投递区导出结果')
  if (typeof source.tarSha256 !== 'string' || !SHA256_PATTERN.test(source.tarSha256)) {
    throw new Error('投递区导出摘要格式无效')
  }
  return {
    entryCount: byteCount(source.entryCount, '投递区导出条目数'),
    bytes: byteCount(source.bytes, '投递区导出字节数'),
    tarName: mailboxFileName(source.tarName, '投递区导出归档名'),
    tarBytes: byteCount(source.tarBytes, '投递区导出归档大小'),
    tarSha256: source.tarSha256,
    manifestName: mailboxFileName(source.manifestName, '投递区导出清单名'),
    subdirectory: source.subdirectory === undefined
      ? undefined
      : optionalIdentifier(source.subdirectory, '投递区导出起点'),
    skippedLinks: byteCount(source.skippedLinks, '投递区跳过链接数'),
    skippedSpecial: byteCount(source.skippedSpecial, '投递区跳过特殊条目数'),
  }
}

/**
 * 断言导出起点的入参。
 *
 * 与原生侧同一套规则：省略或空白表示整个工作区；其余必须是不含 `.` / `..` 分段的相对路径。
 * 前端先拦一道，避免明显非法的取值跨过桥接。
 */
export function assertMailboxSubdirectory(value: string | undefined): string | undefined {
  if (value === undefined) return undefined
  const trimmed = value.trim()
  if (trimmed.length === 0) return undefined
  if (trimmed.length > MAX_MAILBOX_PATH_LENGTH || trimmed.startsWith('/') || trimmed.includes('\\')) {
    throw new Error('投递区导出起点格式无效')
  }
  const segments = trimmed.split('/')
  if (segments.some(segment => segment.length === 0 || segment === '.' || segment === '..')) {
    throw new Error('投递区导出起点格式无效')
  }
  return trimmed
}
