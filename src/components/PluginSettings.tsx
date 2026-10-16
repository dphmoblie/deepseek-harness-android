import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowLeft, ChevronDown, Package, RefreshCw, X } from 'lucide-react'
import { t, useLanguage } from '../i18n'
import type { PluginCatalog, PluginRecoveryState, PluginRequest, RuntimeBridge, RuntimeState } from '../platform/types'
import './PluginSettings.css'

/** 原生侧在无法给出更精确信息时使用的通用文案：与它相同时不重复展示。 */
const GENERIC_PLUGIN_FAILURE = '插件操作失败，请检查运行时状态后重试'

const errorMessages: Record<string, string> = {
  RUNTIME_BUSY: '请先停止 Harness 和 Ubuntu 终端',
  RUNTIME_NOT_INSTALLED: '请先安装 Ubuntu 运行时',
  PLUGIN_PROTECTED: '此核心组件受保护，随运行时更新',
  PLUGIN_UPDATE_FAILED: '插件更新失败，已保留原版本。请检查网络后重试。',
  PLUGIN_DEPENDENCY_UNSUPPORTED: '新版插件的依赖与当前运行时不兼容，已保留原版本',
  PLUGIN_ENGINE_UNSUPPORTED: '该插件没有与当前运行环境（dsh 版本）兼容的版本，已保留原版本。请升级运行环境后重试。',
  PLUGIN_NATIVE_UNSUPPORTED: '该插件包含在移动端受限的原生编译扩展（.node），无法在当前设备上正常运行，已保留原版本',
  PLUGIN_LINK_UNSUPPORTED: '设备不支持安全更新所需的文件链接，已保留原版本',
  PLUGIN_RECOVERY_FAILED: '上次更新尚未恢复，请重试或检查运行时',
  PLUGIN_GROUP_DISABLED: '请先启用所属配置文件',
  // 导入与回滚的受控失败：这些码由原生与访客侧共同定义，文案只讲"下一步做什么"。
  PLUGIN_SOURCE_INVALID: '这个来源地址不可用，请填 npm 包名或 https 地址',
  PLUGIN_IMPORT_UNRESOLVED: '无法确定导入的是哪个插件，请填上预期包名或改用包名再试一次',
  PLUGIN_GIT_MISSING: '当前运行时没有 git，请改用 npm 包名或 https 直链',
  PLUGIN_GIT_UNVERIFIED: '暂时无法确认运行环境是否支持 git，请稍后重试或改用 https 直链',
  PLUGIN_DATA_TOO_LARGE: '插件数据超过 16 MiB，请清理插件数据后重试（原版本保持不变）',
  PLUGIN_DATA_UNSAFE: '插件数据目录里有不安全的链接，已放弃更新（原版本保持不变）',
  PLUGIN_ROLLBACK_UNAVAILABLE: '这个插件没有可回滚的上一版',
  PLUGIN_ROLLBACK_FAILED: '回滚失败，已恢复到操作前的状态',
}

/** 各操作成功后的一句话；`list` 不提示（只是刷新）。 */
const successNotices: Record<string, string> = {
  enable: '插件设置已保存，下次启动生效',
  child: '插件设置已保存，下次启动生效',
  update: '插件包已更新，下次启动生效',
  import: '插件已导入，下次启动生效',
  rollback: '已回滚到上一版本，下次启动生效',
  repair: '插件修复已执行，列表已刷新',
  prune: '已清理历史孤立副本，释放了存储空间',
}

/**
 * 运行时升级后的插件恢复结果，以及人工重试入口。
 *
 * 提示出现有两种来源：原生侧最近一次自愈的受控计数（`recovery`），以及当前列表里
 * 「已启用却解析不到」的插件数（`unresolved`）。后者覆盖「自愈还没跑过」的空档 ——
 * 运行时或应用刚升级完、还没启动过 Harness 时，升级抹掉的插件链接就只表现为列表里
 * 的「未安装」，此时也必须能看到修复入口。
 *
 * 「立即修复插件」是显式重试：原生侧会绕过「同一代次只修一次」的自愈闸门与失败冷却，
 * 修完立刻回读一次列表，所以点完按钮界面上的版本号会跟着变。
 */
function RecoveryNotice({ recovery, unresolved, locked, onRepair, onPrune }: {
  recovery?: PluginRecoveryState
  unresolved: number
  locked: boolean
  onRepair: () => void
  onPrune?: () => void
}) {
  const reported = recovery !== undefined && recovery.status !== 'none'
  const restored = recovery?.restored ?? 0
  const relinked = recovery?.relinked ?? 0
  const missing = recovery?.missing ?? 0
  const failed = recovery?.failed ?? 0
  const refused = recovery?.refused ?? 0
  const migration = recovery?.migration ?? 'none'
  const lock = recovery?.lock ?? 'missing'
  const checked = recovery?.checked ?? 0
  const changed = restored + relinked
  const issueCount = reported ? missing + failed + refused : unresolved
  /*
   * 应用升级迁移（`migration`）与版本记录可用性（`lock`）是后加的状态，提示出现的规则有意收窄：
   * 只有「迁移只完成一部分 / 迁移没成功 / 版本记录损坏或写不进去」这四种要单独提示；迁移正常完成
   * 时并入下面已有文案，免得每次应用升级都在设置页留一条用户读完也不知道要做什么的提示。
   */
  const attention = migration === 'partial' || migration === 'failed' || lock === 'invalid' || lock === 'unwritable'
  if (!reported && !attention && unresolved === 0) return null
  const tone = recovery !== undefined && recovery.status !== 'none'
    ? recovery.status
    : migration === 'failed'
      ? 'failed'
      : 'partial'
  const mark = tone === 'restored' ? '✓' : tone === 'partial' ? '!' : '×'
  const title = reported
    ? tone === 'restored'
      ? '插件已恢复'
      : tone === 'partial'
        ? '插件已部分恢复'
        : '插件恢复失败'
    : migration === 'failed'
      ? '插件迁移没有完成'
      : unresolved > 0
        ? '有插件未接回运行时'
        : migration === 'partial'
          ? '有插件需要重新安装'
          : '插件版本记录有问题'
  const detail = reported
    ? changed > 0
      ? t('已恢复 {0} 个配置、重新链接 {1} 个插件。', restored, relinked)
      : t('没有可直接恢复的插件链接。')
    : unresolved > 0
      ? t('有 {0} 个已启用插件在当前运行时里解析不到，通常发生在运行时或应用升级之后。', unresolved)
      : ''
  const issues = reported && issueCount > 0
    ? t('缺失 {0} 个，失败 {1} 个，拒绝 {2} 个。', missing, failed, refused)
    : ''
  // 迁移结果只用用户看得懂的说法：说"版本记录"与"包还在不在设备上"，不出现内部文件名与字段名。
  const migrationNote = migration === 'done'
    ? checked > 0
      ? t('应用升级迁移已完成，已按版本记录核对 {0} 个插件，包都在设备上。', checked)
      : t('应用升级迁移已完成。')
    : migration === 'partial'
      ? t('应用升级迁移只完成了一部分，有插件的包已不在设备上，需要重新安装。')
      : migration === 'failed'
        ? t('应用升级迁移没有成功，下次启动 Harness 时会自动重试。')
        : ''
  const lockNote = lock === 'invalid'
    ? t('已装插件的版本记录已损坏，无法据此核对插件是否还在设备上。')
    : lock === 'unwritable'
      ? t('已装插件的版本记录写不进去（磁盘空间或权限问题），下次启动会再试一次。')
      : ''
  return <section className={`plugin-recovery plugin-recovery-${tone}`} aria-live="polite" role={tone === 'failed' ? 'alert' : 'status'}>
    <div className="plugin-recovery-mark" aria-hidden="true">{mark}</div>
    <div><strong>{t(title)}</strong><p>{detail} {issues} {migrationNote} {lockNote}</p>
      <div className="plugin-recovery-actions">
        <button className="button button-secondary compact-button" type="button" disabled={locked} onClick={onRepair}>{t('立即修复插件')}</button>
        {onPrune && <button className="button button-secondary compact-button" type="button" disabled={locked} onClick={onPrune}>{t('清理历史孤立副本')}</button>}
        <span className="plugin-hint">{t('按保留的插件包重新接回运行时链接，不重新下载；包本体已被清除的插件修不回来，需要重新安装。')}</span>
      </div>
    </div>
  </section>
}

export function PluginSettings({ bridge, runtime, onBack }: { bridge: RuntimeBridge; runtime: RuntimeState; onBack: () => void }) {
  useLanguage()
  const [catalog, setCatalog] = useState<PluginCatalog | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  /** 受控详情（包名与版本差异），与 notice 分开保存：notice 要能被 t() 整条查表翻译。 */
  const [noticeDetail, setNoticeDetail] = useState('')
  const [failed, setFailed] = useState(false)
  const [stopped, setStopped] = useState(false)
  /** 导入入口的两个输入：来源（必填）与期望包名（可选，用于地址形态消歧）。 */
  const [source, setSource] = useState('')
  const [expected, setExpected] = useState('')
  type FilterTab = 'all' | 'enabled' | 'relinkable' | 'thirdParty'
  const [filterTab, setFilterTab] = useState<FilterTab>('all')
  const [query, setQuery] = useState('')
  const active = useRef(true)
  const pending = useRef(false)
  const run = useCallback(async (request: PluginRequest): Promise<void> => {
    if (pending.current) return
    pending.current = true
    setBusy(true); setNotice(''); setNoticeDetail(''); setFailed(false)
    try {
      const result = await bridge.managePlugins(request)
      if (active.current) {
        setCatalog(result)
        if (request.operation !== 'list') setNotice(successNotices[request.operation] ?? '插件设置已保存，下次启动生效')
        // 导入成功后清空输入：同一条来源再点一次只会命中既有安装，清空更不容易误解成"导入两次"。
        if (request.operation === 'import') { setSource(''); setExpected('') }
      }
    } catch (error) {
      if (active.current) {
        const code = error && typeof error === 'object' && 'code' in error && typeof error.code === 'string' ? error.code : ''
        // 原生侧会在能定位问题时把受控详情（包名与版本差异）放进 message；
        // 与通用文案相同时不重复展示。
        const raw = error && typeof error === 'object' && 'message' in error && typeof error.message === 'string' ? error.message : ''
        const notice = errorMessages[code] ?? GENERIC_PLUGIN_FAILURE
        // 受控码的文案常常与原生消息逐字相同（例如 RUNTIME_BUSY）：同一句话只显示一次。
        const detail = raw !== '' && raw !== GENERIC_PLUGIN_FAILURE && raw !== notice ? raw : ''
        setNotice(notice)
        setNoticeDetail(detail)
        setFailed(true)
      }
    } finally { pending.current = false; if (active.current) setBusy(false) }
  }, [bridge])
  useEffect(() => {
    active.current = true
    void run({ operation: 'list' })
    return () => { active.current = false }
  }, [run])
  useEffect(() => { setStopped(false) }, [runtime.phase])
  const stop = async (): Promise<void> => {
    if (pending.current) return
    pending.current = true; setBusy(true); setNotice(''); setNoticeDetail('')
    try {
      await bridge.stopRuntime()
      if (active.current) { setStopped(true); setNotice('运行环境已停止，可以管理插件'); setFailed(false) }
    } catch { if (active.current) { setNotice('停止运行环境失败，请重试'); setFailed(true) } }
    finally { pending.current = false; if (active.current) setBusy(false) }
  }
  const locked = busy || (!stopped && ['running', 'stopping', 'preparing', 'downloading', 'verifying', 'extracting'].includes(runtime.phase))
  /** 显式重试一次修复：与自愈共用同一个原生入口，但不受它的代次闸门与冷却窗口约束。 */
  const repair = useCallback(() => { void run({ operation: 'repair' }) }, [run])
  /** 清理保留区中未被引用的历史孤立副本。 */
  const prune = useCallback(() => { void run({ operation: 'prune' }) }, [run])
  /** 已启用却在当前运行时里解析不到的插件包：升级抹掉链接后，列表就是这个样子。 */
  const unresolved = catalog?.plugins.filter(group => group.enabled && !group.installed && !group.official).length ?? 0

  const matchesFilter = (group: PluginCatalog['plugins'][number]) => {
    if (filterTab === 'enabled' && !group.enabled) return false
    if (filterTab === 'relinkable' && (group.installed || !group.preserved?.exists)) return false
    if (filterTab === 'thirdParty' && group.official) return false
    if (query.trim() !== '') {
      const q = query.trim().toLowerCase()
      const matchId = group.id.toLowerCase().includes(q)
      const matchFile = group.file.toLowerCase().includes(q)
      const matchChildren = group.children.some(c => c.id.toLowerCase().includes(q) || c.name.toLowerCase().includes(q))
      if (!matchId && !matchFile && !matchChildren) return false
    }
    return true
  }

  return <div className="screen plugin-screen">
    <div className="screen-heading management-heading">
      <div><p className="eyebrow">{t('应用管理')}</p><h1>{t('插件管理')}</h1></div>
      <div className="heading-actions">
        <button className="button button-secondary compact-button" type="button" disabled={busy} onClick={() => { void run({ operation: 'list' }) }}><RefreshCw size={18} />{t('刷新')}</button>
        <button className="icon-button" type="button" title={t('返回设置')} aria-label={t('返回设置')} onClick={onBack} disabled={busy}><ArrowLeft size={19} /></button>
      </div>
    </div>
    <p className="plugin-description">{t('无需启动 Harness 即可管理插件：每个条目是一个插件包，展开后可调整其中的子插件。')}</p>
    <div className="plugin-stop"><span>{t('修改插件前请先停止 Harness 和 Ubuntu 终端；改动在下次启动时生效。')}</span><button className="button button-danger-quiet compact-button" type="button" disabled={busy} onClick={() => { void stop() }}>{t('停止运行环境')}</button></div>
    {/* 受控导入：来源形态在原生与访客两侧各校验一次，这里只负责收集与提示，不做任何解析。 */}
    <section className="plugin-import" aria-labelledby="plugin-import-title">
      <h2 id="plugin-import-title">{t('通过 npm 包名或链接导入')}</h2>
      <p className="plugin-description">{t('支持 npm 包名、HTTPS 压缩包直链和 git+https 仓库地址。导入时不执行包内的安装脚本；来源校验不通过时不会下载。')}</p>
      <div className="plugin-import-row">
        <input type="text" aria-label={t('插件来源')} placeholder={t('例如插件包名，或 https://…/plugin.tgz')} value={source} disabled={busy} onChange={event => setSource(event.target.value)} />
        <button className="button compact-button" type="button" disabled={busy || locked || source.trim() === ''} onClick={() => { void run({ operation: 'import', source: source.trim(), ...(expected.trim() === '' ? {} : { id: expected.trim() }) }) }}>{t('导入')}</button>
      </div>
      <label className="plugin-import-expected">
        <span>{t('预期包名（可选）')}</span>
        <input type="text" aria-label={t('预期包名')} value={expected} disabled={busy} onChange={event => setExpected(event.target.value)} />
      </label>
      <p className="plugin-hint">{t('通过链接导入时，可填写预期包名，以核对安装结果；如果链接中有多个插件包，可用它指定要安装的包。')}</p>
    </section>
    {notice && <p className="plugin-notice" role={failed ? 'alert' : 'status'}>{t(notice)}{noticeDetail !== '' && `（${noticeDetail}）`}</p>}
    {catalog && <RecoveryNotice recovery={catalog.recovery} unresolved={unresolved} locked={locked} onRepair={repair} onPrune={prune} />}
    {busy && <p role="status">{t('正在处理插件，请稍候')}</p>}

    {/* 搜索与分类过滤 */}
    <div className="plugin-filter-bar">
      <div className="search-control plugin-search" role="search" aria-label={t('搜索插件')}>
      <input
        type="search"
        aria-label={t('搜索插件')}
        placeholder={t('搜索插件包名或子插件…')}
        value={query}
        disabled={busy}
        onChange={event => setQuery(event.target.value)}
      />
      {query !== '' && (
        <button className="search-control-clear" type="button" onClick={() => setQuery('')} disabled={busy} aria-label={t('清空查找词')}>
          <X size={16} />
        </button>
      )}
      </div>
      <div className="plugin-tabs" role="tablist" aria-label={t('插件分类')}>
        {([
          { key: 'all', label: '全部' },
          { key: 'enabled', label: '已启用' },
          { key: 'relinkable', label: '待补充链接' },
          { key: 'thirdParty', label: '第三方' },
        ] as const).map(tab => (
          <button
            key={tab.key}
            role="tab"
            aria-selected={filterTab === tab.key}
            className={`plugin-tab ${filterTab === tab.key ? 'plugin-tab-active' : ''}`}
            type="button"
            onClick={() => setFilterTab(tab.key)}
          >
            {t(tab.label)}
          </button>
        ))}
      </div>
    </div>

    {catalog?.plugins.length === 0 && <p>{t('暂无插件。安装运行环境后点击「刷新」。')}</p>}
    {[true, false].map(official => {
      const groups = catalog?.plugins.filter(group => group.official === official && matchesFilter(group)) ?? []
      return <section key={String(official)} className="plugin-category" aria-label={t(official ? '官方插件' : '第三方插件')}>
        <h2>{t(official ? '官方插件' : '第三方插件')}</h2>
        {official && <p className="plugin-description">{t('官方包随运行时更新，避免覆盖 Android 兼容修补。安全组件不可禁用。')}</p>}
        {groups.map(group => <details className="plugin-file" key={group.id}>
          <summary>
            <Package className="plugin-summary-icon" size={20} />
            <span className="plugin-summary-copy">
              <strong>{group.id}</strong>
              <small>{group.file} · {group.version ?? t('未安装')}</small>
              <div className="plugin-dual-status">
                <span className={`plugin-status-badge ${group.installed ? 'status-active' : 'status-unlinked'}`}>
                  {group.installed ? `● ${t('当前环境：已链接')}` : `○ ${t('当前环境：未链接')}`}
                </span>
                <span className={`plugin-status-badge ${group.preserved?.exists ? 'status-preserved' : 'status-empty'}`}>
                  {group.preserved?.exists ? `📁 ${t('手机保留区：v{0}', group.preserved.version ?? '')}` : `📁 ${t('手机保留区：无副本')}`}
                </span>
                {!group.readable && (
                  <span className="plugin-status-badge status-error">{t('配置不可读')}</span>
                )}
                {!group.installed && group.preserved?.exists && (
                  <span className="plugin-status-badge status-relinkable">{t('待补充链接')}</span>
                )}
              </div>
            </span>
            <span className="plugin-state plugin-summary-state">{t(group.enabled ? '已启用' : '已禁用')}</span>
            <ChevronDown className="plugin-summary-chevron" size={18} />
          </summary>
          <div className="plugin-file-actions">
            <label><input type="checkbox" aria-label={t('启用文件 {0}', group.id)} checked={group.enabled} disabled={locked || group.protected} onChange={event => { void run({ operation: 'enable', id: group.id, enabled: event.target.checked }) }} />{t('启用整个文件')}</label>
            <div className="plugin-file-buttons">
              <button className="button button-secondary compact-button" disabled={locked || group.official} type="button" onClick={() => { void run({ operation: 'update', id: group.id }) }}>{t('更新所属插件包')}</button>
              {/* 回滚只动这一个包，且只回退到最近一次更新前保留的那一版；没有上一版时保持禁用，由下方提示说明原因。 */}
              <button className="button button-secondary compact-button" disabled={locked || group.protected || (group.rollback ?? null) === null} type="button" onClick={() => { void run({ operation: 'rollback', id: group.id }) }}>{t('回滚到上一版本')}</button>
            </div>
          </div>
          {!group.installed && group.preserved?.exists && (
            <div className="plugin-relink-banner">
              <span>{t('检测到手机保留区已有此插件副本（v{0}），可直接接回当前运行环境。', group.preserved.version ?? '')}</span>
              <button className="button button-secondary compact-button" disabled={locked} type="button" onClick={repair}>{t('立即补充链接')}</button>
            </div>
          )}
          {group.rollback && <p className="plugin-hint">{t('可回滚到上一版本 {0}。', group.rollback)}</p>}
          {!group.rollback && !group.official && !group.protected && <p className="plugin-hint">{t('没有可回滚的上一版本：每个插件只保留最近一次更新前的那一版。')}</p>}
          {group.protected && <p className="plugin-hint">{t('核心配置文件不可整体禁用，可管理下方非安全子插件。')}</p>}
          {!group.readable && <p className="plugin-hint" role="alert">{t('配置文件无法读取，仍可禁用或更新所属第三方插件包。')}</p>}
          {group.children.length > 0 && <ul className="plugin-children">{group.children.map(child => <li key={child.id}>
            <span><strong>{child.id}</strong><small>{child.name}</small><small>{t(child.effectiveEnabled ? '已启用' : child.enabled ? '受所属文件或父插件禁用影响' : '已禁用')}</small></span>
            <label><input type="checkbox" aria-label={t('启用子插件 {0}', child.id)} checked={child.enabled} disabled={locked || !group.enabled || child.protected} onChange={event => { void run({ operation: 'child', id: group.id, childId: child.id, enabled: event.target.checked }) }} />{t(child.protected ? '安全组件' : '启用')}</label>
          </li>)}</ul>}
          <p className="plugin-hint">{t('子插件随所属插件包更新，不单独替换文件。')}</p>
        </details>)}
      </section>
    })}
  </div>
}
