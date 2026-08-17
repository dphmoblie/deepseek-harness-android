/**
 * 聊天视图状态：历史分页 + mux 增量折叠 + assistant/chunk 流式草稿 +
 * 审批/提问应答。每个聊天页实例持有自己的状态，切换会话时整体重置。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { callUnary, RpcFailure, sendResponse, TransportError } from '../api/wire'
import { describeFailure } from '../errors'
import { applyChunk, createDraft, type AssistantDraft } from './draft'
import { foldEvent, foldHistory, type ChatEntry } from './fold'
import { eventBus, subscribeSession } from './events'
import { titleFromProjectionFrame, titleFromProjections } from './sessionDisplay'
import type {
  AskUserQuestionItem,
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
  title: string | null
  running: boolean
  loading: boolean
  loadingMore: boolean
  hasMore: boolean
  error: string | null
  approval: ApprovalRequest | null
  questions: QuestionRequest | null
  sendPrompt: (text: string, mode: PromptMode) => Promise<void>
  cancelTurn: () => Promise<void>
  loadMore: () => Promise<void>
  answerApproval: (outcome: 'allowed-once' | 'rejected') => Promise<void>
  answerQuestions: (answers: { id: string; selected: string[]; custom?: string }[]) => Promise<void>
  dismissError: () => void
}

export function useChat(sessionId: SessionId | null): ChatController {
  const [entries, setEntries] = useState<ChatEntry[]>([])
  const [draft, setDraftState] = useState<AssistantDraft | null>(null)
  const [todos, setTodos] = useState<TodoItem[]>([])
  const [queuedItems, setQueuedItems] = useState<QueuedInboxItem[]>([])
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

  const toErrorText = useCallback((failure: unknown): string => {
    if (failure instanceof RpcFailure) return describeFailure(failure.code, failure.message)
    if (failure instanceof TransportError) return failure.message
    return String(failure)
  }, [])

  /** 把一条增量事件折进视图（seq 去重、chunk 走草稿）。 */
  const applyEvent = useCallback(
    (event: SessionEvent, view?: ToolEventView) => {
      if (event.seq <= lastSeqRef.current) return
      lastSeqRef.current = event.seq
      const data = event.data
      switch (event.type) {
        case 'assistant/chunk': {
          const turn = data.turn as number
          const step = data.step as number
          const chunk = data.chunk as StreamChunk
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
          runningRevisionRef.current += 1
          setRunning(true)
          return
        case 'turn/end': {
          runningRevisionRef.current += 1
          setRunning(false)
          if (draftRef.current?.turn === (data.turn as number)) setDraftBoth(null)
          const folded = foldEvent(event)
          if (folded !== null) setEntriesBoth((prev) => [...prev, folded])
          return
        }
        case 'todo/write': {
          const next = data.todos
          if (Array.isArray(next)) setTodos(next as TodoItem[])
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
    lastSeqRef.current = -1
    runningRevisionRef.current = 0
    setEntries([])
    setDraftState(null)
    setTodos([])
    setQueuedItems([])
    setTitle(null)
    setRunning(false)
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
          const { event, view } = frame as { event: SessionEvent; view?: ToolEventView }
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
      if (stream !== 'host') return
      const status = frame as { type?: string; sessionId?: SessionId; running?: boolean }
      if (status.type === 'host/session-status' && status.sessionId === sessionId && typeof status.running === 'boolean') {
        runningRevisionRef.current += 1
        setRunning(status.running)
      }
    })
  }, [sessionId])

  const sendPrompt = useCallback(
    async (text: string, mode: PromptMode) => {
      if (sessionId === null || text.trim() === '') return
      setError(null)
      try {
        await callUnary(window.location.origin, 'session.prompt', {
          sessionId,
          mode,
          content: [{ type: 'text', text: text.trim() }],
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
    if (sessionId === null || loadingMore) return
    const head = entriesRef.current[0]
    if (head === undefined) return
    setLoadingMore(true)
    try {
      const value = await callUnary(window.location.origin, 'session.history', {
        sessionId,
        beforeSeq: head.seq,
        maxMessages: HISTORY_PAGE_SIZE,
      })
      const older = foldHistory(value.events).filter((entry) => entry.seq < head.seq)
      if (older.length > 0) setEntriesBoth((prev) => [...older, ...prev])
      setHasMore(value.hasMore)
    } catch (failure) {
      setError(toErrorText(failure))
    } finally {
      setLoadingMore(false)
    }
  }, [sessionId, loadingMore, setEntriesBoth, toErrorText])

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

  return {
    entries,
    draft,
    todos,
    queuedItems,
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
  }
}

/** 应用级便捷导出：App 挂载时启动事件总线。 */
export { eventBus }
