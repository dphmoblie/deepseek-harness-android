export type RuntimePhase =
  | 'not-installed'
  | 'preparing'
  | 'downloading'
  | 'verifying'
  | 'extracting'
  | 'ready'
  | 'running'
  | 'stopping'
  | 'error'

export type TerminalKind = 'ubuntu' | 'device'

export type DeviceCommand = 'screenshot' | 'uiDump' | 'tap' | 'inputText'

export const MODEL_PROVIDER_IDS = [
  'deepseek',
  'openai',
  'anthropic',
  'google',
  'openrouter',
  'groq',
  'xai',
  'mistral',
] as const

export type ModelProviderId = typeof MODEL_PROVIDER_IDS[number]

export type ProviderApiKeys = Partial<Record<ModelProviderId, string>>

export const CUSTOM_PROVIDER_APIS = ['openai-completions', 'openai-responses', 'anthropic-messages'] as const
export type CustomProviderApi = typeof CUSTOM_PROVIDER_APIS[number]

export interface CustomProviderModel {
  id: string
  name: string
  contextWindow: number
  maxTokens: number
}

export interface CustomModelProvider {
  id: string
  name: string
  api: CustomProviderApi
  baseUrl: string
  models: CustomProviderModel[]
}

export interface RuntimeState {
  phase: RuntimePhase
  architecture: string
  installedVersion?: string
  updateAvailable: boolean
  downloadedBytes: number
  totalBytes: number
  runnerAvailable: boolean
  harnessUrl?: string
  errorCode?: string
}

export interface RuntimeSource {
  manifestUrl: string
  manifestSha256: string
}

export interface RuntimeSettings extends RuntimeSource {
  keepScreenAwake: boolean
  terminalFontSize: number
  /** 已配置凭据的供应商；只返回状态，不向 WebView 回传凭据明文。 */
  configuredModelProviders: ModelProviderId[]
  /** 自定义供应商元数据；旧版桥接可缺省，保存的密钥绝不回传。 */
  customModelProviders?: CustomModelProvider[]
  configuredCustomModelProviders?: string[]
  /** 旧版原生桥接兼容字段；校验后只迁移为 DeepSeek 的已配置状态。 */
  apiKey?: string
  /** 打开应用时自动启动 Harness（默认 true）；旧存储/测试可能缺省。 */
  autoLaunch?: boolean
  /**
   * 后台保持 Harness（默认 false，旧存储/旧桥接缺省时按 false 处理）。
   * 开启后运行时会使用前台服务，提升进程存活优先级；
   * 但不能阻止 Android 或厂商系统在内存、电量或后台策略下结束进程。
   */
  keepRuntimeInBackground?: boolean
}

export interface RuntimeSettingsUpdate extends RuntimeSettings {
  /** 本次写入的凭据增量；原生端只接受固定供应商白名单。 */
  providerApiKeys?: ProviderApiKeys
  /** 本次明确清除的供应商凭据。 */
  clearProviderApiKeys?: ModelProviderId[]
  customProviderApiKeys?: Record<string, string>
  clearCustomProviderApiKeys?: string[]
}
export interface RuntimeProgress {
  phase: RuntimePhase
  downloadedBytes: number
  totalBytes: number
  errorCode?: string
}

export interface ShizukuState {
  installed: boolean
  running: boolean
  permission: 'granted' | 'denied' | 'undetermined'
  connected: boolean
  /** Shizuku 服务端版本（诊断用；未安装为空串）。 */
  version?: string
}

/** Android 13+ 前台服务通知权限；unsupported 表示系统版本低于 Android 13。 */
export type NotificationPermission = 'granted' | 'prompt' | 'unsupported'

/** 持久化的运行意图；unknown 表示从未记录。 */
export type RuntimeIntent = 'running' | 'stopped' | 'unknown'

/**
 * 后台保持与恢复状态。
 *
 * 只包含布尔值、枚举与时间戳：不含 Harness 地址、临时 Basic Auth 密码、
 * 模型 API Key、终端内容或其他用户数据，可安全用于界面显示。
 */
export interface KeepAliveState {
  /** 设置中的「后台保持 Harness」开关当前值。 */
  keepRuntimeInBackground: boolean
  /** 前台服务当前是否正在负责本机运行时。 */
  foregroundServiceActive: boolean
  /** 通知权限状态；仅影响 Android 13+ 是否显示前台服务通知。 */
  notificationPermission: NotificationPermission
  /** Shizuku 设备 Shell 是否已授权可用；仅用于辅助连接恢复，未授权时降级为 false。 */
  deviceShellReady: boolean
  /** 进程被系统回收后存在无法复用的旧会话，需要重新连接。 */
  reconnectRequired: boolean
  /** 持久化的最后一次运行意图。 */
  lastIntent: RuntimeIntent
  /** 持久化的最近运行阶段；从未记录时缺省。 */
  lastPhase?: RuntimePhase
  /** 最近一次状态写入时间（毫秒时间戳）；从未记录时为 0。 */
  lastUpdatedAtMillis?: number
}

/** 通知权限申请结果；supported 为 false 表示系统版本低于 Android 13。 */
export interface NotificationPermissionResult {
  granted: boolean
  supported: boolean
}

/**
 * 诊断日志状态。
 *
 * 只包含开关、保留天数、计数与时间戳：**不含任何日志内容**，
 * 日志正文只能由用户在系统分享面板里查看。
 */
export interface DiagnosticLogState {
  enabled: boolean
  retentionDays: number
  fileCount: number
  totalBytes: number
  /** 最近一条记录的时间；从未记录时为 0。 */
  lastEntryAtMillis: number
}

/** 导出结果：在状态之外附带导出文件名与字节数。 */
export interface DiagnosticLogExport extends DiagnosticLogState {
  fileName: string
  exportedBytes: number
}

/** 保留天数范围；原生侧同样会夹取，前端只做先期校验。 */
export const DIAGNOSTIC_RETENTION_MIN = 1
export const DIAGNOSTIC_RETENTION_MAX = 30
export const DIAGNOSTIC_RETENTION_DEFAULT = 3

export interface TerminalChunk {
  sessionId: string
  dataBase64: string
}

export interface TerminalExit {
  sessionId: string
  exitCode: number
}

export interface DeviceCommandResult {
  ok: boolean
  exitCode: number
  text: string
  truncated: boolean
  errorCode?: string
}

export interface ListenerHandle {
  remove: () => Promise<void>
}

export interface RuntimeBridge {
  managePlugins: (request: PluginRequest) => Promise<PluginCatalog>
  /** 保存应用语言，仅接受简体中文和英语。 */
  setAppLanguage: (language: 'zh-CN' | 'en') => Promise<void>
  getState: () => Promise<RuntimeState>
  getSettings: () => Promise<RuntimeSettings>
  saveSettings: (settings: RuntimeSettingsUpdate) => Promise<RuntimeSettings>
  install: (source?: RuntimeSource) => Promise<void>
  startHarness: () => Promise<RuntimeState>
  openHarness: () => Promise<void>
  stopRuntime: () => Promise<RuntimeState>
  reset: (confirmation: 'RESET_RUNTIME') => Promise<RuntimeState>
  createTerminal: (kind: TerminalKind, columns: number, rows: number) => Promise<{ sessionId: string }>
  writeTerminal: (sessionId: string, dataBase64: string) => Promise<void>
  resizeTerminal: (sessionId: string, columns: number, rows: number) => Promise<void>
  closeTerminal: (sessionId: string) => Promise<void>
  execDeviceCommand: (sessionId: string, command: DeviceCommand, param?: string) => Promise<DeviceCommandResult>
  getShizukuState: () => Promise<ShizukuState>
  requestShizukuPermission: () => Promise<ShizukuState>
  connectShizuku: () => Promise<ShizukuState>
  openShizuku: () => Promise<void>
  /** 后台保持与恢复状态；不含任何凭据或用户数据。 */
  getKeepAliveState: () => Promise<KeepAliveState>
  /** 申请前台服务通知权限；Android 13 以下直接返回已授予。 */
  requestNotificationPermission: () => Promise<NotificationPermissionResult>
  /** 诊断日志状态；不含日志内容。 */
  getDiagnosticLogState: () => Promise<DiagnosticLogState>
  /** 更新采集开关与保留天数（1–30）。 */
  setDiagnosticLogSettings: (enabled: boolean, retentionDays: number) => Promise<DiagnosticLogState>
  /** 导出全部诊断日志并打开系统分享面板。 */
  shareDiagnosticLog: () => Promise<DiagnosticLogExport>
  /** 清空全部诊断日志。 */
  clearDiagnosticLog: () => Promise<DiagnosticLogState>
  addRuntimeProgressListener: (listener: (event: RuntimeProgress) => void) => Promise<ListenerHandle>
  addTerminalOutputListener: (listener: (event: TerminalChunk) => void) => Promise<ListenerHandle>
  addTerminalExitListener: (listener: (event: TerminalExit) => void) => Promise<ListenerHandle>
}

/** 插件管理只传递受控元数据，不返回配置值、绝对路径或凭据。 */
export interface PluginChild {
  id: string
  name: string
  enabled: boolean
  effectiveEnabled: boolean
  protected: boolean
}
export interface PluginGroup {
  id: string
  file: string
  version: string | null
  enabled: boolean
  protected: boolean
  official: boolean
  installed: boolean
  readable: boolean
  children: PluginChild[]
}
export interface PluginCatalog { plugins: PluginGroup[] }
export interface PluginRequest {
  operation: 'list' | 'enable' | 'child' | 'update'
  id?: string
  enabled?: boolean
  childId?: string
}
