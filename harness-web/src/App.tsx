import {
  Archive,
  Bot,
  CheckSquare2,
  FileText,
  LoaderCircle,
  MessageSquarePlus,
  MoreVertical,
  Pencil,
  Settings,
  X,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import type { SessionId, SessionSummary } from './api/types'
import { eventBus } from './state/events'
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

function titleByteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength
}

function hasInvalidTitleCharacters(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0)
    if (code === undefined) continue
    if (code <= 0x1f || (code >= 0x7f && code <= 0x9f)) return true
    if (code === 0x200b || code === 0x200e || code === 0x200f || code === 0xfeff) return true
    if ((code >= 0x202a && code <= 0x202e) || (code >= 0x2060 && code <= 0x2064)) return true
    if (code >= 0x2066 && code <= 0x206f) return true
  }
  return false
}

function readLastSession(): SessionId | null {
  try {
    const value = localStorage.getItem(LAST_SESSION_KEY)
    return value !== null && value.length <= 200 ? value : null
  } catch {
    return null
  }
}

function storeLastSession(sessionId: SessionId): void {
  try {
    localStorage.setItem(LAST_SESSION_KEY, sessionId)
  } catch {
    // The conversation remains usable when WebView storage is unavailable.
  }
}

export function App(): ReactElement {
  const sessions = useSessions()
  const [sessionId, setSessionId] = useState<SessionId | null>(null)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [secondaryPage, setSecondaryPage] = useState<SecondaryPage | null>(null)
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
    const rememberedAvailable = remembered !== null && sessions.items.some(
      item => item.sessionId === remembered && !archived.has(item.sessionId),
    )
    const restored = rememberedAvailable
      ? remembered
      : selectRecentActiveSession(sessions.items, sessions.archived)
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

  const currentSummary = useMemo(
    () => sessions.items.find(item => item.sessionId === sessionId),
    [sessionId, sessions.items],
  )

  const openSession = (nextSessionId: SessionId): void => {
    setSessionId(nextSessionId)
    setSecondaryPage(null)
    setDrawerOpen(false)
  }

  const createSession = async (): Promise<void> => {
    const created = await sessions.createSession()
    if (created !== null) openSession(created)
  }

  const archiveSession = async (targetSessionId: SessionId): Promise<void> => {
    const archivedSuccessfully = await sessions.archiveSession(targetSessionId)
    if (!archivedSuccessfully) return
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

  if (secondaryPage !== null) {
    const back = (): void => setSecondaryPage(null)
    if (secondaryPage === 'tasks') return <TasksView onBack={back} />
    if (secondaryPage === 'files') return <FilesView onBack={back} />
    return <SettingsView onBack={back} />
  }

  return (
    <main className="app conversation-app">
      {sessionId === null ? (
        <div className="conversation-loading">
          <span className="loading-mark"><LoaderCircle className="spin" size={24} aria-hidden="true" /></span>
          <strong>{sessions.error === null ? '正在准备会话' : '无法载入会话'}</strong>
          {sessions.error !== null && (
            <>
              <p>{sessions.error}</p>
              <button type="button" className="btn btn-primary" onClick={() => void sessions.reload()}>重试</button>
            </>
          )}
        </div>
      ) : (
        <ChatView
          sessionId={sessionId}
          fallbackTitle={currentSummary === undefined ? undefined : sessionDisplayTitle(currentSummary)}
          onOpenMenu={() => setDrawerOpen(true)}
        />
      )}

      {drawerOpen && (
        <NavigationDrawer
          activeSessionId={sessionId}
          archived={sessions.archived}
          error={sessions.error}
          sessions={sessions.items}
          onArchiveSession={session => archiveSession(session)}
          onClose={() => setDrawerOpen(false)}
          onCreate={() => void createSession()}
          onDismissError={sessions.dismissError}
          onOpenSession={openSession}
          onOpenPage={page => {
            setSecondaryPage(page)
            setDrawerOpen(false)
          }}
          onRenameSession={(targetSessionId, title) => sessions.renameSession(targetSessionId, title)}
        />
      )}
    </main>
  )
}

function NavigationDrawer(props: {
  activeSessionId: SessionId | null
  archived: SessionId[]
  error: string | null
  sessions: ReturnType<typeof useSessions>['items']
  onArchiveSession: (sessionId: SessionId) => Promise<void>
  onClose: () => void
  onCreate: () => void
  onDismissError: () => void
  onOpenSession: (sessionId: SessionId) => void
  onOpenPage: (page: SecondaryPage) => void
  onRenameSession: (sessionId: SessionId, title: string) => Promise<void>
}): ReactElement {
  const {
    activeSessionId,
    archived,
    error,
    sessions,
    onArchiveSession,
    onClose,
    onCreate,
    onDismissError,
    onOpenSession,
    onOpenPage,
    onRenameSession,
  } = props
  const [menuFor, setMenuFor] = useState<SessionSummary | null>(null)
  const archivedSet = new Set(archived)
  const activeSessions = sessions.filter(item => !archivedSet.has(item.sessionId))
  const archivedSessions = sessions.filter(item => archivedSet.has(item.sessionId))

  return (
    <div className="drawer-backdrop" role="presentation" onPointerDown={event => { if (event.target === event.currentTarget) onClose() }}>
      <aside className="navigation-drawer" role="dialog" aria-modal="true" aria-label="会话与功能">
        <header className="drawer-header">
          <span className="drawer-brand"><Bot size={18} aria-hidden="true" />Harness</span>
          <button type="button" className="icon-button" aria-label="关闭菜单" title="关闭" onClick={onClose}><X size={19} /></button>
        </header>

        <button type="button" className="btn btn-primary drawer-create" onClick={onCreate}>
          <MessageSquarePlus size={18} aria-hidden="true" />新建会话
        </button>

        {error !== null && (
          <div className="drawer-error" role="alert">
            <span>{error}</span>
            <button type="button" aria-label="关闭错误提示" title="关闭" onClick={onDismissError}>
              <X size={15} aria-hidden="true" />
            </button>
          </div>
        )}

        <div className="drawer-sessions" aria-label="会话列表">
          <p className="drawer-label">最近会话</p>
          {activeSessions.length === 0 ? (
            <p className="drawer-empty">暂无会话</p>
          ) : activeSessions.map(item => (
            <div className="drawer-session-row" key={item.sessionId}>
              <button
                type="button"
                className={item.sessionId === activeSessionId ? 'drawer-session active' : 'drawer-session'}
                onClick={() => onOpenSession(item.sessionId)}
              >
                <span>{sessionDisplayTitle(item)}</span>
                <small>{item.running ? '运行中' : item.cwd ?? '本机会话'}</small>
              </button>
              <button
                type="button"
                className="drawer-session-menu"
                aria-label={`${sessionDisplayTitle(item)}的操作`}
                title="会话操作"
                onClick={() => setMenuFor(item)}
              >
                <MoreVertical size={18} aria-hidden="true" />
              </button>
            </div>
          ))}

          {archivedSessions.length > 0 && (
            <details className="drawer-archive-group">
              <summary>
                <Archive size={14} aria-hidden="true" />
                <span>已归档</span>
                <small>{archivedSessions.length}</small>
              </summary>
              <div className="drawer-archived-sessions">
                {archivedSessions.map(item => (
                  <button
                    key={item.sessionId}
                    type="button"
                    className={item.sessionId === activeSessionId
                      ? 'drawer-session drawer-session-archived active'
                      : 'drawer-session drawer-session-archived'}
                    onClick={() => onOpenSession(item.sessionId)}
                  >
                    <span>{sessionDisplayTitle(item)}</span>
                    <small>{item.cwd ?? '已归档会话'}</small>
                  </button>
                ))}
              </div>
            </details>
          )}
        </div>

        <nav className="drawer-navigation" aria-label="Harness 功能">
          <button type="button" onClick={() => onOpenPage('tasks')}><CheckSquare2 size={18} />任务</button>
          <button type="button" onClick={() => onOpenPage('files')}><FileText size={18} />文件</button>
          <button type="button" onClick={() => onOpenPage('settings')}><Settings size={18} />模型与 Harness 设置</button>
        </nav>
      </aside>

      {menuFor !== null && (
        <SessionActions
          session={menuFor}
          onArchive={() => onArchiveSession(menuFor.sessionId)}
          onClose={() => setMenuFor(null)}
          onRename={title => onRenameSession(menuFor.sessionId, title)}
        />
      )}
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
  const titleError = titleTooLong
    ? '标题不得超过 80 个 UTF-8 字节'
    : titleHasInvalidCharacters
      ? '标题不能包含控制或不可见字符'
      : null
  const renameDisabled = busy || trimmedTitle === '' || titleError !== null

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent): void => {
      if (event.key === 'Escape' && !busy) onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [busy, onClose])

  const confirmRename = async (): Promise<void> => {
    if (renameDisabled) return
    setBusy(true)
    try {
      await onRename(trimmedTitle)
    } finally {
      onClose()
    }
  }

  const confirmArchive = async (): Promise<void> => {
    if (busy) return
    setBusy(true)
    try {
      await onArchive()
    } finally {
      onClose()
    }
  }

  return (
    <div
      className="sheet-backdrop session-actions-backdrop"
      role="presentation"
      onPointerDown={event => { if (event.target === event.currentTarget && !busy) onClose() }}
    >
      <section className="sheet session-actions" role="dialog" aria-modal="true" aria-label="会话操作">
        <p className="sheet-title">{sessionDisplayTitle(session)}</p>
        {editing ? (
          <>
            <div className="sheet-row">
              <input
                className="field"
                value={title}
                maxLength={MAX_SESSION_TITLE_CHARACTERS}
                aria-label="会话标题"
                aria-invalid={titleError !== null}
                autoFocus
                disabled={busy}
                onChange={event => setTitle(event.target.value)}
                onFocus={event => event.currentTarget.select()}
                onKeyDown={event => {
                  if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                    event.preventDefault()
                    void confirmRename()
                  }
                }}
              />
              <button type="button" className="btn btn-primary" disabled={renameDisabled} onClick={() => void confirmRename()}>
                确定
              </button>
            </div>
            {titleError !== null && <p className="session-title-error" role="alert">{titleError}</p>}
          </>
        ) : (
          <button type="button" className="btn btn-block" disabled={busy} onClick={() => setEditing(true)}>
            <Pencil size={17} aria-hidden="true" />重命名
          </button>
        )}
        <button type="button" className="btn btn-block btn-danger" disabled={busy} onClick={() => void confirmArchive()}>
          <Archive size={17} aria-hidden="true" />归档会话
        </button>
        <button type="button" className="btn btn-block" disabled={busy} onClick={onClose}>取消</button>
      </section>
    </div>
  )
}
