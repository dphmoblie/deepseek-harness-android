import { useCallback, useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import { callUnary } from '../api/wire'
import { eventBus } from '../state/events'
import type { SessionId, SessionSummary, TaskView, TodoItem } from '../api/types'

/**
 * 任务面板：展示所选会话的 job 任务列表（session/jobs 帧）与
 * todo 待办（todo/write 事件），数据全部来自下行事件流。
 */
export function TasksView(): ReactElement {
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [selected, setSelected] = useState<SessionId | null>(null)
  const [jobs, setJobs] = useState<TaskView[]>([])
  const [todos, setTodos] = useState<TodoItem[]>([])
  const [error, setError] = useState<string | null>(null)

  const reloadSessions = useCallback(async () => {
    try {
      const value = await callUnary(window.location.origin, 'session.list', {})
      setSessions(
        [...value.items]
          .sort((a, b) => Number(b.running) - Number(a.running) || b.updatedAt - a.updatedAt),
      )
    } catch (failure) {
      setError(String(failure))
    }
  }, [])

  useEffect(() => {
    void reloadSessions()
    const unsubscribe = eventBus.subscribe(({ stream, frame }) => {
      if (stream === 'mux') {
        const mux = frame as { type?: string; sessionId?: SessionId; jobs?: TaskView[]; event?: { type?: string; data?: unknown } }
        if (mux.type === 'session/jobs' && mux.sessionId === selected && mux.jobs !== undefined) {
          setJobs(mux.jobs)
        }
        if (
          mux.type === 'session/event' &&
          mux.sessionId === selected &&
          mux.event?.type === 'todo/write'
        ) {
          const data = mux.event.data as { todos?: TodoItem[] } | undefined
          if (Array.isArray(data?.todos)) setTodos(data.todos)
        }
      } else if (stream === 'host') {
        const host = frame as { type?: string }
        if (host.type === 'host/session-status') void reloadSessions()
      }
    })
    return unsubscribe
  }, [selected, reloadSessions])

  useEffect(() => {
    if (selected === null && sessions.length > 0) setSelected(sessions[0]?.sessionId ?? null)
  }, [sessions, selected])

  const selectedSummary = sessions.find((item) => item.sessionId === selected)

  return (
    <main className="view">
      <header className="view-header">
        <h1>任务</h1>
      </header>
      {error !== null && <p className="error-bar" onClick={() => setError(null)}>{error}</p>}
      <div className="view-body">
        <label className="field-label">选择会话</label>
        <select
          className="field"
          value={selected ?? ''}
          onChange={(event) => setSelected(event.target.value)}
        >
          {sessions.length === 0 && <option value="">（暂无会话）</option>}
          {sessions.map((item) => (
            <option key={item.sessionId} value={item.sessionId}>
              {item.running ? '● ' : ''}{item.sessionId.slice(0, 8)}
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
        {todos.length === 0 ? (
          <p className="hint">暂无待办</p>
        ) : (
          <ul className="list">
            {todos.map((todo, index) => (
              <li key={index} className="todo-row">
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
