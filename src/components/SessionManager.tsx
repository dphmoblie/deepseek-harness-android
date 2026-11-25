import { useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, ArrowLeft, ChevronDown, ChevronRight, CornerDownRight, Loader2, MessageCircle, Plus, Trash2, X } from 'lucide-react'
import { triggerHaptic } from '../platform/haptics'
import { t, useLanguage } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { RuntimeSessionDeleteReason, RuntimeSessionListReason, RuntimeSessionSummary } from '../platform/types'
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
  /**
   * dsh 的 workspace 注册表把这条会话列入了归档集合。
   * 只有 `true` 会出现在这里：缺失 = 未归档（旧版桥、读不到归档状态时都不能显示成已归档）。
   */
  archived?: boolean
}

interface SessionManagerProps {
  onBack: () => void
  onOpenHarness: (sessionId?: string) => void
  embedded?: boolean
  disabled?: boolean
}

/**
 * 列表筛选：主会话 / 全部 / 已归档。
 *
 * **没有「子代理」这一档**：子代理是会话日志里的一等公民，但它们不占会话入口——只有折叠在父会话
 * 下面才说得清「这是谁派生的」。单独一档平铺出来会让「会话」这个词同时指两种东西（实测一台设备上
 * 27 条会话里有 12 条是子代理），而且把「已归档」挤出了筛选条。归档状态值得单独一档，子代理不值得。
 */
type SessionFilter = 'main' | 'all' | 'archived'

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

/**
 * 删除被拒绝时的说明。
 *
 * 每种拒绝原因都要说清「为什么没删」：用户按了删除却什么都没发生，只看到一句
 * 「删除失败」时唯一能做的就是再按一次。
 */
function deleteFailureHint(reason: RuntimeSessionDeleteReason): string {
  switch (reason) {
    case 'RUNTIME_RUNNING':
      return '运行环境还在运行，先停止后再删除会话。'
    case 'SESSION_NOT_ARCHIVED':
      return '这条会话还没有归档，先归档再删除。'
    case 'SESSION_NOT_FOUND':
      return '磁盘上已经找不到这条会话了，列表稍后会刷新。'
    case 'SESSION_DELETE_FAILED':
      return '删除会话失败，请稍后重试。'
  }
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
  /**
   * 「已归档」**不另开分区**：它只是卡片上的一个标记，跟着现有三档筛选（主会话 / 全部 / 子代理）
   * 一起出现。归档的会话仍留在它原本的位置上——用户按「最近更新」找会话时，位置不该因为归档而变化。
   */
  /** 正在等用户确认删除的会话；同一时刻只允许一条，避免两个确认框叠在一起。 */
  const [pendingDelete, setPendingDelete] = useState<string | null>(null)
  /** 正在删除的会话：删除期间禁用确认按钮，避免重复提交。 */
  const [deletingId, setDeletingId] = useState<string | null>(null)
  /** 上一次删除被拒绝的原因；成功或换一条会话时清空。 */
  const [deleteFailure, setDeleteFailure] = useState<string | null>(null)
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

  /** 当前筛选下的可见条目：父行 + 是否需要展开、以及哪些子代理（或孤儿）要显示。 */
  const view = useMemo(() => {
    const searching = normalizedQuery !== ''
    /** 三档之间的差别只有「取哪些父行」：子代理始终折叠在父行下面，不单独成列。 */
    const parentsOf = (): SessionEntry[] => {
      if (filter === 'main') return grouped.parents.filter(parent => parent.archived !== true)
      if (filter === 'archived') return grouped.parents.filter(parent => parent.archived === true)
      return grouped.parents
    }
    const parents = parentsOf()
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
    // 父会话被 50 条上限截断时会出现「没有父行的子代理」：按它们自己的归档状态分档，不静默隐藏。
    const orphansSource = filter === 'main'
      ? grouped.orphans.filter(orphan => orphan.archived !== true)
      : filter === 'archived'
        ? grouped.orphans.filter(orphan => orphan.archived === true)
        : grouped.orphans
    const orphans = orphansSource.filter(session => matchesQuery(session, normalizedQuery))
    return { parents: visibleParents, childrenOf, orphans }
  }, [filter, grouped, normalizedQuery])

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

  /**
   * 删除一条真实会话（只对已归档的会话开放）。
   *
   * 删成功后**就地**从列表里摘掉这条会话与它的子代理，不整表重读：重读会把已经展开的分组、
   * 搜索词与滚动位置一起冲掉，而用户刚做的只是删掉一行。失败时把原因写在列表上方。
   */
  const confirmDelete = (session: SessionEntry): void => {
    if (deletingId !== null) return
    triggerHaptic('warning')
    setDeletingId(session.id)
    setDeleteFailure(null)
    void runtimeBridge.deleteSession(session.id)
      .then(result => {
        if (result.deleted) {
          setCatalog(current => current.status === 'ready'
            ? {
              ...current,
              sessions: current.sessions.filter(item => item.id !== session.id && item.parentId !== session.id),
            }
            : current)
          setPendingDelete(null)
          triggerHaptic('success')
          return
        }
        setDeleteFailure(deleteFailureHint(result.reason))
      })
      .catch(error => {
        setDeleteFailure(error instanceof Error && error.message !== ''
          ? error.message
          : t('删除会话失败，请稍后重试。'))
      })
      .finally(() => { setDeletingId(null) })
  }

  const visibleCount = view.parents.length + view.orphans.length

  /**
   * 归档状态来自 dsh 自己的 workspace 注册表（见 `RuntimeSessionSummary.archived`）：
   * 外壳既不改写它，也不把「读不到」显示成「已归档」——缺失一律算未归档。
   *
   * 子代理没有自己的归档语义：父会话归档了，它也就跟着显示已归档（子代理不占会话入口，
   * 单独给它一个不同的归档状态只会让同一组会话看起来自相矛盾）。
   */
  const isArchivedRow = (session: SessionEntry): boolean =>
    session.archived === true ||
    (session.parentId !== undefined && grouped.parents.some(parent => parent.id === session.parentId && parent.archived === true))

  /**
   * 「已归档」标记只在**混合**列表里出现：在「已归档」这一档里整屏都是归档会话，
   * 每行再挂一个胶囊只是噪音——这一档的标题本身已经把话说完了。
   */
  const showArchivedBadge = filter !== 'archived'

  /**
   * 一行父会话：打开入口 + 子代理折叠徽标；已归档的行多一个「已归档」标记与删除入口（带就地确认）。
   *
   * 删除只对已归档会话开放：未归档的会话可能仍在用，原生侧也会以 `SESSION_NOT_ARCHIVED`
   * 拒绝，界面不摆一个按了必然失败的按钮。
   */
  const renderParent = (session: SessionEntry) => {
    const children = view.childrenOf(session)
    const open = showsChildren(session)
    const confirming = pendingDelete === session.id
    const archived = isArchivedRow(session)
    const title = session.title === '' ? t('未命名会话') : session.title
    return (
      <div className={archived ? 'session-group session-group--archived' : 'session-group'} key={session.id}>
        <article className={archived ? 'session-card session-card--archived' : 'session-card'} data-session-depth={0}>
          <button className="session-open" type="button" onClick={() => onOpenHarness(session.local ? undefined : session.id)} disabled={disabled}>
            <span className="session-icon"><MessageCircle size={18} /></span>
            <span className="session-copy">
              {/* 读不到标题的会话保留在列表里，但显示「未命名会话」——不拿 id 或时间冒充标题。 */}
              <strong>{title}</strong>
              <small className="session-meta">
                {archived && showArchivedBadge && <span className="session-archived-pill">{t('已归档')}</span>}
                {/* 归档行省掉「最近更新」四个字：363px 下这一行还要和子代理徽标、删除按钮抢宽度，
                    省下的四个字正好够放下时间；未归档行保持原文案不变。 */}
                <span className="session-meta-text">{session.local === true
                  ? t('由 Harness 管理会话内容')
                  : archived && showArchivedBadge
                    ? formatUpdatedAt(session.updatedAt ?? 0)
                    : t('最近更新 {0}', formatUpdatedAt(session.updatedAt ?? 0))}</span>
              </small>
            </span>
          </button>
          {/* 真实会话只有「已归档」这一档给删除按钮：删除是不可逆的，未归档的会话还在用。 */}
          {archived && !confirming && (
            <button
              className="icon-button session-delete"
              type="button"
              aria-label={t('删除会话 {0}', title)}
              title={t('删除会话')}
              disabled={disabled || deletingId !== null}
              onClick={() => { setPendingDelete(session.id); setDeleteFailure(null) }}
            >
              <Trash2 size={17} />
            </button>
          )}
          {/* 本地占位条目从来不在磁盘上，只有它能在这里直接移除。 */}
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
              {/* 窄屏只留数字（见 CSS）：363px 下这一格还要和标题、删除按钮抢宽度，
                  完整文案由 aria-label 承担，读屏与长文案场景都不丢信息。 */}
              <span className="session-badge-text">{t('{0} 个子代理', children.length)}</span>
              <span className="session-badge-count" aria-hidden="true">{children.length}</span>
            </button>
          )}
        </article>
        {confirming && (
          <div className="session-delete-confirm" role="alert">
            <span>{t('删除后无法恢复：会话日志与投影缓存都会被删掉。')}</span>
            <span className="session-delete-actions">
              <button className="button button-secondary compact-button" type="button" disabled={deletingId !== null} onClick={() => setPendingDelete(null)}>
                {t('取消')}
              </button>
              <button className="button button-danger compact-button" type="button" disabled={deletingId !== null} onClick={() => confirmDelete(session)}>
                {deletingId === session.id ? <Loader2 className="spin" size={14} /> : <Trash2 size={14} />}
                {t('删除')}
              </button>
            </span>
          </div>
        )}
        {open && (
          <div className="session-subagents">
            {children.map(child => (
              <SubagentCard
                key={child.id}
                archived={showArchivedBadge && isArchivedRow(child)}
                session={child}
                parentTitle={session.title === '' ? undefined : session.title}
                disabled={disabled}
                onOpenHarness={onOpenHarness}
                compact
              />
            ))}
          </div>
        )}
      </div>
    )
  }

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
          ['archived', t('已归档')],
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

      {deleteFailure !== null && (
        <div className="inline-alert warning session-delete-failure" role="alert">
          <AlertTriangle size={17} />
          <div>
            <strong>{t('没有删除这条会话')}</strong>
            <span>{t(deleteFailure)}</span>
          </div>
          <button className="icon-button" type="button" aria-label={t('关闭提示')} onClick={() => setDeleteFailure(null)}>
            <X size={15} />
          </button>
        </div>
      )}

      <section className="session-list" aria-label={t('会话列表')}>
        {catalog.status === 'loading' ? (
          <div className="session-empty">
            <EmptyMark />
            <strong>{t('正在读取会话…')}</strong>
            <p>{t('只读取标题与更新时间，不会读取会话正文。')}</p>
          </div>
        ) : catalog.status === 'unavailable' || (catalog.truncated && visibleCount === 0) ? (
          <div className="session-empty">
            <EmptyMark />
            <strong>{t(catalog.status === 'unavailable' ? '读不到会话列表' : '会话列表读取不完整')}</strong>
            {catalog.status === 'unavailable' && <p>{t(unavailableHint(catalog.reason))}</p>}
            <button className="button button-secondary compact-button" type="button" onClick={retrySessions} disabled={disabled || retrying}>
              {retrying && <Loader2 className="spin" size={16} />}{t(retrying ? '正在读取会话…' : '重试')}
            </button>
          </div>
        ) : visibleCount === 0 ? (
          <div className="session-empty">
            <EmptyMark />
            <strong>{t(sessions.length === 0
              ? '暂无会话'
              : normalizedQuery !== ''
                ? '没有匹配的会话'
                : filter === 'archived'
                  ? '没有已归档的会话'
                  : '没有未归档的会话')}</strong>
            <p>{t(sessions.length === 0
              ? '点击「新建会话」开始工作。'
              : normalizedQuery !== ''
                ? '换一个关键词，或新建会话开始工作。'
                : filter === 'archived'
                  ? '在 Harness 里归档的会话会出现在这里；归档后可以删除。'
                  : '已归档的会话在「已归档」里。')}</p>
          </div>
        ) : (
          <>
            {/* 子代理不单独成列：它们始终折叠在父会话下面，这里只渲染父行（以及没有父行的孤儿）。 */}
            {view.parents.map(session => renderParent(session))}
            {view.orphans.length > 0 && (
              <div className="session-group session-orphans">
                <h3 className="session-group-title">{t('未关联子代理')}</h3>
                {view.orphans.map(session => (
                  <SubagentCard
                    key={session.id}
                    archived={showArchivedBadge && isArchivedRow(session)}
                    session={session}
                    parentTitle={grouped.parentTitle(session.parentId)}
                    disabled={disabled}
                    onOpenHarness={onOpenHarness}
                  />
                ))}
              </div>
            )}
          </>
        )}
      </section>

      {catalog.status === 'ready' && catalog.truncated && visibleCount > 0 && (
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
  /** 已归档：父会话归档时子代理跟着标记，或它自己被注册表列进归档集合。 */
  archived?: boolean
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
function SubagentCard({ session, parentTitle, archived = false, disabled, onOpenHarness, compact = false }: SubagentCardProps) {
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
          <small className="session-meta">
            {archived && <span className="session-archived-pill">{t('已归档')}</span>}
            <span className="session-meta-text">{parent === '' ? subtitle : `${subtitle} · ${parent}`}</span>
          </small>
        </span>
      </button>
    </article>
  )
}
