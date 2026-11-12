import { useCallback, useEffect, useState } from 'react'
import { ArchiveRestore, Loader2, RefreshCw, Trash2 } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { PreserveCategory, PreservePending, PreserveState, RuntimeBridge } from '../platform/types'
import './DataPreservationCard.css'

/**
 * 体积的人类可读格式：与 `RuntimeResidueCard` / `App.tsx` 同口径（1024 进制，小于 10 保留一位）。
 */
function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / (1024 ** index)
  return `${value >= 10 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`
}

/** 分类的展示顺序与名字（与原生侧 `RuntimePreservePolicy.categoryOf` 同一套 wire 值）。 */
const CATEGORY_ORDER: PreserveCategory[] = ['conversations', 'plugins', 'files', 'settings']

function categoryLabel(category: PreserveCategory): string {
  switch (category) {
    case 'conversations': return t('会话')
    case 'plugins': return t('插件')
    case 'files': return t('文件')
    case 'settings': return t('设置与凭据')
  }
}

/** 一份待处理副本里各分类的条目数，按固定顺序输出非零项。 */
function categorySummary(pending: PreservePending): string {
  const counts = new Map<PreserveCategory, number>()
  for (const item of pending.items) counts.set(item.category, (counts.get(item.category) ?? 0) + 1)
  return CATEGORY_ORDER
    .filter(category => (counts.get(category) ?? 0) > 0)
    .map(category => `${categoryLabel(category)} ${counts.get(category)}`)
    .join(' · ')
}

/**
 * 「数据保留」卡片：上次运行时更新留在暂存目录里的东西，以及用户能做的两件事。
 *
 * 为什么需要它：更新时搬不动的项（与新镜像同名）会留在 `preserve-*` 里，没有入口就等于「文件没了」。
 * 这张卡片把它们按分类列出来，并给出两条出路：
 *  - **取回**：复制到工作区 `recovered/<id>/`（同名不覆盖，暂存原件保留）；
 *  - **删除**：用户显式丢弃这份副本，二次确认，删除后无法恢复。
 *
 * 边界：只读状态与两条动作都走原生桥；本卡片不读、不列文件正文，也不写任何浏览器存储。
 * 旧版本留下的副本没有回填清单——那时不显示「上次回填」那一行，而不是编一个 0。
 */
export function DataPreservationCard({ bridge = runtimeBridge }: { bridge?: RuntimeBridge }) {
  const [state, setState] = useState<PreserveState | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [confirming, setConfirming] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const [actionFailed, setActionFailed] = useState(false)

  const refresh = useCallback(async () => {
    setLoaded(false)
    setFailed(false)
    try {
      const next = await bridge.getPreserveState()
      // 桥返回校验过的载荷；这里仍然防一手：`pending` 不是数组就按「这次没读到」处理，
      // 绝不用空列表冒充「没有待处理数据」（那等于编造一次确认）。
      if (next === null || typeof next !== 'object' || !Array.isArray(next.pending)) {
        setFailed(true)
        return
      }
      setState(next)
      setLoaded(true)
    } catch {
      setFailed(true)
    }
  }, [bridge])

  useEffect(() => { void refresh() }, [refresh])

  const recover = async (id: string): Promise<void> => {
    setBusy(id)
    setActionFailed(false)
    setResult(null)
    try {
      const recovered = await bridge.recoverPreserveItems(id)
      setResult(t('已取回 {0} 个文件（{1}）到工作区 {2}', recovered.files, formatBytes(recovered.bytes), recovered.target))
      await refresh()
    } catch {
      setActionFailed(true)
    } finally {
      setBusy(null)
    }
  }

  const discard = async (id: string): Promise<void> => {
    setBusy(id)
    setConfirming(null)
    setActionFailed(false)
    setResult(null)
    try {
      const discarded = await bridge.discardPreserveItems(id)
      setResult(t(discarded.discarded ? '已删除这份数据副本' : '这份数据副本没有被删除'))
      await refresh()
    } catch {
      setActionFailed(true)
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="data-preservation-card" aria-live="polite">
      <div className="data-preservation-head">
        <span className="data-preservation-title">{t('待处理的数据副本')}</span>
        <button
          className="icon-button"
          type="button"
          title={t('刷新')}
          aria-label={t('刷新待处理数据')}
          onClick={() => void refresh()}
          disabled={!loaded && !failed}
        >
          <RefreshCw size={16} />
        </button>
      </div>

      {!loaded && !failed ? (
        <p className="settings-note"><Loader2 className="spin" size={15} /> {t('正在读取待处理数据…')}</p>
      ) : failed ? (
        <p className="settings-note">{t('读不到待处理数据；这不代表没有数据，重试一次或到文件管理里查看。')}</p>
      ) : state !== null && state.pending.length === 0 ? (
        <p className="settings-note">{t('没有待处理的数据副本。运行时更新留下的东西都已经合并回新运行时。')}</p>
      ) : (
        <ul className="data-preservation-list">
          {state?.pending.map(pending => (
            <li key={pending.id} className="data-preservation-row">
              <div className="data-preservation-copy">
                <strong>{categorySummary(pending)}</strong>
                <small>
                  {t('{0} · {1}', formatBytes(pending.bytes), new Date(pending.updatedAt).toLocaleString())}
                </small>
                {pending.report !== null && (
                  <small>
                    {t('上次更新：回填 {0} 项、合并 {1} 项；仍有 {2} 项待处理', pending.report.restored, pending.report.merged, pending.report.conflicts.length)}
                  </small>
                )}
              </div>
              <div className="data-preservation-actions">
                <button
                  className="button button-secondary compact-button"
                  type="button"
                  onClick={() => void recover(pending.id)}
                  disabled={busy !== null}
                >
                  {busy === pending.id ? <Loader2 className="spin" size={15} /> : <ArchiveRestore size={15} />}
                  {t('取回')}
                </button>
                {confirming === pending.id ? (
                  <>
                    <button
                      className="button button-danger compact-button"
                      type="button"
                      onClick={() => void discard(pending.id)}
                      disabled={busy !== null}
                    >
                      {t('确认删除')}
                    </button>
                    <button className="button button-secondary compact-button" type="button" onClick={() => setConfirming(null)}>
                      {t('取消')}
                    </button>
                  </>
                ) : (
                  <button
                    className="button button-secondary compact-button"
                    type="button"
                    title={t('删除后无法恢复')}
                    onClick={() => setConfirming(pending.id)}
                    disabled={busy !== null}
                  >
                    <Trash2 size={15} />
                    {t('删除')}
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {result !== null && <p className="settings-note">{result}</p>}
      {actionFailed && <p className="settings-note">{t('操作没有完成：可能是运行时正忙或路径不可写，稍后重试。')}</p>}
      {confirming !== null && <p className="settings-note">{t('删除只影响这份待处理副本，不会动当前运行时；删除后无法恢复。')}</p>}
    </div>
  )
}
