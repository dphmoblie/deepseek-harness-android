import { Bot, GitFork, LoaderCircle, Menu, MessageCircle, PanelRightOpen } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactElement, UIEvent } from 'react'
import { callUnary } from '../api/wire'
import { useChat } from '../state/chat'
import { draftActive, draftContent } from '../state/draft'
import { modelCatalogFailureNotice } from '../state/errorDisplay'
import { rpcErrorMessage } from '../state/rpcError'
import type { ModelSelection, SessionId, SessionModelsValue } from '../api/types'
import { Blocks, MessageItem } from './Messages'
import { Composer } from './Composer'
import { ApprovalDialog, QuestionsDialog } from './Approvals'
import { ConversationDetails } from './ConversationDetails'
import { GoalDock } from './GoalDock'
import { QueueDock } from './QueueDock'
import { TodoDock } from './TodoDock'

export function ChatView(props: {
  sessionId: SessionId
  fallbackTitle?: string
  agentPreset?: string
  onOpenMenu: () => void
  onOpenSession?: (sessionId: SessionId) => void
}): ReactElement {
  const { sessionId, fallbackTitle, agentPreset, onOpenMenu, onOpenSession } = props
  const chat = useChat(sessionId)
  const models = useSessionModels(sessionId)
  const [detailsOpen, setDetailsOpen] = useState(false)
  const [forking, setForking] = useState(false)
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const stickToBottom = useRef(true)

  const handleScroll = (event: UIEvent<HTMLDivElement>): void => {
    const element = event.currentTarget
    stickToBottom.current = element.scrollHeight - element.scrollTop - element.clientHeight < 64
    if (element.scrollTop < 40) void chat.loadMore()
  }

  useEffect(() => {
    const element = scrollRef.current
    if (element !== null && stickToBottom.current) element.scrollTop = element.scrollHeight
  }, [chat.entries, chat.draft])

  useEffect(() => {
    stickToBottom.current = true
    setDetailsOpen(false)
    const element = scrollRef.current
    if (element !== null) element.scrollTop = element.scrollHeight
  }, [sessionId])

  const fork = async (atSeq?: number): Promise<void> => {
    if (forking) return
    setForking(true)
    try {
      const value = await callUnary(window.location.origin, 'session.fork', {
        sessionId,
        ...(atSeq === undefined ? {} : { atSeq }),
      })
      onOpenSession?.(value.sessionId)
    } catch (failure) {
      chat.reportError(rpcErrorMessage('分叉会话', failure))
    } finally {
      setForking(false)
    }
  }

  const draftBlocks = chat.draft !== null ? draftContent(chat.draft) : []
  const draftStreaming = chat.draft !== null && draftActive(chat.draft)

  return (
    <main className="chat-workspace">
      <section className="view chat-view">
        <header className="view-header chat-header">
          <button type="button" className="icon-button mobile-menu-button" aria-label="打开会话与功能菜单" title="会话与功能" onClick={onOpenMenu}>
            <Menu size={20} aria-hidden="true" />
          </button>
          <div className="chat-heading">
            <span className="chat-title">{chat.title ?? fallbackTitle ?? `会话 ${sessionId.slice(0, 8)}`}</span>
            <span className="chat-subtitle">
              {agentPreset !== undefined && <span className="preset-label">{agentPreset}</span>}
              {chat.running ? (
                <span className="running-label"><LoaderCircle size={12} className="spin" aria-hidden="true" />运行中</span>
              ) : 'DeepSeek Harness'}
            </span>
          </div>
          <div className="chat-header-actions">
            <button type="button" className="icon-button quiet" aria-label="分叉当前会话" title="分叉当前会话" disabled={forking} onClick={() => void fork()}>
              {forking ? <LoaderCircle className="spin" size={17} /> : <GitFork size={17} />}
            </button>
            <button type="button" className="icon-button quiet" aria-label="打开会话详情" title="会话详情" aria-pressed={detailsOpen} onClick={() => setDetailsOpen(previous => !previous)}>
              <PanelRightOpen size={18} />
            </button>
          </div>
          <ModelPicker state={models} />
        </header>
        {chat.error !== null && (
          <button type="button" className="error-bar" aria-label="关闭错误信息" onClick={chat.dismissError}>{chat.error}</button>
        )}
        {models.error !== null && <p className="model-error" role="status">{models.error}</p>}
        {models.value !== null && models.value.failures.length > 0 && (
          <p className="model-error" role="status">{modelCatalogFailureNotice(models.value.failures)}</p>
        )}
        <div className="chat-scroll" ref={scrollRef} onScroll={handleScroll}>
          {chat.hasMore && (
            <div className="load-more">
              <button type="button" disabled={chat.loadingMore} onClick={() => void chat.loadMore()}>
                {chat.loadingMore ? '正在加载' : '加载更早消息'}
              </button>
            </div>
          )}
          {chat.loading ? (
            <p className="hint"><LoaderCircle className="spin" size={18} />正在加载会话</p>
          ) : (
            <>
              {chat.entries.map(entry => (
                <MessageItem key={entry.seq} entry={entry} sessionId={sessionId} onFork={atSeq => void fork(atSeq)} />
              ))}
              {draftBlocks.length > 0 && (
                <div className="msg msg-assistant">
                  <div className={`bubble bubble-assistant${draftStreaming ? ' bubble-streaming' : ''}`}>
                    <Blocks blocks={draftBlocks} sessionId={sessionId} streaming={draftStreaming} />
                  </div>
                </div>
              )}
              {!chat.running && chat.entries.length === 0 && draftBlocks.length === 0 && (
                <div className="chat-empty">
                  <span className="empty-icon"><MessageCircle size={24} aria-hidden="true" /></span>
                  <h1>DeepSeek Harness</h1>
                  <p>新的 Harness 会话</p>
                </div>
              )}
            </>
          )}
        </div>
        <div className="composer-context">
          <TodoDock todos={chat.todos} />
          <GoalDock sessionId={sessionId} goal={chat.goal} onFailure={chat.reportError} />
          <QueueDock sessionId={sessionId} items={chat.queuedItems} running={chat.running} onFailure={chat.reportError} />
        </div>
        <Composer
          sessionId={sessionId}
          running={chat.running}
          queuedCount={chat.queuedItems.filter(item => item.placement === 'queued').length}
          onSend={(text, mode, images) => void chat.sendPrompt(text, mode, images)}
          onCancel={() => void chat.cancelTurn()}
          onFailure={chat.reportError}
        />
        {chat.approval !== null && <ApprovalDialog request={chat.approval} onAnswer={outcome => void chat.answerApproval(outcome)} />}
        {chat.questions !== null && <QuestionsDialog request={chat.questions} onAnswer={answers => void chat.answerQuestions(answers)} />}
      </section>
      <ConversationDetails sessionId={sessionId} open={detailsOpen} todos={chat.todos} onClose={() => setDetailsOpen(false)} />
    </main>
  )
}

type ModelState = {
  value: SessionModelsValue | null
  loading: boolean
  selecting: boolean
  error: string | null
  select: (provider: string, model: string, reasoningEffort?: string) => Promise<void>
}

function modelKey(selection: Pick<ModelSelection, 'provider' | 'model'>): string {
  return JSON.stringify([selection.provider, selection.model])
}

function useSessionModels(sessionId: SessionId): ModelState {
  const [value, setValue] = useState<SessionModelsValue | null>(null)
  const [loading, setLoading] = useState(true)
  const [selecting, setSelecting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setValue(null)
    setLoading(true)
    setError(null)
    void callUnary(window.location.origin, 'session.models', { sessionId })
      .then(next => { if (!cancelled) setValue(next) })
      .catch((failure: unknown) => { if (!cancelled) setError(rpcErrorMessage('加载模型', failure)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [sessionId])

  const select = useCallback(async (provider: string, model: string, reasoningEffort?: string) => {
    if (value === null || !value.routable || selecting) return
    const group = value.groups.find(item => item.id === provider)
    const catalogModel = group?.models.find(item => item.id === model)
    if (catalogModel === undefined) return
    setSelecting(true)
    setError(null)
    try {
      const result = await callUnary(window.location.origin, 'session.selectModel', {
        sessionId,
        provider,
        model,
        reasoningEffort: reasoningEffort ?? catalogModel.reasoning?.defaultEffort,
      })
      setValue(previous => previous === null ? previous : { ...previous, current: result.selected })
    } catch (failure) {
      setError(rpcErrorMessage('切换模型', failure))
    } finally {
      setSelecting(false)
    }
  }, [selecting, sessionId, value])

  return { value, loading, selecting, error, select }
}

function ModelPicker(props: { state: ModelState }): ReactElement {
  const { state } = props
  const currentKey = state.value === null ? '' : modelKey(state.value.current)
  const disabled = state.loading || state.selecting || state.value === null || !state.value.routable
  const currentModel = state.value?.groups
    .find(group => group.id === state.value?.current.provider)
    ?.models.find(model => model.id === state.value?.current.model)
  const efforts = currentModel?.reasoning?.efforts ?? []

  return (
    <div className="model-controls" title="当前会话模型">
      <label className="model-picker">
        <Bot size={15} aria-hidden="true" />
        <select
          aria-label="当前会话模型"
          value={currentKey}
          disabled={disabled}
          onChange={(event) => {
            const selectedKey = event.target.value
            const match = state.value?.groups.flatMap(group => group.models.map(model => ({ provider: group.id, model: model.id, key: modelKey({ provider: group.id, model: model.id }) }))).find(option => option.key === selectedKey)
            if (match !== undefined) void state.select(match.provider, match.model)
          }}
        >
          {state.value === null && <option value="">{state.loading ? '加载模型' : '模型不可用'}</option>}
          {state.value?.groups.map(group => (
            <optgroup key={group.id} label={group.name}>
              {group.models.map(model => <option key={modelKey({ provider: group.id, model: model.id })} value={modelKey({ provider: group.id, model: model.id })}>{model.name}</option>)}
            </optgroup>
          ))}
        </select>
      </label>
      {state.value !== null && efforts.length > 0 && (
        <label className="reasoning-picker">
          <select
            aria-label="推理强度"
            value={state.value.current.reasoningEffort ?? currentModel?.reasoning?.defaultEffort ?? efforts[0]?.id}
            disabled={disabled}
            onChange={event => {
              if (state.value !== null) void state.select(state.value.current.provider, state.value.current.model, event.target.value)
            }}
          >
            {efforts.map(effort => <option key={effort.id} value={effort.id}>{effort.name}</option>)}
          </select>
        </label>
      )}
    </div>
  )
}
