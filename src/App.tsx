import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  ArrowLeft,
  Bot,
  CheckCircle2,
  ChevronRight,
  CloudDownload,
  Cpu,
  Database,
  Download,
  ExternalLink,
  Gauge,
  HardDrive,
  KeyRound,
  Loader2,
  LockKeyhole,
  MonitorSmartphone,
  Play,
  Power,
  RefreshCw,
  RotateCcw,
  Save,
  Settings2,
  ShieldCheck,
  Smartphone,
  Square,
  SquareTerminal,
  Trash2,
  Waves,
  Wifi,
  X,
} from 'lucide-react'
import { TerminalPanel } from './components/TerminalPanel'
import { runtimeBridge } from './platform/native'
import type {
  RuntimePhase,
  RuntimeProgress,
  RuntimeSettings,
  RuntimeState,
  ShizukuState,
  TerminalKind,
} from './platform/types'

type AppView = 'conversation' | 'settings' | 'terminal' | 'environment'
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
const MAX_NOTICE_CHARACTERS = 240
const RESET_CONFIRMATION = 'RESET_RUNTIME'
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
  INSTALL_IN_PROGRESS: '运行时安装正在进行。',
  INSTALL_CANCELLED: '运行时安装已取消，再次安装时可继续下载。',
  INSTALL_FAILED: '运行时安装失败，请稍后重试。',
  RUNTIME_BUSY: '请先停止 Harness 和 Ubuntu 终端。',
  RUNTIME_CORRUPTED: '运行时文件已损坏，请重置运行时后重新安装。',
  ROOTFS_LINKS_CORRUPTED: '运行时归档的关键符号链接缺失或损坏，请更换运行时来源后重新安装。',
  RUNNER_UNAVAILABLE: 'APK 未包含当前设备架构所需的运行器。',
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
  HARNESS_PORT_IN_USE: 'Harness 本机端口已被占用，请停止占用端口的程序后重试。',
  HARNESS_MODULE_MISSING: 'Harness 运行模块不完整。',
  HARNESS_NATIVE_MODULE_FAILED: 'Harness 原生模块无法在当前设备运行。',
  HARNESS_START_TIMEOUT: 'Harness 首次启动超时，请重试或先打开 Ubuntu 终端检查环境。',
  HARNESS_EXITED: 'Harness 在完成启动前已退出。',
}

function runtimeErrorMessage(errorCode?: string): string {
  if (errorCode === undefined) return '请重试启动；重置环境仅用于清除用户数据。'
  return RUNTIME_ERROR_MESSAGES[errorCode] ?? UNKNOWN_RUNTIME_ERROR_MESSAGE
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
  if (!(error instanceof Error)) return '操作失败，请稍后重试'
  const message = Array.from(error.message.trim())
    .map(character => {
      const code = character.charCodeAt(0)
      return code <= 31 || code === 127 ? ' ' : character
    })
    .slice(0, MAX_NOTICE_CHARACTERS)
    .join('')
    .trim()
  return message === '' ? '操作失败，请稍后重试' : message
}

function Brand() {
  return (
    <div className="brand" aria-label="DeepSeek Harness">
      <span className="brand-symbol" aria-hidden="true"><Waves size={22} strokeWidth={2.3} /></span>
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
      {meta.label}
    </span>
  )
}

function runtimeInstalled(runtime: RuntimeState): boolean {
  return runtime.installedVersion !== undefined || ['ready', 'running', 'stopping'].includes(runtime.phase)
}

function runtimeTransitioning(runtime: RuntimeState): boolean {
  return ['preparing', 'downloading', 'verifying', 'extracting'].includes(runtime.phase)
}

interface ConversationScreenProps {
  busy: string | null
  runtime: RuntimeState
  onInstall: () => void
  onLaunch: () => void
  onOpenSettings: () => void
  onOpenTerminal: () => void
}

function ConversationScreen({ busy, runtime, onInstall, onLaunch, onOpenSettings, onOpenTerminal }: ConversationScreenProps) {
  const installed = runtimeInstalled(runtime)
  const transitioning = runtimeTransitioning(runtime)
  const progress = runtime.totalBytes > 0
    ? Math.min(100, Math.round((runtime.downloadedBytes / runtime.totalBytes) * 100))
    : 0

  return (
    <div className="screen conversation-gate">
      <div className="screen-heading">
        <div>
          <p className="eyebrow">Harness 对话</p>
          <h1>{runtime.phase === 'error' ? '运行环境需要处理' : installed ? '正在进入工作区' : '初始化 Ubuntu'}</h1>
        </div>
        <PhaseBadge phase={runtime.phase} />
      </div>

      <section className="launch-panel">
        <span className="launch-icon" aria-hidden="true">
          {busy === 'launch' || transitioning ? <Loader2 className="spin" size={30} /> : <Bot size={30} />}
        </span>
        <div className="launch-copy">
          <h2>
            {runtime.phase === 'error'
              ? '运行环境需要处理'
              : transitioning
              ? PHASE_META[runtime.phase].label
              : installed
                ? '正在打开 Harness 对话'
                : '首次使用需要准备运行环境'}
          </h2>
          <p>
            {runtime.phase === 'error'
              ? runtimeErrorMessage(runtime.errorCode)
              : transitioning
              ? `${formatBytes(runtime.downloadedBytes)} / ${formatBytes(runtime.totalBytes)}`
              : installed
                ? '应用会自动启动本机服务并进入对话，无需通过浏览器访问。'
                : '安装包会校验 Ubuntu 运行时，远程下载中断后可继续。'}
          </p>
        </div>

        {transitioning && (
          <div className="download-progress gate-progress" aria-live="polite">
            <div className="progress-copy"><span>{PHASE_META[runtime.phase].label}</span><strong>{runtime.totalBytes > 0 ? `${progress}%` : '处理中'}</strong></div>
            <div className="progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}>
              <span style={{ width: `${runtime.totalBytes > 0 ? progress : 100}%` }} className={runtime.totalBytes > 0 ? '' : 'indeterminate'} />
            </div>
          </div>
        )}

        <div className="launch-actions">
          {!installed && !transitioning && (
            <button className="button button-primary" type="button" onClick={onInstall} disabled={busy !== null || !runtime.runnerAvailable}>
              {busy === 'install' ? <Loader2 className="spin" size={18} /> : <Download size={18} />}
              {runtime.phase === 'error' ? '重试安装' : '安装并进入对话'}
            </button>
          )}
          {installed && !transitioning && busy !== 'launch' && (
            <button className="button button-primary" type="button" onClick={onLaunch} disabled={busy !== null}>
              <Play size={18} fill="currentColor" />重新打开对话
            </button>
          )}
          {runtime.phase === 'error' && installed && (
            <button className="button button-secondary" type="button" onClick={onOpenTerminal} disabled={busy !== null}>
              <SquareTerminal size={18} />打开终端排查
            </button>
          )}
          <button className="button button-secondary" type="button" onClick={onOpenSettings} disabled={busy === 'install'}>
            <Settings2 size={18} />应用设置
          </button>
        </div>
      </section>

      {!runtime.runnerAvailable && (
        <div className="inline-alert warning" role="alert">
          <AlertTriangle size={19} />
          <div><strong>本机运行器不可用</strong><span>请安装包含当前 ARM64 运行器的应用版本。</span></div>
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
}

function EnvironmentScreen({ busy, bundledSource, runtime, onBack, onInstall, onReset, onStart, onStop }: EnvironmentScreenProps) {
  const inProgress = ['preparing', 'downloading', 'verifying', 'extracting'].includes(runtime.phase)
  const installed = runtime.installedVersion !== undefined || runtime.phase === 'ready' || runtime.phase === 'running'
  const progress = runtime.totalBytes > 0
    ? Math.min(100, Math.round((runtime.downloadedBytes / runtime.totalBytes) * 100))
    : 0

  const measurableProgress = ['preparing', 'downloading', 'extracting'].includes(runtime.phase) && runtime.totalBytes > 0
  const steps: Array<{ id: string; label: string }> = [
    { id: 'acquire', label: bundledSource ? '读取' : '下载' },
    { id: 'verify', label: '校验' },
    { id: 'install', label: '安装' },
    { id: 'ready', label: '就绪' },
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
          <p className="eyebrow">本机运行时</p>
          <h1>Ubuntu 环境</h1>
        </div>
        <div className="heading-actions">
          <PhaseBadge phase={runtime.phase} />
          <button className="icon-button" type="button" aria-label="返回设置" title="返回设置" onClick={onBack}><ArrowLeft size={19} /></button>
        </div>
      </div>

      <section className="runtime-overview">
        <div className="runtime-title-row">
          <span className="runtime-logo" aria-hidden="true"><MonitorSmartphone size={27} /></span>
          <div>
            <h2>Ubuntu 24.04</h2>
            <p>{runtime.installedVersion === undefined ? '等待安装' : `运行时 ${runtime.installedVersion}`}</p>
          </div>
        </div>

        {inProgress && (
          <div className="download-progress" aria-live="polite">
            <div className="progress-copy">
              <span>{PHASE_META[runtime.phase].label}</span>
              <strong>{measurableProgress ? `${progress}%` : '处理中'}</strong>
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

        <div className="install-steps" aria-label="安装阶段">
          {steps.map((step, index) => {
            const complete = installed || currentStep > index
            const active = currentStep === index && !installed
            return (
              <div className={`install-step ${complete ? 'complete' : ''} ${active ? 'active' : ''}`} key={step.id}>
                <span>{complete ? <CheckCircle2 size={17} /> : index + 1}</span>
                <small>{step.label}</small>
              </div>
            )
          })}
        </div>

        <div className="runtime-actions">
          {!installed && !inProgress && (
            <button className="button button-primary" type="button" onClick={onInstall} disabled={busy !== null || !runtime.runnerAvailable}>
              {busy === 'install' ? <Loader2 className="spin" size={18} /> : <CloudDownload size={18} />}
              {bundledSource ? '安装内置环境' : '下载并安装'}
            </button>
          )}
          {runtime.phase === 'ready' && (
            <button className="button button-primary" type="button" onClick={onStart} disabled={busy !== null}>
              {busy === 'launch' ? <Loader2 className="spin" size={18} /> : <Play size={18} fill="currentColor" />}
              启动
            </button>
          )}
          {runtime.phase === 'running' && (
            <button className="button button-secondary" type="button" onClick={onStop} disabled={busy !== null}>
              {busy === 'stop' ? <Loader2 className="spin" size={18} /> : <Power size={18} />}
              停止
            </button>
          )}
          {(installed || runtime.phase === 'error') && (
            <button className="button button-danger-quiet" type="button" onClick={onReset} disabled={busy !== null}>
              <RotateCcw size={18} />
              重置
            </button>
          )}
        </div>
      </section>

      {runtime.phase === 'error' && (
        <div className="inline-alert danger" role="alert">
          <AlertTriangle size={19} />
          <div><strong>{installed ? '运行环境启动失败' : '安装未完成'}</strong><span>{runtimeErrorMessage(runtime.errorCode)}</span></div>
        </div>
      )}

      <section className="detail-section" aria-labelledby="environment-details">
        <h2 id="environment-details">环境详情</h2>
        <div className="detail-list">
          <div className="detail-row"><span><Cpu size={18} />架构</span><strong>{runtime.architecture}</strong></div>
          <div className="detail-row"><span><Database size={18} />{inProgress ? '当前阶段总量' : '镜像大小'}</span><strong>{formatBytes(runtime.totalBytes)}</strong></div>
          <div className="detail-row"><span><Gauge size={18} />本机运行器</span><strong>{runtime.runnerAvailable ? '可用' : '不可用'}</strong></div>
          <div className="detail-row"><span><LockKeyhole size={18} />网络入口</span><strong>应用内</strong></div>
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
          <p className="eyebrow">交互会话</p>
          <h1>终端</h1>
        </div>
        <div className="heading-actions">
          <button className="icon-button" type="button" title="重新连接" aria-label="重新连接终端" onClick={() => setEpoch(value => value + 1)} disabled={!ready}>
            <RefreshCw size={19} />
          </button>
          <button className="icon-button" type="button" title="返回设置" aria-label="返回设置" onClick={onBack}>
            <ArrowLeft size={19} />
          </button>
        </div>
      </div>

      <div className="segmented" role="tablist" aria-label="终端类型">
        <button type="button" role="tab" aria-selected={kind === 'ubuntu'} className={kind === 'ubuntu' ? 'active' : ''} onClick={() => setKind('ubuntu')}>
          <SquareTerminal size={17} />Ubuntu
        </button>
        <button type="button" role="tab" aria-selected={kind === 'device'} className={kind === 'device' ? 'active' : ''} onClick={() => setKind('device')}>
          <Smartphone size={17} />设备 Shell
        </button>
      </div>

      {ready ? (
        <TerminalPanel key={`${kind}-${epoch}`} bridge={bridge} fontSize={fontSize} kind={kind} onError={onError} />
      ) : kind === 'ubuntu' ? (
        <div className="empty-terminal">
          <span><HardDrive size={27} /></span>
          <h2>Ubuntu 尚未就绪</h2>
          <button className="button button-primary" type="button" onClick={onOpenEnvironment}>
            前往环境
          </button>
        </div>
      ) : (
        <div className="empty-terminal">
          <span><KeyRound size={27} /></span>
          <h2>{!shizuku.installed ? '未安装 Shizuku' : !shizuku.running ? `Shizuku 未运行${shizuku.version ? '（v' + shizuku.version + '）' : ''}` : shizuku.permission !== 'granted' ? '需要 Shizuku 授权' : 'Shizuku 连接未就绪'}</h2>
          {shizuku.installed && !shizuku.running && (
            <p className="shizuku-hint">Shizuku 服务不会自动启动：请在 Shizuku App 内通过无线调试或 adb 启动服务（设备重启后需重新启动）。</p>
          )}
          {shizuku.installed ? (
            <button className="button button-primary" type="button" onClick={!shizuku.running ? onOpenShizuku : shizuku.permission === 'granted' ? onConnect : onAuthorize}>
              {shizuku.permission === 'granted' && shizuku.running ? <RefreshCw size={18} /> : <ShieldCheck size={18} />}
              {!shizuku.running ? '打开 Shizuku' : shizuku.permission === 'granted' ? '连接 Shizuku' : '请求授权'}
            </button>
          ) : (
            <button className="button button-secondary" type="button" onClick={onOpenShizuku}>
              <ExternalLink size={18} />打开 Shizuku
            </button>
          )}
        </div>
      )}
    </div>
  )
}

interface SettingsScreenProps {
  busy: string | null
  runtime: RuntimeState
  settings: RuntimeSettings | null
  shizuku: ShizukuState
  onAuthorize: () => void
  onConnect: () => void
  onLaunch: () => void
  onOpenEnvironment: () => void
  onOpenShizuku: () => void
  onOpenTerminal: () => void
  onSave: (settings: RuntimeSettings) => void
  onStop: () => void
}

function SettingsScreen({ busy, runtime, settings, shizuku, onAuthorize, onConnect, onLaunch, onOpenEnvironment, onOpenShizuku, onOpenTerminal, onSave, onStop }: SettingsScreenProps) {
  const [draft, setDraft] = useState<RuntimeSettings | null>(settings)

  useEffect(() => setDraft(settings), [settings])

  if (draft === null) {
    return <div className="screen loading-screen"><Loader2 className="spin" size={24} /><span>正在读取设置</span></div>
  }

  const shizukuLabel = !shizuku.installed
    ? '未安装'
    : !shizuku.running
      ? '未运行' + (shizuku.version ? '（v' + shizuku.version + '）' : '')
      : shizuku.permission === 'granted'
        ? shizuku.connected ? '已连接' : '已授权'
        : shizuku.permission === 'denied'
          ? '已拒绝'
          : '待授权'

  return (
    <div className="screen settings-screen">
      <div className="screen-heading management-heading">
        <div>
          <p className="eyebrow">应用管理</p>
          <h1>设置</h1>
        </div>
        <button className="button button-primary conversation-button" type="button" onClick={onLaunch} disabled={busy !== null || !runtimeInstalled(runtime)}>
          {busy === 'launch' ? <Loader2 className="spin" size={18} /> : <Bot size={18} />}
          返回对话
        </button>
      </div>

      <section className="management-list" aria-label="运行环境管理">
        <div className="management-service">
          <span className="management-icon dark"><Bot size={20} /></span>
          <span className="management-copy">
            <strong>Harness 服务</strong>
            <small>{runtime.phase === 'running' ? '正在本机运行' : runtimeInstalled(runtime) ? '已停止，可随时启动' : '等待 Ubuntu 环境'}</small>
          </span>
          {runtime.phase === 'running' ? (
            <button className="button button-danger-quiet compact-button" type="button" onClick={onStop} disabled={busy !== null}><Square size={16} />停止</button>
          ) : (
            <PhaseBadge phase={runtime.phase} />
          )}
        </div>
        <button className="management-row" type="button" onClick={onOpenEnvironment}>
          <span className="management-icon green"><HardDrive size={20} /></span>
          <span className="management-copy"><strong>Ubuntu 运行时</strong><small>安装进度、版本、来源与重置</small></span>
          <ChevronRight size={18} />
        </button>
        <button className="management-row" type="button" onClick={onOpenTerminal}>
          <span className="management-icon blue"><SquareTerminal size={20} /></span>
          <span className="management-copy"><strong>终端与设备 Shell</strong><small>Ubuntu 终端、Shizuku 和本机 adb</small></span>
          <ChevronRight size={18} />
        </button>
      </section>

      <form className="settings-form" onSubmit={event => { event.preventDefault(); onSave(draft) }}>
        <section className="settings-section" aria-labelledby="model-settings">
          <div className="section-title">
            <span className="section-icon"><Bot size={19} /></span>
            <div><h2 id="model-settings">模型</h2><p>API Key 只保存在本机，注入 Harness 运行时（DEEPSEEK_API_KEY）</p></div>
          </div>
          <label className="field">
            <span>DeepSeek API Key</span>
            <input
              type="password"
              autoComplete="off"
              spellCheck={false}
              maxLength={200}
              placeholder={draft.apiKey ? '已配置（' + draft.apiKey.slice(0, 6) + '…），输入新值覆盖' : '未配置，填入 sk-...'}
              value={draft.apiKey ?? ''}
              onChange={event => setDraft({ ...draft, apiKey: event.target.value })}
            />
          </label>
          <label className="toggle-row">
            <span><strong>打开应用时自动启动 Harness</strong><small>关闭后需手动点「返回对话」启动</small></span>
            <input
              type="checkbox"
              role="switch"
              checked={draft.autoLaunch}
              onChange={event => setDraft({ ...draft, autoLaunch: event.target.checked })}
            />
          </label>
        </section>

          <div className="section-title">
            <span className="section-icon"><CloudDownload size={19} /></span>
            <div><h2 id="download-settings">运行时来源</h2><p>官方包已固定下载源；仅内嵌开发包可留空</p></div>
          </div>
          <label className="field">
            <span>清单地址</span>
            <input
              type="url"
              inputMode="url"
              autoCapitalize="none"
              autoComplete="off"
              maxLength={2048}
              value={draft.manifestUrl}
              onChange={event => setDraft({ ...draft, manifestUrl: event.target.value })}
            />
          </label>
          <label className="field">
            <span>清单 SHA-256</span>
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
              value={draft.manifestSha256}
              onChange={event => setDraft({ ...draft, manifestSha256: event.target.value })}
            />
          </label>
        </section>

        <section className="settings-section" aria-labelledby="terminal-settings">
          <div className="section-title">
            <span className="section-icon"><SquareTerminal size={19} /></span>
            <div><h2 id="terminal-settings">终端</h2><p>应用内会话显示</p></div>
          </div>
          <label className="range-field">
            <span><strong>字号</strong><small>{draft.terminalFontSize}px</small></span>
            <input
              type="range"
              min={11}
              max={24}
              step={1}
              value={draft.terminalFontSize}
              onChange={event => setDraft({ ...draft, terminalFontSize: Number(event.target.value) })}
            />
          </label>
          <label className="toggle-row">
            <span><strong>保持屏幕常亮</strong><small>运行终端时生效</small></span>
            <input
              type="checkbox"
              role="switch"
              checked={draft.keepScreenAwake}
              onChange={event => setDraft({ ...draft, keepScreenAwake: event.target.checked })}
            />
          </label>
        </section>

        <section className="settings-section" aria-labelledby="shizuku-settings">
          <div className="section-title section-title-action">
            <span className="section-icon"><Smartphone size={19} /></span>
            <div><h2 id="shizuku-settings">Shizuku</h2><p>设备 Shell · {shizukuLabel}</p></div>
            <span className={`status-chip ${shizuku.permission === 'granted' ? 'success' : ''}`}>{shizukuLabel}</span>
          </div>
          <div className="settings-inline-actions">
            {shizuku.installed && shizuku.running && shizuku.permission !== 'granted' && (
              <button className="button button-secondary" type="button" onClick={onAuthorize} disabled={busy !== null}>
                <ShieldCheck size={18} />请求授权
              </button>
            )}
            {(!shizuku.installed || !shizuku.running) && (
              <button className="button button-secondary" type="button" onClick={onOpenShizuku} disabled={busy !== null}>
                <ExternalLink size={18} />打开 Shizuku
              </button>
            )}
            {shizuku.permission === 'granted' && !shizuku.connected && (
              <button className="button button-secondary" type="button" onClick={onConnect} disabled={busy !== null}>
                {busy === 'shizuku-connect' ? <Loader2 className="spin" size={18} /> : <RefreshCw size={18} />}连接 Shizuku
              </button>
            )}
            {shizuku.permission === 'granted' && shizuku.connected && (
              <div className="permission-granted"><CheckCircle2 size={18} />连接可用</div>
            )}
          </div>
        </section>

        <button className="button button-primary save-button" type="submit" disabled={busy !== null}>
          {busy === 'save-settings' ? <Loader2 className="spin" size={18} /> : <Save size={18} />}
          保存设置
        </button>
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
        <button className="dialog-close" type="button" aria-label="关闭" onClick={onCancel} disabled={busy}><X size={19} /></button>
        <span className="dialog-danger-icon"><Trash2 size={23} /></span>
        <h2 id="reset-title">重置 Ubuntu 环境</h2>
        <p>运行时覆盖层将被清除，当前终端和 Harness 会话会立即结束。</p>
        <label className="field confirmation-field">
          <span>输入 RESET_RUNTIME 确认</span>
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
          <button className="button button-secondary" type="button" onClick={onCancel} disabled={busy}>取消</button>
          <button className="button button-danger" type="submit" disabled={busy || !confirmed}>
            {busy ? <Loader2 className="spin" size={18} /> : <RotateCcw size={18} />}
            {busy ? '正在重置' : '确认重置'}
          </button>
        </div>
      </form>
    </div>
  )
}

export function App() {
  const [activeView, setActiveView] = useState<AppView>('conversation')
  const [runtime, setRuntime] = useState<RuntimeState>(EMPTY_RUNTIME)
  const [settings, setSettings] = useState<RuntimeSettings | null>(null)
  const [shizuku, setShizuku] = useState<ShizukuState>(EMPTY_SHIZUKU)
  const [booting, setBooting] = useState(true)
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [resetOpen, setResetOpen] = useState(false)
  const noticeId = useRef(0)
  const busyRef = useRef<string | null>(null)
  const autoLaunchAttempted = useRef(false)
  const shizukuConnecting = useRef(false)

  const notify = useCallback((message: string, tone: NoticeTone = 'info') => {
    noticeId.current += 1
    setNotice({ id: noticeId.current, message, tone })
  }, [])

  const terminalError = useCallback((message: string) => notify(message, 'error'), [notify])

  useEffect(() => {
    if (notice === null) return
    const timer = window.setTimeout(() => setNotice(current => current?.id === notice.id ? null : current), 3600)
    return () => window.clearTimeout(timer)
  }, [notice])

  useEffect(() => {
    let cancelled = false
    let removeProgress: (() => Promise<void>) | undefined
    let progressRevision = 0
    let latestProgress: RuntimeProgress | undefined

    const progressHandlePromise = runtimeBridge.addRuntimeProgressListener(progress => {
      if (cancelled) return
      progressRevision += 1
      latestProgress = progress
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

    void runtimeBridge.getSettings()
      .then(next => { if (!cancelled) setSettings(next) })
      .catch(error => { if (!cancelled) notify(errorMessage(error), 'error') })

    void runtimeBridge.getShizukuState()
      .then(next => { if (!cancelled) setShizuku(next) })
      .catch(() => {
        // Shizuku is optional and must never block the Harness conversation.
      })

    return () => {
      cancelled = true
      if (removeProgress !== undefined) void removeProgress()
      void progressHandlePromise
    }
  }, [notify])

  useEffect(() => {
    let cancelled = false

    const refreshShizuku = (reportError = true): void => {
      if (document.visibilityState === 'hidden') return
      void runtimeBridge.getShizukuState()
        .then(next => {
          if (!cancelled) setShizuku(next)
          if (next.running && next.permission === 'granted' && !next.connected && !shizukuConnecting.current) {
            shizukuConnecting.current = true
            void runtimeBridge.connectShizuku()
              .then(connected => { if (!cancelled) setShizuku(connected) })
              .catch(() => {})
              .finally(() => { shizukuConnecting.current = false })
          }
        })
        .catch(error => { if (!cancelled && reportError) notify(errorMessage(error), 'error') })
    }
    const handleVisibilityChange = (): void => {
      if (document.visibilityState === 'visible') refreshShizuku()
    }
    const handleFocus = (): void => refreshShizuku()

    window.addEventListener('focus', handleFocus)
    document.addEventListener('visibilitychange', handleVisibilityChange)
    const timer = window.setInterval(() => refreshShizuku(false), 2500)
    return () => {
      cancelled = true
      window.clearInterval(timer)
      window.removeEventListener('focus', handleFocus)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [notify])

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
    }, 'Ubuntu 运行时已安装')
  }, [run, settings])

  const launchHarness = useCallback(() => {
    if (busyRef.current !== null) return
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
  }, [notify, runtime])

  useEffect(() => {
    if (booting || activeView !== 'conversation' || busy !== null || autoLaunchAttempted.current) return
    if (settings !== null && settings.autoLaunch === false) return
    if (runtime.phase === 'running' || (runtimeInstalled(runtime) && runtime.phase !== 'stopping')) {
      launchHarness()
    }
  }, [activeView, booting, busy, launchHarness, runtime, settings])

  const stopRuntime = useCallback(() => {
    void run('stop', async () => {
      const next = await runtimeBridge.stopRuntime()
      setRuntime(next)
      setActiveView('settings')
    }, '运行时已停止')
  }, [run])

  const confirmReset = useCallback(() => {
    void run('reset', async () => {
      const next = await runtimeBridge.reset('RESET_RUNTIME')
      setRuntime(next)
      setResetOpen(false)
      autoLaunchAttempted.current = false
      setActiveView('conversation')
    }, 'Ubuntu 环境已重置')
  }, [run])

  const saveSettings = useCallback((nextSettings: RuntimeSettings) => {
    void run('save-settings', async () => {
      const saved = await runtimeBridge.saveSettings(nextSettings)
      setSettings(saved)
    }, '设置已保存')
  }, [run])

  const requestShizukuPermission = useCallback(() => {
    void run('shizuku-permission', async () => {
      const next = await runtimeBridge.requestShizukuPermission()
      setShizuku(next)
    }, 'Shizuku 已授权')
  }, [run])

  const connectShizuku = useCallback(() => {
    void run('shizuku-connect', async () => {
      const next = await runtimeBridge.connectShizuku()
      setShizuku(next)
    }, 'Shizuku 已连接')
  }, [run])

  const openShizuku = useCallback(() => {
    void run('open-shizuku', () => runtimeBridge.openShizuku())
  }, [run])

  const screen = useMemo(() => {
    switch (activeView) {
      case 'conversation':
        return <ConversationScreen busy={busy} runtime={runtime} onInstall={installRuntime} onLaunch={launchHarness} onOpenSettings={() => setActiveView('settings')} onOpenTerminal={() => setActiveView('terminal')} />
      case 'terminal':
        return <TerminalScreen bridge={runtimeBridge} fontSize={settings?.terminalFontSize ?? 14} onAuthorize={requestShizukuPermission} onBack={() => setActiveView('settings')} onConnect={connectShizuku} onError={terminalError} onOpenEnvironment={() => setActiveView('environment')} onOpenShizuku={openShizuku} runtime={runtime} shizuku={shizuku} />
      case 'environment':
        return <EnvironmentScreen busy={busy} bundledSource={settings === null || settings.manifestUrl.trim() === ''} runtime={runtime} onBack={() => setActiveView('settings')} onInstall={installRuntime} onReset={() => setResetOpen(true)} onStart={launchHarness} onStop={stopRuntime} />
      case 'settings':
        return <SettingsScreen busy={busy} runtime={runtime} settings={settings} shizuku={shizuku} onAuthorize={requestShizukuPermission} onConnect={connectShizuku} onLaunch={launchHarness} onOpenEnvironment={() => setActiveView('environment')} onOpenShizuku={openShizuku} onOpenTerminal={() => setActiveView('terminal')} onSave={saveSettings} onStop={stopRuntime} />
    }
  }, [activeView, busy, connectShizuku, installRuntime, launchHarness, openShizuku, requestShizukuPermission, runtime, saveSettings, settings, shizuku, stopRuntime, terminalError])

  if (booting) {
    return (
      <div className="boot-screen">
        <Brand />
        <Loader2 className="spin" size={23} />
        <span>正在连接本机环境</span>
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
            <button className="icon-button header-settings" type="button" aria-label="打开应用设置" title="应用设置" onClick={() => setActiveView('settings')}>
              <Settings2 size={19} />
            </button>
          )}
        </div>
      </header>

      <main className="app-main">{screen}</main>

      {resetOpen && <ResetDialog busy={busy === 'reset'} onCancel={() => setResetOpen(false)} onConfirm={confirmReset} />}

      {notice !== null && (
        <div className={`toast toast-${notice.tone}`} role={notice.tone === 'error' ? 'alert' : 'status'}>
          {notice.tone === 'success' ? <CheckCircle2 size={18} /> : notice.tone === 'error' ? <AlertTriangle size={18} /> : <Wifi size={18} />}
          <span>{notice.message}</span>
          <button type="button" aria-label="关闭提示" onClick={() => setNotice(null)}><X size={16} /></button>
        </div>
      )}
    </div>
  )
}
