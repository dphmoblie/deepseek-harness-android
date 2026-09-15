import appMark from './assets/app-mark.png'
import { t, useLanguage } from './i18n'
import { PluginSettings } from './components/PluginSettings'
import { LanguageSettings } from './components/LanguageSettings'
import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  ArrowLeft,
  BellRing,
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  CloudDownload,
  Copy,
  Cpu,
  Database,
  Download,
  ExternalLink,
  Gauge,
  HardDrive,
  KeyRound,
  Loader2,
  LockKeyhole,
  Play,
  Power,
  RefreshCw,
  RotateCcw,
  Rocket,
  Save,
  ScrollText,
  Settings2,
  Share2,
  ShieldCheck,
  Smartphone,
  Square,
  SquareTerminal,
  Trash2,
  Wifi,
  Wrench,
  X,
} from 'lucide-react'
import { TerminalPanel } from './components/TerminalPanel'
import { Onboarding, ONBOARDING_STORAGE_KEY } from './components/Onboarding'
import { hasConfiguredModelCredential, MODEL_PROVIDERS } from './modelProviders'
import { CustomProviders } from './components/CustomProviders'
import { runtimeBridge } from './platform/native'
import { readLogInsights } from './logInsights'
import {
  selfCheckAdvice,
  selfCheckNeedsRepair,
  type SelfCheckCheckReport,
  type SelfCheckCode,
  type SelfCheckId,
  type SelfCheckItem,
  type SelfCheckOperation,
  type SelfCheckRepairReport,
  type SelfCheckReport,
  type SelfCheckStatus,
} from './runtimeSelfCheck'
import type {
  DiagnosticLogState,
  DiagnosticLogText,
  HarnessLog,
  KeepAliveState,
  ModelProviderId,
  OverlayBallState,
  ProviderApiKeys,
  RuntimePhase,
  RuntimeProgress,
  RuntimeSettings,
  RuntimeSettingsUpdate,
  RuntimeState,
  ShizukuState,
  TerminalKind,
} from './platform/types'
import {
  DIAGNOSTIC_LOG_WINDOW_OPTIONS,
  DIAGNOSTIC_RETENTION_MAX,
  DIAGNOSTIC_RETENTION_DEFAULT,
  DIAGNOSTIC_RETENTION_MIN,
  HARNESS_LOG_WINDOW_OPTIONS,
} from './platform/types'

type AppView =
  | 'conversation'
  | 'settings'
  | 'settings-models'
  | 'settings-runtime'
  | 'settings-terminal'
  | 'settings-shizuku'
  | 'settings-diagnostics'
  | 'terminal'
  | 'environment'
  | 'plugins'

/** 设置二级页：把原先的单页设置按功能分类，避免所有选项挤在一屏里。 */
type SettingsPage = 'models' | 'runtime' | 'terminal' | 'shizuku' | 'diagnostics'

const SETTINGS_PAGE_META: Record<SettingsPage, { title: string; hint: string; view: AppView }> = {
  models: { title: '模型与密钥', hint: '供应商、API Key 与自定义模型', view: 'settings-models' },
  runtime: { title: '运行与后台', hint: '运行时来源、后台保持、悬浮球与前台服务', view: 'settings-runtime' },
  terminal: { title: '终端与外观', hint: '终端字号与屏幕常亮', view: 'settings-terminal' },
  shizuku: { title: 'Shizuku 与设备 Shell', hint: '授权、连接与设备 Shell 可用性', view: 'settings-shizuku' },
  diagnostics: { title: '诊断与日志', hint: '采集开关、保留天数、运行日志与导出', view: 'settings-diagnostics' },
}

const SETTINGS_PAGES: SettingsPage[] = ['models', 'runtime', 'terminal', 'shizuku', 'diagnostics']

function settingsPageOf(view: AppView): SettingsPage | null {
  const entry = SETTINGS_PAGES.find(page => SETTINGS_PAGE_META[page].view === view)
  return entry ?? null
}

/**
 * 是否属于「设置区」（设置首页与五个二级页）。
 *
 * 设置草稿的作用范围就是它：区内切页共享同一份未保存内容，离开设置区即丢弃。
 */
function isSettingsView(view: AppView): boolean {
  return view === 'settings' || settingsPageOf(view) !== null
}

/** 外壳视图的全部取值：历史状态与地址片段只接受这里的值，其余一律回落到主视图。 */
const APP_VIEWS: AppView[] = [
  'conversation',
  'settings',
  'settings-models',
  'settings-runtime',
  'settings-terminal',
  'settings-shizuku',
  'settings-diagnostics',
  'terminal',
  'environment',
  'plugins',
]

/**
 * 主视图：外壳历史栈的栈底，应用启动时也总是落在这里。
 *
 * 回到主视图之后再按返回键，WebView 已经没有可回退的历史（canGoBack() 为 false），
 * 原生侧据此把任务退到后台，而不是结束应用；从设置二级页到主视图的每一级都能原路退回。
 */
const ROOT_VIEW: AppView = 'conversation'

/** 写进 history.state 的视图字段名。 */
const VIEW_STATE_KEY = 'dshView'

function isAppView(value: unknown): value is AppView {
  return typeof value === 'string' && (APP_VIEWS as string[]).includes(value)
}

/** 只认本应用写入的历史状态：其它来源（含 null）一律视为未知，不把外部状态当成视图。 */
function viewFromHistoryState(state: unknown): AppView | null {
  if (typeof state !== 'object' || state === null) return null
  const value = (state as Record<string, unknown>)[VIEW_STATE_KEY]
  return isAppView(value) ? value : null
}

/** 当前地址去掉片段后的部分：主视图回写历史时用它，避免把上一个视图的片段留在地址栏里。 */
function currentPath(): string {
  return `${window.location.pathname}${window.location.search}`
}

/**
 * 视图与地址片段的唯一映射：主视图保持根地址干净，其余视图用 `#视图名`。
 * 地址与视图一一对应，回退或前进后不会出现「界面在一级、地址还停在二级」。
 */
function viewAddress(view: AppView): string {
  return view === ROOT_VIEW ? currentPath() : `#${view}`
}

function viewFromHash(hash: string): AppView | null {
  const value = hash.startsWith('#') ? hash.slice(1) : hash
  return isAppView(value) ? value : null
}

/**
 * 导航入口：切换视图时必须同时写一条历史记录。
 *
 * 外壳过去只改 React 状态，WebView 里不存在任何可回退的历史（canGoBack() 恒为 false），
 * Android 的返回键与返回手势抵达时 Capacitor 外壳会直接结束 Activity —— 用户看到的就是
 * 「在设置二级页按返回直接退出应用」。写入历史后，返回键先回退到上一条记录，
 * 再由 popstate 把视图恢复成上一级。
 */
function pushViewEntry(view: AppView): void {
  window.history.pushState({ [VIEW_STATE_KEY]: view }, '', viewAddress(view))
}

/**
 * 把当前这条历史记录校正成指定视图（不新增记录）。
 *
 * 用于首屏对齐，以及回退到无法识别的记录（如 WebView 恢复历史、外部写入）时把地址
 * 拉回视图，保证两者的对应关系在任何时刻都成立。
 */
function replaceViewEntry(view: AppView): void {
  window.history.replaceState({ [VIEW_STATE_KEY]: view }, '', viewAddress(view))
}

type NoticeTone = 'success' | 'error' | 'info'

interface Notice {
  id: number
  message: string
  tone: NoticeTone
}

const PHASE_META: Record<RuntimePhase, { label: string; tone: string }> = {
  'not-installed': { label: '未安装', tone: 'neutral' },
  preparing: { label: '读取内置环境', tone: 'blue' },
  downloading: { label: '下载中', tone: 'blue' },
  verifying: { label: '正在校验', tone: 'amber' },
  extracting: { label: '正在安装', tone: 'amber' },
  ready: { label: '已就绪', tone: 'green' },
  running: { label: '运行中', tone: 'green' },
  stopping: { label: '正在停止', tone: 'amber' },
  error: { label: '需要处理', tone: 'red' },
}

const EMPTY_RUNTIME: RuntimeState = {
  phase: 'not-installed',
  architecture: '检测中',
  updateAvailable: false,
  downloadedBytes: 0,
  totalBytes: 0,
  runnerAvailable: false,
}

const EMPTY_SHIZUKU: ShizukuState = {
  installed: false,
  running: false,
  permission: 'undetermined',
  connected: false,
}

/** 后台保持状态未知时的占位值：一律按“未开启、未运行”处理，不做保活承诺。 */
const EMPTY_KEEP_ALIVE: KeepAliveState = {
  keepRuntimeInBackground: false,
  foregroundServiceActive: false,
  notificationPermission: 'unsupported',
  deviceShellReady: false,
  reconnectRequired: false,
  lastIntent: 'unknown',
}

/** 诊断日志状态未知时的占位值：按“未收集、无文件”处理，不显示虚假计数。 */
const EMPTY_DIAGNOSTIC: DiagnosticLogState = {
  enabled: false,
  retentionDays: DIAGNOSTIC_RETENTION_DEFAULT,
  fileCount: 0,
  totalBytes: 0,
  lastEntryAtMillis: 0,
}
const MAX_NOTICE_CHARACTERS = 240
const RESET_CONFIRMATION = 'RESET_RUNTIME'
/**
 * 通知权限申请的兜底超时：系统对话框在极端情况下可能不返回结果，
 * 超时后按“未授予”处理，避免界面一直停留在忙碌状态。
 */
const NOTIFICATION_PERMISSION_TIMEOUT_MS = 30_000

/**
 * 保存「后台保持」后，等待前台服务真正进入前台的宽限期。
 *
 * 前台服务由 Android 异步拉起（`startForegroundService` 之后才由服务自己 `startForeground`），
 * 保存后紧接着读取原生状态会看到「尚未生效」。复核必须等一小段时间，
 * 否则会把正常启动中的服务误报成未生效。
 */
const FOREGROUND_SERVICE_SETTLE_MS = 1500

/**
 * 保存设置后的前台服务生效复核。
 *
 * 「后台保持」与「悬浮球」各有一个前台服务，都由 Android 异步拉起：保存后紧接着读取原生状态
 * 会看到「尚未生效」。这里等宽限期过后再读一次，把最新状态交给调用方判断与提示；
 * 复核本身失败时静默保留「设置已保存」的提示，不猜测服务状态，也不误报未生效。
 */
function recheckForegroundServiceAfterSettle<T>(read: () => Promise<T>, onSettled: (latest: T) => void): void {
  window.setTimeout(() => {
    void read().then(onSettled).catch(() => {
      // 复核失败时保留「设置已保存」的提示：不猜测服务状态，也不误报未生效。
    })
  }, FOREGROUND_SERVICE_SETTLE_MS)
}

const UNKNOWN_RUNTIME_ERROR_MESSAGE = '运行时操作失败，请稍后重试；如问题持续，请重置环境。'
const RUNTIME_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  SOURCE_INCOMPLETE: '请同时配置运行时清单地址和 SHA-256，或同时留空。',
  URL_INVALID: '运行时下载地址格式无效。',
  URL_HOST_NOT_ALLOWED: '运行时下载地址必须使用允许的公网 HTTPS 主机。',
  DIGEST_INVALID: '配置的 SHA-256 格式无效。',
  DOWNLOAD_FAILED: '运行时下载失败，请稍后重试。',
  DOWNLOAD_HOST_NOT_ALLOWED: '运行时归档与清单必须使用同一下载主机。',
  DOWNLOAD_NETWORK_UNAVAILABLE: '网络不可用或下载连接已中断，可稍后继续。',
  DOWNLOAD_TIMEOUT: '下载连接或读取超时，可稍后继续。',
  DOWNLOAD_TLS_FAILED: '下载服务的 TLS 校验失败。',
  DOWNLOAD_HOST_UNRESOLVED: '无法解析下载主机。',
  DOWNLOAD_HTTP_ERROR: '下载服务返回了错误响应。',
  DOWNLOAD_INCOMPLETE: '下载尚未完成，再次安装时会继续。',
  DOWNLOAD_RANGE_INVALID: '下载服务返回了无效的断点响应。',
  DOWNLOAD_REDIRECT_LIMIT: '下载重定向次数过多。',
  DOWNLOAD_TOO_LARGE: '下载内容超过清单声明或应用大小限制。',
  DOWNLOAD_PART_CHANGED: '断点文件在下载期间发生变化，请重试。',
  DOWNLOAD_PART_INVALID: '断点文件无效，请重置环境后重试。',
  MANIFEST_DIGEST_MISMATCH: '运行时清单完整性校验失败。',
  MANIFEST_INVALID: '运行时清单格式无效。',
  MANIFEST_SCHEMA_UNSUPPORTED: '当前应用不支持此运行时清单版本。',
  MANIFEST_SIZE_INVALID: '运行时清单中的大小信息无效。',
  ARCHITECTURE_UNSUPPORTED: '运行时架构与当前设备不兼容。',
  ENTRYPOINT_NOT_ALLOWED: '运行时清单包含不允许的启动入口。',
  HARNESS_URL_INVALID: '运行时清单中的 Harness 地址无效。',
  ARCHIVE_COMPRESSION_UNSUPPORTED: '当前应用不支持此运行时归档格式。',
  ROOTFS_DIGEST_MISMATCH: '运行时归档完整性校验失败。',
  ARCHIVE_DIGEST_MISMATCH: '内置运行时归档完整性校验失败。',
  ARCHIVE_SOURCE_DIGEST_MISMATCH: '解压时读取的运行时归档未通过完整性复核。',
  ARCHIVE_SOURCE_SIZE_MISMATCH: '解压时读取的运行时归档大小与清单不一致。',
  ARCHIVE_SIZE_MISMATCH: '运行时归档的实际解压大小与清单不一致。',
  ARCHIVE_EXPANSION_LIMIT: '运行时归档解压后超过允许大小。',
  ARCHIVE_ENTRY_LIMIT: '运行时归档包含过多文件。',
  ARCHIVE_FEATURE_UNSUPPORTED: '运行时归档包含不支持的文件特性。',
  ARCHIVE_ENTRY_TYPE_REJECTED: '运行时归档包含不允许的文件类型。',
  ARCHIVE_PATH_INVALID: '运行时归档包含无效路径。',
  ARCHIVE_PATH_CONFLICT: '运行时归档中的文件路径发生冲突。',
  ARCHIVE_DUPLICATE_ENTRY: '运行时归档包含重复文件。',
  ARCHIVE_LINK_INVALID: '运行时归档包含无效链接。',
  ARCHIVE_TRUNCATED: '运行时归档内容不完整。',
  ARCHIVE_EXTRACTION_FAILED: '无法解压运行时归档。',
  BUNDLED_RUNTIME_MISSING: 'APK 未包含完整的内置运行时。',
  BUNDLED_RUNTIME_READ_FAILED: '无法读取 APK 内置运行时。',
  FILESYSTEM_ERROR: '无法安全读写应用私有运行时文件，请检查可用存储空间。',
  FILESYSTEM_SECURE_DELETE_UNAVAILABLE: '当前设备无法安全清理运行时文件。',
  CLEANUP_FAILED: '无法完整清理旧运行时文件，请重试。',
  RESET_SCOPE_INVALID: '为保护应用数据，已拒绝范围异常的文件清理操作。',
  STAGING_NOT_EMPTY: '运行时暂存目录状态异常，请重试。',
  RUNTIME_RECOVERY_FAILED: '无法恢复上次中断的运行时安装。',
  RUNTIME_PROMOTION_FAILED: '无法启用已完成校验的运行时。',
  RUNTIME_PRESERVE_FAILED: '旧运行时里的用户数据未能放回新运行时；数据与旧运行时备份均已保留，请勿重置环境。',
  INSTALL_IN_PROGRESS: '运行时安装正在进行。',
  INSTALL_CANCELLED: '运行时安装已取消，再次安装时可继续下载。',
  INSTALL_FAILED: '运行时安装失败，请稍后重试。',
  RUNTIME_BUSY: '请先停止 Harness 和 Ubuntu 终端。',
  RUNTIME_CORRUPTED: '运行时文件已损坏，请重置运行时后重新安装。',
  ROOTFS_LINKS_CORRUPTED: '运行时归档的关键符号链接缺失或损坏，请更换运行时来源后重新安装。',
  RUNNER_UNAVAILABLE: '此安装包不包含本机所需的运行组件，无法启动运行时。',
  PROOT_RUNNER_START_FAILED: 'Android 无法执行内置 PRoot，请确认安装的是新版 ARM64 应用。',
  PROOT_RUNNER_TIMEOUT: 'PRoot 自检超时，请停止其他会话后重试。',
  PROOT_RUNNER_REJECTED: '内置 PRoot 未通过启动自检。',
  PROOT_PROBE_TIMEOUT: 'PRoot 启动 Ubuntu 超时。',
  PROOT_PTRACE_DENIED: '系统内核拒绝 PRoot 所需的 ptrace 操作，当前设备可能不兼容。',
  PROOT_SECCOMP_UNAVAILABLE: '系统内核的 seccomp 策略与 PRoot 不兼容。',
  PROOT_GUEST_EXEC_FAILED: 'PRoot 无法加载 Ubuntu 程序。',
  PROOT_GUEST_START_FAILED: 'PRoot 无法启动 Ubuntu 用户空间。',
  RUNNER_PREPARE_FAILED: '无法准备内置 PRoot 运行器。',
  PROOT_REQUIRED_BIND_FAILED: 'PRoot 无法挂载 Ubuntu 必需的 DNS、设备或进程路径。',
  NODE_RUNTIME_FAILED: '内置 Node.js 无法在当前设备运行。',
  NODE_CPU_UNSUPPORTED: '设备 CPU 无法执行内置 Node.js。',
  HARNESS_PREFLIGHT_FAILED: 'Harness 命令未通过启动自检。',
  CREDENTIALS_DECRYPT_FAILED: '无法读取已保存的模型密钥；原有数据已保留，请稍后重试。',
  CREDENTIALS_ENCRYPT_FAILED: '无法安全保存模型密钥；请稍后重试。',
  RUNTIME_CONFIG_FAILED: '无法生成模型供应商启动配置，请检查运行时文件后重试。',
  HARNESS_PORT_IN_USE: 'Harness 本机端口已被占用，请停止占用端口的程序后重试。',
  HARNESS_MODULE_MISSING: 'Harness 运行模块不完整。',
  HARNESS_NATIVE_MODULE_FAILED: 'Harness 原生模块无法在当前设备运行。',
  HARNESS_START_TIMEOUT: 'Harness 首次启动超时，请重试或先打开 Ubuntu 终端检查环境。',
  HARNESS_AUTH_UNAVAILABLE: 'Harness 未提供有效的网页认证入口，请更新运行环境后重试。',
  HARNESS_EXITED: 'Harness 在完成启动前已退出。',
  HARNESS_STOP_FAILED: '无法停止 Harness 进程，请重试。',
  HARNESS_STOP_TIMEOUT: 'Harness 未在限定时间内停止，请重试。',
  HARNESS_STOP_INTERRUPTED: 'Harness 停止操作被中断，请重试。',
  SHIZUKU_UNBIND_TIMEOUT: 'Shizuku 设备服务未在限定时间内退出，请重试。',
  SHIZUKU_UNBIND_INTERRUPTED: 'Shizuku 设备服务停止操作被中断，请重试。',
  SHIZUKU_UNBIND_FAILED: '无法停止 Shizuku 设备服务，请重试。',
  SHIZUKU_DISCONNECTING: 'Shizuku 设备服务正在停止，请稍后重试。',
}

function runtimeErrorMessage(errorCode?: string): string {
  if (errorCode === undefined) return t("请重试；若问题持续，可在「Ubuntu 运行时」页重置后重新安装，重置会清除运行时内的数据。")
  return t(RUNTIME_ERROR_MESSAGES[errorCode] ?? UNKNOWN_RUNTIME_ERROR_MESSAGE)
}

function mergeRuntimeProgress(state: RuntimeState, progress: RuntimeProgress): RuntimeState {
  return {
    ...state,
    phase: progress.phase,
    downloadedBytes: progress.downloadedBytes,
    totalBytes: progress.totalBytes,
    errorCode: progress.phase === 'error' ? progress.errorCode : undefined,
  }
}

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / (1024 ** index)
  return `${value >= 10 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && 'code' in error && typeof error.code === 'string' && Object.hasOwn(RUNTIME_ERROR_MESSAGES, error.code)) {
    return runtimeErrorMessage(error.code)
  }
  if (!(error instanceof Error)) return t("操作失败，请稍后重试")
  const message = Array.from(error.message.trim())
    .map(character => {
      const code = character.charCodeAt(0)
      return code <= 31 || code === 127 ? ' ' : character
    })
    .slice(0, MAX_NOTICE_CHARACTERS)
    .join('')
    .trim()
  return message === '' ? t("操作失败，请稍后重试") : t(message)
}

function Brand() {
  return (
    <div className="brand" aria-label="DeepSeek Harness">
      <span className="brand-symbol" aria-hidden="true"><img src={appMark} alt="" width={26} height={26} /></span>
      <span className="brand-name">deepseek</span>
      <span className="brand-badge">HARNESS</span>
    </div>
  )
}

function PhaseBadge({ phase }: { phase: RuntimePhase }) {
  const meta = PHASE_META[phase]
  return (
    <span className={`phase-badge phase-${meta.tone}`}>
      <span className="phase-dot" />
      {t(meta.label)}
    </span>
  )
}

function runtimeInstalled(runtime: RuntimeState): boolean {
  return runtime.installedVersion !== undefined || ['ready', 'running', 'stopping'].includes(runtime.phase)
}

function runtimeTransitioning(runtime: RuntimeState): boolean {
  return ['preparing', 'downloading', 'verifying', 'extracting'].includes(runtime.phase)
}

/** 通知权限显示文案；三种状态都由原生端给出，前端不猜测系统行为。 */
const NOTIFICATION_PERMISSION_LABELS = {
  granted: '已授予',
  prompt: '未授予',
  unsupported: '系统不支持',
} as const

/** 最近一次停止的判定方式：只区分「用户主动停止」与「运行时自己停了」。 */
type LastStopReason = 'none' | 'user' | 'self'

/**
 * 「上次停止」的显示文案。
 *
 * `none` 表示**本次会话没有观察到停止**，不是「从未停止」：应用看不到原生侧更早的记录，
 * 这里不替它编一个结论。
 */
const LAST_STOP_LABELS: Record<LastStopReason, string> = {
  none: '本次会话未记录',
  user: '用户停止',
  self: '自行停止（可能空闲自动停止）',
}

/**
 * 格式化持久化的恢复记录时间。
 * 无记录时返回空串，界面据此隐藏该行，不显示伪造或推断出来的时间。
 */
function formatRecordedAt(millis: number | undefined): string {
  if (millis === undefined || !Number.isFinite(millis) || millis <= 0) return ''
  try {
    return new Date(millis).toLocaleString()
  } catch {
    return ''
  }
}

/**
 * 给可能长时间不返回的系统交互加超时兜底。
 * 只在“超时后可安全降级”的场景使用。
 */
function withTimeout<T>(promise: Promise<T>, milliseconds: number): Promise<T | null> {
  return new Promise(resolve => {
    const timer = window.setTimeout(() => resolve(null), milliseconds)
    promise.then(
      value => {
        window.clearTimeout(timer)
        resolve(value)
      },
      () => {
        window.clearTimeout(timer)
        resolve(null)
      },
    )
  })
}

interface ConversationScreenProps {
  busy: string | null
  keepAlive: KeepAliveState
  runtime: RuntimeState
  onInstall: () => void
  onLaunch: () => void
  onOpenSettings: () => void
  onOpenTerminal: () => void
  onUpdate: () => void
}

function ConversationScreen({ busy, keepAlive, runtime, onInstall, onLaunch, onOpenSettings, onOpenTerminal, onUpdate }: ConversationScreenProps) {
  const installed = runtimeInstalled(runtime)
  const transitioning = runtimeTransitioning(runtime)
  const updateRequired = installed && runtime.updateAvailable && !transitioning
  const progress = runtime.totalBytes > 0
    ? Math.min(100, Math.round((runtime.downloadedBytes / runtime.totalBytes) * 100))
    : 0

  return (
    <div className="screen conversation-gate">
      {/* 运行阶段徽章只保留页头那一处：同一屏里重复两个相同状态纯属噪声。 */}
      <div className="screen-heading">
        <div>
          <p className="eyebrow">{t("Harness 对话")}</p>
          <h1>{updateRequired ? t("更新运行环境") : runtime.phase === 'error' ? t("运行环境需要处理") : installed ? t("正在进入对话") : t("准备运行环境")}</h1>
        </div>
      </div>

      <section className="launch-panel">
        <span className="launch-icon" aria-hidden="true">
          {busy === 'launch' || transitioning ? <Loader2 className="spin" size={30} /> : updateRequired ? <RefreshCw size={30} /> : <img src={appMark} alt="" width={38} height={38} />}
        </span>
        <div className="launch-copy">
          <h2>
            {updateRequired
              ? t("安装包内置了新版运行环境")
              : runtime.phase === 'error'
              ? installed
                ? t("运行环境启动失败")
                : t("安装未完成")
              : transitioning
              ? t(PHASE_META[runtime.phase].label)
              : installed
                ? t("正在打开 Harness 对话")
                : t("首次使用需要准备运行环境")}
          </h2>
          <p>
            {updateRequired
              ? t("需要先更新运行环境，才能继续打开 Harness；更新会替换其中的本地修改。")
              : runtime.phase === 'error'
              ? runtimeErrorMessage(runtime.errorCode)
              : transitioning
              ? `${formatBytes(runtime.downloadedBytes)} / ${formatBytes(runtime.totalBytes)}`
              : installed
                ? t("应用会自动启动本机服务并进入对话。")
                : t("安装时会校验运行时完整性；从网络下载的，中断后可以继续。")}
          </p>
        </div>

        {transitioning && (
          <div className="download-progress gate-progress" aria-live="polite">
            <div className="progress-copy"><span>{t(PHASE_META[runtime.phase].label)}</span><strong>{runtime.totalBytes > 0 ? `${progress}%` : t("处理中")}</strong></div>
            <div className="progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}>
              <span style={{ width: `${runtime.totalBytes > 0 ? progress : 100}%` }} className={runtime.totalBytes > 0 ? '' : 'indeterminate'} />
            </div>
          </div>
        )}

        <div className="launch-actions">
          {!installed && !transitioning && (
            <button className="button button-primary" type="button" onClick={onInstall} disabled={busy !== null || !runtime.runnerAvailable}>
              {busy === 'install' ? <Loader2 className="spin" size={18} /> : <Download size={18} />}
              {runtime.phase === 'error' ? t("重试安装") : t("安装并进入对话")}
            </button>
          )}
          {updateRequired && (
            <button className="button button-primary" type="button" onClick={onUpdate} disabled={busy !== null || !runtime.runnerAvailable}>
              <RefreshCw size={18} />{t("更新运行环境")}</button>
          )}
          {installed && !transitioning && !updateRequired && busy !== 'launch' && (
            <button className="button button-primary" type="button" onClick={onLaunch} disabled={busy !== null}>
              <Play size={18} fill="currentColor" />{t("打开对话")}</button>
          )}
          {runtime.phase === 'error' && installed && !updateRequired && (
            <button className="button button-secondary" type="button" onClick={onOpenTerminal} disabled={busy !== null}>
              <SquareTerminal size={18} />{t("打开终端排查")}</button>
          )}
          <button className="button button-secondary" type="button" onClick={onOpenSettings} disabled={busy === 'install'}>
            <Settings2 size={18} />{t("应用设置")}</button>
        </div>
      </section>

      {keepAlive.reconnectRequired && !transitioning && (
        <div className="inline-alert warning" role="alert">
          <AlertTriangle size={19} />
          <div>
            <strong>{t("需要重新连接")}</strong>
            <span>{t("应用进程已被系统回收，旧的 Harness 会话与临时凭据无法恢复；请重新连接以启动新的本机会话。")}</span>
          </div>
          <button className="button button-primary compact-button" type="button" onClick={onLaunch} disabled={busy !== null || !installed}>
            {busy === 'launch' ? <Loader2 className="spin" size={18} /> : <RefreshCw size={18} />}{t("重新连接")}
          </button>
        </div>
      )}

      {!runtime.runnerAvailable && (
        <div className="inline-alert warning" role="alert">
          <AlertTriangle size={19} />
          <div><strong>{t("缺少本机运行组件")}</strong><span>{t("请安装支持当前 arm64 设备的新版应用。")}</span></div>
        </div>
      )}
    </div>
  )
}

interface EnvironmentScreenProps {
  busy: string | null
  bundledSource: boolean
  runtime: RuntimeState
  onBack: () => void
  onInstall: () => void
  onReset: () => void
  onStart: () => void
  onStop: () => void
  onUpdate: () => void
  onShareWorkspace: () => void
  onListFiles: () => void
  workspaceFiles: string[]
  onShareFile: (path: string) => void
  onOpenFile: (path: string) => void
}

function EnvironmentScreen({ busy, bundledSource, runtime, onBack, onInstall, onReset, onStart, onStop, onUpdate, onShareWorkspace, onListFiles, workspaceFiles, onShareFile, onOpenFile }: EnvironmentScreenProps) {
  const inProgress = ['preparing', 'downloading', 'verifying', 'extracting'].includes(runtime.phase)
  const installed = runtime.installedVersion !== undefined || runtime.phase === 'ready' || runtime.phase === 'running'
  const progress = runtime.totalBytes > 0
    ? Math.min(100, Math.round((runtime.downloadedBytes / runtime.totalBytes) * 100))
    : 0

  const measurableProgress = ['preparing', 'downloading', 'extracting'].includes(runtime.phase) && runtime.totalBytes > 0
  const steps: Array<{ id: string; label: string }> = [
    { id: 'acquire', label: bundledSource ? t("读取") : t("下载") },
    { id: 'verify', label: t("校验") },
    { id: 'install', label: t("安装") },
    { id: 'ready', label: t("就绪") },
  ]
  const currentStep = installed
    ? 3
    : runtime.phase === 'preparing' || runtime.phase === 'downloading'
      ? 0
      : runtime.phase === 'verifying'
        ? 1
        : runtime.phase === 'extracting'
          ? 2
          : -1

  return (
    <div className="screen environment-screen">
      <div className="screen-heading management-heading">
        <div>
          <p className="eyebrow">{t("应用管理")}</p>
          <h1>{t("Ubuntu 运行时")}</h1>
        </div>
        <div className="heading-actions">
          <PhaseBadge phase={runtime.phase} />
          <button className="icon-button" type="button" aria-label={t("返回设置")} title={t("返回设置")} onClick={onBack}><ArrowLeft size={19} /></button>
        </div>
      </div>

      <section className="runtime-overview">
        <div className="runtime-title-row">
          <span className="runtime-logo" aria-hidden="true"><img src={appMark} alt="" width={34} height={34} /></span>
          <div>
            <h2>Ubuntu 24.04</h2>
            <p>{runtime.installedVersion === undefined ? t("等待安装") : t("运行时 {0}", runtime.installedVersion)}</p>
          </div>
        </div>

        {inProgress && (
          <div className="download-progress" aria-live="polite">
            <div className="progress-copy">
              <span>{t(PHASE_META[runtime.phase].label)}</span>
              <strong>{measurableProgress ? `${progress}%` : t("处理中")}</strong>
            </div>
            <div className="progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}>
              <span style={{ width: `${measurableProgress ? progress : 100}%` }} className={measurableProgress ? '' : 'indeterminate'} />
            </div>
            <div className="progress-detail">
              <span>{formatBytes(runtime.downloadedBytes)}</span>
              <span>{formatBytes(runtime.totalBytes)}</span>
            </div>
          </div>
        )}

        <div className="install-steps" aria-label={t("安装阶段")}>
          {steps.map((step, index) => {
            const complete = installed || currentStep > index
            const active = currentStep === index && !installed
            return (
              <div className={`install-step ${complete ? 'complete' : ''} ${active ? 'active' : ''}`} key={step.id}>
                <span>{complete ? <CheckCircle2 size={17} /> : index + 1}</span>
                <small>{t(step.label)}</small>
              </div>
            )
          })}
        </div>

        <div className="runtime-actions">
          {!installed && !inProgress && (
            <button className="button button-primary" type="button" onClick={onInstall} disabled={busy !== null || !runtime.runnerAvailable}>
              {busy === 'install' ? <Loader2 className="spin" size={18} /> : <CloudDownload size={18} />}
              {bundledSource ? t("安装内置环境") : t("下载并安装")}
            </button>
          )}
          {runtime.updateAvailable && installed && !inProgress && (
            <button className="button button-primary" type="button" onClick={onUpdate} disabled={busy !== null || runtime.phase === 'running' || !runtime.runnerAvailable}>
              <RefreshCw size={18} />{runtime.phase === 'running' ? t("停止后更新") : t("更新运行环境")}
            </button>
          )}
          {runtime.phase === 'ready' && !runtime.updateAvailable && (
            <button className="button button-primary" type="button" onClick={onStart} disabled={busy !== null}>
              {busy === 'launch' ? <Loader2 className="spin" size={18} /> : <Play size={18} fill="currentColor" />}
              {t("启动")}</button>
          )}
          {runtime.phase === 'running' && (
            <button className="button button-secondary" type="button" onClick={onStop} disabled={busy !== null}>
              {busy === 'stop' ? <Loader2 className="spin" size={18} /> : <Power size={18} />}
              {t("停止")}</button>
          )}
          {(installed || runtime.phase === 'error') && (
            <button className="button button-danger-quiet" type="button" onClick={onReset} disabled={busy !== null}>
              <RotateCcw size={18} />
              {t("重置环境")}</button>
          )}
        </div>
      </section>

      {runtime.updateAvailable && installed && (
        <div className="inline-alert warning" role="alert">
          <AlertTriangle size={19} />
          <div><strong>{t("安装包内置的运行环境有更新")}</strong><span>{t("更新会替换 Ubuntu 运行时的系统目录：用 apt 等装进系统的软件与其它本地修改会丢失；会话、模型密钥、Harness 设置、附件、技能和你安装的插件会保留。")}</span></div>
        </div>
      )}

      {runtime.phase === 'error' && (
        <div className="inline-alert danger" role="alert">
          <AlertTriangle size={19} />
          <div><strong>{installed ? t("运行环境启动失败") : t("安装未完成")}</strong><span>{runtimeErrorMessage(runtime.errorCode)}</span></div>
        </div>
      )}

      <section className="detail-section" aria-labelledby="environment-details">
        <h2 id="environment-details">{t("环境详情")}</h2>
        <div className="detail-list">
          <div className="detail-row"><span><Cpu size={18} />{t("架构")}</span><strong>{t(runtime.architecture)}</strong></div>
          <div className="detail-row"><span><Database size={18} />{t("运行时大小")}</span><strong>{formatBytes(runtime.totalBytes)}</strong></div>
          <div className="detail-row"><span><Gauge size={18} />{t("运行组件")}</span><strong>{runtime.runnerAvailable ? t("可用") : t("不可用")}</strong></div>
          <div className="detail-row"><span><LockKeyhole size={18} />{t("网络访问")}</span><strong>{t("仅本应用内")}</strong></div>
        </div>
      </section>
      {installed && <section className="detail-section workspace-export" aria-labelledby="workspace-export-title">
        <h2 id="workspace-export-title">{t("工作区文件")}</h2>
        <p>{t("将 DSH 在运行时工作区创建的文件打包后分享给其他应用")}</p>
        <button className="button button-secondary" type="button" onClick={onShareWorkspace} disabled={busy !== null}>
          {busy === 'workspace-share' ? <Loader2 className="spin" size={18} /> : <Share2 size={18} />}{t("分享工作区")}
        </button>
        <button className="button button-secondary" type="button" onClick={onListFiles} disabled={busy !== null}>{t("选择文件")}</button>
        {workspaceFiles.length > 0 && <div className="workspace-file-list">{workspaceFiles.map(path => <div className="workspace-file-row" key={path}><span title={path}>{path}</span><button className="compact-button" type="button" onClick={() => onOpenFile(path)} disabled={busy !== null}>{t("打开")}</button><button className="compact-button" type="button" onClick={() => onShareFile(path)} disabled={busy !== null}>{t("分享")}</button></div>)}</div>}
      </section>}
    </div>
  )
}

interface TerminalScreenProps {
  bridge: typeof runtimeBridge
  fontSize: number
  onAuthorize: () => void
  onBack: () => void
  onConnect: () => void
  onError: (message: string) => void
  onOpenEnvironment: () => void
  onOpenShizuku: () => void
  runtime: RuntimeState
  shizuku: ShizukuState
}

function TerminalScreen({ bridge, fontSize, onAuthorize, onBack, onConnect, onError, onOpenEnvironment, onOpenShizuku, runtime, shizuku }: TerminalScreenProps) {
  const [kind, setKind] = useState<TerminalKind>('ubuntu')
  const [epoch, setEpoch] = useState(0)
  // Ubuntu 终端只依赖 rootfs 已安装（bash 由 PRoot 直接启动，不经过 dsh web）：
  // dsh web 启动失败（phase=error）时也必须能进终端手动排查，而不是被闸在门外。
  const ubuntuReady = runtimeInstalled(runtime) && !runtimeTransitioning(runtime)
  const deviceReady = shizuku.installed && shizuku.running && shizuku.permission === 'granted' && shizuku.connected
  const ready = kind === 'ubuntu' ? ubuntuReady : deviceReady

  return (
    <div className="screen terminal-screen">
      <div className="screen-heading terminal-heading">
        <div>
          <p className="eyebrow">{t("应用管理")}</p>
          <h1>{t("终端")}</h1>
        </div>
        <div className="heading-actions">
          <button className="icon-button" type="button" title={t("重新连接")} aria-label={t("重新连接终端")} onClick={() => setEpoch(value => value + 1)} disabled={!ready}>
            <RefreshCw size={19} />
          </button>
          <button className="icon-button" type="button" title={t("返回设置")} aria-label={t("返回设置")} onClick={onBack}>
            <ArrowLeft size={19} />
          </button>
        </div>
      </div>

      <div className="segmented" role="tablist" aria-label={t("终端类型")}>
        <button type="button" role="tab" aria-selected={kind === 'ubuntu'} className={kind === 'ubuntu' ? 'active' : ''} onClick={() => setKind('ubuntu')}>
          <SquareTerminal size={17} />Ubuntu
        </button>
        <button type="button" role="tab" aria-selected={kind === 'device'} className={kind === 'device' ? 'active' : ''} onClick={() => setKind('device')}>
          <Smartphone size={17} />{t("设备 Shell")}</button>
      </div>

      {ready ? (
        <TerminalPanel key={`${kind}-${epoch}`} bridge={bridge} fontSize={fontSize} kind={kind} onError={onError} />
      ) : kind === 'ubuntu' ? (
        <div className="empty-terminal">
          <span><HardDrive size={27} /></span>
          <h2>{t("Ubuntu 尚未就绪")}</h2>
          <button className="button button-primary" type="button" onClick={onOpenEnvironment}>
            {t("前往运行环境")}</button>
        </div>
      ) : (
        <div className="empty-terminal">
          <span><KeyRound size={27} /></span>
          <h2>{!shizuku.installed ? t("未安装 Shizuku") : !shizuku.running ? t("Shizuku 未运行{0}", shizuku.version ? '（v' + shizuku.version + '）' : '') : shizuku.permission !== 'granted' ? t("需要 Shizuku 授权") : t("Shizuku 连接未就绪")}</h2>
          {shizuku.installed && !shizuku.running && (
            <p className="shizuku-hint">{t("Shizuku 服务不会自动启动：请在 Shizuku App 内通过无线调试或 adb 启动服务（设备重启后需重新启动）。")}</p>
          )}
          {shizuku.installed ? (
            <button className="button button-primary" type="button" onClick={!shizuku.running ? onOpenShizuku : shizuku.permission === 'granted' ? onConnect : onAuthorize}>
              {shizuku.permission === 'granted' && shizuku.running ? <RefreshCw size={18} /> : <ShieldCheck size={18} />}
              {!shizuku.running ? t("打开 Shizuku") : shizuku.permission === 'granted' ? t("连接 Shizuku") : t("请求授权")}
            </button>
          ) : (
            <button className="button button-secondary" type="button" onClick={onOpenShizuku}>
              <ExternalLink size={18} />{t("打开 Shizuku")}</button>
          )}
        </div>
      )}
    </div>
  )
}

interface SettingsHomeScreenProps {
  busy: string | null
  keepAlive: KeepAliveState
  runtime: RuntimeState
  diagnostic: DiagnosticLogState
  shizuku: ShizukuState
  onLaunch: () => void
  onOpenEnvironment: () => void
  onOpenPage: (page: SettingsPage) => void
  onOpenPlugins: () => void
  onOpenTerminal: () => void
  onStop: () => void
}

/**
 * 设置首页：只保留语言、运行环境管理与五个设置分类入口。
 * 具体选项在各自二级页里，避免单页堆叠过多控件。
 */
function SettingsHomeScreen({ busy, diagnostic, keepAlive, runtime, shizuku, onLaunch, onOpenEnvironment, onOpenPage, onOpenPlugins, onOpenTerminal, onStop }: SettingsHomeScreenProps) {
  return (
    <div className="screen settings-screen">
      <div className="screen-heading management-heading">
        <div>
          <p className="eyebrow">{t("应用管理")}</p>
          <h1>{t("设置")}</h1>
        </div>
        <button className="button button-primary conversation-button" type="button" onClick={onLaunch} disabled={busy !== null || !runtimeInstalled(runtime)}>
          {busy === 'launch' ? <Loader2 className="spin" size={18} /> : runtime.updateAvailable ? <RefreshCw size={18} /> : <Bot size={18} />}
          {runtime.updateAvailable ? t("更新运行环境") : t("打开 Harness")}
        </button>
      </div>

      <LanguageSettings />

      <section className="management-list" aria-label={t("运行环境管理")}>
        <button className="management-row" type="button" onClick={onOpenPlugins}>
          <span className="management-icon"><Settings2 size={20} /></span>
          <span className="management-copy"><strong>{t("插件管理")}</strong><small>{t("官方与第三方插件，按插件包管理启停与更新")}</small></span>
          <ChevronRight size={18} />
        </button>
        <div className="management-service">
          <span className="management-icon dark"><Bot size={20} /></span>
          <span className="management-copy">
            <strong>{t("Harness 服务")}</strong>
            <small>{runtime.phase === 'running' ? t("正在本机运行") : runtimeInstalled(runtime) ? t("已停止，可随时启动") : t("等待安装运行环境")}</small>
          </span>
          {runtime.phase === 'running' ? (
            <button className="button button-danger-quiet compact-button" type="button" onClick={onStop} disabled={busy !== null}><Square size={16} />{t("停止")}</button>
          ) : (
            <PhaseBadge phase={runtime.phase} />
          )}
        </div>
        <button className="management-row" type="button" onClick={onOpenEnvironment}>
          <span className="management-icon dark"><HardDrive size={20} /></span>
          <span className="management-copy"><strong>{t("Ubuntu 运行时")}</strong><small>{runtime.updateAvailable ? t("发现内置运行环境更新") : t("安装进度、版本、来源与重置")}</small></span>
          <ChevronRight size={18} />
        </button>
        <button className="management-row" type="button" onClick={onOpenTerminal}>
          <span className="management-icon"><SquareTerminal size={20} /></span>
          <span className="management-copy"><strong>{t("终端与设备 Shell")}</strong><small>{t("Ubuntu 终端与设备 Shell（需 Shizuku）")}</small></span>
          <ChevronRight size={18} />
        </button>
      </section>

      <section className="management-list" aria-label={t("设置分类")}>
        {SETTINGS_PAGES.map(page => {
          const meta = SETTINGS_PAGE_META[page]
          const badge = page === 'runtime' ? (keepAlive.foregroundServiceActive ? t("后台保持中") : keepAlive.keepRuntimeInBackground ? t("已开启") : t("未开启"))
            : page === 'shizuku' ? (!shizuku.installed ? t("未安装") : shizuku.connected ? t("已连接") : shizuku.permission === 'granted' ? t("已授权") : t("待授权"))
              : page === 'diagnostics' ? (diagnostic.enabled ? t("收集中") : t("未收集"))
                : ''
          return (
            <button className="management-row" key={page} type="button" onClick={() => onOpenPage(page)}>
              <span className="management-icon">
                {page === 'models' ? <KeyRound size={20} /> : page === 'runtime' ? <Power size={20} /> : page === 'terminal' ? <SquareTerminal size={20} /> : page === 'shizuku' ? <Smartphone size={20} /> : <ScrollText size={20} />}
              </span>
              <span className="management-copy">
                <strong>{t(meta.title)}</strong>
                <small>{badge === '' ? t(meta.hint) : `${t(meta.hint)} · ${badge}`}</small>
              </span>
              <ChevronRight size={18} />
            </button>
          )
        })}
      </section>
    </div>
  )
}

interface HarnessLogPanelProps {
  /** 读取访客进程输出尾部；只在用户展开区块或切换窗口时调用。 */
  loadHarnessLog: (maxBytes?: number) => Promise<HarnessLog>
}

interface DiagnosticLogPanelProps {
  /** 读取诊断日志尾部窗口，供应用内查看；只在展开或切换窗口时调用。 */
  loadDiagnosticLog: (maxBytes?: number) => Promise<DiagnosticLogText>
}

/** 窗口字节数的人类可读写法：8 KB / 64 KB / 256 KB。 */
function formatLogWindow(bytes: number): string {
  return `${Math.round(bytes / 1024)} KB`
}

/**
 * 一次渲染的最大行数。
 *
 * 日志窗口最大 256 KB，整段铺进 DOM 会拖慢设置页（机型越旧越明显）。
 * 超出时只渲染**最近**的这些行：排障要看的是最后发生了什么，
 * 更早的部分仍然可以复制全文带走。
 */
const LOG_MAX_RENDERED_LINES = 2000

/** 级别判定只用于着色，不改变任何文本内容。 */
const LOG_ERROR_LINE = /(^|[^a-z])(error|fatal|exception|failed|failure|traceback)([^a-z]|$)/i
const LOG_WARN_LINE = /(^|[^a-z])(warn|warning)([^a-z]|$)/i

function logLineClass(line: string): string {
  if (LOG_ERROR_LINE.test(line)) return 'log-line log-line-error'
  if (LOG_WARN_LINE.test(line)) return 'log-line log-line-warn'
  return 'log-line'
}

interface LogTextProps {
  /** 日志正文。可能来自访客进程或原生诊断目录，一律按纯文本渲染。 */
  text: string
  /** 输出容器的 class，供样式与用例定位（例如 `harness-log-output`）。 */
  outputClassName: string
}

/**
 * 日志正文的阅读器：过滤、级别着色、判读提示与复制。
 *
 * 为什么要有过滤与判读：一段 64 KB 的日志靠肉眼翻，用户得到的往往只是「看起来有很多错」。
 * 过滤让用户能盯着一个关键字，判读提示（[readLogInsights]）把已确诊的签名翻译成结论与下一步。
 *
 * 隐私边界：这里只负责显示调用方已经取到的文本。访客输出可能含会话内容，
 * 因此**不落盘、不写诊断日志、也不随诊断日志导出**；渲染一律走文本节点，
 * 绝不用 dangerouslySetInnerHTML —— 内容不是受控文案。
 */
function LogText({ text, outputClassName }: LogTextProps) {
  const [filter, setFilter] = useState('')
  const [copied, setCopied] = useState(false)
  const [copyFailed, setCopyFailed] = useState(false)
  const copyResetTimer = useRef(0)

  useEffect(() => () => window.clearTimeout(copyResetTimer.current), [])

  const lines = useMemo(() => text.split('\n'), [text])
  const insights = useMemo(() => readLogInsights(text), [text])
  const query = filter.trim().toLowerCase()
  const matched = useMemo(
    () => (query === '' ? lines : lines.filter(line => line.toLowerCase().includes(query))),
    [lines, query],
  )
  const omitted = Math.max(0, matched.length - LOG_MAX_RENDERED_LINES)
  const visible = omitted === 0 ? matched : matched.slice(omitted)

  // 复用终端面板同一套剪贴板实现：不额外引入依赖。
  const copyLog = useCallback(() => {
    if (text === '') return
    void (async () => {
      try {
        await navigator.clipboard.writeText(text)
        setCopied(true)
        setCopyFailed(false)
        window.clearTimeout(copyResetTimer.current)
        copyResetTimer.current = window.setTimeout(() => setCopied(false), 1500)
      } catch {
        setCopyFailed(true)
      }
    })()
  }, [text])

  return (
    <>
      {insights.length > 0 && (
        <div className="log-insights" role="group" aria-label={t("判读提示")}>
          {insights.map(insight => (
            <div className="log-insight" key={insight.id}>
              <strong>{t(insight.title)}</strong>
              <span>{t(insight.meaning)}</span>
              <span className="log-insight-next">{t("下一步：")}{t(insight.nextStep)}</span>
            </div>
          ))}
        </div>
      )}

      <label className="field log-filter">
        <span>{t("过滤日志")}</span>
        <input
          type="search"
          autoComplete="off"
          spellCheck={false}
          placeholder={t("输入关键字，例如 error、plugin、credential")}
          value={filter}
          onChange={event => setFilter(event.target.value)}
        />
      </label>

      <p className="harness-log-state">
        {query === ''
          ? t("共 {0} 行", lines.length)
          : t("匹配 {0} / {1} 行", matched.length, lines.length)}
        {omitted > 0 ? t("，已省略更早的 {0} 行", omitted) : ''}
      </p>

      {matched.length === 0 && <p className="harness-log-state">{t("没有匹配的行")}</p>}

      {matched.length > 0 && (
        <>
          <pre className={`log-output ${outputClassName}`}>
            {visible.map((line, index) => (
              <span className={logLineClass(line)} key={index}>
                {line}
                {index < visible.length - 1 ? '\n' : ''}
              </span>
            ))}
          </pre>
          <div className="settings-inline-actions">
            <button className="button button-secondary" type="button" onClick={copyLog}>
              {copied ? <CheckCircle2 size={18} /> : <Copy size={18} />}
              {copied ? t("已复制") : t("复制")}
            </button>
          </div>
        </>
      )}

      {copyFailed && (
        <p className="harness-log-state" role="alert">{t("复制失败，请长按选择文本后复制")}</p>
      )}
    </>
  )
}

/** 窗口选择器：三档（运行日志）或两档（诊断日志），由调用方给出可选值。 */
function LogWindowPicker({ options, value, onChange }: {
  options: readonly number[]
  value: number
  onChange: (bytes: number) => void
}) {
  if (options.length < 2) return null
  return (
    <label className="field">
      <span>{t("读取窗口")}</span>
      <select value={value} onChange={event => onChange(Number(event.target.value))}>
        {options.map(bytes => <option key={bytes} value={bytes}>{formatLogWindow(bytes)}</option>)}
      </select>
    </label>
  )
}

/**
 * 「运行日志（最近 8 KB / 64 KB / 256 KB）」折叠区块。
 *
 * 为什么需要它：工具调用失败时界面只显示一句不带栈信息的报错，唯一线索是访客进程
 * 自己打在 stdout/stderr 上的完整输出（异常栈、插件加载报错等）。8 KB 常常只够一段栈，
 * 因此窗口可选：缓冲区按最大档分配，切换窗口只是重新截取尾部。
 *
 * 隐私边界：这段文本来自访客进程，**可能包含会话内容**（工具参数、代码片段等），
 * 因此只在本机界面展示 —— 不写入诊断日志、不新增诊断事件，也不随诊断日志导出。
 * 也正因如此，读取是**按需**的：折叠状态下不碰桥接，展开时才去取一次最新尾部。
 */
function HarnessLogPanel({ loadHarnessLog }: HarnessLogPanelProps) {
  const [open, setOpen] = useState(false)
  const [windowBytes, setWindowBytes] = useState<number>(HARNESS_LOG_WINDOW_OPTIONS[0])
  const [log, setLog] = useState<HarnessLog | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!open) return
    let cancelled = false
    setLoading(true)
    setFailed(false)
    void loadHarnessLog(windowBytes)
      .then(next => { if (!cancelled) setLog(next) })
      .catch(() => {
        // 读取失败时不保留上一次的内容：界面要么显示本次真实结果，要么明确说读不到。
        if (!cancelled) { setLog(null); setFailed(true) }
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    // 折叠回来时丢弃在途结果，避免收起后又被异步写回内容。
    return () => { cancelled = true }
  }, [loadHarnessLog, open, windowBytes])

  const hasText = log !== null && log.available && log.text !== ''

  return (
    <section className="settings-section harness-log-section" aria-labelledby="harness-log-title">
      <button
        className="harness-log-toggle"
        type="button"
        aria-expanded={open}
        aria-controls="harness-log-body"
        onClick={() => setOpen(current => !current)}
      >
        <span className="section-icon"><ScrollText size={19} /></span>
        <span className="harness-log-heading">
          <strong id="harness-log-title">{t("运行日志（最近 {0}）", formatLogWindow(windowBytes))}</strong>
          <small>{t("Harness 进程输出尾部，用于排查工具调用失败")}</small>
        </span>
        {open ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
      </button>

      {/*
        折叠时只保留一个空的隐藏容器：aria-controls 指向的元素始终存在，
        内容与读取都只在展开后发生。
      */}
      <div className="harness-log-body" id="harness-log-body" hidden={!open}>
        {open && (
        <>
          <p className="settings-note">
            {t("这段内容来自 Harness 进程输出，可能包含会话内容，仅供排障；它只在设备界面里显示，不会写入诊断日志，也不随诊断日志导出。")}
          </p>

          <LogWindowPicker options={HARNESS_LOG_WINDOW_OPTIONS} value={windowBytes} onChange={setWindowBytes} />

          {loading && (
            <p className="harness-log-state"><Loader2 className="spin" size={16} />{t("正在读取运行日志…")}</p>
          )}
          {!loading && failed && (
            <p className="harness-log-state" role="alert">{t("读取运行日志失败，请稍后重试")}</p>
          )}
          {!loading && !failed && log !== null && !log.available && (
            <p className="harness-log-state">{t("当前没有可读取的运行日志")}</p>
          )}
          {!loading && !failed && log !== null && log.available && log.text === '' && (
            <p className="harness-log-state">{t("Harness 进程最近没有输出")}</p>
          )}

          {!loading && !failed && hasText && (
            <LogText text={log.text} outputClassName="harness-log-output" />
          )}
        </>
        )}
      </div>
    </section>
  )
}

/**
 * 「诊断日志（最近 64 KB / 256 KB）」折叠区块。
 *
 * 之前这里只能看计数和导出：出了问题要么把文件分享出去，要么凭计数猜。现在可以直接
 * 在应用内查看正文（受控字段：时间、级别、事件、状态码、计数），并配合判读提示判断。
 *
 * 与导出的区别：查看只读**尾部窗口**、不落盘、不产生文件；导出仍然是全部文件，
 * 用于交给别人排查。正文不含 URL、凭据、终端内容或用户数据，因此读进界面不构成新泄露面。
 */
function DiagnosticLogPanel({ loadDiagnosticLog }: DiagnosticLogPanelProps) {
  const [open, setOpen] = useState(false)
  const [windowBytes, setWindowBytes] = useState<number>(DIAGNOSTIC_LOG_WINDOW_OPTIONS[0])
  const [log, setLog] = useState<DiagnosticLogText | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!open) return
    let cancelled = false
    setLoading(true)
    setFailed(false)
    void loadDiagnosticLog(windowBytes)
      .then(next => { if (!cancelled) setLog(next) })
      .catch(() => {
        if (!cancelled) { setLog(null); setFailed(true) }
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [loadDiagnosticLog, open, windowBytes])

  const hasText = log !== null && log.text !== ''

  return (
    <section className="settings-section harness-log-section" aria-labelledby="diagnostic-log-title">
      <button
        className="harness-log-toggle"
        type="button"
        aria-expanded={open}
        aria-controls="diagnostic-log-body"
        onClick={() => setOpen(current => !current)}
      >
        <span className="section-icon"><ScrollText size={19} /></span>
        <span className="harness-log-heading">
          <strong id="diagnostic-log-title">{t("诊断日志（最近 {0}）", formatLogWindow(windowBytes))}</strong>
          <small>{t("应用内部状态码与计数，含启动失败原因码")}</small>
        </span>
        {open ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
      </button>

      <div className="harness-log-body" id="diagnostic-log-body" hidden={!open}>
        {open && (
        <>
          <p className="settings-note">
            {t("只包含应用内部的事件名、级别、状态码与计数，不含 URL、凭据、终端内容或用户数据；这里显示的是最近一段，导出会给出全部文件。")}
          </p>

          <LogWindowPicker options={DIAGNOSTIC_LOG_WINDOW_OPTIONS} value={windowBytes} onChange={setWindowBytes} />

          {loading && (
            <p className="harness-log-state"><Loader2 className="spin" size={16} />{t("正在读取诊断日志…")}</p>
          )}
          {!loading && failed && (
            <p className="harness-log-state" role="alert">{t("读取诊断日志失败，请稍后重试")}</p>
          )}
          {!loading && !failed && log !== null && !hasText && (
            <p className="harness-log-state">{t("当前没有可查看的诊断日志；开启采集后重新操作一次即可产生记录。")}</p>
          )}
          {!loading && !failed && hasText && log !== null && (
            <>
              {log.truncated && (
                <p className="harness-log-state">{t("已按窗口截断：这里是最近一段，导出可获取全部内容。")}</p>
              )}
              <LogText text={log.text} outputClassName="diagnostic-log-output" />
            </>
          )}
        </>
        )}
      </div>
    </section>
  )
}

/** 自检状态徽章：配色与标签都由这里决定，`skipped` 用中性样式，不冒充「通过」。 */
const SELF_CHECK_STATUS_META: Record<SelfCheckStatus, { label: string; chip: string }> = {
  ok: { label: '正常', chip: 'success' },
  warn: { label: '注意', chip: 'warn' },
  fail: { label: '失败', chip: 'danger' },
  skipped: { label: '跳过', chip: '' },
}

/** 自检给出的可用空间低于这一档时提示清理：安装、解压与会话保存都可能因空间失败。 */
const SELF_CHECK_LOW_SPACE_BYTES = 512 * 1024 * 1024

/**
 * 出现这些「检查项 + 结论码」组合时，逐项列表之上先给一条跨项汇总。
 *
 * 为什么按 id 与 code 一起匹配：`PTY_EXIT_EARLY` 在裸 `pty` 上同样合法，但那说明断在 PTY 层
 * （见 `runtimeSelfCheck.ts` 的说明），只有 `pty_sandbox` 上的这一码才能和上面两项串成
 * 「沙箱后端不可用 → 被沙箱包裹的命令起不来」这同一条因果链。
 * `PROBE_PARTIAL` 也不在其中：老 ABI 只支持部分 Landlock 能力，自检本身也认为一般仍可用。
 */
const SELF_CHECK_SANDBOX_BLOCKERS: readonly { id: SelfCheckId; code: SelfCheckCode }[] = [
  { id: 'sandbox_probe', code: 'PROBE_UNUSABLE' },
  { id: 'sandbox_exec', code: 'EXEC_LAUNCHER_FAILED' },
  { id: 'pty_sandbox', code: 'PTY_EXIT_EARLY' },
]

/** 自检结果里是否出现了「本机沙箱后端不可用」这一类断点。 */
function selfCheckSandboxBlocked(checks: readonly SelfCheckItem[]): boolean {
  return checks.some(item =>
    SELF_CHECK_SANDBOX_BLOCKERS.some(blocker => item.id === blocker.id && item.code === blocker.code))
}

interface RuntimeSelfCheckPanelProps {
  /** 当前运行时状态：只取已安装版本，用于「运行时版本」一行。 */
  runtime: RuntimeState
  /** 运行自检；只在用户点击按钮时调用，进入页面不自动跑。 */
  runSelfCheck: (operation: SelfCheckOperation) => Promise<SelfCheckReport>
}

/**
 * 单条自检结果：检查项标签 + 状态徽章 + 结论与下一步。
 *
 * 文案全部来自 [selfCheckAdvice] 的受控映射（检查项 id 与结论码都是枚举），
 * 载荷里没有、也不会渲染任何自由文本，因此这里不存在把访客内容带进界面的路径。
 */
function SelfCheckRow({ item }: { item: SelfCheckItem }) {
  const advice = selfCheckAdvice(item)
  const meta = SELF_CHECK_STATUS_META[item.status]
  return (
    <div className={`self-check-row ${item.status}`}>
      <div className="self-check-row-head">
        <span className="self-check-label">{t(advice.label)}</span>
        <span className={meta.chip === '' ? 'status-chip' : `status-chip ${meta.chip}`}>{t(meta.label)}</span>
      </div>
      {advice.meaning !== '' && <p className="self-check-meaning">{t(advice.meaning)}</p>}
      {advice.nextStep !== '' && <p className="self-check-next">{t("下一步：")}{t(advice.nextStep)}</p>}
    </div>
  )
}

/**
 * 「运行时自检」区块。
 *
 * 为什么需要它：运行时链路断在哪一环，通常要靠 bash 才能查——可 bash 本身可能正是断掉
 * 的那一环。自检由原生侧逐环探测，这里把结果翻译成「哪一环断了 + 下一步」，
 * 并让用户一眼看到非 ok 的那些项。
 *
 * 与日志面板同样的按需原则：进页面**不自动自检**，只有用户点「运行自检」才调用；
 * 「修复运行时权限」也只在结果里出现权限位或缺失目录相关的结论码时才提供，
 * 避免给出一个修不了当前问题的按钮。自检载荷只含枚举、字节数与版本号，
 * 不含路径、命令输出或凭据，所以它会显示在界面上，但不落盘、不进诊断日志。
 */
function RuntimeSelfCheckPanel({ runSelfCheck, runtime }: RuntimeSelfCheckPanelProps) {
  const [report, setReport] = useState<SelfCheckCheckReport | null>(null)
  const [repair, setRepair] = useState<SelfCheckRepairReport | null>(null)
  const [checkedAt, setCheckedAt] = useState(0)
  const [busy, setBusy] = useState<SelfCheckOperation | null>(null)
  const [failed, setFailed] = useState<SelfCheckOperation | null>(null)
  const [showOk, setShowOk] = useState(false)

  const start = (operation: SelfCheckOperation): void => {
    setBusy(operation)
    setFailed(null)
    void runSelfCheck(operation)
      .then(next => {
        if (next.operation === 'repair') {
          setRepair(next)
          return
        }
        // 新一次自检整体覆盖旧结果：上一次的修复统计属于上一次的判断，留着只会让人误读。
        setReport(next)
        setRepair(null)
        setCheckedAt(Date.now())
        // 有非 ok 项时默认收起「正常项」，让断在哪一环先出现在视野里。
        setShowOk(false)
      })
      .catch(() => {
        // 与日志面板同一条原则：要么显示本次真实结果，要么明确说读不到。
        // 自检失败时清掉上一次的结果，避免旧结论被当成这次的结论读。
        if (operation === 'check') {
          setReport(null)
          setRepair(null)
        }
        setFailed(operation)
      })
      .finally(() => setBusy(null))
  }

  const checks = report?.checks ?? []
  const failing = checks.filter(item => item.status !== 'ok')
  const passing = checks.filter(item => item.status === 'ok')
  // 沙箱三项失败是同一个根因，逐条读只会看到三条并列的现象：先给一条跨项汇总说清因果。
  const sandboxBlocked = report !== null && selfCheckSandboxBlocked(checks)
  // 修复只改权限位与缺失目录，可用空间仍可能变化，因此以最新一次结果为显示值。
  const availableBytes = repair?.availableBytes ?? report?.availableBytes
  const lowSpace = availableBytes !== undefined && availableBytes < SELF_CHECK_LOW_SPACE_BYTES
  const checkedAtLabel = formatRecordedAt(checkedAt)

  return (
    <section className="settings-section" aria-labelledby="runtime-self-check-title">
      <div className="section-title">
        <span className="section-icon"><ShieldCheck size={19} /></span>
        <div>
          <h2 id="runtime-self-check-title">{t("运行时自检")}</h2>
          <p>{t("不需要 bash 也能判断运行时哪一环断了：逐项检查 Shell、Node、沙箱启动器、内核 Landlock、PTY、访客数据目录、附件目录与 ripgrep，并给出结论与下一步。")}</p>
        </div>
      </div>

      {/* 版本一行不依赖自检：没跑自检时也能看到已安装的运行时版本。 */}
      <div className="settings-status-list">
        <div className="settings-status-row">
          <span>{t("运行时版本")}</span>
          <strong>{runtime.installedVersion ?? t("未安装")}</strong>
        </div>
        <div className="settings-status-row">
          <span>{t("dsh 版本")}</span>
          {/* dsh 版本只有真正进过访客才读得到：自检结果之外不猜、也不为它单独发起重量级调用。 */}
          <strong>{report?.dshVersion ?? t("运行自检后显示")}</strong>
        </div>
      </div>

      <p className="settings-note">{t("已安装插件列表见「插件管理」")}</p>

      <div className="settings-inline-actions self-check-actions">
        <button className="button button-secondary" type="button" onClick={() => start('check')} disabled={busy !== null}>
          {busy === 'check' ? <Loader2 className="spin" size={18} /> : <ShieldCheck size={18} />}{t("运行自检")}
        </button>
        {selfCheckNeedsRepair(checks) && (
          <button className="button button-secondary" type="button" onClick={() => start('repair')} disabled={busy !== null}>
            {busy === 'repair' ? <Loader2 className="spin" size={18} /> : <Wrench size={18} />}{t("修复运行时权限")}
          </button>
        )}
      </div>

      <p className="harness-log-state">
        {busy === 'check'
          ? t("正在运行自检…")
          : busy === 'repair'
            ? t("正在修复运行时权限…")
            : checkedAtLabel === ''
              ? t("尚未自检")
              : t("上次自检：{0}", checkedAtLabel)}
      </p>

      {failed !== null && (
        <p className="harness-log-state" role="alert">
          {failed === 'check' ? t("自检未完成，请稍后重试") : t("修复未完成，请稍后重试")}
        </p>
      )}

      {availableBytes !== undefined && (
        <div className="settings-status-list">
          <div className="settings-status-row">
            <span>{t("可用空间")}</span>
            <strong>{formatBytes(availableBytes)}</strong>
          </div>
        </div>
      )}

      {lowSpace && (
        <div className="inline-alert warning" role="alert">
          <AlertTriangle size={19} />
          <div>
            <strong>{t("可用空间不足")}</strong>
            <span>{t("设备可用空间低于 512 MB：安装、解压与会话保存都可能失败，请先清理空间。")}</span>
          </div>
        </div>
      )}

      {repair !== null && (
        <>
          <p className="harness-log-state">{t("已修复 {0} 项（检查 {1} 项）", repair.repaired, repair.candidates)}</p>
          <p className="harness-log-state">{t("可重新运行「运行自检」确认修复结果。")}</p>
        </>
      )}

      {/* 跨项汇总放在逐项列表之前：先讲清「谁导致谁」，再看每一条的细节。 */}
      {sandboxBlocked && (
        <div className="inline-alert danger" role="alert">
          <AlertTriangle size={19} />
          <div>
            <strong>{t("本机没有可用的沙箱后端")}</strong>
            <span>{t("在要求沙箱的模式（例如 workspace-write）下，dsh 找不到可用的沙箱后端就会拒绝执行命令，这是它的 fail-closed 行为：bash 工具报「PTY shell exited during startup」通常是这条链的结果，不是工具本身坏了。")}</span>
            <span>{t("下一步：")}{t("在 Harness 的权限预设里选择不启用沙箱的模式，然后重启运行环境。这是明确的能力降级：访客内不再有 Landlock 的文件系统隔离；PRoot 与 Android 应用沙箱仍然有效，但 PRoot 只是用户态模拟，不提供宿主内核没有的隔离能力。")}</span>
          </div>
        </div>
      )}

      {report !== null && (
        <>
          {checks.length === 0 ? (
            <p className="harness-log-state">{t("自检没有返回任何检查项")}</p>
          ) : failing.length === 0 ? (
            <p className="harness-log-state">{t("全部 {0} 项检查通过", checks.length)}</p>
          ) : (
            <div className="self-check-list" role="group" aria-label={t("自检结果")}>
              {failing.map(item => <SelfCheckRow item={item} key={item.id} />)}
            </div>
          )}

          {/* 正常项默认收起：没有非 ok 项时它们本来也不构成信息，需要时再展开核对。 */}
          {passing.length > 0 && (
            <>
              <button
                className="self-check-toggle"
                type="button"
                aria-expanded={showOk}
                aria-controls="runtime-self-check-ok"
                onClick={() => setShowOk(current => !current)}
              >
                <span>{showOk ? t("收起正常项（{0}）", passing.length) : t("正常项（{0}）", passing.length)}</span>
                {showOk ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
              </button>
              <div id="runtime-self-check-ok" hidden={!showOk}>
                {showOk && (
                  <div className="self-check-list">
                    {passing.map(item => <SelfCheckRow item={item} key={item.id} />)}
                  </div>
                )}
              </div>
            </>
          )}
        </>
      )}
    </section>
  )
}

/**
 * 设置页草稿：用户尚未保存的编辑内容。
 *
 * 为什么由 App 持有：设置首页与五个二级页是**不同的组件**，页内状态在切页时随组件卸载丢掉
 * —— 用户刚输入的 API Key 只要切一次页就再也找不回来（即「输入的数据没有直接保存」）。
 * 草稿因此放在设置区之上，由设置区内的所有页面共享。
 *
 * 隐私边界：整份草稿只存在于内存（React 状态），不写 localStorage/sessionStorage、
 * 不进诊断日志，也不随任何导出离开设备。其中 [credentials] 与 [customCredentials]
 * 是密钥输入：原生侧落盘后不回显，因此它们只存在于「用户本次输入」到「保存成功」之间。
 */
interface SettingsDraft {
  /** 各字段的当前编辑值，初值由落盘设置复制而来。 */
  settings: RuntimeSettings
  /** 正在编辑的供应商；纯界面选择，切走再回来时停在原处。 */
  selectedProvider: ModelProviderId | 'custom'
  /** 用户在本页拨动过悬浮球开关时才有值；null 表示沿用原生侧真值。 */
  overlayBallEnabled: boolean | null
  /** 仅内存的 API Key 输入。 */
  credentials: ProviderApiKeys
  /** 用户点过「清除密钥」的供应商，保存时才提交。 */
  clearedProviders: ModelProviderId[]
  /** 自定义供应商的 API Key 输入（同上，仅内存）。 */
  customCredentials: Record<string, string>
  clearedCustomProviders: string[]
}

/**
 * 由落盘设置建立草稿。
 *
 * 凭据输入一律为空：密钥落盘后不回显，草稿里只保留用户本次输入的内容。
 * [previous] 只用于保留用户正在查看的供应商，避免后台刷新把界面跳回默认项。
 */
function draftFromSettings(settings: RuntimeSettings, previous: SettingsDraft | null = null): SettingsDraft {
  return {
    settings,
    selectedProvider: previous?.selectedProvider ?? 'deepseek',
    overlayBallEnabled: null,
    credentials: {},
    clearedProviders: [],
    customCredentials: {},
    clearedCustomProviders: [],
  }
}

interface SettingsScreenProps {
  busy: string | null
  settingsReadStatus: 'idle' | 'loading' | 'failed'
  /** 诊断日志状态：与设置草稿独立，由原生侧直接管理。 */
  diagnostic: DiagnosticLogState
  /** 未保存的设置草稿；null 表示设置还没读到，页面显示读取中。 */
  draft: SettingsDraft | null
  keepAlive: KeepAliveState
  /** 读取访客进程输出尾部（窗口可选）；由折叠区块在展开或切换窗口时按需调用。 */
  loadHarnessLog: (maxBytes?: number) => Promise<HarnessLog>
  /** 读取诊断日志正文窗口；同样只在展开或切换窗口时调用。 */
  loadDiagnosticLog: (maxBytes?: number) => Promise<DiagnosticLogText>
  /** 未编辑的悬浮球开关跟随原生状态；null 表示尚未取得快照。 */
  overlayBall: OverlayBallState | null
  overlayBallReadFailed: boolean
  page: SettingsPage
  runtime: RuntimeState
  /** 本次会话记录到的最近一次停止方式；`none` 表示本次会话还没观察到停止。 */
  lastStop: LastStopReason
  /** 运行自检（check / repair）；只在用户点击按钮时调用。 */
  runSelfCheck: (operation: SelfCheckOperation) => Promise<SelfCheckReport>
  shizuku: ShizukuState
  onAuthorize: () => void
  onBack: () => void
  onClearDiagnostic: () => void
  onConnect: () => void
  onDiagnosticSettings: (enabled: boolean, retentionDays: number) => void
  /**
   * 改写草稿。[dirty] 为假表示这次只改「正在看什么」（例如切换供应商下拉框），
   * 草稿里的值没有变化，不应妨碍后台刷新同步。
   */
  onDraftChange: (update: (current: SettingsDraft) => SettingsDraft, dirty?: boolean) => void
  onLaunch: () => void
  /** 用户确认「密钥已在 Harness 内配置过」时的放行入口。 */
  onLaunchConfirmed: () => void
  /** 跳转到系统「显示在其他应用上层」设置页；权限只能由用户手动开启。 */
  onOpenOverlaySettings: () => void
  onOpenShizuku: () => void
  onRequestNotificationPermission: () => void
  onReloadSettings: () => void
  onSave: (settings: RuntimeSettingsUpdate) => void
  onShareDiagnostic: () => void
}

function SettingsScreen({ busy, diagnostic, draft, keepAlive, loadDiagnosticLog, loadHarnessLog, lastStop, overlayBall, overlayBallReadFailed, onDraftChange, page, runSelfCheck, runtime, settingsReadStatus, shizuku, onAuthorize, onBack, onClearDiagnostic, onConnect, onDiagnosticSettings, onLaunch, onLaunchConfirmed, onOpenOverlaySettings, onOpenShizuku, onReloadSettings, onRequestNotificationPermission, onSave, onShareDiagnostic }: SettingsScreenProps) {
  if (settingsReadStatus === 'failed') {
    return <div className="screen loading-screen">
      <p role="alert">{t("无法读取最新设置，请重试")}</p>
      <button className="button button-primary" type="button" onClick={onReloadSettings}>{t("重试")}</button>
      <button className="button button-secondary" type="button" onClick={onBack}>{t("返回设置")}</button>
    </div>
  }
  if (settingsReadStatus === 'loading' || draft === null) {
    return <div className="screen loading-screen"><Loader2 className="spin" size={24} /><span>{t("正在读取设置")}</span></div>
  }

  // 草稿由 App 持有（设置区内所有页面共享），这里只读写它，不再自己保存一份页内状态。
  const settings = draft.settings
  const overlayBallDraft = draft.overlayBallEnabled
  const selectedProvider = draft.selectedProvider
  const credentialDrafts = draft.credentials
  const clearedProviders = draft.clearedProviders
  const customCredentials = draft.customCredentials
  const clearedCustomProviders = draft.clearedCustomProviders

  /**
   * 草稿的写入入口：所有字段改动都经过 [onDraftChange]，由 App 统一标记「未保存」。
   * 命名与原来的 useState setter 保持一致，调用点无需关心草稿存在哪里。
   */
  const setDraft = (next: RuntimeSettings): void => onDraftChange(current => ({ ...current, settings: next }))
  const setOverlayBallDraft = (next: boolean): void => onDraftChange(current => ({ ...current, overlayBallEnabled: next }))
  const setSelectedProvider = (next: ModelProviderId | 'custom'): void =>
    // 只是切换正在查看的供应商，没有改动任何字段值：不算「未保存的输入」。
    onDraftChange(current => ({ ...current, selectedProvider: next }), false)
  const setCredentialDrafts = (update: (current: ProviderApiKeys) => ProviderApiKeys): void =>
    onDraftChange(current => ({ ...current, credentials: update(current.credentials) }))
  const setClearedProviders = (update: (current: ModelProviderId[]) => ModelProviderId[]): void =>
    onDraftChange(current => ({ ...current, clearedProviders: update(current.clearedProviders) }))
  const setCustomCredentials = (next: Record<string, string>): void =>
    onDraftChange(current => ({ ...current, customCredentials: next }))
  const setClearedCustomProviders = (next: string[]): void =>
    onDraftChange(current => ({ ...current, clearedCustomProviders: next }))

  // 只为用户主动修改的开关保留草稿；原生菜单关闭悬浮球时，不影响页内其他未保存内容。
  const overlayBallEnabled = overlayBallDraft
    ?? (overlayBallReadFailed ? settings.overlayBallEnabled : overlayBall?.enabled)
    ?? settings.overlayBallEnabled
    ?? false
  const overlayPermissionKnown = overlayBall !== null && !overlayBallReadFailed

  const shizukuLabel = !shizuku.installed
    ? t("未安装")
    : !shizuku.running
      ? t("未运行") + (shizuku.version ? '（v' + shizuku.version + '）' : '')
      : shizuku.permission === 'granted'
        ? shizuku.connected ? t("已连接") : t("已授权")
        : shizuku.permission === 'denied'
          ? t("已拒绝")
          : t("待授权")
  const selectedProviderOption = MODEL_PROVIDERS.find(provider => provider.id === selectedProvider) ?? MODEL_PROVIDERS[0]
  const keepAliveRecordedAt = formatRecordedAt(keepAlive.lastUpdatedAtMillis)
  const lastStopLabel = t(LAST_STOP_LABELS[lastStop])
  const lastRunLabel = keepAlive.lastPhase === undefined
    ? t("从未记录")
    : keepAliveRecordedAt === ''
      ? t(PHASE_META[keepAlive.lastPhase].label)
      : `${t(PHASE_META[keepAlive.lastPhase].label)} · ${keepAliveRecordedAt}`
  const selectedProviderConfigured = selectedProvider !== 'custom' && (
    settings.configuredModelProviders.includes(selectedProvider) || credentialDrafts[selectedProvider] !== undefined
  ) && !clearedProviders.includes(selectedProvider)
  const saveDraft = (): void => {
    // 悬浮球是原生侧可被菜单直接修改的独立偏好。只有用户在本页实际拨动开关时，
    // 才把它作为更新字段发送；否则省略该字段，让原生侧保留菜单刚写入的值。
    const settingsWithoutOverlayBall = { ...settings }
    delete settingsWithoutOverlayBall.overlayBallEnabled
    onSave({
      ...settingsWithoutOverlayBall,
      ...(overlayBallDraft === null ? {} : { overlayBallEnabled }),
      ...(Object.keys(credentialDrafts).length === 0 ? {} : { providerApiKeys: credentialDrafts }),
      ...(clearedProviders.length === 0 ? {} : { clearProviderApiKeys: clearedProviders }),
      ...(Object.keys(customCredentials).length === 0 ? {} : { customProviderApiKeys: customCredentials }),
      ...(clearedCustomProviders.length === 0 ? {} : { clearCustomProviderApiKeys: clearedCustomProviders }),
    })
  }

  return (
    <div className="screen settings-screen">
      <div className="screen-heading management-heading">
        <div>
          <p className="eyebrow">{t("应用管理")}</p>
          <h1>{t(SETTINGS_PAGE_META[page].title)}</h1>
        </div>
        <button className="icon-button" type="button" aria-label={t("返回设置")} title={t("返回设置")} onClick={onBack}>
          <ArrowLeft size={19} />
        </button>
      </div>

      <form className="settings-form" onSubmit={event => { event.preventDefault(); saveDraft() }}>
        {page === 'models' && (
        <section className="settings-section" aria-labelledby="model-settings">
          <div className="section-title">
            <span className="section-icon"><Bot size={19} /></span>
            <div><h2 id="model-settings">{t("模型供应商")}</h2><p>{t("密钥在本机加密保存，只在本机启动 Harness 时使用，页面不会回显")}</p></div>
          </div>
          {!hasConfiguredModelCredential(settings) && (
            /*
             * 应用看不到 Harness 自己保存的凭据（网页端模型页写入 ~/.dsh/.credentials.yaml）。
             * 因此这里给出显式放行入口，而不是把这类用户永久挡在门外。
             */
            <div className="inline-alert warning" role="alert">
              <AlertTriangle size={19} />
              <div>
                <strong>{t("还没有可用的模型凭据")}</strong>
                <span>{t("没有密钥时每一轮对话都会失败，因此应用不会打开 Harness；保存一次 API Key 即可。若你已在 Harness 页面内配置过密钥，可以直接打开。")}</span>
              </div>
              <button className="button button-secondary" type="button" disabled={busy !== null} onClick={onLaunchConfirmed}>
                {busy === 'launch' ? <Loader2 className="spin" size={18} /> : <Rocket size={18} />}{t("我已在 Harness 内配置过，仍要打开")}</button>
            </div>
          )}
          <label className="field">
            <span>{t("供应商")}</span>
            <select
              value={selectedProvider}
              onChange={event => setSelectedProvider(event.target.value as ModelProviderId | 'custom')}
            >
              {MODEL_PROVIDERS.map(provider => {
                const configured = (settings.configuredModelProviders.includes(provider.id) || credentialDrafts[provider.id] !== undefined)
                  && !clearedProviders.includes(provider.id)
                return <option key={provider.id} value={provider.id}>{provider.label}{configured ? t("（已配置）") : ''}</option>
              })}
              <option value="custom">{t('自定义')}</option>
            </select>
          </label>
          {selectedProvider !== 'custom' && <><label className="field">
            <span>{selectedProviderOption.label} API Key · {selectedProviderOption.environmentVariable}</span>
            <input
              type="password"
              autoComplete="new-password"
              spellCheck={false}
              maxLength={200}
              placeholder={selectedProviderConfigured ? t("已配置，留空保持不变") : t("输入 API Key")}
              value={credentialDrafts[selectedProvider] ?? ''}
              onChange={event => {
                const value = event.target.value
                setCredentialDrafts(current => {
                  const next = { ...current }
                  if (value === '') delete next[selectedProvider]
                  else next[selectedProvider] = value
                  return next
                })
                if (value !== '') setClearedProviders(current => current.filter(provider => provider !== selectedProvider))
              }}
            />
          </label>
          {(settings.configuredModelProviders.includes(selectedProvider) || clearedProviders.includes(selectedProvider)) && (
            <div className="credential-actions">
              <button
                className="button button-danger-quiet compact-button"
                type="button"
                onClick={() => {
                  setCredentialDrafts(current => {
                    const next = { ...current }
                    delete next[selectedProvider]
                    return next
                  })
                  setClearedProviders(current => current.includes(selectedProvider)
                    ? current.filter(provider => provider !== selectedProvider)
                    : [...current, selectedProvider])
                }}
              >
                {clearedProviders.includes(selectedProvider) ? <RotateCcw size={16} /> : <Trash2 size={16} />}
                {clearedProviders.includes(selectedProvider) ? t("撤销清除") : t("清除密钥")}
              </button>
            </div>
          )}</>}
          {selectedProvider === 'custom' && <CustomProviders
            providers={settings.customModelProviders ?? []}
            configured={settings.configuredCustomModelProviders ?? []}
            credentials={customCredentials}
            cleared={clearedCustomProviders}
            onChange={providers => setDraft({ ...settings, customModelProviders: providers })}
            onCredentials={setCustomCredentials}
            onClear={setClearedCustomProviders}
          />}
          <label className="toggle-row">
            <span><strong>{t("打开应用时自动启动 Harness")}</strong><small>{t("关闭后需手动点「打开 Harness」启动")}</small></span>
            <input
              type="checkbox"
              role="switch"
              checked={settings.autoLaunch}
              onChange={event => setDraft({ ...settings, autoLaunch: event.target.checked })}
            />
          </label>
        </section>
        )}

        {page === 'runtime' && (
        <section className="settings-section" aria-labelledby="download-settings">
          <div className="section-title">
            <span className="section-icon"><CloudDownload size={19} /></span>
            <div><h2 id="download-settings">{t("运行时来源")}</h2><p>{t("两项留空表示使用 APK 内置的运行时（官方构建即内置，可离线安装）；填写清单地址与 SHA-256 则改为从该来源下载，两项必须成对。")}</p></div>
          </div>
          <label className="field">
            <span>{t("运行时清单地址")}</span>
            <input
              type="url"
              inputMode="url"
              autoCapitalize="none"
              autoComplete="off"
              maxLength={2048}
              value={settings.manifestUrl}
              onChange={event => setDraft({ ...settings, manifestUrl: event.target.value })}
            />
          </label>
          <label className="field">
            <span>{t("运行时清单 SHA-256")}</span>
            <input
              className="mono-input"
              type="text"
              inputMode="text"
              autoCapitalize="none"
              autoComplete="off"
              spellCheck={false}
              minLength={64}
              maxLength={64}
              pattern="[A-Fa-f0-9]{64}"
              value={settings.manifestSha256}
              onChange={event => setDraft({ ...settings, manifestSha256: event.target.value })}
            />
          </label>
        </section>
        )}

        {page === 'terminal' && (
        <section className="settings-section" aria-labelledby="terminal-settings">
          <div className="section-title">
            <span className="section-icon"><SquareTerminal size={19} /></span>
            <div><h2 id="terminal-settings">{t("终端")}</h2><p>{t("应用内终端的显示方式")}</p></div>
          </div>
          <label className="range-field">
            <span><strong>{t("字号")}</strong><small>{settings.terminalFontSize}px</small></span>
            <input
              type="range"
              min={11}
              max={24}
              step={1}
              value={settings.terminalFontSize}
              onChange={event => setDraft({ ...settings, terminalFontSize: Number(event.target.value) })}
            />
          </label>
          <label className="toggle-row">
            <span><strong>{t("保持屏幕常亮")}</strong><small>{t("应用管理与 Harness 对话界面均保持常亮")}</small></span>
            <input
              type="checkbox"
              role="switch"
              checked={settings.keepScreenAwake}
              onChange={event => setDraft({ ...settings, keepScreenAwake: event.target.checked })}
            />
          </label>
        </section>
        )}

        {page === 'runtime' && (
        <section className="settings-section" aria-labelledby="keep-alive-settings">
          <div className="section-title section-title-action">
            <span className="section-icon"><BellRing size={19} /></span>
            <div><h2 id="keep-alive-settings">{t("后台保持")}</h2><p>{t("通过系统前台服务提高运行时进程的存活优先级")}</p></div>
            <span className={`status-chip ${keepAlive.foregroundServiceActive ? 'success' : ''}`}>
              {keepAlive.foregroundServiceActive ? t("前台服务运行中") : t("前台服务未运行")}
            </span>
          </div>
          <label className="toggle-row">
            <span>
              <strong>{t("后台保持 Harness")}</strong>
              <small>{t("开启后需要常驻通知；锁屏、返回桌面或划掉最近任务后仍可能继续运行")}</small>
            </span>
            <input
              type="checkbox"
              role="switch"
              checked={settings.keepRuntimeInBackground ?? false}
              onChange={event => {
                setDraft({ ...settings, keepRuntimeInBackground: event.target.checked })
                // 开启时立即申请通知权限：前台服务在 Android 13+ 需要它才能显示常驻通知。
                if (event.target.checked) onRequestNotificationPermission()
              }}
            />
          </label>
          {/* 悬浮球与「后台保持」是两个互相独立的开关，但各自都会提高本应用进程被系统回收的优先级：
              开悬浮球不会去拉起运行时的保活服务；悬浮球自身由独立的前台服务承载，
              与「后台保持」一样只提高优先级，不保证进程不被系统结束。 */}
          <label className="toggle-row">
            <span>
              <strong>{t("悬浮球")}</strong>
              <small>
                {!overlayPermissionKnown
                  ? t("暂时无法确认悬浮窗权限")
                  : overlayBall.canDrawOverlays
                  ? t("在其他应用上层显示悬浮球，点按可快速回到对话")
                  : t("需要「显示在其他应用上层」权限才能使用")}
              </small>
              {overlayBallEnabled && overlayPermissionKnown && !overlayBall.canDrawOverlays ? (
                <small className="status-text-error">{t("系统权限已关闭")}</small>
              ) : null}
            </span>
            {/* 只禁用「开启」方向：没有权限且当前是关的就禁用，防止误开后无声失败；
                已经开着时仍允许关闭，否则权限被系统撤销后用户无法在应用内关掉这个功能。 */}
            <input
              type="checkbox"
              role="switch"
              checked={overlayBallEnabled}
              disabled={(!overlayPermissionKnown || !overlayBall.canDrawOverlays) && !overlayBallEnabled}
              onChange={event => setOverlayBallDraft(event.target.checked)}
            />
          </label>
          {overlayBallReadFailed && <p className="status-text-error" role="alert">{t("无法读取悬浮球状态，正在重试")}</p>}
          {overlayPermissionKnown && !overlayBall.canDrawOverlays && (
            <button className="button button-secondary" type="button" onClick={onOpenOverlaySettings} disabled={busy !== null}>
              <ExternalLink size={18} />{t("前往系统设置开启")}</button>
          )}
          <div className="settings-status-list">
            <div className="settings-status-row">
              <span>{t("前台服务")}</span>
              <strong>{keepAlive.foregroundServiceActive ? t("运行中") : t("未运行")}</strong>
            </div>
            <div className="settings-status-row">
              <span>{t("通知权限")}</span>
              <strong>{t(NOTIFICATION_PERMISSION_LABELS[keepAlive.notificationPermission])}</strong>
            </div>
            <div className="settings-status-row">
              <span>{t("最近状态")}</span>
              <strong>{lastRunLabel}</strong>
            </div>
            {/* 运行环境会被 dsh 自己在空闲后停掉：事后也要能看出这次停止是谁发起的。 */}
            <div className="settings-status-row">
              <span>{t("上次停止")}</span>
              <strong>{lastStopLabel}</strong>
            </div>
            <div className="settings-status-row">
              <span>{t("设备 Shell（连接恢复）")}</span>
              <strong>{keepAlive.deviceShellReady ? t("可用") : t("不可用")}</strong>
            </div>
          </div>
          <p className="settings-note">
            {t("前台服务只提升本应用进程的优先级：Android 与厂商的电池、内存和后台策略仍可能结束进程，无法保证绝对不被停止；进程被系统强制停止后，旧的 Harness 会话与临时凭据不可恢复。")}
          </p>
          <div className="settings-inline-actions">
            {keepAlive.notificationPermission === 'prompt' && (
              <button className="button button-secondary" type="button" onClick={onRequestNotificationPermission} disabled={busy !== null}>
                <BellRing size={18} />{t("申请通知权限")}</button>
            )}
            {keepAlive.reconnectRequired && (
              <button className="button button-secondary" type="button" onClick={onLaunch} disabled={busy !== null || !runtimeInstalled(runtime)}>
                {busy === 'launch' ? <Loader2 className="spin" size={18} /> : <RefreshCw size={18} />}{t("重新连接")}</button>
            )}
          </div>
          {keepAlive.reconnectRequired && (
            <div className="inline-alert warning" role="alert">
              <AlertTriangle size={19} />
              <div>
                <strong>{t("需要重新连接")}</strong>
                <span>{t("应用进程已被系统回收，旧的 Harness 会话与临时凭据无法恢复；请重新连接以启动新的本机会话。")}</span>
              </div>
            </div>
          )}
        </section>
        )}

        {page === 'runtime' && (
        <RuntimeSelfCheckPanel runSelfCheck={runSelfCheck} runtime={runtime} />
        )}

        {page === 'shizuku' && (
        <section className="settings-section" aria-labelledby="shizuku-settings">
          <div className="section-title section-title-action">
            <span className="section-icon"><Smartphone size={19} /></span>
            <div><h2 id="shizuku-settings">Shizuku</h2><p>{t("设备 Shell ·")}{shizukuLabel}</p></div>
            <span className={`status-chip ${shizuku.permission === 'granted' ? 'success' : ''}`}>{shizukuLabel}</span>
          </div>
          <div className="settings-inline-actions">
            {shizuku.installed && shizuku.running && shizuku.permission !== 'granted' && (
              <button className="button button-secondary" type="button" onClick={onAuthorize} disabled={busy !== null}>
                <ShieldCheck size={18} />{t("请求授权")}</button>
            )}
            {(!shizuku.installed || !shizuku.running) && (
              <button className="button button-secondary" type="button" onClick={onOpenShizuku} disabled={busy !== null}>
                <ExternalLink size={18} />{t("打开 Shizuku")}</button>
            )}
            {shizuku.permission === 'granted' && !shizuku.connected && (
              <button className="button button-secondary" type="button" onClick={onConnect} disabled={busy !== null}>
                {busy === 'shizuku-connect' ? <Loader2 className="spin" size={18} /> : <RefreshCw size={18} />}{t("连接 Shizuku")}</button>
            )}
            {shizuku.permission === 'granted' && shizuku.connected && (
              <div className="permission-granted"><CheckCircle2 size={18} />{t("连接可用")}</div>
            )}
          </div>
          <p className="settings-note">
            {t("Shizuku 只用于设备 Shell 的授权与连接状态检测、连接恢复辅助和健康检查；它不是 root，也不提供永久保活能力。未安装、未授权或断开时，设备 Shell 功能自动降级，不影响 Ubuntu 终端与 Harness。")}
          </p>
        </section>
        )}

        {page === 'diagnostics' && (
        <>
        <section className="settings-section" aria-labelledby="diagnostic-settings">
          <div className="section-title section-title-action">
            <span className="section-icon"><ScrollText size={19} /></span>
            <div><h2 id="diagnostic-settings">{t("诊断日志")}</h2><p>{t("只记录应用内部状态码与计数，用于排查问题")}</p></div>
            <span className={`status-chip ${diagnostic.enabled ? 'success' : ''}`}>
              {diagnostic.enabled ? t("收集中") : t("未收集")}
            </span>
          </div>
          <label className="toggle-row">
            <span>
              <strong>{t("收集诊断日志")}</strong>
              <small>{t("默认关闭；开启后记录运行时阶段、启动结果与前台服务状态")}</small>
            </span>
            <input
              type="checkbox"
              role="switch"
              checked={diagnostic.enabled}
              disabled={busy !== null}
              onChange={event => onDiagnosticSettings(event.target.checked, diagnostic.retentionDays)}
            />
          </label>
          <label className="range-field">
            <span><strong>{t("保留天数")}</strong><small>{diagnostic.retentionDays} {t("天")}</small></span>
            <input
              type="range"
              min={DIAGNOSTIC_RETENTION_MIN}
              max={DIAGNOSTIC_RETENTION_MAX}
              step={1}
              value={diagnostic.retentionDays}
              disabled={busy !== null}
              onChange={event => onDiagnosticSettings(diagnostic.enabled, Number(event.target.value))}
            />
          </label>
          <div className="settings-status-list">
            <div className="settings-status-row">
              <span>{t("日志文件")}</span>
              <strong>{t("{0} 个文件 · {1}", diagnostic.fileCount, formatBytes(diagnostic.totalBytes))}</strong>
            </div>
            <div className="settings-status-row">
              <span>{t("最近记录")}</span>
              <strong>{formatRecordedAt(diagnostic.lastEntryAtMillis) || t("从未记录")}</strong>
            </div>
          </div>
          <p className="settings-note">
            {t("诊断日志只包含应用内部的事件名、状态码、布尔值与计数：不含 URL、模型密钥、Harness 临时密码、设备桥令牌、终端内容或文件路径。日志保存在应用私有目录且不参与备份，到期自动删除。")}
          </p>
          <div className="settings-inline-actions">
            <button className="button button-secondary" type="button" onClick={onShareDiagnostic} disabled={busy !== null || diagnostic.fileCount === 0}>
              {busy === 'diagnostic-share' ? <Loader2 className="spin" size={18} /> : <Share2 size={18} />}{t("导出并分享")}</button>
            <button className="button button-danger-quiet" type="button" onClick={onClearDiagnostic} disabled={busy !== null || diagnostic.fileCount === 0}>
              {busy === 'diagnostic-clear' ? <Loader2 className="spin" size={18} /> : <Trash2 size={18} />}{t("清空日志")}</button>
          </div>
        </section>
        {/* 诊断日志正文可直接在应用内查看；运行日志是访客输出尾部，只在界面展示、不进导出。 */}
        <DiagnosticLogPanel loadDiagnosticLog={loadDiagnosticLog} />
        <HarnessLogPanel loadHarnessLog={loadHarnessLog} />
        </>
        )}

        {(page === 'models' || page === 'runtime' || page === 'terminal') && (
        <button className="button button-primary save-button" type="submit" disabled={busy !== null}>
          {busy === 'save-settings' ? <Loader2 className="spin" size={18} /> : <Save size={18} />}
          {t("保存设置")}</button>
        )}
      </form>
    </div>
  )
}

interface ResetDialogProps {
  busy: boolean
  onCancel: () => void
  onConfirm: () => void
}

function ResetDialog({ busy, onCancel, onConfirm }: ResetDialogProps) {
  const [confirmation, setConfirmation] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const confirmed = confirmation.trim().toUpperCase() === RESET_CONFIRMATION

  const submitReset = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (busy || !confirmed) return
    inputRef.current?.blur()
    onConfirm()
  }

  return (
    <div className="dialog-backdrop" role="presentation" onPointerDown={event => { if (event.target === event.currentTarget && !busy) onCancel() }}>
      <form className="dialog" role="dialog" aria-modal="true" aria-labelledby="reset-title" onSubmit={submitReset}>
        <button className="dialog-close" type="button" aria-label={t("关闭")} onClick={onCancel} disabled={busy}><X size={19} /></button>
        <span className="dialog-danger-icon"><Trash2 size={23} /></span>
        <h2 id="reset-title">{t("重置运行环境")}</h2>
        <p>{t("已安装的 Ubuntu 运行环境会被清除，其中的用户数据（会话、密钥、插件等）一并删除，终端与 Harness 会话将立即结束。")}</p>
        <label className="field confirmation-field">
          <span>{t("输入 RESET_RUNTIME 确认")}</span>
          <input
            ref={inputRef}
            type="text"
            autoComplete="off"
            autoCapitalize="characters"
            enterKeyHint="done"
            spellCheck={false}
            maxLength={32}
            value={confirmation}
            onChange={event => setConfirmation(event.target.value)}
          />
        </label>
        <div className="dialog-actions">
          <button className="button button-secondary" type="button" onClick={onCancel} disabled={busy}>{t("取消")}</button>
          <button className="button button-danger" type="submit" disabled={busy || !confirmed}>
            {busy ? <Loader2 className="spin" size={18} /> : <RotateCcw size={18} />}
            {busy ? t("正在重置") : t("确认重置")}
          </button>
        </div>
      </form>
    </div>
  )
}

interface UpdateDialogProps {
  busy: boolean
  onCancel: () => void
  onConfirm: () => void
}

function UpdateDialog({ busy, onCancel, onConfirm }: UpdateDialogProps) {
  return (
    <div className="dialog-backdrop" role="presentation" onPointerDown={event => { if (event.target === event.currentTarget && !busy) onCancel() }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="update-title">
        <button className="dialog-close" type="button" aria-label={t("关闭")} onClick={onCancel} disabled={busy}><X size={19} /></button>
        <span className="dialog-danger-icon"><RefreshCw size={23} /></span>
        <h2 id="update-title">{t("更新 Ubuntu 运行环境")}</h2>
        <p>{t("当前 APK 内置了新版运行环境。继续后会替换 Ubuntu 运行时的系统目录：用 apt 等装进系统的软件与其它本地修改会丢失；会话、模型密钥、Harness 设置、附件、技能和你安装的插件会保留，应用设置也不受影响。")}</p>
        <div className="dialog-actions">
          <button className="button button-secondary" type="button" onClick={onCancel} disabled={busy}>{t("暂不更新")}</button>
          <button className="button button-danger" type="button" onClick={onConfirm} disabled={busy}>
            {busy ? <Loader2 className="spin" size={18} /> : <RefreshCw size={18} />}
            {busy ? t("正在更新") : t("确认更新")}
          </button>
        </div>
      </div>
    </div>
  )
}

export function App() {
  const language = useLanguage()
  useEffect(() => { document.documentElement.lang = language ?? 'zh-CN' }, [language])
  const [activeView, setActiveViewState] = useState<AppView>(ROOT_VIEW)
  /**
   * 当前视图的同步真值：导航与历史回退都先改它再改状态。
   * 同一视图重复导航（例如启动成功后再次切到设置）由此判重，不会往历史里堆冗余记录。
   */
  const activeViewRef = useRef<AppView>(activeView)
  /**
   * 当前这条历史记录被压入时所在的视图，也就是真实的上一级。
   * 未知时为 null：历史回退/前进之后无法再知道相邻记录是谁。
   */
  const previousViewRef = useRef<AppView | null>(null)
  const [runtime, setRuntime] = useState<RuntimeState>(EMPTY_RUNTIME)
  const [settings, setSettings] = useState<RuntimeSettings | null>(null)
  const [settingsReadStatus, setSettingsReadStatus] = useState<'idle' | 'loading' | 'failed'>('idle')
  const settingsReadRevision = useRef(0)
  /**
   * 设置区未保存的草稿。
   *
   * 它必须由 App 持有：设置首页与五个二级页是**不同的组件**，页内状态在切页时随卸载消失
   * —— 用户刚输入的 API Key 只要切一次页就再也找不回来。草稿放在设置区之上，
   * 区内所有页面共享同一份未保存内容。
   */
  const [settingsDraft, setSettingsDraft] = useState<SettingsDraft | null>(null)
  /**
   * 草稿里是否有用户尚未保存的输入。
   *
   * 为真时后台刷新（进入设置页时的重读、以及任何迟到的读取结果）**不覆盖**草稿：
   * 刷新是为了同步原生侧改动，绝不能把用户正在输入的内容（典型是 API Key）清掉。
   * 只在「保存成功」与「离开设置区」两处复位。
   */
  const settingsDraftDirty = useRef(false)
  const [shizuku, setShizuku] = useState<ShizukuState>(EMPTY_SHIZUKU)
  const [keepAlive, setKeepAlive] = useState<KeepAliveState>(EMPTY_KEEP_ALIVE)
  // 查询失败与「没有权限」分开记录，保留最近一次快照供开关关闭操作使用。
  const [overlayBall, setOverlayBall] = useState<OverlayBallState | null>(null)
  const [overlayBallReadFailed, setOverlayBallReadFailed] = useState(false)
  const overlayBallReadRevision = useRef(0)
  const [diagnostic, setDiagnostic] = useState<DiagnosticLogState>(EMPTY_DIAGNOSTIC)
  const [booting, setBooting] = useState(true)
  const [onboardingOpen, setOnboardingOpen] = useState(() => {
    try { return window.localStorage.getItem(ONBOARDING_STORAGE_KEY) === null } catch { return true }
  })
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [resetOpen, setResetOpen] = useState(false)
  const [updateOpen, setUpdateOpen] = useState(false)
  const noticeId = useRef(0)
  const busyRef = useRef<string | null>(null)
  const autoLaunchAttempted = useRef(false)
  /**
   * 运行阶段与「用户是否点过停止」的记录。
   *
   * 运行环境会被 dsh 自己在空闲后停掉（profile 里 idleStopMinutes: 15），随后再被自动拉起，
   * 端口与临时凭据都会变。没有这段记录时，「自行停止」与「崩溃」在界面上完全一样。
   */
  const wasRunning = useRef(false)
  const stopRequested = useRef(false)
  const [lastStop, setLastStop] = useState<LastStopReason>('none')

  const notify = useCallback((message: string, tone: NoticeTone = 'info') => {
    noticeId.current += 1
    setNotice({ id: noticeId.current, message, tone })
  }, [])

  const terminalError = useCallback((message: string) => notify(message, 'error'), [notify])

  /**
   * 写入设置草稿。[dirty] 为假表示这次只改「正在看什么」（例如切换供应商下拉框），
   * 草稿里的值没有变化，不应妨碍后台刷新同步。
   */
  const updateSettingsDraft = useCallback((update: (current: SettingsDraft) => SettingsDraft, dirty = true) => {
    if (dirty) settingsDraftDirty.current = true
    setSettingsDraft(current => (current === null ? current : update(current)))
  }, [])

  /**
   * 用落盘设置重建草稿并解除「未保存」标记。
   *
   * 只有两个调用点：读到最新设置（且用户没有未保存的输入时）与保存成功之后。
   * 重建时凭据输入一律清空：密钥落盘后不回显，草稿里也就不该继续留着它。
   */
  const resyncSettingsDraft = useCallback((next: RuntimeSettings) => {
    settingsDraftDirty.current = false
    setSettingsDraft(current => draftFromSettings(next, current))
  }, [])

  const readOverlayBall = useCallback(async () => {
    const revision = ++overlayBallReadRevision.current
    try {
      const next = await runtimeBridge.getOverlayBallState()
      if (revision === overlayBallReadRevision.current) {
        setOverlayBall(next)
        setOverlayBallReadFailed(false)
      }
      return next
    } catch (error) {
      if (revision === overlayBallReadRevision.current) setOverlayBallReadFailed(true)
      throw error
    }
  }, [])

  /**
   * 视图导航入口（取代直接调用裸的 setState）：只有视图真的变化才写历史并重渲染。
   */
  const setActiveView = useCallback((next: AppView) => {
    const current = activeViewRef.current
    if (current === next) return
    activeViewRef.current = next
    previousViewRef.current = current
    setActiveViewState(next)
    pushViewEntry(next)
  }, [])

  /**
   * 屏幕内返回按钮：回到上一级视图。
   *
   * 上一级正好就是目标视图时回退历史，而不是再压一条新记录 —— 否则历史会变成
   * 「主视图 → 设置 → 二级页 → 设置」，用户此后按系统返回键会被重新送回二级页。
   * 上一级不是目标视图时（例如从终端跳到设置首页）保持普通导航语义：压入目标视图。
   * 视图切换由回退后的 popstate 完成，与系统返回键走同一条路径。
   */
  const backToView = useCallback((target: AppView) => {
    if (previousViewRef.current !== target) {
      setActiveView(target)
      return
    }
    previousViewRef.current = null
    window.history.back()
  }, [setActiveView])

  /**
   * 历史回退与前进（系统返回键/返回手势、浏览器后退/前进按钮）只改变历史位置，
   * 这里把视图同步到历史所在的那条记录上。
   *
   * 刻意不在这里写新记录：回退过程中 pushState 会截断前进方向的历史并不断堆积记录，
   * 返回键就再也回不到真正的上一级。
   */
  useEffect(() => {
    // 应用始终从主视图启动（启动 Harness 的流程依赖主视图），首屏就把栈底记录校正成主视图：
    // WebView 恢复历史时地址可能残留上一次的片段，这里顺手清掉，避免视图与地址不一致。
    replaceViewEntry(activeViewRef.current)
    const handlePopState = (event: PopStateEvent): void => {
      const restored = viewFromHistoryState(event.state) ?? viewFromHash(window.location.hash) ?? ROOT_VIEW
      activeViewRef.current = restored
      // 回退/前进之后相邻记录未知，不能再据此把屏幕内返回当成回退。
      previousViewRef.current = null
      setActiveViewState(restored)
      // 记录无法识别时把地址拉回视图，避免出现「界面在主视图、地址停在二级页」。
      replaceViewEntry(restored)
    }
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [])

  /**
   * 设置草稿的作用范围就是「设置区」（设置首页与五个二级页）。
   *
   * 离开设置区立即丢弃草稿：未保存的输入不再保留，内存里的 API Key 也随之消失。
   * 区内切页不清空 —— 那正是用户「输入 API Key 后做别的操作」的常见路径。
   *
   * 历史回退/前进可能不经 [openSettings] 直接把二级页恢复出来，这里用已有的设置快照
   * 补建一份草稿，避免页面一直停在「正在读取设置」。
   */
  useEffect(() => {
    if (!isSettingsView(activeView)) {
      if (settingsDraft !== null) setSettingsDraft(null)
      settingsDraftDirty.current = false
      return
    }
    if (settingsDraft === null && settings !== null) setSettingsDraft(draftFromSettings(settings))
  }, [activeView, settings, settingsDraft])

  useEffect(() => {
    if (notice === null) return
    const timer = window.setTimeout(() => setNotice(current => current?.id === notice.id ? null : current), 3600)
    return () => window.clearTimeout(timer)
  }, [notice])

  /**
   * 记录一次**实时**运行阶段变化：运行环境自行停止时给一次性提示。
   *
   * 为什么需要它：运行环境会被 dsh 自己在空闲后停掉（profile 里 idleStopMinutes: 15），
   * 随后又被自动拉起——端口与临时凭据都会变，旧会话接不回去。没有提示时，这种停止与
   * 崩溃在界面上完全一样。用户自己点过停止时不再提示（他知道自己做了什么），
   * 只在「上次停止」一行留下记录。
   *
   * 为什么只处理原生侧主动推送的阶段变化：状态快照（getState）可能落后于实际状态，
   * 用它判断「刚才还在运行、现在停了」会误报；推送到前端的阶段变化才是真正的实时信号。
   * `stopping` 是过渡态，等落到最终阶段再判定；`error` 与安装阶段不属于「自行停止」，
   * 由既有的错误与进度界面负责，这里只把「运行中」的标记清掉。
   */
  const noteLivePhase = useCallback((phase: RuntimePhase) => {
    if (phase === 'running') {
      wasRunning.current = true
      // 新一次运行开始时清掉上一次的停止意图，否则它会吃掉下一次自行停止的提示。
      stopRequested.current = false
      return
    }
    if (phase === 'stopping') return
    if (!wasRunning.current) return
    wasRunning.current = false
    const userRequested = stopRequested.current
    stopRequested.current = false
    if (phase !== 'ready') return
    setLastStop(userRequested ? 'user' : 'self')
    if (userRequested) return
    notify(t("运行环境已自行停止（可能是空闲自动停止）；点「打开 Harness」会重新启动，旧会话需要重新连接。"), 'info')
  }, [notify])

  /** 快照读取（挂载、启动、停止后的 getState）同样要维护「上一次是否在运行」，但它不触发提示。 */
  useEffect(() => {
    if (runtime.phase !== 'running') return
    wasRunning.current = true
    stopRequested.current = false
  }, [runtime.phase])

  useEffect(() => {
    let cancelled = false
    let removeProgress: (() => Promise<void>) | undefined
    let progressRevision = 0
    let latestProgress: RuntimeProgress | undefined

    const progressHandlePromise = runtimeBridge.addRuntimeProgressListener(progress => {
      if (cancelled) return
      progressRevision += 1
      latestProgress = progress
      // 先判读这次阶段变化，再用它更新状态：自行停止的判定依赖更新前的「运行中」标记。
      noteLivePhase(progress.phase)
      setRuntime(current => mergeRuntimeProgress(current, progress))
    })
      .then(handle => {
        if (cancelled) return handle.remove()
        removeProgress = handle.remove
      })
      .catch(error => {
        if (!cancelled) notify(errorMessage(error), 'error')
      })

    void (async () => {
      const revisionBeforeSnapshot = progressRevision
      try {
        const nextRuntime = await runtimeBridge.getState()
        if (cancelled) return
        const initialRuntime = progressRevision === revisionBeforeSnapshot || latestProgress === undefined
          ? nextRuntime
          : mergeRuntimeProgress(nextRuntime, latestProgress)
        setRuntime(initialRuntime)
      } catch (error) {
        if (!cancelled) {
          setRuntime(current => ({ ...current, phase: 'error', errorCode: 'STATE_UNAVAILABLE' }))
          notify(errorMessage(error), 'error')
        }
      } finally {
        if (!cancelled) setBooting(false)
      }
    })()

    const initialSettingsRevision = settingsReadRevision.current
    void runtimeBridge.getSettings()
      .then(next => { if (!cancelled && initialSettingsRevision === settingsReadRevision.current) setSettings(next) })
      .catch(error => { if (!cancelled) notify(errorMessage(error), 'error') })

    void runtimeBridge.getShizukuState()
      .then(next => { if (!cancelled) setShizuku(next) })
      .catch(() => {
        // Shizuku is optional and must never block the Harness conversation.
      })

    void runtimeBridge.getKeepAliveState()
      .then(next => { if (!cancelled) setKeepAlive(next) })
      .catch(() => {
        // 后台保持状态读取失败不阻塞界面：保持上一次的已知状态。
      })

    void readOverlayBall()
      .catch(() => {
        // 悬浮球属于可选能力，错误由设置页单独显示。
      })

    void runtimeBridge.getDiagnosticLogState()
      .then(next => { if (!cancelled) setDiagnostic(next) })
      .catch(() => {
        // 诊断日志状态读取失败不阻塞界面。
      })

    return () => {
      cancelled = true
      if (removeProgress !== undefined) void removeProgress()
      void progressHandlePromise
    }
  }, [noteLivePhase, notify, readOverlayBall])

  useEffect(() => {
    let cancelled = false

    /**
     * Harness 的 Models 页面会直接更新访客内的凭据文件。MainActivity 恢复前台时重读设置，
     * 让原生侧识别到的「已配置」状态及时进入管理界面；用户正在编辑的草稿仍优先保留。
     */
    const refreshSettings = (): void => {
      if (document.visibilityState === 'hidden') return
      const revision = ++settingsReadRevision.current
      void runtimeBridge.getSettings()
        .then(next => {
          if (cancelled || revision !== settingsReadRevision.current) return
          setSettings(next)
          if (!settingsDraftDirty.current) resyncSettingsDraft(next)
        })
        .catch(() => {
          // 前台恢复属于后台同步；读取失败时保留最近一次设置快照，不弹重复错误。
        })
    }

    const refreshShizuku = (reportError = true): void => {
      if (document.visibilityState === 'hidden') return
      void runtimeBridge.getShizukuState()
        .then(next => {
          if (!cancelled) setShizuku(next)
        })
        .catch(error => { if (!cancelled && reportError) notify(errorMessage(error), 'error') })
    }
    // 后台保持状态同时轮询：前台服务可能被系统结束，需要通过原生端才能得知。
    const refreshKeepAlive = (): void => {
      if (document.visibilityState === 'hidden') return
      void runtimeBridge.getKeepAliveState()
        .then(next => { if (!cancelled) setKeepAlive(next) })
        .catch(() => {
          // 轮询失败时保留上一次状态，不重复提示同一条错误。
        })
    }
    /**
     * 悬浮球状态同样在页面重新可见时重读：用户可能刚在系统设置里授予或撤销了
     * 「显示在其他应用上层」权限，也可能用悬浮球菜单在原生侧关掉了球。
     * 读取失败时保留上一次的已知值，不清零：清零会把「未知」显示成「已关闭」。
     */
    const refreshOverlayBall = (): void => {
      if (document.visibilityState === 'hidden') return
      void readOverlayBall()
        .catch(() => {
          // 设置页保留查询失败提示，不在轮询中重复弹通知。
        })
    }
    const handleVisibilityChange = (): void => {
      if (document.visibilityState === 'visible') {
        refreshSettings()
        refreshShizuku()
        refreshKeepAlive()
        refreshOverlayBall()
      }
    }
    const handleFocus = (): void => {
      refreshSettings()
      refreshShizuku()
      refreshKeepAlive()
      refreshOverlayBall()
    }

    window.addEventListener('focus', handleFocus)
    document.addEventListener('visibilitychange', handleVisibilityChange)
    const timer = window.setInterval(() => {
      refreshShizuku(false)
      refreshKeepAlive()
      refreshOverlayBall()
    }, 5000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
      window.removeEventListener('focus', handleFocus)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [notify, readOverlayBall, resyncSettingsDraft])

  const run = useCallback(async (id: string, operation: () => Promise<void>, success?: string) => {
    if (busyRef.current !== null) return
    busyRef.current = id
    setBusy(id)
    try {
      await operation()
      if (success !== undefined) notify(success, 'success')
    } catch (error) {
      notify(errorMessage(error), 'error')
    } finally {
      busyRef.current = null
      setBusy(null)
    }
  }, [notify])

  const installRuntime = useCallback(() => {
    void run('install', async () => {
      try {
        await runtimeBridge.install(settings === null ? undefined : {
          manifestUrl: settings.manifestUrl,
          manifestSha256: settings.manifestSha256,
        })
      } catch (error) {
        try {
          setRuntime(await runtimeBridge.getState())
        } catch {
          // Preserve the install failure; state refresh is best effort.
        }
        throw error
      }
      const next = await runtimeBridge.getState()
      setRuntime(next)
      autoLaunchAttempted.current = false
      setActiveView('conversation')
    }, t("运行环境已安装"))
  }, [run, setActiveView, settings])

  const requestRuntimeUpdate = useCallback(() => {
    if (busyRef.current === null) setUpdateOpen(true)
  }, [])

  const confirmRuntimeUpdate = useCallback(() => {
    void run('update-runtime', async () => {
      try {
        // Empty source fields explicitly select the APK-bundled, digest-verified runtime.
        await runtimeBridge.install({ manifestUrl: '', manifestSha256: '' })
      } catch (error) {
        try {
          setRuntime(await runtimeBridge.getState())
        } catch {
          // Preserve the update failure; state refresh is best effort.
        }
        throw error
      }
      const next = await runtimeBridge.getState()
      setRuntime(next)
      setUpdateOpen(false)
      autoLaunchAttempted.current = false
      setActiveView('conversation')
    }, t("运行环境已更新"))
  }, [run, setActiveView])

  /**
   * 真正的打开流程。`skipCredentialGate` 只允许由用户显式确认的入口传入
   * （引导页与「模型与密钥」页的「我已在 Harness 内配置过」按钮）：
   * **绝不要把事件对象或其它真值直接传进来**，否则等于默认绕过门禁。
   */
  const openHarness = useCallback((skipCredentialGate: boolean) => {
    if (busyRef.current !== null) return
    /*
     * 首次配置未完成时不打开 Harness：没有模型密钥时每轮对话都会因缺少凭据失败，
     * 打开只会看到一个用不了的界面。这里挡在唯一的打开入口上，覆盖引导页、
     * 首页按钮与「打开应用时自动启动」，并直接把用户送到「模型与密钥」页。
     * 设置尚未读取（settings 为 null）时放行，不做无法验证的判断；
     * 本应用看不到 Harness 自己保存的凭据，因此留出用户显式确认后放行的通道。
     */
    if (!skipCredentialGate && settings !== null && !hasConfiguredModelCredential(settings)) {
      autoLaunchAttempted.current = true
      notify(t("未检测到模型凭据：请先在「模型与密钥」保存一次 API Key 再打开 Harness"), 'error')
      setActiveView(SETTINGS_PAGE_META.models.view)
      return
    }
    if (runtime.updateAvailable) {
      autoLaunchAttempted.current = true
      requestRuntimeUpdate()
      return
    }
    autoLaunchAttempted.current = true
    busyRef.current = 'launch'
    setBusy('launch')
    void (async () => {
      try {
        let nextRuntime = runtime
        if (nextRuntime.phase !== 'running') {
          nextRuntime = await runtimeBridge.startHarness()
          setRuntime(nextRuntime)
        }
        // 启动成功后前台服务状态才可能变化（设置开启时）。
        setKeepAlive(await runtimeBridge.getKeepAliveState())
        // HarnessActivity overlays MainActivity. Keeping settings underneath makes
        // its native management button return to the intended management surface.
        setActiveView('settings')
        await runtimeBridge.openHarness()
      } catch (error) {
        setActiveView('conversation')
        try {
          setRuntime(await runtimeBridge.getState())
        } catch {
          // The launch error is the actionable failure; refresh is best effort.
        }
        notify(errorMessage(error), 'error')
      } finally {
        busyRef.current = null
        setBusy(null)
      }
    })()
  }, [notify, requestRuntimeUpdate, runtime, setActiveView, settings])

  /** 常规打开入口：没有本机模型凭据时会被拦下并跳转到「模型与密钥」。 */
  const launchHarness = useCallback(() => openHarness(false), [openHarness])

  /** 用户已确认「密钥在 Harness 里配置过」时的放行入口，只在显式按钮上使用。 */
  const launchHarnessConfirmed = useCallback(() => openHarness(true), [openHarness])

  useEffect(() => {
    if (language === null || onboardingOpen || booting || activeView !== 'conversation' || busy !== null || autoLaunchAttempted.current) return
    if (settings !== null && settings.autoLaunch === false) return
    if (runtime.updateAvailable) return
    if (runtime.phase === 'running' || (runtimeInstalled(runtime) && runtime.phase !== 'stopping')) {
      launchHarness()
    }
  }, [activeView, booting, busy, language, launchHarness, onboardingOpen, runtime, settings])

  const openSettings = useCallback((page: SettingsPage) => {
    // 最新设置读完之前不展示可编辑的旧草稿，避免迟到响应清空刚输入的内容。
    const revision = ++settingsReadRevision.current
    setSettingsReadStatus('loading')
    void runtimeBridge.getSettings()
      .then(next => {
        if (revision !== settingsReadRevision.current) return
        setSettings(next)
        // 只有用户没有未保存的输入时才用落盘值重建草稿：重读是为了同步原生侧改动
        // （例如悬浮球菜单在原生侧关了球），绝不能把用户正在输入的内容覆盖掉。
        if (!settingsDraftDirty.current) resyncSettingsDraft(next)
        setSettingsReadStatus('idle')
      })
      .catch(() => {
        if (revision === settingsReadRevision.current) setSettingsReadStatus('failed')
      })
    void readOverlayBall()
      .catch(() => {
        // 悬浮球查询失败不阻塞其他设置。
      })
    setActiveView(SETTINGS_PAGE_META[page].view)
  }, [readOverlayBall, resyncSettingsDraft, setActiveView])

  /** 更新诊断日志采集开关与保留天数；原生侧会再次夹取保留范围。 */
  const saveDiagnosticSettings = useCallback((enabled: boolean, retentionDays: number) => {
    void run('diagnostic-settings', async () => {
      setDiagnostic(await runtimeBridge.setDiagnosticLogSettings(enabled, retentionDays))
    })
  }, [run])

  /**
   * 导出诊断日志：导出后交给系统分享面板。
   * 用户取消分享不算失败，因此这里吞掉分享相关的拒绝，只在真正导出失败时提示。
   */
  const shareDiagnostic = useCallback(() => {
    void run('diagnostic-share', async () => {
      try {
        const result = await runtimeBridge.shareDiagnosticLog()
        setDiagnostic(result)
        notify(t("诊断日志已导出：{0}", result.fileName), 'success')
      } catch (error) {
        setDiagnostic(await runtimeBridge.getDiagnosticLogState())
        throw error
      }
    })
  }, [notify, run])

  const shareWorkspace = useCallback(() => {
    void run('workspace-share', () => runtimeBridge.shareRuntimeWorkspace(), t('已打开分享面板'))
  }, [notify, run])
  const [workspaceFiles, setWorkspaceFiles] = useState<string[]>([])
  const listWorkspaceFiles = useCallback(() => { void run('workspace-files', async () => setWorkspaceFiles(await runtimeBridge.listRuntimeWorkspaceFiles())) }, [run])
  const shareWorkspaceFile = useCallback((path: string) => { void run('workspace-file-share', () => runtimeBridge.shareRuntimeWorkspaceFile(path)) }, [run])
  const openWorkspaceFile = useCallback((path: string) => { void run('workspace-file-open', () => runtimeBridge.openRuntimeWorkspaceFile(path)) }, [run])

  const clearDiagnostic = useCallback(() => {
    void run('diagnostic-clear', async () => {
      setDiagnostic(await runtimeBridge.clearDiagnosticLog())
    }, t("诊断日志已清空"))
  }, [run])

  /**
   * 读取访客进程输出尾部。
   *
   * 只由诊断页的折叠区块在展开时调用：进入设置页不读取，避免把可能含会话内容的
   * 文本无谓地带进界面。引用保持稳定，折叠区块的副作用不会因此重复触发。
   */
  const loadHarnessLog = useCallback(
    (maxBytes?: number) => runtimeBridge.getHarnessLog({ maxBytes }),
    [],
  )

  /**
   * 读取诊断日志正文窗口。
   *
   * 与状态读取不同，这里会带回日志正文（受控字段），因此同样只在折叠区块展开时才调用；
   * 正文不含 URL、凭据、终端内容或用户数据，读进界面不构成新的泄露面。
   */
  const loadDiagnosticLog = useCallback(
    (maxBytes?: number) => runtimeBridge.readDiagnosticLog({ maxBytes }),
    [],
  )

  /**
   * 运行运行时自检。
   *
   * 与日志面板同样的按需原则：只在用户点「运行自检」或「修复运行时权限」时调用，
   * 进入设置页不自动跑（自检要进访客逐环探测，代价不低）。引用保持稳定，区块的点击不会因此重建。
   */
  const runSelfCheck = useCallback(
    (operation: SelfCheckOperation) => runtimeBridge.runRuntimeSelfCheck(operation),
    [],
  )

  const stopRuntime = useCallback(() => {
    // 先记下「这次停止由本应用发起」：阶段变化可能早于桥接返回，晚记会把显式停止误报成自行停止。
    stopRequested.current = true
    void run('stop', async () => {
      const next = await runtimeBridge.stopRuntime()
      setRuntime(next)
      // 即使原生侧没有推送阶段变化，用户主动停止也要在「上次停止」里留下记录。
      setLastStop('user')
      // 显式停止会同时撤销前台服务。
      setKeepAlive(await runtimeBridge.getKeepAliveState())
      setActiveView('settings')
    }, t("运行环境已停止"))
  }, [run, setActiveView])

  const confirmReset = useCallback(() => {
    // 重置同样会停掉正在运行的运行时：这也是用户在本应用里主动发起的停止。
    stopRequested.current = true
    void run('reset', async () => {
      const next = await runtimeBridge.reset('RESET_RUNTIME')
      setRuntime(next)
      setKeepAlive(await runtimeBridge.getKeepAliveState())
      setResetOpen(false)
      autoLaunchAttempted.current = false
      setActiveView('conversation')
    }, t("运行环境已重置"))
  }, [run, setActiveView])

  const saveSettings = useCallback((nextSettings: RuntimeSettingsUpdate) => {
    void run('save-settings', async () => {
      const saved = await runtimeBridge.saveSettings(nextSettings)
      setSettings(saved)
      // 保存成功：草稿以后端落盘值为准重建（含清空 API Key 输入框）并解除「未保存」标记。
      // 不做这一步的话，旧草稿会在下一次后台刷新时把刚保存的值反向覆盖回去。
      resyncSettingsDraft(saved)
      // 落盘成功后，状态查询失败不能被当成保存失败；三项查询互不阻塞。
      const [runtimeResult, keepAliveResult, overlayResult] = await Promise.allSettled([
        runtimeBridge.getState(), runtimeBridge.getKeepAliveState(), readOverlayBall(),
      ])
      if (runtimeResult.status === 'fulfilled') setRuntime(runtimeResult.value)
      if (keepAliveResult.status === 'fulfilled') setKeepAlive(keepAliveResult.value)
      if ([runtimeResult, keepAliveResult, overlayResult].some(result => result.status === 'rejected')) {
        notify(t("设置已保存，但部分状态暂时无法确认，请稍后重试"), 'info')
      } else {
        notify(t("设置已保存"), 'success')
      }
      if (saved.keepRuntimeInBackground === true && keepAliveResult.status === 'fulfilled' && !keepAliveResult.value.foregroundServiceActive) {
        // 服务可能只是还在启动中，等宽限期过后用原生状态复核：
        // 仍为「未运行」才提示未生效（缺少通知权限、后台启动被系统拒绝或厂商策略限制都会停在这里）。
        recheckForegroundServiceAfterSettle(
          () => runtimeBridge.getKeepAliveState(),
          latest => {
            setKeepAlive(latest)
            if (latest.keepRuntimeInBackground === true && !latest.foregroundServiceActive) {
              notify(t("设置已保存，但后台保持未生效：前台服务未运行。Android 13 及以上需要通知权限，并可能受系统后台限制；请在系统设置中为本应用开启通知权限后重试。"), 'error')
            }
          },
        )
      }
      if (saved.overlayBallEnabled === true && overlayResult.status === 'fulfilled' && !overlayResult.value.serviceActive) {
        // 悬浮球走同一套节奏：原生侧启动前台服务失败时是静默吞异常的，
        // 只有等宽限期过后复核原生状态，才能把「开关开着但球没起来」的原因告诉用户。
        recheckForegroundServiceAfterSettle(
          readOverlayBall,
          latest => {
            if (latest.enabled === true && !latest.serviceActive) {
              notify(t("设置已保存，但悬浮球未生效：前台服务未运行。系统可能拒绝了前台服务启动，或权限不足；请在系统设置中检查「显示在其他应用上层」与通知权限后重试。"), 'error')
            }
          },
        )
      }
    })
  }, [notify, readOverlayBall, resyncSettingsDraft, run])

  /**
   * 申请前台服务通知权限。
   *
   * 权限被拒绝（或调用超时）时前台服务根本起不来，「后台保持」不会生效：
   * 部分厂商 ROM 甚至会因此终结应用进程，所以这里如实说明后果并指引到系统设置，
   * 不再声称「前台服务仍会运行」。
   */
  const requestNotificationPermission = useCallback(() => {
    void run('notification-permission', async () => {
      const result = await withTimeout(
        runtimeBridge.requestNotificationPermission(),
        NOTIFICATION_PERMISSION_TIMEOUT_MS,
      )
      setKeepAlive(await runtimeBridge.getKeepAliveState())
      if (result === null || (!result.granted && result.supported)) {
        notify(t("未获得通知权限：Android 13 及以上需要通知权限才能运行前台服务，后台保持不会生效。请在系统设置中为本应用开启通知权限后重试。"), 'error')
      }
    })
  }, [notify, run])

  const requestShizukuPermission = useCallback(() => {
    void run('shizuku-permission', async () => {
      const next = await runtimeBridge.requestShizukuPermission()
      setShizuku(next)
    }, t("Shizuku 已授权"))
  }, [run])

  const connectShizuku = useCallback(() => {
    void run('shizuku-connect', async () => {
      const next = await runtimeBridge.connectShizuku()
      setShizuku(next)
    }, t("Shizuku 已连接"))
  }, [run])

  const openShizuku = useCallback(() => {
    void run('open-shizuku', () => runtimeBridge.openShizuku())
  }, [run])

  /**
   * 跳转到系统「显示在其他应用上层」设置页。
   *
   * 该权限只能由用户在系统界面手动开启，这里只负责把用户送到那个页面；
   * 返回应用时由可见性刷新重读状态，开关随之从禁用变为可用。
   */
  const openOverlaySettings = useCallback(() => {
    void run('open-overlay-settings', () => runtimeBridge.openOverlaySettings())
  }, [run])

  const screen = (() => {
    switch (activeView) {
      case 'conversation':
        return <ConversationScreen busy={busy} keepAlive={keepAlive} runtime={runtime} onInstall={installRuntime} onLaunch={launchHarness} onOpenSettings={() => setActiveView('settings')} onOpenTerminal={() => setActiveView('terminal')} onUpdate={requestRuntimeUpdate} />
      case 'terminal':
        return <TerminalScreen bridge={runtimeBridge} fontSize={settings?.terminalFontSize ?? 14} onAuthorize={requestShizukuPermission} onBack={() => backToView('settings')} onConnect={connectShizuku} onError={terminalError} onOpenEnvironment={() => setActiveView('environment')} onOpenShizuku={openShizuku} runtime={runtime} shizuku={shizuku} />
      case 'plugins':
        return <PluginSettings bridge={runtimeBridge} runtime={runtime} onBack={() => backToView('settings')} />
      case 'environment':
        return <EnvironmentScreen busy={busy} bundledSource={settings === null || settings.manifestUrl.trim() === ''} runtime={runtime} onBack={() => backToView('settings')} onInstall={installRuntime} onReset={() => setResetOpen(true)} onStart={launchHarness} onStop={stopRuntime} onUpdate={requestRuntimeUpdate} onShareWorkspace={shareWorkspace} onListFiles={listWorkspaceFiles} workspaceFiles={workspaceFiles} onShareFile={shareWorkspaceFile} onOpenFile={openWorkspaceFile} />
      case 'settings':
        return <SettingsHomeScreen busy={busy} diagnostic={diagnostic} keepAlive={keepAlive} runtime={runtime} shizuku={shizuku} onLaunch={launchHarness} onOpenEnvironment={() => setActiveView('environment')} onOpenPage={openSettings} onOpenPlugins={() => setActiveView('plugins')} onOpenTerminal={() => setActiveView('terminal')} onStop={stopRuntime} />
      default: {
        const page = settingsPageOf(activeView)
        if (page === null) return null
        return <SettingsScreen key={`${page}-${settingsReadStatus}`} busy={busy} diagnostic={diagnostic} draft={settingsDraft} keepAlive={keepAlive} lastStop={lastStop} loadDiagnosticLog={loadDiagnosticLog} loadHarnessLog={loadHarnessLog} overlayBall={overlayBall} overlayBallReadFailed={overlayBallReadFailed} onDraftChange={updateSettingsDraft} page={page} runSelfCheck={runSelfCheck} runtime={runtime} settingsReadStatus={settingsReadStatus} shizuku={shizuku} onAuthorize={requestShizukuPermission} onBack={() => backToView('settings')} onClearDiagnostic={clearDiagnostic} onConnect={connectShizuku} onDiagnosticSettings={saveDiagnosticSettings} onLaunch={launchHarness} onLaunchConfirmed={launchHarnessConfirmed} onOpenOverlaySettings={openOverlaySettings} onOpenShizuku={openShizuku} onReloadSettings={() => openSettings(page)} onRequestNotificationPermission={requestNotificationPermission} onSave={saveSettings} onShareDiagnostic={shareDiagnostic} />
      }
    }
  })()

  if (language === null) {
    return <main className="language-screen"><LanguageSettings initial /></main>
  }

  if (booting) {
    return (
      <div className="boot-screen">
        <Brand />
        <Loader2 className="spin" size={23} />
        <span>{t("正在连接本机环境")}</span>
      </div>
    )
  }

  return (
    <div className="app-shell management-shell">
      <header className="mobile-header">
        <Brand />
        <div className="header-actions">
          <PhaseBadge phase={runtime.phase} />
          {activeView === 'conversation' && (
            <button className="icon-button header-settings" type="button" aria-label={t("打开应用设置")} title={t("应用设置")} onClick={() => setActiveView('settings')}>
              <Settings2 size={19} />
            </button>
          )}
        </div>
      </header>

      <main className="app-main">{screen}</main>

      {resetOpen && <ResetDialog busy={busy === 'reset'} onCancel={() => setResetOpen(false)} onConfirm={confirmReset} />}
      {updateOpen && <UpdateDialog busy={busy === 'update-runtime'} onCancel={() => setUpdateOpen(false)} onConfirm={confirmRuntimeUpdate} />}

      {onboardingOpen && (
        <Onboarding
          busy={busy}
          runtime={runtime}
          shizuku={shizuku}
          settings={settings}
          onInstall={installRuntime}
          onAuthorize={requestShizukuPermission}
          onOpenShizuku={openShizuku}
          onOpenHarness={launchHarness}
          onOpenHarnessConfirmed={launchHarnessConfirmed}
          onDone={() => {
            try {
              window.localStorage.setItem(ONBOARDING_STORAGE_KEY, '1')
            } catch {
              // Storage can be unavailable in restricted WebViews; closing remains in-memory.
            }
            setOnboardingOpen(false)
          }}
          onSaveSettings={saveSettings}
        />
      )}

      {notice !== null && (
        <div className={`toast toast-${notice.tone}`} role={notice.tone === 'error' ? 'alert' : 'status'}>
          {notice.tone === 'success' ? <CheckCircle2 size={18} /> : notice.tone === 'error' ? <AlertTriangle size={18} /> : <Wifi size={18} />}
          <span>{notice.message}</span>
          <button type="button" aria-label={t("关闭提示")} onClick={() => setNotice(null)}><X size={16} /></button>
        </div>
      )}
    </div>
  )
}
