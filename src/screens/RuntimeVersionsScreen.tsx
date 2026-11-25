/**
 * 版本管理页：运行时槽位切换（当前/上一版本/内置）、Harness 版本、数据备份、应用更新与数据恢复/残留清理的汇总入口。
 *
 * 已移除版本库（本地/在线目录）与缓存清理卡；页内所有操作通过可注入的 `bridge` 访问原生桥。
 */
import { useCallback, useEffect, useState } from 'react'
import {
  ArrowLeft,
  HardDrive,
  Loader2,
  RefreshCw,
  RotateCcw,
  Trash2,
} from 'lucide-react'
import { HarnessVersionsSection } from '../components/HarnessVersionsSection'
import { DataPreservationCard } from '../components/DataPreservationCard'
import { RuntimeResidueCard } from '../components/RuntimeResidueCard'
import { formatByteCount as formatBytes } from '../downloadTelemetry'
import { t } from '../i18n'
import { errorMessage, type NoticeTone } from '../ui/messages'
import { PhaseBadge } from '../ui/phase'
import type {
  AppUpdateState,
  ListenerHandle,
  RuntimeBridge,
  RuntimeSessionSnapshotRestoreResult,
  RuntimeSessionSnapshotState,
  AppUpdateProgress,
  AppUpdateChannel,
  RuntimeVersionSlot,
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
  /** 切换成功后重新读取运行时状态：已安装版本与阶段都会变。 */
  refreshRuntime: () => Promise<void>
  notify: (message: string, tone: NoticeTone) => void
  /** 其它版本区块的长操作是否正在进行；用于跨区互斥。 */
  externalBusy?: boolean
  /** 把本区块的长操作状态交给页面外层，离开页面后仍能挡住 Harness 操作。 */
  onBusyChange?: (busy: boolean) => void
}

/**
 * 运行时版本管理：当前使用的版本、保留下来的上一版本与随安装包内置版本。
 */
export function RuntimeVersionsPanel({
  runtime,
  loadVersions,
  switchVersion,
  deleteVersion,
  refreshRuntime,
  notify,
  externalBusy = false,
  onBusyChange,
}: RuntimeVersionsPanelProps) {
  const [versions, setVersions] = useState<RuntimeVersionsState | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)

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

  const switchToPrevious = (): void => {
    if (busy !== null || externalBusy) return
    setBusy('switch:previous')
    onBusyChange?.(true)
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
        onBusyChange?.(false)
      }
    })()
  }

  const removePrevious = (): void => {
    if (busy !== null || externalBusy) return
    setBusy('delete:previous')
    onBusyChange?.(true)
    void (async () => {
      try {
        setVersions(await deleteVersion('previous'))
        setConfirmDelete(false)
        notify(t("已删除上一版本"), 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
        onBusyChange?.(false)
      }
    })()
  }

  const switchToBundled = (): void => {
    if (busy !== null || externalBusy) return
    setBusy('switch:bundled')
    onBusyChange?.(true)
    void (async () => {
      try {
        setVersions(await switchVersion('bundled'))
        await refreshRuntime()
        notify(t("已切换到随包内置版本"), 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
        onBusyChange?.(false)
      }
    })()
  }

  const phaseBlocked = RUNTIME_VERSION_SWITCH_BLOCKED_PHASES.includes(runtime.phase)
  const busyNow = busy !== null || externalBusy
  const canSwitch = versions?.canSwitch === true && !phaseBlocked && !busyNow
  const canDelete = versions?.canDelete === true && !busyNow
  const list = versions?.versions ?? []

  const currentSlot = list.find(v => v.active)?.slot
  const isCurrentBundled = currentSlot === 'bundled'
  const hasBundled = list.some(v => v.slot === 'bundled')

  return (
    <section className="settings-section" aria-labelledby="runtime-versions-title">
      <div className="section-title">
        <span className="section-icon"><HardDrive size={19} /></span>
        <div>
          <h2 id="runtime-versions-title">{t("运行时版本管理")}</h2>
          <p>{t("管理当前、上一版与内置运行时（安装新版请前往「Ubuntu 运行时」）")}</p>
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
            {t("切换或删除仅操作上一版副本，删除后不可恢复")}
          </p>
          {phaseBlocked && versions?.canSwitch === true && (
            <p className="settings-note">{t("运行中不能切换版本：请先停止运行环境与 Ubuntu 终端。")}</p>
          )}

          <div className="settings-inline-actions">
            <button
              className="button button-secondary"
              type="button"
              onClick={switchToPrevious}
              disabled={!canSwitch}
            >
              {busy === 'switch:previous' ? <Loader2 className="spin" size={18} /> : <RotateCcw size={18} />}
              {t("切换到上一版本")}
            </button>
            {hasBundled && !isCurrentBundled && (
              <button
                className="button button-secondary"
                type="button"
                onClick={switchToBundled}
                disabled={phaseBlocked || busyNow}
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
                disabled={!canDelete}
              >
                <Trash2 size={18} />{t("删除上一版本")}
              </button>
            )}
            {confirmDelete && (
              <>
                <button className="button button-danger" type="button" onClick={removePrevious} disabled={busyNow}>
                  {busy === 'delete:previous' ? <Loader2 className="spin" size={18} /> : <Trash2 size={18} />}{t("确认删除")}
                </button>
                <button
                  className="button button-secondary"
                  type="button"
                  onClick={() => setConfirmDelete(false)}
                  disabled={busyNow}
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
            <p className="settings-note">{t("暂无上一版本，安装新版后自动保留")}</p>
          )}
        </>
      )}



    </section>
  )
}

interface RuntimeVersionsScreenProps extends RuntimeVersionsPanelProps {
  /** 运行时桥实例；默认使用原生桥，单测和预览可注入替代实现。 */
  bridge?: RuntimeBridge
  /** Harness 版本长操作是否还在跑（由 App 持有，跨页面保留）。 */
  harnessVersionBusy: boolean
  /** 安装/切换/删除 Harness 版本的开始与结束。 */
  onHarnessVersionBusyChange: (busy: boolean) => void
  /** 读取 Harness 版本列表也是长操作：增量计数交给 App，读列表期间禁用启动。 */
  onHarnessListBusyChange?: (delta: 1 | -1) => void
  runtimeBusy: boolean
  onStopHarness: () => void
  /** 返回设置首页：版本管理是一级页，返回键回设置首页，而不是回「后台」。 */
  onBack: () => void
  loadSnapshots: () => Promise<RuntimeSessionSnapshotState>
  createSnapshot: () => Promise<RuntimeSessionSnapshotState>
  restoreSnapshot: (id: string) => Promise<RuntimeSessionSnapshotRestoreResult>
  deleteSnapshot: (id: string) => Promise<RuntimeSessionSnapshotState>
  loadUpdate: (forceRefresh?: boolean) => Promise<AppUpdateState>
  /** 纯本地应用更新状态（不触网），用于首帧展示与离线保留频道开关。 */
  loadLocalUpdate?: () => Promise<AppUpdateState>
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
  bridge = runtimeBridge,
  runtime,
  harnessVersionBusy,
  onHarnessVersionBusyChange,
  onHarnessListBusyChange,
  runtimeBusy,
  onStopHarness,
  externalBusy,
  onBusyChange,
  loadVersions,
  switchVersion,
  deleteVersion,
  refreshRuntime,
  notify,
  onBack,
  loadSnapshots,
  createSnapshot,
  restoreSnapshot,
  deleteSnapshot,
  loadUpdate,
  loadLocalUpdate,
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

      <fieldset className="settings-model-fieldset" disabled={harnessVersionBusy || runtimeBusy}>
      <RuntimeVersionsPanel
        runtime={runtime}
        loadVersions={loadVersions}
        switchVersion={switchVersion}
        deleteVersion={deleteVersion}
        refreshRuntime={refreshRuntime}
        notify={notify}
        externalBusy={externalBusy}
        onBusyChange={onBusyChange}
      />
      </fieldset>

      <HarnessVersionsSection bridge={bridge} runtime={runtime} refreshRuntime={refreshRuntime}
        externalBusy={harnessVersionBusy} onBusyChange={onHarnessVersionBusyChange}
        onListBusyChange={onHarnessListBusyChange}
        runtimeBusy={runtimeBusy} onStop={onStopHarness} />

      {/* 顺序按用户关心的操作组织：运行时版本 → Harness 版本 → 数据备份 → 应用自身。 */}
      <fieldset className="settings-model-fieldset" disabled={harnessVersionBusy || runtimeBusy}>
      <RuntimeSnapshotsSection
        loadSnapshots={loadSnapshots}
        createSnapshot={createSnapshot}
        restoreSnapshot={restoreSnapshot}
        deleteSnapshot={deleteSnapshot}
        notify={notify}
      />
      </fieldset>

      <AppUpdateSection
        loadUpdate={loadUpdate}
        loadLocalUpdate={loadLocalUpdate}
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

      <fieldset className="settings-model-fieldset" disabled={harnessVersionBusy || runtimeBusy}>
        <DataPreservationCard bridge={bridge} />
        <RuntimeResidueCard bridge={bridge} />
      </fieldset>
    </div>
  )
}
