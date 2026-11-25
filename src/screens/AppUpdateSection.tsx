/**
 * 应用自身（APK）的更新区块。
 *
 * 从 `App.tsx` 整体搬出：它只依赖 props 传入的桥操作与更新状态类型，不读外壳状态。
 * 正文零改动，只把原来散在外壳顶部的 import 收进本文件。
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { Download, ExternalLink, Loader2, RefreshCw, Rocket, Smartphone } from 'lucide-react'
import { ReleaseNotes } from '../components/ReleaseNotes'
import { formatByteCount as formatBytes, formatDuration, useDownloadTelemetry } from '../downloadTelemetry'
import { t } from '../i18n'
import { errorMessage, type NoticeTone } from '../ui/messages'
import type {
  AppUpdateChannel,
  AppUpdateProgress,
  AppUpdateState,
  ListenerHandle,
} from '../platform/types'
interface AppUpdateSectionProps {
  /** 读取应用自身（APK）的更新状态；默认联网检查最新版，明确传 false 才读取缓存。 */
  loadUpdate: (forceRefresh?: boolean) => Promise<AppUpdateState>
  /** 读取纯本地更新事实（已装版本、安装权限与已持久化频道），绝不触网。 */
  loadLocalUpdate?: () => Promise<AppUpdateState>
  /** 切换更新频道；原生先落盘再尝试联网刷新，联网失败时回传已保存频道的本地态。 */
  setUpdateChannel: (channel: AppUpdateChannel) => Promise<AppUpdateState>
  downloadUpdate: () => Promise<void>
  installUpdate: () => Promise<void>
  /** 订阅应用更新包的下载进度；原生侧只在真的下载时推送（见 [AppUpdateProgress]）。 */
  subscribeProgress: (listener: (event: AppUpdateProgress) => void) => Promise<ListenerHandle>
  /** 去系统设置里为本应用打开「安装未知应用」；返回后重新读一次状态。 */
  openInstallSettings: () => Promise<void>
  notify: (message: string, tone: NoticeTone) => void
  reminderEnabled: boolean
  onReminderEnabledChange: (enabled: boolean) => void
  reminderStorageAvailable: boolean
}

/**
 * 应用更新（APK 自身）：从 GitHub 发布页取最新正式构建，下载校验后交给系统安装器。
 *
 * 与「运行环境更新」是两层：这里换的是应用 APK（外壳与内置运行时一起变），
 * 上面几块换的是运行时镜像。安装必须由系统安装器完成，应用只能把包递过去，
 * 所以这里会如实说明「会跳到系统安装界面」以及「需要允许安装未知来源」。
 */
export function AppUpdateSection({ loadUpdate, loadLocalUpdate, setUpdateChannel, downloadUpdate, installUpdate, openInstallSettings, notify, subscribeProgress, reminderEnabled, onReminderEnabledChange, reminderStorageAvailable }: AppUpdateSectionProps) {
  const [state, setState] = useState<AppUpdateState | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState<'download' | 'install' | null>(null)
  const [switchingChannel, setSwitchingChannel] = useState(false)
  const [progress, setProgress] = useState<AppUpdateProgress | null>(null)
  const readRevision = useRef(0)

  /**
   * 订阅下载进度：挂载时订阅、卸载时注销，而不是点「下载更新」时才订阅。
   *
   * 原因：原生侧可能从断点续传的第一个字节就开始推送，点击到订阅之间的空档会吞掉
   * 第一个读数，进度条会先停一次 0%。订阅失败不弹提示——没有进度条仍然能下载，
   * 这一屏的其它功能也不该被它拖住。
   */
  useEffect(() => {
    let cancelled = false
    let remove: (() => Promise<void>) | undefined
    const handlePromise = subscribeProgress(next => {
      if (!cancelled) setProgress(next)
    }).then(handle => {
      if (cancelled) return handle.remove()
      remove = handle.remove
    }).catch(() => undefined)
    return () => {
      cancelled = true
      void handlePromise.then(() => remove?.())
    }
  }, [subscribeProgress])

  const load = useCallback((forceRefresh = true) => {
    const revision = ++readRevision.current
    let remoteSucceeded = false
    setLoading(true)
    setFailed(false)
    if (loadLocalUpdate !== undefined) {
      // 首帧与离线先读本地事实（已装版本、安装权限与持久化频道），防止更新后缓存失效
      // 或 GitHub 波动把「接收测试版本」开关回弹关闭并锁死。
      void loadLocalUpdate().then(local => {
        if (revision === readRevision.current && !remoteSucceeded) {
          setState(prev => {
            if (prev === null || prev.channel !== local.channel || prev.installedVersion !== local.installedVersion ||
                prev.installedVersionCode !== local.installedVersionCode || local.checkedAt !== undefined) {
              return local
            }
            const { available, cached, checkedAt } = prev
            return { ...local, available, cached, checkedAt }
          })
        }
      }).catch(() => undefined)
    }
    void (async () => {
      try {
        const next = await loadUpdate(forceRefresh)
        remoteSucceeded = true
        if (revision === readRevision.current) {
          setState(next)
          setFailed(next.refreshFailed === true)
        }
      } catch {
        if (revision === readRevision.current) setFailed(true)
      } finally {
        if (revision === readRevision.current) setLoading(false)
      }
    })()
  }, [loadLocalUpdate, loadUpdate])

  useEffect(() => {
    // 进入页面默认直连发布目录，避免 24 小时缓存让用户看到旧的下一版本。
    load()
    return () => { readRevision.current += 1 }
  }, [load])

  const runStep = (step: 'download' | 'install', action: () => Promise<void>, success: string): void => {
    if (busy !== null || switchingChannel || loading || state?.available === undefined) return
    setBusy(step)
    // 进度只描述「这一次下载」：开始前清掉上一轮读数，结束后无论成败都不再显示进度条。
    if (step === 'download') setProgress(null)
    void (async () => {
      try {
        await action()
        notify(success, 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
        if (step === 'download') setProgress(null)
      }
    })()
  }

  const available = state?.available

  /**
   * 切换更新频道：原生先同步落盘频道，再尝试联网刷新目标频道候选。
   *
   * - 联网成功：直接采用返回的完整状态；
   * - 联网失败（`refreshFailed: true`）：保留新频道与本地事实、清除旧频道候选，并提示稍后重试；
   * - 落盘或桥接异常：先尝试远端回读，再退回本地回读，避免网络故障时把开关锁死。
   */
  const switchChannel = (next: AppUpdateChannel): void => {
    if (state === null || busy !== null || switchingChannel) return
    const revision = ++readRevision.current
    setSwitchingChannel(true)
    setLoading(false)
    setFailed(false)
    void (async () => {
      try {
        const fresh = await setUpdateChannel(next)
        if (revision !== readRevision.current) return
        setState(fresh)
        if (fresh.refreshFailed === true) {
          setFailed(true)
          notify(t("更新频道已保存，但暂时无法检查远端更新，请稍后重试。"), 'error')
        }
      } catch (error) {
        if (revision !== readRevision.current) return
        notify(errorMessage(error), 'error')
        try {
          const fresh = await loadUpdate(true)
          if (revision === readRevision.current) {
            setState(fresh)
            setFailed(fresh.refreshFailed === true)
          }
        } catch {
          if (revision !== readRevision.current) return
          if (loadLocalUpdate !== undefined) {
            try {
              const local = await loadLocalUpdate()
              if (revision === readRevision.current) {
                setState(local)
                setFailed(true)
              }
              return
            } catch {
              // 本地回读也失败时，下方退回空态并禁用开关。
            }
          }
          if (revision === readRevision.current) {
            setState(null)
            setFailed(true)
          }
        }
      } finally {
        if (revision === readRevision.current) setSwitchingChannel(false)
      }
    })()
  }
  const hasRemoteStatus = state !== null && (state.checkedAt !== undefined || (!loading && !failed && state.refreshFailed !== true))
  const showRefreshAlert = state !== null && (failed || state.refreshFailed === true)
  const statusChipTone = available !== undefined ? 'warn' : (hasRemoteStatus ? 'success' : 'warn')
  const statusChipLabel = available !== undefined
    ? t("可更新")
    : (hasRemoteStatus ? t("已是最新") : (loading ? t("正在检查") : t("未检查")))
  const statusDetail = available !== undefined
    ? `${available.version} · ${formatBytes(available.bytes)}`
    : (hasRemoteStatus
      ? (state?.channel === 'beta' ? t("发布页上没有更新的正式版或测试版") : t("发布页上没有更新的正式版本"))
      : (loading ? t("正在连接发布目录…") : t("暂时无法连接发布目录，请检查网络后重试")))
  // 百分比只在拿到原生进度、且总量为正时才有意义；否则显示「处理中」的不确定进度条。
  const downloadPercent = progress === null || progress.totalBytes <= 0
    ? null
    : Math.min(100, Math.round((progress.downloadedBytes / progress.totalBytes) * 100))
  // GitHub 慢网下百分比可能几分钟一跳：速度、已用时间和停滞提示让用户确认「还在下」。
  const downloadTelemetry = useDownloadTelemetry(
    progress?.downloadedBytes ?? 0,
    progress?.totalBytes ?? 0,
    busy === 'download',
  )

  return (
    <section className="settings-section" aria-labelledby="app-update-title">
      <div className="section-title">
        <span className="section-icon"><Smartphone size={19} /></span>
        <div>
          <h2 id="app-update-title">{t("应用更新")}</h2>
          <p>{t("更新应用本体（APK）及内置运行时")}</p>
        </div>
      </div>

      {loading && <p className="harness-log-state">{t("正在读取应用版本…")}</p>}

      <label className="toggle-row">
        <span>
          <strong>{t("接收测试版本")}</strong>
          <small>{t("包含 -mobile 测试构建；关闭则仅检查正式版")}</small>
        </span>
        <input
          type="checkbox"
          role="switch"
          aria-label={t("接收测试版本")}
          checked={state?.channel === 'beta'}
          disabled={state === null || busy !== null || switchingChannel}
          onChange={event => switchChannel(event.target.checked ? 'beta' : 'stable')}
        />
      </label>

      <label className="toggle-row">
        <span>
          <strong>{t("每日更新提醒")}</strong>
          <small>{t("每日首次打开提示一次，关闭后仍可手动检查")}</small>
          {!reminderStorageAvailable && <small role="status">{t("提醒偏好或日期未能持久保存，重启后可能再次提醒。")}</small>}
        </span>
        <input type="checkbox" role="switch" aria-label={t("每日更新提醒")} checked={reminderEnabled} onChange={event => onReminderEnabledChange(event.target.checked)} />
      </label>

      {!loading && failed && state === null && (
        <>
          <p className="harness-log-state" role="alert">{t("暂时读不到应用更新状态：请检查网络后重试。")}</p>
          <div className="settings-inline-actions">
            <button className="button button-secondary" type="button" onClick={() => load(true)}>
              <RefreshCw size={18} />{t("重试")}
            </button>
          </div>
        </>
      )}

      {state !== null && (
        <>
          {showRefreshAlert && (
            <p className="settings-note" role="alert">
              {state.checkedAt !== undefined || state.available !== undefined
                ? t("重新检查失败，仍显示上次缓存的更新信息。")
                : t("暂时无法连接发布目录，已保留当前更新频道，请检查网络后重试。")}
            </p>
          )}
          <div className="settings-status-list">
            <div className="settings-status-row">
              <span className="status-chip">{t("当前应用")}</span>
              <strong>{`${state.installedVersion}（${state.installedVersionCode}）`}</strong>
            </div>
            <div className="settings-status-row">
              <span className={`status-chip ${statusChipTone}`}>
                {statusChipLabel}
              </span>
              <strong>{statusDetail}</strong>
            </div>
          </div>
          {state.checkedAt !== undefined && <p className="settings-note">
            {state.cached ? t("正在显示缓存的发布信息，点击“重新检查”可获取最新信息。") : t("发布信息已从网络检查。")}
            {state.checkedAt !== undefined ? ` ${t("上次检查：{0}", new Date(state.checkedAt).toLocaleString())}` : ''}
          </p>}

          {available !== undefined && available.notes.trim() !== '' && <ReleaseNotes notes={available.notes} />}

          {state.installAllowed
            ? (
              <p className="settings-note">{t("下载完成后交给系统安装器安装，装完应用会重启")}</p>
            )
            : (
              <>
                <p className="settings-note">{t("系统还没有允许本应用安装其它应用。请先开启「安装未知应用」")}</p>
                <div className="settings-inline-actions">
                  <button
                    className="button button-secondary"
                    type="button"
                    onClick={() => {
                      void openInstallSettings().then(() => load()).catch(error => notify(errorMessage(error), 'error'))
                    }}
                    disabled={loading || busy !== null || switchingChannel}
                  >
                    <ExternalLink size={18} />{t("去系统设置允许安装")}
                  </button>
                </div>
              </>
            )}

          <div className="settings-inline-actions">
            <button
              className="button button-secondary"
              type="button"
              onClick={() => runStep('download', downloadUpdate, t("更新包已下载并通过校验"))}
              disabled={available === undefined || loading || busy !== null || switchingChannel}
            >
              {busy === 'download' ? <Loader2 className="spin" size={18} /> : <Download size={18} />}
              {t("下载更新")}
            </button>
            <button
              className="button button-secondary"
              type="button"
              onClick={() => runStep('install', installUpdate, t("已交给系统安装器"))}
              disabled={available === undefined || loading || busy !== null || switchingChannel}
            >
              {busy === 'install' ? <Loader2 className="spin" size={18} /> : <Rocket size={18} />}
              {t("安装更新")}
            </button>
            <button className="button button-secondary" type="button" onClick={() => load(true)} disabled={loading || busy !== null || switchingChannel}>
              <RefreshCw size={18} />{t("重新检查")}
            </button>
          </div>

          {busy === 'download' && (
            <div className="download-progress" aria-live="polite">
              <div className="progress-copy">
                <span>{t("正在下载更新包…")}</span>
                <strong>{downloadPercent === null ? t("处理中") : `${downloadPercent}%`}</strong>
              </div>
              <div
                className="progress-track"
                role="progressbar"
                aria-label={t("更新包下载进度")}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={downloadPercent ?? 0}
              >
                <span style={{ width: `${downloadPercent ?? 100}%` }} className={downloadPercent === null ? 'indeterminate' : ''} />
              </div>
              {progress !== null && (
                <div className="progress-detail">
                  <span>{formatBytes(progress.downloadedBytes)}</span>
                  <span>{formatBytes(progress.totalBytes)}</span>
                </div>
              )}
              <div className="progress-detail progress-telemetry">
                <span>{t("已用 {0}", formatDuration(downloadTelemetry.elapsedMs))}</span>
                {downloadTelemetry.speedBytesPerSec > 0 && (
                  <span>{t("速度 {0}/秒", formatBytes(downloadTelemetry.speedBytesPerSec))}</span>
                )}
                {downloadTelemetry.etaMs !== null && (
                  <span>{t("剩余约 {0}", formatDuration(downloadTelemetry.etaMs))}</span>
                )}
              </div>
              {downloadTelemetry.stalled && (
                <div className="progress-note">{t("网络较慢，正在等待数据…")}</div>
              )}
            </div>
          )}

          <p className="settings-note">{t("仅从 GitHub 发布页下载，先下载校验后再安装")}</p>
        </>
      )}
    </section>
  )
}
