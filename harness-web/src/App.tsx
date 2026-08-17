import {
  Bot,
  CheckSquare2,
  FileText,
  LoaderCircle,
  MessageSquarePlus,
  Settings,
  X,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import type { SessionId } from './api/types'
import { eventBus } from './state/events'
import { selectRecentActiveSession, sessionDisplayTitle } from './state/sessionDisplay'
import { useSessions } from './state/sessions'
import { ChatView } from './ui/ChatView'
import { FilesView } from './ui/FilesView'
import { SettingsView } from './ui/SettingsView'
import { TasksView } from './ui/TasksView'

type SecondaryPage = 'tasks' | 'files' | 'settings'

const LAST_SESSION_KEY = 'dsh-mobile-last-session-v1'

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
          sessions={sessions.items}
          onClose={() => setDrawerOpen(false)}
          onCreate={() => void createSession()}
          onOpenSession={openSession}
          onOpenPage={page => {
            setSecondaryPage(page)
            setDrawerOpen(false)
          }}
        />
      )}
    </main>
  )
}

function NavigationDrawer(props: {
  activeSessionId: SessionId | null
  archived: SessionId[]
  sessions: ReturnType<typeof useSessions>['items']
  onClose: () => void
  onCreate: () => void
  onOpenSession: (sessionId: SessionId) => void
  onOpenPage: (page: SecondaryPage) => void
}): ReactElement {
  const { activeSessionId, archived, sessions, onClose, onCreate, onOpenSession, onOpenPage } = props
  const archivedSet = new Set(archived)
  const activeSessions = sessions.filter(item => !archivedSet.has(item.sessionId))

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

        <div className="drawer-sessions" aria-label="最近会话">
          <p className="drawer-label">最近会话</p>
          {activeSessions.length === 0 ? (
            <p className="drawer-empty">暂无会话</p>
          ) : activeSessions.map(item => (
            <button
              key={item.sessionId}
              type="button"
              className={item.sessionId === activeSessionId ? 'drawer-session active' : 'drawer-session'}
              onClick={() => onOpenSession(item.sessionId)}
            >
              <span>{sessionDisplayTitle(item)}</span>
              <small>{item.running ? '运行中' : item.cwd ?? '本机会话'}</small>
            </button>
          ))}
        </div>

        <nav className="drawer-navigation" aria-label="Harness 功能">
          <button type="button" onClick={() => onOpenPage('tasks')}><CheckSquare2 size={18} />任务</button>
          <button type="button" onClick={() => onOpenPage('files')}><FileText size={18} />文件</button>
          <button type="button" onClick={() => onOpenPage('settings')}><Settings size={18} />模型与 Harness 设置</button>
        </nav>
      </aside>
    </div>
  )
}
