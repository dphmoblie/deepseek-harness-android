import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, ChevronDown, ChevronRight, CornerDownRight, Loader2, MessageCircle, Plus, Trash2, X } from 'lucide-react'
import { triggerHaptic } from '../platform/haptics'
import { t, useLanguage } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { RuntimeSessionListReason, RuntimeSessionSummary } from '../platform/types'
// 走 Vite 的资源 URL 导入（与 App.tsx 里 app-mark 的用法一致，见 src/assets.d.ts 的 *.png 声明）：
// 打包后是一份带哈希的静态文件，不会像放 public/ 那样多一处需要手工同步的路径。
import appMark from '../assets/app-mark.png'
import './SessionManager.css'

interface SessionEntry {
  id: string
  title: string
  /**
   * 真实会话的最近更新时间；`undefined` 表示这是**本地新建的占位条目**
   * （它还没落到运行时的会话目录里，所以既没有时间，也不能在磁盘上删除）。
   */
  updatedAt?: number
  local?: true
  /** 委派深度：0 / 缺省 = 主会话；>0 = 子代理会话（见 `RuntimeSessionSummary`）。 */
  depth?: number
  /** 父会话标识：仅子代理有；缺失表示这条子代理连父都指不出来。 */
  parentId?: string
  /** 子代理的预设名（`agentPreset`），用于显示与搜索。 */
  agentName?: string
}

interface SessionManagerProps {
  onBack: () => void
  onOpenHarness: (sessionId?: string) => void
  embedded?: boolean
  disabled?: boolean
}

/**
 * 列表筛选：主会话 / 全部 / 子代理。
 *
 * **默认只看主会话**：子代理是会话日志里的一等公民，但列表里把它们与真实对话平铺在一起，
 * 会让「会话」这个词同时指两种东西——实测一台设备上 27 条会话里有 12 条是子代理（44%），
 * 50 条上限还会把主对话挤出去。子代理折叠进父行、按需展开，才是它们与用户的真实关系。
 */
type SessionFilter = 'main' | 'all' | 'subagents'

/**
 * 会话列表的读取状态。
 *
 * `loading` 与 `unavailable` 都必须与「真的没有会话」分开：冷读取要解 zstd，上百条会话首屏本来
 * 就慢，把「还没加载完」显示成「暂无会话」是把两件不同的事混成一句谎话。
 */
type SessionCatalogState =
  | { status: 'loading' }
  | { status: 'ready'; sessions: RuntimeSessionSummary[]; truncated: boolean }
  | { status: 'unavailable'; reason: RuntimeSessionListReason }

/**
 * 读不到会话列表时的说明。
 *
 * 三种原因都**不是**「没有会话」，所以每句都要把这层意思说出来——我们并不知道有没有会话，
 * 只知道这次没读到。
 */
function unavailableHint(reason: RuntimeSessionListReason): string {
  switch (reason) {
    case 'RUNTIME_NOT_INSTALLED':
      return '运行环境还没安装，读不到会话列表；这不代表没有会话。'
    case 'SESSION_CATALOG_TIMEOUT':
      return '读取会话列表超时；这不代表没有会话。'
    case 'SESSION_CATALOG_FAILED':
      return '暂时读不到会话列表；这不代表没有会话。'
  }
}

/** 时间格式沿用仓库既有写法（`App.tsx` 里的日志时间也是 `toLocaleString()`）。 */
function formatUpdatedAt(millis: number): string {
  return new Date(millis).toLocaleString()
}

/** 是不是子代理会话：深度缺失（旧版桥）一律按主会话。 */
function isSubagent(session: SessionEntry): boolean {
  return (session.depth ?? 0) > 0
}

/**
 * 搜索命中：标题按大小写无关的子串匹配；子代理额外的 `agentName` 也参与匹配
 * （用户记得的往往是「那个 cordis 子代理」，不是它的标题）。
 */
function matchesQuery(session: SessionEntry, normalized: string): boolean {
  if (normalized === '') return true
  return session.title.toLocaleLowerCase().includes(normalized) ||
    (session.agentName ?? '').toLocaleLowerCase().includes(normalized)
}

/**
 * 空态 / 加载态 / 读不到态共用的装饰徽标。
 *
 * 原来这里只有一个 24px 的线性图标，落在 180px+ 高的虚线框里显得整个框「一片空白」。
 * 改用仓库自带的满幅徽标，并把它放大到框宽的约 48%（见 .session-empty-mark）；
 * 图是纯装饰，文案负责语义，所以 alt 留空、不给它加 role。
 */
function EmptyMark() {
  return <img className="session-empty-mark" src={appMark} alt="" width={96} height={96} />
}

/** 一条会话（主会话或子代理）的标题文案；子代理没标题时用预设名兜底，不写成「未命名会话」。 */
function sessionTitleOf(session: SessionEntry): string {
  if (session.title !== '') return session.title
  if (!isSubagent(session)) return t('未命名会话')
  return session.agentName === undefined ? t('子代理') : t('子代理 · {0}', session.agentName)
}

/**
 * 外壳会话管理页。
 *
 * 安全边界（比原先的措辞更严，因此随之更新）：外壳**只**取会话元数据——会话标识、标题、
 * 最近更新时间，以及**只用于分组**的委派深度 / 父标识 / 子代理预设名，全部通过原生桥接的
 * 只读端点拿；会话正文、消息片段与搜索 snippet 一律不读取、不复制，也**不写入任何浏览器存储**
 * （localStorage/sessionStorage/IndexedDB 都不写）。
 *
 * 标题要单独说明：它是宿主从会话日志里折出来的文本（没有标题事件时来自首条用户消息的开头），
 * 因此只**在内存里渲染**——不缓存、不持久化、不发给任何人。会话本身仍由 Harness 运行时管理，
 * 这里只提供入口、筛选与本地导航状态。
 */
export function SessionManager({ onBack, onOpenHarness, embedded = false, disabled = false }: SessionManagerProps) {
  useLanguage()
  // 本地新建的占位条目：仍然是「仅本地」的行为（新建后直接进 Harness，会话由运行时创建）。
  const [localSessions, setLocalSessions] = useState<SessionEntry[]>([])
  const [catalog, setCatalog] = useState<SessionCatalogState>({ status: 'loading' })
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<SessionFilter>('main')
  /** 已展开的父会话 id：默认全部折叠，展开状态只活在这一屏。 */
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set())
  const [reloadToken, setReloadToken] = useState(0)
  const [retrying, setRetrying] = useState(false)
  const loadingRef = useRef(false)

  /**
   * 挂载时读一次真实会话元数据。
   *
   * 失败一律落到 `unavailable`（并带受控原因），不静默成空列表；卸载后不再写状态，
   * 避免开发模式下 StrictMode 的双次挂载把旧结果盖上来。
   */
  useEffect(() => {
    let active = true
    loadingRef.current = true
    Promise.resolve()
      .then(() => runtimeBridge.listSessions())
      .then(result => {
        if (!active) return
        setCatalog(result.status === 'ready'
          ? { status: 'ready', sessions: [...result.sessions], truncated: result.truncated }
          : { status: 'unavailable', reason: result.reason })
      })
      .catch(() => {
        // 桥校验失败（载荷形状不符）也走这里：对用户而言同样是「这次没读到」。
        if (active) setCatalog({ status: 'unavailable', reason: 'SESSION_CATALOG_FAILED' })
      })
      .finally(() => {
        if (active) {
          loadingRef.current = false
          setRetrying(false)
        }
      })
    return () => { active = false }
  }, [reloadToken])

  const retrySessions = (): void => {
    // Ref 立即锁住请求，避免连续点击在下一次渲染前排队多个桥调用。
    if (disabled || loadingRef.current) return
    loadingRef.current = true
    setRetrying(true)
    setReloadToken(current => current + 1)
  }

  const sessions = useMemo<SessionEntry[]>(() => [
    ...localSessions,
    ...(catalog.status === 'ready' ? catalog.sessions : []),
  ], [catalog, localSessions])

  /** 主会话与子代理分开：界面上的「会话」只指主会话，子代理挂在父会话下面。 */
  const grouped = useMemo(() => {
    const parents = sessions.filter(session => !isSubagent(session))
    const knownParents = new Set(parents.map(session => session.id))
    const childrenByParent = new Map<string, SessionEntry[]>()
    const orphans: SessionEntry[] = []
    for (const session of sessions) {
      if (!isSubagent(session)) continue
      if (session.parentId !== undefined && knownParents.has(session.parentId)) {
        const list = childrenByParent.get(session.parentId)
        if (list === undefined) childrenByParent.set(session.parentId, [session])
        else list.push(session)
      } else {
        // 父会话被 50 条上限截断、或父标识不合法：放进「未关联子代理」，**不静默隐藏**。
        orphans.push(session)
      }
    }
    return {
      parents,
      children: (id: string): SessionEntry[] => childrenByParent.get(id) ?? [],
      orphans,
      parentTitle: (id: string | undefined): string | undefined =>
        id === undefined ? undefined : parents.find(session => session.id === id)?.title,
    }
  }, [sessions])

  const subagentCount = useMemo(
    () => sessions.filter(session => isSubagent(session)).length,
    [sessions],
  )

  const normalizedQuery = query.trim().toLocaleLowerCase()

  /** 当前筛选下的可见条目：父行 + 是否需要展开、以及哪些子代理要显示。 */
  const view = useMemo(() => {
    const searching = normalizedQuery !== ''
    if (filter === 'subagents') {
      const rows = sessions.filter(session => isSubagent(session) && matchesQuery(session, normalizedQuery))
      return { parents: [] as SessionEntry[], childrenOf: (): SessionEntry[] => [], orphans: [] as SessionEntry[], rows }
    }
    const parents = grouped.parents
    const visibleParents = parents.filter(parent => {
      if (!searching) return true
      // 命中父标题 → 它的子代理一起带出来；只命中子代理 → 父行也要在，否则搜到了却看不到。
      return matchesQuery(parent, normalizedQuery) || grouped.children(parent.id).some(child => matchesQuery(child, normalizedQuery))
    })
    const childrenOf = (parent: SessionEntry): SessionEntry[] => {
      const children = grouped.children(parent.id)
      // 搜索时：父标题命中的保留全部子代理，否则只留命中的那几个。
      const kept = searching && !matchesQuery(parent, normalizedQuery)
        ? children.filter(child => matchesQuery(child, normalizedQuery))
        : children
      return kept
    }
    const orphans = grouped.orphans.filter(session => matchesQuery(session, normalizedQuery))
    return { parents: visibleParents, childrenOf, orphans, rows: [] as SessionEntry[] }
  }, [filter, grouped, normalizedQuery, sessions])

  /** 一行（主会话）是否展开：搜索命中的子代理必须可见，不能靠用户再点一次。 */
  const showsChildren = (parent: SessionEntry): boolean => {
    const children = view.childrenOf(parent)
    if (children.length === 0) return false
    // 搜索时父行既然在结果里，它的子代理就一起带出来（父标题命中时本该如此）。
    if (normalizedQuery !== '') return true
    if (filter === 'all') return true
    return expanded.has(parent.id)
  }

  const toggleExpanded = (id: string): void => {
    triggerHaptic('light')
    setExpanded(current => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const createSession = (): void => {
    triggerHaptic('medium')
    const id = `local-${Date.now().toString(36)}`
    const number = sessions.length + 1
    setLocalSessions(current => [{ id, title: t('新会话 {0}', number), local: true }, ...current])
    onOpenHarness()
  }

  const removeSession = (id: string): void => {
    triggerHaptic('warning')
    // 只从**本地占位条目**里移除：真实会话不在这条通道的删除范围内，界面也不会假装删掉了。
    setLocalSessions(current => current.filter(session => session.id !== id))
  }

  const visibleCount = filter === 'subagents' ? view.rows.length : view.parents.length + view.orphans.length

  return (
    <div className={embedded ? 'session-manager-embedded' : 'screen session-screen'}>
      {!embedded && <div className="screen-heading management-heading session-heading">
        <div>
          <p className="eyebrow">{t('会话工作区')}</p>
          <h1>{t('会话管理')}</h1>
        </div>
        <div className="heading-actions">
          <button className="button button-primary compact-button" type="button" onClick={createSession} disabled={disabled}>
            <Plus size={17} />{t('新建会话')}
          </button>
          <button className="icon-button" type="button" title={t('返回对话')} aria-label={t('返回对话')} onClick={onBack}>
            <ArrowLeft size={19} />
          </button>
        </div>
      </div>}

      {embedded && <div className="session-embedded-heading">
        <h2>{t('会话工作区')}</h2>
        <button className="button button-secondary compact-button" type="button" onClick={createSession} disabled={disabled}>
          <Plus size={17} />{t('新建会话')}
        </button>
      </div>}

      <div className="session-toolbar">
        <div className="search-control session-search" role="search" aria-label={t('搜索会话')}>
          <span className="sr-only">{t('搜索会话')}</span>
          <input
            type="search"
            value={query}
            onChange={event => setQuery(event.target.value.slice(0, 80))}
            onCompositionEnd={event => {
              const val = event.currentTarget?.value ?? (event.target as HTMLInputElement)?.value ?? ''
              setQuery(val.slice(0, 80))
            }}
            placeholder={t('搜索会话')}
            aria-label={t('搜索会话')}
            maxLength={80}
            autoComplete="off"
          />
          {query !== '' && (
            <button
              type="button"
              className="search-control-clear"
              onClick={() => setQuery('')}
              aria-label={t('清空查找词')}
            >
              <X size={15} />
            </button>
          )}
        </div>
        <span className="session-count">
          {subagentCount === 0
            ? t('{0} 个会话', grouped.parents.length)
            : t('{0} 个会话 · {1} 个子代理', grouped.parents.length, subagentCount)}
        </span>
      </div>

      <div className="segmented session-filter" role="tablist" aria-label={t('会话类型')}>
        {([
          ['main', t('主会话')],
          ['all', t('全部')],
          ['subagents', t('子代理')],
        ] as const).map(([value, label]) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={filter === value}
            className={filter === value ? 'active' : ''}
            onClick={() => { triggerHaptic('light'); setFilter(value) }}
          >
            {label}
          </button>
        ))}
      </div>

      <section className="session-list" aria-label={t('会话列表')}>
        {catalog.status === 'loading' ? (
          <div className="session-empty">
            <EmptyMark />
            <strong>{t('正在读取会话…')}</strong>
            <p>{t('只读取标题与更新时间，不会读取会话正文。')}</p>
          </div>
        ) : catalog.status === 'unavailable' ? (
          <div className="session-empty">
            <EmptyMark />
            <strong>{t('读不到会话列表')}</strong>
            <p>{t(unavailableHint(catalog.reason))}</p>
            <button className="button button-secondary compact-button" type="button" onClick={retrySessions} disabled={disabled || retrying}>
              {retrying && <Loader2 className="spin" size={16} />}{t(retrying ? '正在读取会话…' : '重试')}
            </button>
          </div>
        ) : visibleCount === 0 ? (
          <div className="session-empty">
            <EmptyMark />
            <strong>{t(sessions.length === 0
              ? '暂无会话'
              : filter === 'subagents' && normalizedQuery === '' ? '没有子代理会话' : '没有匹配的会话')}</strong>
            <p>{t(sessions.length === 0 ? '点击「新建会话」开始工作。' : '换一个关键词，或新建会话开始工作。')}</p>
          </div>
        ) : filter === 'subagents' ? (
          view.rows.map(session => (
            <SubagentCard key={session.id} session={session} parentTitle={grouped.parentTitle(session.parentId)} disabled={disabled} onOpenHarness={onOpenHarness} />
          ))
        ) : (
          <>
            {view.parents.map(session => {
              const children = view.childrenOf(session)
              const open = showsChildren(session)
              return (
                <div className="session-group" key={session.id}>
                  <article className="session-card" data-session-depth={0}>
                    <button className="session-open" type="button" onClick={() => onOpenHarness(session.local ? undefined : session.id)} disabled={disabled}>
                      <span className="session-icon"><MessageCircle size={18} /></span>
                      <span className="session-copy">
                        {/* 读不到标题的会话保留在列表里，但显示「未命名会话」——不拿 id 或时间冒充标题。 */}
                        <strong>{session.title === '' ? t('未命名会话') : session.title}</strong>
                        <small>{session.local === true
                          ? t('由 Harness 管理会话内容')
                          : t('最近更新 {0}', formatUpdatedAt(session.updatedAt ?? 0))}</small>
                      </span>
                    </button>
                    {/* 只有本地占位条目能在这里移除：真实会话的删除不在这条只读通道里，
                        按钮一旦出现就等于承诺一件做不到的事。 */}
                    {session.local === true && (
                      <button className="icon-button session-delete" type="button" aria-label={t('删除会话 {0}', session.title)} title={t('删除会话')} onClick={() => removeSession(session.id)}>
                        <Trash2 size={17} />
                      </button>
                    )}
                    {children.length > 0 && (
                      <button
                        className="session-badge"
                        type="button"
                        aria-expanded={open}
                        aria-label={t(open ? '收起 {0} 个子代理' : '展开 {0} 个子代理', children.length)}
                        onClick={() => toggleExpanded(session.id)}
                      >
                        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                        {t('{0} 个子代理', children.length)}
                      </button>
                    )}
                  </article>
                  {open && (
                    <div className="session-subagents">
                      {children.map(child => (
                        <SubagentCard key={child.id} session={child} parentTitle={session.title === '' ? undefined : session.title} disabled={disabled} onOpenHarness={onOpenHarness} compact />
                      ))}
                    </div>
                  )}
                </div>
              )
            })}
            {view.orphans.length > 0 && (
              <div className="session-group session-orphans">
                <h3 className="session-group-title">{t('未关联子代理')}</h3>
                {view.orphans.map(session => (
                  <SubagentCard key={session.id} session={session} parentTitle={grouped.parentTitle(session.parentId)} disabled={disabled} onOpenHarness={onOpenHarness} />
                ))}
              </div>
            )}
          </>
        )}
      </section>

      {catalog.status === 'ready' && catalog.truncated && (
        <p className="session-note">{t('会话较多，这里只列出最近更新的一部分。')}</p>
      )}

      <p className="session-note">{t('子代理会话是 Harness 在后台派生的，不占会话入口；它们折叠在所属会话下面。')}</p>
      <p className="session-note">{t('会话正文由 Harness 运行时管理，外壳不会复制或上传。')}</p>
    </div>
  )
}

/** 父行自己的标题：子代理卡片显示「属于谁」。 */

interface SubagentCardProps {
  session: SessionEntry
  /** 所属会话的标题：子代理自己通常没有标题，靠它说明「这是谁派生的」。 */
  parentTitle: string | undefined
  disabled: boolean
  onOpenHarness: (sessionId?: string) => void
  compact?: boolean
}

/**
 * 一条子代理。
 *
 * 点击打开的是**父会话**（子代理不能当成普通会话独立打开，那样只会得到空白页）；
 * 连父标识都没有时按钮禁用，并在说明里写清楚——不做假的成功。
 */
function SubagentCard({ session, parentTitle, disabled, onOpenHarness, compact = false }: SubagentCardProps) {
  const openable = session.parentId !== undefined
  const subtitle = t('最近更新 {0}', formatUpdatedAt(session.updatedAt ?? 0))
  const parent = parentTitle === undefined ? '' : t('属于「{0}」', parentTitle)
  return (
    <article className={compact ? 'session-card session-card-subagent compact' : 'session-card session-card-subagent'} data-session-depth={session.depth ?? 1}>
      <button
        className="session-open"
        type="button"
        disabled={disabled || !openable}
        aria-label={openable ? t('打开所属会话：{0}', sessionTitleOf(session)) : t('未关联子代理：{0}', sessionTitleOf(session))}
        onClick={() => { if (openable) onOpenHarness(session.parentId) }}
      >
        <span className="session-icon"><CornerDownRight size={16} /></span>
        <span className="session-copy">
          <strong>{sessionTitleOf(session)}</strong>
          <small>{parent === '' ? subtitle : `${subtitle} · ${parent}`}</small>
        </span>
      </button>
    </article>
  )
}
