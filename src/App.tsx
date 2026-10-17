import appMark from './assets/app-mark.png'
import { t, useLanguage } from './i18n'
import { formatByteCount as formatBytes, formatDuration, useDownloadTelemetry } from './downloadTelemetry'
import { PluginSettings } from './components/PluginSettings'
import { ApplicationPicker } from './components/ApplicationPicker'
import { DeviceShellSettings } from './components/DeviceShellSettings'
import { VirtualScreenSettings } from './components/VirtualScreenSettings'
import { AutomationRuleForm } from './components/AutomationRuleForm'
import type { AutomationRuleDefaults } from './components/AutomationRuleForm'
import { AutomationAppConfigForm } from './components/AutomationAppConfigForm'
import { AutomationDisclaimer } from './components/AutomationDisclaimer'
import { AutomationTaskCard } from './components/AutomationTaskCard'
import { AppBackground } from './components/AppBackground'
import { LanguageSettings } from './components/LanguageSettings'
import { AppearanceSettings } from './components/AppearanceSettings'
import { SessionManager } from './components/SessionManager'
import { DiagnosticLogPanel, HarnessLogPanel } from './ui/logs'
import { FilesScreen, type FilesRoot } from './screens/FilesScreen'
import { RuntimeSelfCheckPanel } from './screens/RuntimeSelfCheckPanel'
import { PermissionGuideDialog, ResetDialog, UpdateDialog } from './screens/Dialogs'
import { keepAliveGuideResolved, overlayFailureGuide, overlayGuideResolved, type PermissionGuide } from './ui/permissionGuide'
import { RuntimeVersionsScreen } from './screens/RuntimeVersionsScreen'
import { lazy, Suspense, type ReactNode, useCallback, useEffect, useRef, useState } from 'react'
import { triggerHaptic } from './platform/haptics'
import { errorMessage, runtimeErrorMessage, type NoticeTone } from './ui/messages'
import { formatRecordedAt } from './ui/formatTime'
import {
  FILES_STORAGE_DIRECTORY_BUSY_ID,
  FILES_STORAGE_FOLDER_CREATE_BUSY_ID,
  STORAGE_DIR_ADD_BUSY_ID,
  STORAGE_DIR_CANCELLED,
  storageDirErrorCode,
  storageDirMessage,
  storageDirRemoveBusyId,
} from './ui/storageDir'
import { PhaseBadge } from './ui/phase'
import { PHASE_META } from './ui/phaseMeta'
import {
  AlertTriangle,
  ArrowLeft,
  BellRing,
  Bot,
  Check,
  CheckCircle2,
  ChevronRight,
  CloudDownload,
  Cpu,
  Database,
  Download,
  ExternalLink,
  FolderInput,
  Gauge,
  HardDrive,
  KeyRound,
  Loader2,
  LockKeyhole,
  Pencil,
  Play,
  Plus,
  Power,
  RefreshCw,
  RotateCcw,
  Rocket,
  Save,
  Search,
  ScrollText,
  Settings2,
  Share2,
  ShieldCheck,
  SlidersHorizontal,
  ListFilter,
  Smartphone,
  Square,
  SquareTerminal,
  Trash2,
  Wifi,
  X,
  Blocks,
  ListChecks,
  MessageSquare,
} from 'lucide-react'
import { Onboarding, ONBOARDING_STORAGE_KEY } from './components/Onboarding'
import { hasConfiguredModelCredential, MODEL_PROVIDERS } from './modelProviders'
import { CustomProviders } from './components/CustomProviders'
import { DeviceCapabilityCard } from './components/DeviceCapabilityCard'
import { RootEngineCard } from './components/RootEngineCard'
import { AppUpdateReminderDialog } from './components/AppUpdateReminderDialog'
import { useAppUpdateReminder } from './appUpdateReminder'
import { runtimeBridge } from './platform/native'
import { observeKeyboardInset } from './keyboardInset'
import { applyShellFontScale, SHELL_FONT_BASELINE_PX } from './shellFont'
import { acknowledgeAutomationDisclaimer, isAutomationDisclaimerAcknowledged } from './accessibilityDisclaimer'
import { assertMailboxSubdirectory, validateAccessibilityPasswordInput } from './platform/validation'
import {
  type SelfCheckOperation,
  type SelfCheckReport,
} from './runtimeSelfCheck'
import type {
  DiagnosticLogState,
  DiagnosticLogText,
  AccessibilityAutomationAction,
  AccessibilityAutomationAppConfig,
  AccessibilityAutomationAppConfigDetail,
  AccessibilityAutomationRuleInput,
  AccessibilityAutomationRuleWriteMode,
  AccessibilityAutomationRun,
  AccessibilityAutomationRules,
  AccessibilityAutomationRuns,
  AccessibilityAutomationRuntime,
  AccessibilityAutomationState,
  AccessibilityServiceStatus,
  AppUpdateChannel,
  CustomModelProvider,
  HarnessLog,
  KeepAliveState,
  MailboxDirectoryState,
  MailboxExportResult,
  MailboxImportResult,
  MailboxRoot,
  MailboxState,
  ModelConnectivityResult,
  ModelProviderId,
  OverlayBallState,
  ProviderApiKeys,
  RuntimeInstallResult,
  RuntimePhase,
  RuntimeProgress,
  RuntimeSource,
  RuntimeSettings,
  RuntimeSettingsUpdate,
  RuntimeState,
  ShizukuState,
  StorageAccessState,
  StorageDirectoryState,
  StorageDirsState,
  TerminalKind,
} from './platform/types'

import {
  DIAGNOSTIC_RETENTION_MAX,
  DIAGNOSTIC_RETENTION_DEFAULT,
  DIAGNOSTIC_RETENTION_MIN,
} from './platform/types'

const TerminalPanel = lazy(() => import('./components/TerminalPanel').then(module => ({ default: module.TerminalPanel })))

type AppView =
  | 'conversation'
  | 'sessions'
  | 'settings'
  | 'settings-models'
  | 'settings-runtime'
  | 'settings-terminal'
  | 'settings-device-shell'
  | 'settings-diagnostics'
  | 'settings-automation'
  | 'terminal'
  | 'environment'
  | 'plugins'
  | 'versions'
  | 'files'

/** 设置二级页：把原先的单页设置按功能分类，避免所有选项挤在一屏里。 */
type SettingsPage = 'models' | 'runtime' | 'terminal' | 'device-shell' | 'automation' | 'diagnostics'

const SETTINGS_PAGE_META: Record<SettingsPage, { title: string; hint: string; view: AppView }> = {
  models: { title: '模型与密钥', hint: '供应商、API Key 与自定义模型', view: 'settings-models' },
  runtime: { title: '后台', hint: '运行时来源与两个入口、后台保持、悬浮球与前台服务', view: 'settings-runtime' },
  terminal: { title: '主题', hint: '字号、卡片颜色与透明度、图片与视频背景', view: 'settings-terminal' },
  'device-shell': { title: '设备 Shell 设置', hint: 'Shizuku 授权与 AI Shell', view: 'settings-device-shell' },
  automation: { title: '自动化设置', hint: '无障碍应用自动化与副屏', view: 'settings-automation' },
  diagnostics: { title: '诊断与日志', hint: '采集开关、保留天数、运行日志与导出', view: 'settings-diagnostics' },
}

const SETTINGS_PAGES: SettingsPage[] = ['models', 'runtime', 'terminal', 'device-shell', 'automation', 'diagnostics']

function settingsPageOf(view: AppView): SettingsPage | null {
  const entry = SETTINGS_PAGES.find(page => SETTINGS_PAGE_META[page].view === view)
  return entry ?? null
}

/**
 * 是否属于「设置区」（设置首页与五个二级页）。
 *
 * 设置草稿的作用范围就是它：区内切页共享同一份未保存内容，离开设置区即丢弃。
 * 副屏设置并进「自动化设置」页之后，设置区内不再有自带读写生命周期的独立页。
 */
function isSettingsView(view: AppView): boolean {
  return view === 'settings' || settingsPageOf(view) !== null
}

/** 按用户用途分类；分类只影响入口展示，不改动设置草稿。 */
type SettingsHomeGroup = 'runtime' | 'files' | 'versions' | 'models' | 'appearance' | 'device' | 'diagnostics'

const SETTINGS_HOME_GROUPS: { id: SettingsHomeGroup; label: string }[] = [
  { id: 'models', label: '模型连接' },
  { id: 'appearance', label: '外观与显示' },
  { id: 'device', label: '设备与自动化' },
  { id: 'runtime', label: '后台' },
  { id: 'files', label: '文件管理' },
  { id: 'versions', label: '版本管理' },
  { id: 'diagnostics', label: '日志与排查' },
]

/** 入口点下去要去哪：设置二级页，或外壳一级页。 */
type SettingsHomeTarget = { kind: 'page'; page: SettingsPage } | { kind: 'view'; view: AppView }

interface SettingsHomeEntry {
  /** 稳定标识：只用于 React key、图标与测试定位；改标题不影响它，也不参与匹配。 */
  id: string
  group: SettingsHomeGroup
  /** 标题与说明就是入口按钮上显示的文字（与二级页标题逐字一致，匹配时也算进去）。 */
  title: string
  hint: string
  keywords: readonly string[]
  target: SettingsHomeTarget
  icon: ReactNode
}

/**
 * 设置首页的全部入口。
 *
 * 每个入口归属一个用途分类。
 *
 * 这里的标题与说明是**裸字符串**，渲染时才交给 `t()`；仓库外的 `locales-check.cjs`
 * 只认字面量 `t("…")` 调用，看不见这张表。补英文词条时必须手工同步 `src/locales/en.ts`，
 * 兜底的是 `src/App.settingsHome.test.tsx` 里「切成英文后整屏入口都是英文」那条用例。
 */
const SETTINGS_HOME_ENTRIES: SettingsHomeEntry[] = [
  // —— 后台：运行环境来源与后台保活配置；Harness 服务的启停统一在首页。
  {
    id: 'environment',
    group: 'runtime',
    title: 'Ubuntu 运行时',
    hint: '安装进度、版本、来源与重置',
    keywords: ['ubuntu', '运行时', '环境', '安装', '重置', '更新', '来源', 'proot', 'linux', 'runtime', 'reset', 'install'],
    target: { kind: 'view', view: 'environment' },
    icon: <HardDrive size={20} />,
  },
  {
    id: 'runtime-page',
    group: 'runtime',
    title: '后台',
    hint: '运行时来源与两个入口、后台保持、悬浮球与前台服务',
    keywords: ['后台', '保活', '保持', '悬浮球', '前台服务', '通知', '电池', 'keepalive', 'background', 'overlay', 'foreground'],
    target: { kind: 'page', page: 'runtime' },
    icon: <Power size={20} />,
  },
  {
    id: 'terminal-view',
    group: 'device',
    title: '设备 Shell',
    hint: '设备上的 Shell（需 Shizuku）',
    keywords: ['设备shell', 'shell', 'terminal', '终端', 'shizuku', 'adb', '命令行', '控制台'],
    target: { kind: 'view', view: 'terminal' },
    icon: <SquareTerminal size={20} />,
  },

  // —— 文件管理：手机侧与访客之间对外可见的目录都在这一个页面里。
  {
    id: 'files',
    group: 'files',
    title: '文件管理',
    hint: '外部工作区：一处浏览、建文件夹与搬运',
    keywords: ['文件', '文件夹', '目录', '外部工作区', '投递区', '共享目录', '导入', '导出', '搬运', '浏览', '新建文件夹', 'mailbox', 'storage', 'file', 'files', 'folder', 'directory'],
    // 这组功能原属「后台」页：访客的外部工作区不再挂在那页上，
    // 搜「导入」「投递区」的人要找的是这一页。
    target: { kind: 'view', view: 'files' },
    icon: <FolderInput size={20} />,
  },

  // —— 版本管理：磁盘上有哪几份运行时、现在用哪一份。
  {
    id: 'versions',
    group: 'versions',
    title: '版本管理',
    hint: '保留上一版本，可一键切回；装新版本仍在运行环境页',
    keywords: ['版本', 'version', '运行时版本', '上一版本', '回退', '切回', '切换', '回滚', '降级', '升级', '更新', 'dsh', 'previous', 'rollback', 'switch', 'retained'],
    target: { kind: 'view', view: 'versions' },
    icon: <RotateCcw size={20} />,
  },

  // —— 配置入口按模型、外观、设备与诊断分别归类。
  {
    id: 'models',
    group: 'models',
    title: '模型与密钥',
    hint: '供应商、API Key 与自定义模型',
    keywords: ['模型', '密钥', 'key', 'api', 'apikey', 'api key', '供应商', 'provider', 'token', '凭据', '自定义模型', 'deepseek', 'openai', 'anthropic'],
    target: { kind: 'page', page: 'models' },
    icon: <KeyRound size={20} />,
  },
  {
    id: 'terminal-settings',
    group: 'appearance',
    title: '主题',
    hint: '字号、卡片颜色与透明度、图片与视频背景',
    keywords: ['主题', '外观', '字号', '字体', '深色', '浅色', '背景', '壁纸', '视频', '图片', '透明度', '卡片', '颜色', '强调色', 'theme', 'font', 'appearance', 'background', 'color'],
    target: { kind: 'page', page: 'terminal' },
    icon: <SquareTerminal size={20} />,
  },
  {
    id: 'device-shell',
    group: 'device',
    title: '设备 Shell 设置',
    hint: 'Shizuku 授权与 AI Shell',
    keywords: ['shizuku', '授权', '权限', '连接', '设备shell', 'ai shell', 'device shell'],
    target: { kind: 'page', page: 'device-shell' },
    icon: <Smartphone size={20} />,
  },
  {
    id: 'automation',
    group: 'device',
    title: '自动化设置',
    hint: '无障碍应用自动化与副屏',
    keywords: ['无障碍', '自动化', '白名单', '应用列表', '副屏', '虚拟屏幕', '小窗', '截图', '点击', 'accessibility', 'automation', 'virtual screen', 'display'],
    target: { kind: 'page', page: 'automation' },
    icon: <Bot size={20} />,
  },
  {
    id: 'diagnostics',
    group: 'diagnostics',
    title: '诊断与日志',
    hint: '采集开关、保留天数、运行日志与导出',
    keywords: ['诊断', '日志', 'log', 'logs', '采集', '保留', '导出', '排查', '报错', '错误', '崩溃', '重启', '自检', 'self check'],
    target: { kind: 'page', page: 'diagnostics' },
    icon: <ScrollText size={20} />,
  },
]

/**
 * 按功能词本地匹配入口：不发请求、不写存储、不改运行状态。
 *
 * 规则刻意保持简单，让用户能预期、不用猜：
 *  - 大小写不敏感；
 *  - 查询按空白拆成多个词，**全部命中**才算匹配（多词顺序无关，可当「缩小范围」用）；
 *  - 匹配范围 = 标题 + 说明 + 同义关键词。
 *
 * 断网、运行时没装好时一样可用——「找不到入口」恰恰是用户最需要搜的时候。
 */
function matchesSettingsHomeQuery(entry: SettingsHomeEntry, query: string): boolean {
  const tokens = query.toLowerCase().split(/\s+/).filter(token => token !== '')
  if (tokens.length === 0) return true
  const group = SETTINGS_HOME_GROUPS.find(item => item.id === entry.group)
  const haystack = `${entry.title} ${t(entry.title)} ${entry.hint} ${t(entry.hint)} ${group?.label} ${t(group?.label ?? '')} ${entry.keywords.join(' ')}`.toLowerCase()
  return tokens.every(token => haystack.includes(token))
}

/** 外壳视图的全部取值：历史状态与地址片段只接受这里的值，其余一律回落到主视图。 */
const APP_VIEWS: AppView[] = [
  'conversation',
  'sessions',
  'settings',
  'settings-models',
  'settings-runtime',
  'settings-terminal',
  'settings-device-shell',
  'settings-diagnostics',
  'settings-automation',
  'terminal',
  'environment',
  'plugins',
  'versions',
  'files',
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


interface Notice {
  id: number
  message: string
  tone: NoticeTone
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
/**
 * 重置确认的考虑时间（秒）。
 *
 * 重置会连同用户数据一起清除，所以确认按钮在这段时间内保持禁用，给用户一个反悔的机会；
 * 但不再要求输入确认词——在手机上敲单词既费力又拦不住真正的误触。
 * 交给原生侧的确认口令（`RESET_RUNTIME`）不变，这里只改交互。
 */
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
 * 后台状态轮询的周期（登记册 5.6-C）。
 *
 * 5 秒是「前台服务被系统结束」这类变化能被用户察觉的上限：更密没有必要（原生侧的变化本身不频繁），
 * 更疏会让「刚在系统设置里改了权限」的反馈显得迟钝。轮询只在页面可见时进行。
 */
const BACKGROUND_POLL_INTERVAL_MS = 5000

/**
 * 单飞的陈旧逃逸时间。
 *
 * 单飞本身有风险：桥调用若卡死不返回，`Promise.allSettled` 永不结束，轮询会被**永久**停住——
 * 那比「重复请求」糟得多。因此超过这个时间仍未结束就视为陈旧，允许下一轮重试。
 * 取 30 秒而不是 5 秒：真机上安装/更新运行时的单次读取确实可能超过一个轮询周期，
 * 太短会让单飞形同虚设。
 */
const BACKGROUND_REFRESH_STALE_MS = 30_000

/**
 * 用户动作的看门狗。
 *
 * 桥调用在真机上可能**永不返回**（provider 掉线、原生线程池打满、WebView 与 Service
 * 之间的回调丢失）。`run()` 用互斥锁挡重复提交，一旦某次调用卡死，锁就不会释放：
 * 界面从此永久停在「处理中」，用户只能杀进程——这比「操作失败」糟得多。
 *
 * 看门狗只做一件事：到点后**释放界面互斥并提示超时**，不取消底层请求
 * （Capacitor 没有取消语义，强杀会留下半截的原生操作）。晚到的真实结果由下一次
 * 状态刷新收口，界面不会因为「超时那一刻的乐观判断」而说谎。
 */
const ACTION_WATCHDOG_MS = 45_000

/**
 * 安装/更新的看门狗。
 *
 * 这一步包含真实下载（数百 MB）与解压，正常耗时以分钟计，不能用 [ACTION_WATCHDOG_MS]。
 * 取 15 分钟只为兜住「桥彻底失联」：正常情况下进度事件会持续刷新界面，
 * 用户不会看到这一条。
 */
const INSTALL_WATCHDOG_MS = 15 * 60_000

/**
 * 启动链状态读取的超时。
 *
 * 门屏是否结束只取决于 `getState()` 是否返回。它若卡死，`booting` 永远为真，
 * 用户面对的是一个没有任何按钮的加载页——连「重试」都点不到。
 */
const BOOT_STATE_TIMEOUT_MS = 15_000

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


function mergeRuntimeProgress(state: RuntimeState, progress: RuntimeProgress): RuntimeState {
  return {
    ...state,
    phase: progress.phase,
    downloadedBytes: progress.downloadedBytes,
    totalBytes: progress.totalBytes,
    errorCode: progress.phase === 'error' ? progress.errorCode : undefined,
  }
}

/**
 * 轮询快照的「值相等」判据：逐字段 `Object.is`。
 *
 * 为什么必须有这层比较：**桥调用回来的是新对象**。`src/platform/native.ts` 的每个读取都走
 * Capacitor 桥（JSON 序列化），再过一遍 `src/platform/validation.ts` 的
 * `validateShizukuState` / `validateKeepAliveState` / `validateOverlayBallState`，
 * 而这三个校验函数都以**新的对象字面量**返回。于是「5 秒一次的轮询读到的东西一个字都没变」
 * 这件事，在 React 眼里是三个全新的引用 —— `Object.is` 的免渲染短路永远不命中，
 * 结果就是每 5 秒把当前挂载的整棵视图白渲染一遍。
 *
 * 为什么不引第三方深比较：这里要比的三类快照（`ShizukuState` / `KeepAliveState` /
 * `OverlayBallState`，见 `src/platform/types.ts`）都是**扁平对象**，字段全是布尔/字符串/数字，
 * 逐键 `Object.is` 就是完整语义，不需要再加一个依赖。
 *
 * 键取**并集**而不是只遍历 `previous` 的键：可选字段（`version`、`lastPhase`、
 * `lastUpdatedAtMillis`）在缺省时是「这个键不存在」而不是「值为 undefined」，
 * 只比一侧会漏掉「一侧多出一个字段」的变化。任一侧缺键时另一侧读到 `undefined`，
 * 两侧都缺即相等，正是想要的语义。
 */
function sameSnapshot<T extends object>(previous: T, next: T): boolean {
  const keys = new Set([...Object.keys(previous), ...Object.keys(next)])
  for (const key of keys) {
    const left = (previous as Record<string, unknown>)[key]
    const right = (next as Record<string, unknown>)[key]
    if (!Object.is(left, right)) return false
  }
  return true
}


/**
 * 从桥调用抛出的异常里取出原生错误码；取不到就回 `undefined`。
 *
 * 为什么不能写成 `error instanceof Error && error.code === 'RUNTIME_CLOSED'`：
 * 原生侧的错误码挂在 Capacitor 的 `CapacitorException` 上（`@capacitor/core` 里
 * `this.code = code`），它在真实设备上**未必**是 `Error` 的实例，只保证「是个带 `code` 的对象」。
 * [errorMessage] 早就按后者判断（`'code' in error`），这里必须与它同一口径——
 * 两边对「什么算有码的错误」判断不一致时，会出现「文案认得这个码、重试却不认得」的诡异分裂。
 */
function bridgedRuntimeCode(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null || !('code' in error)) return undefined
  const code = error.code
  return typeof code === 'string' ? code : undefined
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

// 读取之前的占位状态：什么都还没读到，因此列表空、密码标记为未设置。
// 真实取值由 getAccessibilityAutomationState 回填，这里不预置任何包名。
// 总开关与服务状态取**最保守**的一侧（关着、未连上）：占位值出现在界面上时不能说成「正在跑」。
const EMPTY_ACCESSIBILITY: AccessibilityAutomationState = {
  enabled: false,
  whitelistEnabled: true,
  allowedPackages: [],
  alwaysAllowedPackages: [],
  passwordConfigured: false,
  automationRuntime: 'off',
  serviceStatus: 'off',
}

/** 总开关三态 → 界面标签。未知取值不猜，按「关闭」显示（与原生 fail-closed 口径一致）。 */
function automationRuntimeLabel(runtime: AccessibilityAutomationRuntime): string {
  if (runtime === 'running') return t("自动化运行中")
  if (runtime === 'paused') return t("自动化已暂停")
  return t("自动化已关闭")
}

/** 系统侧无障碍服务三态 → 界面标签。 */
function accessibilityServiceLabel(status: AccessibilityServiceStatus): string {
  if (status === 'connected') return t("无障碍服务已连接")
  if (status === 'enabled_disconnected') return t("系统里已开启，但服务没连上")
  return t("系统里未开启")
}

/**
 * 把「相对开机时刻的毫秒数」显示成人能读的时长。
 *
 * 运行记录里的时间是原生侧的 `SystemClock.elapsedRealtime()`，**不是**墙上时钟，
 * 所以这里绝不把它渲染成一个日期时间——那会显示成一个 1970 年附近的假时间点。
 */
function formatElapsedSinceBoot(atMs: number): string {
  const totalSeconds = Math.max(0, Math.floor(atMs / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  if (hours > 0) return t("{0} 小时 {1} 分前", hours, minutes)
  if (minutes > 0) return t("{0} 分 {1} 秒前", minutes, seconds)
  return t("{0} 秒前", seconds)
}

/** 一条运行记录的结果标签：受控码的中文说明优先，读不懂就显示原始码（绝不改写成「已跳过」）。 */
function automationRunLabel(run: AccessibilityAutomationRun): string {
  return run.message ?? run.code
}

interface AppSidebarProps {
  activeView: AppView
  onNavigate: (view: AppView) => void
}

/**
 * 外壳导航轨道：窄屏保留图标，宽屏显示文字。
 *
 * 入口只负责切换外壳视图，真正的 Harness 会话仍由运行时页面管理；
 * 因此侧栏不会把会话正文、凭据或终端输出放进 DOM。
 */
function AppSidebar({ activeView, onNavigate }: AppSidebarProps) {
  // 「版本管理」也归在设置轨道高亮里：它是从设置首页进入的一级页，底部导航不该看起来像「没进设置」。
  // 「文件管理」与「版本管理」是从设置首页进入的一级页：底部导航/侧栏仍算在设置里，
  // 否则用户进到这两页会觉得「我没在设置里」。
  const settingsActive = isSettingsView(activeView) || activeView === 'environment' || activeView === 'terminal' || activeView === 'versions' || activeView === 'files'
  const item = (view: AppView, label: string, icon: ReactNode, active: boolean, className = '') => (
    <button
      className={`sidebar-item ${active ? 'is-active' : ''} ${className}`}
      type="button"
      aria-label={t(label)}
      aria-current={active ? 'page' : undefined}
      title={t(label)}
      onClick={() => onNavigate(view)}
    >
      {icon}<span>{t(label)}</span>
    </button>
  )

  return (
    <aside className="app-sidebar" aria-label={t('应用导航')}>
      <button
        className={`sidebar-mark ${activeView === 'sessions' ? 'is-active' : ''}`}
        type="button"
        aria-label={t('会话管理')}
        aria-current={activeView === 'sessions' ? 'page' : undefined}
        title={t('会话管理')}
        onClick={() => onNavigate('sessions')}
      >
        <img className="app-mark" src={appMark} alt="" width={26} height={26} />
        <span>{t('会话')}</span>
      </button>
      <nav className="sidebar-nav">
        {item('conversation', '对话', <MessageSquare size={20} />, activeView === 'conversation')}
        {item('plugins', '插件管理', <Blocks size={20} />, activeView === 'plugins')}
      </nav>
      <div className="sidebar-bottom">
        {item('settings', '应用设置', <Settings2 size={20} />, settingsActive)}
      </div>
    </aside>
  )
}

/** 手机横竖屏使用底部导航；会话管理由页头鲸鱼入口打开。 */
function BottomNavigation({ activeView, onNavigate }: AppSidebarProps) {
  const items: { view: AppView; label: string; icon: ReactNode; active: boolean }[] = [
    { view: 'conversation', label: '首页', icon: <MessageSquare size={20} />, active: activeView === 'conversation' || activeView === 'sessions' },
    { view: 'plugins', label: '插件', icon: <Blocks size={20} />, active: activeView === 'plugins' },
    { view: 'settings', label: '设置', icon: <Settings2 size={20} />, active: isSettingsView(activeView) || activeView === 'environment' || activeView === 'terminal' || activeView === 'versions' || activeView === 'files' },
  ]
  return (
    <nav className="bottom-navigation" aria-label={t('底部导航')}>
      {items.map(item => (
        <button key={item.view} type="button" className={`bottom-navigation-item ${item.active ? 'is-active' : ''}`}
          aria-label={t(item.label)} title={t(item.label)} aria-current={item.active ? 'page' : undefined} onClick={() => onNavigate(item.view)}>
          {item.icon}
          <span>{t(item.label)}</span>
        </button>
      ))}
    </nav>
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

/**
 * 把桥调用抛出的任意拒绝值规整成 `Error`，并**保留 `code`**。
 *
 * 两个约束必须同时满足：`prefer-promise-reject-errors` 要求拒绝值是 Error；但原生侧的
 * `CapacitorException` 未必是 Error 实例（见 [bridgedRuntimeCode] 的解释），
 * 而 [errorMessage] 正是靠 `'code' in error` 认出它的。直接 `new Error(reason)` 会把 code
 * 丢掉，用户看到的就从「运行时已关闭，旧连接已失效；已尝试重新连接，请再试一次」
 * 退化成一句通用失败文案——比原样透传更糟。
 */
function toErrorWithCode(reason: unknown): Error {
  if (reason instanceof Error) return reason
  const code = typeof reason === 'object' && reason !== null && 'code' in reason && typeof reason.code === 'string'
    ? reason.code
    : undefined
  return Object.assign(new Error(code ?? 'BRIDGE_CALL_FAILED'), code === undefined ? {} : { code })
}

/**
 * 超时即**失败**的桥调用包装。
 *
 * [withTimeout] 走的是「超时降级成 null」的路线，只适合调用方本来就把 null 当成正常结果
 * （权限询问被系统悄悄吞掉时就该按未授予处理）。启动链上的状态读取不能用那个口径：
 * 「读不到」必须是一条可见的错误，不能伪装成「没有状态」——否则门屏会在没有任何提示的
 * 情况下停在加载页。超时拒绝的错误带 `code`，由 [errorMessage] 统一翻成文案。
 */
function withBridgeTimeout<T>(promise: Promise<T>, milliseconds: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => {
      reject(Object.assign(new Error('BRIDGE_TIMEOUT'), { code: 'BRIDGE_TIMEOUT' }))
    }, milliseconds)
    promise.then(
      value => {
        window.clearTimeout(timer)
        resolve(value)
      },
      error => {
        window.clearTimeout(timer)
        reject(toErrorWithCode(error))
      },
    )
  })
}

interface ConversationScreenProps {
  busy: string | null
  keepAlive: KeepAliveState
  runtime: RuntimeState
  onInstall: () => void
  onLaunch: (sessionId?: string) => void
  /** 启动卡片上那一格按需求从「应用设置」换成「停止」：只管停 Harness 服务。 */
  onStop: () => void
  onOpenTerminal: () => void
  onUpdate: () => void
}

function ConversationScreen({ busy, keepAlive, runtime, onInstall, onLaunch, onStop, onOpenTerminal, onUpdate }: ConversationScreenProps) {
  const installed = runtimeInstalled(runtime)
  const transitioning = runtimeTransitioning(runtime)
  const updateRequired = installed && runtime.updateAvailable && !transitioning
  const progress = runtime.totalBytes > 0
    ? Math.min(100, Math.round((runtime.downloadedBytes / runtime.totalBytes) * 100))
    : 0
  // 门屏是「用户最容易怀疑卡死」的地方：阶段切换会重置字节口径，以 phase 作为分段键。
  const downloadTelemetry = useDownloadTelemetry(runtime.downloadedBytes, runtime.totalBytes, transitioning, runtime.phase)

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
            <div className="progress-detail progress-telemetry">
              <span>{t("已用 {0}", formatDuration(downloadTelemetry.elapsedMs))}</span>
              {downloadTelemetry.speedBytesPerSec > 0 && (
                <span>{t("速度 {0}/秒", formatBytes(downloadTelemetry.speedBytesPerSec))}</span>
              )}
              {downloadTelemetry.etaMs !== null && (
                <span>{t("剩余约 {0}", formatDuration(downloadTelemetry.etaMs))}</span>
              )}
            </div>
            {runtime.phase === 'downloading' && downloadTelemetry.stalled && (
              <div className="progress-note">{t("网络较慢，正在等待数据…")}</div>
            )}
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
            <button className="button button-primary" type="button" onClick={() => onLaunch()} disabled={busy !== null}>
              <Play size={18} fill="currentColor" />{t("打开对话")}</button>
          )}
          {runtime.phase === 'error' && installed && !updateRequired && (
            <button className="button button-secondary" type="button" onClick={onOpenTerminal} disabled={busy !== null}>
              <SquareTerminal size={18} />{t("打开终端排查")}</button>
          )}
          {/* 这一格是「停止 Harness 服务」：应用设置入口在顶部图标与底部导航里都有，
              不必占着启动卡片的重心；没在跑的时候不摆一个按不动的停止键。 */}
          {runtime.phase === 'running' && (
            <button className="button button-secondary" type="button" onClick={onStop} disabled={busy !== null}>
              <Square size={18} />{t("停止")}</button>
          )}
        </div>
      </section>

      {keepAlive.reconnectRequired && !transitioning && (
        <div className="inline-alert warning" role="alert">
          <AlertTriangle size={19} />
          <div>
            <strong>{t("需要重新连接")}</strong>
            <span>{t("应用进程已被系统结束，当前连接已失效。请重新连接以启动 Harness。")}</span>
          </div>
          <button className="button button-primary compact-button" type="button" onClick={() => onLaunch()} disabled={busy !== null || !installed}>
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
      {installed && !transitioning && !updateRequired && <SessionManager embedded disabled={busy !== null} onBack={() => undefined} onOpenHarness={onLaunch} />}
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
}

function EnvironmentScreen({ busy, bundledSource, runtime, onBack, onInstall, onReset, onStart, onStop, onUpdate }: EnvironmentScreenProps) {
  const inProgress = ['preparing', 'downloading', 'verifying', 'extracting'].includes(runtime.phase)
  const installed = runtime.installedVersion !== undefined || runtime.phase === 'ready' || runtime.phase === 'running'
  const progress = runtime.totalBytes > 0
    ? Math.min(100, Math.round((runtime.downloadedBytes / runtime.totalBytes) * 100))
    : 0

  const measurableProgress = ['preparing', 'downloading', 'extracting'].includes(runtime.phase) && runtime.totalBytes > 0
  // 阶段切换（下载→校验→安装）会更换字节口径，按 phase 分段计时计速。
  const downloadTelemetry = useDownloadTelemetry(runtime.downloadedBytes, runtime.totalBytes, inProgress, runtime.phase)
  // 速度/ETA 只在真有字节流的两段展示；preparing 读本地、verifying 算哈希，给速度反而误导。
  const showTransferRate = runtime.phase === 'downloading' || runtime.phase === 'extracting'
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
            <div className="progress-detail progress-telemetry">
              <span>{t("已用 {0}", formatDuration(downloadTelemetry.elapsedMs))}</span>
              {showTransferRate && downloadTelemetry.speedBytesPerSec > 0 && (
                <span>{t("速度 {0}/秒", formatBytes(downloadTelemetry.speedBytesPerSec))}</span>
              )}
              {showTransferRate && downloadTelemetry.etaMs !== null && (
                <span>{t("剩余约 {0}", formatDuration(downloadTelemetry.etaMs))}</span>
              )}
            </div>
            {runtime.phase === 'downloading' && downloadTelemetry.stalled && (
              <div className="progress-note">{t("网络较慢，正在等待数据…")}</div>
            )}
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
          <div><strong>{t("安装包内置的运行环境有更新")}</strong><span>{t("更新会替换 Ubuntu 运行时的系统目录：用 apt 等装进系统的软件与其它本地修改会丢失；会话、模型密钥、Harness 设置、附件、技能、默认工作区，以及插件（包括终端安装的第三方插件）会自动备份并尝试恢复。若恢复发生冲突，请到插件管理检查。")}</span></div>
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
        <Suspense fallback={<div className="terminal-loading"><Loader2 className="spin" size={22} /></div>}>
          <TerminalPanel key={`${kind}-${epoch}`} bridge={bridge} fontSize={fontSize} kind={kind} onError={onError} />
        </Suspense>
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
          <h2>{!shizuku.installed ? t("未安装 Shizuku") : !shizuku.running ? t("Shizuku 未运行{0}", shizuku.appVersion ? '（' + shizuku.appVersion + '）' : '') : shizuku.permission !== 'granted' ? t("需要 Shizuku 授权") : t("Shizuku 连接未就绪")}</h2>
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
  onOpenPage: (page: SettingsPage) => void
  onOpenView: (view: AppView) => void
}

/**
 * 设置首页：按分组列出全部入口，分组可以筛选。
 *
 * 筛选只决定这一屏显示哪些入口，不改变任何行为：点下去走的是同一条路。
 */
function SettingsHomeScreen({ busy, diagnostic, keepAlive, runtime, shizuku, onLaunch, onOpenPage, onOpenView }: SettingsHomeScreenProps) {
  /** 过滤词只活在组件里：离开设置首页即清空，下次进来不该被上次的搜索框卡住。 */
  const [query, setQuery] = useState('')
  /** 已确认的查找词：只有点搜索图标或按回车才落地。输入过程本身不筛选——
      移动端输入法组合中途触发筛选，列表刷新会把候选词条顶掉，搜索看起来就是「不能用」。 */
  const [appliedQuery, setAppliedQuery] = useState('')
  /** 输入法是否正在组合（中文/日文等）。组合中途的 Enter 是「确认候选词」，不是「提交搜索」——
      照抄 `ApplicationPicker` 的既有范式：不放行会被吞掉候选词，正好破坏本组件想保护的输入。 */
  const [isComposing, setIsComposing] = useState(false)
  const [category, setCategory] = useState<SettingsHomeGroup | 'all'>('all')
  const searchInputRef = useRef<HTMLInputElement>(null)
  /** 分类芯片默认收起：换行后占 4 行，会把功能入口挤出首屏；由搜索框旁的筛选钮展开。 */
  const [filterOpen, setFilterOpen] = useState(false)
  /** 每个分组只留命中的入口；整组都没命中就连标题一起不渲染，避免空标题刷屏。 */
  const groups = SETTINGS_HOME_GROUPS
    .filter(group => category === 'all' || category === group.id)
    .map(group => ({ group, entries: SETTINGS_HOME_ENTRIES.filter(entry => entry.group === group.id && matchesSettingsHomeQuery(entry, appliedQuery)) }))
    .filter(item => item.entries.length > 0)
  const matched = groups.reduce((total, item) => total + item.entries.length, 0)
  const searching = appliedQuery.trim() !== ''

  /** 兼容原生直接写入 DOM 的输入；限制长度并同步草稿，分类同时复位。 */
  const applySearch = (value: string): void => {
    const nextValue = value.slice(0, 120)
    setQuery(nextValue)
    setAppliedQuery(nextValue.trim())
    setCategory('all')
  }

  /** 运行环境更新提示跟随当前状态，其余入口使用固定说明。 */
  const hintOf = (entry: SettingsHomeEntry): string => entry.id === 'environment' && runtime.updateAvailable ? t("发现内置运行环境更新")
      : t(entry.hint)

  /** 右侧状态徽标：与二级页里显示的是同一个事实，不在两处各写一遍。 */
  const badgeOf = (entry: SettingsHomeEntry): string => entry.id === 'runtime-page'
    ? (keepAlive.foregroundServiceActive ? t("后台保持中") : keepAlive.keepRuntimeInBackground ? t("已开启") : t("未开启"))
    : entry.id === 'shizuku'
      ? (!shizuku.installed ? t("未安装") : shizuku.connected ? t("已连接") : shizuku.permission === 'granted' ? t("已授权") : t("待授权"))
      : entry.id === 'diagnostics'
        ? (diagnostic.enabled ? t("收集中") : t("未收集"))
        : ''

  const openEntry = (entry: SettingsHomeEntry): void => {
    if (entry.target.kind === 'page') onOpenPage(entry.target.page)
    else if (entry.target.kind === 'view') onOpenView(entry.target.view)
  }

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

      {/* 搜索框固定在入口列表上方：入口一多，用户第一反应是找框，而不是往下翻。
          刻意不做边输边筛：输入法组合中途触发筛选会把候选词顶掉，改为点搜索图标或回车确认。 */}
      <div className="settings-search" role="search" aria-label={t("搜索入口")}>
        <input
          type="search"
          ref={searchInputRef}
          value={query}
          onChange={event => setQuery(event.target.value)}
          onCompositionStart={() => setIsComposing(true)}
          onCompositionEnd={event => {
            setIsComposing(false)
            const val = event.currentTarget?.value ?? (event.target as HTMLInputElement)?.value ?? ''
            setQuery(val)
          }}
          /* 失焦兜底：移动端软键盘选词或直接收起键盘时偶尔丢失 compositionend，
             不补这一条会让 `isComposing` 卡在 true，回车再也提交不了。 */
          onBlur={event => {
            setIsComposing(false)
            // captureInput 可能只改 DOM；先把失焦时仍可见的文字同步回受控状态，
            // 避免结束输入法组合时 React 重绘把它清掉。
            setQuery(event.currentTarget.value.slice(0, 120))
          }}
          onKeyDown={event => {
            // 组合中的 Enter 交给输入法确认候选词，不当作提交。
            if (event.key === 'Enter' && !isComposing) {
              event.preventDefault()
              applySearch(event.currentTarget.value)
            }
          }}
          maxLength={120}
          placeholder={t("按功能词查找入口")}
          aria-label={t("按功能词查找入口")}
          autoComplete="off"
        />
        {query !== '' && (
          <button className="settings-search-clear" type="button" onClick={() => { setQuery(''); setAppliedQuery('') }} aria-label={t("清空查找词")}>
            <X size={16} />
          </button>
        )}
        {/* 已选了具体分类时筛选钮高亮，收起芯片后用户仍能看出列表正被筛选。 */}
        <button className={`settings-search-filter ${category !== 'all' ? 'is-filtered' : ''}`} type="button"
          onClick={() => setFilterOpen(open => !open)} aria-label={t("按分类筛选")}
          aria-expanded={filterOpen} aria-controls="settings-categories">
          <ListFilter size={18} />
        </button>
        <button className="settings-search-go" type="button" onClick={() => applySearch(searchInputRef.current?.value ?? query)} aria-label={t("搜索入口")}>
          <Search size={18} />
        </button>
      </div>

      {/* 收起时保留隐藏容器：aria-controls 指向的元素始终存在（与 harness-log-body 同一约定）。 */}
      <div id="settings-categories" className="settings-categories" role="listbox" aria-label={t("筛选功能分类")} hidden={!filterOpen}>
        <button type="button" role="option" aria-label={t("筛选分类：全部分类")} aria-selected={category === 'all'} onClick={() => setCategory('all')}>{t("全部分类")}</button>
        {SETTINGS_HOME_GROUPS.map(group => (
          <button key={group.id} type="button" role="option" aria-label={`${t("筛选分类：")} ${t(group.label)}`} aria-selected={category === group.id}
            onClick={() => { setCategory(group.id); setQuery(''); setAppliedQuery('') }}>{t(group.label)}</button>
        ))}
      </div>

      {/* 结果条用 role="status"：输入时读屏能听到「找到几个」，而不是列表安静地变了一下。 */}
      {searching && (
        <p className="settings-note" role="status">
          {matched === 0
            ? t("没有匹配的入口：试试「导入」「日志」「保活」这类功能词")
            : t("找到 {0} 个匹配的入口", matched)}
        </p>
      )}

      <div className="settings-directory">
      {groups.map(({ group, entries }) => (
        <section className="management-list" key={group.id} aria-label={t(group.label)}>
          {/* 分组标题刻意不用 h1/h2：页头已经有一个「设置」，再加一个同名标题会让
              「按标题名找元素」一次命中两个（测试与读屏都受影响），分组名走 section 的 aria-label。 */}
          <p className="management-group-title">{t(group.label)}</p>
          {entries.map(entry => (
            <button className="management-row" key={entry.id} type="button" onClick={() => openEntry(entry)}>
              <span className="management-icon">{entry.icon}</span>
              <span className="management-copy">
                <strong>{t(entry.title)}</strong>
                <small>{badgeOf(entry) === '' ? hintOf(entry) : `${hintOf(entry)} · ${badgeOf(entry)}`}</small>
              </span>
              <ChevronRight size={18} />
            </button>
          ))}
        </section>
      ))}
      </div>
    </div>
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

/**
 * 规则写入表单的打开状态。
 *
 * `rule` 为 `null` 只允许出现在 `create`：编辑必须先读回整条规则，
 * 因为原生侧的 `update` 是整条替换，缺字段不会保持原样。
 */
interface AutomationRuleDraftState {
  mode: AccessibilityAutomationRuleWriteMode
  rule: AccessibilityAutomationRuleInput | null
  /**
   * 新建时的三个数字初值，取自「每应用参数」读数。
   *
   * 只对 `create` 有意义：`update` 一律用读回的整条规则。没读到参数时留空，
   * 由表单回落到原生默认值——那是**表单初值**的回落，与「把一份没读到的读数当真相显示」
   * 是两回事：这里没有向用户展示任何数字，用户填表时也会看到自己填的是什么。
   */
  defaults?: AutomationRuleDefaults
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
  overlayBall: OverlayBallState | null
  overlayBallReadFailed: boolean
  page: SettingsPage
  runtime: RuntimeState
  /** 本次会话记录到的最近一次停止方式；`none` 表示本次会话还没观察到停止。 */
  lastStop: LastStopReason
  /** 运行自检（check / repair）；只在用户点击按钮时调用。 */
  runSelfCheck: (operation: SelfCheckOperation) => Promise<SelfCheckReport>
  shizuku: ShizukuState
  accessibility: AccessibilityAutomationState
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
  /** 跳到「Ubuntu 运行时」独立页；后台页只留入口。 */
  onOpenEnvironment: () => void
  /** 跳到「设备 Shell」独立页；后台页只留入口。 */
  onOpenDeviceShell: () => void
  onOpenAccessibilitySettings: () => void
  /**
   * 保存白名单。已设置验证密码时**必须**带上 `password`：
   * 原生侧会拒绝不带密码的修改，这里也先在前端拦一道，省掉一次必然失败的桥调用。
   */
  onSaveAccessibilityPackages: (packages: string[], password?: string, whitelistEnabled?: boolean) => void
  /**
   * 改「自动化总开关」：开始 / 暂停 / 继续 / 紧急停止。
   *
   * 这是**应用内独有**的开启路径：设备桥（AI 侧）没有开启自动化的能力。
   * 返回的 Promise 用于让界面保持在途标记，直到桥真的给了结果。
   */
  onSetAccessibilityRuntime: (action: AccessibilityAutomationAction) => Promise<void>
  /** 启用 / 停用一条规则；成功后重新读取整包读数（不做乐观更新，失败时开关保持原位）。 */
  onSetAutomationRuleEnabled: (options: {
    packageName: string
    ruleId: string
    enabled: boolean
  }) => Promise<void>
  /** 设置（省略 currentPassword）或修改（必须带 currentPassword）白名单验证密码。 */
  onSaveAccessibilityPassword: (password: string, currentPassword?: string) => void
  /** 清除验证密码；必须带上当前密码。 */
  onClearAccessibilityPassword: (currentPassword: string) => void
  /** 忘记密码时的退路：走系统生物识别/锁屏密码重置，只清密码、保留白名单。 */
  onResetAccessibilityPassword: () => void
  onRequestNotificationPermission: () => void
  onReloadSettings: () => void
  onSave: (settings: RuntimeSettingsUpdate) => Promise<boolean>
  onShareDiagnostic: () => void
}

function SettingsScreen({ accessibility, busy, diagnostic, draft, keepAlive, loadDiagnosticLog, loadHarnessLog, lastStop, overlayBall, overlayBallReadFailed, onDraftChange, page, runSelfCheck, runtime, settingsReadStatus, shizuku, onAuthorize, onBack, onClearAccessibilityPassword, onClearDiagnostic, onConnect, onDiagnosticSettings, onLaunch, onLaunchConfirmed, onOpenAccessibilitySettings, onOpenOverlaySettings, onOpenShizuku, onOpenEnvironment, onOpenDeviceShell, onReloadSettings, onRequestNotificationPermission, onResetAccessibilityPassword, onSave, onSaveAccessibilityPackages, onSaveAccessibilityPassword, onSetAccessibilityRuntime, onSetAutomationRuleEnabled, onShareDiagnostic }: SettingsScreenProps) {
  const [accessibilityDraft, setAccessibilityDraft] = useState(accessibility.allowedPackages.join('\n'))
  const [whitelistEnabledDraft, setWhitelistEnabledDraft] = useState(accessibility.whitelistEnabled ?? true)
  const [autoSaved, setAutoSaved] = useState(false)
  const [testingProvider, setTestingProvider] = useState<string | null>(null)
  const [connectivityResults, setConnectivityResults] = useState<Record<string, ModelConnectivityResult>>({})
  const autoSaveTimerRef = useRef<number | null>(null)
  const autoSavedTimerRef = useRef<number | null>(null)
  const latestDraftRef = useRef(draft)
  latestDraftRef.current = draft

  const triggerAutoSave = useCallback((delay = 500) => {
    if (autoSaveTimerRef.current !== null) {
      window.clearTimeout(autoSaveTimerRef.current)
    }
    autoSaveTimerRef.current = window.setTimeout(() => {
      autoSaveTimerRef.current = null
      const currentDraft = latestDraftRef.current
      if (currentDraft === null) return
      const currentSettings = { ...currentDraft.settings }
      delete currentSettings.overlayBallEnabled
      const currentOverlayBall = currentDraft.overlayBallEnabled
        ?? (overlayBallReadFailed ? currentDraft.settings.overlayBallEnabled : overlayBall?.enabled)
        ?? currentDraft.settings.overlayBallEnabled
        ?? false
      void onSave({
        ...currentSettings,
        ...(currentDraft.overlayBallEnabled === null ? {} : { overlayBallEnabled: currentOverlayBall }),
        ...(Object.keys(currentDraft.credentials).length === 0 ? {} : { providerApiKeys: currentDraft.credentials }),
        ...(currentDraft.clearedProviders.length === 0 ? {} : { clearProviderApiKeys: currentDraft.clearedProviders }),
        ...(Object.keys(currentDraft.customCredentials).length === 0 ? {} : { customProviderApiKeys: currentDraft.customCredentials }),
        ...(currentDraft.clearedCustomProviders.length === 0 ? {} : { clearCustomProviderApiKeys: currentDraft.clearedCustomProviders }),
      })
      triggerHaptic('light')
      setAutoSaved(true)
      if (autoSavedTimerRef.current !== null) window.clearTimeout(autoSavedTimerRef.current)
      autoSavedTimerRef.current = window.setTimeout(() => setAutoSaved(false), 2000)
    }, delay)
  }, [onSave, overlayBall?.enabled, overlayBallReadFailed])

  useEffect(() => {
    return () => {
      // Keep a pending save alive when the user switches settings pages. The draft is
      // owned by App, so cancelling here would silently drop a just-edited value.
      if (autoSavedTimerRef.current !== null) window.clearTimeout(autoSavedTimerRef.current)
    }
  }, [])
  /** 总开关的一次操作还在路上：按钮在这期间全部禁用，避免连点出两次动作。 */
  const [automationPending, setAutomationPending] = useState(false)
  /**
   * 免责说明的展示状态。
   *
   * `confirm` 带着触发它的那个动作：从「继续」进来的确认要把 `resume` 发回去，
   * 不能一律当成 `start`（总开关在「已暂停」时重新开与从未开过不是同一个动作）。
   * `read` 只是回看，不写任何状态、也不改总开关。
   */
  const [automationDisclaimer, setAutomationDisclaimer] = useState<
    { mode: 'confirm'; action: AccessibilityAutomationAction } | { mode: 'read' } | null
  >(null)
  /**
   * 本次会话的运行记录。
   *
   * `null` 表示**还没读到**（与「读到了但是空的」必须分开显示）；`failed` 表示这次读取失败。
   * 记录来自无障碍服务进程，服务被回收后从空开始，所以这里同时保留 `connected` 给界面说明。
   */
  const [automationRuns, setAutomationRuns] = useState<AccessibilityAutomationRuns | null>(null)
  const [automationRunsStatus, setAutomationRunsStatus] = useState<'idle' | 'loading' | 'failed'>('idle')
  /** 当前正在查看规则的应用；空串表示还没选。 */
  const [rulesPackage, setRulesPackage] = useState('')
  /** 规则读数；`null` 同样是「还没读到」，与「读到了但一条规则都没有」分开显示。 */
  const [automationRules, setAutomationRules] = useState<AccessibilityAutomationRules | null>(null)
  const [automationRulesStatus, setAutomationRulesStatus] = useState<'idle' | 'loading' | 'failed'>('idle')
  /** 正在对哪条规则做单条操作（开关 / 读回全文）；在途时只禁用这一条。 */
  const [automationRulePending, setAutomationRulePending] = useState<string | null>(null)
  /** 正在写入（保存 / 删除）；非空时表单与其它写入口一律禁用。 */
  const [automationRuleWriting, setAutomationRuleWriting] = useState<'save' | 'delete' | null>(null)
  /** 待确认删除的规则 id：删除是整条删除、删掉就没了，所以先问一次再动手。 */
  const [automationRuleConfirmDelete, setAutomationRuleConfirmDelete] = useState<string | null>(null)
  /** 规则写入 / 读回失败的原因；桥回来的文案原样展示，不吞也不改写。 */
  const [automationRuleError, setAutomationRuleError] = useState<string | null>(null)
  /**
   * 打开中的规则表单草稿。
   *
   * `create` 时 [rule] 为 `null`：新建的合法默认值由表单自己给。
   * `update` 时**必须**带着读回的整条规则——没有它就绝不打开表单（见 startEditRule）。
   */
  const [automationRuleDraft, setAutomationRuleDraft] = useState<AutomationRuleDraftState | null>(null)
  /**
   * 当前应用的自动化参数读数（按应用暂停开关 + 新建规则的默认值）。
   *
   * `null` 同样是「还没读到」，与「读到了」分开显示：读不到就一个输入框都不给，
   * 否则用户点一次保存就是用界面里的假数字覆盖盘上的真数据（写入是整条替换）。
   */
  const [automationAppConfig, setAutomationAppConfig] = useState<AccessibilityAutomationAppConfigDetail | null>(null)
  const [automationAppConfigStatus, setAutomationAppConfigStatus] = useState<'idle' | 'loading' | 'failed'>('idle')
  /** 参数写入是否在途；在途时表单禁用，避免两次整条写入叠在一起。 */
  const [automationAppConfigWriting, setAutomationAppConfigWriting] = useState(false)
  /** 参数读取 / 写入失败的原因；桥回来的文案原样展示，不吞也不改写。 */
  const [automationAppConfigError, setAutomationAppConfigError] = useState<string | null>(null)
  /**
   * 规则与每应用参数的读取属于同一次「选包」操作。代次和包名都放进 ref，
   * 这样换包后旧请求即使晚回来，也不能把旧应用的载荷写进当前表单。
   */
  const automationReadGeneration = useRef(0)
  const automationReadPackage = useRef('')
  /**
   * 保存白名单时要输入的验证密码。
   *
   * 它**只活在这个组件里**：验证密码是原生侧的事，前端既不持久化、也不放进设置草稿，
   * 保存成功或离开这一页就清空，避免留在内存里被后续请求带上。
   */
  const [whitelistPassword, setWhitelistPassword] = useState('')
  /** 「设置/修改验证密码」表单的临时内容；语义同 whitelistPassword，用完即清。 */
  const [passwordDraft, setPasswordDraft] = useState({ current: '', next: '', confirm: '' })
  /** 「设置/修改验证密码」表单的本地校验提示；原生侧的错误照旧走顶部提示。 */
  const [passwordMessage, setPasswordMessage] = useState<string | null>(null)
  /**
   * 提交「设置/修改验证密码」表单。
   *
   * 两次输入不一致、或长度与字符不合规时在前端就挡下来，省掉一次必然失败的桥调用；
   * 提交后立刻清空三个输入框：密码只是本次过桥用的临时输入，界面不留存
   * （原生侧也只保存盐与哈希，明文不落盘）。
   */
  const submitAccessibilityPassword = () => {
    if (passwordDraft.next !== passwordDraft.confirm) {
      setPasswordMessage(t("两次输入的新密码不一致"))
      return
    }
    try {
      // 复用平台层同一套规则，避免界面和桥各写一份长度与空白判定。
      validateAccessibilityPasswordInput(passwordDraft.next, t("新验证密码"))
    } catch (error) {
      setPasswordMessage(errorMessage(error))
      return
    }
    setPasswordMessage(null)
    onSaveAccessibilityPassword(passwordDraft.next, accessibility.passwordConfigured ? passwordDraft.current : undefined)
    setPasswordDraft({ current: '', next: '', confirm: '' })
  }
  /** 保存白名单：把当前输入框里的验证密码一起交出去（未设置密码时传 undefined）。 */
  const saveAccessibilityDraft = () => {
    const packages = accessibilityDraft.split(/[\n,]/u).map(value => value.trim()).filter(Boolean)
    const password = accessibility.passwordConfigured && whitelistPassword !== '' ? whitelistPassword : undefined
    onSaveAccessibilityPackages([...new Set(packages)], password, whitelistEnabledDraft)
    setWhitelistPassword('')
  }
  useEffect(() => {
    if (page !== 'automation') return
    setAccessibilityDraft(accessibility.allowedPackages.join('\n'))
    setWhitelistEnabledDraft(accessibility.whitelistEnabled ?? true)
    // 每次回到这一页都从空白开始：密码是临时输入，不回填、不残留。
    setWhitelistPassword('')
    setPasswordDraft({ current: '', next: '', confirm: '' })
  }, [accessibility.allowedPackages, accessibility.whitelistEnabled, page])

  /**
   * 读取运行记录。
   *
   * 只在「无障碍」这一页读，并且**读取失败要如实显示**：返回 `connected=false` 时列表必然为空，
   * 那个空列表不能当「什么都没发生」渲染（服务被回收后记录会从空开始，这是刻意的设计）。
   */
  const loadAutomationRuns = useCallback((packageName?: string) => {
    setAutomationRunsStatus('loading')
    void runtimeBridge.getAccessibilityAutomationRuns(packageName)
      .then(result => {
        setAutomationRuns(result)
        setAutomationRunsStatus('idle')
      })
      .catch(() => setAutomationRunsStatus('failed'))
  }, [])
  useEffect(() => {
    if (page !== 'automation') return
    // 总开关一变就重读：刚按下「开始」时最该看到的就是它是否真的连上了服务。
    loadAutomationRuns()
  }, [accessibility.automationRuntime, accessibility.serviceStatus, loadAutomationRuns, page])

  /** 真的把动作交给桥：在途期间按钮禁用，失败提示由 App 的统一 notify 负责。 */
  const applyAutomationRuntime = (action: AccessibilityAutomationAction): void => {
    setAutomationPending(true)
    void onSetAccessibilityRuntime(action).finally(() => setAutomationPending(false))
  }

  /**
   * 按一次总开关按钮。
   *
   * 「开始」与「继续」都是**让自动化重新跑起来**的动作，所以第一次按要先把免责说明摆出来，
   * 看过并勾选之后才真的去开（见 [confirmAutomationDisclaimer]）。暂停与紧急停止不拦：
   * 它们是收敛动作，不能让一段说明挡住急停——尤其是紧急停止，它要能一键生效。
   */
  const changeAutomationRuntime = (action: AccessibilityAutomationAction): void => {
    if ((action === 'start' || action === 'resume') && !isAutomationDisclaimerAcknowledged()) {
      setAutomationDisclaimer({ mode: 'confirm', action })
      return
    }
    applyAutomationRuntime(action)
  }

  /**
   * 确认免责说明后接着把刚才那个动作发出去。
   *
   * 确认本身先落盘；写不进去（无痕模式等）也照常往下走——只是下次点「开始」还会再弹一遍，
   * 这里不假装用户已确认。**不再过一遍 [changeAutomationRuntime]**：用户刚刚就在眼前确认过，
   * 而且写盘失败时再过闸门会把弹窗反复弹出来。
   */
  const confirmAutomationDisclaimer = (): void => {
    const action = automationDisclaimer?.mode === 'confirm' ? automationDisclaimer.action : 'start'
    acknowledgeAutomationDisclaimer()
    setAutomationDisclaimer(null)
    applyAutomationRuntime(action)
  }

  /**
   * 读取某个包的规则。
   *
   * 与运行记录分开：规则是落盘的、跨进程重启还在，所以这里读不到的原因只有三种——
   * 没选包、桥调用失败、数据损坏（`corrupt` 非空，原生已把它清掉）。三种都要如实显示。
   */
  const loadAutomationRules = useCallback((packageName: string, generation: number) => {
    if (packageName === '') return
    setAutomationRulesStatus('loading')
    void runtimeBridge.getAccessibilityAutomationRules(packageName)
      .then(result => {
        if (automationReadGeneration.current !== generation
          || automationReadPackage.current !== packageName) return
        if (result.packageName !== packageName) throw new Error('规则返回的应用与请求不一致')
        setAutomationRules(result)
        setAutomationRulesStatus('idle')
      })
      .catch(() => {
        if (automationReadGeneration.current !== generation || automationReadPackage.current !== packageName) return
        setAutomationRules(null)
        setAutomationRulesStatus('failed')
      })
  }, [])
  /**
   * 读取某个应用的自动化参数。
   *
   * 与 [loadAutomationRules] 同构，但**不设 rulesPackage**（由调用方设）：这份读数是「当前查看的
   * 应用」的附属读数，换包必须跟着规则一起换。开读前先清掉上一份读数——参数表单在换包时会按 `key`
   * 重建，留着上一份读数就等于拿上一个应用的数字给新表单当初始值，那正是这里最不能犯的错。
   */
  const loadAutomationAppConfig = useCallback((packageName: string, generation: number) => {
    if (packageName === '') return
    setAutomationAppConfigStatus('loading')
    void runtimeBridge.getAccessibilityAutomationAppConfig({ packageName })
      .then(result => {
        if (automationReadGeneration.current !== generation
          || automationReadPackage.current !== packageName) return
        if (result.packageName !== packageName) throw new Error('参数返回的应用与请求不一致')
        setAutomationAppConfig(result)
        setAutomationAppConfigStatus('idle')
      })
      .catch(error => {
        if (automationReadGeneration.current !== generation || automationReadPackage.current !== packageName) return
        setAutomationAppConfig(null)
        setAutomationAppConfigStatus('failed')
        setAutomationAppConfigError(errorMessage(error))
      })
  }, [])
  /**
   * 选一个应用：规则与参数两份读数一起换。
   *
   * 合成一个入口是刻意的：两份读数都属于「当前查看的应用」，分开调就会出现「规则已经是新包、
   * 参数还写着旧包」的一瞬间，而那一瞬间刚好是用户会照着填默认值的时候。
   */
  const selectRulesPackage = useCallback((packageName: string): void => {
    if (packageName === '') return
    const generation = automationReadGeneration.current + 1
    automationReadGeneration.current = generation
    automationReadPackage.current = packageName
    setRulesPackage(packageName)
    setAutomationRules(null)
    setAutomationRulesStatus('loading')
    setAutomationAppConfig(null)
    setAutomationAppConfigStatus('loading')
    setAutomationAppConfigError(null)
    loadAutomationRules(packageName, generation)
    loadAutomationAppConfig(packageName, generation)
  }, [loadAutomationAppConfig, loadAutomationRules])
  useEffect(() => {
    // 刚进这一页且白名单里已有目标应用时，先把第一个包的两份读数读出来，省掉一次点击。
    if (page !== 'automation' || rulesPackage !== '' || automationReadPackage.current !== '') return
    const first = accessibility.allowedPackages.find(name => !accessibility.alwaysAllowedPackages.includes(name))
    if (first !== undefined) selectRulesPackage(first)
  }, [accessibility.alwaysAllowedPackages, accessibility.allowedPackages, page, rulesPackage, selectRulesPackage])

  /** 开/关一条规则：在途只禁用这一条，其余仍可操作；写完重读整包读数（不做乐观更新）。 */
  const toggleAutomationRule = (ruleId: string, enabled: boolean): void => {
    if (rulesPackage === '') return
    setAutomationRulePending(ruleId)
    void onSetAutomationRuleEnabled({ packageName: rulesPackage, ruleId, enabled })
      .finally(() => {
        setAutomationRulePending(null)
        selectRulesPackage(rulesPackage)
      })
  }

  /**
   * 「规则」这一块的其它写入口是否要禁用。
   *
   * 表单开着、一次写入还在路上、有规则正在读回或开关、有待确认的删除——任一成立就锁住，
   * 免得两个写操作同时在途：它们返回的都是整包读数，后回来的那份说了算，先回来的写入看起来就像没发生。
   */
  const automationRuleLocked = busy !== null || automationRuleWriting !== null || automationRuleDraft !== null
    || automationRulePending !== null || automationRuleConfirmDelete !== null || automationAppConfigWriting

  /**
   * 打开「新建规则」表单。
   *
   * 三个数字初值用当前读到的「每应用参数」；没读到就不传，由表单回落到原生默认值（1 / 3000 / 0）。
   * 那是**表单初值**的回落，与「把一份没读到的读数当真相显示给用户看」不是一回事：
   * 这里没有展示任何读数，用户看到的始终是自己填进输入框的数字。
   */
  const startCreateRule = (): void => {
    if (rulesPackage === '') return
    setAutomationRuleError(null)
    setAutomationRuleDraft({
      mode: 'create',
      rule: null,
      defaults: automationAppConfig === null ? undefined : {
        maxActions: automationAppConfig.config.defaultMaxActions,
        actionCoolDownMs: automationAppConfig.config.defaultActionCoolDownMs,
        matchDelayMs: automationAppConfig.config.defaultMatchDelayMs,
      },
    })
  }

  /**
   * 打开「编辑规则」表单。
   *
   * **必须先把整条规则读回来**：原生侧的 `update` 是整条替换，没提交的字段不会保持原样，
   * 所以读不回来就绝不打开表单——尤其不能打开一张空的，那等于用户点一次保存就把原来的选择器整片抹掉。
   * 失败时只把桥回来的原因写在卡片里（`role="alert"`），没有任何输入框出现。
   */
  const startEditRule = (ruleId: string): void => {
    if (rulesPackage === '') return
    setAutomationRuleError(null)
    setAutomationRulePending(ruleId)
    void runtimeBridge.getAccessibilityAutomationRule({ packageName: rulesPackage, ruleId })
      .then(detail => setAutomationRuleDraft({ mode: 'update', rule: detail.rule }))
      .catch(error => setAutomationRuleError(errorMessage(error)))
      .finally(() => setAutomationRulePending(null))
  }

  /** 关闭表单：连失败提示一起清掉，免得下次打开还挂着上一次的原因。 */
  const closeAutomationRuleForm = (): void => {
    setAutomationRuleDraft(null)
    setAutomationRuleError(null)
  }

  /**
   * 保存规则（新建或编辑）。
   *
   * 用桥回来的**整包读数**直接刷新列表，不再补一次读取：那一次读可能读到的又是旧的，
   * 界面就会短暂显示一份和刚写进去的内容不一致的列表。
   */
  const saveAutomationRule = (rule: AccessibilityAutomationRuleInput): void => {
    if (automationRuleDraft === null || rulesPackage === '') return
    setAutomationRuleWriting('save')
    setAutomationRuleError(null)
    void runtimeBridge.saveAccessibilityAutomationRule({
      packageName: rulesPackage,
      mode: automationRuleDraft.mode,
      rule,
    })
      .then(result => {
        if (automationReadPackage.current !== rulesPackage) return
        if (result.packageName !== rulesPackage) throw new Error('规则返回的应用与请求不一致')
        setAutomationRules(result)
        setAutomationRuleDraft(null)
      })
      .catch(error => setAutomationRuleError(errorMessage(error)))
      .finally(() => setAutomationRuleWriting(null))
  }

  /**
   * 删除一条规则；调用方保证已经二次确认过。
   *
   * 失败只留下原因，列表保持原样：本地先删掉再回滚会让「到底删没删」变得看不清，
   * 而这里唯一可信的答案就是桥回来的那份读数。
   */
  const deleteAutomationRule = (ruleId: string): void => {
    if (rulesPackage === '') return
    setAutomationRuleWriting('delete')
    setAutomationRuleError(null)
    void runtimeBridge.deleteAccessibilityAutomationRule({ packageName: rulesPackage, ruleId })
      .then(result => {
        if (automationReadPackage.current !== rulesPackage) return
        if (result.packageName !== rulesPackage) throw new Error('规则返回的应用与请求不一致')
        setAutomationRules(result)
        setAutomationRuleConfirmDelete(null)
      })
      .catch(error => setAutomationRuleError(errorMessage(error)))
      .finally(() => setAutomationRuleWriting(null))
  }

  /**
   * 保存这个应用的自动化参数。
   *
   * 整条四个字段一起交给原生（写入是整条替换，缺字段会被拒收），且**不做乐观更新**：
   * 界面显示的是「写完之后」返回的那份读数。界面自己拼一份「应该是什么」，只会在写入被拒时
   * 留下一屏假数字；失败时只留下原因，表单里的草稿一个字都不动。
   */
  const saveAutomationAppConfig = (config: AccessibilityAutomationAppConfig): void => {
    if (rulesPackage === '') return
    setAutomationAppConfigWriting(true)
    setAutomationAppConfigError(null)
    void runtimeBridge.setAccessibilityAutomationAppConfig({ packageName: rulesPackage, ...config })
      .then(result => {
        if (automationReadPackage.current !== rulesPackage) return
        if (result.packageName !== rulesPackage) throw new Error('参数返回的应用与请求不一致')
        setAutomationAppConfig(result)
        setAutomationAppConfigStatus('idle')
      })
      .catch(error => setAutomationAppConfigError(errorMessage(error)))
      .finally(() => setAutomationAppConfigWriting(false))
  }

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
   * 同时触发轻量防抖自动保存，并在移动端触发触觉反馈。
   */
  const setDraft = (next: RuntimeSettings): void => {
    onDraftChange(current => ({ ...current, settings: next }))
    triggerAutoSave(500)
  }
  const setOverlayBallDraft = (next: boolean): void => {
    triggerHaptic('medium')
    onDraftChange(current => ({ ...current, overlayBallEnabled: next }))
    triggerAutoSave(300)
  }
  const setSelectedProvider = (next: ModelProviderId | 'custom'): void =>
    // 只是切换正在查看的供应商，没有改动任何字段值：不算「未保存的输入」。
    onDraftChange(current => ({ ...current, selectedProvider: next }), false)
  const setCredentialDrafts = (update: (current: ProviderApiKeys) => ProviderApiKeys): void => {
    onDraftChange(current => ({ ...current, credentials: update(current.credentials) }))
    triggerAutoSave(800)
  }
  const setClearedProviders = (update: (current: ModelProviderId[]) => ModelProviderId[]): void => {
    triggerHaptic('medium')
    onDraftChange(current => ({ ...current, clearedProviders: update(current.clearedProviders) }))
    triggerAutoSave(300)
  }
  const setCustomCredentials = (next: Record<string, string>): void => {
    onDraftChange(current => ({ ...current, customCredentials: next }))
    triggerAutoSave(800)
  }
  const setClearedCustomProviders = (next: string[]): void => {
    triggerHaptic('medium')
    onDraftChange(current => ({ ...current, clearedCustomProviders: next }))
    triggerAutoSave(300)
  }

  // 只为用户主动修改的开关保留草稿；原生菜单关闭悬浮球时，不影响页内其他未保存内容。
  const overlayBallEnabled = overlayBallDraft
    ?? (overlayBallReadFailed ? settings.overlayBallEnabled : overlayBall?.enabled)
    ?? settings.overlayBallEnabled
    ?? false
  const overlayPermissionKnown = overlayBall !== null && !overlayBallReadFailed

  const shizukuLabel = !shizuku.installed
    ? t("未安装")
    : !shizuku.running
      ? t("未运行")
      : shizuku.permission === 'granted'
        ? shizuku.connected ? t("已连接") : t("已授权")
        : shizuku.permission === 'denied'
          ? t("已拒绝")
          : t("待授权")
  // 两种版本必须分开显示：`version` 是服务端 API 版本（13.x 的 Shizuku 应用上报的都是 13），
  // `appVersion` 才是已安装应用的版本名（13.6.0 这种）。以前只显示前者，
  // 用户更新 Shizuku 后会以为「版本识别错了」（真机反馈）。
  const shizukuVersions = [
    shizuku.appVersion ? t("Shizuku 应用 {0}", shizuku.appVersion) : '',
    shizuku.version ? t("服务端 API {0}", shizuku.version) : '',
  ].filter(value => value !== '').join(' · ')
  const selectedProviderOption = MODEL_PROVIDERS.find(provider => provider.id === selectedProvider) ?? MODEL_PROVIDERS[0]
  const keepAliveRecordedAt = formatRecordedAt(keepAlive.lastUpdatedAtMillis)
  const lastStopLabel = t(LAST_STOP_LABELS[lastStop])
  const lastRunLabel = keepAlive.lastPhase === undefined
    ? t("从未记录")
    : keepAliveRecordedAt === ''
      ? t(PHASE_META[keepAlive.lastPhase].label)
      : `${t(PHASE_META[keepAlive.lastPhase].label)} · ${keepAliveRecordedAt}`
  const selectedProviderConfigured = selectedProvider !== 'custom' && (
    settings.harnessConfiguredModelProviders?.includes(selectedProvider) === true
    || (
      (settings.configuredModelProviders.includes(selectedProvider) || credentialDrafts[selectedProvider] !== undefined)
      && !clearedProviders.includes(selectedProvider)
    )
  )


  const handleTestConnectivity = async (providerId: string) => {
    triggerHaptic('light')
    setTestingProvider(providerId)
    try {
      const result = await runtimeBridge.testModelConnectivity({
        providerId,
        apiKey: credentialDrafts[providerId as ModelProviderId],
      })
      setConnectivityResults(current => ({ ...current, [providerId]: result }))
      if (result.ok) triggerHaptic('success')
      else triggerHaptic('warning')
    } catch (error) {
      setConnectivityResults(current => ({
        ...current,
        [providerId]: { ok: false, status: 0, latencyMs: 0, message: errorMessage(error) },
      }))
      triggerHaptic('error')
    } finally {
      setTestingProvider(null)
    }
  }

  const handleTestCustomConnectivity = async (provider: CustomModelProvider, draftKey?: string): Promise<ModelConnectivityResult> => {
    triggerHaptic('light')
    try {
      const result = await runtimeBridge.testModelConnectivity({
        providerId: provider.id,
        baseUrl: provider.baseUrl,
        apiKey: draftKey,
        api: provider.api,
      })
      if (result.ok) triggerHaptic('success')
      else triggerHaptic('warning')
      return result
    } catch (error) {
      triggerHaptic('error')
      return { ok: false, status: 0, latencyMs: 0, message: errorMessage(error) }
    }
  }

  const saveDraft = (): void => {
    if (autoSaveTimerRef.current !== null) {
      window.clearTimeout(autoSaveTimerRef.current)
      autoSaveTimerRef.current = null
    }
    // 悬浮球是原生侧可被菜单直接修改的独立偏好。只有用户在本页实际拨动开关时，
    // 才把它作为更新字段发送；否则省略该字段，让原生侧保留菜单刚写入的值。
    const settingsWithoutOverlayBall = { ...settings }
    delete settingsWithoutOverlayBall.overlayBallEnabled
    void onSave({
      ...settingsWithoutOverlayBall,
      ...(overlayBallDraft === null ? {} : { overlayBallEnabled }),
      ...(Object.keys(credentialDrafts).length === 0 ? {} : { providerApiKeys: credentialDrafts }),
      ...(clearedProviders.length === 0 ? {} : { clearProviderApiKeys: clearedProviders }),
      ...(Object.keys(customCredentials).length === 0 ? {} : { customProviderApiKeys: customCredentials }),
      ...(clearedCustomProviders.length === 0 ? {} : { clearCustomProviderApiKeys: clearedCustomProviders }),
    })
    triggerHaptic('success')
    setAutoSaved(true)
    if (autoSavedTimerRef.current !== null) window.clearTimeout(autoSavedTimerRef.current)
    autoSavedTimerRef.current = window.setTimeout(() => setAutoSaved(false), 2000)
  }

  return (
    <div className="screen settings-screen">
      <div className="screen-heading management-heading">
        <div>
          <p className="eyebrow">{t("应用管理")}</p>
          <div className="heading-title-row">
            <h1>{t(SETTINGS_PAGE_META[page].title)}</h1>
            {autoSaved && (
              <span className="auto-save-pill success" role="status">
                <Check size={13} /> {t("已自动保存")}
              </span>
            )}
            {busy === 'save-settings' && (
              <span className="auto-save-pill saving" role="status">
                <Loader2 size={13} className="spin" /> {t("保存中...")}
              </span>
            )}
          </div>
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
            <div><h2 id="model-settings">{t("模型供应商")}</h2><p>{t("密钥在本机加密保存，供 Harness 连接模型服务使用；保存后不再显示密钥内容。")}</p></div>
          </div>
          {!hasConfiguredModelCredential(settings) && (
            /*
             * 应用只读取 Harness 凭据文件中的白名单配置状态，不读取密钥内容，也无法识别
             * 用户自行维护的其它来源。因此保留显式放行入口，避免这类用户被永久挡在门外。
             */
            <div className="inline-alert warning" role="alert">
              <AlertTriangle size={19} />
              <div>
                <strong>{t("还没有可用的模型凭据")}</strong>
                <span>{t("没有密钥时每一轮对话都会失败，因此应用不会打开 Harness；保存一次 API Key 即可。若状态尚未同步，或凭据来自 Harness 的其他来源，可以直接打开。")}</span>
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
                const configured = settings.harnessConfiguredModelProviders?.includes(provider.id) === true
                  || ((settings.configuredModelProviders.includes(provider.id) || credentialDrafts[provider.id] !== undefined)
                    && !clearedProviders.includes(provider.id))
                return <option key={provider.id} value={provider.id}>{provider.label}{configured ? t("（已配置）") : ''}</option>
              })}
              <option value="custom">{t('自定义')}</option>
            </select>
          </label>
          {/* 状态一览只负责反馈配置结果；供应商下拉框是唯一的编辑入口，避免同一动作出现两套入口。 */}
          <div className="provider-status" role="list" aria-label={t("供应商状态")}>
            {MODEL_PROVIDERS.map(provider => {
              const inHarness = settings.harnessConfiguredModelProviders?.includes(provider.id) === true
              const draft = credentialDrafts[provider.id] !== undefined
              const inApp = (settings.configuredModelProviders.includes(provider.id) || draft)
                && !clearedProviders.includes(provider.id)
              const clearing = clearedProviders.includes(provider.id)
              const status = inApp
                ? (draft ? t("待保存") : t("已配置"))
                : clearing ? t("保存后清除")
                  : inHarness ? t("已在 Harness 中配置") : t("未配置")
              return (
                <div className={`provider-status-row${selectedProvider === provider.id ? ' is-selected' : ''}`} role="listitem" key={provider.id}>
                  <span className="provider-status-name">{provider.label}</span>
                  <span className={`provider-status-badge${inApp || inHarness ? ' is-configured' : ''}`}>{status}</span>
                  {selectedProvider === provider.id && <span className="provider-status-current">{t("正在编辑")}</span>}
                </div>
              )
            })}
          </div>
          {selectedProvider !== 'custom' && <><label className="field">
            <span>{selectedProviderOption.label} API Key · {selectedProviderOption.environmentVariable}</span>
            <input
              type="password"
              autoComplete="new-password"
              spellCheck={false}
              maxLength={200}
              placeholder={settings.harnessConfiguredModelProviders?.includes(selectedProvider) === true
                && (!settings.configuredModelProviders.includes(selectedProvider) || clearedProviders.includes(selectedProvider))
                ? t("已在 Harness 中配置，留空保持不变")
                : selectedProviderConfigured ? t("已配置，留空保持不变") : t("输入 API Key")}
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
          <div className="credential-actions">
            <button
              className="button button-secondary compact-button"
              type="button"
              disabled={testingProvider === selectedProvider}
              onClick={() => void handleTestConnectivity(selectedProvider)}
            >
              {testingProvider === selectedProvider ? <Loader2 className="spin" size={16} /> : <Wifi size={16} />}
              {testingProvider === selectedProvider ? t("正在测试…") : t("测试连接")}
            </button>
            {(settings.configuredModelProviders.includes(selectedProvider) || clearedProviders.includes(selectedProvider)) && (
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
            )}
          </div>
          {connectivityResults[selectedProvider] && (
            <div className={`connectivity-result ${connectivityResults[selectedProvider].ok ? 'success' : 'error'}`}>
              {connectivityResults[selectedProvider].message}
            </div>
          )}</>}
          {selectedProvider === 'custom' && <CustomProviders
            providers={settings.customModelProviders ?? []}
            configured={settings.configuredCustomModelProviders ?? []}
            harnessConfigured={settings.harnessConfiguredCustomModelProviders ?? []}
            credentials={customCredentials}
            cleared={clearedCustomProviders}
            onChange={providers => setDraft({ ...settings, customModelProviders: providers })}
            onCredentials={setCustomCredentials}
            onClear={setClearedCustomProviders}
            onTestConnectivity={handleTestCustomConnectivity}
          />}
        </section>
        )}

        {page === 'runtime' && (
        <section className="settings-section" aria-labelledby="download-settings">
          <div className="section-title">
            <span className="section-icon"><CloudDownload size={19} /></span>
            <div><h2 id="download-settings">{t("运行时与设备 Shell")}</h2><p>{t("装、启停、重置在「Ubuntu 运行时」；Shizuku 授权与 AI Shell 在「设备 Shell 设置」。")}</p></div>
          </div>
          <div className="settings-inline-actions">
            <button className="button button-secondary" type="button" onClick={onOpenEnvironment} disabled={busy !== null}>
              <CloudDownload size={18} />{t("Ubuntu 运行时")}
            </button>
            <button className="button button-secondary" type="button" onClick={onOpenDeviceShell} disabled={busy !== null}>
              <SquareTerminal size={18} />{t("设备 Shell 设置")}
            </button>
          </div>
          <p className="settings-note">{t("运行环境使用 APK 内置的受校验版本；需要其它版本时请在 Ubuntu 运行时的版本管理中下载并切换。")}</p>
        </section>
        )}

        {page === 'terminal' && (
        <section className="settings-section" aria-labelledby="terminal-settings">
          <div className="section-title">
            <span className="section-icon"><SquareTerminal size={19} /></span>
            <div><h2 id="terminal-settings">{t("界面")}</h2><p>{t("整个应用壳子的字号与显示方式")}</p></div>
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

        {/* 外观（登记册 5.4）：落在「主题」页——登记册指定的现成落点。
            组件无 props，主题的存储与 DOM 落地全在 src/theme.ts，这里只负责挂载。 */}
        {page === 'terminal' && <AppearanceSettings />}

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
                  ? t("在其他应用上层显示悬浮球，点按展开 AI 对话小窗")
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
            {t("后台保持可以降低应用被系统结束的概率，但无法阻止所有电池优化或内存清理。进程停止后，需要重新连接 Harness。")}
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
                <span>{t("应用进程已被系统结束，当前连接已失效。请重新连接以启动 Harness。")}</span>
              </div>
            </div>
          )}
        </section>
        )}

        {page === 'device-shell' && (
        <section className="settings-section" aria-labelledby="shizuku-settings">
          <div className="section-title section-title-action">
            <span className="section-icon"><Smartphone size={19} /></span>
            <div><h2 id="shizuku-settings">Shizuku</h2><p>{t("设备 Shell ·")}{shizukuLabel}{shizukuVersions === '' ? '' : ` · ${shizukuVersions}`}</p></div>
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
            {t("Shizuku 提供设备命令和文件操作能力，权限取决于其启动模式。未安装、未授权或断开时，设备工具不可用，不影响 Ubuntu 终端与 Harness。")}
          </p>
          <DeviceShellSettings bridge={runtimeBridge} disabled={busy !== null} />
        </section>
        )}

        {/*
          自动化设置：无障碍与副屏是同一条能力的两条腿（谁去读界面、画面从哪来），
          所以合并进同一页，不再各占一个入口。
        */}
        {page === 'automation' && (
        <>
          <div className="settings-subsection" aria-labelledby="accessibility-automation-settings">
            <div className="section-title section-title-action">
              <span className="section-icon"><Bot size={19} /></span>
              <div><h3 id="accessibility-automation-settings">{t("无障碍应用自动化")}</h3><p>{t("读取允许的应用界面并执行受控动作")}</p></div>
              <span className={`status-chip ${accessibility.enabled ? 'success' : ''}`}>{accessibility.enabled ? t("服务已开启") : t("需要手动开启")}</span>
            </div>
            <p className="settings-note">
              {t("开启白名单限制时仅允许列表内的应用；关闭后可操作未加入名单的普通应用。锁屏、系统设置及涉及权限、支付、验证码和密码的页面仍受保护；服务必须由你在系统无障碍设置中手动开启。")}
            </p>
            <div className="settings-subsection" aria-labelledby="accessibility-automation-console">
              <div className="section-title section-title-action">
                <span className="section-icon"><Play size={19} /></span>
                <div><h3 id="accessibility-automation-console">{t("自动化总开关")}</h3><p>{t("停到你再明确打开为止")}</p></div>
                <span className={`status-chip ${accessibility.automationRuntime === 'running' ? 'success' : ''}`} data-testid="automation-runtime-chip">
                  {automationRuntimeLabel(accessibility.automationRuntime)}
                </span>
              </div>
              <p className="settings-note">
                {t("总开关只在这里改：AI 侧没有开启自动化的路径，总开关不是「运行中」时写规则的命令会被拒绝。暂停与紧急停止都不会关掉系统里的无障碍服务，也不清空白名单与规则。")}
              </p>
              <div className="button-row">
                <button className="button button-primary" type="button" disabled={busy !== null || automationPending || accessibility.automationRuntime === 'running'}
                  onClick={() => changeAutomationRuntime('start')}>
                  {automationPending ? <Loader2 className="spin" size={18} /> : <Play size={18} />}{t("开始")}</button>
                <button className="button button-secondary" type="button" disabled={busy !== null || automationPending || accessibility.automationRuntime !== 'running'}
                  onClick={() => changeAutomationRuntime('pause')}>
                  <Square size={18} />{t("暂停")}</button>
                <button className="button button-secondary" type="button" disabled={busy !== null || automationPending || accessibility.automationRuntime !== 'paused'}
                  onClick={() => changeAutomationRuntime('resume')}>
                  <Play size={18} />{t("继续")}</button>
                <button className="button button-secondary" type="button" disabled={busy !== null || automationPending || accessibility.automationRuntime === 'off'}
                  onClick={() => changeAutomationRuntime('emergency_stop')}>
                  <AlertTriangle size={18} />{t("紧急停止")}</button>
              </div>
              {/*
                服务状态与总开关是两件事，必须分开说：`enabled_disconnected` 是「系统里开着、
                服务实例不在」，这时候只能请用户去系统设置里重新开关一次，界面不能假装已连接。
              */}
              <p className="settings-note" role="status" data-testid="automation-service-status">
                {accessibilityServiceLabel(accessibility.serviceStatus)}
                {accessibility.serviceStatus === 'connected'
                  ? t("：服务在跑，总开关「运行中」时才会执行规则里的动作。")
                  : t("：需要你在系统无障碍设置里手动开启（或重新开关一次），应用内无法代劳。")}
              </p>
              {accessibility.serviceStatus !== 'connected' && (
                <button className="button button-secondary" type="button" onClick={onOpenAccessibilitySettings} disabled={busy !== null}>
                  <ExternalLink size={18} />{t("打开系统无障碍设置")}
                </button>
              )}
              {/*
                回看入口不在这里：免责说明在这一页末尾只占一条（见页面底部），
                免得同一条说明有两个入口。
              */}
            </div>
            {/*
              免责说明弹窗：确认模式由「开始 / 继续」触发，回看模式由上面的入口触发。
              放在控制台这一块里，读代码时「谁把它弹出来的」一目了然。
            */}
            {automationDisclaimer !== null && (
              <AutomationDisclaimer
                mode={automationDisclaimer.mode}
                onConfirm={confirmAutomationDisclaimer}
                onCancel={() => setAutomationDisclaimer(null)}
              />
            )}
            {/*
              任务台账的可见面：AI 按步骤执行时的当前任务，以及人叫停它的那一下。
              位置就是阅读顺序：现在能不能动（总开关）→ 正在动什么（本卡）→ 刚才动过什么（运行记录）。
            */}
            <AutomationTaskCard />
            <div className="settings-subsection" aria-labelledby="accessibility-automation-runs">
              <div className="section-title section-title-action">
                <span className="section-icon"><ScrollText size={19} /></span>
                <div><h3 id="accessibility-automation-runs">{t("本次会话的运行记录")}</h3><p>{t("只保留无障碍服务本次运行期间的数据")}</p></div>
                <button className="button button-secondary" type="button" onClick={() => loadAutomationRuns()} disabled={automationRunsStatus === 'loading'}>
                  {automationRunsStatus === 'loading' ? <Loader2 className="spin" size={18} /> : <RefreshCw size={18} />}{t("刷新")}</button>
              </div>
              <p className="settings-note">
                {t("记录只在无障碍服务本次运行期间存在，服务被系统回收后会从空开始——这里为空不代表没有发生过动作。记录里只有受控码与规则标识，不含界面文字、不含截图。")}
              </p>
              {automationRunsStatus === 'failed' && <p className="settings-note" role="alert">{t("运行记录读取失败，请稍后重试")}</p>}
              {automationRunsStatus === 'loading' && automationRuns === null && <p className="settings-note" role="status">{t("正在读取运行记录")}</p>}
              {automationRuns !== null && !automationRuns.connected && (
                <p className="settings-note" role="status" data-testid="automation-runs-disconnected">
                  {t("无障碍服务当前没连上，因此读不到运行记录。")}
                </p>
              )}
              {automationRuns !== null && automationRuns.connected && automationRuns.runs.length === 0 && (
                <p className="settings-note" role="status">{t("服务已连接，还没有运行记录。")}</p>
              )}
              {automationRuns !== null && automationRuns.dropped > 0 && (
                <p className="settings-note">{t("更早的 {0} 条记录已超出容量被丢弃。", automationRuns.dropped)}</p>
              )}
              {automationRuns !== null && automationRuns.blocked.length > 0 && (
                <div className="settings-note" data-testid="automation-blocked-reasons">
                  <strong>{t("拦截原因（本次会话累计）")}</strong>
                  <ul>
                    {automationRuns.blocked.map(reason => (
                      <li key={reason.code}>
                        {reason.message ?? reason.code}
                        <span>{t("（{0}）", reason.code)}</span>
                        <span>{t("× {0} 次", reason.count)}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              {automationRuns !== null && automationRuns.runs.length > 0 && (
                <ul className="settings-note" data-testid="automation-run-list">
                  {automationRuns.runs.slice(0, 20).map((run, index) => (
                    <li key={`${run.atMs}-${run.ruleId}-${run.code}-${index}`}>
                      <span>{formatElapsedSinceBoot(run.atMs)}</span>
                      <span>{run.ruleId}</span>
                      <span>{automationRunLabel(run)}</span>
                      <span>{run.performed ? t("已执行") : t("未执行")}</span>
                      {run.degraded && <span>{t("降级路径")}</span>}
                      {!run.ok && <span>{t("失败")}</span>}
                    </li>
                  ))}
                </ul>
              )}
              {automationRuns !== null && automationRuns.runs.length > 20 && (
                <p className="settings-note">{t("只显示最近 20 条，共 {0} 条。", automationRuns.runs.length)}</p>
              )}
            </div>
            <div className="settings-subsection" aria-labelledby="accessibility-automation-rules">
              <div className="section-title section-title-action">
                <span className="section-icon"><ListChecks size={19} /></span>
                <div><h3 id="accessibility-automation-rules">{t("自动化规则")}</h3><p>{t("按应用查看，逐条启用或停用")}</p></div>
                {rulesPackage !== '' && (
                  <button className="button button-secondary" type="button"
                    onClick={() => selectRulesPackage(rulesPackage)} disabled={automationRuleLocked || automationRulesStatus === 'loading'}>
                    {automationRulesStatus === 'loading' ? <Loader2 className="spin" size={18} /> : <RefreshCw size={18} />}{t("刷新")}
                  </button>
                )}
                {/* 新建入口不依赖「已经有没有规则」：一条规则都没有时它才是最该看到的按钮。 */}
                {rulesPackage !== '' && (
                  <button className="button button-secondary" type="button" data-testid="automation-rule-create"
                    onClick={startCreateRule} disabled={automationRuleLocked || automationRulesStatus === 'loading'}>
                    <Plus size={18} />{t("新建规则")}
                  </button>
                )}
              </div>
              <p className="settings-note">
                {t("规则由 AI 生成、你在写盘前确认；这里只改「启不启用」，不改规则内容。停用一条规则不会删掉它。")}
              </p>
              {accessibility.allowedPackages.filter(name => !accessibility.alwaysAllowedPackages.includes(name)).length === 0 ? (
                <p className="settings-note" role="status" data-testid="automation-rules-no-packages">
                  {t("白名单里还没有其他应用：先在上面加入目标应用，规则才有归属。")}
                </p>
              ) : (
                <div className="settings-note" data-testid="automation-rule-packages">
                  {accessibility.allowedPackages
                    .filter(name => !accessibility.alwaysAllowedPackages.includes(name))
                    .map(name => (
                      <button key={name} className="button button-secondary" type="button"
                        onClick={() => selectRulesPackage(name)} disabled={automationRuleLocked}
                        aria-pressed={rulesPackage === name}>
                        {name}
                      </button>
                    ))}
                </div>
              )}
              {automationRulesStatus === 'failed' && <p className="settings-note" role="alert">{t("规则读取失败，请稍后重试")}</p>}
              {automationRulesStatus === 'loading' && automationRules === null && (
                <p className="settings-note" role="status">{t("正在读取规则")}</p>
              )}
              {automationRules !== null && automationRules.corrupt !== null && (
                <p className="settings-note" role="alert" data-testid="automation-rules-corrupt">
                  {t("这个应用的规则数据不可用，已被清除：{0}（{1}）。需要重新生成规则。",
                    automationRules.corrupt.message, automationRules.corrupt.code)}
                </p>
              )}
              {automationRules !== null && automationRules.corrupt === null && !automationRules.allowed && (
                <p className="settings-note" role="status" data-testid="automation-rules-not-allowed">
                  {t("{0} 不在白名单里：这些规则不会被任何事件用到。", automationRules.packageName)}
                </p>
              )}
              {automationRules !== null && automationRules.corrupt === null && automationRules.allowed
                && automationRules.rules.length === 0 && (
                <p className="settings-note" role="status">{t("这个应用还没有规则。")}</p>
              )}
              {automationRules !== null && automationRules.rules.length > 0 && (
                <ul className="settings-note" data-testid="automation-rule-list">
                  {automationRules.rules.map(rule => (
                    <li key={rule.id}>
                      <span>{rule.id}</span>
                      <span>{rule.actionKind}</span>
                      <span>{t("{0} 个选择器", rule.selectorCount)}</span>
                      <span>{t("最多 {0} 次", rule.maxActions)}</span>
                      <span>{rule.enabled ? t("已启用") : t("已停用")}</span>
                      <button className="button button-secondary" type="button"
                        onClick={() => toggleAutomationRule(rule.id, !rule.enabled)}
                        disabled={automationRuleLocked || !automationRules.allowed}>
                        {automationRulePending === rule.id
                          ? <Loader2 className="spin" size={18} />
                          : rule.enabled ? t("停用") : t("启用")}
                      </button>
                      {/*
                        「编辑」只发起一次读回（startEditRule）：原生 update 是整条替换，
                        读不回全文就打不开表单——打开一张空的，用户一保存就把选择器整片抹掉了。
                      */}
                      <button className="button button-secondary" type="button"
                        aria-label={t("编辑规则 {0}", rule.id)}
                        onClick={() => startEditRule(rule.id)}
                        disabled={automationRuleLocked}>
                        {automationRulePending === rule.id
                          ? <Loader2 className="spin" size={18} />
                          : <Pencil size={18} />}{t("编辑")}
                      </button>
                      <button className="button button-secondary" type="button"
                        aria-label={t("删除规则 {0}", rule.id)}
                        onClick={() => setAutomationRuleConfirmDelete(rule.id)}
                        disabled={automationRuleLocked}>
                        <Trash2 size={18} />{t("删除")}
                      </button>
                      {automationRuleConfirmDelete === rule.id && (
                        <div className="automation-rule-delete-confirm" data-testid="automation-rule-delete-confirm">
                          <p role="alert">
                            {t("删除「{0}」是整条删除：规则本身、它的全部选择器和动作都不会留下，删了就没了。", rule.id)}
                          </p>
                          <button className="button button-secondary compact-button" type="button"
                            onClick={() => deleteAutomationRule(rule.id)} disabled={automationRuleWriting !== null}>
                            {automationRuleWriting === 'delete'
                              ? <Loader2 className="spin" size={18} />
                              : <Trash2 size={18} />}{t("确认删除")}
                          </button>
                          <button className="button button-secondary compact-button" type="button"
                            onClick={() => setAutomationRuleConfirmDelete(null)} disabled={automationRuleWriting !== null}>
                            {t("取消")}
                          </button>
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              {/*
                读回 / 写入失败的原因：原样展示桥回来的文案，不改写也不吞掉。
                表单没打开时提示显示在卡片里——「编辑」读回失败正是这种情况：
                这里**只会出现这段原因，不会出现任何输入框**。
              */}
              {automationRuleError !== null && automationRuleDraft === null && (
                <p className="settings-note" role="alert" data-testid="automation-rule-write-error">
                  {automationRuleError}
                </p>
              )}
              {automationRuleDraft !== null && (
                // key 带上方式与标识：换一条规则编辑时表单重建，草稿不会带着上一条的内容。
                <AutomationRuleForm
                  key={`automation-rule-${automationRuleDraft.mode}-${automationRuleDraft.rule?.id ?? 'new'}`}
                  packageName={rulesPackage}
                  rule={automationRuleDraft.rule}
                  mode={automationRuleDraft.mode}
                  defaults={automationRuleDraft.defaults}
                  busy={automationRuleWriting !== null}
                  error={automationRuleError}
                  onSubmit={saveAutomationRule}
                  onCancel={closeAutomationRuleForm}
                />
              )}
            </div>
            {/*
              「每应用参数」：按应用的暂停开关 + 新建规则的三个默认值。
              单独一块是刻意的——它改的是「这个应用的行为边界」，与规则正文无关，
              放同一张卡里会让人以为保存参数会连规则一起动。
            */}
            <div className="settings-subsection" aria-labelledby="accessibility-automation-app-config">
              <div className="section-title">
                <span className="section-icon"><SlidersHorizontal size={19} /></span>
                <div>
                  <h3 id="accessibility-automation-app-config">{t("每应用参数")}</h3>
                  <p>{t("暂停与新建规则的默认值")}</p>
                </div>
              </div>
              {rulesPackage === '' ? (
                <p className="settings-note" role="status" data-testid="automation-app-config-no-package">
                  {t("先在上面选一个应用")}
                </p>
              ) : (
                // key 带上包名：换应用即重建表单，草稿不会带着上一个应用的数字。
                <AutomationAppConfigForm
                  key={`automation-app-config-${rulesPackage}`}
                  packageName={rulesPackage}
                  detail={automationAppConfig}
                  status={automationAppConfigStatus}
                  busy={automationAppConfigWriting}
                  locked={automationRuleLocked}
                  error={automationAppConfigError}
                  onSave={saveAutomationAppConfig}
                  onReload={() => selectRulesPackage(rulesPackage)}
                />
              )}
            </div>
            <label className="toggle-row">
              <span><strong>{t("启用无障碍白名单限制")}</strong><small>{t("关闭不会清空名单；修改后点击「保存白名单」生效，已设密码时仍需验证。")}</small></span>
              <input type="checkbox" role="switch" aria-label={t("启用无障碍白名单限制")}
                checked={whitelistEnabledDraft} disabled={busy !== null}
                onChange={event => setWhitelistEnabledDraft(event.target.checked)} />
            </label>
            <p className="settings-note" role="status">
              {accessibility.whitelistEnabled === false ? t("当前生效：白名单限制已关闭") : t("当前生效：白名单限制已开启")}
            </p>
            <p className="settings-note">
              {t("本应用（{0}）始终在白名单里：服务重启、连接重建都不会掉，也不需要写进下面的列表。", accessibility.alwaysAllowedPackages.length ? accessibility.alwaysAllowedPackages.join("、") : t("未配置"))}
            </p>
            <ApplicationPicker bridge={runtimeBridge} disabled={busy !== null}
              selected={[...new Set(accessibilityDraft.split(/[\n,]/u).map(value => value.trim()).filter(Boolean))]}
              onChange={packages => setAccessibilityDraft(packages.join('\n'))} />
            <label className="field">
              <span>{t("目标应用包名（每行一个，数量不限）")}</span>
              <textarea
                value={accessibilityDraft}
                onChange={event => setAccessibilityDraft(event.target.value)}
                rows={Math.min(8, Math.max(3, accessibility.allowedPackages.length + 2))}
                spellCheck={false}
                placeholder="com.example.reader\ncom.example.notes"
                aria-label={t("目标应用包名")}
              />
            </label>
            {accessibility.passwordConfigured && (
              <label className="field">
                <span>{t("验证密码（修改白名单需要）")}</span>
                <input
                  type="password"
                  value={whitelistPassword}
                  onChange={event => setWhitelistPassword(event.target.value)}
                  autoComplete="off"
                  spellCheck={false}
                  maxLength={64}
                  aria-label={t("修改白名单的验证密码")}
                />
              </label>
            )}
            <div className="settings-inline-actions">
              <button className="button button-secondary" type="button" onClick={saveAccessibilityDraft} disabled={busy !== null}>
                <Save size={18} />{t("保存白名单")}
              </button>
              <button className="button button-secondary" type="button" onClick={onOpenAccessibilitySettings} disabled={busy !== null}>
                <ExternalLink size={18} />{t("打开系统无障碍设置")}
              </button>
            </div>
            <p className="settings-note">{t("当前白名单：{0}", accessibility.allowedPackages.length ? accessibility.allowedPackages.join("、") : t("未配置"))}</p>
            <div className="settings-subsection" aria-labelledby="accessibility-password-settings">
              <div className="section-title section-title-action">
                <span className="section-icon"><KeyRound size={19} /></span>
                <div>
                  <h3 id="accessibility-password-settings">{t("白名单验证密码")}</h3>
                  <p>{t("设置后，每次修改白名单都要输入")}</p>
                </div>
                <span className={`status-chip ${accessibility.passwordConfigured ? 'success' : ''}`}>
                  {accessibility.passwordConfigured ? t("已设置") : t("未设置")}
                </span>
              </div>
              <p className="settings-note">
                {t("密码只以加盐哈希保存在设备上，不写日志、不随桥返回，界面上输入后也不保留；忘记时可用系统生物识别或锁屏密码重置，重置只清密码，白名单保留。")}
              </p>
              {accessibility.passwordConfigured && (
                <label className="field">
                  <span>{t("当前密码")}</span>
                  <input
                    type="password"
                    value={passwordDraft.current}
                    onChange={event => setPasswordDraft(draft => ({ ...draft, current: event.target.value }))}
                    autoComplete="off"
                    spellCheck={false}
                    maxLength={64}
                    aria-label={t("当前验证密码")}
                  />
                </label>
              )}
              <label className="field">
                <span>{t("新密码（6 到 64 个字符）")}</span>
                <input
                  type="password"
                  value={passwordDraft.next}
                  onChange={event => setPasswordDraft(draft => ({ ...draft, next: event.target.value }))}
                  autoComplete="off"
                  spellCheck={false}
                  maxLength={64}
                  aria-label={t("新验证密码")}
                />
              </label>
              <label className="field">
                <span>{t("再输一次新密码")}</span>
                <input
                  type="password"
                  value={passwordDraft.confirm}
                  onChange={event => setPasswordDraft(draft => ({ ...draft, confirm: event.target.value }))}
                  autoComplete="off"
                  spellCheck={false}
                  maxLength={64}
                  aria-label={t("确认新验证密码")}
                />
              </label>
              <div className="settings-inline-actions">
                <button className="button button-secondary" type="button" onClick={submitAccessibilityPassword} disabled={busy !== null}>
                  <Save size={18} />{accessibility.passwordConfigured ? t("修改密码") : t("设置密码")}
                </button>
                {accessibility.passwordConfigured && (
                  <>
                    <button
                      className="button button-secondary"
                      type="button"
                      disabled={busy !== null || passwordDraft.current === ''}
                      onClick={() => {
                        onClearAccessibilityPassword(passwordDraft.current)
                        setPasswordDraft({ current: '', next: '', confirm: '' })
                      }}
                    >
                      <Trash2 size={18} />{t("清除密码")}
                    </button>
                    <button
                      className="button button-secondary"
                      type="button"
                      disabled={busy !== null}
                      onClick={() => {
                        onResetAccessibilityPassword()
                        setPasswordDraft({ current: '', next: '', confirm: '' })
                      }}
                    >
                      <ShieldCheck size={18} />{t("用生物识别重置")}
                    </button>
                  </>
                )}
              </div>
              {passwordMessage !== null && <p className="settings-note" role="alert">{passwordMessage}</p>}
            </div>
          </div>
          {/*
            副屏是自动化的第二条腿：取帧档位、方向、分辨率与跟随策略都在这一页里，
            不再另开独立路由，免得「自动化设置」还要再跳一次才看得到画面相关参数。
          */}
          <VirtualScreenSettings bridge={runtimeBridge} />
          {/*
            免责说明只占一条：标题 + 一句话，详情在弹窗里（同一段文字，不改任何状态）。
            确认过之后不再自动弹，但「我当初到底同意了什么」随时要能查。
          */}
          <div className="settings-subsection" aria-labelledby="automation-disclaimer">
            <div className="section-title section-title-action">
              <span className="section-icon"><ShieldCheck size={19} /></span>
              <div><h3 id="automation-disclaimer">{t("免责说明")}</h3><p>{t("自动化可能误点或误读界面，请只对自己有权操作的应用启用。")}</p></div>
              <button className="button button-secondary" type="button" disabled={busy !== null}
                onClick={() => setAutomationDisclaimer({ mode: 'read' })}>
                <ShieldCheck size={18} />{t("查看完整免责说明")}
              </button>
            </div>
          </div>
        </>
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
            {t("诊断日志只包含应用内部的事件名、状态码、布尔值与计数；导出文件头另有应用版本、机型与 ROM 构建串（不含序列号、IMEI、Android ID、账号）。不含 URL、模型密钥、Harness 临时密码、设备桥令牌、终端内容或文件路径。日志保存在应用私有目录且不参与备份，到期自动删除。")}
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
        <section className="settings-section" aria-labelledby="feedback-title">
          <h2 id="feedback-title">{t('问题反馈')}</h2>
          <p>{t('反馈时请附上应用版本、屏幕方向和复现步骤。分享日志前请检查是否包含个人信息。')}</p>
          <p>QQ {t('交流群')}：1108895375</p>
          <a className="button button-secondary" href="https://github.com/dphmoblie/deepseek-harness-android/issues" target="_blank" rel="noopener noreferrer">
            <ExternalLink size={18} />{t('在 GitHub 反馈问题')}
          </a>
        </section>

        <RuntimeSelfCheckPanel runSelfCheck={runSelfCheck} runtime={runtime} />

        {/* 设备能力清单：从版本管理页挪过来，回答「这台机器现在能做什么」。 */}
        <DeviceCapabilityCard onOpenDeviceShell={onOpenDeviceShell} />
        {/* 根通道单独立卡：它是**写**操作（开关），不能和能力清单那份 5 秒轮询共用状态。 */}
        <RootEngineCard />
        </>
        )}

        {autoSaved && <p className="settings-note settings-auto-saved" role="status"><Check size={16} />{t("已自动保存")}</p>}
      </form>
    </div>
  )
}

export function App() {
  const language = useLanguage()
  useEffect(() => { document.documentElement.lang = language ?? 'zh-CN' }, [language])
  /**
   * 输入法弹起时把界面整体让出被遮挡的高度（见 [observeKeyboardInset]）。
   *
   * 让位写成一个 CSS 变量而不是量某个输入框：终端页、设置页、对话框里的输入框都靠
   * `.app-shell` 的这条下内边距一起抬起来，输入区最下端就贴在输入法框上沿。
   */
  useEffect(() => observeKeyboardInset(
    window.visualViewport ?? undefined,
    inset => { document.documentElement.style.setProperty('--keyboard-inset', `${inset}px`) },
    () => window.innerHeight,
  ), [])
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
  /**
   * 主题页的「字号」管整个应用壳子：设置页、对话页与应用内终端一起缩放。
   * dsh 自己界面的字号仍由 dsh 自己设置，这里不碰它。
   */
  useEffect(() => {
    applyShellFontScale(settings?.terminalFontSize ?? SHELL_FONT_BASELINE_PX)
  }, [settings?.terminalFontSize])
  const [settingsReadStatus, setSettingsReadStatus] = useState<'idle' | 'loading' | 'failed'>('idle')
  const settingsReadRevision = useRef(0)
  /**
   * 后台轮询的两件状态必须放在 ref 里，**不能放在 effect 闭包里**：
   * 这个 effect 的依赖里有若干 `useCallback`，桥调用引起的状态更新会让它们换标识、
   * 从而让 effect 重跑；闭包里的变量会随之被重置——「单飞」会静默失效，
   * 定时器也会被反复清掉再武装。放 ref 才能跨 effect 重跑保住语义。
   */
  const backgroundRefreshInFlight = useRef(false)
  const backgroundRefreshStartedAt = useRef(0)
  /**
   * 在途轮次的编号。
   *
   * 陈旧逃逸允许「上一轮还没结束就再发一轮」，于是多轮会同时在飞。结算时必须能分辨
   * 「我是被接管的那一轮，还是当前这一轮」，否则晚到的旧轮次会清掉新一轮的在途标志，
   * 单飞保护直接失效（真机上表现为请求一层层堆积）。
   */
  const backgroundRefreshRound = useRef(0)
  const backgroundPollTimer = useRef<number | null>(null)
  /**
   * 「一轮对话刚结束」的基线序号，来自原生侧 `turnCompletionSequence`。
   *
   * `null` 表示还没读到过：应用冷启动时这个序号往往已经不是 0（此前跑过若干轮），
   * 首次读到只记基线、不跳视图，否则每次打开应用都会被历史完成事件拽回对话界面。
   * 原生进程重启后序号从 0 重新计数，新值小于等于基线时同样只更新基线。
   * 与轮询状态放在一起的原因相同：必须跨 effect 重跑保住，否则每次重跑都会退化成「首次读到」。
   */
  const lastTurnCompletionSequence = useRef<number | null>(null)
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
  const [accessibility, setAccessibility] = useState<AccessibilityAutomationState>(EMPTY_ACCESSIBILITY)
  const [keepAlive, setKeepAlive] = useState<KeepAliveState>(EMPTY_KEEP_ALIVE)
  // 查询失败与「没有权限」分开记录，保留最近一次快照供开关关闭操作使用。
  const [overlayBall, setOverlayBall] = useState<OverlayBallState | null>(null)
  const [overlayBallReadFailed, setOverlayBallReadFailed] = useState(false)
  const overlayBallReadRevision = useRef(0)
  /**
   * 外置投递区状态。null 表示尚未读到快照（界面显示「未读取」，不给可点的按钮）；
   * [mailboxReadFailed] 单独记录读取失败，避免把「读取失败」显示成「投递区不可用」。
   */
  const [mailbox, setMailbox] = useState<MailboxState | null>(null)
  const [mailboxDirectory, setMailboxDirectory] = useState<MailboxDirectoryState | null>(null)
  const [mailboxDirectoryReadFailed, setMailboxDirectoryReadFailed] = useState(false)
  const [storageAccess, setStorageAccess] = useState<StorageAccessState | null>(null)
  /**
   * 目录白名单（访客内 `/mnt/user/<序号>` 的唯一来源）。与投递区同一套口径：
   * null 表示尚未读到快照，读取失败单独记录，绝不把「读取失败」显示成「没有目录」——
   * 后者会让用户以为自己的选择被清空了。
   */
  const [storageDirs, setStorageDirs] = useState<StorageDirsState | null>(null)
  const [mailboxReadFailed, setMailboxReadFailed] = useState(false)
  const [storageDirsReadFailed, setStorageDirsReadFailed] = useState(false)
  /**
   * 「文件管理」页当前浏览的根，以及共享目录那份目录快照。
   *
   * 子目录光标只取当前根匹配的快照；共享目录还保留宿主路径以识别被重新分配的槽位。
   */
  const [filesRoot, setFilesRoot] = useState<FilesRoot>({ kind: 'mailbox', root: 'inbox' })
  const filesRootRef = useRef(filesRoot)
  const [storageDirectory, setStorageDirectory] = useState<StorageDirectoryState | null>(null)
  const [storageDirectoryReadFailed, setStorageDirectoryReadFailed] = useState(false)
  const mailboxReadRevision = useRef(0)
  const mailboxDirectoryReadRevision = useRef(0)
  const storageDirectoryReadRevision = useRef(0)
  /**
   * 本次会话内最近一次导入/导出的结果。
   *
   * 只放在界面状态里，原生侧不保存历史：重进应用后显示为「本次会话还没有搬运」，
   * 而不是编造一份上一次的记录。
   */
  const [lastMailboxImport, setLastMailboxImport] = useState<MailboxImportResult | null>(null)
  const [lastMailboxExport, setLastMailboxExport] = useState<MailboxExportResult | null>(null)
  const [diagnostic, setDiagnostic] = useState<DiagnosticLogState>(EMPTY_DIAGNOSTIC)
  const [booting, setBooting] = useState(true)
  const [onboardingOpen, setOnboardingOpen] = useState(() => {
    try { return window.localStorage.getItem(ONBOARDING_STORAGE_KEY) === null } catch { return true }
  })
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [resetOpen, setResetOpen] = useState(false)
  /**
   * 悬浮球 / 后台保持启动受阻时的权限指引弹窗。
   *
   * 由「保存后延迟复核」与「通知权限被拒」两个路径置位；服务确认运行或用户关闭后清空。
   * 不放具体错误字符串，只放 [PermissionGuide] 的受控判别值。
   */
  const [permissionGuide, setPermissionGuide] = useState<PermissionGuide | null>(null)
  /**
   * 待用户确认的运行时安装；`null` 表示没有待确认的安装。
   *
   * 只在**已经装过运行时**时才需要提醒（首次安装没有会话可丢、也没有旧 dsh 的会话格式问题，
   * 弹窗只会挡路）。`version` 只用于弹窗文案，`bundled` 决定安装成功后提示「已更新」还是「已安装」。
   */
  const [pendingInstall, setPendingInstall] = useState<{ source?: RuntimeSource; version: string | null; bundled: boolean } | null>(null)
  const noticeId = useRef(0)
  const busyRef = useRef<string | null>(null)
  /**
   * 在途操作的令牌序号。
   *
   * 只用 `busyRef` 的字符串值判断「这次 `finally` 该不该释放锁」是不够的：同一个操作可以
   * 被点了两次（第一次被看门狗放行后用户再点一次），两轮的 id 字符串完全一样，
   * 晚到的第一轮 `finally` 会把第二轮的锁释放掉——界面看起来又能点，实际已有两个请求在飞。
   * 每次进入 `run()` 取一个新令牌，释放时比对令牌，才是真正的「只释放自己那把锁」。
   */
  const busyToken = useRef(0)
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
    if (tone === 'success') {
      triggerHaptic('success')
    } else if (tone === 'error') {
      triggerHaptic('error')
    } else {
      triggerHaptic('light')
    }
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

  /**
   * Applies a fresh native snapshot without discarding unsaved form input.
   * Harness credential presence is read-only in this UI, so it can be refreshed independently.
   */
  const applyRefreshedSettings = useCallback((next: RuntimeSettings) => {
    setSettings(next)
    if (!settingsDraftDirty.current) {
      resyncSettingsDraft(next)
      return
    }
    setSettingsDraft(current => current === null ? current : {
      ...current,
      settings: {
        ...current.settings,
        harnessConfiguredModelProviders: next.harnessConfiguredModelProviders,
        harnessConfiguredCustomModelProviders: next.harnessConfiguredCustomModelProviders,
      },
    })
  }, [resyncSettingsDraft])

  const readOverlayBall = useCallback(async () => {
    const revision = ++overlayBallReadRevision.current
    try {
      const next = await runtimeBridge.getOverlayBallState()
      if (revision === overlayBallReadRevision.current) {
        // 值没变就不要写状态：桥调用给的是新引用，写进去 React 只会认为「变了」并重渲染整棵视图。
        setOverlayBall(previous => (previous !== null && sameSnapshot(previous, next) ? previous : next))
        setOverlayBallReadFailed(false)
      }
      return next
    } catch (error) {
      if (revision === overlayBallReadRevision.current) setOverlayBallReadFailed(true)
      throw error
    }
  }, [])

  /**
   * 读取投递区、存储权限档与目录白名单。
   *
   * 三个载荷一起并行读：它们互相决定对方的显示 —— 投递区是否可用由「所有文件访问」决定，
   * 目录白名单能不能添加也由它决定，分开读会出现「显示需要授权但按钮可点」这类
   * 自相矛盾的瞬间。失败时清除旧快照并单独提示读取失败，旧权限不能继续授权写入。
   *
   * 三路用同一个版本号收口：它们是**一屏状态**，版本不一致就会出现「投递区是新的、
   * 白名单是旧的」这种半个屏幕的组合，比整体用上一批快照更难解释。
   */
  const readMailbox = useCallback(async () => {
    const revision = ++mailboxReadRevision.current
    try {
      const [nextMailbox, nextStorage, nextDirs] = await Promise.all([
        runtimeBridge.getMailboxState(),
        runtimeBridge.getStorageAccessState(),
        runtimeBridge.getStorageDirs(),
      ])
      if (revision === mailboxReadRevision.current) {
        setMailbox(nextMailbox)
        setStorageAccess(nextStorage)
        setStorageDirs(nextDirs)
        const root = filesRootRef.current
        if (!nextMailbox.available) {
          mailboxDirectoryReadRevision.current += 1
          setMailboxDirectory(null)
        }
        if (root.kind === 'storage' && !nextDirs.entries.some(entry => entry.guestPath === root.guestPath && entry.path === root.path && entry.available)) {
          storageDirectoryReadRevision.current += 1
          setStorageDirectory(null)
        }
        setMailboxReadFailed(false)
        setStorageDirsReadFailed(false)
      }
      return nextMailbox
    } catch (error) {
      if (revision === mailboxReadRevision.current) {
        mailboxDirectoryReadRevision.current += 1
        storageDirectoryReadRevision.current += 1
        setMailbox(null)
        setStorageAccess(null)
        setStorageDirs(null)
        setMailboxDirectory(null)
        setStorageDirectory(null)
        setMailboxReadFailed(true)
        setStorageDirsReadFailed(true)
      }
      throw error
    }
  }, [])

  /** 读取固定投递区根目录下的当前子目录；只接受桥接层返回的受控快照。 */
  const readMailboxDirectory = useCallback(async (root: MailboxRoot, subdirectory?: string): Promise<MailboxDirectoryState> => {
    const revision = ++mailboxDirectoryReadRevision.current
    try {
      const next = await runtimeBridge.getMailboxDirectory(root, subdirectory)
      if (next.root !== root || next.path !== subdirectory) throw new Error('投递区目录响应与请求不一致')
      const selected = filesRootRef.current
      if (revision === mailboxDirectoryReadRevision.current && selected.kind === 'mailbox' && selected.root === root) {
        setMailboxDirectory(next)
        setMailboxDirectoryReadFailed(false)
      }
      return next
    } catch (error) {
      if (revision === mailboxDirectoryReadRevision.current) {
        setMailboxDirectory(null)
        setMailboxDirectoryReadFailed(true)
      }
      throw error
    }
  }, [])

  /**
   * 读取共享目录（白名单里某一条）下的当前子目录；只接受桥接层返回的受控快照。
   *
   * 与投递区那条结构相同但**不共用**：两条链路的修订号必须各自独立，否则用户在
   * 投递区里快速点几下就能把共享目录那次读取的结果丢掉（反之亦然）。
   */
  const readStorageDirectory = useCallback(async (guestPath: string, subdirectory?: string): Promise<StorageDirectoryState> => {
    const revision = ++storageDirectoryReadRevision.current
    try {
      const next = await runtimeBridge.getStorageDirectory(guestPath, subdirectory)
      if (next.guestPath !== guestPath || next.path !== subdirectory) throw new Error('共享目录响应与请求不一致')
      const selected = filesRootRef.current
      const selectedEntry = selected.kind === 'storage'
        ? storageDirs?.entries.find(entry => entry.guestPath === selected.guestPath && entry.path === selected.path && entry.available)
        : undefined
      if (revision === storageDirectoryReadRevision.current && selected.kind === 'storage' && selected.guestPath === guestPath && selectedEntry?.path === selected.path) {
        setStorageDirectory(next)
        setStorageDirectoryReadFailed(false)
      }
      return next
    } catch (error) {
      if (revision === storageDirectoryReadRevision.current) {
        setStorageDirectory(null)
        setStorageDirectoryReadFailed(true)
      }
      throw error
    }
  }, [storageDirs])

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
   * 移动端屏幕左边缘右滑返回手势（Edge Swipe Back）：
   * 在非根视图（非 conversation）时，若触点从屏幕最左边缘（<= 30px）起手，
   * 且向右滑动超过 75px 且纵向偏差较小时，触发返回上一级并伴随轻微触感振动。
   */
  useEffect(() => {
    if (activeView === ROOT_VIEW || resetOpen || pendingInstall !== null || onboardingOpen) return

    let startX = 0
    let startY = 0
    let tracking = false

    const handleTouchStart = (event: TouchEvent) => {
      if (event.touches.length !== 1) return
      const touch = event.touches[0]
      if (touch.clientX <= 30) {
        startX = touch.clientX
        startY = touch.clientY
        tracking = true
      }
    }

    const handleTouchEnd = (event: TouchEvent) => {
      if (!tracking) return
      tracking = false
      if (event.changedTouches.length === 0) return
      const touch = event.changedTouches[0]
      const deltaX = touch.clientX - startX
      const deltaY = Math.abs(touch.clientY - startY)

      if (deltaX > 75 && deltaY < deltaX * 0.75) {
        triggerHaptic('light')
        if (activeView === 'settings') {
          backToView('conversation')
        } else {
          backToView('settings')
        }
      }
    }

    const handleTouchCancel = () => {
      tracking = false
    }

    window.addEventListener('touchstart', handleTouchStart, { passive: true })
    window.addEventListener('touchend', handleTouchEnd, { passive: true })
    window.addEventListener('touchcancel', handleTouchCancel, { passive: true })

    return () => {
      window.removeEventListener('touchstart', handleTouchStart)
      window.removeEventListener('touchend', handleTouchEnd)
      window.removeEventListener('touchcancel', handleTouchCancel)
    }
  }, [activeView, backToView, resetOpen, pendingInstall, onboardingOpen])

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
        // 门屏的唯一出口就是这一读：卡住就等于把用户锁在一个没有按钮的加载页上。
        const nextRuntime = await withBridgeTimeout(runtimeBridge.getState(), BOOT_STATE_TIMEOUT_MS)
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
        // Shizuku 是可选能力，查不到状态绝不能阻塞 Harness 对话启动。
      })

    void runtimeBridge.getAccessibilityAutomationState()
      .then(next => {
        if (!cancelled) setAccessibility(previous => (
          previous.enabled === next.enabled &&
          previous.whitelistEnabled === next.whitelistEnabled &&
          previous.passwordConfigured === next.passwordConfigured &&
          previous.allowedPackages.length === next.allowedPackages.length &&
          previous.allowedPackages.every((value, index) => value === next.allowedPackages[index])
            ? previous
            : next
        ))
      })
      .catch(() => {
        // 无障碍是可选能力；读取失败时保持关闭且不阻塞 Harness。
      })

    void runtimeBridge.getKeepAliveState()
      .then(next => {
        if (cancelled) return
        setKeepAlive(next)
        // 用户已在系统设置里补齐权限/后台策略后返回时，关闭滞留的指引弹窗。
        setPermissionGuide(previous => (keepAliveGuideResolved(previous, next) ? null : previous))
      })
      .catch(() => {
        // 后台保持状态读取失败不阻塞界面：保持上一次的已知状态。
      })

    void readOverlayBall()
      .then(next => {
        if (cancelled) return
        setPermissionGuide(previous => (overlayGuideResolved(previous, next) ? null : previous))
      })
      .catch(() => {
        // 悬浮球属于可选能力，错误由设置页单独显示。
      })

    void readMailbox()
      .then(next => {
        if (next.available) void readMailboxDirectory('inbox').catch(() => {
          // 目录浏览失败只影响文件夹列表；投递区状态本身已读取成功。
        })
      })
      .catch(() => {
        // 投递区属于可选能力（无存储权限时是预期降级），错误由设置页单独显示。
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
  }, [noteLivePhase, notify, readMailbox, readMailboxDirectory, readOverlayBall])

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
          applyRefreshedSettings(next)
        })
        .catch(() => {
          // 前台恢复属于后台同步；读取失败时保留最近一次设置快照，不弹重复错误。
        })
    }

    const refreshShizuku = (reportError = true): Promise<void> => {
      if (document.visibilityState === 'hidden') return Promise.resolve()
      return runtimeBridge.getShizukuState()
        .then(next => {
          // 值没变就返回上一个引用：React 据此跳过本次更新（连同整棵视图的渲染）。
          if (!cancelled) setShizuku(previous => (sameSnapshot(previous, next) ? previous : next))
        })
        .catch(error => { if (!cancelled && reportError) notify(errorMessage(error), 'error') })
    }
    const refreshAccessibility = (reportError = false): Promise<void> => {
      if (document.visibilityState === 'hidden') return Promise.resolve()
      return runtimeBridge.getAccessibilityAutomationState()
        .then(next => {
          if (!cancelled) setAccessibility(previous => (
            previous.enabled === next.enabled &&
            previous.whitelistEnabled === next.whitelistEnabled &&
            previous.passwordConfigured === next.passwordConfigured &&
            previous.allowedPackages.length === next.allowedPackages.length &&
            previous.allowedPackages.every((value, index) => value === next.allowedPackages[index])
              ? previous
              : next
          ))
        })
        .catch(error => { if (!cancelled && reportError) notify(errorMessage(error), 'error') })
    }
    /**
     * 一轮对话结束时把外壳切回对话界面（需求 5.6-F 里的「任务完成回到对话」）。
     *
     * 判定只用原生侧受理成功的累计序号：它由原生侧在真正受理一次「一轮结束」时才自增，
     * 被节流丢弃的通知**不**自增，因此这里不会因为重复上报而反复跳视图。
     *
     * 刻意只做视图切换，不额外弹提示：锁屏/通知栏那条通知由原生侧负责，
     * 而用户已经回到前台时，界面自己就是最直接的反馈。
     */
    const handleTurnCompletion = (sequence: number | undefined): void => {
      const value = typeof sequence === 'number' && Number.isFinite(sequence) && sequence > 0 ? Math.floor(sequence) : 0
      const previous = lastTurnCompletionSequence.current
      lastTurnCompletionSequence.current = value
      // 首次读到（previous === null）、序号没变或回退（原生进程重启）都不动视图。
      if (previous === null || value <= previous) return
      if (activeViewRef.current === 'conversation') return
      setActiveView('conversation')
    }
    // 后台保持状态同时轮询：前台服务可能被系统结束，需要通过原生端才能得知。
    const refreshKeepAlive = (): Promise<void> => {
      if (document.visibilityState === 'hidden') return Promise.resolve()
      return runtimeBridge.getKeepAliveState()
        .then(next => {
          if (cancelled) return
          setKeepAlive(previous => (sameSnapshot(previous, next) ? previous : next))
          handleTurnCompletion(next.turnCompletionSequence)
          setPermissionGuide(previous => (keepAliveGuideResolved(previous, next) ? null : previous))
        })
        .catch(() => {
          // 轮询失败时保留上一次状态，不重复提示同一条错误。
        })
    }
    /**
     * 悬浮球状态同样在页面重新可见时重读：用户可能刚在系统设置里授予或撤销了
     * 「显示在其他应用上层」权限，也可能用悬浮球菜单在原生侧关掉了球。
     * 读取失败时保留上一次的已知值，不清零：清零会把「未知」显示成「已关闭」。
     */
    const refreshOverlayBall = (): Promise<void> => {
      if (document.visibilityState === 'hidden') return Promise.resolve()
      return readOverlayBall()
        .then(next => {
          setPermissionGuide(previous => (overlayGuideResolved(previous, next) ? null : previous))
        })
        .catch(() => {
          // 设置页保留查询失败提示，不在轮询中重复弹通知。
        })
    }
    /**
     * 投递区与目录白名单状态在页面重新可见时重读：用户很可能刚去系统设置里开启了
     * 「所有文件访问」、或者刚在系统文件管理器里删掉了一个已选目录，回到应用时必须
     * 立刻看到新状态而不是旧状态。
     * 刻意**不放进 5 秒轮询**：投递区读取会做一次真实写探测（FUSE 上 canWrite 不可信），
     * 白名单读取要对每条目录做一次真实 stat，两者都没必要每 5 秒跑一遍。
     */
    const refreshMailbox = (): void => {
      if (document.visibilityState === 'hidden') return
      void readMailbox()
        .catch(() => {
          // 设置页保留查询失败提示，不在刷新中重复弹通知。
        })
    }
    /**
     * 后台状态轮询：**真正停表 + 单飞**（登记册 5.6-C）。
     *
     * 修复前的形态有两个问题，都不显眼但都是真的：
     *  1. `setInterval` 无条件每 5 秒触发一次，只有各个刷新器内部自查
     *     `visibilityState === 'hidden'` 才不至于发出桥调用。结果是**后台仍在每 5 秒唤醒一次 JS**，
     *     白白耗电；而且「后台不该轮询」这件事散落在四个刷新器里各写一遍，改一个漏一个。
     *  2. 没有单飞保护：某个请求慢于 5 秒（真机上安装/更新期间很常见）时，下一轮 tick 会再发一遍，
     *     请求会一层层堆积。
     *
     * 现在的做法：可见时才启动定时器、进入后台立刻 `clearInterval`；每轮把三路读取**合并**成一次
     * `Promise.allSettled`，上一轮没结束就跳过这一轮。单飞与定时器句柄都放在 ref 里，
     * 因此 effect 重跑不会把它们重置（这是第一版实现的缺陷，由单飞用例暴露出来）。
     *
     * 陈旧逃逸：真机上桥调用卡死时，`allSettled` 永远不会结束，单飞会把轮询**永久**停住。
     * 因此超过 [BACKGROUND_REFRESH_STALE_MS] 仍未结束时视为陈旧，允许下一轮重试。
     *
     * 仍未做（需要新增原生方法，等 Kotlin 侧空闲再做）：把三路桥调用合并成**一次**原生调用，
     * 减少 WebView ↔ 原生 的往返次数。当前只是把它们并发发出并统一收口。
     */
    const refreshBackgroundState = (): void => {
      if (document.visibilityState === 'hidden') return
      const now = Date.now()
      const stale = now - backgroundRefreshStartedAt.current > BACKGROUND_REFRESH_STALE_MS
      if (backgroundRefreshInFlight.current && !stale) return
      // 陈旧逃逸会让**两轮同时在飞**：给每轮编号，只有编号仍是最新的那一轮才有权清在途标志。
      // 否则先发的那轮晚到，会把新一轮的在途状态抹掉，单飞在原地失效——而这正是它唯一的作用。
      const round = ++backgroundRefreshRound.current
      backgroundRefreshInFlight.current = true
      backgroundRefreshStartedAt.current = now
      void Promise.allSettled([refreshShizuku(false), refreshAccessibility(), refreshKeepAlive(), refreshOverlayBall()])
        .then(() => {
          if (round === backgroundRefreshRound.current) backgroundRefreshInFlight.current = false
        })
    }
    const startPolling = (): void => {
      // 只有可见时才装表。首帧就处于后台（后台唤起、切前台的一瞬）时不必留下一个
      // 每 5 秒唤醒一次 JS 却每轮都早退的空转定时器；可见性变化或 focus 会把它重新武装。
      if (document.visibilityState === 'hidden') return
      if (backgroundPollTimer.current !== null) return
      backgroundPollTimer.current = window.setInterval(refreshBackgroundState, BACKGROUND_POLL_INTERVAL_MS)
    }
    const stopPolling = (): void => {
      if (backgroundPollTimer.current === null) return
      window.clearInterval(backgroundPollTimer.current)
      backgroundPollTimer.current = null
      // 停表同时清掉单飞标志并推进轮次：否则「后台停表期间发起的那一轮」会把回到前台后的
      // 第一轮吃掉；而那一轮晚到的结算也不该再去动新一轮的在途标志。
      backgroundRefreshRound.current += 1
      backgroundRefreshInFlight.current = false
    }
    const handleVisibilityChange = (): void => {
      if (document.visibilityState === 'visible') {
        refreshSettings()
        void refreshShizuku()
        void refreshAccessibility()
        void refreshKeepAlive()
        void refreshOverlayBall()
        refreshMailbox()
        startPolling()
      } else {
        // 停表：后台不轮询。回到前台时上面那一支会重新启动并立刻刷新一次。
        stopPolling()
      }
    }
    const handleFocus = (): void => {
      refreshSettings()
      void refreshShizuku()
      void refreshAccessibility()
      void refreshKeepAlive()
      void refreshOverlayBall()
      // focus 与 visibilitychange 不是同一条路径：部分 ROM 从系统设置返回时只发 focus。
      // 这里补一次武装，避免「没收到 visibilitychange」导致轮询永久停在停表状态。
      startPolling()
    }

    window.addEventListener('focus', handleFocus)
    document.addEventListener('visibilitychange', handleVisibilityChange)
    startPolling()
    return () => {
      cancelled = true
      stopPolling()
      window.removeEventListener('focus', handleFocus)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [applyRefreshedSettings, notify, readMailbox, readOverlayBall, setActiveView])

  /**
   * 执行一个用户动作：互斥、提示、以及**卡死自救**。
   *
   * 互斥的意义是防止用户连点造成重复的原生操作；但它同时是一条单点故障链：
   * 只要有一次桥调用永不返回，`finally` 就永远不会执行，界面从此停在「处理中」，
   * 连退出都要靠杀进程。真机上这类卡死不是假设（`RUNTIME_CLOSED` 那组错误码就是
   * 为它准备的），所以这里加一道看门狗：
   *
   * - 到点后释放互斥并提示超时，**不**去取消底层请求（Capacitor 没有取消语义）；
   * - 底层请求若之后真的返回，它仍会走自己的成功/失败分支，但**不再**改动忙碌状态，
   *   也不会把一条「成功」提示盖在「已超时」提示之上——令牌比对负责这件事；
   * - 看门狗时长按操作性质给：普通动作 45 秒，安装/更新单独给 15 分钟（真实下载）。
   *
   * @param watchdogMs 看门狗时长；`null` 表示不设看门狗（仅限确实无上界的操作）。
   */
  const run = useCallback(async (
    id: string,
    operation: () => Promise<void>,
    success?: string,
    guide?: PermissionGuide,
    watchdogMs: number | null = ACTION_WATCHDOG_MS,
  ) => {
    if (busyRef.current !== null) return
    const token = ++busyToken.current
    busyRef.current = id
    setBusy(id)
    /** 只有令牌仍是自己时才释放：晚到的旧轮次不许动新一轮的锁。 */
    const release = (): void => {
      if (busyToken.current !== token) return
      busyRef.current = null
      setBusy(null)
    }
    const watchdog = watchdogMs === null
      ? null
      : window.setTimeout(() => {
        if (busyToken.current !== token) return
        // 先释放再提示：提示本身会触发一次渲染，若此时 busy 还在，用户会看到
        // 「已提示超时、按钮却仍然是禁用的」这种自相矛盾的瞬间。
        release()
        notify(runtimeErrorMessage('BRIDGE_TIMEOUT'), 'error')
      }, watchdogMs)
    try {
      await operation()
      if (success !== undefined && busyToken.current === token) notify(success, 'success')
    } catch (error) {
      // 超时之后才失败的错误不再重复提示：用户已经看到过那条超时说明。
      if (busyToken.current === token) {
        notify(errorMessage(error), 'error')
        // 权限类操作失败只弹一条会消失的 toast 不足以让用户自助修好：
        // 由调用方给出对应指引时，同时打开可跳转系统设置/Shizuku 的指引弹窗。
        if (guide !== undefined) setPermissionGuide(guide)
      }
    } finally {
      if (watchdog !== null) window.clearTimeout(watchdog)
      release()
    }
  }, [notify])

  /**
   * 安装/更新运行时：真正干活的那一步，只在用户确认过之后调用。
   *
   * `autoSnapshot` 是这次安装前自动备份会话的结论，必须如实报出来：
   * 安装本身成功不代表备份成功，反过来备份失败也不该把安装说成失败。
   */
  const performRuntimeInstall = useCallback((source: RuntimeSource | undefined, successMessage: string) => {
    void run('install', async () => {
      let result: RuntimeInstallResult
      try {
        result = await runtimeBridge.install(source)
      } catch (error) {
        try {
          setRuntime(await runtimeBridge.getState())
        } catch {
          // 安装失败要保留；刷新运行时状态只是尽力而为，不要再覆盖这个错误。
        }
        throw error
      }
      const next = await runtimeBridge.getState()
      setRuntime(next)
      setPendingInstall(null)
      autoLaunchAttempted.current = false
      setActiveView('conversation')
      const outcome = result.autoSnapshot
      if (outcome === undefined) return
      if (outcome.status === 'created') {
        if (outcome.evictedIds !== undefined && outcome.evictedIds.length > 0) {
          notify(t("安装前已自动备份会话（{0}）；为满足保留限制，已删除 {1} 份最旧的备份。", outcome.snapshotId ?? '', String(outcome.evictedIds.length)), 'success')
          return
        }
        notify(t("安装前已自动备份会话（{0}）", outcome.snapshotId ?? ''), 'success')
        return
      }
      if (outcome.status === 'skipped') {
        notify(outcome.message ?? t("没有需要备份的会话数据，这次没有生成备份。"), 'info')
        return
      }
      notify(t("会话自动备份失败：{0}", outcome.message ?? t("原因未提供")), 'error')
    }, successMessage, undefined, INSTALL_WATCHDOG_MS)
  }, [notify, run, setActiveView])

  /**
   * 带提醒的安装入口：已经装过运行时先弹一次确认（讲清会丢什么、并说明会先备份会话），
   * 没装过就直接装——首次安装没有会话可丢，弹窗只会多一步。
   */
  const requestRuntimeInstall = useCallback((source: RuntimeSource | undefined, version: string | null, bundled: boolean) => {
    if (busyRef.current !== null) return
    if (runtimeInstalled(runtime)) {
      setPendingInstall({ source, version, bundled })
      return
    }
    performRuntimeInstall(source, bundled ? t("运行环境已更新") : t("运行环境已安装"))
  }, [performRuntimeInstall, runtime])

  const installRuntime = useCallback(() => {
    requestRuntimeInstall(settings === null ? undefined : {
      manifestUrl: settings.manifestUrl,
      manifestSha256: settings.manifestSha256,
    }, null, false)
  }, [requestRuntimeInstall, settings])

  /** 「更新运行环境」按钮：用 APK 内置的、带摘要校验的镜像。 */
  const requestRuntimeUpdate = useCallback(() => {
    requestRuntimeInstall({ manifestUrl: '', manifestSha256: '' }, null, true)
  }, [requestRuntimeInstall])

  const confirmPendingInstall = useCallback(() => {
    if (pendingInstall === null) return
    performRuntimeInstall(pendingInstall.source, pendingInstall.bundled ? t("运行环境已更新") : t("运行环境已安装"))
  }, [pendingInstall, performRuntimeInstall])

  /** 版本管理页读的三块数据：都只是读，失败由各自的区块自己显示。 */
  const loadSessionSnapshots = useCallback(() => runtimeBridge.getRuntimeSessionSnapshotState(), [])
  const createSessionSnapshot = useCallback(() => runtimeBridge.createRuntimeSessionSnapshot(), [])
  const restoreSessionSnapshot = useCallback((id: string) => runtimeBridge.restoreRuntimeSessionSnapshot(id), [])
  const deleteSessionSnapshot = useCallback((id: string) => runtimeBridge.deleteRuntimeSessionSnapshot(id), [])
  const loadAppUpdate = useCallback((forceRefresh = false) => runtimeBridge.getAppUpdateState(forceRefresh), [])
  const setAppUpdateChannel = useCallback((channel: AppUpdateChannel) => runtimeBridge.setAppUpdateChannel(channel), [])
  const appUpdateReminder = useAppUpdateReminder(language !== null && !booting && !onboardingOpen, loadAppUpdate)
  const downloadAppUpdate = useCallback(() => runtimeBridge.downloadAppUpdate(), [])
  const installAppUpdate = useCallback(() => runtimeBridge.installAppUpdate(), [])
  const openAppUpdateInstallSettings = useCallback(() => runtimeBridge.openAppUpdateInstallSettings(), [])

  /**
   * 真正的打开流程。`skipCredentialGate` 只允许由用户显式确认的入口传入
   * （引导页与「模型与密钥」页的「我已在 Harness 内配置过」按钮）：
   * **绝不要把事件对象或其它真值直接传进来**，否则等于默认绕过门禁。
   */
  const openHarness = useCallback((skipCredentialGate: boolean, sessionId?: string) => {
    if (busyRef.current !== null) return
    /*
     * 首次配置未完成时不打开 Harness：没有模型密钥时每轮对话都会因缺少凭据失败，
     * 打开只会看到一个用不了的界面。这里挡在唯一的打开入口上，覆盖引导页、
     * 首页按钮与「打开应用时自动启动」，并直接把用户送到「模型与密钥」页。
     * 设置尚未读取（settings 为 null）时放行，不做无法验证的判断；管理端只能识别
     * App 加密存储与 Harness 凭据文件，因此仍为其它凭据来源保留用户显式放行通道。
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
      let launchRuntime = runtime
      /*
       * 打开序列。抽成函数是为了让「失败后重连一次」走的是**同一条**路径：
       * 抄一遍重试分支，迟早出现「重试时漏掉了 getKeepAliveState，或在重试成功时仍然弹了错误提示」
       * 这类只在罕见路径上才暴露的漂移。
       */
      const launchSequence = async (): Promise<void> => {
        let nextRuntime = launchRuntime
        if (nextRuntime.phase !== 'running') {
          nextRuntime = await withBridgeTimeout(runtimeBridge.startHarness(), 180_000)
          launchRuntime = nextRuntime
          setRuntime(nextRuntime)
        }
        // HarnessActivity 覆盖在 MainActivity 之上；把设置留在底页，原生"管理"按钮
        // 就能回到正确的管理界面，而不是覆盖 HarnessActivity。
        setActiveView('settings')
        await withBridgeTimeout(runtimeBridge.openHarness(sessionId), ACTION_WATCHDOG_MS)
        // 先打开页面，再刷新可选状态，避免它抢占原生运行时锁。
        void runtimeBridge.getKeepAliveState()
          .then(setKeepAlive)
          .catch(() => { /* 保留上一次已知状态。 */ })
      }

      /** 陈旧句柄的恢复代价：刷新一次状态，成功才让用户看得见。失败保持原样，不打断启动失败的主线。 */
      const recoverFromClosedRuntime = async (): Promise<void> => {
        try {
          launchRuntime = await withBridgeTimeout(runtimeBridge.getState(), BOOT_STATE_TIMEOUT_MS)
          setRuntime(launchRuntime)
        } catch {
          // 刷新只是重连前的准备；刷不动就按原样重试，失败原因仍由原来的错误来交代。
        }
      }

      try {
        try {
          await launchSequence()
        } catch (error) {
          /*
           * `RUNTIME_CLOSED` 是**唯一**值得自动重试的码，而且只重试一次。
           *
           * 为什么单独放行它：这个码的含义是「句柄所指向的插件实例已经不在了」，而
           * WebView 插件实例销毁与进程级运行时控制器释放是**两件事**——前者刚发生时后者
           * 往往还活着。也就是说此刻的失败多半是「拿着刚失效的句柄问了一次」，
           * 重连一次就能拿到新实例，用户不必自己再点一次「重新连接」。
           *
           * 为什么其他码一次都不重试：它们描述的失败重试一次只会得到同样的结果
           * （下载失败、架构不兼容、没有安装运行时……），多试一次只是让用户多等一轮，
           * 还会把真正的失败原因往后拖。这里刻意**不**做「任何错误都重试」的通用退避。
           *
           * 为什么必须有上限：真机上 `RUNTIME_CLOSED` 曾经三连出现。启动失败时若不加约束地
           * 重试，界面会陷进「一直连不上、一直重连」，连带 busy 永远不释放——比直接报错更糟。
           */
          if (bridgedRuntimeCode(error) !== 'RUNTIME_CLOSED') throw error
          await recoverFromClosedRuntime()
          await launchSequence()
        }
      } catch (error) {
        setActiveView('conversation')
        // 错误后的状态刷新不能阻止释放打开按钮，尤其是桥本身无响应时。
        void withBridgeTimeout(runtimeBridge.getState(), BOOT_STATE_TIMEOUT_MS)
          .then(setRuntime)
          .catch(() => { /* 保留上一次已知状态。 */ })
        notify(errorMessage(error), 'error')
      } finally {
        busyRef.current = null
        setBusy(null)
      }
    })()
  }, [notify, requestRuntimeUpdate, runtime, setActiveView, settings])

  /** 常规打开入口：没有本机模型凭据时会被拦下并跳转到「模型与密钥」。 */
  const launchHarness = useCallback((sessionId?: string) => openHarness(false, sessionId), [openHarness])

  /** 用户已确认「密钥在 Harness 里配置过」时的放行入口，只在显式按钮上使用。 */
  const launchHarnessConfirmed = useCallback((sessionId?: string) => openHarness(true, sessionId), [openHarness])

  useEffect(() => {
    /*
     * 自动启动必须等基础设置读完再判断。旧版本曾提供「自动启动 Harness」开关，
     * 但它会让应用打开后的入口分裂成两套，现已移除；保留旧字段只为兼容已安装版本，
     * 不再影响启动行为。设置读取失败时仍不启动，避免把未知状态当成用户意图。
     */
    // Android 原生存储层已把该字段固定为 true；保留读取条件是为了兼容浏览器预览和旧版桥接，
    // 防止它们在没有真实运行时的情况下误触发启动流程。
    if (settings === null || settings.autoLaunch !== true) return
    if (language === null || onboardingOpen || booting || appUpdateReminder.checking || appUpdateReminder.available !== null || activeView !== 'conversation' || busy !== null || autoLaunchAttempted.current) return
    if (runtime.updateAvailable) return
    if (runtime.phase === 'running' || (runtimeInstalled(runtime) && runtime.phase !== 'stopping')) {
      launchHarness()
    }
  }, [activeView, appUpdateReminder.available, appUpdateReminder.checking, booting, busy, language, launchHarness, onboardingOpen, runtime, settings])

  const openSettings = useCallback((page: SettingsPage) => {
    // 最新设置读完之前不展示可编辑的旧草稿，避免迟到响应清空刚输入的内容。
    const revision = ++settingsReadRevision.current
    setSettingsReadStatus('loading')
    void runtimeBridge.getSettings()
      .then(next => {
        if (revision !== settingsReadRevision.current) return
        applyRefreshedSettings(next)
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
  }, [applyRefreshedSettings, readOverlayBall, setActiveView])

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

  /**
   * 运行时版本列表：只读的轻量查询（原生侧读清单与包描述），进页面即读。
   *
   * 与自检不同，这里不启动访客进程，所以不需要「按需触发」那条纪律；
   * 引用保持稳定，版本区块不会因为父组件重渲染而反复拉取。
   */
  const loadRuntimeVersions = useCallback(() => runtimeBridge.getRuntimeVersions(), [])

  /** 切换运行时版本（支持 previous、bundled 或版本库条目 ID）。 */
  const switchRuntimeVersion = useCallback(
    (target: string) => runtimeBridge.switchRuntimeVersion(target),
    [],
  )

  /** 删除上一版本或版本库中的历史版本。 */
  const deleteRuntimeVersion = useCallback(
    (target: string) => runtimeBridge.deleteRuntimeVersion(target),
    [],
  )

  /** 锁定/解锁版本库里的归档；目标必须是条目 ID（槽位不是归档），校验在桥接层完成。 */
  const setRuntimeVersionPinned = useCallback(
    (target: string, pinned: boolean) => runtimeBridge.setRuntimeVersionPinned(target, pinned),
    [],
  )

  /** 下载远端运行时发布到本地版本库（不立即激活）。 */
  const downloadRuntimeRelease = useCallback(
    (manifestUrl: string, manifestSha256: string) => runtimeBridge.downloadRuntimeRelease(manifestUrl, manifestSha256),
    [],
  )

  /** 查询可用版本发布列表。 */
  const listRuntimeReleases = useCallback(() => runtimeBridge.listRuntimeReleases(), [])

  /** 切换成功后重新读运行时状态：已安装版本与阶段都变了，不能沿用旧值。 */
  const refreshRuntimeState = useCallback(async () => {
    setRuntime(await runtimeBridge.getState())
  }, [])

  const stopRuntime = useCallback(() => {
    // 先记下「这次停止由本应用发起」：阶段变化可能早于桥接返回，晚记会把显式停止误报成自行停止。
    stopRequested.current = true
    void run('stop', async () => {
      const next = await runtimeBridge.stopRuntime()
      setRuntime(next)
      // 即使原生侧没有推送阶段变化，用户主动停止也要在「上次停止」里留下记录。
      setLastStop('user')
      // 显式停止已撤销前台服务，辅助状态后台回填，不占用操作按钮。
      void runtimeBridge.getKeepAliveState()
        .then(setKeepAlive)
        .catch(() => { /* 保留上一次已知状态。 */ })
      setActiveView('settings')
    }, t("运行环境已停止"))
  }, [run, setActiveView])

  const confirmReset = useCallback(() => {
    // 重置同样会停掉正在运行的运行时：这也是用户在本应用里主动发起的停止。
    stopRequested.current = true
    triggerHaptic('warning')
    void run('reset', async () => {
      const next = await runtimeBridge.reset('RESET_RUNTIME')
      setRuntime(next)
      setKeepAlive(await runtimeBridge.getKeepAliveState())
      setResetOpen(false)
      autoLaunchAttempted.current = false
      setActiveView('conversation')
    }, t("运行环境已重置"))
  }, [run, setActiveView])

  const saveSettings = useCallback(async (nextSettings: RuntimeSettingsUpdate): Promise<boolean> => {
    let savedSuccessfully = false
    await run('save-settings', async () => {
      const saved = await runtimeBridge.saveSettings(nextSettings)
      savedSuccessfully = true
      setSettings(saved)
      // 保存成功：草稿以后端落盘值为准重建（含清空 API Key 输入框）并解除「未保存」标记。
      // 不做这一步的话，旧草稿会在下一次后台刷新时把刚保存的值反向覆盖回去。
      resyncSettingsDraft(saved)
      triggerHaptic('success')
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
        // 仍为「未运行」时，按通知权限 / 厂商后台限制分流，弹窗给出可操作的跳转，
        // 而不是只弹一条没有入口的 toast（缺通知权限、自启动被禁、省电策略都会停在这里）。
        recheckForegroundServiceAfterSettle(
          () => runtimeBridge.getKeepAliveState(),
          latest => {
            setKeepAlive(latest)
            if (latest.keepRuntimeInBackground === true && !latest.foregroundServiceActive) {
              // 原生侧只在真正失败时下发受控令牌（foreground_declined / start_disallowed），
              // 它们都属于厂商后台策略拦截：令牌在，就必须走后台拦截指引，不能因为
              // 「还没申请过通知权限」（prompt）把用户误导去通知设置页 —— 缺通知权限
              // 已不再阻止前台服务启动，此时「prompt + 服务没起来」几乎必然是系统拒绝。
              const rejected = latest.foregroundStartError !== undefined && latest.foregroundStartError !== null
              setPermissionGuide(
                !rejected && latest.notificationPermission === 'prompt'
                  ? { kind: 'notification-permission' }
                  : { kind: 'background-blocked', feature: 'keepAlive' },
              )
            }
          },
        )
      }
      if (saved.overlayBallEnabled === true && overlayResult.status === 'fulfilled' && !overlayResult.value.serviceActive) {
        // 悬浮球走同一套节奏：原生侧把启动失败原因写进受控令牌（startError），
        // 宽限期过后复核状态，按「悬浮窗权限 / ROM 拦截 / 界面创建失败」分流到对应指引弹窗。
        recheckForegroundServiceAfterSettle(
          readOverlayBall,
          latest => {
            if (latest.enabled === true && !latest.serviceActive) {
              setPermissionGuide(
                latest.canDrawOverlays
                  ? overlayFailureGuide(latest.startError)
                  : { kind: 'overlay-permission' },
              )
            }
          },
        )
      }
    })
    return savedSuccessfully
  }, [notify, readOverlayBall, resyncSettingsDraft, run])

  /**
   * 申请前台服务通知权限。
   *
   * 权限被拒绝（或调用超时）时前台服务根本起不来，「后台保持」不会生效：
   * 部分厂商 ROM 甚至会因此终结应用进程，所以这里弹出可跳转的指引弹窗，
   * 用户可一键到系统通知设置页手动开启，而不是只看到一条会消失的提示。
   */
  const requestNotificationPermission = useCallback(() => {
    void run('notification-permission', async () => {
      const result = await withTimeout(
        runtimeBridge.requestNotificationPermission(),
        NOTIFICATION_PERMISSION_TIMEOUT_MS,
      )
      setKeepAlive(await runtimeBridge.getKeepAliveState())
      if (result === null || (!result.granted && result.supported)) {
        setPermissionGuide({ kind: 'notification-permission' })
      }
    })
  }, [run])

  const requestShizukuPermission = useCallback(() => {
    void run('shizuku-permission', async () => {
      const next = await runtimeBridge.requestShizukuPermission()
      setShizuku(next)
    }, t("Shizuku 已授权"), { kind: 'shizuku-permission' })
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

  const openAccessibilitySettings = useCallback(() => {
    void run('open-accessibility-settings', () => runtimeBridge.openAccessibilitySettings())
  }, [run])

  /**
   * 启用 / 停用一条自动化规则。
   *
   * 只改「启不启用」：规则内容、白名单、总开关都不动。白名单校验在原生侧（写规则必须过白名单），
   * 这里只把参数原样递过去，成功提示与失败提示都交给统一的 [run]。
   * 界面**不做乐观更新**：失败时不会留下一个「看起来已经关掉」的开关。
   */
  const setAccessibilityRuleEnabled = useCallback((options: {
    packageName: string
    ruleId: string
    enabled: boolean
  }) => run('set-accessibility-rule', async () => {
    await runtimeBridge.setAccessibilityAutomationRuleEnabled(options)
  }, options.enabled ? t("规则已启用") : t("规则已停用")), [run])

  const saveAccessibilityPackages = useCallback((packages: string[], password?: string, whitelistEnabled?: boolean) => {
    // 已设置验证密码却没交密码：在本地就挡下来，不白发一次必然失败的桥调用。
    // 错误文案里不出现用户输入的任何内容。
    if (accessibility.passwordConfigured && (password === undefined || password === '')) {
      notify(t("修改无障碍白名单需要先输入验证密码"), 'error')
      return
    }
    void run('save-accessibility-packages', async () => {
      const next = await runtimeBridge.setAccessibilityAutomationPackages(packages, password, whitelistEnabled)
      setAccessibility(next)
    }, t("无障碍应用白名单已保存"))
  }, [accessibility.passwordConfigured, notify, run])

  /**
   * 改自动化总开关：开始 / 暂停 / 继续 / 紧急停止。
   *
   * 这是**应用内独有的开启路径**——设备桥（AI 侧）没有开启自动化的能力，总开关不是「运行中」时
   * 写规则的命令会被原生侧拒绝。动作名在前端就是受控枚举，原生侧还会再拒收一次未知取值。
   * 失败提示由 [run] 统一负责；这里把 Promise 交回界面，让按钮能显示在途状态。
   */
  const setAccessibilityRuntime = useCallback(async (action: AccessibilityAutomationAction) => {
    await run('set-accessibility-runtime', async () => {
      const next = await runtimeBridge.setAccessibilityAutomationRuntime(action)
      setAccessibility(next)
    }, action === 'emergency_stop' ? t("已紧急停止自动化") : t("自动化总开关已更新"))
  }, [run])

  /** 设置（首次）或修改（已设置时）白名单验证密码；成功后原生返回最新状态。 */
  const saveAccessibilityPassword = useCallback((password: string, currentPassword?: string) => {
    void run('save-accessibility-password', async () => {
      const next = await runtimeBridge.setAccessibilityPassword(password, currentPassword)
      setAccessibility(next)
    }, t("验证密码已更新"))
  }, [run])

  /** 清除验证密码：必须带当前密码，白名单保持不变。 */
  const clearAccessibilityPassword = useCallback((currentPassword: string) => {
    void run('clear-accessibility-password', async () => {
      const next = await runtimeBridge.clearAccessibilityPassword(currentPassword)
      setAccessibility(next)
    }, t("验证密码已清除"))
  }, [run])

  /**
   * 忘记密码时的退路：交给系统生物识别/锁屏密码确认。
   *
   * 只清密码、保留白名单——这条路是「用户本人证明了身份」，不是绕过保护。
   */
  const resetAccessibilityPassword = useCallback(() => {
    void run('reset-accessibility-password', async () => {
      const next = await runtimeBridge.resetAccessibilityPasswordWithBiometric()
      setAccessibility(next)
    }, t("验证密码已重置"))
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

  /** 跳到本应用的系统通知设置页：通知权限被拒后只能由用户手动开启。 */
  const openNotificationSettings = useCallback(() => {
    void run('open-notification-settings', () => runtimeBridge.openNotificationSettings())
  }, [run])

  /**
   * 跳到应用详情页。
   *
   * HyperOS 的「省电策略 / 自启动」没有可稳定直达的公开 Intent，先把用户送到应用详情页，
   * 弹窗文案里写清点哪一项；用户返回应用后由可见性刷新重新复核服务状态。
   */
  const openApplicationSettings = useCallback(() => {
    void run('open-application-settings', () => runtimeBridge.openApplicationSettings())
  }, [run])

  /**
   * 跳转到系统「所有文件访问」设置页。
   *
   * 这是投递区与目录白名单**共用**的解锁入口：该权限不会弹运行时对话框，只能由用户手动开启。
   * 本回调只负责跳转与重读，不承诺一定授权成功——用户可能直接返回而不开启，
   * 那时界面仍显示原来的档位。
   */
  const openAllFilesAccessSettings = useCallback(() => {
    void run('open-all-files-access', async () => {
      await runtimeBridge.openAllFilesAccessSettings()
      await readMailbox().catch(() => {
        // 刚跳出去还没授权时会读到「需要授权」，属正常结果，不当作错误提示。
      })
    })
  }, [readMailbox, run])

  /**
   * 一键导入：inbox 的 tar → 工作区 `mailbox-import/`。
   *
   * 必须由用户点击触发（没有任何自动搬运路径）：导入会替换工作区里的落点目录，
   * 静默执行会让用户在自己的工作区里看到来历不明的目录。
   */
  const importMailbox = useCallback(() => {
    void run('mailbox-import', async () => {
      const result = await runtimeBridge.importMailbox()
      setLastMailboxImport(result)
      await readMailbox().catch(() => {
        // 搬运已成功；状态重读失败不影响结果展示。
      })
    }, t("投递区已导入工作区"))
  }, [readMailbox, run])

  /** 一键导出：工作区 → outbox 的 tar + manifest + sha256；同样必须由用户点击触发。 */
  const exportMailbox = useCallback(() => {
    if (filesRoot.kind !== 'mailbox' || mailbox?.available !== true) return
    const root = filesRoot.root
    const destinationDirectory = root === 'outbox' && mailboxDirectory?.root === root ? mailboxDirectory.path : undefined
    void run('mailbox-export', async () => {
      const result = await runtimeBridge.exportMailbox(undefined, destinationDirectory)
      setLastMailboxExport(result)
      await readMailbox().catch(() => {
        // 搬运已成功；状态重读失败不影响结果展示。
      })
      if (root === 'outbox') await readMailboxDirectory(root, destinationDirectory).catch(() => {
        // 产物已导出；列表重读失败可由用户手动刷新。
      })
    }, t("投递区已导出到 outbox"))
  }, [filesRoot, mailbox, mailboxDirectory, readMailbox, readMailboxDirectory, run])

  /** 重新检查投递区可用性：用户在系统设置里授权后手动触发，避免反复进出页面。 */
  const refreshMailbox = useCallback(() => {
    void run('mailbox-refresh', async () => {
      await readMailbox()
    })
  }, [readMailbox, run])

  /**
   * 切换「文件管理」页浏览的根。
   *
   * 换根只换光标，不动任何文件：「看」永远不写。投递区走既有的受控目录读取，
   * 共享目录走白名单条目的挂载点（`guestPath` 由白名单提供，界面从不自己拼路径）。
   */
  const selectFilesRoot = useCallback((next: FilesRoot) => {
    void run('files-root', async () => {
      filesRootRef.current = next
      setFilesRoot(next)
      mailboxDirectoryReadRevision.current += 1
      storageDirectoryReadRevision.current += 1
      setMailboxDirectory(null)
      setStorageDirectory(null)
      setMailboxDirectoryReadFailed(false)
      setStorageDirectoryReadFailed(false)
      if (next.kind === 'mailbox') await readMailboxDirectory(next.root)
      else await readStorageDirectory(next.guestPath)
    })
  }, [readMailboxDirectory, readStorageDirectory, run])

  /** 进入当前根下的子目录（不传表示回到该根目录）；路径由桥接层与原生策略再次校验。 */
  const openFilesDirectory = useCallback((path?: string) => {
    void run(filesRoot.kind === 'mailbox' ? 'mailbox-directory' : FILES_STORAGE_DIRECTORY_BUSY_ID, async () => {
      if (filesRoot.kind === 'mailbox') await readMailboxDirectory(filesRoot.root, path)
      else await readStorageDirectory(filesRoot.guestPath, path)
    })
  }, [filesRoot, readMailboxDirectory, readStorageDirectory, run])

  /**
   * 在当前目录新建文件夹：名称必须由用户明确输入（避免静默写入公共存储）。
   *
   * 校验复用投递区那一个 `assertMailboxSubdirectory`（平台层只有这一份实现），并要求名称是
   * **单层**的：目标是「当前目录下的一个名字」，写成 `a/b` 会把子目录悄悄建到别处。
   * 两条链路真正的差别只有最后那次桥调用；原生侧同样是同一份实现（CREATE_NEW 语义，不覆盖）。
   */
  const createFilesFolder = useCallback(() => {
    if (busyRef.current !== null) return
    const browsingMailbox = filesRoot.kind === 'mailbox'
    if (browsingMailbox && mailbox?.available !== true) return
    const directory = browsingMailbox
      ? mailboxDirectory?.root === filesRoot.root ? mailboxDirectory : null
      : storageDirectory?.guestPath === filesRoot.guestPath && storageDirs?.entries.some(entry => entry.guestPath === filesRoot.guestPath && entry.path === filesRoot.path && entry.available) ? storageDirectory : null
    if (directory === null || (browsingMailbox ? mailboxDirectoryReadFailed : storageDirectoryReadFailed)) return
    if (filesRoot.kind === 'storage' && !storageDirs?.entries.some(entry => entry.guestPath === filesRoot.guestPath && entry.path === filesRoot.path && entry.available)) return
    const currentPath = directory.path
    const raw = window.prompt(t("请输入新文件夹名称（仅支持单层名称）"))
    if (raw === null) return
    const name = raw.trim()
    const target = currentPath ? `${currentPath}/${name}` : name
    try {
      const normalized = assertMailboxSubdirectory(target)
      if (normalized === undefined || normalized.split('/').length !== (currentPath ? currentPath.split('/').length + 1 : 1)) {
        throw new Error('文件夹名称格式无效')
      }
    } catch (error) {
      notify(errorMessage(error), 'error')
      return
    }
    if (filesRoot.kind === 'mailbox') {
      const root = filesRoot.root
      void run('mailbox-folder-create', async () => {
        const next = await runtimeBridge.createMailboxFolder(root, target)
        if (next.root !== root) throw new Error('投递区目录响应与请求不一致')
        setMailboxDirectory(next)
        setMailboxDirectoryReadFailed(false)
      }, t("投递区文件夹已创建"))
      return
    }
    const guestPath = filesRoot.guestPath
    void run(FILES_STORAGE_FOLDER_CREATE_BUSY_ID, async () => {
      const next = await runtimeBridge.createStorageFolder(guestPath, target)
      if (next.guestPath !== guestPath || !storageDirs?.entries.some(entry => entry.guestPath === guestPath && entry.path === filesRoot.path && entry.available)) {
        throw new Error('共享目录响应与请求不一致')
      }
      setStorageDirectory(next)
      setStorageDirectoryReadFailed(false)
    }, t("文件夹已创建"))
  }, [filesRoot, mailbox, mailboxDirectory, mailboxDirectoryReadFailed, notify, run, storageDirectory, storageDirectoryReadFailed, storageDirs])

  /**
   * 目录白名单操作（添加/移除）的统一收口。
   *
   * 与 [run] 的差别只有一处，但很关键：**用户取消不是故障**。在 SAF 选择器里点返回会以
   * `STORAGE_DIR_CANCELLED` 拒绝，那什么都没发生过，弹一条错误提示会让用户以为坏了。
   * 其余错误码一律换成可操作的中文（见 [STORAGE_DIR_ERROR_MESSAGES]），不把码甩给用户。
   *
   * 成功与失败都用**桥返回的最新状态**覆盖本地快照，不做本地增量合并：条目的序号来自
   * 原生侧的持久化槽位，界面不再按数组位置生成挂载点。
   */
  const runStorageDirAction = useCallback(async (id: string, operation: () => Promise<StorageDirsState>): Promise<void> => {
    if (busyRef.current !== null) return
    if (filesRootRef.current.kind === 'storage') {
      storageDirectoryReadRevision.current += 1
      setStorageDirectory(null)
    }
    busyRef.current = id
    setBusy(id)
    try {
      const next = await operation()
      setStorageDirs(next)
      const root = filesRootRef.current
      if (root.kind === 'storage' && !next.entries.some(entry => entry.guestPath === root.guestPath && entry.path === root.path && entry.available)) {
        storageDirectoryReadRevision.current += 1
        setStorageDirectory(null)
      }
      // 这一次是真的拿到了原生快照：顺手清掉读取失败标记，界面不必再让用户手动重试。
      setStorageDirsReadFailed(false)
    } catch (error) {
      const code = storageDirErrorCode(error)
      if (code !== STORAGE_DIR_CANCELLED) notify(storageDirMessage(code, error), 'error')
    } finally {
      busyRef.current = null
      setBusy(null)
    }
  }, [notify])

  /**
   * 添加一条目录：原生侧弹系统 SAF 目录选择器（`ACTION_OPEN_DOCUMENT_TREE`）。
   *
   * 选择与校验全在原生侧（映射、规范化、准入规则都只有那一份实现），这里只负责发起与
   * 用返回值刷新界面；用户在系统界面里取消时静默返回，什么都不改也不提示。
   */
  const addStorageDirectory = useCallback(() => {
    void runStorageDirAction(STORAGE_DIR_ADD_BUSY_ID, () => runtimeBridge.addStorageDirectory())
  }, [runStorageDirAction])

  /** 移除一条目录：用宿主路径定位，不能把已经复用的槽位当作原目录。 */
  const removeStorageDirectory = useCallback((path: string) => {
    void runStorageDirAction(storageDirRemoveBusyId(path), () => runtimeBridge.removeStorageDirectory(path))
  }, [runStorageDirAction])

  const screen = (() => {
    switch (activeView) {
      case 'conversation':
        return <ConversationScreen busy={busy} keepAlive={keepAlive} runtime={runtime} onInstall={installRuntime} onLaunch={launchHarness} onStop={stopRuntime} onOpenTerminal={() => setActiveView('terminal')} onUpdate={requestRuntimeUpdate} />
      case 'sessions':
        return <SessionManager onBack={() => backToView('conversation')} onOpenHarness={launchHarness} />
      case 'terminal':
        return <TerminalScreen bridge={runtimeBridge} fontSize={settings?.terminalFontSize ?? 14} onAuthorize={requestShizukuPermission} onBack={() => backToView('settings')} onConnect={connectShizuku} onError={terminalError} onOpenEnvironment={() => setActiveView('environment')} onOpenShizuku={openShizuku} runtime={runtime} shizuku={shizuku} />
      case 'plugins':
        return <PluginSettings bridge={runtimeBridge} runtime={runtime} onBack={() => backToView('settings')} />
      case 'environment':
        return <EnvironmentScreen busy={busy} bundledSource={settings === null || settings.manifestUrl.trim() === ''} runtime={runtime} onBack={() => backToView('settings')} onInstall={installRuntime} onReset={() => setResetOpen(true)} onStart={launchHarness} onStop={stopRuntime} onUpdate={requestRuntimeUpdate} />
      case 'versions':
        return (
        <RuntimeVersionsScreen
          runtime={runtime}
          loadVersions={loadRuntimeVersions}
          switchVersion={switchRuntimeVersion}
          deleteVersion={deleteRuntimeVersion}
          setPinned={setRuntimeVersionPinned}
          downloadRelease={downloadRuntimeRelease}
          listReleases={listRuntimeReleases}
          refreshRuntime={refreshRuntimeState}
          notify={notify}
          onBack={() => backToView('settings')}
          loadSnapshots={loadSessionSnapshots}
          createSnapshot={createSessionSnapshot}
          restoreSnapshot={restoreSessionSnapshot}
          deleteSnapshot={deleteSessionSnapshot}
          loadUpdate={loadAppUpdate}
          setUpdateChannel={setAppUpdateChannel}
          downloadUpdate={downloadAppUpdate}
          installUpdate={installAppUpdate}
          subscribeUpdateProgress={runtimeBridge.addAppUpdateProgressListener}
          openInstallSettings={openAppUpdateInstallSettings}
          reminderEnabled={appUpdateReminder.enabled}
          onReminderEnabledChange={appUpdateReminder.setEnabled}
          reminderStorageAvailable={appUpdateReminder.storageAvailable}
        />
      )
      case 'files':
        return <FilesScreen busy={busy} filesRoot={filesRoot} lastMailboxExport={lastMailboxExport} lastMailboxImport={lastMailboxImport} mailbox={mailbox} mailboxDirectory={mailboxDirectory} mailboxDirectoryReadFailed={mailboxDirectoryReadFailed} mailboxReadFailed={mailboxReadFailed} storageAccess={storageAccess} storageDirectory={storageDirectory} storageDirectoryReadFailed={storageDirectoryReadFailed} storageDirs={storageDirs} storageDirsReadFailed={storageDirsReadFailed} onAddStorageDirectory={addStorageDirectory} onBack={() => backToView('settings')} onCreateFilesFolder={createFilesFolder} onExportMailbox={exportMailbox} onImportMailbox={importMailbox} onOpenAllFilesAccess={openAllFilesAccessSettings} onOpenFilesDirectory={openFilesDirectory} onRefreshMailbox={refreshMailbox} onRemoveStorageDirectory={removeStorageDirectory} onSelectFilesRoot={selectFilesRoot} />
      case 'settings':
        return <SettingsHomeScreen busy={busy} diagnostic={diagnostic} keepAlive={keepAlive} runtime={runtime} shizuku={shizuku} onLaunch={launchHarness} onOpenPage={openSettings} onOpenView={setActiveView} />
      default: {
        const page = settingsPageOf(activeView)
        if (page === null) return null
        return <SettingsScreen key={`${page}-${settingsReadStatus}`} accessibility={accessibility} busy={busy} diagnostic={diagnostic} draft={settingsDraft} keepAlive={keepAlive} lastStop={lastStop} loadDiagnosticLog={loadDiagnosticLog} loadHarnessLog={loadHarnessLog} overlayBall={overlayBall} overlayBallReadFailed={overlayBallReadFailed} onDraftChange={updateSettingsDraft} page={page} runSelfCheck={runSelfCheck} runtime={runtime} settingsReadStatus={settingsReadStatus} shizuku={shizuku} onAuthorize={requestShizukuPermission} onBack={() => backToView('settings')} onClearDiagnostic={clearDiagnostic} onConnect={connectShizuku} onDiagnosticSettings={saveDiagnosticSettings} onLaunch={launchHarness} onLaunchConfirmed={launchHarnessConfirmed} onOpenAccessibilitySettings={openAccessibilitySettings} onOpenOverlaySettings={openOverlaySettings} onOpenShizuku={openShizuku} onOpenEnvironment={() => setActiveView('environment')} onOpenDeviceShell={() => setActiveView('settings-device-shell')} onReloadSettings={() => openSettings(page)} onRequestNotificationPermission={requestNotificationPermission} onSave={saveSettings} onSaveAccessibilityPackages={saveAccessibilityPackages} onSaveAccessibilityPassword={saveAccessibilityPassword} onSetAccessibilityRuntime={setAccessibilityRuntime} onSetAutomationRuleEnabled={setAccessibilityRuleEnabled} onClearAccessibilityPassword={clearAccessibilityPassword} onResetAccessibilityPassword={resetAccessibilityPassword} onShareDiagnostic={shareDiagnostic} />
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
      <AppBackground />
      <AppSidebar activeView={activeView} onNavigate={setActiveView} />
      <div className="app-frame">
        <header className="mobile-header">
          <div className="header-brand-group"><Brand /></div>
          <div className="header-actions">
            <PhaseBadge phase={runtime.phase} />
            {activeView === 'conversation' && (
              <button className="icon-button header-settings" type="button" aria-label={t("打开应用设置")} title={t("应用设置")} onClick={() => setActiveView('settings')}>
                <Settings2 size={19} />
              </button>
            )}
          </div>
        </header>

        {/* 切换页面后从顶部重新渲染，不保留上一页的滚动位置。 */}
        <main className="app-main" key={activeView}>{screen}</main>
      </div>
      <BottomNavigation activeView={activeView} onNavigate={setActiveView} />

      {resetOpen && <ResetDialog busy={busy === 'reset'} onCancel={() => setResetOpen(false)} onConfirm={confirmReset} />}
      {permissionGuide !== null && (
        <PermissionGuideDialog
          guide={permissionGuide}
          busy={busy !== null}
          onClose={() => setPermissionGuide(null)}
          onOpenOverlaySettings={() => { setPermissionGuide(null); openOverlaySettings() }}
          onOpenNotificationSettings={() => { setPermissionGuide(null); openNotificationSettings() }}
          onOpenApplicationSettings={() => { setPermissionGuide(null); openApplicationSettings() }}
          onOpenShizuku={() => { setPermissionGuide(null); openShizuku() }}
        />
      )}
      {pendingInstall !== null && <UpdateDialog busy={busy === 'install'} version={pendingInstall.version} onCancel={() => setPendingInstall(null)} onConfirm={confirmPendingInstall} />}
      {appUpdateReminder.available !== null && (
        <AppUpdateReminderDialog
          version={appUpdateReminder.available.version}
          onDismiss={appUpdateReminder.dismiss}
          onUpdate={() => {
            appUpdateReminder.dismiss()
            setActiveView('versions')
          }}
        />
      )}

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
              // 受限 WebView 里 localStorage 可能不可用；首次引导关闭的状态仍在内存中即可。
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
