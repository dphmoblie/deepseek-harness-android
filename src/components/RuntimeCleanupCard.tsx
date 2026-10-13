import { useCallback, useEffect, useState } from 'react'
import { Trash2 } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type {
  PluginCleanupLedger,
  RuntimeBridge,
  RuntimeCleanupOutcome,
  RuntimeCleanupState,
} from '../platform/types'

/**
 * 体积的人类可读格式。
 *
 * 与 `App.tsx` 的 `formatBytes`、`RuntimeResidueCard` 里那份同口径（B/KB/MB/GB/TB、1024 进制、
 * 小于 10 才保留一位小数）；真要统一得把三处提到一个共享模块，那超出这张卡片的改动边界。
 */
function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / (1024 ** index)
  return `${value >= 10 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`
}

/**
 * 缓存条目的界面名称。
 *
 * 名字来自原生侧白名单（`RuntimeCacheEntries.CLEARABLE`）。没登记过的名字**回原名**而不是
 * 「未知项」：真出现说明两侧不同步，把技术名字显示出来比藏起来更容易查出问题。
 */
const ENTRY_LABELS: Record<string, string> = {
  diagnostics: '诊断导出',
  share: '工作区分享暂存',
  'proot-tmp': 'PRoot 临时目录',
}

/** 账本状态对应的说明：只有 `ok` 能清理，其余三种都要讲清「为什么现在不能清」。 */
const LEDGER_NOTES: Record<PluginCleanupLedger, string> = {
  ok: '',
  missing: '还没有插件版本账本，暂时无法确定哪些目录是插件数据。',
  invalid: '插件版本账本已损坏，暂时无法确定哪些目录是插件数据。',
  unwritable: '插件版本账本写不进去，暂时无法安全地清理插件数据。',
}

/** 原生侧受控失败码 → 给人看的下一步；讲不出下一步的码一律回落到通用文案。 */
const FAILURE_NOTES: Record<string, string> = {
  RUNTIME_BUSY: '请先停止 Harness 和 Ubuntu 终端',
  CLEANUP_LEDGER_UNAVAILABLE: '插件版本账本不可用，未清理任何数据',
  // 逐包清理独有的两个码要分开讲：一个是「串的形状不对」，一个是「形状对但账本里没这个插件」。
  CLEANUP_SCOPE_INVALID: '插件标识不合法：重新打开这一页取一次列表再试',
  CLEANUP_PLUGIN_UNKNOWN: '账本里没有这个插件：刷新列表后再试',
}

function failureNote(error: unknown): string | null {
  const code = (error as { code?: unknown } | null)?.code
  return typeof code === 'string' ? FAILURE_NOTES[code] ?? null : null
}

/**
 * 一次清理的目标。
 *
 * `packageName` 只在插件档有意义，而且**必须是 `RuntimeCleanupPackage.id` 原样读回来的串**
 * （npm 包名形状，如 `left-pad`、`@scope/tool`）：界面不拼接、不规范化、不让用户手输。
 * 省略它 = 清空全部已登记插件的数据；带上它 = 只清这一个。
 */
type CleanupTarget =
  | { tier: 'cache' }
  | { tier: 'plugin'; packageName?: string }

/** 结果回显要点名清的是哪一档/哪个包，但不许夸大：这里只描述**这一次尝试**的范围。 */
function targetLabel(target: CleanupTarget): string {
  if (target.tier === 'cache') return t('缓存')
  return target.packageName === undefined
    ? t('全部已登记插件的数据')
    : t('插件 {0} 的数据', target.packageName)
}

/**
 * 「数据清理」卡片：分两档清理——缓存（诊断导出、工作区分享暂存、PRoot 临时目录）与插件自己的
 * 数据目录，并把「运行环境的数据」明确指到「重置运行环境」那一档，不混在一个按钮里。
 * 插件数据档还能**按包只清一个**：整档按钮清全部已登记插件，每一行的「只清这个插件」只清那一个。
 *
 * 五条边界：
 *  - **运行时在跑就不给点**：`proot-tmp` 是运行中沙箱的 `TMPDIR`、插件进程也在写自己的数据目录，
 *    此时清理会把「清理」变成「打断会话」与「损坏一半的状态」。原生侧另有 `RUNTIME_BUSY` 兜底。
 *  - **账本不可用就不给点**：插件数据档由版本账本驱动，账本缺失/损坏/不可写时我们核不准哪些目录
 *    是插件数据，界面据此禁用并说明原因，而不是让用户点了再看一句「未清理任何数据」。此时
 *    `packages` 是空数组，逐包列表**根本不渲染**——不给任何按包清理的入口。
 *  - **包名只能原样回传**：`packageName` 就是 `packages[].id` 读回来的那个串，界面不加工也不手输；
 *    账本里核对不到的包会被原生侧以 `CLEANUP_PLUGIN_UNKNOWN` 拒掉且零改动。
 *  - **清理后的现状一律重新查一次**：`removed`/`failed` 是这一次尝试的结果，不是当前状态，
 *    拿它们加减会和磁盘上的真实情况脱节。结果回显点名清的是哪一档/哪个包，绝不把「清了一个」
 *    说成「所有插件都清了」。
 *  - 失败如实显示，不吞也不美化。卡片本身**不写入任何浏览器存储**。
 */
export function RuntimeCleanupCard({ bridge = runtimeBridge }: { bridge?: RuntimeBridge }) {
  const [state, setState] = useState<RuntimeCleanupState | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [failed, setFailed] = useState(false)
  const [pending, setPending] = useState<CleanupTarget | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionFailed, setActionFailed] = useState<string | null>(null)
  const [result, setResult] = useState<{ target: CleanupTarget; outcome: RuntimeCleanupOutcome } | null>(null)

  const refresh = useCallback(async () => {
    setLoaded(false); setFailed(false)
    try { setState(await bridge.getRuntimeCleanupState()); setLoaded(true) }
    catch { setFailed(true) }
  }, [bridge])
  useEffect(() => { void refresh() }, [refresh])

  async function clean(target: CleanupTarget) {
    setBusy(true); setPending(null); setActionFailed(null); setResult(null)
    try {
      // 整档清理**不带任何参数**：`purgePluginData()` 是「清全部已登记插件」，
      // `purgePluginData({ packageName })` 才是「只清这一个」——两者不能混着传，
      // 也不能用 `{ packageName: undefined }` 代替「不传」（那是拿实现细节赌语义）。
      const outcome = target.tier === 'cache'
        ? await bridge.clearRuntimeCache()
        : target.packageName === undefined
          ? await bridge.purgePluginData()
          : await bridge.purgePluginData({ packageName: target.packageName })
      setResult({ target, outcome })
      // 现状必须重新问一次原生侧：outcome 只描述「这一次尝试」，逐包清理也一样。
      setState(await bridge.getRuntimeCleanupState())
      setLoaded(true)
    } catch (error) {
      const note = failureNote(error)
      setActionFailed(note === null
        ? t('清理没能完成，请稍后重试；仍然失败请查看诊断与日志。')
        : t('清理没能完成：{0}', note))
      // 清理失败也要刷新：盘上的东西可能已经被删掉一部分，界面显示的必须是现在的事实。
      try { setState(await bridge.getRuntimeCleanupState()); setLoaded(true) } catch { setFailed(true) }
    } finally { setBusy(false) }
  }

  const ready = loaded && !failed && state !== null
  const running = ready && state.runtimeRunning
  const entries = (ready ? state.cache.entries : []).filter(entry => entry.present)
  const cacheReady = ready && !running && entries.length > 0
  const ledger = ready ? state.plugin.ledger : null
  const pluginReady = ready && !running && ledger === 'ok'

  return <div className="settings-subsection" aria-labelledby="runtime-cleanup">
    <div className="section-title"><span className="section-icon"><Trash2 size={19} /></span>
      <div><h3 id="runtime-cleanup">{t('缓存与插件数据')}</h3>
        <p>{t('清掉缓存与插件自己的数据；运行环境的数据在「重置运行环境」里处理。')}</p></div>
    </div>
    {!loaded && !failed && <p className="settings-note">{t('正在统计数据占用…')}</p>}
    {ready && running && <p className="settings-note"><strong>{t('运行时正在运行：请先停止 Harness 与 Ubuntu 终端再清理；正在使用的临时目录与插件数据不能删。')}</strong></p>}
    {ready && !running && entries.length === 0 && <p className="settings-note">{t('没有可清理的缓存')}</p>}
    {ready && entries.map(entry => <p className="settings-note" key={entry.name}>
      {t('{0}：{1} 个文件，共 {2}', t(ENTRY_LABELS[entry.name] ?? entry.name), String(entry.files), formatBytes(entry.bytes))}
    </p>)}
    {ready && entries.length > 0 && <p className="settings-note">
      <strong>{t('缓存合计：{0} 个文件，共 {1}', String(state.cache.files), formatBytes(state.cache.bytes))}</strong>
    </p>}
    {ready && <p className="settings-note">{t('插件数据：已登记 {0} 个包', String(state.plugin.checked))}</p>}
    {ready && ledger !== null && ledger !== 'ok' && <p className="settings-note">{t(LEDGER_NOTES[ledger])}</p>}
    {ready && ledger === 'ok' && state.plugin.packages.map(pkg => <p className="settings-note" key={pkg.id}>
      {pkg.present
        ? t('{0}：{1} 个文件，共 {2}', pkg.id, String(pkg.files), formatBytes(pkg.bytes))
        : t('{0}：还没有数据', pkg.id)}
      {' '}
      {/* 包名不进 testid（可能含 `@` 与 `/`），但**要进无障碍名**：几行按钮同名时，读屏用户分不清清的是哪一个。 */}
      <button type="button" disabled={!pluginReady || busy || !pkg.present}
        aria-label={t('只清插件 {0} 的数据', pkg.id)}
        onClick={() => setPending({ tier: 'plugin', packageName: pkg.id })}>{t('只清这个插件')}</button>
    </p>)}
    <p className="settings-note">{t('插件数据清理只清空插件自己的数据目录，插件本身仍然保留。')}</p>

    {pending?.tier !== 'cache' && <button type="button" disabled={!cacheReady || busy} onClick={() => setPending({ tier: 'cache' })}>{t('清理缓存')}</button>}
    {pending?.tier === 'cache' && <div role="alertdialog" aria-labelledby="runtime-cleanup-cache-confirm">
      <p className="settings-note" id="runtime-cleanup-cache-confirm"><strong>{t('确认清理缓存？')}</strong> {t('只删除诊断导出、工作区分享暂存与 PRoot 临时目录；不删待安装的更新包，也不碰运行环境的数据。')}</p>
      <button type="button" disabled={busy} onClick={() => { void clean({ tier: 'cache' }) }}>{busy ? t('正在清理…') : t('确认清理')}</button>
      <button type="button" disabled={busy} onClick={() => setPending(null)}>{t('取消')}</button>
    </div>}

    {pending?.tier !== 'plugin' && <button type="button" disabled={!pluginReady || busy} onClick={() => setPending({ tier: 'plugin' })}>{t('清理插件数据')}</button>}
    {pending?.tier === 'plugin' && pending.packageName === undefined && <div role="alertdialog" aria-labelledby="runtime-cleanup-plugin-confirm">
      <p className="settings-note" id="runtime-cleanup-plugin-confirm"><strong>{t('确认清理插件数据？')}</strong> {t('清空每个已安装插件自己的数据目录（data/config/storage/.config）；插件本身仍然保留，下次使用会重新生成数据。')}</p>
      <button type="button" disabled={busy} onClick={() => { void clean({ tier: 'plugin' }) }}>{busy ? t('正在清理…') : t('确认清理')}</button>
      <button type="button" disabled={busy} onClick={() => setPending(null)}>{t('取消')}</button>
    </div>}
    {/* 逐包的确认与整档的分开：这个块只在点名了包时出现，**文案必须点出是哪一个包**，
        而且要讲清「只清这一个、插件本体与其它插件的数据都不动」——否则与整档按钮长得一样，点错就清了全部。 */}
    {pending?.tier === 'plugin' && pending.packageName !== undefined && <div role="alertdialog" aria-labelledby="runtime-cleanup-package-confirm">
      <p className="settings-note" id="runtime-cleanup-package-confirm"><strong>{t('确认只清理插件 {0} 的数据？', pending.packageName)}</strong> {t('只清空这一个插件的数据目录（data/config/storage/.config）；插件本身仍然保留，其它插件的数据不动。')}</p>
      <button type="button" disabled={busy} onClick={() => { void clean(pending) }}>{busy ? t('正在清理…') : t('确认清理')}</button>
      <button type="button" disabled={busy} onClick={() => setPending(null)}>{t('取消')}</button>
    </div>}

    {result !== null && <p className="settings-note" role="status">
      {result.outcome.ok
        ? t('{0}：本次已清理 {1} 项，共 {2}。', targetLabel(result.target), String(result.outcome.removed), formatBytes(result.outcome.bytes))
        : t('{0}：本次有 {1} 项未能清理，详见诊断与日志。', targetLabel(result.target), String(result.outcome.failed))}
    </p>}
    {result !== null && result.outcome.fallback > 0 && <p className="settings-note">
      {t('其中 {0} 项走了兜底删除路径。', String(result.outcome.fallback))}
    </p>}
    {result !== null && result.outcome.skipped > 0 && <p className="settings-note">
      {t('另有 {0} 项本来就不存在，已跳过。', String(result.outcome.skipped))}
    </p>}
    {failed && <p role="alert">{t('无法读取数据占用')} <button type="button" onClick={() => { void refresh() }}>{t('重试')}</button></p>}
    {actionFailed !== null && <p role="alert">{actionFailed}</p>}
  </div>
}
