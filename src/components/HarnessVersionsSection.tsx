import { useCallback, useEffect, useRef, useState } from 'react'
import { Download, Loader2, RefreshCw, RotateCcw, Trash2 } from 'lucide-react'
import { t, useLanguage } from '../i18n'
import { formatByteCount } from '../downloadTelemetry'
import type { RuntimeBridge, RuntimeState } from '../platform/types'
import type { HarnessReleaseList, HarnessVersionOperation, HarnessVersionsState } from '../platform/harnessVersions'

interface Props {
  bridge: RuntimeBridge
  runtime: RuntimeState
  refreshRuntime: () => Promise<void>
}

/** 上游 npm 目录与本地已安装版本分开加载；断网不影响离线切换。 */
export function HarnessVersionsSection({ bridge, runtime, refreshRuntime }: Props) {
  useLanguage()
  const [local, setLocal] = useState<HarnessVersionsState | null>(null)
  const [remote, setRemote] = useState<HarnessReleaseList | null>(null)
  const [localError, setLocalError] = useState(false)
  const [remoteError, setRemoteError] = useState(false)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [deleting, setDeleting] = useState<string | null>(null)
  const alive = useRef(true)
  const actionLock = useRef(false)
  const loadLocal = useCallback(() => {
    setLocalError(false)
    void bridge.manageHarnessVersions('list').then(value => { if (alive.current) setLocal(value) })
      .catch(() => { if (alive.current) setLocalError(true) })
  }, [bridge])
  const loadRemote = useCallback(() => {
    setLoading(true)
    setRemoteError(false)
    void bridge.listHarnessReleases().then(value => { if (alive.current) setRemote(value) })
      .catch(() => { if (alive.current) setRemoteError(true) })
      .finally(() => { if (alive.current) setLoading(false) })
  }, [bridge])
  useEffect(() => {
    alive.current = true
    loadLocal()
    loadRemote()
    return () => { alive.current = false }
  }, [loadLocal, loadRemote])
  const act = async (operation: HarnessVersionOperation, version: string) => {
    if (actionLock.current) return
    actionLock.current = true
    setBusy(`${operation}:${version}`)
    setMessage(null)
    try {
      const result = await bridge.manageHarnessVersions(operation, version)
      if (alive.current) {
        setLocal(result)
        setDeleting(null)
        setMessage(operation === 'select' ? t('已启动所选 Harness，并设为默认版本。') : operation === 'install' ? t('Harness 已安装，可以离线切换。') : t('已删除此 Harness 包。'))
      }
    } catch (error) {
      if (alive.current) {
        setMessage(error instanceof Error ? error.message : t('Harness 版本操作失败，原版本仍保留。'))
        loadLocal()
      }
    } finally {
      await refreshRuntime().catch(() => undefined)
      actionLock.current = false
      if (alive.current) setBusy(null)
    }
  }
  const blocked = ['preparing', 'downloading', 'verifying', 'extracting', 'stopping'].includes(runtime.phase)
  const stopped = runtime.phase !== 'running'
  const releases = expanded ? remote?.entries : remote?.entries.slice(0, 5)
  return (
    <section className="card settings-card" aria-labelledby="harness-versions-title">
      <div className="section-title section-title-plain">
        <div><h2 id="harness-versions-title">{t('Harness 上游版本')}</h2>
          <p>{t('直接安装官方 @deepseek-ai/dsh npm 包；Ubuntu 环境、会话与插件独立保留。')}</p></div>
      </div>
      <p className="settings-note">{t('切换会重启 Harness 并检查插件；启动失败自动恢复旧默认版本。上游原版的 Android 兼容性以实际启动检查为准。')}</p>
      {message && <p className="settings-note" role="status">{message}</p>}
      {busy && <p className="harness-log-state" role="status"><Loader2 size={16} className="spin" /> {t('正在处理 Harness 版本，首次安装完整依赖可能需要数分钟…')}</p>}
      {localError && <div role="alert"><p>{t('无法读取本地 Harness 版本。')}</p><button type="button" className="button button-secondary" onClick={loadLocal}>{t('重试')}</button></div>}
      {local && <>
        <div className="release-row"><div className="release-copy"><strong>{t('内置 Harness')}{local.bundledVersion ? ` · ${local.bundledVersion}` : ''}</strong>
          {local.active === 'bundled' && <small>{t('默认版本')}</small>}</div>
          {local.active !== 'bundled' && <button type="button" className="button button-secondary" disabled={busy !== null || blocked} onClick={() => void act('select', 'bundled')}><RotateCcw size={16} />{t('切回内置')}</button>}
        </div>
        <div className="release-list">{local.installed.map(entry => <div className="release-row" key={entry.version}>
          <div className="release-copy"><strong>{entry.version}</strong><small>{formatByteCount(entry.bytes)} · {entry.active ? t('默认版本') : t('已安装')}</small>
            {!entry.compatible && <small>{t('Ubuntu 环境已变化，需删除后重装此包。')}</small>}</div>
          <div className="release-actions">
            {!entry.active && <button type="button" className="button button-secondary" disabled={busy !== null || blocked || !entry.compatible} onClick={() => void act('select', entry.version)}>{t('使用并设为默认')}</button>}
            {!entry.active && (deleting === entry.version ? <>
              <button type="button" className="button button-danger" disabled={busy !== null || !stopped || blocked} onClick={() => void act('delete', entry.version)}>{t('确认删除')}</button>
              <button type="button" className="button button-secondary" disabled={busy !== null} onClick={() => setDeleting(null)}>{t('取消')}</button>
            </> : <button type="button" className="icon-button" aria-label={t('删除 Harness {0}', entry.version)} disabled={busy !== null || !stopped || blocked} onClick={() => setDeleting(entry.version)}><Trash2 size={16} /></button>)}
          </div>
        </div>)}</div>
        {deleting && <p className="settings-note">{t('删除只移除此 Harness 安装包，之后需要重新下载。会话与插件数据保留。')}</p>}
      </>}
      <div className="settings-subsection">
        <div className="section-title section-title-plain"><h3>{t('官方 npm 全版本')}</h3>
          <button type="button" className="icon-button" disabled={loading || busy !== null} aria-label={t('刷新 Harness 上游版本')} onClick={loadRemote}><RefreshCw size={16} className={loading ? 'spin' : ''} /></button></div>
        {loading && <p className="harness-log-state">{t('正在读取上游 Harness 版本…')}</p>}
        {remoteError && <p className="settings-note" role="alert">{t('上游版本暂时无法读取，已安装版本仍可离线切换。')}</p>}
        {!loading && remote?.entries.length === 0 && <p className="settings-note">{t('上游尚无符合校验要求的 npm 版本。')}</p>}
        {!stopped && <p className="settings-note">{t('安装或删除包前，请先在首页停止 Harness；切换按钮会自动重启服务。')}</p>}
        <div className="release-list">{releases?.map(entry => {
          const saved = local?.installed.some(item => item.version === entry.version)
          return <div className="release-row" key={entry.version}><div className="release-copy"><strong>{entry.version}</strong><small>{entry.tags.join(' · ') || t('历史版本')}</small></div>
            {saved ? <span className="status-chip">{t('已保存')}</span> : <button type="button" className="button button-secondary" disabled={busy !== null || blocked || !stopped || !runtime.installedVersion || !local} onClick={() => void act('install', entry.version)}><Download size={16} />{t('安装 Harness')}</button>}
          </div>
        })}</div>
        {(remote?.entries.length ?? 0) > 5 && <button type="button" className="button button-secondary" onClick={() => setExpanded(value => !value)}>{expanded ? t('收起') : t('显示全部 {0} 条', String(remote?.entries.length))}</button>}
      </div>
    </section>
  )
}
