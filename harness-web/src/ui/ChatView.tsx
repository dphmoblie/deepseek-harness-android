import { Bot, LoaderCircle, Menu, MessageCircle } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactElement, UIEvent } from 'react'
import { callUnary, RpcFailure, TransportError } from '../api/wire'
import { describeFailure } from '../errors'
import { useChat } from '../state/chat'
import { draftActive, draftContent } from '../state/draft'
import type { ModelSelection, SessionId, SessionModelsValue } from '../api/types'
import { Blocks, MessageItem } from './Messages'
import { Composer } from './Composer'
import { ApprovalDialog, QuestionsDialog } from './Approvals'

export function ChatView(props: {
  sessionId: SessionId
  fallbackTitle?: string
  onOpenMenu: () => void
}): ReactElement {
  const { sessionId, fallbackTitle, onOpenMenu } = props
  const chat = useChat(sessionId)
  const models = useSessionModels(sessionId)
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const stickToBottom = useRef(true)

  const handleScroll = (event: UIEvent<HTMLDivElement>): void => {
    const element = event.currentTarget
    stickToBottom.current = element.scrollHeight - element.scrollTop - element.clientHeight < 64
    if (element.scrollTop < 40) void chat.loadMore()
  }

  // 新内容到达时贴底滚动
  useEffect(() => {
    const element = scrollRef.current
    if (element !== null && stickToBottom.current) {
      element.scrollTop = element.scrollHeight
    }
  }, [chat.entries, chat.draft])

  // 切换会话后强制回到底部
  useEffect(() => {
    stickToBottom.current = true
    const element = scrollRef.current
    if (element !== null) element.scrollTop = element.scrollHeight
  }, [sessionId])

  const draftBlocks = chat.draft !== null ? draftContent(chat.draft) : []
  const draftStreaming = chat.draft !== null && draftActive(chat.draft)

  return (
    <main className="view chat-view">
      <header className="view-header chat-header">
        <button
          type="button"
          className="icon-button"
          aria-label="打开会话与功能菜单"
          title="会话与功能"
          onClick={onOpenMenu}
        >
          <Menu size={20} aria-hidden="true" />
        </button>
        <div className="chat-heading">
          <span className="chat-title">{chat.title ?? fallbackTitle ?? `会话 ${sessionId.slice(0, 8)}`}</span>
          <span className="chat-subtitle">
            DeepSeek Harness
            {chat.running && (
              <span className="running-label">
                <LoaderCircle size={12} className="spin" aria-hidden="true" />
                运行中
              </span>
            )}
          </span>
        </div>
        <ModelPicker state={models} />
      </header>
      {chat.error !== null && (
        <button type="button" className="error-bar" onClick={chat.dismissError}>{chat.error}</button>
      )}
      {models.error !== null && <p className="model-error" title={models.error}>模型列表不可用</p>}
      <div className="chat-scroll" ref={scrollRef} onScroll={handleScroll}>
        {chat.hasMore && (
          <p className="load-more">
            {chat.loadingMore ? '加载中…' : '上拉加载更早消息'}
          </p>
        )}
        {chat.loading ? (
          <p className="hint">正在加载会话…</p>
        ) : (
          <>
            {chat.entries.map((entry) => (
              <MessageItem key={entry.seq} entry={entry} sessionId={sessionId} />
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
                <h1>开始新的对话</h1>
                <p>描述任务、粘贴错误信息，或让 Harness 检查当前工作区。</p>
              </div>
            )}
          </>
        )}
      </div>
      <Composer
        running={chat.running}
        queuedCount={chat.queuedItems.filter((item) => item.placement === 'queued').length}
        onSend={(text, mode) => void chat.sendPrompt(text, mode)}
        onCancel={() => void chat.cancelTurn()}
      />
      {chat.approval !== null && (
        <ApprovalDialog request={chat.approval} onAnswer={(outcome) => void chat.answerApproval(outcome)} />
      )}
      {chat.questions !== null && (
        <QuestionsDialog request={chat.questions} onAnswer={(answers) => void chat.answerQuestions(answers)} />
      )}
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

  const failureText = useCallback((failure: unknown): string => {
    if (failure instanceof RpcFailure) return describeFailure(failure.code, failure.message)
    if (failure instanceof TransportError) return failure.message
    return String(failure)
  }, [])

  useEffect(() => {
    let cancelled = false
    setValue(null)
    setLoading(true)
    setError(null)
    void callUnary(window.location.origin, 'session.models', { sessionId })
      .then((next) => {
        if (!cancelled) setValue(next)
      })
      .catch((failure: unknown) => {
        if (!cancelled) setError(failureText(failure))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sessionId, failureText])

  const select = useCallback(async (provider: string, model: string, reasoningEffort?: string) => {
    if (value === null || !value.routable || selecting) return
    const group = value.groups.find((item) => item.id === provider)
    const catalogModel = group?.models.find((item) => item.id === model)
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
      setValue((previous) => previous === null ? previous : { ...previous, current: result.selected })
    } catch (failure) {
      setError(failureText(failure))
    } finally {
      setSelecting(false)
    }
  }, [failureText, selecting, sessionId, value])

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
    <div className="model-controls" title={state.error ?? '当前会话模型'}>
      <label className="model-picker">
        <Bot size={15} aria-hidden="true" />
        <select
          aria-label="当前会话模型"
          value={currentKey}
          disabled={disabled}
          onChange={(event) => {
            const selectedKey = event.target.value
            const match = state.value?.groups.flatMap((group) =>
              group.models.map((model) => ({ provider: group.id, model: model.id, key: modelKey({ provider: group.id, model: model.id }) })),
            ).find((option) => option.key === selectedKey)
            if (match !== undefined) void state.select(match.provider, match.model)
          }}
        >
          {state.value === null && <option value="">{state.loading ? '加载模型…' : '模型不可用'}</option>}
          {state.value?.groups.map((group) => (
            <optgroup key={group.id} label={group.name}>
              {group.models.map((model) => (
                <option key={modelKey({ provider: group.id, model: model.id })} value={modelKey({ provider: group.id, model: model.id })}>
                  {model.name}
                </option>
              ))}
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
            onChange={event => void state.select(state.value!.current.provider, state.value!.current.model, event.target.value)}
          >
            {efforts.map(effort => <option key={effort.id} value={effort.id}>{effort.name}</option>)}
          </select>
        </label>
      )}
    </div>
  )
}
