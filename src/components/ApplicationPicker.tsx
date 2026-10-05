import { useEffect, useRef, useState } from 'react'
import { Search, Smartphone } from 'lucide-react'
import type { InstalledApplication, RuntimeBridge } from '../platform/types'
import { MAX_ACCESSIBILITY_PACKAGES } from '../platform/validation'
import { t } from '../i18n'
import { isVisible, normalizeSearchQuery, orderForDisplay, type VisibilityFilters } from './applicationSearchQuery'
import './ApplicationPicker.css'

const SEARCH_DEBOUNCE_MS = 200

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
  const [automationOnly, setAutomationOnly] = useState(false)
  const [hideSystem, setHideSystem] = useState(false)
  const [attempt, setAttempt] = useState(0)
  /**
   * 竞态票据 + 桥引用。
   *
   * `bridge` **刻意不放进依赖数组**：父组件每次渲染都新建 bridge 对象时（真机上设置页
   * 每 5 秒轮询一次就会重渲染），effect 会被反复作废重启，200 ms 防抖永远等不到落地，
   * 界面就停在「正在读取应用列表」。改用 ref 拿到最新桥，行为不变而重渲染不再影响请求。
   *
   * `generation` 是**唯一的作废口径**：任何一个新请求（含卸载、重试、输入变化）都会
   * `generation.current++`，旧请求的 then/catch/finally 都要先比票据再写状态——
   * 包括把 loading 置回 false 这一步：改前只有 then/catch 比票据，慢请求回来时
   * 会把新一轮的 loading 清掉（或永远清不掉），这正是「永远在读取」的第二个来源。
   */
  const bridgeRef = useRef(bridge)
  bridgeRef.current = bridge
  const generation = useRef(0)
  /** 上一次 effect 见过的词：只在「用户改了输入」时防抖，打开面板与重试都立刻请求。 */
  const lastQuery = useRef<string | null>(null)
  useEffect(() => {
    if (!opened) return
    setLoading(true)
    setFailed(false)
    const ticket = ++generation.current
    const normalized = normalizeSearchQuery(query)
    const changed = lastQuery.current !== null && lastQuery.current !== normalized
    lastQuery.current = normalized
    const timer = window.setTimeout(() => {
      void bridgeRef.current.listInstalledApplications(normalized, 0).then(page => {
        if (ticket !== generation.current) return
        setApps(page.apps); setNextOffset(page.nextOffset); setTotal(page.total)
      }).catch(() => {
        if (ticket !== generation.current) return
        setFailed(true); setApps([]); setNextOffset(null); setTotal(0)
      }).finally(() => { if (ticket === generation.current) setLoading(false) })
    }, changed ? SEARCH_DEBOUNCE_MS : 0)
    return () => { window.clearTimeout(timer); generation.current = ticket + 1 }
  }, [opened, query, attempt])
  async function more() {
    if (nextOffset === null || loading) return
    const ticket = ++generation.current
    setLoading(true)
    try {
      const page = await bridgeRef.current.listInstalledApplications(normalizeSearchQuery(query), nextOffset)
      if (ticket !== generation.current) return
      setApps(current => [...new Map([...current, ...page.apps].map(app => [app.packageName, app])).values()])
      setNextOffset(page.nextOffset); setTotal(page.total); setFailed(false)
    } catch { if (ticket === generation.current) setFailed(true) }
    finally { if (ticket === generation.current) setLoading(false) }
  }
  function retry() {
    generation.current++
    setAttempt(value => value + 1)
  }
  function toggleFilter(setter: (value: boolean) => void, value: boolean) {
    setter(value)
    // 过滤只改可见性，不重新请求：清掉的只是已经拿到的这一页，翻页进度与已勾选都不受影响。
    setFailed(false)
  }
  const filters: VisibilityFilters = { automationOnly, hideSystem }
  const visible = apps.filter(app => isVisible(app, filters))
  const hidden = apps.length - visible.length
  const searching = normalizeSearchQuery(query).length > 0
  return <div className="application-picker">
    <button type="button" className="button button-secondary" aria-expanded={opened} onClick={() => setOpened(!opened)} disabled={disabled}>
      <Smartphone size={18} />{t('从已安装应用选择')}
    </button>
    {opened && <div className="application-picker-panel">
      <label className="field"><span><Search size={16} />{t('搜索应用名称或包名')}</span>
        <input value={query} maxLength={160} onChange={event => setQuery(event.target.value)} placeholder={t('输入应用名称或包名')}
          aria-label={t('搜索应用名称或包名')} />
      </label>
      <p className="settings-note">{t('支持拼音与首字母：微信可搜 wx、weixin；多个词用空格分开，顺序随意。')}</p>
      <div className="application-picker-filters" role="group" aria-label={t('列表筛选')}>
        <label><input type="checkbox" checked={automationOnly} disabled={disabled}
          onChange={event => toggleFilter(setAutomationOnly, event.target.checked)} /><span>{t('只看可自动化应用')}</span></label>
        <label><input type="checkbox" checked={hideSystem} disabled={disabled}
          onChange={event => toggleFilter(setHideSystem, event.target.checked)} /><span>{t('隐藏系统组件')}</span></label>
      </div>
      <p className="settings-note">{t('已选择 {0} 个应用，白名单最多 {1} 个；勾选后点击保存白名单。', selected.length, MAX_ACCESSIBILITY_PACKAGES)}</p>
      {/* 已选项**不因为超过上限被丢弃**（可能有历史数据），但要说清楚：这样保存会被拒绝。 */}
      {selected.length > MAX_ACCESSIBILITY_PACKAGES && <p className="settings-note" role="status">
        {t('已选 {0} 个，超过上限 {1} 个；保存前请先取消多余的勾选，否则保存会被拒绝。', selected.length, selected.length - MAX_ACCESSIBILITY_PACKAGES)}
      </p>}
      {failed && <p role="alert">{t('无法读取应用列表，请在安卓设备上重试。')} <button type="button" onClick={retry}>{t('重试')}</button></p>}
      <div className="application-picker-list" aria-busy={loading}>
        {orderForDisplay(visible, selected).map(app => <label className="application-picker-row" key={app.packageName}>
          <input type="checkbox" checked={selected.includes(app.packageName)} disabled={disabled || (!app.selectable && !selected.includes(app.packageName))}
            onChange={event => onChange(event.target.checked ? [...new Set([...selected, app.packageName])] : selected.filter(value => value !== app.packageName))} />
          <span><strong>{app.label || app.packageName}</strong><small>{app.packageName}</small>
            {!app.selectable && <small>{t('系统安全组件，不支持无障碍自动化')}</small>}
            {app.system && app.selectable && <small>{t('系统应用')}</small>}
          </span>
        </label>)}
      </div>
      {/* 三种「空」要分开说：读取中 / 读取失败 / 真的没有匹配，不然后两类都会被当成「还在读」。 */}
      {!loading && !failed && visible.length === 0 && <p>{hidden > 0
        ? t('当前筛选条件下没有可显示的应用，取消勾选「只看可自动化应用」或「隐藏系统组件」可看到全部。')
        : searching ? t('没有匹配的应用') : t('没有可显示的应用')}</p>}
      {loading && apps.length === 0 && <p role="status">{t('正在读取应用列表')}</p>}
      {hidden > 0 && <p className="settings-note">{t('已隐藏 {0} 个应用（当前页面内）。', hidden)}</p>}
      {nextOffset !== null && <button type="button" className="button button-secondary" aria-label={t('加载更多应用')}
        onClick={() => { void more() }} disabled={loading}>{t('加载更多应用')}</button>}
      {!failed && <small>{hidden > 0
        ? t('当前显示 {0} / {1}（已加载）', visible.length, apps.length)
        : t('当前显示 {0} / {1}', apps.length, total)}</small>}
    </div>}
  </div>
}
