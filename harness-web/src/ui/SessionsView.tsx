import { useState } from 'react'
import type { ReactElement } from 'react'
import { useSessions } from '../state/sessions'
import { sessionDisplayTitle } from '../state/sessionDisplay'
import type { SessionId, SessionSummary } from '../api/types'

function timeLabel(updatedAt: number): string {
  const date = new Date(updatedAt)
  const now = new Date()
  const sameDay = date.toDateString() === now.toDateString()
  if (sameDay) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

/** 移动端操作菜单：长按条目弹出重命名/归档。 */
function SessionActions(props: {
  session: SessionSummary
  onRename: (title: string) => void
  onArchive: () => void
  onClose: () => void
}): ReactElement {
  const { session, onRename, onArchive, onClose } = props
  const [title, setTitle] = useState('')
  const [editing, setEditing] = useState(false)

  const confirmRename = (): void => {
    const next = title.trim()
    if (next !== '') onRename(next)
    onClose()
  }

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" onClick={(event) => event.stopPropagation()}>
        <p className="sheet-title">{sessionDisplayTitle(session)}</p>
        {editing ? (
          <div className="sheet-row">
            <input
              className="field"
              value={title}
              placeholder="新标题"
              autoFocus
              onChange={(event) => setTitle(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') confirmRename()
              }}
            />
            <button type="button" className="btn" onClick={confirmRename}>确定</button>
          </div>
        ) : (
          <button type="button" className="btn btn-block" onClick={() => setEditing(true)}>
            重命名
          </button>
        )}
        <button type="button" className="btn btn-block btn-danger" onClick={onArchive}>
          归档会话
        </button>
        <button type="button" className="btn btn-block" onClick={onClose}>
          取消
        </button>
      </div>
    </div>
  )
}

export function SessionsView(props: { onOpen: (sessionId: SessionId) => void }): ReactElement {
  const { onOpen } = props
  const sessions = useSessions()
  const [menuFor, setMenuFor] = useState<SessionSummary | null>(null)

  const active = sessions.items.filter((item) => !sessions.archived.includes(item.sessionId))
  const archived = sessions.items.filter((item) => sessions.archived.includes(item.sessionId))

  const handleCreate = async (): Promise<void> => {
    const sessionId = await sessions.createSession()
    if (sessionId !== null) onOpen(sessionId)
  }

  return (
    <main className="view">
      <header className="view-header">
        <h1>会话</h1>
        <button type="button" className="btn btn-primary" onClick={() => void handleCreate()}>
          新建会话
        </button>
      </header>
      {sessions.error !== null && (
        <p className="error-bar" onClick={sessions.dismissError}>{sessions.error}</p>
      )}
      <div className="view-body">
        {sessions.loading && active.length === 0 ? (
          <p className="hint">正在加载会话…</p>
        ) : active.length === 0 ? (
          <p className="hint">暂无会话，点击右上角新建</p>
        ) : (
          <ul className="list">
            {active.map((item) => (
              <li key={item.sessionId}>
                <button
                  type="button"
                  className="list-row"
                  onClick={() => onOpen(item.sessionId)}
                  onContextMenu={(event) => {
                    event.preventDefault()
                    setMenuFor(item)
                  }}
                >
                  <span className="list-main">
                    <span className="list-title">{sessionDisplayTitle(item)}</span>
                    {item.cwd !== undefined && <span className="list-sub">{item.cwd}</span>}
                  </span>
                  <span className="list-side">
                    {item.running && <span className="badge badge-running">运行中</span>}
                    <span className="list-time">{timeLabel(item.updatedAt)}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {archived.length > 0 && (
          <details className="archive-group">
            <summary>已归档（{archived.length}）</summary>
            <ul className="list">
              {archived.map((item) => (
                <li key={item.sessionId}>
                  <button type="button" className="list-row" onClick={() => onOpen(item.sessionId)}>
                    <span className="list-title">{sessionDisplayTitle(item)}</span>
                    <span className="list-time">{timeLabel(item.updatedAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          </details>
        )}
      </div>
      {menuFor !== null && (
        <SessionActions
          session={menuFor}
          onClose={() => setMenuFor(null)}
          onRename={(title) => void sessions.renameSession(menuFor.sessionId, title)}
          onArchive={() => {
            void sessions.archiveSession(menuFor.sessionId)
            setMenuFor(null)
          }}
        />
      )}
    </main>
  )
}
