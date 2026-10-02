import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowLeft, ChevronDown, Package, RefreshCw } from 'lucide-react'
import { t, useLanguage } from '../i18n'
import type { PluginCatalog, PluginRequest, RuntimeBridge, RuntimeState } from '../platform/types'
import './PluginSettings.css'

/** 原生侧在无法给出更精确信息时使用的通用文案：与它相同时不重复展示。 */
const GENERIC_PLUGIN_FAILURE = '插件操作失败，请检查运行时状态后重试'

const errorMessages: Record<string, string> = {  RUNTIME_BUSY: '请先停止 Harness 和 Ubuntu 终端',
  RUNTIME_NOT_INSTALLED: '请先安装 Ubuntu 运行时',
  PLUGIN_PROTECTED: '此核心组件受保护，随运行时更新',
  PLUGIN_UPDATE_FAILED: '插件更新失败，已保留原版本。请检查网络后重试。',
  PLUGIN_DEPENDENCY_UNSUPPORTED: '新版插件的依赖与当前运行时不兼容，已保留原版本',
  PLUGIN_ENGINE_UNSUPPORTED: '该插件没有与当前运行环境（dsh 版本）兼容的版本，已保留原版本。请升级运行环境后重试。',
  PLUGIN_LINK_UNSUPPORTED: '设备不支持安全更新所需的文件链接，已保留原版本',
  PLUGIN_RECOVERY_FAILED: '上次更新尚未恢复，请重试或检查运行时',
  PLUGIN_GROUP_DISABLED: '请先启用所属配置文件',
  // 导入与回滚的受控失败：这些码由原生与访客侧共同定义，文案只讲"下一步做什么"。
  PLUGIN_SOURCE_INVALID: '这个来源地址不可用，请填 npm 包名或 https 地址',
  PLUGIN_IMPORT_UNRESOLVED: '无法确定导入的是哪个插件，请填上期望的包名或改用包名再试一次',
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
        const detail = raw !== '' && raw !== GENERIC_PLUGIN_FAILURE ? raw : ''
        setNotice(errorMessages[code] ?? GENERIC_PLUGIN_FAILURE)
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
      <h2 id="plugin-import-title">{t('从 npm 包名或连接地址导入')}</h2>
      <p className="plugin-description">{t('支持三种来源：npm 包名、https 直链 tarball、git+https 仓库地址。安装脚本一律不执行；来源不合格会在本机被拒绝，不会下载或回显你填的内容。')}</p>
      <div className="plugin-import-row">
        <input type="text" aria-label={t('插件来源')} placeholder={t('例如插件包名，或 https://…/plugin.tgz')} value={source} disabled={busy} onChange={event => setSource(event.target.value)} />
        <button className="button compact-button" type="button" disabled={busy || locked || source.trim() === ''} onClick={() => { void run({ operation: 'import', source: source.trim(), ...(expected.trim() === '' ? {} : { id: expected.trim() }) }) }}>{t('导入')}</button>
      </div>
      <label className="plugin-import-expected">
        <span>{t('期望的包名（可选）')}</span>
        <input type="text" aria-label={t('期望的包名')} value={expected} disabled={busy} onChange={event => setExpected(event.target.value)} />
      </label>
      <p className="plugin-hint">{t('只有地址形态需要「期望的包名」：装完后用它核对装的是不是这个包，出现多个候选时也靠它消歧。')}</p>
    </section>
    {notice && <p className="plugin-notice" role={failed ? 'alert' : 'status'}>{t(notice)}{noticeDetail !== '' && `（${noticeDetail}）`}</p>}
    {busy && <p role="status">{t('正在处理插件，请稍候')}</p>}
    {catalog?.plugins.length === 0 && <p>{t('暂无插件。安装运行环境后点击「刷新」。')}</p>}
    {[true, false].map(official => <section key={String(official)} className="plugin-category" aria-label={t(official ? '官方插件' : '第三方插件')}>
      <h2>{t(official ? '官方插件' : '第三方插件')}</h2>
      {official && <p className="plugin-description">{t('官方包随运行时更新，避免覆盖 Android 兼容修补。安全组件不可禁用。')}</p>}
      {catalog?.plugins.filter(group => group.official === official).map(group => <details className="plugin-file" key={group.id}>
        <summary><Package className="plugin-summary-icon" size={20} /><span className="plugin-summary-copy"><strong>{group.id}</strong><small>{group.file} · {group.version ?? t('未安装')}</small></span><span className="plugin-state plugin-summary-state">{t(group.enabled ? '已启用' : '已禁用')}</span><ChevronDown className="plugin-summary-chevron" size={18} /></summary>
        <div className="plugin-file-actions">
          <label><input type="checkbox" aria-label={t('启用文件 {0}', group.id)} checked={group.enabled} disabled={locked || group.protected} onChange={event => { void run({ operation: 'enable', id: group.id, enabled: event.target.checked }) }} />{t('启用整个文件')}</label>
          <div className="plugin-file-buttons">
            <button className="button button-secondary compact-button" disabled={locked || group.official} type="button" onClick={() => { void run({ operation: 'update', id: group.id }) }}>{t('更新所属插件包')}</button>
            {/* 回滚只动这一个包，且只回退到最近一次更新前保留的那一版；没有上一版时保持禁用，由下方提示说明原因。 */}
            <button className="button button-secondary compact-button" disabled={locked || group.protected || (group.rollback ?? null) === null} type="button" onClick={() => { void run({ operation: 'rollback', id: group.id }) }}>{t('回滚到上一版本')}</button>
          </div>
        </div>
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
    </section>)}
  </div>
}
