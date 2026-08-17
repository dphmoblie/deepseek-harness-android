/**
 * 聊天视图状态：历史分页 + mux 增量折叠 + assistant/chunk 流式草稿 +
 * 审批/提问应答。每个聊天页实例持有自己的状态，切换会话时整体重置。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { callUnary, sendResponse } from '../api/wire'
import { applyChunk, createDraft, type AssistantDraft } from './draft'
import { streamErrorNotice } from './errorDisplay'
import { foldEvent, foldHistory, type ChatEntry } from './fold'
import { eventBus, subscribeSession } from './events'
import { parseGoalProjection, parseTodoProjection, todosFromHistory, type GoalSnapshotView } from './projections'
import { rpcErrorMessage } from './rpcError'
import { titleFromProjectionFrame, titleFromProjections } from './sessionDisplay'
import type {
  AskUserQuestionItem,
  FinishReason,
  PromptContentPart,
  QueuedInboxItem,
  SessionEvent,
  SessionId,
  StreamChunk,
  TodoItem,
  ToolEventView,
} from '../api/types'

const HISTORY_PAGE_SIZE = 100

export type PromptMode = 'queue' | 'steer'

export type ApprovalRequest = {
  rpcId: string
  sessionId: SessionId
  approvalId: string
  toolName: string
  callId?: string
  reason?: string
}

export type QuestionRequest = {
  rpcId: string
  sessionId: SessionId
  questions: AskUserQuestionItem[]
}

export type ChatController = {
  entries: ChatEntry[]
  draft: AssistantDraft | null
  todos: TodoItem[]
  queuedItems: QueuedInboxItem[]
  goal: GoalSnapshotView | null | undefined
  title: string | null
  running: boolean
  loading: boolean
  loadingMore: boolean
  hasMore: boolean
  error: string | null
  approval: ApprovalRequest | null
  questions: QuestionRequest | null
  sendPrompt: (
    text: string,
    mode: PromptMode,
    images?: Extract<PromptContentPart, { type: 'image' }>[],
  ) => Promise<void>
  cancelTurn: () => Promise<void>
  loadMore: () => Promise<void>
  answerApproval: (outcome: 'allowed-once' | 'rejected') => Promise<void>
  answerQuestions: (answers: { id: string; selected: string[]; custom?: string }[]) => Promise<void>
  dismissError: () => void
  reportError: (message: string) => void
}

export function useChat(sessionId: SessionId | null): ChatController {
  const [entries, setEntries] = useState<ChatEntry[]>([])
  const [draft, setDraftState] = useState<AssistantDraft | null>(null)
  const [todos, setTodos] = useState<TodoItem[]>([])
  const [queuedItems, setQueuedItems] = useState<QueuedInboxItem[]>([])
  const [goal, setGoal] = useState<GoalSnapshotView | null | undefined>(undefined)
  const [title, setTitle] = useState<string | null>(null)
  const [running, setRunning] = useState(false)
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [hasMore, setHasMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [approval, setApproval] = useState<ApprovalRequest | null>(null)
  const [questions, setQuestions] = useState<QuestionRequest | null>(null)

  // 高频增量用 ref 保存权威状态，setState 仅负责触发渲染
  const entriesRef = useRef<ChatEntry[]>([])
  const draftRef = useRef<AssistantDraft | null>(null)
  const finishReasonRef = useRef<{ turn: number; reason: FinishReason } | null>(null)
  const lastSeqRef = useRef(-1)
  const runningRevisionRef = useRef(0)
  const historyRequestRef = useRef(0)

  const setEntriesBoth = useCallback((updater: (prev: ChatEntry[]) => ChatEntry[]) => {
    entriesRef.current = updater(entriesRef.current)
    setEntries(entriesRef.current)
  }, [])

  const setDraftBoth = useCallback((next: AssistantDraft | null) => {
    draftRef.current = next
    setDraftState(next)
  }, [])

  const toErrorText = useCallback((failure: unknown): string => rpcErrorMessage('Harness 请求', failure), [])

  /** 把一条增量事件折进视图（seq 去重、chunk 走草稿）。 */
  const applyEvent = useCallback(
    (event: SessionEvent, view?: ToolEventView) => {
      if (event.seq <= lastSeqRef.current) return
      lastSeqRef.current = event.seq
      const data = event.data
      switch (event.type) {
        case 'assistant/chunk': {
          const turn = sequenceNumber(data.turn)
          const step = sequenceNumber(data.step)
          const chunk = streamChunkOf(data.chunk)
          if (turn === null || step === null || chunk === null) {
            setError(streamErrorNotice({ message: 'assistant/chunk 载荷格式无效' }))
            return
          }
          if (chunk.type === 'finish') finishReasonRef.current = { turn, reason: chunk.reason }
          const current = draftRef.current
          const base = current !== null && current.turn === turn && current.step === step
            ? current
            : createDraft(turn, step)
          setDraftBoth(applyChunk(base, chunk))
          return
        }
        case 'assistant/message': {
          setDraftBoth(null)
          const folded = foldEvent(event)
          if (folded !== null) setEntriesBoth((prev) => [...prev, folded])
          return
        }
        case 'turn/start':
          finishReasonRef.current = null
          runningRevisionRef.current += 1
          setTodos([])
          setRunning(true)
          return
        case 'turn/end': {
          runningRevisionRef.current += 1
          setRunning(false)
          const turn = data.turn as number
          if (draftRef.current?.turn === turn) setDraftBoth(null)
          const finishReason = finishReasonRef.current?.turn === turn ? finishReasonRef.current.reason : undefined
          finishReasonRef.current = null
          const folded = foldEvent(event, undefined, finishReason)
          if (folded !== null) setEntriesBoth((prev) => [...prev, folded])
          return
        }
        case 'todo/write': {
          const next = parseTodoProjection(data.todos)
          if (next !== undefined) setTodos(next)
          return
        }
        default: {
          const folded = foldEvent(event, view)
          if (folded !== null) setEntriesBoth((prev) => [...prev, folded])
        }
      }
    },
    [setDraftBoth, setEntriesBoth],
  )

  /** 拉取历史首页（切换会话或检测到漏事件时调用）。 */
  const loadHistory = useCallback(
    async (session: SessionId) => {
      const requestId = ++historyRequestRef.current
      const seqAtRequest = lastSeqRef.current
      const runningRevisionAtRequest = runningRevisionRef.current
      setLoadingMore(false)
      setLoading(true)
      setError(null)
      try {
        const value = await callUnary(window.location.origin, 'session.history', {
          sessionId: session,
          maxMessages: HISTORY_PAGE_SIZE,
        })
        if (sessionIdRef.current !== session) return
        const folded = foldHistory(value.events)
        const historyLastSeq = value.events[value.events.length - 1]?.event.seq ?? -1
        if (lastSeqRef.current > seqAtRequest) {
          const merged = new Map<number, ChatEntry>()
          folded.forEach(entry => merged.set(entry.seq, entry))
          entriesRef.current.forEach(entry => merged.set(entry.seq, entry))
          const nextEntries = [...merged.values()].sort((left, right) => left.seq - right.seq)
          entriesRef.current = nextEntries
          setEntries(nextEntries)
        } else {
          entriesRef.current = folded
          setEntries(folded)
        }
        setHasMore(value.hasMore)
        setTitle(titleFromProjections(value.projections))
        setTodos(todosFromHistory(value))
        if (value.projections !== undefined && Object.prototype.hasOwnProperty.call(value.projections.values, 'goal')) {
          setGoal(parseGoalProjection(value.projections.values.goal))
        }
        let historyRunning = false
        for (const entry of value.events) {
          if (entry.event.type === 'turn/start') historyRunning = true
          if (entry.event.type === 'turn/end') historyRunning = false
        }
        if (runningRevisionRef.current === runningRevisionAtRequest) setRunning(historyRunning)
        lastSeqRef.current = Math.max(lastSeqRef.current, historyLastSeq)
      } catch (failure) {
        if (historyRequestRef.current === requestId && sessionIdRef.current === session) {
          setError(toErrorText(failure))
        }
      } finally {
        if (historyRequestRef.current === requestId) setLoading(false)
      }
    },
    [toErrorText],
  )

  // 会话切换时整体重置（sessionIdRef 防止过期响应污染新会话）
  const sessionIdRef = useRef<SessionId | null>(null)
  useEffect(() => {
    sessionIdRef.current = sessionId
    historyRequestRef.current += 1
    entriesRef.current = []
    draftRef.current = null
    finishReasonRef.current = null
    lastSeqRef.current = -1
    runningRevisionRef.current = 0
    setEntries([])
    setDraftState(null)
    setTodos([])
    setQueuedItems([])
    setGoal(undefined)
    setTitle(null)
    setRunning(false)
    setLoadingMore(false)
    setHasMore(false)
    setApproval(null)
    setQuestions(null)
    setError(null)
    if (sessionId !== null) void loadHistory(sessionId)
  }, [sessionId, loadHistory])

  useEffect(() => {
    if (sessionId === null) return
    const unsubscribe = subscribeSession(sessionId, (rpcId, frame) => {
      // MuxFrame 含 merge-extensible 透传分支，switch 不窄化，case 内显式收窄
      switch (frame.type) {
        case 'session/event': {
          const candidate = frame as { event?: unknown; view?: ToolEventView }
          const event = sessionEventOf(candidate.event)
          if (event === null) {
            setError(streamErrorNotice({ message: 'session/event 载荷格式无效' }))
            break
          }
          const { view } = candidate
          applyEvent(event, view)
          break
        }
        case 'session/subscribed': {
          const { lastSeq } = frame as { lastSeq: number }
          // 漏事件检测：流建立晚于最新事件，说明中间有缺口，重载首页补全
          if (lastSeq > lastSeqRef.current) void loadHistory(sessionId)
          break
        }
        case 'approval/requested': {
          const { sessionId: sid, approvalId, toolName, callId, reason } = frame as {
            sessionId: SessionId
            approvalId: string
            toolName: string
            callId?: string
            reason?: string
          }
          setApproval({ rpcId, sessionId: sid, approvalId, toolName, callId, reason })
          break
        }
        case 'approval/resolved':
          setApproval(null)
          break
        case 'question/requested': {
          const { sessionId: sid, questions: qs } = frame as {
            sessionId: SessionId
            questions: AskUserQuestionItem[]
          }
          setQuestions({ rpcId, sessionId: sid, questions: qs })
          break
        }
        case 'question/resolved':
          setQuestions(null)
          break
        case 'session/queue': {
          const { items } = frame as { items: QueuedInboxItem[] }
          setQueuedItems(Array.isArray(items) ? items : [])
          break
        }
        case 'session/projection': {
          const { key, value } = frame as { key: string; value: unknown }
          const nextTitle = titleFromProjectionFrame(key, value)
          if (nextTitle !== null) setTitle(nextTitle)
          if (key === 'goal') setGoal(parseGoalProjection(value))
          if (key === 'todos') {
            const nextTodos = parseTodoProjection(value)
            if (nextTodos !== undefined) setTodos(nextTodos)
          }
          break
        }
        default:
          break
      }
    })
    return unsubscribe
  }, [sessionId, applyEvent, loadHistory])

  useEffect(() => {
    if (sessionId === null) return
    return eventBus.subscribe(({ stream, frame }) => {
      const status = frame as {
        type?: string
        sessionId?: SessionId
        running?: boolean
        message?: unknown
        error?: unknown
      }
      if (stream !== 'host') return
      if (status.type === 'host/session-status' && status.sessionId === sessionId && typeof status.running === 'boolean') {
        runningRevisionRef.current += 1
        setRunning(status.running)
      }
    })
  }, [sessionId])

  const sendPrompt = useCallback(
    async (
      text: string,
      mode: PromptMode,
      images: Extract<PromptContentPart, { type: 'image' }>[] = [],
    ) => {
      if (sessionId === null) return
      const trimmed = text.trim()
      const safeImages = images
        .filter(image => (
          (image.mediaType === 'image/png'
            || image.mediaType === 'image/jpeg'
            || image.mediaType === 'image/webp'
            || image.mediaType === 'image/gif')
          && image.data.length > 0
          && image.data.length <= 14_000_000
        ))
        .slice(0, 4)
      if (trimmed === '' && safeImages.length === 0) return
      if (trimmed.length > 12_000) {
        setError('消息超过 12000 字符限制')
        return
      }
      const content: PromptContentPart[] = [
        ...(trimmed === '' ? [] : [{ type: 'text' as const, text: trimmed }]),
        ...safeImages.map(image => ({
          ...image,
          ...(image.name === undefined ? {} : { name: image.name.slice(0, 120) }),
        })),
      ]
      setError(null)
      try {
        await callUnary(window.location.origin, 'session.prompt', {
          sessionId,
          mode,
          content,
          clientTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        })
      } catch (failure) {
        setError(toErrorText(failure))
      }
    },
    [sessionId, toErrorText],
  )

  const cancelTurn = useCallback(async () => {
    if (sessionId === null) return
    try {
      await callUnary(window.location.origin, 'session.cancel', { sessionId })
    } catch (failure) {
      setError(toErrorText(failure))
    }
  }, [sessionId, toErrorText])

  const loadMore = useCallback(async () => {
    if (sessionId === null || loading || loadingMore) return
    const head = entriesRef.current[0]
    if (head === undefined) return
    const sessionAtRequest = sessionId
    const historyRequestAtRequest = historyRequestRef.current
    const isCurrentRequest = () => (
      sessionIdRef.current === sessionAtRequest
      && historyRequestRef.current === historyRequestAtRequest
    )
    setLoadingMore(true)
    try {
      const value = await callUnary(window.location.origin, 'session.history', {
        sessionId: sessionAtRequest,
        beforeSeq: head.seq,
        maxMessages: HISTORY_PAGE_SIZE,
      })
      if (!isCurrentRequest()) return
      const older = foldHistory(value.events).filter((entry) => entry.seq < head.seq)
      if (older.length > 0) setEntriesBoth((prev) => [...older, ...prev])
      setHasMore(value.hasMore)
    } catch (failure) {
      if (isCurrentRequest()) setError(toErrorText(failure))
    } finally {
      if (isCurrentRequest()) setLoadingMore(false)
    }
  }, [sessionId, loading, loadingMore, setEntriesBoth, toErrorText])

  const answerApproval = useCallback(
    async (outcome: 'allowed-once' | 'rejected') => {
      if (approval === null) return
      const pending = approval
      setApproval(null)
      try {
        await sendResponse(window.location.origin, pending.rpcId, {
          sessionId: pending.sessionId,
          approvalId: pending.approvalId,
          outcome,
        })
      } catch (failure) {
        setError(toErrorText(failure))
      }
    },
    [approval, toErrorText],
  )

  const answerQuestions = useCallback(
    async (answers: { id: string; selected: string[]; custom?: string }[]) => {
      if (questions === null) return
      const pending = questions
      setQuestions(null)
      try {
        await sendResponse(window.location.origin, pending.rpcId, {
          sessionId: pending.sessionId,
          answer: { answers },
        })
      } catch (failure) {
        setError(toErrorText(failure))
      }
    },
    [questions, toErrorText],
  )

  const dismissError = useCallback(() => setError(null), [])
  const reportError = useCallback((message: string) => setError(message.slice(0, 500)), [])

  return {
    entries,
    draft,
    todos,
    queuedItems,
    goal,
    title,
    running,
    loading,
    loadingMore,
    hasMore,
    error,
    approval,
    questions,
    sendPrompt,
    cancelTurn,
    loadMore,
    answerApproval,
    answerQuestions,
    dismissError,
    reportError,
  }
}

/** 应用级便捷导出：App 挂载时启动事件总线。 */
export { eventBus }

function recordOf(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function sequenceNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null
}

function boundedText(value: unknown, maxLength: number): value is string {
  return typeof value === 'string' && value.length <= maxLength
}

function streamChunkOf(value: unknown): StreamChunk | null {
  const chunk = recordOf(value)
  if (chunk === null || !boundedText(chunk.type, 128) || chunk.type.length === 0) return null
  const index = (): boolean => sequenceNumber(chunk.index) !== null && (chunk.index as number) <= 100_000
  switch (chunk.type) {
    case 'block-start':
      return index() && boundedText(chunk.blockType, 128) && chunk.blockType.length > 0 ? chunk as StreamChunk : null
    case 'text-delta':
    case 'reasoning-delta':
      return index() && boundedText(chunk.text, 2_000_000) ? chunk as StreamChunk : null
    case 'tool-call-delta':
      return index()
        && boundedText(chunk.id, 512)
        && boundedText(chunk.argumentsDelta, 2_000_000)
        && (chunk.name === undefined || boundedText(chunk.name, 256))
        ? chunk as StreamChunk
        : null
    case 'block-end': {
      const block = recordOf(chunk.block)
      return index() && block !== null && boundedText(block.type, 128) && block.type.length > 0
        ? chunk as StreamChunk
        : null
    }
    case 'usage':
      return validTokenUsage(chunk.usage) ? chunk as StreamChunk : null
    case 'finish': {
      const reason = recordOf(chunk.reason)
      return reason !== null && boundedText(reason.kind, 128) && reason.kind.length > 0 ? chunk as StreamChunk : null
    }
    default:
      // 新版插件分块由 draft 安全忽略；仍限制外层形状和类型长度。
      return chunk as unknown as StreamChunk
  }
}

function sessionEventOf(value: unknown): SessionEvent | null {
  const event = recordOf(value)
  if (event === null || !boundedText(event.type, 128) || event.type.length === 0) return null
  if (sequenceNumber(event.seq) === null) return null
  if (typeof event.time !== 'number' || !Number.isFinite(event.time)) return null
  const data = recordOf(event.data)
  if (data === null) return null
  if (event.type === 'turn/end' && recordOf(data.reason) === null) return null
  return event as SessionEvent
}

function validTokenUsage(value: unknown): boolean {
  const usage = recordOf(value)
  if (usage === null) return false
  const fields = ['inputTokens', 'outputTokens', 'cacheReadTokens', 'cacheWriteTokens', 'reasoningTokens'] as const
  for (const field of fields) {
    const amount = usage[field]
    if (amount === undefined && field !== 'inputTokens' && field !== 'outputTokens') continue
    if (typeof amount !== 'number' || !Number.isSafeInteger(amount) || amount < 0) return false
  }
  return true
}
