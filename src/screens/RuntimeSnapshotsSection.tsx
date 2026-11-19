/**
 * 会话备份区块（运行时页内的「会话备份」）。
 *
 * 从 `App.tsx` 整体搬出：它只依赖 props 与桥返回的快照类型，没有一处读外壳状态，
 * 留在 7 千行的外壳里纯属占地方。正文未改动，仅 `formatSnapshotTime` 随它一起搬来
 * （该函数只有这一处使用）。
 */
import { useCallback, useEffect, useState } from 'react'
import { Database, Loader2, RefreshCw, RotateCcw, Save, Trash2 } from 'lucide-react'
import { formatByteCount as formatBytes } from '../downloadTelemetry'
import { t } from '../i18n'
import { errorMessage, type NoticeTone } from '../ui/messages'
import type { RuntimeSessionSnapshotRestoreResult, RuntimeSessionSnapshotState } from '../platform/types'
/** 快照时间显示：原生给的是 ISO 8601；解析不了就原样显示，绝不显示成 Invalid Date。 */
function formatSnapshotTime(createdAt: string): string {
  const millis = Date.parse(createdAt)
  return Number.isNaN(millis) ? createdAt : new Date(millis).toLocaleString()
}

interface RuntimeSnapshotsSectionProps {
  loadSnapshots: () => Promise<RuntimeSessionSnapshotState>
  createSnapshot: () => Promise<RuntimeSessionSnapshotState>
  restoreSnapshot: (id: string) => Promise<RuntimeSessionSnapshotRestoreResult>
  deleteSnapshot: (id: string) => Promise<RuntimeSessionSnapshotState>
  notify: (message: string, tone: NoticeTone) => void
}

/**
 * 会话备份：安装/更新运行时会自动拍一份，这里也能手动拍、恢复、删除。
 *
 * 为什么需要它：会话文件本身不会因为更新被删——它们按保留白名单搬进新根；真正的风险是
 * 新镜像里的 dsh 版本与现在不同，可能读不出旧版本写的会话（会话格式版本与压缩方式都可能变）。
 * 快照存在应用私有目录（不进系统云备份，也绝不进 rootfs），恢复是合并回填：
 * 同名文件不覆盖，跳过的数量会如实报出来。
 */
export function RuntimeSnapshotsSection({ loadSnapshots, createSnapshot, restoreSnapshot, deleteSnapshot, notify }: RuntimeSnapshotsSectionProps) {
  const [state, setState] = useState<RuntimeSessionSnapshotState | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [confirmId, setConfirmId] = useState<string | null>(null)
  const [confirmCreate, setConfirmCreate] = useState(false)
  const [snapshotsExpanded, setSnapshotsExpanded] = useState(false)
  const COLLAPSE_AFTER = 3

  const load = useCallback(() => {
    setLoading(true)
    setFailed(false)
    void (async () => {
      try {
        setState(await loadSnapshots())
      } catch {
        setFailed(true)
      } finally {
        setLoading(false)
      }
    })()
  }, [loadSnapshots])

  useEffect(() => { load() }, [load])

  const create = (): void => {
    if (busy !== null) return
    setBusy('create')
    void (async () => {
      try {
        setState(await createSnapshot())
        notify(t("数据备份已创建"), 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
      }
    })()
  }

  const restore = (id: string): void => {
    if (busy !== null) return
    setBusy(`restore:${id}`)
    void (async () => {
      try {
        const result = await restoreSnapshot(id)
        setState(result.state)
        notify(
          result.skippedFileCount > 0
            ? t("数据恢复完成：恢复 {0} 个文件，跳过 {1} 个已存在的文件。", String(result.restoredFileCount), String(result.skippedFileCount))
            : t("数据恢复完成：恢复 {0} 个文件。", String(result.restoredFileCount)),
          'success',
        )
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
      }
    })()
  }

  const remove = (id: string): void => {
    if (busy !== null) return
    setBusy(`delete:${id}`)
    void (async () => {
      try {
        setState(await deleteSnapshot(id))
        setConfirmId(null)
        notify(t("数据备份已删除"), 'success')
      } catch (error) {
        notify(errorMessage(error), 'error')
      } finally {
        setBusy(null)
      }
    })()
  }

  const snapshots = state?.snapshots ?? []

  return (
    <section className="settings-section" aria-labelledby="runtime-snapshots-title">
      <div className="section-title">
        <span className="section-icon"><Database size={19} /></span>
        <div>
          <h2 id="runtime-snapshots-title">{t("数据备份")}</h2>
          <p>{t("本机私有目录备份；环境变更前自动轻备份，含附件请手动创建")}</p>
        </div>
      </div>
      <p className="settings-note">{t("完整备份经设备密钥加密，系统密钥失效将无法恢复")}</p>

      {loading && <p className="harness-log-state">{t("正在读取数据备份…")}</p>}

      {!loading && failed && (
        <>
          <p className="harness-log-state" role="alert">{t("暂时读不到数据备份列表")}</p>
          <div className="settings-inline-actions">
            <button className="button button-secondary" type="button" onClick={load}>
              <RefreshCw size={18} />{t("重试")}
            </button>
          </div>
        </>
      )}

      {!loading && !failed && state !== null && (
        <>
          {snapshots.length === 0
            ? <p className="harness-log-state">{t("暂无数据备份。你可以手动创建，也可以等应用在安装运行环境前自动备份。")}</p>
            : (
              <>
              <div className="release-list">
                {(snapshotsExpanded ? snapshots : snapshots.slice(0, COLLAPSE_AFTER)).map(snapshot => (
                  <div className="release-row" key={snapshot.id}>
                    <div className="release-copy">
                      <strong>{formatSnapshotTime(snapshot.createdAt)}</strong>
                      <small>
                        {[
                          t("{0} 个文件", String(snapshot.fileCount)),
                          formatBytes(snapshot.bytes),
                          snapshot.dshVersion === undefined ? t("dsh 版本未读到") : `dsh ${snapshot.dshVersion}`,
                          snapshot.runtimeVersion === undefined ? t("运行时版本未读到") : snapshot.runtimeVersion,
                        ].join(' · ')}
                      </small>
                    </div>
                    <div className="release-actions">
                      <button
                        className="button button-secondary"
                        type="button"
                        onClick={() => restore(snapshot.id)}
                        disabled={busy !== null}
                      >
                        {busy === `restore:${snapshot.id}` ? <Loader2 className="spin" size={18} /> : <RotateCcw size={18} />}
                        {t("恢复")}
                      </button>
                      {confirmId === snapshot.id ? (
                        <button
                          className="button button-danger"
                          type="button"
                          onClick={() => remove(snapshot.id)}
                          disabled={busy !== null}
                        >
                          {busy === `delete:${snapshot.id}` ? <Loader2 className="spin" size={18} /> : <Trash2 size={18} />}
                          {t("确认删除")}
                        </button>
                      ) : (
                        <button
                          className="button button-secondary"
                          type="button"
                          onClick={() => setConfirmId(snapshot.id)}
                          disabled={busy !== null}
                        >
                          <Trash2 size={18} />{t("删除")}
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
              {snapshots.length > COLLAPSE_AFTER && (
                <div className="settings-inline-actions">
                  <button
                    className="button button-secondary"
                    type="button"
                    onClick={() => setSnapshotsExpanded(v => !v)}
                  >
                    {snapshotsExpanded ? t("收起") : t("显示全部 {0} 条", String(snapshots.length))}
                  </button>
                </div>
              )}
              </>
            )}

          <p className="settings-note">
            {t("最多保留 {0} 份备份，总大小不超过 {1}；恢复只补缺失文件", String(state.maxSnapshots), formatBytes(state.maxBytes))}
          </p>

          <div className="settings-inline-actions">
            {!confirmCreate && <button className="button button-secondary" type="button" onClick={() => setConfirmCreate(true)} disabled={busy !== null}>
              {busy === 'create' ? <Loader2 className="spin" size={18} /> : <Save size={18} />}
              {t("现在备份一次")}
            </button>}
            {confirmCreate && <div role="alertdialog" aria-labelledby="runtime-snapshot-create-confirm">
              <p className="settings-note" id="runtime-snapshot-create-confirm"><strong>{t("确认创建完整数据备份？")}</strong> {t("备份会包含会话、模型凭据、设置、技能和附件，并加密保存在本机应用私有目录；设备密钥丢失后历史加密备份不可恢复。")}</p>
              <button className="button button-primary" type="button" onClick={() => { setConfirmCreate(false); create() }} disabled={busy !== null}>
                {busy === 'create' ? <Loader2 className="spin" size={18} /> : <Save size={18} />}
                {t("继续创建")}
              </button>
              <button className="button button-secondary" type="button" onClick={() => setConfirmCreate(false)} disabled={busy !== null}>{t("取消创建")}</button>
            </div>}
          </div>
        </>
      )}
    </section>
  )
}
