import { useCallback, useEffect, useRef, useState } from 'react'
import { Download, Loader2, RefreshCw, RotateCcw, Square, Trash2 } from 'lucide-react'
import { t, useLanguage } from '../i18n'
import { formatByteCount } from '../downloadTelemetry'
import type { RuntimeBridge, RuntimeState } from '../platform/types'
import type { HarnessInstallStage, HarnessProgress, HarnessReleaseList, HarnessVersionOperation, HarnessVersionsState } from '../platform/harnessVersions'

interface Props {
  bridge: RuntimeBridge
  runtime: RuntimeState
  refreshRuntime: () => Promise<void>
  /**
   * 把「Harness 版本长操作正在跑」告诉外层。
   *
   * npm 安装/切换/删除都持有原生生命周期锁数分钟；如果外层不知道，用户在别的页面点
   * 「打开对话」时请求会排在安装后面（原生启动入口已另有快速失败，这里是界面层的提前禁用）。
   * 组件因导航被卸载时**不能**在这里复位：底层调用仍在跑，直到 finally 才结束。
   */
  onBusyChange?: (busy: boolean) => void
  /** 外层记录的忙状态：离开版本页再回来时，安装仍在进行也要禁用重复操作。 */
  externalBusy?: boolean
  /** 外层的运行时操作尚未完成时，版本操作也不能继续排队。 */
  runtimeBusy?: boolean
  onStop?: () => void
}

/** 阶段名只用于界面文案；顺序与访客脚本上报顺序一致，进度条按阶段完成度推进。 */
const STAGE_TEXT: Record<HarnessInstallStage, string> = {
  prepare: '准备安装目录',
  resolve: '解析依赖版本',
  lock: '校验依赖锁定',
  fetch: '下载并安装依赖',
  bridge: '复制安卓桥接插件',
  native: '准备原生模块',
  smoke: '冒烟测试',
  record: '校验并记录指纹',
  verify: '复核已安装版本',
  done: '完成',
}

function elapsedText(millis: number): string {
  const total = Math.max(0, Math.floor(millis / 1000))
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

/** 上游 npm 目录与本地已安装版本分开加载；断网不影响离线切换。 */
export function HarnessVersionsSection({ bridge, runtime, refreshRuntime, onBusyChange, externalBusy = false, runtimeBusy = false, onStop }: Props) {
  useLanguage()
  const [local, setLocal] = useState<HarnessVersionsState | null>(null)
  const [remote, setRemote] = useState<HarnessReleaseList | null>(null)
  const [localError, setLocalError] = useState(false)
  const [remoteError, setRemoteError] = useState(false)
  const [loading, setLoading] = useState(false)
  const [localLoading, setLocalLoading] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [deleting, setDeleting] = useState<string | null>(null)
  const [progress, setProgress] = useState<HarnessProgress | null>(null)
  const [startedAt, setStartedAt] = useState(0)
  const [now, setNow] = useState(0)
  const alive = useRef(true)
  const actionLock = useRef(false)
  const previousPhase = useRef(runtime.phase)
  const localReadRevision = useRef(0)
  const remoteReadRevision = useRef(0)
  const loadLocal = useCallback(() => {
    const revision = ++localReadRevision.current
    setLocalLoading(true)
    setLocalError(false)
    void bridge.manageHarnessVersions('list').then(value => { if (alive.current && revision === localReadRevision.current) setLocal(value) })
      .catch(() => { if (alive.current && revision === localReadRevision.current) setLocalError(true) })
      .finally(() => { if (alive.current && revision === localReadRevision.current) setLocalLoading(false) })
  }, [bridge])
  const loadRemote = useCallback(() => {
    const revision = ++remoteReadRevision.current
    setLoading(true)
    setRemoteError(false)
    void bridge.listHarnessReleases().then(value => { if (alive.current && revision === remoteReadRevision.current) setRemote(value) })
      .catch(() => { if (alive.current && revision === remoteReadRevision.current) setRemoteError(true) })
      .finally(() => { if (alive.current && revision === remoteReadRevision.current) setLoading(false) })
  }, [bridge])
  useEffect(() => {
    alive.current = true
    loadLocal()
    loadRemote()
    return () => { alive.current = false; localReadRevision.current += 1; remoteReadRevision.current += 1 }
  }, [loadLocal, loadRemote])
  // 本页提供的“停止 Harness”完成后，重新读一次选择记录；这也会把中断切换留下的
  // pending 状态交给原生入口恢复，而不要求用户手动猜该按哪个刷新按钮。
  useEffect(() => {
    if (runtime.phase === 'running') {
      previousPhase.current = 'running'
      return
    }
    if (previousPhase.current === 'running' && !runtimeBusy && !externalBusy && !actionLock.current) {
      previousPhase.current = runtime.phase
      loadLocal()
    }
  }, [externalBusy, loadLocal, runtime.phase, runtimeBusy])
  // 阶段事件由原生在安装过程中推送；插件销毁时事件直接丢弃，这里只保留最后一次。
  useEffect(() => {
    let handle: { remove: () => Promise<void> } | null = null
    let cancelled = false
    void bridge.addHarnessProgressListener(event => { if (alive.current) setProgress(event) })
      .then(value => { if (cancelled) void value.remove().catch(() => undefined); else handle = value })
      .catch(() => undefined)
    return () => { cancelled = true; void handle?.remove().catch(() => undefined) }
  }, [bridge])
  // 已用时间每秒走一格：npm 安装几分钟不吐可用字节数，时间是最诚实的参照。
  useEffect(() => {
    if (busy === null) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [busy])
  const act = async (operation: HarnessVersionOperation, version: string) => {
    if (actionLock.current || externalBusy || runtimeBusy) return
    actionLock.current = true
    // 操作结果比开始前的列表新，迟到的读取不能把默认版本和安装状态改回去。
    localReadRevision.current += 1
    setLocalLoading(false)
    setBusy(`${operation}:${version}`)
    onBusyChange?.(true)
    setMessage(null)
    setProgress(null)
    setStartedAt(Date.now())
    setNow(Date.now())
    try {
      const result = await bridge.manageHarnessVersions(operation, version)
      if (alive.current) {
        setLocal(result)
        setLocalError(false)
        setDeleting(null)
        setMessage(operation === 'select' ? t('已启动所选 Harness，并设为默认版本。') : operation === 'install' ? t('Harness 已安装，可以离线切换。') : t('已删除此 Harness 包。'))
      }
    } catch (error) {
      if (alive.current) {
        setMessage(error instanceof Error ? error.message : t('Harness 版本操作失败，原版本仍保留。'))
        loadLocal()
      }
    } finally {
      actionLock.current = false
      // 原生操作已经结束；切换时短暂保留外层闸门，等运行状态回读，刷新失败也会限时放开。
      const refreshed = refreshRuntime().catch(() => undefined)
      // 切换成功会自动拉起 Harness；等待一次有界状态回读，避免旧的 ready 快照在这几百毫秒里
      // 让用户再次点安装/删除，随后才收到原生的 RUNTIME_BUSY。
      if (operation === 'select') {
        await Promise.race([
          refreshed,
          new Promise<void>(resolve => window.setTimeout(resolve, 3000)),
        ])
      } else {
        void refreshed
      }
      onBusyChange?.(false)
      if (alive.current) setBusy(null)
    }
  }
  const stageLabel = progress ? t(STAGE_TEXT[progress.stage]) : t('正在处理 Harness 版本…')
  const installing = busy?.startsWith('install:') === true
  const percent = progress?.percent ?? 0
  const elapsed = elapsedText(now - startedAt)
  // 本页自己发起的操作看 `busy`；离开本页再回来时只剩外层的 `externalBusy`，
  // 两者都必须禁用按钮，否则第二次点击只会排在第一次后面继续等。
  const busyNow = busy !== null || externalBusy || runtimeBusy
  const blocked = externalBusy || runtimeBusy || ['preparing', 'downloading', 'verifying', 'extracting', 'stopping'].includes(runtime.phase)
  const environmentUnavailable = local?.environmentAvailable === false
  const compatibilityDeferred = environmentUnavailable && runtime.phase === 'running'
  const stopped = runtime.phase !== 'running' && !externalBusy
  const releases = expanded ? remote?.entries : remote?.entries.slice(0, 5)
  return (
    <section className="card settings-card" aria-labelledby="harness-versions-title">
      <div className="section-title section-title-plain">
        <div><h2 id="harness-versions-title">{t('Harness 上游版本')}</h2>
          <p>{t('直接安装官方 @deepseek-ai/dsh npm 包；Ubuntu 环境、会话与插件独立保留。')}</p></div>
      </div>
      <p className="settings-note">{t('切换会重启 Harness 并检查插件；启动失败自动恢复旧默认版本。上游原版的 Android 兼容性以实际启动检查为准。')}</p>
      {message && <p className="settings-note" role="status">{message}</p>}
      {externalBusy && busy === null && <p className="settings-note" role="status">
        <Loader2 size={16} className="spin" /> {t('Harness 版本操作仍在进行，请等待完成后再启动或修复运行环境。')}
      </p>}
      {runtimeBusy && busy === null && <p className="settings-note" role="status">
        <Loader2 size={16} className="spin" /> {t('其它运行环境操作仍在进行，请等待完成后再管理 Harness。')}
      </p>}
      {localLoading && <p className="harness-log-state">{t('正在读取本地 Harness 版本…')}</p>}
      {busy && !installing && <p className="settings-note" role="status"><Loader2 size={16} className="spin" /> {busy.startsWith('select:') ? t('正在切换 Harness…') : t('正在删除 Harness…')} {t('已用 {0}', elapsed)}</p>}
      {installing && <div className="download-progress" role="status">
        <p className="progress-copy"><span><Loader2 size={16} className="spin" /> {stageLabel}</span><strong>{progress ? `${percent}%` : t('准备中…')}</strong></p>
        <div className="progress-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}
          aria-valuetext={t('安装阶段：{0}', `${stageLabel} ${percent}%`)}><span style={{ width: `${percent}%` }} /></div>
        <div className="progress-detail"><span>{t('首次安装完整依赖需要数分钟，可在其它页面查看设置，安装会继续。')}</span><span>{t('已用 {0}', elapsed)}</span></div>
      </div>}
      {localError && <div role="alert"><p>{t('无法读取本地 Harness 版本。')}</p><button type="button" className="button button-secondary" disabled={busyNow} onClick={loadLocal}>{t('重试')}</button></div>}
      {local && <>
        <div className="section-title section-title-plain">
          <div><h3>{t('本地 Harness 版本')}</h3><p>{t('已安装版本可离线切换；安装失败会保留原默认版本。')}</p></div>
          <button type="button" className="icon-button" aria-label={t('刷新本地 Harness 版本')} disabled={busyNow || localLoading} onClick={loadLocal}><RefreshCw size={16} className={localLoading ? 'spin' : ''} /></button>
        </div>
        {environmentUnavailable && <p className="settings-note" role={compatibilityDeferred ? 'status' : 'alert'}>{compatibilityDeferred ? t('Harness 正在运行，切换版本时会重新检查兼容性。') : t('无法确认运行环境，刷新后重试；暂不判断已安装 Harness 是否兼容。')}</p>}
        <div className="release-row"><div className="release-copy"><strong>{t('内置 Harness')}{local.bundledVersion ? ` · ${local.bundledVersion}` : ''}</strong>
          {local.active === 'bundled' && <small>{t('默认版本')}</small>}</div>
          {local.active !== 'bundled' && <button type="button" className="button button-secondary" disabled={busyNow || blocked} onClick={() => void act('select', 'bundled')}><RotateCcw size={16} />{t('切回内置')}</button>}
        </div>
        <div className="release-list">{local.installed.map(entry => <div className="release-row" key={entry.version}>
          <div className="release-copy"><strong>{entry.version}</strong><small>{entry.invalid ? t('安装不完整') : `${formatByteCount(entry.bytes)} · ${entry.active ? t('默认版本') : t('已安装')}`}</small>
            {entry.invalid ? <small>{t('此目录缺少完整安装记录，可以删除后重新安装。')}</small> : environmentUnavailable ? <small>{compatibilityDeferred ? t('切换时检查兼容性') : t('无法确认运行环境，刷新后重试。')}</small> : !entry.compatible && <small>{t('当前运行时的 Node.js ABI 已变化，需删除后重装此包。')}</small>}</div>
          <div className="release-actions">
            {!entry.active && !entry.invalid && <button type="button" className="button button-secondary" disabled={busyNow || blocked || (!entry.compatible && !compatibilityDeferred)} onClick={() => void act('select', entry.version)}>{t('使用并设为默认')}</button>}
            {!entry.active && (deleting === entry.version ? <>
              <button type="button" className="button button-danger" disabled={busyNow || !stopped || blocked} onClick={() => void act('delete', entry.version)}>{t('确认删除')}</button>
              <button type="button" className="button button-secondary" disabled={busyNow} onClick={() => setDeleting(null)}>{t('取消')}</button>
            </> : <button type="button" className={entry.invalid || (local.environmentAvailable !== false && !entry.compatible) ? 'button button-secondary' : 'icon-button'} aria-label={t('删除 Harness {0}', entry.version)} disabled={busyNow || !stopped || blocked} onClick={() => setDeleting(entry.version)}><Trash2 size={16} />{(entry.invalid || (local.environmentAvailable !== false && !entry.compatible)) && t('删除后重装')}</button>)}
          </div>
        </div>)}</div>
        {deleting && <p className="settings-note">{t('删除只移除此 Harness 安装包，之后需要重新下载。会话与插件数据保留。')}</p>}
      </>}
      <div className="settings-subsection">
        <div className="section-title section-title-plain"><h3>{t('官方 npm 全版本')}</h3>
          <button type="button" className="icon-button" disabled={loading || busyNow} aria-label={t('刷新 Harness 上游版本')} onClick={loadRemote}><RefreshCw size={16} className={loading ? 'spin' : ''} /></button></div>
        {loading && <p className="harness-log-state">{t('正在读取上游 Harness 版本…')}</p>}
        {remoteError && <p className="settings-note" role="alert">{t('上游版本暂时无法读取，已安装版本仍可离线切换。')}</p>}
        {!loading && remote?.entries.length === 0 && <p className="settings-note">{t('上游尚无符合校验要求的 npm 版本。')}</p>}
        {runtime.phase === 'running' && <div className="settings-note">
          <p>{t('安装或删除包前需先停止 Harness；切换默认版本会自动重启服务。')}</p>
          {onStop && <button type="button" className="button button-secondary" disabled={busyNow} onClick={onStop}><Square size={16} />{t('停止 Harness')}</button>}
        </div>}
        <div className="release-list">{releases?.map(entry => {
          const saved = local?.installed.find(item => item.version === entry.version)
          return <div className="release-row" key={entry.version}><div className="release-copy"><strong>{entry.version}</strong><small>{entry.tags.join(' · ') || t('历史版本')}</small></div>
            {saved ? <span className="status-chip">{saved.invalid || (local?.environmentAvailable !== false && !saved.compatible) ? t('已保存，需删除后重装') : environmentUnavailable ? t('已保存，兼容性待确认') : t('已保存')}</span> : <button type="button" className="button button-secondary" disabled={busyNow || blocked || environmentUnavailable || !stopped || !runtime.installedVersion || !local} onClick={() => void act('install', entry.version)}><Download size={16} />{t('安装 Harness')}</button>}
          </div>
        })}</div>
        {(remote?.entries.length ?? 0) > 5 && <button type="button" className="button button-secondary" onClick={() => setExpanded(value => !value)}>{expanded ? t('收起') : t('显示全部 {0} 条', String(remote?.entries.length))}</button>}
      </div>
    </section>
  )
}
