/**
 * 版本管理页：运行时版本库、内置/远程版本切换、应用自身更新与会话备份的汇总入口。
 *
 * 从 `App.tsx` 整体搬出：这一屏把已经抽出的 `AppUpdateSection`、`RuntimeSnapshotsSection`
 * 与 `RuntimeCleanupCard` / `RuntimeResidueCard` 组合起来，自身只吃 props。
 * 正文零改动。
 */
import { useCallback, useEffect, useState } from 'react'
import {
  ArrowLeft,
  Download,
  HardDrive,
  Loader2,
  Pin,
  PinOff,
  RefreshCw,
  RotateCcw,
  Trash2,
} from 'lucide-react'
import { ReleaseNotes } from '../components/ReleaseNotes'
import { RuntimeCleanupCard } from '../components/RuntimeCleanupCard'
import { RuntimeResidueCard } from '../components/RuntimeResidueCard'
import { formatByteCount as formatBytes } from '../downloadTelemetry'
import { t } from '../i18n'
import { errorMessage, type NoticeTone } from '../ui/messages'
import { PhaseBadge } from '../ui/phase'
import type {
  AppUpdateState,
  ListenerHandle,
  RuntimeReleaseEntry,
  RuntimeSessionSnapshotRestoreResult,
  RuntimeSessionSnapshotState,
  AppUpdateProgress,
  AppUpdateChannel,
  RuntimeVersionSlot,
  RuntimeLibraryEntry,
  RuntimeReleaseList,
  RuntimeState,
  RuntimePhase,
  RuntimeVersionsState,
} from '../platform/types'
import { AppUpdateSection } from './AppUpdateSection'
import { RuntimeSnapshotsSection } from './RuntimeSnapshotsSection'
import { runtimeBridge } from '../platform/native'
const RUNTIME_VERSION_SLOT_META: Record<RuntimeVersionSlot, { label: string; chip: string }> = {
  current: { label: '当前使用', chip: 'success' },
  previous: { label: '上一版本', chip: 'warn' },
  bundled: { label: '随安装包内置', chip: '' },
}

/** 这些阶段里运行环境正忙：切换要改名根目录并搬迁访客数据，必须先停下来。 */
const RUNTIME_VERSION_SWITCH_BLOCKED_PHASES: readonly RuntimePhase[] = [
  'preparing', 'downloading', 'verifying', 'extracting', 'running', 'stopping',
]

export interface RuntimeVersionsPanelProps {
  /** 当前运行时状态：只用于判断此刻能不能切换版本。 */
  runtime: RuntimeState
  /** 版本列表与两个操作；由 App 里引用稳定的回调提供。 */
  loadVersions: () => Promise<RuntimeVersionsState>
  switchVersion: (target: string) => Promise<RuntimeVersionsState>
  deleteVersion: (target: string) => Promise<RuntimeVersionsState>
  /**
   * 锁定/解锁版本库里的一个归档（`target` 是版本库条目 ID，不是 `previous`/`bundled` 槽位）。
   *
   * 锁定只影响「版本库满额时谁可以被自动淘汰」：锁定的版本不会被挑走；全部锁定时下载会
   * 明确失败（原生侧抛 `RUNTIME_LIBRARY_FULL`），界面据此提示先删除或解锁一个。
   */
  setPinned: (target: string, pinned: boolean) => Promise<RuntimeVersionsState>
  downloadRelease?: (manifestUrl: string, manifestSha256: string) => Promise<void>
  listReleases?: () => Promise<RuntimeReleaseList>
  /** 切换成功后重新读取运行时状态：已安装版本与阶段都会变。 */
  refreshRuntime: () => Promise<void>
  notify: (message: string, tone: NoticeTone) => void
}

/**
 * 运行时版本管理：当前使用的版本、保留下来的上一版本、随安装包内置版本以及本地多版本库。
 *
 * 移动端多版本采用单活解压（Cold/Hot Tiering）模式：
 * 磁盘上始终只保留一份解压运行态（约 960MB），其余版本以压缩包（.bundle，约 300MB）
 * 保存在本地私有版本库中（最多 5 个），切换时本地原子解压并搬迁访客数据，不耗流量。
 * 随 APK 内置的出厂版本（Slot 0）物理只读永久保留，不占版本库配额。
 */
export function RuntimeVersionsPanel({
  runtime,
  loadVersions,
  switchVersion,
  deleteVersion,
  setPinned,
  downloadRelease,
  listReleases,
  refreshRuntime,
  notify,
}: RuntimeVersionsPanelProps) {
  const [versions, setVersions] = useState<RuntimeVersionsState | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [confirmDeleteLibraryId, setConfirmDeleteLibraryId] = useState<string | null>(null)

  const [catalog, setCatalog] = useState<RuntimeReleaseEntry[]>([])
  const [catalogLoading, setCatalogLoading] = useState(false)
  const [catalogFailed, setCatalogFailed] = useState(false)
  const [downloadingVersion, setDownloadingVersion] = useState<string | null>(null)

  // release 列表折叠：超过 3 条时默认只显示最新的 3 条，底部放「显示全部」按钮。
  // 每个列表独立控制；展开后保持到页面卸载。
  const [libraryExpanded, setLibraryExpanded] = useState(false)
  const [catalogExpanded, setCatalogExpanded] = useState(false)
  const COLLAPSE_AFTER = 3

  const load = useCallback(() => {
    setLoading(true)
    setFailed(false)
    void (async () => {
      try {
        setVersions(await loadVersions())
      } catch {
        setFailed(true)
      } finally {
        setLoading(false)
      }
    })()
  }, [loadVersions])

  useEffect(() => { load() }, [load])

  const loadCatalog = useCallback(() => {
    setCatalogLoading(true)
    setCatalogFailed(false)
    void (async () => {
      try {
        const fetcher = listReleases ?? (() => runtimeBridge.listRuntimeReleases())
        const res = await fetcher()
        setCatalog(res.entries ?? [])
      } catch {
        setCatalogFailed(true)
      } finally {
        setCatalogLoading(false)
      }
    })()
  }, [listReleases])

  useEffect(() => { loadCatalog() }, [loadCatalog])

  const switchToPrevious = (): void => {
    if (busy !== null) return
    setBusy('switch:previous')
    void (async () => {
      try {
        setVersions(await switchVersion('previous'))
        // 切换后当前版本已经换人：状态里的已安装版本必须重新读，不能沿用旧值。
        await refreshRuntime()
        notify(t("已切换到上一版本"), 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
      }
    })()
  }

  const removePrevious = (): void => {
    if (busy !== null) return
    setBusy('delete:previous')
    void (async () => {
      try {
        setVersions(await deleteVersion('previous'))
        setConfirmDelete(false)
        notify(t("已删除上一版本"), 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
      }
    })()
  }

  const switchToBundled = (): void => {
    if (busy !== null) return
    setBusy('switch:bundled')
    void (async () => {
      try {
        setVersions(await switchVersion('bundled'))
        await refreshRuntime()
        notify(t("已切换到随包内置版本"), 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
      }
    })()
  }

  const switchToLibrary = (id: string): void => {
    if (busy !== null) return
    setBusy(`switch:${id}`)
    void (async () => {
      try {
        setVersions(await switchVersion(id))
        await refreshRuntime()
        notify(t("已切换到指定版本"), 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
      }
    })()
  }

  const removeLibrary = (id: string): void => {
    if (busy !== null) return
    setBusy(`delete:${id}`)
    void (async () => {
      try {
        setVersions(await deleteVersion(id))
        setConfirmDeleteLibraryId(null)
        notify(t("已从版本库删除"), 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
      }
    })()
  }

  const togglePinned = (entry: RuntimeLibraryEntry): void => {
    if (busy !== null) return
    const next = !entry.pinned
    setBusy(`pin:${entry.id}`)
    void (async () => {
      try {
        setVersions(await setPinned(entry.id, next))
        notify(next ? t("已锁定该版本") : t("已解锁该版本"), 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
      }
    })()
  }

  const downloadToLibrary = (item: RuntimeReleaseEntry): void => {
    if (!item.manifestUrl || !item.manifestSha256 || busy !== null) return
    setDownloadingVersion(item.version)
    void (async () => {
      try {
        const downloader = downloadRelease ?? ((url, sha) => runtimeBridge.downloadRuntimeRelease(url, sha))
        await downloader(item.manifestUrl!, item.manifestSha256!)
        notify(t("已成功下载到版本库"), 'success')
        load()
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setDownloadingVersion(null)
      }
    })()
  }

  const phaseBlocked = RUNTIME_VERSION_SWITCH_BLOCKED_PHASES.includes(runtime.phase)
  const canSwitch = versions?.canSwitch === true && !phaseBlocked
  const canDelete = versions?.canDelete === true
  const list = versions?.versions ?? []
  const library = versions?.library ?? []
  const libraryCapacity = versions?.libraryCapacity ?? 5

  const currentSlot = list.find(v => v.active)?.slot
  const isCurrentBundled = currentSlot === 'bundled'
  const hasBundled = list.some(v => v.slot === 'bundled')

  return (
    <section className="settings-section" aria-labelledby="runtime-versions-title">
      <div className="section-title">
        <span className="section-icon"><HardDrive size={19} /></span>
        <div>
          <h2 id="runtime-versions-title">{t("运行时版本管理")}</h2>
          <p>{t("当前使用的运行时、保留下来的上一版本，以及随安装包内置的版本。装新版本仍在运行环境页：内置更新，或填写 manifest 地址与 SHA-256。")}</p>
        </div>
      </div>

      {loading && <p className="harness-log-state">{t("正在读取运行时版本…")}</p>}

      {!loading && failed && (
        <>
          <p className="harness-log-state" role="alert">{t("暂时读不到运行时版本列表")}</p>
          <div className="settings-inline-actions">
            <button className="button button-secondary" type="button" onClick={load}>
              <RefreshCw size={18} />{t("重试")}
            </button>
          </div>
        </>
      )}

      {!loading && !failed && list.length === 0 && (
        <p className="harness-log-state">{t("还没有安装运行时：安装后这里会列出当前版本与上一版本。")}</p>
      )}

      {!loading && !failed && list.length > 0 && (
        <div className="settings-status-list">
          {list.map(info => {
            const meta = RUNTIME_VERSION_SLOT_META[info.slot]
            const parts = [info.version]
            parts.push(info.dshVersion === undefined ? t("dsh 版本未读到") : `dsh ${info.dshVersion}`)
            parts.push(formatBytes(info.extractedBytes))
            if (info.active) parts.push(t("使用中"))
            return (
              <div className="settings-status-row" key={info.slot}>
                <span className={`status-chip ${meta.chip}`}>{t(meta.label)}</span>
                <strong>{parts.join(' · ')}</strong>
              </div>
            )
          })}
        </div>
      )}

      {!loading && !failed && (
        <>
          <p className="settings-note">
            {t("上一版本是上一次安装时保留下来的完整副本，切换与删除都只动它。删除后无法恢复，也不再占用磁盘。")}
          </p>
          {phaseBlocked && versions?.canSwitch === true && (
            <p className="settings-note">{t("运行中不能切换版本：请先停止运行环境与 Ubuntu 终端。")}</p>
          )}

          <div className="settings-inline-actions">
            <button
              className="button button-secondary"
              type="button"
              onClick={switchToPrevious}
              disabled={!canSwitch || busy !== null}
            >
              {busy === 'switch:previous' ? <Loader2 className="spin" size={18} /> : <RotateCcw size={18} />}
              {t("切换到上一版本")}
            </button>
            {hasBundled && !isCurrentBundled && (
              <button
                className="button button-secondary"
                type="button"
                onClick={switchToBundled}
                disabled={phaseBlocked || busy !== null}
              >
                {busy === 'switch:bundled' ? <Loader2 className="spin" size={18} /> : <RotateCcw size={18} />}
                {t("切换到随包内置版本")}
              </button>
            )}
            {!confirmDelete && (
              <button
                className="button button-secondary"
                type="button"
                onClick={() => setConfirmDelete(true)}
                disabled={!canDelete || busy !== null}
              >
                <Trash2 size={18} />{t("删除上一版本")}
              </button>
            )}
            {confirmDelete && (
              <>
                <button className="button button-danger" type="button" onClick={removePrevious} disabled={busy !== null}>
                  {busy === 'delete:previous' ? <Loader2 className="spin" size={18} /> : <Trash2 size={18} />}{t("确认删除")}
                </button>
                <button
                  className="button button-secondary"
                  type="button"
                  onClick={() => setConfirmDelete(false)}
                  disabled={busy !== null}
                >
                  {t("取消")}
                </button>
              </>
            )}
          </div>

          {confirmDelete && (
            <p className="settings-note">{t("删除上一版本会永久删掉这份副本，无法撤销；当前使用的版本不受影响。")}</p>
          )}
          {versions !== null && !versions.canSwitch && !versions.canDelete && (
            <p className="settings-note">{t("现在没有可切换的上一版本：安装一次新版本后才会保留。")}</p>
          )}
        </>
      )}

      {/* 本地多版本库 */}
      {!loading && !failed && (
        <div className="settings-subsection" aria-labelledby="runtime-library-group">
          <div className="section-title section-title-plain">
            <div>
              <h3 id="runtime-library-group">{t("本地版本库")}</h3>
              <p>
                {t(
                  "离线保存的 dsh 运行时包（最多保留 {0} 个）。切换由本地原子解压并搬迁访客数据，不耗流量。",
                  String(libraryCapacity),
                )}
              </p>
            </div>
            <span className="status-chip">
              {t("已保存 {0} / {1}", String(library.length), String(libraryCapacity))}
            </span>
          </div>

          {library.length === 0 ? (
            <p className="harness-log-state">{t("本地版本库暂无额外版本包。可从下方「在线可用版本」中下载。")}</p>
          ) : (
            <>
            <div className="release-list">
              {(libraryExpanded ? library : library.slice(0, COLLAPSE_AFTER)).map(entry => {
                const isEntryActive = entry.active
                const isDeleting = busy === `delete:${entry.id}`
                const isSwitching = busy === `switch:${entry.id}`
                return (
                  <div className="release-row" key={entry.id}>
                    <div className="release-copy">
                      <strong>
                        {entry.version}
                        {isEntryActive && <span className="status-chip success" style={{ marginLeft: 6 }}>{t("使用中")}</span>}
                        {entry.pinned && <span className="status-chip" style={{ marginLeft: 6 }}>{t("已锁定")}</span>}
                      </strong>
                      <small>
                        {[
                          entry.dshVersion ? `dsh ${entry.dshVersion}` : t("dsh 版本未读到"),
                          formatBytes(entry.bytes),
                          ...(entry.downloadedAt === undefined
                            ? []
                            : [t("保存于 {0}", new Date(entry.downloadedAt).toLocaleString())]),
                        ].join(' · ')}
                      </small>
                    </div>
                    <div className="release-actions">
                      {/* 锁定只改「满额时谁可以被自动淘汰」，和「现在用哪一份」无关：
                          正在使用的版本也能先锁上，免得切走之后被淘汰。 */}
                      <button
                        className="button button-secondary"
                        type="button"
                        onClick={() => togglePinned(entry)}
                        disabled={busy !== null}
                        title={entry.pinned ? t("解锁后该版本可能在版本库满额时被自动淘汰") : t("锁定的版本在版本库满额时不会被自动淘汰")}
                      >
                        {busy === `pin:${entry.id}`
                          ? <Loader2 className="spin" size={18} />
                          : entry.pinned ? <PinOff size={18} /> : <Pin size={18} />}
                        {entry.pinned ? t("解锁") : t("锁定")}
                      </button>
                      {!isEntryActive && (
                        <>
                          <button
                            className="button button-secondary"
                            type="button"
                            onClick={() => switchToLibrary(entry.id)}
                            disabled={phaseBlocked || busy !== null}
                          >
                            {isSwitching ? <Loader2 className="spin" size={18} /> : <RotateCcw size={18} />}
                            {t("切换至此版本")}
                          </button>
                          {confirmDeleteLibraryId === entry.id ? (
                            <>
                              <button
                                className="button button-danger"
                                type="button"
                                onClick={() => removeLibrary(entry.id)}
                                disabled={busy !== null}
                              >
                                {isDeleting ? <Loader2 className="spin" size={18} /> : <Trash2 size={18} />}
                                {t("确认删除")}
                              </button>
                              <button
                                className="button button-secondary"
                                type="button"
                                onClick={() => setConfirmDeleteLibraryId(null)}
                                disabled={busy !== null}
                              >
                                {t("取消")}
                              </button>
                            </>
                          ) : (
                            <button
                              className="button button-secondary"
                              type="button"
                              onClick={() => setConfirmDeleteLibraryId(entry.id)}
                              disabled={busy !== null}
                            >
                              <Trash2 size={18} />
                              {t("删除")}
                            </button>
                          )}
                        </>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
            {library.length > COLLAPSE_AFTER && (
                <div className="settings-inline-actions">
                  <button
                    className="button button-secondary"
                    type="button"
                    onClick={() => setLibraryExpanded(v => !v)}
                  >
                    {libraryExpanded ? t("收起") : t("显示全部 {0} 条", String(library.length))}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* 在线可下载发布列表 */}
      {!loading && !failed && (
        <div className="settings-subsection" aria-labelledby="runtime-catalog-group">
          <div className="section-title section-title-plain">
            <div>
              <h3 id="runtime-catalog-group">{t("在线可用版本")}</h3>
              <p>{t("从官方源拉取经过适配校验的 dsh 运行时镜像。下载后存入本地版本库，可随时无网切换。")}</p>
            </div>
            <button
              className="icon-button"
              type="button"
              aria-label={t("刷新可用版本")}
              title={t("刷新可用版本")}
              onClick={loadCatalog}
              disabled={catalogLoading}
            >
              <RefreshCw className={catalogLoading ? "spin" : ""} size={16} />
            </button>
          </div>

          {catalogLoading && <p className="harness-log-state">{t("正在检查可用版本…")}</p>}

          {!catalogLoading && catalogFailed && (
            <p className="harness-log-state" role="alert">{t("暂时无法连接版本目录，离线时仅可使用已下载和内置版本。")}</p>
          )}

          {!catalogLoading && !catalogFailed && catalog.length === 0 && (
            <p className="harness-log-state">{t("当前无可用更新版本。")}</p>
          )}

          {!catalogLoading && !catalogFailed && catalog.length > 0 && (
            <>
            <div className="release-list">
              {(catalogExpanded ? catalog : catalog.slice(0, COLLAPSE_AFTER)).map(item => {
                const inLibrary = library.some(lib => lib.version === item.version)
                const isCurrentActive = list.some(l => l.active && l.version === item.version)
                const isDownloading = downloadingVersion === item.version
                const canDownload = Boolean(item.manifestUrl && item.manifestSha256)

                return (
                  <div className="release-row" key={item.version}>
                    <div className="release-copy">
                      <strong>
                        {item.version}
                        {isCurrentActive && <span className="status-chip success" style={{ marginLeft: 6 }}>{t("使用中")}</span>}
                        {!isCurrentActive && inLibrary && <span className="status-chip" style={{ marginLeft: 6 }}>{t("已保存")}</span>}
                      </strong>
                      <small>{item.dshVersion ? `dsh ${item.dshVersion}` : t("dsh 版本未读到")}</small>
                      {item.notes !== undefined && item.notes.trim() !== '' && <ReleaseNotes notes={item.notes} />}
                    </div>
                    <div className="release-actions">
                      {isCurrentActive ? (
                        <span className="settings-note" style={{ margin: 0 }}>{t("正在运行")}</span>
                      ) : inLibrary ? (
                        <span className="settings-note" style={{ margin: 0 }}>{t("已在版本库")}</span>
                      ) : (
                        <button
                          className="button button-secondary"
                          type="button"
                          onClick={() => downloadToLibrary(item)}
                          disabled={!canDownload || isDownloading || busy !== null}
                        >
                          {isDownloading ? <Loader2 className="spin" size={18} /> : <Download size={18} />}
                          {t("下载到版本库")}
                        </button>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
            {catalog.length > COLLAPSE_AFTER && (
                <div className="settings-inline-actions">
                  <button
                    className="button button-secondary"
                    type="button"
                    onClick={() => setCatalogExpanded(v => !v)}
                  >
                    {catalogExpanded ? t("收起") : t("显示全部 {0} 条", String(catalog.length))}
                  </button>
                </div>
              )}
            </>
          )}
          {library.length >= libraryCapacity && (
            <p className="settings-note">
              {library.every(entry => entry.pinned)
                ? t("版本库已满（{0} 个），且全部已锁定：下载新版本会被明确拒绝，需要先删除或解锁一个版本。", String(libraryCapacity))
                : t("版本库已满（{0} 个）：下载新版本会给未锁定的最旧版本腾位置；锁定的版本不会被自动淘汰。", String(libraryCapacity))}
            </p>
          )}
        </div>
      )}

      {/* 数据清理与运行时占用合成一张卡：上面是缓存与插件数据，下面才是运行时残留。 */}
      <div className="settings-subsection" aria-labelledby="runtime-cleanup-group">
        <div className="section-title section-title-plain">
          <div>
            <h3 id="runtime-cleanup-group">{t('清理')}</h3>
            <p>{t('缓存、插件数据与运行时残留都在这里回收；运行环境本身在「重置运行环境」里处理。')}</p>
          </div>
        </div>

        <RuntimeCleanupCard />

        <RuntimeResidueCard />
      </div>
    </section>
  )
}

interface RuntimeVersionsScreenProps extends RuntimeVersionsPanelProps {
  /** 返回设置首页：版本管理是一级页，返回键回设置首页，而不是回「后台」。 */
  onBack: () => void
  loadSnapshots: () => Promise<RuntimeSessionSnapshotState>
  createSnapshot: () => Promise<RuntimeSessionSnapshotState>
  restoreSnapshot: (id: string) => Promise<RuntimeSessionSnapshotRestoreResult>
  deleteSnapshot: (id: string) => Promise<RuntimeSessionSnapshotState>
  loadUpdate: (forceRefresh?: boolean) => Promise<AppUpdateState>
  /** 应用更新频道切换，只透传给 [AppUpdateSection]。 */
  setUpdateChannel: (channel: AppUpdateChannel) => Promise<AppUpdateState>
  downloadUpdate: () => Promise<void>
  installUpdate: () => Promise<void>
  /** 应用更新包的下载进度订阅，只透传给 [AppUpdateSection]。 */
  subscribeUpdateProgress: (listener: (event: AppUpdateProgress) => void) => Promise<ListenerHandle>
  openInstallSettings: () => Promise<void>
  reminderEnabled: boolean
  onReminderEnabledChange: (enabled: boolean) => void
  reminderStorageAvailable: boolean
}

/**
 * 版本管理（一级页）：把运行时版本从「后台」页里拆出来，单独占一屏。
 *
 * 为什么要拆：这一屏管的是「磁盘上有哪几份运行时、现在用哪一份」，和「保活、悬浮球、
 * 前台服务、投递区」这些运行期开关不是一类事。混在一页里时，用户得在很长的运行页里
 * 往下翻才能找到版本；拆开之后设置首页的「版本管理」分组才有对应入口可命中
 * （搜索框里输入「版本」「回退」「切回」能直接到这一屏）。
 *
 * 页内不复制第二份实现：列表与两个操作仍然只有 `RuntimeVersionsPanel` 那一份，
 * 这里只补页头、返回键与状态徽标。
 */
export function RuntimeVersionsScreen({
  runtime,
  loadVersions,
  switchVersion,
  deleteVersion,
  setPinned,
  downloadRelease,
  listReleases,
  refreshRuntime,
  notify,
  onBack,
  loadSnapshots,
  createSnapshot,
  restoreSnapshot,
  deleteSnapshot,
  loadUpdate,
  setUpdateChannel,
  downloadUpdate,
  installUpdate,
  subscribeUpdateProgress,
  openInstallSettings,
  reminderEnabled,
  onReminderEnabledChange,
  reminderStorageAvailable,
}: RuntimeVersionsScreenProps) {
  return (
    <div className="screen versions-screen">
      <div className="screen-heading management-heading">
        <div>
          <p className="eyebrow">{t("应用管理")}</p>
          <h1>{t("版本管理")}</h1>
        </div>
        <div className="heading-actions">
          <PhaseBadge phase={runtime.phase} />
          <button className="icon-button" type="button" aria-label={t("返回设置")} title={t("返回设置")} onClick={onBack}><ArrowLeft size={19} /></button>
        </div>
      </div>

      <RuntimeVersionsPanel
        runtime={runtime}
        loadVersions={loadVersions}
        switchVersion={switchVersion}
        deleteVersion={deleteVersion}
        setPinned={setPinned}
        downloadRelease={downloadRelease}
        listReleases={listReleases}
        refreshRuntime={refreshRuntime}
        notify={notify}
      />

      {/* 顺序即用户关心顺序：磁盘上有哪几份 → 会话备份 → 应用自身。 */}
      <RuntimeSnapshotsSection
        loadSnapshots={loadSnapshots}
        createSnapshot={createSnapshot}
        restoreSnapshot={restoreSnapshot}
        deleteSnapshot={deleteSnapshot}
        notify={notify}
      />

      <AppUpdateSection
        loadUpdate={loadUpdate}
        setUpdateChannel={setUpdateChannel}
        downloadUpdate={downloadUpdate}
        installUpdate={installUpdate}
        subscribeProgress={subscribeUpdateProgress}
        openInstallSettings={openInstallSettings}
        notify={notify}
        reminderEnabled={reminderEnabled}
        onReminderEnabledChange={onReminderEnabledChange}
        reminderStorageAvailable={reminderStorageAvailable}
      />
    </div>
  )
}
