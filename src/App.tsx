import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
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
import type { LucideIcon } from 'lucide-react'
import { TerminalPanel } from './components/TerminalPanel'
import { runtimeBridge } from './platform/native'
import type {
  RuntimePhase,
  RuntimeSettings,
  RuntimeState,
  ShizukuState,
  TerminalKind,
} from './platform/types'

type AppTab = 'agent' | 'terminal' | 'environment' | 'settings'
type NoticeTone = 'success' | 'error' | 'info'

interface Notice {
  id: number
  message: string
  tone: NoticeTone
}

interface TabDefinition {
  id: AppTab
  label: string
  icon: LucideIcon
}

const TABS: TabDefinition[] = [
  { id: 'agent', label: 'Agent', icon: Bot },
  { id: 'terminal', label: '终端', icon: SquareTerminal },
  { id: 'environment', label: '环境', icon: HardDrive },
  { id: 'settings', label: '设置', icon: Settings2 },
]

const PHASE_META: Record<RuntimePhase, { label: string; tone: string }> = {
  'not-installed': { label: '未安装', tone: 'neutral' },
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
}
const MAX_NOTICE_CHARACTERS = 240

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

interface AgentScreenProps {
  busy: string | null
  runtime: RuntimeState
  onInstall: () => void
  onOpen: () => void
  onStart: () => void
  onStop: () => void
  onTabChange: (tab: AppTab) => void
}

function AgentScreen({ busy, runtime, onInstall, onOpen, onStart, onStop, onTabChange }: AgentScreenProps) {
  const installed = runtime.phase === 'ready' || runtime.phase === 'running' || runtime.phase === 'stopping'
  const running = runtime.phase === 'running'
  const transitional = runtime.phase === 'downloading' || runtime.phase === 'verifying' || runtime.phase === 'extracting'

  return (
    <div className="screen agent-screen">
      <div className="screen-heading">
        <div>
          <p className="eyebrow">工作区</p>
          <h1>Agent</h1>
        </div>
        <PhaseBadge phase={runtime.phase} />
      </div>

      <section className={`agent-console ${running ? 'is-running' : ''}`}>
        <div className="agent-console-copy">
          <span className="agent-icon" aria-hidden="true"><Bot size={30} /></span>
          <div>
            <h2>{running ? 'Harness 已连接' : installed ? 'Harness 可以启动' : '准备 Ubuntu 环境'}</h2>
            <p>
              {running
                ? '本机运行时与应用内页面均已就绪。'
                : installed
                  ? '启动服务后在应用内打开 Agent。'
                  : transitional
                    ? '运行时正在准备，完成后即可启动。'
                    : '首次使用需要安装经过校验的运行时。'}
            </p>
          </div>
        </div>

        <div className="agent-actions">
          {running ? (
            <>
              <button className="button button-inverted" type="button" onClick={onOpen} disabled={busy !== null}>
                {busy === 'open' ? <Loader2 className="spin" size={18} /> : <ExternalLink size={18} />}
                打开 Harness
              </button>
              <button className="button button-ghost-dark" type="button" onClick={onStop} disabled={busy !== null}>
                <Square size={17} />
                停止
              </button>
            </>
          ) : installed ? (
            <button className="button button-inverted" type="button" onClick={onStart} disabled={busy !== null || runtime.phase === 'stopping'}>
              {busy === 'start' ? <Loader2 className="spin" size={18} /> : <Play size={18} fill="currentColor" />}
              启动 Harness
            </button>
          ) : (
            <button className="button button-inverted" type="button" onClick={transitional ? () => onTabChange('environment') : onInstall} disabled={busy !== null}>
              {busy === 'install' || transitional ? <Loader2 className="spin" size={18} /> : <Download size={18} />}
              {transitional ? '查看进度' : '安装运行时'}
            </button>
          )}
        </div>
      </section>

      <div className="quick-grid" aria-label="运行状态">
        <button className="quick-item" type="button" onClick={() => onTabChange('environment')}>
          <span className="quick-icon blue"><Cpu size={19} /></span>
          <span><small>架构</small><strong>{runtime.architecture}</strong></span>
          <ChevronRight size={17} />
        </button>
        <button className="quick-item" type="button" onClick={() => onTabChange('terminal')}>
          <span className="quick-icon green"><SquareTerminal size={19} /></span>
          <span><small>Ubuntu</small><strong>{installed ? '终端可用' : '等待安装'}</strong></span>
          <ChevronRight size={17} />
        </button>
        <button className="quick-item" type="button" onClick={() => onTabChange('settings')}>
          <span className="quick-icon amber"><ShieldCheck size={19} /></span>
          <span><small>连接</small><strong>应用内打开</strong></span>
          <ChevronRight size={17} />
        </button>
      </div>

      {!runtime.runnerAvailable && (
        <div className="inline-alert warning" role="alert">
          <AlertTriangle size={19} />
          <div><strong>本机运行器不可用</strong><span>请安装包含当前 ABI 运行器的应用版本。</span></div>
        </div>
      )}

      {runtime.phase === 'error' && (
        <div className="inline-alert danger" role="alert">
          <AlertTriangle size={19} />
          <div><strong>运行时发生错误</strong><span>{runtime.errorCode ?? '请前往环境页面重试或重置。'}</span></div>
        </div>
      )}
    </div>
  )
}

interface EnvironmentScreenProps {
  busy: string | null
  runtime: RuntimeState
  onInstall: () => void
  onReset: () => void
  onStart: () => void
  onStop: () => void
}

function EnvironmentScreen({ busy, runtime, onInstall, onReset, onStart, onStop }: EnvironmentScreenProps) {
  const inProgress = ['downloading', 'verifying', 'extracting'].includes(runtime.phase)
  const installed = runtime.installedVersion !== undefined || runtime.phase === 'ready' || runtime.phase === 'running'
  const progress = runtime.totalBytes > 0
    ? Math.min(100, Math.round((runtime.downloadedBytes / runtime.totalBytes) * 100))
    : 0

  const steps: Array<{ phase: RuntimePhase; label: string }> = [
    { phase: 'downloading', label: '下载' },
    { phase: 'verifying', label: '校验' },
    { phase: 'extracting', label: '安装' },
    { phase: 'ready', label: '就绪' },
  ]
  const currentStep = runtime.phase === 'running' ? 3 : steps.findIndex(step => step.phase === runtime.phase)

  return (
    <div className="screen environment-screen">
      <div className="screen-heading">
        <div>
          <p className="eyebrow">本机运行时</p>
          <h1>Ubuntu 环境</h1>
        </div>
        <PhaseBadge phase={runtime.phase} />
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
              <strong>{runtime.phase === 'downloading' ? `${progress}%` : '处理中'}</strong>
            </div>
            <div className="progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}>
              <span style={{ width: `${runtime.phase === 'downloading' ? progress : 100}%` }} className={runtime.phase === 'downloading' ? '' : 'indeterminate'} />
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
              <div className={`install-step ${complete ? 'complete' : ''} ${active ? 'active' : ''}`} key={step.phase}>
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
              下载并安装
            </button>
          )}
          {runtime.phase === 'ready' && (
            <button className="button button-primary" type="button" onClick={onStart} disabled={busy !== null}>
              {busy === 'start' ? <Loader2 className="spin" size={18} /> : <Play size={18} fill="currentColor" />}
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

      <section className="detail-section" aria-labelledby="environment-details">
        <h2 id="environment-details">环境详情</h2>
        <div className="detail-list">
          <div className="detail-row"><span><Cpu size={18} />架构</span><strong>{runtime.architecture}</strong></div>
          <div className="detail-row"><span><Database size={18} />镜像大小</span><strong>{formatBytes(runtime.totalBytes)}</strong></div>
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
  onError: (message: string) => void
  onOpenShizuku: () => void
  onTabChange: (tab: AppTab) => void
  runtime: RuntimeState
  shizuku: ShizukuState
}

function TerminalScreen({ bridge, fontSize, onAuthorize, onError, onOpenShizuku, onTabChange, runtime, shizuku }: TerminalScreenProps) {
  const [kind, setKind] = useState<TerminalKind>('ubuntu')
  const [epoch, setEpoch] = useState(0)
  const ubuntuReady = runtime.phase === 'ready' || runtime.phase === 'running'
  const deviceReady = shizuku.installed && shizuku.running && shizuku.permission === 'granted'
  const ready = kind === 'ubuntu' ? ubuntuReady : deviceReady

  return (
    <div className="screen terminal-screen">
      <div className="screen-heading terminal-heading">
        <div>
          <p className="eyebrow">交互会话</p>
          <h1>终端</h1>
        </div>
        <button className="icon-button" type="button" title="重新连接" aria-label="重新连接终端" onClick={() => setEpoch(value => value + 1)} disabled={!ready}>
          <RefreshCw size={19} />
        </button>
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
          <button className="button button-primary" type="button" onClick={() => onTabChange('environment')}>
            前往环境
          </button>
        </div>
      ) : (
        <div className="empty-terminal">
          <span><KeyRound size={27} /></span>
          <h2>{!shizuku.installed ? '未安装 Shizuku' : !shizuku.running ? 'Shizuku 未运行' : '需要 Shizuku 授权'}</h2>
          {shizuku.installed ? (
            <button className="button button-primary" type="button" onClick={shizuku.running ? onAuthorize : onOpenShizuku}>
              <ShieldCheck size={18} />{shizuku.running ? '请求授权' : '打开 Shizuku'}
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
  settings: RuntimeSettings | null
  shizuku: ShizukuState
  onAuthorize: () => void
  onOpenShizuku: () => void
  onSave: (settings: RuntimeSettings) => void
}

function SettingsScreen({ busy, settings, shizuku, onAuthorize, onOpenShizuku, onSave }: SettingsScreenProps) {
  const [draft, setDraft] = useState<RuntimeSettings | null>(settings)

  useEffect(() => setDraft(settings), [settings])

  if (draft === null) {
    return <div className="screen loading-screen"><Loader2 className="spin" size={24} /><span>正在读取设置</span></div>
  }

  const shizukuLabel = !shizuku.installed
    ? '未安装'
    : !shizuku.running
      ? '未运行'
      : shizuku.permission === 'granted'
        ? '已授权'
        : shizuku.permission === 'denied'
          ? '已拒绝'
          : '待授权'

  return (
    <div className="screen settings-screen">
      <div className="screen-heading">
        <div>
          <p className="eyebrow">应用配置</p>
          <h1>设置</h1>
        </div>
      </div>

      <form className="settings-form" onSubmit={event => { event.preventDefault(); onSave(draft) }}>
        <section className="settings-section" aria-labelledby="download-settings">
          <div className="section-title">
            <span className="section-icon"><CloudDownload size={19} /></span>
            <div><h2 id="download-settings">运行时来源</h2><p>仅接受经过固定摘要校验的 HTTPS 清单</p></div>
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
              required
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
              required
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
            {shizuku.permission === 'granted' && (
              <div className="permission-granted"><CheckCircle2 size={18} />权限可用</div>
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

  useEffect(() => inputRef.current?.focus(), [])

  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget && !busy) onCancel() }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="reset-title">
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
            spellCheck={false}
            maxLength={13}
            value={confirmation}
            onChange={event => setConfirmation(event.target.value)}
          />
        </label>
        <div className="dialog-actions">
          <button className="button button-secondary" type="button" onClick={onCancel} disabled={busy}>取消</button>
          <button className="button button-danger" type="button" onClick={onConfirm} disabled={busy || confirmation !== 'RESET_RUNTIME'}>
            {busy ? <Loader2 className="spin" size={18} /> : <RotateCcw size={18} />}
            确认重置
          </button>
        </div>
      </div>
    </div>
  )
}

export function App() {
  const [activeTab, setActiveTab] = useState<AppTab>('environment')
  const [runtime, setRuntime] = useState<RuntimeState>(EMPTY_RUNTIME)
  const [settings, setSettings] = useState<RuntimeSettings | null>(null)
  const [shizuku, setShizuku] = useState<ShizukuState>(EMPTY_SHIZUKU)
  const [booting, setBooting] = useState(true)
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [resetOpen, setResetOpen] = useState(false)
  const noticeId = useRef(0)

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

    void (async () => {
      try {
        const [nextRuntime, nextSettings, nextShizuku, progressHandle] = await Promise.all([
          runtimeBridge.getState(),
          runtimeBridge.getSettings(),
          runtimeBridge.getShizukuState(),
          runtimeBridge.addRuntimeProgressListener(progress => {
            if (cancelled) return
            setRuntime(current => ({
              ...current,
              phase: progress.phase,
              downloadedBytes: progress.downloadedBytes,
              totalBytes: progress.totalBytes,
              errorCode: progress.phase === 'error' ? current.errorCode : undefined,
            }))
          }),
        ])
        if (cancelled) {
          await progressHandle.remove()
          return
        }
        removeProgress = progressHandle.remove
        setRuntime(nextRuntime)
        setSettings(nextSettings)
        setShizuku(nextShizuku)
        setActiveTab(['ready', 'running'].includes(nextRuntime.phase) ? 'agent' : 'environment')
      } catch (error) {
        if (!cancelled) notify(errorMessage(error), 'error')
      } finally {
        if (!cancelled) setBooting(false)
      }
    })()

    return () => {
      cancelled = true
      if (removeProgress !== undefined) void removeProgress()
    }
  }, [notify])

  useEffect(() => {
    let cancelled = false

    const refreshShizuku = (): void => {
      if (document.visibilityState === 'hidden') return
      void runtimeBridge.getShizukuState()
        .then(next => { if (!cancelled) setShizuku(next) })
        .catch(error => { if (!cancelled) notify(errorMessage(error), 'error') })
    }
    const handleVisibilityChange = (): void => {
      if (document.visibilityState === 'visible') refreshShizuku()
    }

    window.addEventListener('focus', refreshShizuku)
    document.addEventListener('visibilitychange', handleVisibilityChange)
    return () => {
      cancelled = true
      window.removeEventListener('focus', refreshShizuku)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [notify])

  const run = useCallback(async (id: string, operation: () => Promise<void>, success?: string) => {
    if (busy !== null) return
    setBusy(id)
    try {
      await operation()
      if (success !== undefined) notify(success, 'success')
    } catch (error) {
      notify(errorMessage(error), 'error')
    } finally {
      setBusy(null)
    }
  }, [busy, notify])

  const installRuntime = useCallback(() => {
    void run('install', async () => {
      await runtimeBridge.install(settings === null ? undefined : {
        manifestUrl: settings.manifestUrl,
        manifestSha256: settings.manifestSha256,
      })
      const next = await runtimeBridge.getState()
      setRuntime(next)
    }, 'Ubuntu 运行时已安装')
  }, [run, settings])

  const startHarness = useCallback(() => {
    void run('start', async () => {
      const next = await runtimeBridge.startHarness()
      setRuntime(next)
    }, 'Harness 已启动')
  }, [run])

  const openHarness = useCallback(() => {
    void run('open', () => runtimeBridge.openHarness())
  }, [run])

  const stopRuntime = useCallback(() => {
    void run('stop', async () => {
      const next = await runtimeBridge.stopRuntime()
      setRuntime(next)
    }, '运行时已停止')
  }, [run])

  const confirmReset = useCallback(() => {
    void run('reset', async () => {
      const next = await runtimeBridge.reset('RESET_RUNTIME')
      setRuntime(next)
      setResetOpen(false)
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

  const openShizuku = useCallback(() => {
    void run('open-shizuku', () => runtimeBridge.openShizuku())
  }, [run])

  const screen = useMemo(() => {
    switch (activeTab) {
      case 'agent':
        return <AgentScreen busy={busy} runtime={runtime} onInstall={installRuntime} onOpen={openHarness} onStart={startHarness} onStop={stopRuntime} onTabChange={setActiveTab} />
      case 'terminal':
        return <TerminalScreen bridge={runtimeBridge} fontSize={settings?.terminalFontSize ?? 14} onAuthorize={requestShizukuPermission} onError={terminalError} onOpenShizuku={openShizuku} onTabChange={setActiveTab} runtime={runtime} shizuku={shizuku} />
      case 'environment':
        return <EnvironmentScreen busy={busy} runtime={runtime} onInstall={installRuntime} onReset={() => setResetOpen(true)} onStart={startHarness} onStop={stopRuntime} />
      case 'settings':
        return <SettingsScreen busy={busy} settings={settings} shizuku={shizuku} onAuthorize={requestShizukuPermission} onOpenShizuku={openShizuku} onSave={saveSettings} />
    }
  }, [activeTab, busy, installRuntime, openHarness, openShizuku, requestShizukuPermission, runtime, saveSettings, settings, shizuku, startHarness, stopRuntime, terminalError])

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
    <div className="app-shell">
      <aside className="desktop-sidebar">
        <Brand />
        <nav aria-label="主导航">
          {TABS.map(tab => {
            const Icon = tab.icon
            return (
              <button key={tab.id} type="button" className={activeTab === tab.id ? 'active' : ''} onClick={() => setActiveTab(tab.id)} aria-current={activeTab === tab.id ? 'page' : undefined}>
                <Icon size={20} />
                <span>{tab.label}</span>
              </button>
            )
          })}
        </nav>
        <div className="sidebar-runtime">
          <PhaseBadge phase={runtime.phase} />
          <small>{runtime.installedVersion ?? runtime.architecture}</small>
        </div>
      </aside>

      <header className="mobile-header">
        <Brand />
        <PhaseBadge phase={runtime.phase} />
      </header>

      <main className="app-main">{screen}</main>

      <nav className="bottom-nav" aria-label="主导航">
        {TABS.map(tab => {
          const Icon = tab.icon
          return (
            <button key={tab.id} type="button" className={activeTab === tab.id ? 'active' : ''} onClick={() => setActiveTab(tab.id)} aria-current={activeTab === tab.id ? 'page' : undefined}>
              <Icon size={21} />
              <span>{tab.label}</span>
            </button>
          )
        })}
      </nav>

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
