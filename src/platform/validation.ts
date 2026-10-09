import type {
  AccessibilityAutomationState,
  AllFilesAccessResult,
  AppUpdateState,
  DeviceCommand,
  DeviceCommandResult,
  DiagnosticLogExport,
  DiagnosticLogState,
  DiagnosticLogText,
  HarnessLog,
  KeepAliveState,
  MailboxAvailability,
  MailboxDirectoryState,
  MailboxExportResult,
  MailboxImportResult,
  MailboxRoot,
  MailboxState,
  MediaPermissionResult,
  ModelProviderId,
  NotificationPermission,
  NotificationPermissionResult,
  OverlayBallState,
  ProviderApiKeys,
  RuntimeInstallResult,
  RuntimeIntent,
  RuntimePhase,
  RuntimeProgress,
  RuntimeReleaseEntry,
  RuntimeReleaseList,
  RuntimeSessionListReason,
  RuntimeSessionListResult,
  RuntimeSessionSummary,
  RuntimeSessionSnapshot,
  RuntimeSessionSnapshotRestoreResult,
  RuntimeSessionSnapshotState,
  RuntimeResidueCleanupState,
  RuntimeResidueState,
  RuntimeSettings,
  RuntimeSettingsUpdate,
  RuntimeSource,
  RuntimeState,
  RuntimeVersionInfo,
  RuntimeVersionsState,
  ShizukuState,
  StorageAccessState,
  StorageDirAvailability,
  StorageDirectoryState,
  StorageDirEntry,
  StorageDirsState,
  TerminalChunk,
  TerminalExit,
  VirtualScreenAutoFollow,
  VirtualScreenOrientation,
  VirtualScreenPreviewMode,
  VirtualScreenSettings,
  VirtualScreenSettingsUpdate,
  VirtualScreenStartRequest,
  VirtualScreenState,
} from './types'
import {
  DIAGNOSTIC_LOG_MAX_CHARS,
  DIAGNOSTIC_LOG_WINDOW_OPTIONS,
  DIAGNOSTIC_RETENTION_MAX,
  DIAGNOSTIC_RETENTION_MIN,
  HARNESS_LOG_MAX_CHARS,
  HARNESS_LOG_WINDOW_OPTIONS,
  MAX_STORAGE_DIRECTORIES,
  MODEL_PROVIDER_IDS,
  VIRTUAL_SCREEN_AUTO_FOLLOW_VALUES,
  VIRTUAL_SCREEN_MAX_DPI,
  VIRTUAL_SCREEN_MAX_EDGE,
  VIRTUAL_SCREEN_MIN_DPI,
  VIRTUAL_SCREEN_MIN_EDGE,
  VIRTUAL_SCREEN_ORIENTATION_VALUES,
  VIRTUAL_SCREEN_PREVIEW_MODES,
  normalizeVirtualScreenPreviewMode,
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
/** 单次目录浏览最多返回的条目数；原生侧超出时以 truncated 标记。 */
const MAX_MAILBOX_DIRECTORY_ENTRIES = 256
/** 工作区文件列表与原生 `RuntimeWorkspaceFiles` 的上限保持一致。 */
const MAX_RUNTIME_WORKSPACE_FILES = 100
const MAX_RUNTIME_WORKSPACE_PATH_LENGTH = 240
/** 原生侧最多回传的版本槽数（当前 + 上一版本 + 内置）；超出即视为载荷不符合契约。 */
const MAX_RUNTIME_VERSIONS = 3
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

/**
 * 工作区文件只能是运行时工作区下的相对普通文件路径。
 *
 * 这条校验同时用于原生返回的列表和后续打开/分享/删除的入参：列表不是可信来源，
 * 不能让畸形载荷先进入 UI 再等用户点按钮时才失败。
 */
export function validateRuntimeWorkspaceFilePath(value: unknown): string {
  if (
    typeof value !== 'string' || value.length < 1 || value.length > MAX_RUNTIME_WORKSPACE_PATH_LENGTH ||
    value.startsWith('/') || value.includes('\\') || containsControlCharacter(value)
  ) {
    throw new Error('工作区文件路径无效')
  }
  const segments = value.split('/')
  if (segments.some(segment => segment.length === 0 || segment === '.' || segment === '..')) {
    throw new Error('工作区文件路径无效')
  }
  return value
}

/** 原生工作区文件列表的结构校验；未知字段被忽略，路径逐条走同一套规则。 */
export function validateRuntimeWorkspaceFileList(value: unknown): string[] {
  const source = asRecord(value, '工作区文件列表')
  if (!Array.isArray(source.files) || source.files.length > MAX_RUNTIME_WORKSPACE_FILES) {
    throw new Error('工作区文件列表格式无效')
  }
  const files = source.files.map(validateRuntimeWorkspaceFilePath)
  if (new Set(files).size !== files.length) throw new Error('工作区文件列表包含重复路径')
  return files
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

/**
 * 运行时版本操作目标：目前只有「上一版本」可切换或删除。
 *
 * 取值在前端就拦死，不让原生侧去猜未知目标。
 */
export function assertRuntimeVersionTarget(value: unknown): 'previous' {
  if (value !== 'previous') throw new Error('运行时版本操作目标无效')
  return value
}

export function validateRuntimeVersions(value: unknown): RuntimeVersionsState {
  const state = asRecord(value, '运行时版本状态')
  const source = state.versions
  if (!Array.isArray(source) || source.length > MAX_RUNTIME_VERSIONS) throw new Error('运行时版本列表格式无效')
  if (typeof state.canSwitch !== 'boolean') throw new Error('运行时版本切换状态格式无效')
  if (typeof state.canDelete !== 'boolean') throw new Error('运行时版本删除状态格式无效')

  const versions: RuntimeVersionInfo[] = source.map(entry => {
    const item = asRecord(entry, '运行时版本条目')
    if (item.slot !== 'current' && item.slot !== 'previous' && item.slot !== 'bundled') {
      throw new Error('运行时版本槽位格式无效')
    }
    if (typeof item.active !== 'boolean') throw new Error('运行时版本使用状态格式无效')
    const version = requiredIdentifier(item.version, '运行时版本号')
    const runtimeId = requiredIdentifier(item.runtimeId, '运行时标识')
    const extractedBytes = byteCount(item.extractedBytes, '运行时体积')
    const dshVersion = optionalIdentifier(item.dshVersion, 'dsh 版本')
    return {
      slot: item.slot,
      version,
      runtimeId,
      extractedBytes,
      active: item.active,
      ...(dshVersion === undefined ? {} : { dshVersion }),
    }
  })

  return { versions, canSwitch: state.canSwitch, canDelete: state.canDelete }
}

/**
 * 运行时占用盘点：份数、字节数与截断标记。
 *
 * 只认这三个字段的形状——残留载荷里根本没有路径字段的位置，这里也就不该有。
 */
export function validateRuntimeResidue(value: unknown): RuntimeResidueState {
  const state = asRecord(value, '运行时占用状态')
  return {
    count: byteCount(state.count, '运行时残留份数'),
    bytes: byteCount(state.bytes, '运行时残留体积'),
    truncated: requiredBoolean(state.truncated, '运行时残留截断标记'),
  }
}

const MAX_RESIDUE_MESSAGE_LENGTH = 200

/**
 * 一次清理残留的结果：**这一次尝试**的份数、字节数与一句如实说明。
 *
 * `cleaned` / `failed` 不是「当前还剩几份」——界面一律重新查一次盘点；`message` 只给人看不参与
 * 判断，但空串与超长仍然拦掉，免得把一条没有内容的提示渲染给用户。
 */
export function validateRuntimeResidueCleanup(value: unknown): RuntimeResidueCleanupState {
  const state = asRecord(value, '运行时残留清理结果')
  const message = state.message
  if (typeof message !== 'string' || message.trim().length === 0 || message.length > MAX_RESIDUE_MESSAGE_LENGTH) {
    throw new Error('运行时残留清理说明格式无效')
  }
  return {
    cleaned: byteCount(state.cleaned, '运行时残留回收份数'),
    failed: byteCount(state.failed, '运行时残留失败份数'),
    reclaimedBytes: byteCount(state.reclaimedBytes, '运行时残留回收体积'),
    message,
  }
}

export function validateRuntimeProgress(value: unknown): RuntimeProgress {  const progress = asRecord(value, '运行时进度')
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
  // 应用版本名（13.6.0 这种）与服务端 API 版本是两件事，走同一条标识符校验；
  // 原生侧已先按同一字符集过滤过，这里只做兜底。
  const appVersion = state.appVersion === undefined
    ? undefined
    : optionalIdentifier(state.appVersion, 'Shizuku 应用版本', IDENTIFIER_PATTERN, 32)
  return {
    installed: state.installed,
    running: state.running,
    permission: state.permission,
    connected: state.connected,
    ...(version === undefined ? {} : { version }),
    ...(appVersion === undefined ? {} : { appVersion }),
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
  // 「一轮对话结束」的两项信号：原生侧始终带上（从未收到时为 0），这里与其它可选字段一致，
  // 缺失就不写回，调用方按 0 处理——外壳只比较"序号有没有变新"，不依赖字段一定存在。
  let lastTurnCompletedAtMillis: number | undefined
  if (state.lastTurnCompletedAtMillis !== undefined) {
    if (!Number.isSafeInteger(state.lastTurnCompletedAtMillis) || (state.lastTurnCompletedAtMillis as number) < 0) {
      throw new Error('对话完成时间格式无效')
    }
    lastTurnCompletedAtMillis = state.lastTurnCompletedAtMillis as number
  }
  let turnCompletionSequence: number | undefined
  if (state.turnCompletionSequence !== undefined) {
    if (!Number.isSafeInteger(state.turnCompletionSequence) || (state.turnCompletionSequence as number) < 0) {
      throw new Error('对话完成序号格式无效')
    }
    turnCompletionSequence = state.turnCompletionSequence as number
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
    ...(lastTurnCompletedAtMillis === undefined ? {} : { lastTurnCompletedAtMillis }),
    ...(turnCompletionSequence === undefined ? {} : { turnCompletionSequence }),
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

const DEVICE_COMMANDS = new Set<DeviceCommand>([
  'screenshot',
  'uiDump',
  'tap',
  'inputText',
  'deviceInfo',
  'listPackages',
  'getSetting',
  'battery',
  'launchApp',
  'foregroundPackage',
  'wait',
  'fileList',
  'fileRead',
  'fileWrite',
  'fileMkdir',
  'fileDownload',
  'fileUpload',
  'shell',
  'backgroundTasks',
])
// 文件上传通过 Base64 传递，原生侧仍有 128 KiB 解码上限；这里保留 JSON 参数的有界窗口。
const MAX_DEVICE_PARAM_CHARS = 180_000
/** 「白名单验证密码」的长度区间；密码本身不会被前端留存或回显。 */
const ACCESSIBILITY_PASSWORD_MIN_CHARS = 6
const ACCESSIBILITY_PASSWORD_MAX_CHARS = 64

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
  const errorCode = result.errorCode === undefined
    ? undefined
    : optionalIdentifier(result.errorCode, '设备命令错误码', ERROR_CODE_PATTERN, MAX_ERROR_CODE_LENGTH)
  return {
    ok: result.ok,
    exitCode: result.exitCode,
    text: result.text,
    truncated: result.truncated,
    ...(errorCode === undefined ? {} : { errorCode }),
  }
}

/**
 * 校验一份无障碍包名列表：只接受原生层已经约束的标准格式，重复项直接拒绝（条目数量不设上限）。
 *
 * 去重与否刻意**不在这里悄悄修正**：重复项说明原生回了一份读不懂的状态，
 * 静默去重会让「白名单里有两条一样的东西」这种缺陷永远查不出来。
 */
function accessibilityPackages(value: unknown, label: string): string[] {
  if (!Array.isArray(value)) throw new Error(`${label}格式无效`)
  const packages = value.map((item, index) => {
    if (typeof item !== 'string' || item.length < 3 || item.length > 160 ||
        !/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*){1,12}$/u.test(item)) {
      throw new Error(`无障碍白名单第 ${index + 1} 项无效`)
    }
    return item
  })
  if (new Set(packages).size !== packages.length) throw new Error(`${label}包含重复项`)
  return packages
}

/**
 * 校验无障碍服务状态。
 *
 * 四个字段都必须存在：`alwaysAllowedPackages` 或 `passwordConfigured` 缺失时若补成默认值，
 * 界面会把「读不到状态」显示成「没有自动项 / 没设密码」，用户照着点下去只会不断失败。
 * `passwordConfigured` 只是布尔——**本函数不接触、也不返回任何密码内容**。
 */
export function validateAccessibilityAutomationState(value: unknown): AccessibilityAutomationState {
  const state = asRecord(value, '无障碍自动化状态')
  if (typeof state.enabled !== 'boolean') throw new Error('无障碍自动化状态格式无效')
  return {
    enabled: state.enabled,
    allowedPackages: accessibilityPackages(state.allowedPackages, '无障碍白名单'),
    // 兼容旧版壳：缺省仍开启限制，其他非布尔值拒绝。
    whitelistEnabled: state.whitelistEnabled === undefined ? true : requiredBoolean(state.whitelistEnabled, '无障碍白名单开关'),
    alwaysAllowedPackages: accessibilityPackages(state.alwaysAllowedPackages, '无障碍自动白名单'),
    passwordConfigured: requiredBoolean(state.passwordConfigured, '无障碍验证密码状态'),
  }
}

/**
 * 校验「白名单验证密码」入参：只在前端拦明显不合法的取值，真正的比对在原生侧。
 *
 * 密码**只用于本次过桥**：这里不记录、不缓存、不拼接进任何错误消息，
 * 错误文案里因此也不会出现用户输入的那串字符。
 */
export function validateAccessibilityPasswordInput(value: unknown, label: string): string {
  if (typeof value !== 'string') throw new Error(`${label}格式无效`)
  if (value.length < ACCESSIBILITY_PASSWORD_MIN_CHARS || value.length > ACCESSIBILITY_PASSWORD_MAX_CHARS) {
    throw new Error(`${label}需要 ${ACCESSIBILITY_PASSWORD_MIN_CHARS} 到 ${ACCESSIBILITY_PASSWORD_MAX_CHARS} 个字符`)
  }
  if (containsControlCharacter(value)) throw new Error(`${label}包含不可用字符`)
  // 首尾空白几乎都是粘贴带进来的：原样送过去只会让用户在原生侧反复收到「密码不正确」。
  if (value.trim() === '') throw new Error(`${label}不能全是空白`)
  if (value !== value.trim()) throw new Error(`${label}首尾不能有空白`)
  return value
}

export function validateDeviceShellAccess(value: unknown): { enabled: boolean } {
  if (!value || typeof value !== 'object' || typeof (value as Record<string, unknown>).enabled !== 'boolean') {
    throw new Error('AI Shell 授权状态格式无效')
  }
  return { enabled: (value as Record<string, boolean>).enabled }
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

/** 校验投递区目录浏览快照；路径和条目均只允许固定根下的相对值。 */
export function validateMailboxDirectoryState(value: unknown): MailboxDirectoryState {
  const source = asRecord(value, '投递区目录状态')
  const root = source.root
  if (root !== 'inbox' && root !== 'outbox') throw new Error('投递区根目录格式无效')
  if (!Array.isArray(source.entries) || source.entries.length > MAX_MAILBOX_DIRECTORY_ENTRIES) {
    throw new Error('投递区目录条目格式无效')
  }
  const path = source.path === undefined || source.path === null
    ? undefined
    : typeof source.path === 'string'
      ? assertMailboxSubdirectory(source.path)
      : (() => { throw new Error('投递区目录路径格式无效') })()
  return {
    root: root as MailboxRoot,
    path,
    entries: source.entries.map(item => {
      const entry = asRecord(item, '投递区目录条目')
      const kind = entry.kind
      if (kind !== 'file' && kind !== 'directory') throw new Error('投递区目录条目类型无效')
      return {
        name: mailboxFileName(entry.name, '投递区目录条目名称'),
        kind,
        bytes: byteCount(entry.bytes, '投递区目录条目大小'),
      }
    }),
    truncated: requiredBoolean(source.truncated, '投递区目录截断状态'),
  }
}

/**
 * 共享目录的访客路径：严格是 `/mnt/user/<序号>`，序号从 1 起且没有前导零。
 *
 * 与原生侧同一套形态规则：`/mnt/user`、`/mnt/user/01`、`/mnt/user/+1`、`/mnt/user/1/`、
 * `/mnt/user/1/extra` 全都不是合法取值 —— 它们指不到白名单里的任何一条目录。
 */
const STORAGE_GUEST_PATH_PATTERN = /^\/mnt\/user\/[1-9]\d*$/

/**
 * 校验共享目录浏览快照；访客路径与条目都只允许白名单里的受控取值。
 *
 * `path` 复用投递区的相对子目录规则（[assertMailboxSubdirectory]）：原生回传根目录时给的是
 * `null`，这里统一收敛成 `undefined`。条目的线形状与投递区逐字相同（`kind` 只有
 * `file` / `directory`，没有 `type`），单次上限也复用投递区那个常量。
 */
export function validateStorageDirectoryState(value: unknown): StorageDirectoryState {
  const source = asRecord(value, '共享目录状态')
  const guestPath = source.guestPath
  if (typeof guestPath !== 'string' || !STORAGE_GUEST_PATH_PATTERN.test(guestPath)) {
    throw new Error('共享目录路径格式无效')
  }
  if (!Array.isArray(source.entries) || source.entries.length > MAX_MAILBOX_DIRECTORY_ENTRIES) {
    throw new Error('共享目录条目格式无效')
  }
  const path = source.path === undefined || source.path === null
    ? undefined
    : typeof source.path === 'string'
      ? assertMailboxSubdirectory(source.path)
      : (() => { throw new Error('共享目录子路径格式无效') })()
  return {
    guestPath,
    path,
    entries: source.entries.map(item => {
      const entry = asRecord(item, '共享目录条目')
      const kind = entry.kind
      if (kind !== 'file' && kind !== 'directory') throw new Error('共享目录条目类型无效')
      return {
        name: mailboxFileName(entry.name, '共享目录条目名称'),
        kind,
        bytes: byteCount(entry.bytes, '共享目录条目大小'),
      }
    }),
    truncated: requiredBoolean(source.truncated, '共享目录截断状态'),
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
      : typeof source.subdirectory === 'string'
        ? assertMailboxSubdirectory(source.subdirectory)
        : (() => { throw new Error('投递区导出起点格式无效') })(),
    destinationDirectory: source.destinationDirectory === undefined
      ? undefined
      : typeof source.destinationDirectory === 'string'
        ? assertMailboxSubdirectory(source.destinationDirectory)
        : (() => { throw new Error('投递区导出目标目录格式无效') })(),
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

/**
 * 目录白名单条目的可用性档位。
 *
 * 只接受四个受控取值：未知取值一律抛错，而不是回落到 `available` ——
 * 把「读不懂的状态」显示成「可用」会让用户以为访客里真的能看到这个目录。
 */
const STORAGE_DIR_AVAILABILITIES = new Set<StorageDirAvailability>([
  'available',
  'unavailable',
  'needsPermission',
  'unsupported',
])

/** 与原生侧一致的路径上限（相对共享存储根 240 字符 + `/storage/emulated/0/` 前缀）。 */
const MAX_STORAGE_DIR_PATH_LENGTH = 20 + 240
const MAX_STORAGE_DIR_NAME_LENGTH = 64

/**
 * 白名单路径：必须是 `/storage/emulated/0/` 之下的绝对路径。
 *
 * 这条前缀是**契约的一部分**：原生侧只回传共享存储里的用户可见路径（私有路径与 rootfs 路径
 * 不进桥接载荷），因此任何越出该前缀的取值都说明载荷不符合契约，按格式无效处理。
 */
function storageDirPath(value: unknown): string {
  const label = '存储目录路径'
  if (
    typeof value !== 'string' || value.length < 2 || value.length > MAX_STORAGE_DIR_PATH_LENGTH ||
    !value.startsWith('/storage/emulated/0/') || value.includes('\\') || containsControlCharacter(value)
  ) {
    throw new Error(`${label}格式无效`)
  }
  const segments = value.slice(1).split('/')
  if (segments.some(segment => segment.length === 0 || segment === '.' || segment === '..')) {
    throw new Error(`${label}格式无效`)
  }
  return value
}

/** 断言移除操作的入参；与原生侧同一套规则（前端先拦一道明显非法的取值）。 */
export function assertStorageDirPath(path: string): string {
  return storageDirPath(path)
}

function storageDirAvailability(value: unknown): StorageDirAvailability {
  if (typeof value !== 'string' || !STORAGE_DIR_AVAILABILITIES.has(value as StorageDirAvailability)) {
    throw new Error('存储目录可用性格式无效')
  }
  return value as StorageDirAvailability
}

/**
 * 校验目录白名单状态。
 *
 * 除逐条校验外还钉住三组自洽关系：`count === entries.length`、`available === (availability
 * === 'available')`、`level` 与 `supported`/`granted` 一致。缺一个就会出现
 * 「按钮可点但原生说不可用」或「文案说 T2 却没有权限」这类自相矛盾的界面状态。
 */
export function validateStorageDirsState(value: unknown): StorageDirsState {
  const source = asRecord(value, '存储目录白名单状态')
  const supported = requiredBoolean(source.supported, '存储目录支持状态')
  const granted = requiredBoolean(source.granted, '存储目录授权状态')
  if (granted && !supported) throw new Error('存储目录白名单状态自相矛盾')
  const level = source.level
  if (level !== 'T2' && level !== 'T0') throw new Error('存储目录权限档位格式无效')
  if (level !== (supported && granted ? 'T2' : 'T0')) throw new Error('存储目录白名单状态自相矛盾')
  if (source.maxDirectories !== MAX_STORAGE_DIRECTORIES) throw new Error('存储目录上限格式无效')
  if (!Array.isArray(source.entries) || source.entries.length > MAX_STORAGE_DIRECTORIES) {
    throw new Error('存储目录条目格式无效')
  }
  if (source.count !== source.entries.length) throw new Error('存储目录白名单状态自相矛盾')
  const entries: StorageDirEntry[] = source.entries.map((item, position) => {
    const entry = asRecord(item, '存储目录条目')
    // 序号来自持久化顺序：第 n 条的序号必须是 n（1 起）。重排会让 /mnt/user/<序号> 指向别的目录。
    if (entry.index !== position + 1) throw new Error('存储目录序号格式无效')
    const availability = storageDirAvailability(entry.availability)
    const available = requiredBoolean(entry.available, '存储目录可用性')
    if (available !== (availability === 'available')) throw new Error('存储目录条目自相矛盾')
    // 逐条的权限档由可用性唯一决定：不一致说明载荷不符合契约。
    const entryLevel: 'T2' | 'T0' = availability === 'available' ? 'T2' : 'T0'
    if (entry.level !== entryLevel) throw new Error('存储目录权限档位格式无效')
    if (
      typeof entry.displayName !== 'string' || entry.displayName.length === 0 ||
      entry.displayName.length > MAX_STORAGE_DIR_NAME_LENGTH || entry.displayName.includes('/') ||
      containsControlCharacter(entry.displayName)
    ) {
      throw new Error('存储目录名称格式无效')
    }
    if (entry.guestPath !== `/mnt/user/${position + 1}`) throw new Error('存储目录挂载点格式无效')
    let reasonCode: string | undefined
    if (entry.reasonCode !== undefined) {
      if (
        typeof entry.reasonCode !== 'string' || entry.reasonCode.length > MAX_ERROR_CODE_LENGTH ||
        !ERROR_CODE_PATTERN.test(entry.reasonCode) || availability === 'available'
      ) {
        throw new Error('存储目录错误码格式无效')
      }
      reasonCode = entry.reasonCode
    }
    return {
      index: position + 1,
      path: storageDirPath(entry.path),
      displayName: entry.displayName,
      guestPath: entry.guestPath,
      availability,
      level: entryLevel,
      available,
      reasonCode,
    }
  })
  const active = requiredBoolean(source.active, '存储目录生效状态')
  if (active !== entries.some(entry => entry.available)) throw new Error('存储目录白名单状态自相矛盾')
  return {
    entries,
    maxDirectories: MAX_STORAGE_DIRECTORIES,
    count: entries.length,
    supported,
    granted,
    level,
    active,
  }
}

/** 原生侧单次最多列出的运行时可用版本数；超出即视为载荷不符合契约。 */
const MAX_RUNTIME_RELEASES = 40
/** 运行时版本清单地址的长度上限；与原生侧保持一致。 */
const MAX_RELEASE_MANIFEST_URL_LENGTH = 1024
/** Android versionCode 的受控上限；超出说明这份载荷不是本应用该有的版本代码。 */
const MAX_APP_VERSION_CODE = 2_100_000_000
/** 单个应用更新包的体积上限（4 GiB = 4294967296 字节）。 */
const MAX_APP_UPDATE_BYTES = 4 * 1024 * 1024 * 1024
/** 更新说明的字符上限；超出直接报错，不截断——截断会改变用户读到的更新内容。 */
const MAX_APP_UPDATE_NOTES_LENGTH = 4000

/**
 * 校验运行时可用版本列表。
 *
 * `manifestUrl` 与 `manifestSha256` 要么都有、要么都没有：只有一个说明原生侧回了一份
 * 半截载荷，宁可报错也不能让界面把它当成「可安装」，更不能替它补另一半。
 */
export function validateRuntimeReleaseList(value: unknown): RuntimeReleaseList {
  const list = asRecord(value, '运行时可用版本状态')
  const source = list.entries
  if (!Array.isArray(source) || source.length > MAX_RUNTIME_RELEASES) {
    throw new Error('运行时可用版本列表格式无效')
  }

  const entries: RuntimeReleaseEntry[] = source.map(item => {
    const entry = asRecord(item, '运行时可用版本条目')
    const version = requiredIdentifier(entry.version, '运行时可用版本号')
    const dshVersion = optionalIdentifier(entry.dshVersion, '运行时内置 dsh 版本')
    const hasManifestUrl = entry.manifestUrl !== undefined
    const hasManifestSha256 = entry.manifestSha256 !== undefined
    if (hasManifestUrl !== hasManifestSha256) {
      throw new Error('运行时版本清单地址与 SHA-256 必须同时出现或同时缺失')
    }
    if (!hasManifestUrl) {
      return { version, ...(dshVersion === undefined ? {} : { dshVersion }) }
    }

    const manifestUrl = entry.manifestUrl
    if (
      typeof manifestUrl !== 'string' || manifestUrl.length > MAX_RELEASE_MANIFEST_URL_LENGTH ||
      !manifestUrl.startsWith('https://')
    ) {
      throw new Error('运行时版本清单地址格式无效')
    }
    const manifestSha256 = entry.manifestSha256
    if (typeof manifestSha256 !== 'string' || !SHA256_PATTERN.test(manifestSha256)) {
      throw new Error('运行时版本清单 SHA-256 必须是 64 位小写十六进制')
    }
    return { version, manifestUrl, manifestSha256, ...(dshVersion === undefined ? {} : { dshVersion }) }
  })

  return { entries }
}

function appVersionCode(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0 || (value as number) > MAX_APP_VERSION_CODE) {
    throw new Error('已安装应用版本代码格式无效')
  }
  return value as number
}

function appUpdateBytes(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) <= 0 || (value as number) > MAX_APP_UPDATE_BYTES) {
    throw new Error('应用更新包大小格式无效')
  }
  return value as number
}

/**
 * 校验应用自身的更新状态。
 *
 * `installAllowed` 缺失或非布尔一律报错：把未知当成 false 会让已授权的用户白跑一趟
 * 系统设置页，当成 true 则会让界面承诺一次必然失败的安装。
 */
export function validateAppUpdateState(value: unknown): AppUpdateState {
  const state = asRecord(value, '应用更新状态')
  const installedVersion = requiredIdentifier(state.installedVersion, '已安装应用版本号')
  const installedVersionCode = appVersionCode(state.installedVersionCode)
  if (typeof state.installAllowed !== 'boolean') throw new Error('应用更新安装权限状态无效')

  if (state.available === undefined) {
    return { installedVersion, installedVersionCode, installAllowed: state.installAllowed }
  }

  const release = asRecord(state.available, '可用应用更新')
  const version = requiredIdentifier(release.version, '应用更新版本号')
  const bytes = appUpdateBytes(release.bytes)
  if (typeof release.sha256 !== 'string' || !SHA256_PATTERN.test(release.sha256)) {
    throw new Error('应用更新包 SHA-256 必须是 64 位小写十六进制')
  }
  if (typeof release.notes !== 'string') throw new Error('应用更新说明格式无效')
  if (release.notes.length > MAX_APP_UPDATE_NOTES_LENGTH) throw new Error('应用更新说明长度无效')

  return {
    installedVersion,
    installedVersionCode,
    installAllowed: state.installAllowed,
    available: { version, bytes, sha256: release.sha256, notes: release.notes },
  }
}

/**
 * 会话标识形态：**与原生侧 `SessionCatalogPayload` 同一条规则**——拒绝控制字符、斜杠、反斜杠与
 * 空白，长度 1..200，且不以 `.` 开头。
 *
 * 两层必须一致：这里放松会让带路径的 id 进到界面，这里收紧会让真实会话整块读取失败。
 * 用「危险字符」而不是窄字符集白名单，是因为会话 id 由 dsh 生成，形态可能变化。
 *
 * 按码点扫描而不是写正则：`[\u0000-\u001F]` 这样的字符组会触发 `no-control-regex`，
 * 本仓库没有豁免先例，而扫描与文件内 `containsControlCharacter` 是同一套写法。
 */
function isCatalogSessionId(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_SESSION_ID_CHARS) return false
  if (value.startsWith('.')) return false
  return !Array.from(value).some(character => {
    const code = character.charCodeAt(0)
    return code <= 31 || code === 127 || character === '/' || character === '\\' || /\s/.test(character)
  })
}
/** 会话标识长度上限：与原生侧 `SessionCatalogPayload.MAX_IDENTIFIER_CHARS` 同值。 */
const MAX_SESSION_ID_CHARS = 200
/** 会话条数上限：访客脚本与原生侧都按 50 截断，超出的载荷视为不可信。 */
const MAX_RUNTIME_SESSIONS = 50
/** 标题长度上限（UTF-16 字符数，与原生侧 `MAX_TITLE_CHARS` 同口径）：宿主侧标题最长 80 字节，200 字符只是防御性天花板。 */
const MAX_SESSION_TITLE_CHARS = 200
/** 更新时间上限（毫秒，2100-01-01）：明显越界的取值视为不可信。 */
const MAX_SESSION_UPDATED_AT = 4_102_444_800_000
/** 受控的读取失败原因：多一个少一个都要在这里显式登记，界面才能给出对应说明。 */
const SESSION_LIST_REASONS = new Set<RuntimeSessionListReason>([
  'RUNTIME_NOT_INSTALLED',
  'SESSION_CATALOG_TIMEOUT',
  'SESSION_CATALOG_FAILED',
])

/** 会话快照份数的防御性上限：原生当前上限是 3，但上限本身会变，校验不该把 4 当非法。 */
const MAX_RUNTIME_SESSION_SNAPSHOTS = 32
/** 会话快照占用空间的上限（512 MiB）；与原生侧保持一致。 */
const MAX_RUNTIME_SESSION_SNAPSHOT_BYTES = 512 * 1024 * 1024
/**
 * 快照标识：`snap-<毫秒时间戳>-<序号>`。
 *
 * **故意宽松**：不写死序号位数（原生现在是 8 位十六进制）。原生以后改格式不该把界面打死，
 * 这条只用来挡住「明显不是标识」的值（空串、带路径、带斜杠）。
 */
const RUNTIME_SESSION_SNAPSHOT_ID_PATTERN = /^snap-[0-9]{10,16}-[0-9a-zA-Z]{1,16}$/

/** 快照标识的宽松校验：只看形态，不查它是否真的存在（那是原生侧的事）。 */
function requiredSnapshotId(value: unknown): string {
  if (typeof value !== 'string' || !RUNTIME_SESSION_SNAPSHOT_ID_PATTERN.test(value)) {
    throw new Error('运行时会话快照标识格式无效')
  }
  return value
}

/** 正整数且不超过 [maximum]；用于「上限」这类有防御性天花板的字段。 */
function boundedPositiveInteger(value: unknown, maximum: number, message: string): number {
  if (!Number.isSafeInteger(value) || (value as number) <= 0 || (value as number) > maximum) {
    throw new Error(message)
  }
  return value as number
}

/** `evictedIds` 是契约之外的附加字段：存在时必须是快照标识数组，否则这份载荷不可信。 */
function snapshotIdList(value: unknown): string[] {
  if (!Array.isArray(value)) throw new Error('运行时自动快照淘汰列表格式无效')
  return value.map(item => requiredSnapshotId(item))
}

/**
 * 校验会话快照总览。
 *
 * 未知键一律忽略：原生侧以后加字段不该让整块界面失败（`evictedIds` 就是这么加进来的）。
 * `snapshots` 的长度上限用载荷自己给的 `maxSnapshots`，而不是写死原生今天那个 3——
 * 上限会变，界面跟着载荷走。
 */
export function validateRuntimeSessionSnapshotState(value: unknown): RuntimeSessionSnapshotState {
  const state = asRecord(value, '运行时会话快照状态')
  const maxSnapshots = boundedPositiveInteger(state.maxSnapshots, MAX_RUNTIME_SESSION_SNAPSHOTS, '会话快照份数上限无效')
  const maxBytes = boundedPositiveInteger(state.maxBytes, MAX_RUNTIME_SESSION_SNAPSHOT_BYTES, '会话快照空间上限无效')
  const totalBytes = state.totalBytes
  if (!Number.isSafeInteger(totalBytes) || (totalBytes as number) < 0 || (totalBytes as number) > maxBytes) {
    throw new Error('会话快照已用空间无效')
  }

  const source = state.snapshots
  if (!Array.isArray(source) || source.length > maxSnapshots) throw new Error('运行时会话快照列表格式无效')

  const snapshots: RuntimeSessionSnapshot[] = source.map(item => {
    const entry = asRecord(item, '运行时会话快照条目')
    const id = requiredSnapshotId(entry.id)
    const createdAt = entry.createdAt
    if (typeof createdAt !== 'string' || !Number.isFinite(Date.parse(createdAt))) {
      throw new Error('运行时会话快照创建时间格式无效')
    }
    const bytes = boundedPositiveInteger(entry.bytes, maxBytes, '运行时会话快照大小格式无效')
    const fileCount = byteCount(entry.fileCount, '运行时会话快照文件数格式无效')
    const runtimeVersion = optionalIdentifier(entry.runtimeVersion, '运行时会话快照运行时版本')
    const dshVersion = optionalIdentifier(entry.dshVersion, '运行时会话快照内置 dsh 版本')
    return {
      id,
      createdAt,
      bytes,
      fileCount,
      ...(runtimeVersion === undefined ? {} : { runtimeVersion }),
      ...(dshVersion === undefined ? {} : { dshVersion }),
    }
  })

  return { maxSnapshots, maxBytes, totalBytes: totalBytes as number, snapshots }
}

/** 校验快照恢复结果：两个计数都是非负整数，内嵌的总览走上面同一套校验。 */
export function validateRuntimeSessionSnapshotRestoreResult(value: unknown): RuntimeSessionSnapshotRestoreResult {
  const result = asRecord(value, '运行时会话快照恢复结果')
  return {
    restoredFileCount: byteCount(result.restoredFileCount, '运行时会话快照恢复文件数格式无效'),
    skippedFileCount: byteCount(result.skippedFileCount, '运行时会话快照跳过文件数格式无效'),
    state: validateRuntimeSessionSnapshotState(result.state),
  }
}

/**
 * 校验会话列表读取结果。
 *
 * 两态判别只看 `status`，未知键一律忽略（原生侧以后加字段不该让整块界面失败）。
 * 每条**只取** `id` / `title` / `updatedAt` 三个字段：正文、路径、`cwd` 之类即便混进载荷也到不了
 * 界面——这里的返回体是与界面之间的最后一个约定点。
 *
 * 校验器只负责拒绝「形状不符」的载荷，不负责过滤：一条不合规的会话（含路径、含控制字符）
 * 会让整块读取失败，而不是被悄悄丢掉——静默少一条会话同样是编造事实。
 */
export function validateRuntimeSessionListResult(value: unknown): RuntimeSessionListResult {
  const result = asRecord(value, '会话列表')
  if (result.status === 'unavailable') {
    const reason = result.reason
    if (typeof reason !== 'string' || !SESSION_LIST_REASONS.has(reason as RuntimeSessionListReason)) {
      throw new Error('会话列表读取失败原因无效')
    }
    return { status: 'unavailable', reason: reason as RuntimeSessionListReason }
  }
  if (result.status !== 'ready') throw new Error('会话列表状态无效')

  const source = result.sessions
  if (!Array.isArray(source) || source.length > MAX_RUNTIME_SESSIONS) throw new Error('会话列表格式无效')

  const sessions: RuntimeSessionSummary[] = source.map(item => {
    const entry = asRecord(item, '会话条目')
    const id = entry.id
    if (!isCatalogSessionId(id)) throw new Error('会话标识格式无效')
    const title = entry.title
    if (
      typeof title !== 'string' ||
      title.length > MAX_SESSION_TITLE_CHARS ||
      containsControlCharacter(title)
    ) {
      throw new Error('会话标题格式无效')
    }
    const updatedAt = entry.updatedAt
    if (!Number.isSafeInteger(updatedAt) || (updatedAt as number) < 0 || (updatedAt as number) > MAX_SESSION_UPDATED_AT) {
      throw new Error('会话更新时间格式无效')
    }
    return { id, title, updatedAt: updatedAt as number }
  })

  return { status: 'ready', sessions, truncated: requiredBoolean(result.truncated, '会话列表截断标记') }
}

/**
 * 校验运行时安装结果。
 *
 * `undefined` / `null` / `{}` 都合法，一律归一化成 `{}`：安装本来就可以没有自动快照这个键
 * （旧版原生桥接的 `install` 什么都不返回），界面不该因为少了一个可选结论就报错。
 */
export function validateRuntimeInstallResult(value: unknown): RuntimeInstallResult {
  if (value === undefined || value === null) return {}
  const result = asRecord(value, '运行时安装结果')
  if (result.autoSnapshot === undefined) return {}

  const outcome = asRecord(result.autoSnapshot, '运行时自动快照结论')
  const status = outcome.status
  if (status !== 'created' && status !== 'skipped' && status !== 'failed') {
    throw new Error('运行时自动快照结论状态无效')
  }
  const evictedIds = outcome.evictedIds === undefined ? undefined : snapshotIdList(outcome.evictedIds)

  if (status === 'created') {
    if (outcome.snapshotId === undefined) throw new Error('运行时自动快照结论缺少快照标识')
    return {
      autoSnapshot: {
        status,
        snapshotId: requiredSnapshotId(outcome.snapshotId),
        ...(evictedIds === undefined ? {} : { evictedIds }),
      },
    }
  }

  // skipped / failed：错误码必须存在（受控枚举由原生侧保证），给用户看的说明去空白后不能为空。
  if (typeof outcome.code !== 'string' || outcome.code === '') throw new Error('运行时自动快照结论缺少错误码')
  if (typeof outcome.message !== 'string' || outcome.message.trim() === '') {
    throw new Error('运行时自动快照结论缺少说明')
  }
  return {
    autoSnapshot: {
      status,
      code: outcome.code,
      message: outcome.message,
      ...(evictedIds === undefined ? {} : { evictedIds }),
    },
  }
}

/**
 * 目标应用副屏：档位 / 自动跟随 / 方向的受控枚举。
 *
 * 用 `Set` 查询而不是逐项 `!==` 串联：档位有 10 档，写成一串比较既容易漏项，
 * 也没法在别处复用同一份口径（界面与校验共用 `types.ts` 里的那三个数组）。
 */
const VIRTUAL_SCREEN_PREVIEW_MODE_SET = new Set<string>(VIRTUAL_SCREEN_PREVIEW_MODES)
const VIRTUAL_SCREEN_AUTO_FOLLOW_SET = new Set<string>(VIRTUAL_SCREEN_AUTO_FOLLOW_VALUES)
const VIRTUAL_SCREEN_ORIENTATION_SET = new Set<string>(VIRTUAL_SCREEN_ORIENTATION_VALUES)

/** 副屏包名与无障碍白名单同一条规则：至少一个点、以字母开头，避免把畸形字符串送进原生。 */
const PACKAGE_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*){1,12}$/u

function virtualScreenPreviewMode(value: unknown): VirtualScreenPreviewMode {
  if (typeof value !== 'string' || !VIRTUAL_SCREEN_PREVIEW_MODE_SET.has(value)) throw new Error('副屏取帧档位格式无效')
  return value as VirtualScreenPreviewMode
}

/**
 * 校验状态里的档位：原生写状态时用的是标签形式（`limited-fps` / `realtime-60fps`）。
 *
 * 认不出时回落 `limited`，与原生 `VirtualScreenPolicy.frameModeOf` 同一口径——
 * 状态是**读数**，为一个看不懂的档位整条读失败、让界面显示「读不到状态」，对用户更没用。
 */
function virtualScreenStatePreviewMode(value: unknown): VirtualScreenPreviewMode {
  if (typeof value !== 'string') throw new Error('副屏状态格式无效')
  return normalizeVirtualScreenPreviewMode(value) ?? 'limited'
}

function virtualScreenAutoFollow(value: unknown): VirtualScreenAutoFollow {
  if (typeof value !== 'string' || !VIRTUAL_SCREEN_AUTO_FOLLOW_SET.has(value)) throw new Error('副屏自动跟随策略格式无效')
  return value as VirtualScreenAutoFollow
}

function virtualScreenOrientation(value: unknown): VirtualScreenOrientation {
  if (typeof value !== 'string' || !VIRTUAL_SCREEN_ORIENTATION_SET.has(value)) throw new Error('副屏方向格式无效')
  return value as VirtualScreenOrientation
}

/** 非负有限数：帧率与刷新率是浮点读数，0 表示未采样（界面显示「未知」）。 */
function requiredNonNegativeNumber(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) throw new Error(`${label}格式无效`)
  return value
}

/** 非负安全整数：宽高与 DPI 未就绪时是 0（界面显示「未知」），负数一律拒绝。 */
function requiredNonNegativeInteger(value: unknown, label: string): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw new Error(`${label}格式无效`)
  return value as number
}

/**
 * 可负的安全整数：`displayId` 未就绪时原生给的是 -1。
 *
 * 与宽高、DPI 分开两条规则，正是因为 -1 在这里是**合法读数**：
 * 若一并要求非负，副屏没启动时整条状态读取都会失败，界面就永远只能显示「读不到状态」。
 */
function requiredIntegerValue(value: unknown, label: string): number {
  if (!Number.isSafeInteger(value)) throw new Error(`${label}格式无效`)
  return value as number
}

/**
 * 允许「没有值」的短文本。
 *
 * 副屏状态里「取不到」既可能是空串（会话标识），也可能是 JSON 的 `null`
 * （本轮前台应用、Activity、目标包名：原生侧刻意写 null 而不是编一个包名）：
 * 两种都收敛成空串交给界面显示「未知」，**不补默认值、也不当成读取失败**。
 */
function optionalStateText(value: unknown, label: string, maximumLength = 200): string {
  if (value === null || value === undefined) return ''
  if (typeof value !== 'string' || value.length > maximumLength || containsControlCharacter(value)) {
    throw new Error(`${label}格式无效`)
  }
  return value
}

/**
 * 这一项到底传没传。
 *
 * 原生侧（与 `docs/副屏设置桥.md` 的约定）把 `undefined` 与 `null` 都当作「这一项不改」：
 * 前端必须原样放过，别把 `null` 当成非法值拦下来，也别拿默认值补上去。
 */
function present(value: unknown): boolean {
  return value !== undefined && value !== null
}

/**
 * 写入路径的宽高：**0 与负数表示「清掉自定义尺寸」**，正数越界按原生 `coerceIn` 夹取。
 *
 * 与读取路径（`requiredNonNegativeInteger`）刻意不同：原生侧 0 的语义是「没有自定义过」，
 * 界面靠它把尺寸重置回自适应或方向预设；要是像越界那样一律夹成 200，用户就再也回不到预设规格了。
 * 类型错误仍然直接拒绝——静默把 `"800"` 当数字会让写错入参的人看不出问题。
 */
function virtualScreenEdge(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('副屏宽高格式无效')
  if (value <= 0) return 0
  return Math.min(Math.max(Math.round(value), VIRTUAL_SCREEN_MIN_EDGE), VIRTUAL_SCREEN_MAX_EDGE)
}

/** 写入路径的 DPI：规则同 `virtualScreenEdge`（0 与负数清掉自定义尺寸，正数夹进区间）。 */
function virtualScreenDensity(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('副屏像素密度格式无效')
  if (value <= 0) return 0
  return Math.min(Math.max(Math.round(value), VIRTUAL_SCREEN_MIN_DPI), VIRTUAL_SCREEN_MAX_DPI)
}

/**
 * 校验原生返回的副屏设置。
 *
 * 七个字段一个都不能少：缺字段时若补默认值，界面会把「读不到设置」显示成
 * 「档位 60 FPS、方向自动」，用户改完保存才发现原生根本没读到——这里必须抛错。
 *
 * 宽高与 DPI 这三项**允许 0**：原生侧用 0 表示「用户没有自定义尺寸」，
 * 这时生效规格由规格层按自适应开关与屏幕方向算出来。三者的区间校验留在
 * `assertVirtualScreenSettingsUpdate`（写入路径）——把 0 也要求成 200..4096，
 * 一台没自定义过尺寸的设备会永远读不出设置。
 */
export function validateVirtualScreenSettings(value: unknown): VirtualScreenSettings {
  const source = asRecord(value, '副屏设置')
  return {
    previewMode: virtualScreenPreviewMode(source.previewMode),
    autoFollow: virtualScreenAutoFollow(source.autoFollow),
    orientation: virtualScreenOrientation(source.orientation),
    adaptive: requiredBoolean(source.adaptive, '副屏自适应开关'),
    widthPx: requiredNonNegativeInteger(source.widthPx, '副屏宽度'),
    heightPx: requiredNonNegativeInteger(source.heightPx, '副屏高度'),
    densityDpi: requiredNonNegativeInteger(source.densityDpi, '副屏像素密度'),
  }
}

/**
 * 校验原生返回的副屏状态。
 *
 * 十五个字段都必须存在（`active` 为假时其余字段照样要在，只是取值为空/0/-1）：
 * 与悬浮球、存储访问那两条同一理由——字段缺失时抛错，而不是补成「未运行」，
 * 否则「读不到状态」会被界面显示成「副屏没在跑」，用户以为停掉了、其实还在跑。
 */
export function validateVirtualScreenState(value: unknown): VirtualScreenState {
  const source = asRecord(value, '副屏状态')
  return {
    active: requiredBoolean(source.active, '副屏运行状态'),
    sessionId: optionalStateText(source.sessionId, '副屏会话标识', 160),
    displayId: requiredIntegerValue(source.displayId, '副屏显示标识'),
    previewMode: virtualScreenStatePreviewMode(source.previewMode),
    autoFollow: virtualScreenAutoFollow(source.autoFollow),
    orientation: virtualScreenOrientation(source.orientation),
    adaptive: requiredBoolean(source.adaptive, '副屏自适应开关'),
    widthPx: requiredNonNegativeInteger(source.widthPx, '副屏宽度'),
    heightPx: requiredNonNegativeInteger(source.heightPx, '副屏高度'),
    densityDpi: requiredNonNegativeInteger(source.densityDpi, '副屏像素密度'),
    targetPackage: optionalStateText(source.targetPackage, '副屏目标应用', 160),
    frameFps: requiredNonNegativeNumber(source.frameFps, '副屏采集帧率'),
    displayRefreshRate: requiredNonNegativeNumber(source.displayRefreshRate, '副屏实际刷新率'),
    virtualForegroundPackage: optionalStateText(source.virtualForegroundPackage, '副屏前台应用', 160),
    virtualForegroundActivity: optionalStateText(source.virtualForegroundActivity, '副屏前台页面', 200),
  }
}

/**
 * 校验「保存副屏设置」的入参：只在前端拦明显不合法的取值，落盘与否在原生侧。
 *
 * 只处理**出现过的**字段——没传的字段必须原样缺席，不能补成某个默认值再发过去：
 * 那等于用界面的默认值覆盖用户在别处（悬浮窗快捷入口）刚改过的设置。
 * 宽高与 DPI 允许 0：0 是原生侧「清掉自定义尺寸」的合法取值（页面拿它做重置），不算越界。
 */
export function assertVirtualScreenSettingsUpdate(value: unknown): VirtualScreenSettingsUpdate {
  const source = asRecord(value, '副屏设置更新')
  const update: VirtualScreenSettingsUpdate = {}
  if (present(source.previewMode)) update.previewMode = virtualScreenPreviewMode(source.previewMode)
  if (present(source.autoFollow)) update.autoFollow = virtualScreenAutoFollow(source.autoFollow)
  if (present(source.orientation)) update.orientation = virtualScreenOrientation(source.orientation)
  if (present(source.adaptive)) update.adaptive = requiredBoolean(source.adaptive, '副屏自适应开关')
  if (present(source.widthPx)) update.widthPx = virtualScreenEdge(source.widthPx)
  if (present(source.heightPx)) update.heightPx = virtualScreenEdge(source.heightPx)
  if (present(source.densityDpi)) update.densityDpi = virtualScreenDensity(source.densityDpi)
  return update
}

/** 校验「启动副屏」的入参：包名必填且必须是标准包名，其余可选字段与保存设置同一套规则。 */
export function assertVirtualScreenStartRequest(value: unknown): VirtualScreenStartRequest {
  const source = asRecord(value, '启动副屏参数')
  const packageName = source.packageName
  if (typeof packageName !== 'string' || packageName.length < 3 || packageName.length > 160 || !PACKAGE_NAME_PATTERN.test(packageName)) {
    throw new Error('目标应用包名格式无效')
  }
  const request: VirtualScreenStartRequest = { packageName }
  if (present(source.adaptive)) request.adaptive = requiredBoolean(source.adaptive, '副屏自适应开关')
  if (present(source.orientation)) request.orientation = virtualScreenOrientation(source.orientation)
  if (present(source.widthPx)) request.widthPx = virtualScreenEdge(source.widthPx)
  if (present(source.heightPx)) request.heightPx = virtualScreenEdge(source.heightPx)
  if (present(source.densityDpi)) request.densityDpi = virtualScreenDensity(source.densityDpi)
  return request
}
