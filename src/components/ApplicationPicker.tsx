import { useEffect, useRef, useState } from 'react'
import { Search, Smartphone } from 'lucide-react'
import type { InstalledApplication, RuntimeBridge } from '../platform/types'
import { t } from '../i18n'
import './ApplicationPicker.css'

export function ApplicationPicker({ bridge, selected, onChange, disabled }: {
  bridge: RuntimeBridge; selected: string[]; onChange: (packages: string[]) => void; disabled: boolean
}) {
  const [opened, setOpened] = useState(false)
  const [query, setQuery] = useState('')
  const [apps, setApps] = useState<InstalledApplication[]>([])
  const [nextOffset, setNextOffset] = useState<number | null>(null)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const generation = useRef(0)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    if (!opened) return
    const ticket = ++generation.current
    setLoading(true)
    setFailed(false)
    const timer = window.setTimeout(() => {
      void bridge.listInstalledApplications(query.trim(), 0).then(page => {
        if (ticket !== generation.current) return
        setApps(page.apps); setNextOffset(page.nextOffset); setTotal(page.total)
      }).catch(() => {
        if (ticket === generation.current) { setFailed(true); setApps([]); setNextOffset(null) }
      }).finally(() => { if (ticket === generation.current) setLoading(false) })
    }, 200)
    return () => { window.clearTimeout(timer); generation.current = ticket + 1 }
  }, [bridge, opened, query, attempt])
  async function more() {
    if (nextOffset === null || loading) return
    const ticket = generation.current
    setLoading(true)
    try {
      const page = await bridge.listInstalledApplications(query.trim(), nextOffset)
      if (ticket !== generation.current) return
      setApps(current => [...new Map([...current, ...page.apps].map(app => [app.packageName, app])).values()])
      setNextOffset(page.nextOffset); setTotal(page.total); setFailed(false)
    } catch { if (ticket === generation.current) setFailed(true) }
    finally { if (ticket === generation.current) setLoading(false) }
  }
  return <div className="application-picker">
    <button type="button" className="button button-secondary" aria-expanded={opened} onClick={() => setOpened(!opened)} disabled={disabled}>
      <Smartphone size={18} />{t('从已安装应用选择')}
    </button>
    {opened && <div className="application-picker-panel">
      <label className="field"><span><Search size={16} />{t('搜索应用名称或包名')}</span>
        <input value={query} maxLength={160} onChange={event => setQuery(event.target.value)} placeholder={t('输入应用名称或包名')} />
      </label>
      <p className="settings-note">{t('已选择 {0} 个应用，不限制白名单数量；勾选后点击保存白名单。', selected.length)}</p>
      {failed && <p role="alert">{t('无法读取应用列表，请在安卓设备上重试。')} <button type="button" onClick={() => setAttempt(value => value + 1)}>{t('重试')}</button></p>}
      <div className="application-picker-list" aria-busy={loading}>
        {apps.map(app => <label className="application-picker-row" key={app.packageName}>
          <input type="checkbox" checked={selected.includes(app.packageName)} disabled={disabled || (!app.selectable && !selected.includes(app.packageName))}
            onChange={event => onChange(event.target.checked ? [...new Set([...selected, app.packageName])] : selected.filter(value => value !== app.packageName))} />
          <span><strong>{app.label || app.packageName}</strong><small>{app.packageName}</small>
            {!app.selectable && <small>{t('系统安全组件，不支持无障碍自动化')}</small>}
          </span>
        </label>)}
      </div>
      {!loading && !failed && apps.length === 0 && <p>{t('没有匹配的应用')}</p>}
      {loading && <p role="status">{t('正在读取应用列表')}</p>}
      {nextOffset !== null && <button type="button" className="button button-secondary" onClick={() => { void more() }} disabled={loading}>{t('加载更多应用')}</button>}
      {!failed && <small>{t('当前显示 {0} / {1}', apps.length, total)}</small>}
    </div>}
  </div>
}
