import {
  Archive,
  Bot,
  CheckSquare2,
  FolderTree,
  LoaderCircle,
  MessageSquarePlus,
  MoreVertical,
  Pencil,
  Search,
  Settings,
  X,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from './api/wire'
import type { AgentPresetEntry, SessionCreateRequest, SessionId, SessionSummary, WorkspaceView } from './api/types'
import { eventBus } from './state/events'
import { rpcErrorMessage } from './state/rpcError'
import { selectRecentActiveSession, sessionDisplayTitle } from './state/sessionDisplay'
import { useSessions } from './state/sessions'
import { ChatView } from './ui/ChatView'
import { FilesView } from './ui/FilesView'
import { SettingsView } from './ui/SettingsView'
import { TasksView } from './ui/TasksView'

type SecondaryPage = 'tasks' | 'files' | 'settings'

const LAST_SESSION_KEY = 'dsh-mobile-last-session-v1'
const MAX_SESSION_TITLE_BYTES = 80
const MAX_SESSION_TITLE_CHARACTERS = 80
const MAX_SEARCH_LENGTH = 200

export function App(): ReactElement {
  const sessions = useSessions()
  const [sessionId, setSessionId] = useState<SessionId | null>(null)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [secondaryPage, setSecondaryPage] = useState<SecondaryPage | null>(null)
  const [newSessionOpen, setNewSessionOpen] = useState(false)
  const isDesktop = useDesktopLayout()
  const initialized = useRef(false)

  useEffect(() => {
    eventBus.start()
    return () => eventBus.stop()
  }, [])

  useEffect(() => {
    if (sessions.loading || initialized.current) return
    if (sessions.error !== null && sessions.items.length === 0) return
    initialized.current = true
    const archived = new Set(sessions.archived)
    const remembered = readLastSession()
    const rememberedAvailable = remembered !== null && sessions.items.some(item => item.sessionId === remembered && !archived.has(item.sessionId))
    const restored = rememberedAvailable ? remembered : selectRecentActiveSession(sessions.items, sessions.archived)
    if (restored !== null) {
      setSessionId(restored)
      return
    }
    void sessions.createSession().then(created => {
      if (created !== null) setSessionId(created)
      else initialized.current = false
    })
  }, [sessions])

  useEffect(() => {
    if (sessionId !== null) storeLastSession(sessionId)
  }, [sessionId])

  useEffect(() => {
    if (!isDesktop && initialized.current && sessions.error !== null) setDrawerOpen(true)
  }, [isDesktop, sessions.error])

  const currentSummary = useMemo(
    () => sessions.items.find(item => item.sessionId === sessionId),
    [sessionId, sessions.items],
  )
  const currentWorkspace = sessions.workspaces.find(workspace => sessionId !== null && workspace.sessionIds.includes(sessionId))

  const openSession = (nextSessionId: SessionId): void => {
    setSessionId(nextSessionId)
    setSecondaryPage(null)
    setDrawerOpen(false)
  }

  const createSession = async (options: Omit<SessionCreateRequest, 'sessionId'> = {}): Promise<string | null> => {
    const result = await sessions.createSessionResult(options)
    if (result.sessionId !== null) openSession(result.sessionId)
    return result.error
  }

  const archiveSession = async (targetSessionId: SessionId): Promise<void> => {
    if (!await sessions.archiveSession(targetSessionId)) return
    if (sessionId !== targetSessionId) return
    const replacement = selectRecentActiveSession(
      sessions.items.filter(item => item.sessionId !== targetSessionId),
      [...sessions.archived, targetSessionId],
    )
    if (replacement !== null) {
      openSession(replacement)
      return
    }
    setSessionId(null)
    setDrawerOpen(false)
    const created = await sessions.createSession()
    if (created !== null) openSession(created)
  }

  const openPage = (page: SecondaryPage): void => {
    setSecondaryPage(page)
    setDrawerOpen(false)
  }

  let content: ReactElement
  if (secondaryPage === 'tasks') content = <TasksView onBack={() => setSecondaryPage(null)} />
  else if (secondaryPage === 'files') content = <FilesView onBack={() => setSecondaryPage(null)} />
  else if (secondaryPage === 'settings') content = (
    <SettingsView currentSessionId={sessionId} onBack={() => setSecondaryPage(null)} />
  )
  else if (sessionId === null) {
    content = (
      <div className="conversation-loading">
        <span className="loading-mark"><LoaderCircle className="spin" size={24} aria-hidden="true" /></span>
        <strong>{sessions.error === null ? '正在准备会话' : '无法载入会话'}</strong>
        {sessions.error !== null && (
          <><p>{sessions.error}</p><button type="button" className="btn btn-primary" onClick={() => void sessions.reload()}>重试</button></>
        )}
      </div>
    )
  } else {
    content = (
      <ChatView
        sessionId={sessionId}
        fallbackTitle={currentSummary === undefined ? undefined : sessionDisplayTitle(currentSummary)}
        agentPreset={currentSummary?.agentPreset}
        onOpenMenu={() => setDrawerOpen(true)}
        onOpenSession={openSession}
      />
    )
  }

  return (
    <main className="app harness-shell">
      {!isDesktop && drawerOpen && <button type="button" className="drawer-screen" aria-label="关闭会话菜单" onClick={() => setDrawerOpen(false)} />}
      <NavigationSidebar
        activeSessionId={sessionId}
        archived={sessions.archived}
        error={sessions.error}
        isDesktop={isDesktop}
        open={isDesktop || drawerOpen}
        sessions={sessions.items}
        workspaces={sessions.workspaces}
        onArchiveSession={archiveSession}
        onClose={() => setDrawerOpen(false)}
        onCreate={() => {
          sessions.dismissError()
          setNewSessionOpen(true)
        }}
        onDismissError={sessions.dismissError}
        onOpenSession={openSession}
        onOpenPage={openPage}
        onRenameSession={(targetSessionId, title) => sessions.renameSession(targetSessionId, title)}
      />
      <section className="shell-center">{content}</section>
      {newSessionOpen && (
        <NewSessionDialog
          currentWorkspaceId={currentWorkspace?.workspaceId}
          workspaces={sessions.workspaces}
          onClose={() => setNewSessionOpen(false)}
          onCreate={async options => {
            const failure = await createSession(options)
            if (failure === null) setNewSessionOpen(false)
            return failure
          }}
        />
      )}
    </main>
  )
}

function NavigationSidebar(props: {
  activeSessionId: SessionId | null
  archived: SessionId[]
  error: string | null
  isDesktop: boolean
  open: boolean
  sessions: SessionSummary[]
  workspaces: WorkspaceView[]
  onArchiveSession: (sessionId: SessionId) => Promise<void>
  onClose: () => void
  onCreate: () => void
  onDismissError: () => void
  onOpenSession: (sessionId: SessionId) => void
  onOpenPage: (page: SecondaryPage) => void
  onRenameSession: (sessionId: SessionId, title: string) => Promise<void>
}): ReactElement {
  const { activeSessionId, archived, error, isDesktop, open, sessions, workspaces } = props
  const [menuFor, setMenuFor] = useState<SessionSummary | null>(null)
  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [results, setResults] = useState<{ sessionId: SessionId; snippet: string }[] | null>(null)
  const archivedSet = new Set(archived)
  const activeSessions = sessions.filter(item => !archivedSet.has(item.sessionId) && item.origin !== 'subagent')
  const archivedSessions = sessions.filter(item => archivedSet.has(item.sessionId) && item.origin !== 'subagent')
  const claimed = new Set(workspaces.flatMap(workspace => workspace.sessionIds))
  const looseSessions = activeSessions.filter(item => !claimed.has(item.sessionId))

  const search = async (): Promise<void> => {
    const value = query.trim()
    if (value === '') {
      setResults(null)
      setSearchError(null)
      return
    }
    if (value.length > MAX_SEARCH_LENGTH || hasInvalidTitleCharacters(value)) {
      setSearchError('搜索词格式无效')
      return
    }
    setSearching(true)
    setSearchError(null)
    try {
      const response = await callUnary(window.location.origin, 'session.search', { query: value })
      setResults(response.items.slice(0, 100).map(item => ({ sessionId: item.sessionId, snippet: cleanSnippet(item.snippet) })))
    } catch (failure) {
      setSearchError(rpcErrorMessage('搜索会话', failure))
    } finally {
      setSearching(false)
    }
  }

  return (
    <aside
      className={open ? 'navigation-drawer open' : 'navigation-drawer'}
      role={!isDesktop && open ? 'dialog' : 'complementary'}
      aria-modal={!isDesktop && open ? true : undefined}
      aria-hidden={!open}
      aria-label="会话与功能"
    >
      <header className="drawer-header">
        <button type="button" className="drawer-brand" aria-label="新建会话" onClick={props.onCreate}><Bot size={21} aria-hidden="true" /><strong>deepseek</strong></button>
        <button type="button" className="icon-button sidebar-close" aria-label="关闭菜单" title="关闭" onClick={props.onClose}><X size={18} /></button>
      </header>
      <button type="button" className="drawer-create" onClick={props.onCreate}><MessageSquarePlus size={16} aria-hidden="true" />新建会话</button>

      <form className="session-search" role="search" onSubmit={event => { event.preventDefault(); void search() }}>
        <Search size={15} aria-hidden="true" />
        <input
          value={query}
          maxLength={MAX_SEARCH_LENGTH}
          aria-label="搜索会话"
          placeholder="搜索会话"
          onChange={event => {
            setQuery(event.target.value)
            if (event.target.value === '') { setResults(null); setSearchError(null) }
          }}
        />
        {query !== '' && <button type="button" aria-label="清除搜索" onClick={() => { setQuery(''); setResults(null); setSearchError(null) }}><X size={14} /></button>}
        <button type="submit" aria-label="执行搜索" disabled={searching}>{searching ? <LoaderCircle className="spin" size={14} /> : <Search size={14} />}</button>
      </form>

      {error !== null && (
        <div className="drawer-error" role="status"><span>{error}</span><button type="button" aria-label="关闭错误提示" title="关闭" onClick={props.onDismissError}><X size={15} /></button></div>
      )}
      {searchError !== null && <p className="drawer-search-error" role="status">{searchError}</p>}

      <div className="drawer-sessions" aria-label="会话列表">
        {results !== null ? (
          <section className="workspace-group">
            <p className="drawer-label">搜索结果</p>
            {results.length === 0 ? <p className="drawer-empty">没有匹配的会话</p> : results.map(result => {
              const summary = sessions.find(item => item.sessionId === result.sessionId)
              return (
                <button key={result.sessionId} type="button" className="drawer-search-result" onClick={() => props.onOpenSession(result.sessionId)}>
                  <strong>{summary === undefined ? `会话 ${result.sessionId.slice(0, 8)}` : sessionDisplayTitle(summary)}</strong>
                  <span>{result.snippet}</span>
                </button>
              )
            })}
          </section>
        ) : (
          <>
            {workspaces.map(workspace => {
              const rows = workspace.sessionIds
                .map(id => activeSessions.find(item => item.sessionId === id))
                .filter((item): item is SessionSummary => item !== undefined)
              return (
                <section className="workspace-group" key={workspace.workspaceId}>
                  <p className="drawer-label"><FolderTree size={13} />{workspace.title}<small>{rows.length}</small></p>
                  {rows.map(item => <SessionRow key={item.sessionId} item={item} activeSessionId={activeSessionId} onMenu={setMenuFor} onOpen={props.onOpenSession} />)}
                </section>
              )
            })}
            {(looseSessions.length > 0 || workspaces.length === 0) && (
              <section className="workspace-group">
                <p className="drawer-label">最近会话</p>
                {looseSessions.length === 0 ? <p className="drawer-empty">暂无会话</p> : looseSessions.map(item => <SessionRow key={item.sessionId} item={item} activeSessionId={activeSessionId} onMenu={setMenuFor} onOpen={props.onOpenSession} />)}
              </section>
            )}
            {archivedSessions.length > 0 && (
              <details className="drawer-archive-group">
                <summary><Archive size={14} aria-hidden="true" /><span>已归档</span><small>{archivedSessions.length}</small></summary>
                <div className="drawer-archived-sessions">
                  {archivedSessions.map(item => (
                    <button key={item.sessionId} type="button" className={item.sessionId === activeSessionId ? 'drawer-session drawer-session-archived active' : 'drawer-session drawer-session-archived'} onClick={() => props.onOpenSession(item.sessionId)}>
                      <span>{sessionDisplayTitle(item)}</span><small>{item.cwd ?? '已归档会话'}</small>
                    </button>
                  ))}
                </div>
              </details>
            )}
          </>
        )}
      </div>

      <nav className="drawer-navigation" aria-label="Harness 功能">
        <button type="button" onClick={() => props.onOpenPage('tasks')}><CheckSquare2 size={18} />任务与计划</button>
        <button type="button" onClick={() => props.onOpenPage('files')}><FolderTree size={18} />工作区与目录</button>
        <button type="button" onClick={() => props.onOpenPage('settings')}><Settings size={18} />设置</button>
      </nav>

      {menuFor !== null && (
        <SessionActions session={menuFor} onArchive={() => props.onArchiveSession(menuFor.sessionId)} onClose={() => setMenuFor(null)} onRename={title => props.onRenameSession(menuFor.sessionId, title)} />
      )}
    </aside>
  )
}

function SessionRow(props: {
  item: SessionSummary
  activeSessionId: SessionId | null
  onMenu: (item: SessionSummary) => void
  onOpen: (sessionId: SessionId) => void
}): ReactElement {
  const { item } = props
  return (
    <div className="drawer-session-row">
      <button type="button" className={item.sessionId === props.activeSessionId ? 'drawer-session active' : 'drawer-session'} onClick={() => props.onOpen(item.sessionId)}>
        <span>{sessionDisplayTitle(item)}</span>
        <small>{item.running ? '运行中' : item.cwd ?? '本机会话'}</small>
      </button>
      <button type="button" className="drawer-session-menu" aria-label={`${sessionDisplayTitle(item)}的操作`} title="会话操作" onClick={() => props.onMenu(item)}><MoreVertical size={17} /></button>
    </div>
  )
}

function NewSessionDialog(props: {
  currentWorkspaceId?: string
  workspaces: WorkspaceView[]
  onClose: () => void
  onCreate: (options: Omit<SessionCreateRequest, 'sessionId'>) => Promise<string | null>
}): ReactElement {
  const [workspaceId, setWorkspaceId] = useState(props.currentWorkspaceId ?? '')
  const [presetId, setPresetId] = useState('')
  const [presets, setPresets] = useState<AgentPresetEntry[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    void callUnary(window.location.origin, 'agentPreset.list', {}, { signal: controller.signal })
      .then(value => {
        if (controller.signal.aborted) return
        setPresets(value.presets.filter(preset => preset.broken === undefined))
        setPresetId(value.presets.find(preset => preset.isDefault && preset.broken === undefined)?.id ?? '')
      })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) setError(rpcErrorMessage('加载 Agent preset', failure))
      })
    return () => { controller.abort() }
  }, [])

  const create = async (): Promise<void> => {
    if (busy) return
    if ((workspaceId !== '' && !props.workspaces.some(workspace => workspace.workspaceId === workspaceId)) || (presetId !== '' && !presets.some(preset => preset.id === presetId))) {
      setError('所选工作区或 Agent preset 已不可用')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const failure = await props.onCreate({
        ...(workspaceId === '' ? {} : { workspaceId }),
        ...(presetId === '' ? {} : { agentPreset: presetId }),
      })
      if (failure !== null) setError(failure)
    } catch (failure) {
      setError(rpcErrorMessage('创建会话', failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="sheet-backdrop" role="presentation" onPointerDown={event => { if (event.target === event.currentTarget && !busy) props.onClose() }}>
      <section className="sheet new-session-sheet" role="dialog" aria-modal="true" aria-label="新建会话">
        <header><h2>新建会话</h2><button type="button" className="mini-icon-button" aria-label="关闭新建会话" onClick={props.onClose}><X size={16} /></button></header>
        <label className="field-label" htmlFor="new-session-workspace">工作区</label>
        <select id="new-session-workspace" className="field" value={workspaceId} onChange={event => setWorkspaceId(event.target.value)}>
          <option value="">不指定工作区</option>
          {props.workspaces.map(workspace => <option key={workspace.workspaceId} value={workspace.workspaceId}>{workspace.title}</option>)}
        </select>
        {presets.length > 0 && (
          <><label className="field-label" htmlFor="new-session-preset">Agent preset</label><select id="new-session-preset" className="field" value={presetId} onChange={event => setPresetId(event.target.value)}>{presets.map(preset => <option key={preset.id} value={preset.id}>{preset.name ?? preset.id}{preset.isDefault ? '（默认）' : ''}</option>)}</select></>
        )}
        {error !== null && <p className="inline-error" role="status">{error}</p>}
        <div className="sheet-actions"><button type="button" className="btn" disabled={busy} onClick={props.onClose}>取消</button><button type="button" className="btn btn-primary" disabled={busy} onClick={() => void create()}>{busy ? '正在创建' : '创建'}</button></div>
      </section>
    </div>
  )
}

function SessionActions(props: {
  session: SessionSummary
  onArchive: () => Promise<void>
  onClose: () => void
  onRename: (title: string) => Promise<void>
}): ReactElement {
  const { session, onArchive, onClose, onRename } = props
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [title, setTitle] = useState(sessionDisplayTitle(session))
  const trimmedTitle = title.trim()
  const titleTooLong = titleByteLength(trimmedTitle) > MAX_SESSION_TITLE_BYTES
  const titleHasInvalidCharacters = hasInvalidTitleCharacters(trimmedTitle)
  const titleError = titleTooLong ? '标题不得超过 80 个 UTF-8 字节' : titleHasInvalidCharacters ? '标题不能包含控制或不可见字符' : null
  const renameDisabled = busy || trimmedTitle === '' || titleError !== null

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent): void => { if (event.key === 'Escape' && !busy) onClose() }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [busy, onClose])

  const confirmRename = async (): Promise<void> => {
    if (renameDisabled) return
    setBusy(true)
    try { await onRename(trimmedTitle) } finally { onClose() }
  }
  const confirmArchive = async (): Promise<void> => {
    if (busy) return
    setBusy(true)
    try { await onArchive() } finally { onClose() }
  }

  return (
    <div className="sheet-backdrop session-actions-backdrop" role="presentation" onPointerDown={event => { if (event.target === event.currentTarget && !busy) onClose() }}>
      <section className="sheet session-actions" role="dialog" aria-modal="true" aria-label="会话操作">
        <p className="sheet-title">{sessionDisplayTitle(session)}</p>
        {editing ? (
          <><div className="sheet-row"><input className="field" value={title} maxLength={MAX_SESSION_TITLE_CHARACTERS} aria-label="会话标题" aria-invalid={titleError !== null} autoFocus disabled={busy} onChange={event => setTitle(event.target.value)} onFocus={event => event.currentTarget.select()} onKeyDown={event => { if (event.key === 'Enter' && !event.nativeEvent.isComposing) { event.preventDefault(); void confirmRename() } }} /><button type="button" className="btn btn-primary" disabled={renameDisabled} onClick={() => void confirmRename()}>确定</button></div>{titleError !== null && <p className="session-title-error" role="alert">{titleError}</p>}</>
        ) : <button type="button" className="btn btn-block" disabled={busy} onClick={() => setEditing(true)}><Pencil size={17} />重命名</button>}
        <button type="button" className="btn btn-block btn-danger" disabled={busy} onClick={() => void confirmArchive()}><Archive size={17} />归档会话</button>
        <button type="button" className="btn btn-block" disabled={busy} onClick={onClose}>取消</button>
      </section>
    </div>
  )
}

function useDesktopLayout(): boolean {
  const [desktop, setDesktop] = useState(() => typeof window.matchMedia === 'function' && window.matchMedia('(min-width: 900px)').matches)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const media = window.matchMedia('(min-width: 900px)')
    const update = (): void => setDesktop(media.matches)
    update()
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])
  return desktop
}

function titleByteLength(value: string): number { return new TextEncoder().encode(value).byteLength }

function hasInvalidTitleCharacters(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0)
    if (code === undefined) continue
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) return true
    if (code === 0x200b || code === 0x200e || code === 0x200f || code === 0xfeff) return true
    if ((code >= 0x202a && code <= 0x202e) || (code >= 0x2060 && code <= 0x206f)) return true
  }
  return false
}

function cleanSnippet(value: string): string {
  return Array.from(value.slice(0, 500), character => {
    const code = character.codePointAt(0) ?? 0
    return code <= 0x1f || (code >= 0x7f && code <= 0x9f) ? ' ' : character
  }).join('').replace(/\s+/g, ' ').trim()
}

function readLastSession(): SessionId | null {
  try { const value = localStorage.getItem(LAST_SESSION_KEY); return value !== null && value.length <= 200 ? value : null } catch { return null }
}
function storeLastSession(sessionId: SessionId): void {
  try { localStorage.setItem(LAST_SESSION_KEY, sessionId) } catch { /* Storage is optional inside restricted WebViews. */ }
}
