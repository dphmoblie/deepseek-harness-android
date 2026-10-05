import { useEffect, useMemo, useState } from 'react'
import { ArrowLeft, MessageCircle, Plus, Search, Trash2 } from 'lucide-react'
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
}

interface SessionManagerProps {
  onBack: () => void
  onOpenHarness: () => void
  embedded?: boolean
  disabled?: boolean
}

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
 * 空态 / 加载态 / 读不到态共用的装饰徽标。
 *
 * 原来这里只有一个 24px 的线性图标，落在 180px+ 高的虚线框里显得整个框「一片空白」。
 * 改用仓库自带的满幅徽标，并把它放大到框宽的约 48%（见 .session-empty-mark）；
 * 图是纯装饰，文案负责语义，所以 alt 留空、不给它加 role。
 */
function EmptyMark() {
  return <img className="session-empty-mark" src={appMark} alt="" width={96} height={96} />
}

/**
 * 外壳会话管理页。
 *
 * 安全边界（比原先的措辞更严，因此随之更新）：外壳**只**取会话元数据——会话标识、标题与最近
 * 更新时间，全部通过原生桥接的只读端点拿；会话正文、消息片段与搜索 snippet 一律不读取、不复制，
 * 也**不写入任何浏览器存储**（localStorage/sessionStorage/IndexedDB 都不写）。
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

  /**
   * 挂载时读一次真实会话元数据。
   *
   * 失败一律落到 `unavailable`（并带受控原因），不静默成空列表；卸载后不再写状态，
   * 避免开发模式下 StrictMode 的双次挂载把旧结果盖上来。
   */
  useEffect(() => {
    let active = true
    runtimeBridge.listSessions()
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
    return () => { active = false }
  }, [])

  const sessions = useMemo<SessionEntry[]>(() => [
    ...localSessions,
    ...(catalog.status === 'ready' ? catalog.sessions : []),
  ], [catalog, localSessions])

  const filtered = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase()
    if (normalized === '') return sessions
    return sessions.filter(session => session.title.toLocaleLowerCase().includes(normalized))
  }, [query, sessions])

  const createSession = (): void => {
    const id = `local-${Date.now().toString(36)}`
    const number = sessions.length + 1
    setLocalSessions(current => [{ id, title: t('新会话 {0}', number), local: true }, ...current])
    onOpenHarness()
  }

  const removeSession = (id: string): void => {
    // 只从**本地占位条目**里移除：真实会话不在这条通道的删除范围内，界面也不会假装删掉了。
    setLocalSessions(current => current.filter(session => session.id !== id))
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
        <label className="session-search">
          <Search size={17} aria-hidden="true" />
          <span className="sr-only">{t('搜索会话')}</span>
          <input value={query} onChange={event => setQuery(event.target.value.slice(0, 80))} placeholder={t('搜索会话')} maxLength={80} />
        </label>
        <span className="session-count">{t('{0} 个会话', sessions.length)}</span>
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
          </div>
        ) : filtered.length === 0 ? (
          <div className="session-empty">
            <EmptyMark />
            <strong>{t(sessions.length === 0 ? '暂无会话' : '没有匹配的会话')}</strong>
            <p>{t(sessions.length === 0 ? '点击「新建会话」开始工作。' : '换一个关键词，或新建会话开始工作。')}</p>
          </div>
        ) : filtered.map(session => (
          <article className="session-card" key={session.id}>
            <button className="session-open" type="button" onClick={onOpenHarness} disabled={disabled}>
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
          </article>
        ))}
      </section>

      {catalog.status === 'ready' && catalog.truncated && (
        <p className="session-note">{t('会话较多，这里只列出最近更新的一部分。')}</p>
      )}

      <p className="session-note">{t('会话正文由 Harness 运行时管理，外壳不会复制或上传。')}</p>
    </div>
  )
}
