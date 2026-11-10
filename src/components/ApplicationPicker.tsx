import { useEffect, useRef, useState } from 'react'
import { Search, Smartphone, X } from 'lucide-react'
import type { InstalledApplication, RuntimeBridge } from '../platform/types'
import { t } from '../i18n'
import { isVisible, normalizeSearchQuery, orderForDisplay, type VisibilityFilters } from './applicationSearchQuery'
import './ApplicationPicker.css'

const SEARCH_DEBOUNCE_MS = 200

export function ApplicationPicker({ bridge, selected, onChange, disabled }: {
  bridge: RuntimeBridge; selected: string[]; onChange: (packages: string[]) => void; disabled: boolean
}) {
  const [opened, setOpened] = useState(false)
  const [query, setQuery] = useState('')
  const [isComposing, setIsComposing] = useState(false)
  const [apps, setApps] = useState<InstalledApplication[]>([])
  const [nextOffset, setNextOffset] = useState<number | null>(null)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const [automationOnly, setAutomationOnly] = useState(false)
  const [hideSystem, setHideSystem] = useState(false)
  const [attempt, setAttempt] = useState(0)
  /**
   * 「读取应用列表」权限状态。
   *
   * `denied` 时**不发起列表请求**：国产 ROM 上那会返回一份只有本应用的清单，看起来像
   * 「搜索坏了」，而不是「缺权限」——必须先把原因说清楚。
   */
  const [access, setAccess] = useState<'checking' | 'granted' | 'denied' | 'unsupported'>('checking')
  const [requestingAccess, setRequestingAccess] = useState(false)
  const [accessRequested, setAccessRequested] = useState(false)
  /**
   * 第一页列表是否已经落定。
   *
   * 权限提示要等列表真的回来后才有意义：国产 ROM 上「缺权限」的表现是列表只剩本应用，
   * 而 AOSP 上 `supported=false`；**不能**在列表还能读全的时候也拦着用户去授权
   * （荣耀 MagicOS 11 就是 `QUERY_ALL_PACKAGES` 已够用、但这条权限同样存在）。
   */
  const [listed, setListed] = useState(false)
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
  const inputRef = useRef<HTMLInputElement | null>(null)
  bridgeRef.current = bridge
  const generation = useRef(0)
  /** 上一次 effect 见过的词：只在「用户改了输入」时防抖，打开面板与重试都立刻请求。 */
  const lastQuery = useRef<string | null>(null)
  /** 输入法组合输入的安全重置定时器：移动端软键盘选词或失焦时偶尔丢失 compositionend，避免检索被永久阻断。 */
  useEffect(() => {
    if (!isComposing) return
    const timer = window.setTimeout(() => {
      setIsComposing(false)
    }, 800)
    return () => window.clearTimeout(timer)
  }, [isComposing, query])
  /**
   * 每次展开面板先问一次权限状态。
   *
   * 旧原生（或测试里的简化桥）没有这两个方法时按「系统没有这条权限」处理，仍然走原来的
   * 列表请求：这道闸只存在于国产 ROM，不能因为桥落后一版就把整个选择器弄坏。
   */
  useEffect(() => {
    if (!opened) return
    const check = bridgeRef.current.getInstalledApplicationsPermission
    if (typeof check !== 'function') {
      setAccess('unsupported')
      return
    }
    let current = true
    setAccess('checking')
    void check().then(result => {
      if (!current) return
      setAccess(result.supported ? (result.granted ? 'granted' : 'denied') : 'unsupported')
    }).catch(() => {
      if (current) setAccess('unsupported')
    })
    return () => { current = false }
  }, [opened])
  useEffect(() => {
    if (!opened || isComposing) return
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
      }).finally(() => {
        if (ticket !== generation.current) return
        setLoading(false)
        setListed(true)
      })
    }, changed ? SEARCH_DEBOUNCE_MS : 0)
    return () => { window.clearTimeout(timer); generation.current = ticket + 1 }
  }, [opened, query, attempt, isComposing])
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

  /**
   * 申请「读取应用列表」。
   *
   * 系统对话框结束后由原生重新 `checkSelfPermission`，返回值是**真实状态**；这里不猜结果，
   * 也不在拒绝后自动再弹一次——第二次多半已经点不出来，界面改为指向应用设置页。
   */
  async function grantAccess(): Promise<void> {
    const request = bridgeRef.current.requestInstalledApplicationsPermission
    if (typeof request !== 'function') {
      setAccess('unsupported')
      return
    }
    setRequestingAccess(true)
    setAccessRequested(true)
    try {
      const result = await request()
      setAccess(result.supported ? (result.granted ? 'granted' : 'denied') : 'unsupported')
      if (result.granted) {
        setListed(false)
        retry()
      }
    } catch {
      setAccess('denied')
    } finally {
      setRequestingAccess(false)
    }
  }
  function openAppSettings(): void {
    void bridgeRef.current.openApplicationSettings().catch(() => undefined)
  }

  /**
   * 显式搜索：按输入框当前原文立刻查一次，不等防抖，也不依赖输入法一定派发 compositionend。
   *
   * 移动端拼音候选、联想补全有时会漏掉 compositionend，只靠「输入即搜」会让人以为搜索坏了；
   * 按钮直接取 `input.value`，并把 lastQuery 清掉以跳过 200 ms 防抖。
   */
  function searchNow(): void {
    const committed = inputRef.current?.value ?? query
    setIsComposing(false)
    lastQuery.current = null
    setQuery(committed)
    retry()
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
  // 只在「列表真的只剩本应用」时提示缺权限；读得全就说明这台 ROM 不需要这条权限。
  const restricted = access === 'denied' && listed && !failed && total <= 1
  return <div className="application-picker">
    <button type="button" className="button button-secondary" aria-expanded={opened} onClick={() => {
      const next = !opened
      setOpened(next)
      // 重新展开时先回到「检查中」：不能拿上一次的 granted 直接下结论。
      if (next) {
        setAccess('checking')
        setListed(false)
      }
    }} disabled={disabled}>
      <Smartphone size={18} />{t('从已安装应用选择')}
    </button>
    {opened && <div className="application-picker-panel">
      <div className="search-control application-picker-search" role="search" aria-label={t('搜索应用名称或包名')}>
          <input
            ref={inputRef}
            type="search"
            value={query}
            maxLength={160}
            autoComplete="off"
            autoCorrect="off"
            autoCapitalize="off"
            spellCheck={false}
            onCompositionStart={() => setIsComposing(true)}
            onCompositionEnd={event => {
              setIsComposing(false)
              const committed = event.currentTarget?.value ?? (event.target as HTMLInputElement)?.value
              if (typeof committed === 'string') {
                setQuery(committed)
              }
            }}
            onBlur={() => setIsComposing(false)}
            onKeyDown={event => {
              if (event.key === 'Enter') {
                event.preventDefault()
                searchNow()
              }
            }}
            onChange={event => setQuery(event.target.value)}
            placeholder={t('输入应用名称或包名')}
            aria-label={t('搜索应用名称或包名')}
          />
          <button
            type="button"
            className="button button-secondary compact-button"
            onClick={searchNow}
            disabled={disabled}
            aria-label={t('搜索')}
          >
            <Search size={16} />{t('搜索')}
          </button>
          {query !== '' && (
            <button
              type="button"
              className="search-control-clear"
              onClick={() => {
                setQuery('')
                setIsComposing(false)
              }}
              aria-label={t('清空查找词')}
            >
              <X size={16} />
            </button>
          )}
      </div>
      {restricted && <div className="application-picker-permission" role="alert">
        <p>{t('列表里只有本应用。部分国产系统需要「读取应用列表」权限才能读取其他应用。')}</p>
        <p className="settings-note">{t('若系统没有弹出授权框，请到应用设置里手动开启。')}</p>
        <div className="application-picker-permission-actions">
          <button type="button" className="button button-secondary" onClick={() => { void grantAccess() }}
            disabled={requestingAccess || disabled}>
            {requestingAccess ? t('正在申请权限…') : t('授予权限')}
          </button>
          <button type="button" className="button button-quiet" onClick={openAppSettings}>{t('打开应用设置')}</button>
        </div>
        {accessRequested && !requestingAccess && <p role="status">{t('未获得「读取应用列表」权限；请到应用设置里手动开启。')}</p>}
      </div>}
      <p className="settings-note">{t('支持拼音与首字母：微信可搜 wx、weixin；多个词用空格分开，顺序随意。')}</p>
      <div className="application-picker-filters" role="group" aria-label={t('列表筛选')}>
        <label><input type="checkbox" checked={automationOnly} disabled={disabled}
          onChange={event => toggleFilter(setAutomationOnly, event.target.checked)} /><span>{t('只看可自动化应用')}</span></label>
        <label><input type="checkbox" checked={hideSystem} disabled={disabled}
          onChange={event => toggleFilter(setHideSystem, event.target.checked)} /><span>{t('隐藏系统组件')}</span></label>
      </div>
      {/* 条目数量不设上限（原生侧从不限制条数）：只报已选个数，不再给「会被拒绝」的警告。 */}
      <p className="settings-note">{t('已选择 {0} 个应用；勾选后点击保存白名单。', selected.length)}</p>
      {failed && <p role="alert">{t('无法读取应用列表，请在安卓设备上重试。')} <button className="button button-quiet" type="button" onClick={retry}>{t('重试')}</button></p>}
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
