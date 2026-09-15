import type { SelfCheckOperation, SelfCheckReport } from '../runtimeSelfCheck'
import type { HarnessPermissionMode } from '../harnessPermissionMode'

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
  /** dsh 启动默认值；省略更新时保留原值，默认要求工作区沙箱。 */
  harnessPermissionMode?: HarnessPermissionMode
  keepScreenAwake: boolean
  terminalFontSize: number
  /** 已配置凭据的供应商；只返回状态，不向 WebView 回传凭据明文。 */
  configuredModelProviders: ModelProviderId[]
  /** Harness 网页凭据文件中已配置的供应商；只读状态，不可由管理端清除。 */
  harnessConfiguredModelProviders?: ModelProviderId[]
  /** 自定义供应商元数据；旧版桥接可缺省，保存的密钥绝不回传。 */
  customModelProviders?: CustomModelProvider[]
  configuredCustomModelProviders?: string[]
  /** Harness 网页凭据文件中已配置的自定义供应商；只读状态。 */
  harnessConfiguredCustomModelProviders?: string[]
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
  /**
   * 设置中的「悬浮球」开关。默认关闭。
   *
   * 与 keepRuntimeInBackground 是两件独立的事，界面不要合并成一个开关。
   */
  overlayBallEnabled?: boolean
}

export interface RuntimeSettingsUpdate extends RuntimeSettings {
  /** 省略时保留原生侧当前偏好；显式布尔值才修改悬浮球开关。 */
  overlayBallEnabled?: boolean
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

/**
 * 悬浮球状态。
 *
 * 不含任何用户数据：只有开关值、系统权限状态与服务存活状态三个布尔量。
 */
export interface OverlayBallState {
  /** 设置中的「悬浮球」开关当前值。 */
  enabled: boolean
  /** 系统是否已授予「显示在其他应用上层」权限。 */
  canDrawOverlays: boolean
  /** 悬浮球服务当前是否在运行。 */
  serviceActive: boolean
}

/** 通知权限申请结果；supported 为 false 表示系统版本低于 Android 13。 */
export interface NotificationPermissionResult {
  granted: boolean
  supported: boolean
}

/**
 * 投递区可用性档位。
 *
 * 与 `docs/真机缺陷与改进清单.md` §5.1 的 T0–T3 口径一致：
 * `available` 对应 T2（已授予「所有文件访问」且目录真实可读写）；
 * `needsPermission` 表示系统支持该权限但尚未授予，界面必须给出授权入口；
 * `unsupported` 表示系统不存在这一档（Android 11 以下），界面不提供授权入口；
 * `unwritable` 表示已授权但目录仍不可写 —— 如实报告，不猜测原因。
 * 后三档都落回 T0（控制台上传）：投递区不可用是**预期降级**，不是故障，
 * 任何一档都**不得**静默改用别的目录冒充投递区。
 */
export type MailboxAvailability = 'available' | 'needsPermission' | 'unsupported' | 'unwritable'

/** inbox 里的一个 tar 候选（界面用来显示「将导入哪一个」）。 */
export interface MailboxTarCandidate {
  name: string
  bytes: number
}

/**
 * 投递区状态。
 *
 * 只含**用户可见路径**、访客固定挂载点、可用性档位与计数：
 * 不含任何私有路径、宿主真实路径或文件内容。
 */
export interface MailboxState {
  availability: MailboxAvailability
  /** 与 [availability] 对应的权限档位（T2 / T0），供界面展示口径使用。 */
  level: 'T2' | 'T0'
  /** 是否可用；等价于 `availability === 'available'`，供按钮禁用条件直接使用。 */
  available: boolean
  /** 系统是否存在「所有文件访问」这一档。 */
  supported: boolean
  /** 是否已授予「所有文件访问」。 */
  granted: boolean
  /** 用户可见的 inbox 路径（`/storage/emulated/0/Documents/DSH/inbox`）。 */
  inboxPath: string
  outboxPath: string
  /** 访客内固定挂载点（`/mnt/inbox` / `/mnt/outbox`）。 */
  guestInboxPath: string
  guestOutboxPath: string
  /** inbox 内的常规文件数；未授予权限时恒为 0，**不代表目录是空的**。 */
  inboxFileCount: number
  /** inbox 内的 tar 候选，最多 5 个。 */
  inboxTars: MailboxTarCandidate[]
  /** 导出产物的固定文件名。 */
  exportTarName: string
  exportManifestName: string
  /** 导入落点（相对工作区的固定子目录）。 */
  importDirectory: string
}

/**
 * 存储访问状态（原生 `getStorageAccessState` 的载荷）。
 *
 * 对应 `docs/真机缺陷与改进清单.md` §5.1 的权限分级：
 * `mediaGranted` 是 T1（媒体只读），`allFilesGranted` 是 T2（所有文件访问，投递区的前提）。
 * `allFilesSupported` 为 false 表示系统版本低于 Android 11，界面应隐藏该入口。
 */
export interface StorageAccessState {
  mediaGranted: boolean
  allFilesGranted: boolean
  allFilesSupported: boolean
  sdkInt: number
}

/** 权限申请结果；被拒绝时如实返回 false，不承诺一定能授权成功。 */
export interface MediaPermissionResult {
  granted: boolean
}

/** 「所有文件访问」设置路径的跳转结果；supported 为 false 表示系统不存在这一档。 */
export interface AllFilesAccessResult {
  supported: boolean
  granted: boolean
}

/** 一次导入的结果；只有计数、字节数、文件名与落点，不含内容。 */
export interface MailboxImportResult {
  entryCount: number
  fileCount: number
  directoryCount: number
  symlinkCount: number
  hardlinkCount: number
  bytes: number
  tarName: string
  tarBytes: number
  /** 是否用 manifest 逐条校验过；没有 manifest 时为 false。 */
  verified: boolean
  /** 用的 manifest 文件名；没有时为 undefined。 */
  manifestName?: string
  /** inbox 里被忽略的散文件数（它们不会被写进工作区）。 */
  ignoredFiles: number
  target: string
}

/** 一次导出的结果。 */
export interface MailboxExportResult {
  entryCount: number
  bytes: number
  tarName: string
  tarBytes: number
  tarSha256: string
  manifestName: string
  /** 导出起点相对工作区的路径；整个工作区时为 undefined。 */
  subdirectory?: string
  /** 因目标越出导出起点而被跳过的符号链接数。 */
  skippedLinks: number
  /** 因类型无法表达（FIFO / 设备节点）而被跳过的条目数。 */
  skippedSpecial: number
}

/**
 * 诊断日志状态。
 *
 * 只包含开关、保留天数、计数与时间戳：**不含任何日志内容**，
 * 日志正文由 [readDiagnosticLog] 单独读取（应用内查看），或经系统分享面板导出。
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

/**
 * Harness 访客进程输出的尾部快照（stdout 与 stderr 已合并）。
 *
 * 与诊断日志相反，这段文本**可能包含会话内容**（工具参数、报错栈、代码片段等）：
 * 它只在设备上的界面里展示，不写入诊断日志，也不随诊断日志导出。
 */
export interface HarnessLog {
  /** 当前是否有可读的进程输出；false 时 text 恒为空串。 */
  available: boolean
  /** 输出尾部；原生侧按字节上限截断，且落在 UTF-8 字符边界上。 */
  text: string
  /** 原生侧实际应用的窗口字节数（受控档位，见 [HARNESS_LOG_WINDOW_OPTIONS]）。 */
  maxBytes: number
}

/**
 * 应用内查看诊断日志的结果。
 *
 * [text] 是受控字段组成的尾部窗口（见 `docs/诊断日志.md`：只有事件名、级别、
 * 状态码与计数，不含 URL、凭据、终端内容或用户数据），因此它读进 WebView
 * 不构成新的泄露面；[truncated] 表示前面还有被裁掉的内容。
 */
export interface DiagnosticLogText {
  text: string
  /** 原生侧实际应用的窗口字节数。 */
  maxBytes: number
  /** 整个诊断目录的字节数，用于说明「显示的是最近一部分」。 */
  totalBytes: number
  truncated: boolean
}

/** 保留天数范围；原生侧同样会夹取，前端只做先期校验。 */
export const DIAGNOSTIC_RETENTION_MIN = 1
export const DIAGNOSTIC_RETENTION_MAX = 30
export const DIAGNOSTIC_RETENTION_DEFAULT = 3

/**
 * 界面接受的运行日志字符数上限。
 *
 * 原生侧已按窗口字节数（UTF-8 边界）截断后回传；这里是前端校验的兜底：
 * 留出多字节字符与换行差异的余量，异常载荷不允许把界面撑爆。
 */
export const HARNESS_LOG_MAX_CHARS = 32 * 1024

/**
 * 运行日志可选窗口（字节）。
 *
 * 8 KB 只够一段异常栈；插件安装失败、反复重试的场景需要往上翻，因此提供三档。
 * 原生侧的缓冲区按最大档分配，请求更小的档位只是截取尾部。
 */
export const HARNESS_LOG_WINDOW_OPTIONS = [8 * 1024, 64 * 1024, 256 * 1024] as const

/** 界面接受的诊断日志字符数上限；与 [HARNESS_LOG_MAX_CHARS] 同理，按最大窗口留余量。 */
export const DIAGNOSTIC_LOG_MAX_CHARS = 512 * 1024

/** 诊断日志应用内查看的可选窗口（字节）。 */
export const DIAGNOSTIC_LOG_WINDOW_OPTIONS = [64 * 1024, 256 * 1024] as const

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
  /** 悬浮球状态；不含任何用户数据。 */
  getOverlayBallState: () => Promise<OverlayBallState>
  /** 跳转到系统「显示在其他应用上层」设置页。该权限只能由用户手动开启。 */
  openOverlaySettings: () => Promise<void>
  /**
   * 外置投递区状态：用户可见路径、访客挂载点、可用性与 inbox 计数。
   *
   * 投递区目录固定为 `/storage/emulated/0/Documents/DSH/{inbox,outbox}`，访客内固定挂在
   * `/mnt/inbox` 与 `/mnt/outbox`（仅在目录确实可访问时才绑定）。不可用时界面必须如实说明
   * 并给出授权入口，**不得**改用别的目录。
   */
  getMailboxState: () => Promise<MailboxState>
  /** 存储访问状态（T1 媒体只读 / T2 所有文件访问）；只有布尔与枚举，不含路径。 */
  getStorageAccessState: () => Promise<StorageAccessState>
  /** 申请媒体读取权限（T1）。它**不解锁投递区**，投递区需要 T2。 */
  requestMediaPermission: () => Promise<MediaPermissionResult>
  /**
   * 跳转到系统「所有文件访问」设置页（T2，投递区的前提）。
   *
   * 该权限是特殊权限，不会弹运行时对话框，只能由用户手动开启；本调用只负责跳转，
   * 授权结果由界面在回到前台后重新查询 [getStorageAccessState] 获得。
   */
  openAllFilesAccessSettings: () => Promise<AllFilesAccessResult>
  /**
   * 一键导入：inbox 的 tar → 工作区 `mailbox-import/`。
   *
   * 越界条目、绝对符号链接、超限与摘要不符一律在写第一个字节之前拒绝；
   * 失败不留半截产物，重复执行幂等。**不会自动执行**，必须由用户点击触发。
   */
  importMailbox: () => Promise<MailboxImportResult>
  /**
   * 一键导出：工作区（或 [subdirectory] 指定的子目录）→ outbox 的
   * `dsh-workspace.tar` + `dsh-workspace.manifest.json` + `dsh-workspace.tar.sha256`。
   */
  exportMailbox: (subdirectory?: string) => Promise<MailboxExportResult>
  /** 诊断日志状态；不含日志内容。 */
  getDiagnosticLogState: () => Promise<DiagnosticLogState>
  /** 更新采集开关与保留天数（1–30）。 */
  setDiagnosticLogSettings: (enabled: boolean, retentionDays: number) => Promise<DiagnosticLogState>
  /** 导出全部诊断日志并打开系统分享面板。 */
  shareDiagnosticLog: () => Promise<DiagnosticLogExport>
  shareRuntimeWorkspace: () => Promise<void>
  listRuntimeWorkspaceFiles: () => Promise<string[]>
  shareRuntimeWorkspaceFile: (path: string) => Promise<void>
  openRuntimeWorkspaceFile: (path: string) => Promise<void>
  deleteRuntimeWorkspaceFile: (path: string) => Promise<void>
  /** 清空全部诊断日志。 */
  clearDiagnosticLog: () => Promise<DiagnosticLogState>
  /**
   * 读取诊断日志的尾部窗口，供应用内查看。
   *
   * 内容只有受控字段（事件、级别、状态码、计数），不含 URL、凭据、终端内容或用户数据；
   * [maxBytes] 缺省时由原生侧取 64 KB，并夹到 1 KB..256 KB。
   */
  readDiagnosticLog: (options?: { maxBytes?: number }) => Promise<DiagnosticLogText>
  /**
   * 读取访客进程输出的尾部（只读、不落盘）。
   *
   * 可能包含会话内容：只在设备上的界面里展示，不写入诊断日志，也不随诊断日志导出。
   * [maxBytes] 由原生侧收敛到受控档位（8 / 64 / 256 KB），缺省 8 KB。
   */
  getHarnessLog: (options?: { maxBytes?: number }) => Promise<HarnessLog>
  /**
   * 运行时自检：`check` 逐项检查运行时链路，`repair` 修复权限位与缺失目录。
   *
   * 不需要 bash 即可判断运行时断在哪一环。载荷只含受控枚举（检查项 id、状态、
   * 结论码）、字节数与 dsh 版本号：不含路径、命令输出、日志正文或凭据。
   */
  runRuntimeSelfCheck: (operation: SelfCheckOperation) => Promise<SelfCheckReport>
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
