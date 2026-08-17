import {
  Activity,
  ArrowLeft,
  ArrowUp,
  Bot,
  CheckCircle2,
  Circle,
  LoaderCircle,
  PauseCircle,
  RefreshCw,
  Sparkles,
  Square,
  X,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactElement } from 'react'
import type {
  HistoryEntry,
  SessionId,
  SkillEntry,
  SubagentListEntry,
  TaskView,
  TodoItem,
} from '../api/types'
import { callUnary } from '../api/wire'
import { eventBus } from '../state/events'
import { foldHistory } from '../state/fold'
import { rpcErrorMessage } from '../state/rpcError'
import { MessageItem } from './Messages'

type DetailsTab = 'activity' | 'subagents' | 'skills'
type HealthySubagent = Extract<SubagentListEntry, { kind: 'child' }>

export function ConversationDetails(props: {
  sessionId: SessionId
  open: boolean
  todos: TodoItem[]
  onClose: () => void
}): ReactElement {
  const { sessionId, open, todos, onClose } = props
  const [tab, setTab] = useState<DetailsTab>('activity')
  const [jobs, setJobs] = useState<TaskView[]>([])
  const [subagents, setSubagents] = useState<SubagentListEntry[]>([])
  const [skills, setSkills] = useState<SkillEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [child, setChild] = useState<HealthySubagent | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    const [agentResult, skillResult] = await Promise.allSettled([
      callUnary(window.location.origin, 'subagent.list', { parentSessionId: sessionId }),
      callUnary(window.location.origin, 'skill.list', { sessionId }),
    ])
    if (agentResult.status === 'fulfilled') setSubagents(agentResult.value.entries)
    else setError(rpcErrorMessage('加载子代理', agentResult.reason))
    if (skillResult.status === 'fulfilled') setSkills(skillResult.value.skills)
    else if (agentResult.status === 'fulfilled') setError(rpcErrorMessage('加载技能', skillResult.reason))
    setLoading(false)
  }, [sessionId])

  useEffect(() => {
    setJobs([])
    setSubagents([])
    setSkills([])
    setChild(null)
    if (open) void reload()
  }, [open, reload, sessionId])

  useEffect(() => eventBus.subscribe(({ stream, frame }) => {
    if (stream !== 'mux') return
    const value = frame as { type?: string; sessionId?: SessionId; jobs?: TaskView[] }
    if (value.type === 'session/jobs' && value.sessionId === sessionId && Array.isArray(value.jobs)) {
      setJobs(value.jobs)
    }
  }), [sessionId])

  const activeAgents = useMemo(
    () => subagents.filter(entry => entry.kind === 'child' && entry.activity === 'running').length,
    [subagents],
  )

  return (
    <>
      {open && <button type="button" className="details-scrim" aria-label="关闭详情" onClick={onClose} />}
      <aside className={open ? 'conversation-details open' : 'conversation-details'} aria-label="会话详情" aria-hidden={!open}>
        <header className="details-header">
          <div>
            <strong>会话详情</strong>
            <span>{activeAgents > 0 ? `${activeAgents} 个子代理运行中` : '任务、子代理与技能'}</span>
          </div>
          <button type="button" className="icon-button quiet" aria-label="关闭详情" title="关闭" onClick={onClose}>
            <X size={18} />
          </button>
        </header>

        {child === null ? (
          <>
            <div className="details-tabs" role="tablist" aria-label="会话详情视图">
              <button type="button" role="tab" aria-selected={tab === 'activity'} onClick={() => setTab('activity')}>
                <Activity size={15} />活动
              </button>
              <button type="button" role="tab" aria-selected={tab === 'subagents'} onClick={() => setTab('subagents')}>
                <Bot size={15} />子代理{subagents.length > 0 && <small>{subagents.length}</small>}
              </button>
              <button type="button" role="tab" aria-selected={tab === 'skills'} onClick={() => setTab('skills')}>
                <Sparkles size={15} />技能{skills.length > 0 && <small>{skills.length}</small>}
              </button>
            </div>
            {error !== null && <p className="inline-error" role="status">{error}</p>}
            <div className="details-body">
              {loading && subagents.length === 0 && skills.length === 0 ? (
                <p className="details-empty"><LoaderCircle className="spin" size={18} />正在读取会话能力</p>
              ) : tab === 'activity' ? (
                <ActivityPanel jobs={jobs} todos={todos} />
              ) : tab === 'subagents' ? (
                <SubagentCatalog entries={subagents} onOpen={setChild} />
              ) : (
                <SkillCatalog skills={skills} />
              )}
            </div>
            <footer className="details-footer">
              <button type="button" className="text-button" disabled={loading} onClick={() => void reload()}>
                <RefreshCw className={loading ? 'spin' : ''} size={14} />刷新
              </button>
            </footer>
          </>
        ) : (
          <SubagentConversation parentSessionId={sessionId} child={child} onBack={() => setChild(null)} />
        )}
      </aside>
    </>
  )
}

function ActivityPanel(props: { jobs: TaskView[]; todos: TodoItem[] }): ReactElement {
  const { jobs, todos } = props
  return (
    <div className="details-sections">
      <section>
        <h2>当前任务</h2>
        {jobs.length === 0 ? <p className="details-empty">没有运行中的后台任务</p> : (
          <ul className="details-list">
            {jobs.map(job => (
              <li key={job.id}>
                <TaskStateIcon status={job.status} />
                <span><strong>{job.label}</strong>{job.detail !== undefined && <small>{job.detail}</small>}</span>
                <small>{taskLabel(job.status)}</small>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section>
        <h2>计划</h2>
        {todos.length === 0 ? <p className="details-empty">当前会话没有计划项</p> : (
          <ul className="details-list">
            {todos.map((todo, index) => (
              <li key={`${index}-${todo.content}`}>
                {todo.status === 'completed'
                  ? <CheckCircle2 className="state-success" size={16} />
                  : todo.status === 'in_progress'
                    ? <LoaderCircle className="spin state-active" size={16} />
                    : <Circle size={16} />}
                <span className={todo.status === 'completed' ? 'completed-copy' : ''}>{todo.content}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function SubagentCatalog(props: {
  entries: SubagentListEntry[]
  onOpen: (entry: HealthySubagent) => void
}): ReactElement {
  if (props.entries.length === 0) return <p className="details-empty">当前会话没有子代理</p>
  return (
    <ul className="details-list agent-list">
      {props.entries.map(entry => entry.kind === 'child' ? (
        <li key={entry.id}>
          <button type="button" className="details-row-button" onClick={() => props.onOpen(entry)}>
            <span className={entry.activity === 'running' ? 'agent-state running' : 'agent-state'} />
            <span>
              <strong>{entry.label ?? `子代理 ${entry.id.slice(0, 8)}`}</strong>
              <small>{entry.mode === 'continuable' ? '可继续对话' : '一次性任务'} · {entry.activity === 'running' ? '运行中' : '已停止'}</small>
            </span>
          </button>
        </li>
      ) : (
        <li key={entry.id} className="diagnostic-row">
          <PauseCircle size={16} />
          <span><strong>无法读取的子代理</strong><small>{entry.reason}</small></span>
        </li>
      ))}
    </ul>
  )
}

function SkillCatalog(props: { skills: SkillEntry[] }): ReactElement {
  if (props.skills.length === 0) return <p className="details-empty">当前代理没有可用技能</p>
  return (
    <ul className="details-list skill-list">
      {props.skills.map(skill => (
        <li key={skill.name}>
          <Sparkles size={16} />
          <span>
            <strong>/{skill.name}{!skill.modelInvocable && <em>仅限用户</em>}</strong>
            <small>{skill.description}</small>
            {skill.whenToUse !== undefined && <small>{skill.whenToUse}</small>}
          </span>
        </li>
      ))}
    </ul>
  )
}

function SubagentConversation(props: {
  parentSessionId: SessionId
  child: HealthySubagent
  onBack: () => void
}): ReactElement {
  const { parentSessionId, child, onBack } = props
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const value = await callUnary(window.location.origin, 'subagent.history', {
        parentSessionId,
        childSessionId: child.id,
        mode: child.mode,
        maxMessages: 100,
      })
      setHistory(value.events)
    } catch (failure) {
      setError(rpcErrorMessage('加载子代理记录', failure))
    } finally {
      setLoading(false)
    }
  }, [child.id, child.mode, parentSessionId])

  useEffect(() => { void reload() }, [reload])

  const send = async (): Promise<void> => {
    const prompt = text.trim()
    if (child.mode !== 'continuable' || prompt === '' || prompt.length > 12_000 || busy) return
    setBusy(true)
    setError(null)
    try {
      await callUnary(window.location.origin, 'subagent.prompt', {
        parentSessionId,
        childSessionId: child.id,
        mode: 'continuable',
        content: [{ type: 'text', text: prompt }],
        clientTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      })
      setText('')
      await reload()
    } catch (failure) {
      setError(rpcErrorMessage('发送子代理消息', failure))
    } finally {
      setBusy(false)
    }
  }

  const interrupt = async (): Promise<void> => {
    if (child.mode !== 'continuable' || busy) return
    setBusy(true)
    setError(null)
    try {
      await callUnary(window.location.origin, 'subagent.interrupt', {
        parentSessionId,
        childSessionId: child.id,
        mode: 'continuable',
      })
      await reload()
    } catch (failure) {
      setError(rpcErrorMessage('停止子代理', failure))
    } finally {
      setBusy(false)
    }
  }

  const entries = foldHistory(history)
  return (
    <div className="subagent-conversation">
      <header className="subagent-header">
        <button type="button" className="mini-icon-button" aria-label="返回子代理列表" onClick={onBack}><ArrowLeft size={16} /></button>
        <span><strong>{child.label ?? `子代理 ${child.id.slice(0, 8)}`}</strong><small>{child.mode === 'continuable' ? '可继续' : '只读记录'}</small></span>
        <button type="button" className="mini-icon-button" aria-label="刷新子代理记录" onClick={() => void reload()}><RefreshCw className={loading ? 'spin' : ''} size={15} /></button>
      </header>
      {error !== null && <p className="inline-error" role="status">{error}</p>}
      <div className="subagent-history">
        {loading && entries.length === 0 ? <p className="details-empty">正在载入执行记录</p> : entries.length === 0 ? <p className="details-empty">暂无记录</p> : (
          entries.map(entry => <MessageItem key={entry.seq} entry={entry} sessionId={child.id} />)
        )}
      </div>
      {child.mode === 'continuable' && (
        <div className="subagent-composer">
          <textarea
            rows={2}
            maxLength={12_000}
            value={text}
            aria-label="发送给子代理"
            placeholder="继续这个子代理的任务"
            onChange={event => setText(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                event.preventDefault()
                void send()
              }
            }}
          />
          {child.activity === 'running' && (
            <button type="button" className="mini-icon-button danger" aria-label="停止子代理" disabled={busy} onClick={() => void interrupt()}><Square size={13} fill="currentColor" /></button>
          )}
          <button type="button" className="mini-icon-button primary" aria-label="发送给子代理" disabled={busy || text.trim() === ''} onClick={() => void send()}><ArrowUp size={16} /></button>
        </div>
      )}
    </div>
  )
}

function TaskStateIcon(props: { status: TaskView['status'] }): ReactElement {
  if (props.status === 'running' || props.status === 'stopping') return <LoaderCircle className="spin state-active" size={16} />
  if (props.status === 'completed') return <CheckCircle2 className="state-success" size={16} />
  return <PauseCircle className="state-error" size={16} />
}

function taskLabel(status: TaskView['status']): string {
  if (status === 'running') return '运行中'
  if (status === 'stopping') return '停止中'
  if (status === 'completed') return '已完成'
  if (status === 'failed') return '失败'
  return '已终止'
}
