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

/** Shizuku 设备命令：只读诊断与受控交互均由原生白名单兜底。 */
export type DeviceCommand =
  | 'screenshot'
  | 'uiDump'
  | 'tap'
  | 'inputText'
  | 'deviceInfo'
  | 'listPackages'
  | 'getSetting'
  | 'battery'
  | 'launchApp'
  | 'foregroundPackage'
  | 'wait'
  | 'fileList'
  | 'fileRead'
  | 'fileWrite'
  | 'fileMkdir'
  | 'fileDownload'
  | 'fileUpload'
  | 'shell'
  | 'backgroundTasks'

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

/** 运行时版本槽：当前使用的版本、保留下来的上一版本、APK 内置版本。 */
export type RuntimeVersionSlot = 'current' | 'previous' | 'bundled'

export interface RuntimeVersionInfo {
  slot: RuntimeVersionSlot
  version: string
  /** 归档内的 dsh 版本；清单里没有且读不到包描述时缺省。 */
  dshVersion?: string
  runtimeId: string
  /** 解压后的字节数；内置版本取自清单声明，不是本机实测。 */
  extractedBytes: number
  /** 当前正在使用的槽位。 */
  active: boolean
}

/**
 * 运行时版本列表与可执行操作。
 *
 * [canSwitch] / [canDelete] 只说明「磁盘上有没有可用的上一版本」这个事实；
 * 运行中不允许切换由界面按 [RuntimeState.phase] 决定按钮是否可用，
 * 请求本身仍由原生侧以 `RUNTIME_BUSY` 兜底。
 */
export interface RuntimeVersionsState {
  versions: RuntimeVersionInfo[]
  canSwitch: boolean
  canDelete: boolean
}

/**
 * 一份运行时会话快照。
 *
 * 快照只装访客里的**可变数据**（会话、设置、凭据、工作区），存在应用私有目录
 * （Android 的 `filesDir`）下：不进云备份、不进 rootfs，卸载应用即随应用一起消失。
 * [runtimeVersion] / [dshVersion] 缺失表示当时没读到（与 [RuntimeVersionInfo.dshVersion]
 * 同一约定），界面显示「未知」即可，不影响恢复。
 */
export interface RuntimeSessionSnapshot {
  id: string
  /** ISO8601 时刻，例如 `2023-11-14T22:13:21Z`。 */
  createdAt: string
  bytes: number
  fileCount: number
  runtimeVersion?: string
  dshVersion?: string
}

/**
 * 会话快照总览（四条快照桥方法里三条的返回体）。
 *
 * 上限是 [maxSnapshots] 份 / [maxBytes] 字节；超限时原生侧从**最旧**的一份开始淘汰，
 * 所以界面不能假设「刚创建的那份一定还在」。[snapshots] 新的在前，顺序由原生侧决定，前端不重排。
 */
export interface RuntimeSessionSnapshotState {
  maxSnapshots: number
  maxBytes: number
  totalBytes: number
  snapshots: RuntimeSessionSnapshot[]
}

/**
 * 恢复一份快照的结果。
 *
 * 恢复是**合并回填**而不是覆盖：目标位置已有同名文件时跳过并计入 [skippedFileCount]。
 * 两个计数都要如实展示——只报「恢复成功」会把被跳过的文件藏起来。
 */
export interface RuntimeSessionSnapshotRestoreResult {
  restoredFileCount: number
  skippedFileCount: number
  state: RuntimeSessionSnapshotState
}

/**
 * 安装/更新前自动快照的结论；不阻断安装，只如实报告。
 *
 * 三态而不是布尔：`skipped` 表示**没有可备份的会话数据**（首次安装、会话目录为空），
 * 与 `failed`（真的出错）不是同一件事——把首次安装报成「快照失败」只会制造噪音，
 * 把真正的失败混进 `skipped` 又会让用户以为已经有了备份。
 * [evictedIds] 是契约之外的附加字段（这次淘汰了哪些旧快照）：允许存在，界面可以忽略。
 */
export interface RuntimeAutoSnapshotOutcome {
  status: 'created' | 'skipped' | 'failed'
  /** 仅 `created`：新建快照的标识。 */
  snapshotId?: string
  /** 仅 `skipped` / `failed`：受控错误码，取值见 [RuntimeBridge.install]。 */
  code?: string
  /** 仅 `skipped` / `failed`：给用户看的中文说明。 */
  message?: string
  evictedIds?: string[]
}

/** 运行时安装的结果；[autoSnapshot] 缺失表示这次没有自动快照这回事（旧版原生桥接也会缺失）。 */
export interface RuntimeInstallResult {
  autoSnapshot?: RuntimeAutoSnapshotOutcome
}

/**
 * 运行时镜像的一个可用版本。
 *
 * [dshVersion] 是从清单里读到的内置 dsh 版本，读不到就缺失（界面显示成「未知」即可，不影响安装）。
 * [manifestUrl] 与 [manifestSha256] **缺失**表示「这个版本出现过，但没有通过验证的清单」：
 * 界面只能把它列出来、不能安装。两者要么都有、要么都没有——只有一个说明原生侧
 * 回了一份半截载荷，校验侧按错误处理，不替它猜另一半。
 */
export interface RuntimeReleaseEntry {
  version: string
  dshVersion?: string
  manifestUrl?: string
  manifestSha256?: string
}

/** 原生侧已知的运行时可用版本；新的在前，顺序由原生侧决定，前端不重排。 */
export interface RuntimeReleaseList {
  entries: RuntimeReleaseEntry[]
}

/** 一个可安装的应用自身更新（APK）。[notes] 是给用户看的更新说明。 */
export interface AppUpdateRelease {
  version: string
  bytes: number
  sha256: string
  notes: string
}

/**
 * 应用自身的更新状态。
 *
 * `installedVersionCode` 是 Android 的 versionCode（单调递增，用来判断新旧），
 * 与给人看的 `installedVersion` 是两件事，界面不要拿版本字符串比大小。
 * [available] 缺失表示没有可用更新。
 */
export interface AppUpdateState {
  installedVersion: string
  installedVersionCode: number
  /**
   * 本应用是否已被授予「安装未知应用」权限（原生 `canRequestPackageInstalls()`）。
   *
   * 为 false 时下载仍可能成功，但安装会被系统拦下：界面应引导用户去
   * [RuntimeBridge.openAppUpdateInstallSettings] 授权，而不是假装下一步一定能装上。
   */
  installAllowed: boolean
  available?: AppUpdateRelease
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
  /**
   * 最近一次「一轮对话结束」被原生受理的毫秒时间戳；与 `lastUpdatedAtMillis` 同源时钟，从未收到时为 0。
   *
   * 与 `lastUpdatedAtMillis` 的区别：后者任何一次状态写入都会刷新，前者只在受理到「本轮对话完成」时刷新，
   * 因此可用它判断"这一轮的收尾是否真的被原生侧接住"，而不会被无关的状态变更顶掉。
   */
  lastTurnCompletedAtMillis?: number
  /**
   * 「一轮对话结束」被受理的累计序号；进程重启后从 0 重新计数。
   *
   * 时间戳可能因同毫秒内多次受理而无法区分先后，序号专门用来消除这种歧义。
   */
  turnCompletionSequence?: number
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

export interface InstalledApplication {
  packageName: string
  label: string
  system: boolean
  selectable: boolean
}
export interface InstalledApplicationsPage {
  apps: InstalledApplication[]
  total: number
  nextOffset: number | null
}
export interface DeviceShellAccessState { enabled: boolean }

/**
 * 无障碍自动化永远自动包含、界面上不可移除的包名。
 *
 * 本应用自己必须在白名单里：用户若把它删掉，AI 就再也无法通过无障碍看到本应用的界面，
 * 连回来重新勾上的入口都会消失。因此原生侧把它写成自动项，前端**只从这一处取**，
 * 界面据此把对应条目渲染成不可移除（而不是在别处再硬编码一遍包名）。
 */
export const ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES: string[] = ['io.deepseekharness.mobile']

/**
 * 无障碍自动化状态；只返回是否已由用户开启、用户维护的目标包白名单与密码是否已设置。
 *
 * **密码本身永远不过这条线**：`passwordConfigured` 只是个布尔，界面上要展示的信息仅此而已。
 * 校验密码（含生物识别重置）是原生侧的事，前端既不保存密码、也不写日志。
 */
export interface AccessibilityAutomationState {
  enabled: boolean
  /** 有效白名单：已包含自动项（本应用）。 */
  allowedPackages: string[]
  /** 自动包含、界面上不可移除的包名（当前恒为 [ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES]）。 */
  alwaysAllowedPackages: string[]
  /** 是否已设置「白名单验证密码」；不返回任何密码信息。 */
  passwordConfigured: boolean
}

/** 投递区固定根目录。只允许 inbox / outbox，不能把任意宿主路径交给文件浏览器。 */
export type MailboxRoot = 'inbox' | 'outbox'

/** 投递区当前目录中的一项；符号链接和特殊文件不会出现在此列表中。 */
export interface MailboxDirectoryEntry {
  name: string
  kind: 'file' | 'directory'
  bytes: number
}

/** 投递区目录浏览快照；[path] 为空表示当前位于所选根目录。 */
export interface MailboxDirectoryState {
  root: MailboxRoot
  path?: string
  entries: MailboxDirectoryEntry[]
  /** 条目超过上限时只返回排序后的前一部分，界面应提示用户继续缩小目录范围。 */
  truncated: boolean
}

/**
 * 共享目录（`/mnt/user/<序号>`）当前目录中的一项。
 *
 * 与投递区条目**共用同一个线形状**（原生侧两处用的是同一个 `MailboxDirectoryEntry.toJs`）：
 * 字段逐字为 `name` / `kind` / `bytes`，没有 `type`。共享目录里的符号链接、硬链接与特殊文件
 * 不会被列出。
 */
export type StorageDirectoryEntry = MailboxDirectoryEntry

/**
 * 共享目录浏览快照；与 [MailboxDirectoryState] 只差根字段（`root` → `guestPath`）。
 *
 * - `guestPath` 严格形如 `/mnt/user/<序号>`，序号从 1 起，取自 [StorageDirsState] 条目里的
 *   同名字段。序号是**持久化顺序**（`/mnt/user/3` 永远指白名单里的第 3 条，不因别的条目失效而重排），
 *   因此界面可以把它当稳定标识用；它标识的是「白名单里的第几条」，不是任意宿主路径。
 * - `path` 为 `undefined` 表示当前就位于该目录根；否则是规范化后的相对子目录
 *   （语义与 [MailboxDirectoryState] 的 `path` 完全一致，原生回传根目录时给的是 `null`）。
 * - `entries` 单次最多 256 条（与投递区同一个上限）。超出上限时只返回一部分且
 *   `truncated` 为 true，界面应提示用户继续缩小目录范围，而不是把它当成目录的全部内容。
 */
export interface StorageDirectoryState {
  guestPath: string
  path?: string
  entries: StorageDirectoryEntry[]
  /** 条目超过上限时只返回一部分，界面应提示用户继续缩小目录范围。 */
  truncated: boolean
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

/**
 * 目录白名单的固定上限。
 *
 * 与原生侧 `RuntimeStorageDirsLimits.MAX_DIRECTORIES` 必须是同一个数：界面用它做禁用条件，
 * 不一致会出现「按钮还能点、原生直接拒绝」的矛盾状态。
 */
export const MAX_STORAGE_DIRECTORIES = 8

/**
 * 单条白名单目录的可用性（与原生 `StorageDirAvailability` 一一对应）。
 *
 * - `available`：T2，目录存在且可读，会出现在访客内的 `/mnt/user/<序号>`；
 * - `unavailable`：已被删除/改名/不可读，或不再满足规则 —— **只影响这一条**，其余条目照常；
 * - `needsPermission`：尚未授予「所有文件访问」（此时不判断目录是否存在，避免误导）；
 * - `unsupported`：系统不存在这一档（Android 11 以下），条目只保留不生效。
 */
export type StorageDirAvailability = 'available' | 'unavailable' | 'needsPermission' | 'unsupported'

/**
 * 白名单里的一条目录。
 *
 * `path` 是**用户自己通过 SAF 点选过的**宿主路径（`/storage/emulated/0/...`），与投递区的
 * `inboxPath` 同类，属于用户可见信息；原生侧不会回传应用私有路径或 rootfs 路径。
 */
export interface StorageDirEntry {
  /**
   * 序号（1 起）。访客内挂载点为 `/mnt/user/<index>`。
   *
   * 序号来自**持久化顺序**：某条目录失效时只跳过该条（访客里留下空洞），不会重排 ——
   * 否则一次失效就会让别的条目换到另一个挂载点上。
   */
  index: number
  /** 宿主侧的真实路径（已解析符号链接，不是 `/sdcard`）。 */
  path: string
  /** 展示名（目录的最后一级名字）。 */
  displayName: string
  /** 访客内固定挂载点：`/mnt/user/<index>`。 */
  guestPath: string
  availability: StorageDirAvailability
  /** 与 [availability] 对应的权限档（T2 / T0）。 */
  level: 'T2' | 'T0'
  /** 是否可用；等价于 `availability === 'available'`，供按钮/文案直接使用。 */
  available: boolean
  /** 不可用时的受控错误码（如 `STORAGE_DIR_NOT_A_DIRECTORY`）；可用时不存在。 */
  reasonCode?: string
}

/**
 * ≤8 目录白名单状态。
 *
 * 这是访客内可见目录的**唯一来源**：旧的 `/sdcard` 整体绑定已被它取代
 * （`docs/存储权限与导入落点.md` §3.1），因此 `entries` 为空时访客里就没有任何用户目录。
 */
export interface StorageDirsState {
  entries: StorageDirEntry[]
  /** 上限（固定 8）；与 [MAX_STORAGE_DIRECTORIES] 一致。 */
  maxDirectories: number
  /** 条数；等价于 `entries.length`。 */
  count: number
  /** 系统是否存在「所有文件访问」这一档。 */
  supported: boolean
  /** 是否已授予「所有文件访问」（T2）。 */
  granted: boolean
  /** 整档口径：`supported && granted` 为 T2，否则 T0。 */
  level: 'T2' | 'T0'
  /** 是否至少有一条目录会真的出现在访客里。 */
  active: boolean
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
  /** 产物所在的 outbox 相对目录；省略表示 outbox 根目录。 */
  destinationDirectory?: string
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

/**
 * 应用主题模式。刻意在平台层**自带一份**而不是从 `src/theme.ts` 导入：
 * 平台层是桥的契约，不该依赖界面模块（否则主题模块一改就可能连带改动桥的契约）。
 * 两边的取值必须保持一致，`src/theme.ts` 的 `ThemeMode` 与它是同一组字符串。
 */
export type AppThemeMode = 'system' | 'light' | 'dark'

export interface RuntimeBridge {
  managePlugins: (request: PluginRequest) => Promise<PluginCatalog>
  /** 保存应用语言，仅接受简体中文和英语。 */
  setAppLanguage: (language: 'zh-CN' | 'en') => Promise<void>
  /**
   * 把应用主题模式同步给原生（登记册 5.4）。
   *
   * **为什么需要这条**：主题选择是 Web 侧的偏好，但**状态栏属于窗口，Web 改不了**——
   * `meta[name=theme-color]` 只有 Chrome for Android 认，Android WebView 不认。
   * 只传模式、不传「深/浅」：原生在 `system` 模式下要自己按 `uiMode` 现算，
   * 传结论会把某一刻的取值固化成用户的显式选择，之后系统再切就不跟随了。
   */
  setAppTheme: (mode: AppThemeMode) => Promise<void>
  getState: () => Promise<RuntimeState>
  getSettings: () => Promise<RuntimeSettings>
  saveSettings: (settings: RuntimeSettingsUpdate) => Promise<RuntimeSettings>
  /**
   * 安装（或更新到）[source] 指定的运行时镜像；省略 `source` 表示用内置/已下载的镜像。
   *
   * 原生侧在动手之前会先做一次**更新前自动快照**，结论放在返回值的 `autoSnapshot` 里：
   * `skipped`（没有可备份的会话数据，例如首次安装）与 `failed` 都要让用户看见，但不必阻断安装。
   * 相关错误码：`RUNTIME_SNAPSHOT_EMPTY`（没有可备份数据）、`RUNTIME_SNAPSHOT_ID_INVALID`、
   * `RUNTIME_SNAPSHOT_NOT_FOUND`、`RUNTIME_SNAPSHOT_TOO_LARGE`、`RUNTIME_SNAPSHOT_FAILED`。
   * 快照本身的成败不影响安装结果，界面按 `phase`/`errorCode` 判断安装是否失败。
   */
  install: (source?: RuntimeSource) => Promise<RuntimeInstallResult>
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
  /** 读取无障碍服务状态和目标应用白名单；不会返回当前窗口内容，也不返回任何密码信息。 */
  getAccessibilityAutomationState: () => Promise<AccessibilityAutomationState>
  /** 本机应用选择器的分页查询，数量不限；不向 AI 自动发送清单。 */
  listInstalledApplications: (query: string, offset: number) => Promise<InstalledApplicationsPage>
  getDeviceShellAccess: () => Promise<DeviceShellAccessState>
  setDeviceShellAccess: (enabled: boolean) => Promise<DeviceShellAccessState>
  /**
   * 保存目标应用包名白名单；只能由用户在设置页修改。
   *
   * **每次修改都要带验证密码**（`password`），由原生侧比对后才会落盘；
   * 密码只在这一次调用里出现过桥，**不落到前端存储、也不进日志**。
   * 返回值里的 `allowedPackages` 是原生合并自动项之后的**有效白名单**，界面必须用它刷新，
   * 不能拿本地那份入参当结果。[alwaysAllowedPackages] 里的条目由原生自动保证，界面不可移除。
   */
  setAccessibilityAutomationPackages: (packages: string[], password?: string) => Promise<AccessibilityAutomationState>
  /**
   * 设置或修改「白名单验证密码」。
   *
   * 首次设置不需要 `currentPassword`；已设置过密码时**必须**带上当前密码，由原生侧校验。
   * 密码只作为本次调用的入参，前端不留存、不缓存、也不写日志。
   */
  setAccessibilityPassword: (password: string, currentPassword?: string) => Promise<AccessibilityAutomationState>
  /** 清除「白名单验证密码」，必须带当前密码；**白名单本身不受影响**。 */
  clearAccessibilityPassword: (currentPassword: string) => Promise<AccessibilityAutomationState>
  /**
   * 用系统生物识别 / 锁屏密码重置验证密码——「忘记密码」的唯一出路。
   *
   * 重置**只清掉密码、保留白名单**：用户丢的是那串数字，不是自己配好的目标应用清单。
   * 这条不需要（也不能）带旧密码：能过系统生物识别/锁屏就说明是机主本人。
   * 浏览器预览里没有系统生物识别，不得谎报成功。
   */
  resetAccessibilityPasswordWithBiometric: () => Promise<AccessibilityAutomationState>
  /** 跳转系统无障碍设置，由用户手动开启服务。 */
  openAccessibilitySettings: () => Promise<void>
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
  /** 浏览固定 inbox/outbox 根目录下的相对目录，返回受限且经过 NoFollow 检查的条目。 */
  getMailboxDirectory: (root: MailboxRoot, subdirectory?: string) => Promise<MailboxDirectoryState>
  /** 在固定 inbox/outbox 根目录下创建相对目录；不会覆盖已有条目。 */
  createMailboxFolder: (root: MailboxRoot, subdirectory: string) => Promise<MailboxDirectoryState>
  /**
   * 浏览共享目录白名单里某一条目录下的相对目录。
   *
   * `guestPath` **只能取自运行时状态**（[StorageDirsState] 条目里的同名字段，形如 `/mnt/user/1`）：
   * 它标识的是白名单里的第几条，不接受任意宿主路径，界面也不能直接接用户输入。
   * `subdirectory` 省略表示该目录本身。
   */
  getStorageDirectory: (guestPath: string, subdirectory?: string) => Promise<StorageDirectoryState>
  /**
   * 在共享目录的某条目录下创建相对目录，返回建完后的新快照（界面不必再发一次浏览请求）。
   *
   * 语义是 CREATE_NEW：父目录可按需创建，目标已存在时报错，**绝不覆盖**用户已有的条目。
   */
  createStorageFolder: (guestPath: string, subdirectory: string) => Promise<StorageDirectoryState>
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
   * 一键导出：工作区（或 [subdirectory] 指定的子目录）→ outbox（或 [destinationDirectory] 子目录）的
   * `dsh-workspace.tar` + `dsh-workspace.manifest.json` + `dsh-workspace.tar.sha256`。
   */
  exportMailbox: (subdirectory?: string, destinationDirectory?: string) => Promise<MailboxExportResult>
  /**
   * ≤8 目录白名单状态：访客内 `/mnt/user/<序号>` 的**唯一来源**。
   *
   * 取代了旧的 `/sdcard` 整体绑定：用户没点过的目录不会出现在访客里，「App 有什么权限」
   * 不再等价于「访客能看到什么」。每条都带可用性与受控错误码，界面据此逐条显示状态。
   */
  getStorageDirs: () => Promise<StorageDirsState>
  /**
   * 新增一条：Android 侧弹 SAF 目录选择器（`ACTION_OPEN_DOCUMENT_TREE`），
   * 回调里做映射与校验，**全部通过才落盘**。
   *
   * 非 `primary:` 卷（SD 卡/OTG）、共享存储根、`Android/` 及其子目录、应用私有目录、
   * 符号链接逃逸、重复选择、超过 8 条、路径含运行时不支持的字符，一律以受控错误码拒绝
   * （错误码见 `StorageDirEntry.reasonCode` 的同一套取值）。用户取消是 `STORAGE_DIR_CANCELLED`，
   * 界面不应把它当故障。成功与失败都不返回增量，只返回**最新状态**。
   */
  addStorageDirectory: () => Promise<StorageDirsState>
  /**
   * 按 [path]（取自 [getStorageDirs] 的条目）移除一条。
   *
   * 用路径而不是序号作为标识：序号会随增删变化，用序号删除可能删掉另一条目录。
   */
  removeStorageDirectory: (path: string) => Promise<StorageDirsState>
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
  /**
   * 运行时版本列表：当前版本、保留下来的上一版本与 APK 内置版本。
   *
   * 只读：不需要停止运行时；载荷只有槽位、版本号、dsh 版本、字节数与可用操作，
   * 不含路径、下载地址或凭据。
   */
  getRuntimeVersions: () => Promise<RuntimeVersionsState>
  /**
   * 切换到上一版本（目前只支持 `target = 'previous'`）。
   *
   * 会改名运行时根目录并搬迁访客数据（会话、设置、凭据、工作区），
   * 所以运行中会被拒绝：界面应在运行时按 phase 禁用按钮，原生侧另有兜底。
   */
  switchRuntimeVersion: (target: 'previous') => Promise<RuntimeVersionsState>
  /** 删除保留下来的上一版本以释放磁盘空间；只影响上一版本，不需要停止运行时。 */
  deleteRuntimeVersion: (target: 'previous') => Promise<RuntimeVersionsState>
  /**
   * 会话快照总览（无参数，只读）。
   *
   * 快照存在应用私有目录里：不进云备份、不进 rootfs；上限 3 份 / 512 MiB，
   * 超限时原生侧从最旧的一份开始淘汰，返回的只是**当前实际还在**的那些。
   */
  getRuntimeSessionSnapshotState: () => Promise<RuntimeSessionSnapshotState>
  /**
   * 立即创建一份会话快照，返回**创建之后的最新总览**。
   *
   * 没有可备份的会话数据时原生侧以 `RUNTIME_SNAPSHOT_EMPTY` 拒绝，**不产出空快照**：
   * 界面如实提示即可，不要自己往列表里插一条来「表示成功」。
   */
  createRuntimeSessionSnapshot: () => Promise<RuntimeSessionSnapshotState>
  /**
   * 把一份快照**合并回填**到运行时数据目录。
   *
   * 同名文件已存在时跳过并计入 `skippedFileCount`——不是覆盖，也不会删掉现有数据。
   * `id` 形态非法是 `RUNTIME_SNAPSHOT_ID_INVALID`，找不到是 `RUNTIME_SNAPSHOT_NOT_FOUND`。
   */
  restoreRuntimeSessionSnapshot: (id: string) => Promise<RuntimeSessionSnapshotRestoreResult>
  /**
   * 删除一份快照并返回最新总览。
   *
   * `id` 不存在是 `RUNTIME_SNAPSHOT_NOT_FOUND`：如实提示即可，不用重试。
   */
  deleteRuntimeSessionSnapshot: (id: string) => Promise<RuntimeSessionSnapshotState>
  /**
   * 列出原生侧已知的运行时可用版本（无参数，只读）。
   *
   * 条目缺 `manifestUrl`/`manifestSha256` 的版本只可展示、不可安装（见 [RuntimeReleaseEntry]）：
   * 界面要按「能不能装」分开渲染，而不是把列表里的每一项都当成可安装。
   */
  listRuntimeReleases: () => Promise<RuntimeReleaseList>
  /** 应用自身的更新状态：已安装版本，以及是否有可用更新。 */
  getAppUpdateState: () => Promise<AppUpdateState>
  /**
   * 下载应用更新包（目标是原生侧当前认定的那个可用更新）。
   *
   * **不接受 URL**：下载地址由原生侧自己决定，前端不能指定任意地址——
   * 否则等于给 WebView 开了一个「下载并安装任意 APK」的口子。
   */
  downloadAppUpdate: () => Promise<void>
  /**
   * 安装已下载的应用更新包，交给系统安装器接管。
   *
   * 未授予「安装未知应用」权限时原生侧会拒绝（错误码 `APP_UPDATE_INSTALL_PERMISSION`）：
   * 界面如实显示中文错误并引导授权即可，不要重试或绕过。
   */
  installAppUpdate: () => Promise<void>
  /**
   * 跳转系统「安装未知应用」授权页。
   *
   * 是否授权由用户在系统界面决定，返回成功只代表页面打开了，不代表已授权：
   * 回来后必须重新 [RuntimeBridge.getAppUpdateState]，不能自己把 installAllowed 置为 true。
   */
  openAppUpdateInstallSettings: () => Promise<void>
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
  /**
   * 可以回滚到的上一版版本号；没有上一版、上一版目录已被清理或该包受保护时为 `null`。
   *
   * 桥接返回值里**总是**带有这个键；类型上可选只是为了兼容手工构造的目录对象（界面按
   * `group.rollback ?? null` 读取即可）。
   */
  rollback?: string | null
  enabled: boolean
  protected: boolean
  official: boolean
  installed: boolean
  readable: boolean
  children: PluginChild[]
}
export interface PluginCatalog { plugins: PluginGroup[] }
export interface PluginRequest {
  /**
   * `import`：受控导入（npm 包名 / `https://` 直链 / `git+https://` 地址），需要 `source`；
   * `rollback`：回滚到该插件的上一版，需要 `id`。
   */
  operation: 'list' | 'enable' | 'child' | 'update' | 'import' | 'rollback'
  id?: string
  enabled?: boolean
  childId?: string
  /** 仅 `import` 使用：来源地址或包名；地址形态可用 `id` 提示期望的包名（可选）。 */
  source?: string
}
