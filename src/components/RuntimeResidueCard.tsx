import { useCallback, useEffect, useState } from 'react'
import { Database } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { RuntimeBridge, RuntimeResidueCleanupState, RuntimeResidueState } from '../platform/types'

/**
 * 体积的人类可读格式。
 *
 * 与 `App.tsx` 里的 `formatBytes` 同口径（B/KB/MB/GB/TB、1024 进制、小于 10 才保留一位小数），
 * 但那个是模块私有的，import 不到这里。这份口径就写在卡片里：真要统一，得把两个函数提到共享
 * 模块，那超出这张卡片的范围，也超出「只加一张卡片」这次改动的边界。
 */
function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / (1024 ** index)
  return `${value >= 10 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`
}

/**
 * 「运行时占用」卡片：显示运行时目录里还有多少份可回收的残留（删除失败后被改名挪到一边的整份
 * 运行时目录，`stale-*`），并提供一个需要二次确认的「清理残留」按钮。
 *
 * 三条边界：
 *  - 只删残留：说明文案里写明不影响会话、设置与当前运行时——这正是清理器自己的语义
 *    （它只认 `RuntimeResidueNames.isResidueName` 的那些目录）。
 *  - 清理后的「还剩几份」**一律重新查一次**：`cleaned` / `failed` 是这一次尝试的结果，
 *    不是当前状态，拿它们加减会和磁盘上的真实情况脱节。
 *  - 失败如实显示，不吞也不美化；桥的两条方法都不抛，所以这里的 catch 只兜真正意外的情况。
 *    卡片本身**不写入任何浏览器存储**（localStorage / sessionStorage / IndexedDB）。
 */
export function RuntimeResidueCard({ bridge = runtimeBridge }: { bridge?: RuntimeBridge }) {
  const [state, setState] = useState<RuntimeResidueState | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [failed, setFailed] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [cleaning, setCleaning] = useState(false)
  const [cleanFailed, setCleanFailed] = useState(false)
  const [result, setResult] = useState<RuntimeResidueCleanupState | null>(null)
  const refresh = useCallback(async () => {
    setLoaded(false); setFailed(false)
    try { const next = await bridge.getRuntimeResidue(); setState(next); setLoaded(true) }
    catch { setFailed(true) }
  }, [bridge])
  useEffect(() => { void refresh() }, [refresh])
  async function clean() {
    setCleaning(true); setConfirming(false); setCleanFailed(false); setResult(null)
    try {
      const cleaned = await bridge.cleanRuntimeResidue()
      setResult(cleaned)
      // 剩余量必须重新问一次原生侧：cleaned/failed 只描述「这一次尝试」。
      setState(await bridge.getRuntimeResidue())
      setLoaded(true)
    } catch {
      setCleanFailed(true)
      // 清理失败也要刷新：盘上的残留可能已经被删掉一部分，界面显示的必须是现在的事实。
      try { setState(await bridge.getRuntimeResidue()); setLoaded(true) } catch { setFailed(true) }
    } finally { setCleaning(false) }
  }
  const receivable = loaded && !failed && state !== null && state.count > 0
  return <div className="settings-subsection" aria-labelledby="runtime-residue">
    <div className="section-title"><span className="section-icon"><Database size={19} /></span>
      <div><h3 id="runtime-residue">{t('运行时占用')}</h3><p>{t('运行时目录里由本应用生成的残留可以在这里回收。')}</p></div>
    </div>
    {!loaded && !failed && <p className="settings-note">{t('正在统计运行时占用…')}</p>}
    {loaded && state !== null && state.count === 0 && <p className="settings-note">{t('没有可回收的残留')}</p>}
    {receivable && <p className="settings-note"><strong>{t('可回收的残留：{0} 份，共 {1}', String(state.count), formatBytes(state.bytes))}</strong></p>}
    {receivable && state.truncated && <p className="settings-note">{t('体积统计因条目上限提前停止，上面的数字只是下界。')}</p>}
    {!confirming && <button type="button" disabled={!receivable || cleaning} onClick={() => setConfirming(true)}>{t('清理残留')}</button>}
    {confirming && <div role="alertdialog" aria-labelledby="runtime-residue-confirm">
      <p className="settings-note" id="runtime-residue-confirm"><strong>{t('确认清理运行时残留？')}</strong> {t('只删除运行时目录下由本应用生成的 stale-* 残留，不影响会话、设置与当前运行时。')}</p>
      <button type="button" disabled={cleaning} onClick={() => { void clean() }}>{cleaning ? t('正在清理…') : t('确认清理')}</button>
      <button type="button" disabled={cleaning} onClick={() => setConfirming(false)}>{t('取消')}</button>
    </div>}
    {result !== null && <p className="settings-note" role="status">
      {result.cleaned > 0
        ? t('本次已回收 {0} 份，共 {1}。', String(result.cleaned), formatBytes(result.reclaimedBytes))
        : t('本次没有回收任何残留。')}
    </p>}
    {/* 原生侧那句说明里带着界面算不出来的事实（走了兜底删除器 / 被占用删不掉），原样附上。 */}
    {result !== null && <p className="settings-note">{t('原生侧说明：')}{result.message}</p>}
    {result !== null && receivable && <p className="settings-note"><strong>{t('仍有 {0} 份未清理，详见诊断与日志。', String(state.count))}</strong></p>}
    {failed && <p role="alert">{t('无法读取运行时占用')} <button type="button" onClick={() => { void refresh() }}>{t('重试')}</button></p>}
    {cleanFailed && <p role="alert">{t('清理没能完成，请稍后重试；仍然失败请查看诊断与日志。')}</p>}
  </div>
}
