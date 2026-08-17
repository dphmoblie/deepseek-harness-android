import { ArrowLeft } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from '../api/wire'
import { eventBus } from '../state/events'
import { parseTodoProjection, todosFromHistory } from '../state/projections'
import { rpcErrorMessage } from '../state/rpcError'
import { streamErrorNotice } from '../state/errorDisplay'
import type {
  SessionId,
  SessionSummary,
  TaskView,
  TodoItem,
} from '../api/types'

const HISTORY_PAGE_SIZE = 100

/**
 * 任务面板：jobs 来自 session/jobs 实时帧；todo 优先使用历史尾页的
 * todos 投影，并以 todo/write 回放及实时事件作为兼容回退。
 */
export function TasksView(props: { onBack: () => void }): ReactElement {
  const { onBack } = props
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [selected, setSelected] = useState<SessionId | null>(null)
  const [jobs, setJobs] = useState<TaskView[]>([])
  const [todos, setTodos] = useState<TodoItem[]>([])
  const [loadingHistory, setLoadingHistory] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const selectionGenerationRef = useRef(0)
  const todoRevisionRef = useRef(0)

  const reloadSessions = useCallback(async () => {
    try {
      const value = await callUnary(window.location.origin, 'session.list', {})
      setSessions(
        [...value.items]
          .sort((a, b) => Number(b.running) - Number(a.running) || b.updatedAt - a.updatedAt),
      )
    } catch (failure) {
      setError(rpcErrorMessage('加载会话', failure))
    }
  }, [])

  useEffect(() => {
    void reloadSessions()
    return eventBus.subscribe(({ stream, frame }) => {
      const value = frame as { type?: string; error?: unknown }
      if (value.type === 'stream/error') {
        setError(streamErrorNotice(value.error))
        return
      }
      if (stream === 'host' && value.type === 'host/session-status') void reloadSessions()
    })
  }, [reloadSessions])

  useEffect(() => {
    if (sessions.length === 0) {
      if (selected !== null) setSelected(null)
      return
    }
    if (selected === null || !sessions.some((item) => item.sessionId === selected)) {
      setSelected(sessions[0]?.sessionId ?? null)
    }
  }, [sessions, selected])

  useEffect(() => {
    const generation = ++selectionGenerationRef.current
    todoRevisionRef.current = 0
    setJobs([])
    setTodos([])
    setLoadingHistory(selected !== null)
    setError(null)
    if (selected === null) return

    const controller = new AbortController()
    const revisionAtRequest = todoRevisionRef.current
    void callUnary(window.location.origin, 'session.history', {
      sessionId: selected,
      maxMessages: HISTORY_PAGE_SIZE,
    }, { signal: controller.signal })
      .then((value) => {
        if (
          selectionGenerationRef.current === generation
          && todoRevisionRef.current === revisionAtRequest
        ) {
          setTodos(todosFromHistory(value))
        }
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted || selectionGenerationRef.current !== generation) return
        setError(rpcErrorMessage('加载任务历史', failure))
      })
      .finally(() => {
        if (selectionGenerationRef.current === generation) setLoadingHistory(false)
      })

    return () => controller.abort()
  }, [selected])

  useEffect(() => {
    if (selected === null) return
    return eventBus.subscribe(({ stream, frame }) => {
      if (stream !== 'mux') return
      const mux = frame as {
        type?: string
        sessionId?: SessionId
        jobs?: unknown
        key?: unknown
        value?: unknown
        event?: { type?: unknown; data?: Record<string, unknown> }
      }
      if (mux.sessionId !== selected) return

      if (mux.type === 'session/jobs') {
        const nextJobs = parseTaskViews(mux.jobs)
        if (nextJobs !== null) setJobs(nextJobs)
        return
      }

      if (mux.type === 'session/projection' && mux.key === 'todos') {
        const nextTodos = parseTodoProjection(mux.value)
        if (nextTodos !== undefined) {
          todoRevisionRef.current += 1
          setTodos(nextTodos)
        }
        return
      }

      if (mux.type !== 'session/event') return
      if (mux.event?.type === 'turn/start') {
        todoRevisionRef.current += 1
        setTodos([])
        return
      }
      if (mux.event?.type === 'todo/write') {
        const nextTodos = parseTodoProjection(mux.event.data?.todos)
        if (nextTodos !== undefined) {
          todoRevisionRef.current += 1
          setTodos(nextTodos)
        }
      }
    })
  }, [selected])

  const selectedSummary = sessions.find((item) => item.sessionId === selected)

  const changeSession = (sessionId: string): void => {
    // Clear synchronously with the selection so one session never flashes the
    // previous session's jobs or todo snapshot while history is loading.
    setJobs([])
    setTodos([])
    setError(null)
    setSelected(sessionId === '' ? null : sessionId)
  }

  return (
    <main className="view">
      <header className="view-header secondary-header">
        <button type="button" className="icon-button" aria-label="返回对话" title="返回对话" onClick={onBack}><ArrowLeft size={20} /></button>
        <h1>任务</h1>
      </header>
      {error !== null && <p className="error-bar" role="alert" onClick={() => setError(null)}>{error}</p>}
      <div className="view-body">
        <label className="field-label" htmlFor="task-session">选择会话</label>
        <select
          id="task-session"
          className="field"
          value={selected ?? ''}
          onChange={(event) => changeSession(event.target.value)}
        >
          {sessions.length === 0 && <option value="">（暂无会话）</option>}
          {sessions.map((item) => (
            <option key={item.sessionId} value={item.sessionId}>
              {item.running ? '运行中 · ' : ''}{item.sessionId.slice(0, 8)}
              {item.cwd !== undefined ? `（${item.cwd}）` : ''}
            </option>
          ))}
        </select>

        <h2 className="section-title">
          执行任务
          {selectedSummary?.running === true && <span className="badge badge-running">运行中</span>}
        </h2>
        {jobs.length === 0 ? (
          <p className="hint">当前会话没有进行中的任务</p>
        ) : (
          <ul className="list">
            {jobs.map((job) => (
              <li key={job.id} className="task-row">
                <span className="task-status" data-status={job.status} />
                <span className="list-main">
                  <span className="list-title">{job.label}</span>
                  {job.detail !== undefined && job.detail !== '' && (
                    <span className="list-sub">{job.detail}</span>
                  )}
                </span>
                <span className="list-time">{jobStatusLabel(job.status)}</span>
              </li>
            ))}
          </ul>
        )}

        <h2 className="section-title">待办事项</h2>
        {loadingHistory && todos.length === 0 ? (
          <p className="hint">正在恢复待办…</p>
        ) : todos.length === 0 ? (
          <p className="hint">暂无待办</p>
        ) : (
          <ul className="list">
            {todos.map((todo) => (
              <li key={`${todo.status}:${todo.content}`} className="todo-row">
                <span className="todo-status" data-status={todo.status} />
                <span className="list-title">{todo.content}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </main>
  )
}

function parseTaskViews(value: unknown): TaskView[] | null {
  if (!Array.isArray(value) || value.length > 1_000) return null
  const result: TaskView[] = []
  for (const item of value) {
    if (!isRecord(item)) return null
    const { id, kind, label, status, detail, startedAt, finishedAt } = item
    if (
      typeof id !== 'string' || id.length === 0 || id.length > 200
      || typeof kind !== 'string' || kind.length === 0 || kind.length > 200
      || typeof label !== 'string' || label.length === 0 || label.length > 10_000
      || !isTaskStatus(status)
      || (detail !== undefined && (typeof detail !== 'string' || detail.length > 20_000))
      || typeof startedAt !== 'number' || !Number.isFinite(startedAt)
      || (finishedAt !== undefined && (typeof finishedAt !== 'number' || !Number.isFinite(finishedAt)))
    ) return null
    result.push({
      id,
      kind,
      label,
      status,
      startedAt,
      ...(detail === undefined ? {} : { detail }),
      ...(finishedAt === undefined ? {} : { finishedAt }),
    })
  }
  return result
}

function isTaskStatus(value: unknown): value is TaskView['status'] {
  return value === 'running'
    || value === 'stopping'
    || value === 'completed'
    || value === 'killed'
    || value === 'failed'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function jobStatusLabel(status: TaskView['status']): string {
  switch (status) {
    case 'running':
      return '运行中'
    case 'stopping':
      return '停止中'
    case 'completed':
      return '已完成'
    case 'killed':
      return '已终止'
    case 'failed':
      return '失败'
  }
}
