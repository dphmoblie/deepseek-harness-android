import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowLeft, ChevronDown, Package, RefreshCw } from 'lucide-react'
import { t, useLanguage } from '../i18n'
import type { PluginCatalog, PluginRequest, RuntimeBridge, RuntimeState } from '../platform/types'
import './PluginSettings.css'

const errorMessages: Record<string, string> = {
  RUNTIME_BUSY: '请先停止 Harness 和 Ubuntu 终端',
  RUNTIME_NOT_INSTALLED: '请先安装 Ubuntu 运行时',
  PLUGIN_PROTECTED: '此核心组件受保护，随运行时更新',
  PLUGIN_UPDATE_FAILED: '插件更新失败，已保留原版本。请检查网络后重试。',
  PLUGIN_DEPENDENCY_UNSUPPORTED: '新版插件的依赖与当前运行时不兼容，已保留原版本',
  PLUGIN_ENGINE_UNSUPPORTED: '该插件没有与当前运行环境（dsh 版本）兼容的版本，已保留原版本。请升级运行环境后重试。',
  PLUGIN_LINK_UNSUPPORTED: '设备不支持安全更新所需的文件链接，已保留原版本',
  PLUGIN_RECOVERY_FAILED: '上次更新尚未恢复，请重试或检查运行时',
  PLUGIN_GROUP_DISABLED: '请先启用所属配置文件',
}

export function PluginSettings({ bridge, runtime, onBack }: { bridge: RuntimeBridge; runtime: RuntimeState; onBack: () => void }) {
  useLanguage()
  const [catalog, setCatalog] = useState<PluginCatalog | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [failed, setFailed] = useState(false)
  const [stopped, setStopped] = useState(false)
  const active = useRef(true)
  const pending = useRef(false)
  const run = useCallback(async (request: PluginRequest): Promise<void> => {
    if (pending.current) return
    pending.current = true
    setBusy(true); setNotice(''); setFailed(false)
    try {
      const result = await bridge.managePlugins(request)
      if (active.current) {
        setCatalog(result)
        if (request.operation !== 'list') setNotice(request.operation === 'update' ? '插件包已更新，下次启动生效' : '插件设置已保存，下次启动生效')
      }
    } catch (error) {
      if (active.current) {
        const code = error && typeof error === 'object' && 'code' in error && typeof error.code === 'string' ? error.code : ''
        setNotice(errorMessages[code] ?? '插件操作失败，请重试'); setFailed(true)
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
    pending.current = true; setBusy(true); setNotice('')
    try {
      await bridge.stopRuntime()
      if (active.current) { setStopped(true); setNotice('运行时已停止，可以管理插件'); setFailed(false) }
    } catch { if (active.current) { setNotice('停止运行时失败，请重试'); setFailed(true) } }
    finally { pending.current = false; if (active.current) setBusy(false) }
  }
  const locked = busy || (!stopped && ['running', 'stopping', 'preparing', 'downloading', 'verifying', 'extracting'].includes(runtime.phase))
  return <div className="screen plugin-screen">
    <button className="button button-quiet" type="button" onClick={onBack} disabled={busy}><ArrowLeft size={18} />{t('返回设置')}</button>
    <div className="screen-heading">
      <div><p className="eyebrow">{t('应用管理')}</p><h1>{t('插件管理')}</h1></div>
      <button className="button button-quiet" type="button" disabled={busy} onClick={() => { void run({ operation: 'list' }) }}><RefreshCw size={18} />{t('刷新')}</button>
    </div>
    <p className="plugin-description">{t('无需启动 Harness，即可按配置文件管理插件。展开文件可调整子插件。')}</p>
    <div className="plugin-stop"><span>{t('修改前请停止 Harness 和 Ubuntu 终端；设置将在下次启动生效。')}</span><button className="button button-danger-quiet" type="button" disabled={busy} onClick={() => { void stop() }}>{t('停止运行时')}</button></div>
    {notice && <p className="plugin-notice" role={failed ? 'alert' : 'status'}>{t(notice)}</p>}
    {busy && <p role="status">{t('正在处理插件，请稍候')}</p>}
    {catalog?.plugins.length === 0 && <p>{t('暂无插件。请在 Android 设备上安装运行时后刷新。')}</p>}
    {[true, false].map(official => <section key={String(official)} className="plugin-category" aria-label={t(official ? '官方插件' : '第三方插件')}>
      <h2>{t(official ? '官方插件' : '第三方插件')}</h2>
      {official && <p className="plugin-description">{t('官方包随运行时更新，避免覆盖 Android 兼容修补。安全组件不可禁用。')}</p>}
      {catalog?.plugins.filter(group => group.official === official).map(group => <details className="plugin-file" key={group.id}>
        <summary><Package size={20} /><span><strong>{group.id}</strong><small>{group.file} · {group.version ?? t('未安装')}</small></span><span className="plugin-state">{t(group.enabled ? '已启用' : '已禁用')}</span><ChevronDown size={18} /></summary>
        <div className="plugin-file-actions">
          <label><input type="checkbox" aria-label={t('启用文件 {0}', group.id)} checked={group.enabled} disabled={locked || group.protected} onChange={event => { void run({ operation: 'enable', id: group.id, enabled: event.target.checked }) }} />{t('启用整个文件')}</label>
          <button className="button button-quiet compact-button" disabled={locked || group.official} type="button" onClick={() => { void run({ operation: 'update', id: group.id }) }}>{t('更新所属插件包')}</button>
        </div>
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
