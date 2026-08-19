/**
 * 会话列表状态：session.list + workspace.list（归档集合），
 * host 帧驱动的节流刷新。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { callUnary } from '../api/wire'
import { eventBus } from './events'
import { agentErrorNotice, streamErrorNotice } from './errorDisplay'
import { rpcErrorMessage } from './rpcError'
import type { SessionCreateRequest, SessionId, SessionSummary, WorkspaceView } from '../api/types'

const REFRESH_THROTTLE_MS = 500

export type SessionsController = {
  items: SessionSummary[]
  archived: SessionId[]
  workspaces: WorkspaceView[]
  loading: boolean
  error: string | null
  reload: () => Promise<void>
  createSession: (options?: Omit<SessionCreateRequest, 'sessionId'>) => Promise<SessionId | null>
  createSessionResult: (options?: Omit<SessionCreateRequest, 'sessionId'>) => Promise<SessionCreationResult>
  renameSession: (sessionId: SessionId, title: string) => Promise<void>
  archiveSession: (sessionId: SessionId) => Promise<boolean>
  dismissError: () => void
}

export type SessionCreationResult =
  | { sessionId: SessionId; error: null }
  | { sessionId: null; error: string }

export function useSessions(): SessionsController {
  const [items, setItems] = useState<SessionSummary[]>([])
  const [archived, setArchived] = useState<SessionId[]>([])
  const [workspaces, setWorkspaces] = useState<WorkspaceView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const reloadTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const reloadGeneration = useRef(0)
  const reloadController = useRef<AbortController | null>(null)

  const reload = useCallback(async () => {
    const generation = ++reloadGeneration.current
    reloadController.current?.abort()
    const controller = new AbortController()
    reloadController.current = controller
    try {
      const [sessions, workspaces] = await Promise.all([
        callUnary(window.location.origin, 'session.list', {}, { signal: controller.signal }),
        callUnary(window.location.origin, 'workspace.list', {}, { signal: controller.signal }),
      ])
      if (controller.signal.aborted || reloadGeneration.current !== generation) return
      setItems([...sessions.items].sort((a, b) => b.updatedAt - a.updatedAt))
      setArchived(workspaces.archivedSessionIds)
      setWorkspaces(workspaces.items)
      setError(null)
    } catch (failure) {
      if (controller.signal.aborted || reloadGeneration.current !== generation || isAbortFailure(failure)) return
      setError(rpcErrorMessage('加载会话与工作区', failure))
    } finally {
      if (!controller.signal.aborted && reloadGeneration.current === generation) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void reload()
    const unsubscribe = eventBus.subscribe(({ stream, frame }) => {
      const status = frame as { type?: unknown; message?: unknown; error?: unknown }
      if (status.type === 'stream/error') {
        setError(current => current ?? streamErrorNotice(status.error))
        return
      }
      if (stream !== 'host') return
      if (status.type === 'host/agent-error') {
        setError(agentErrorNotice(status.message))
        return
      }
      const type = typeof status.type === 'string' ? status.type : undefined
      if (
        type !== 'host/session-added' &&
        type !== 'host/session-removed' &&
        type !== 'host/session-status' &&
        type !== 'host/archived-sessions-changed' &&
        type !== 'host/workspace-changed' &&
        type !== 'host/workspace-removed' &&
        type !== 'host/workspace-order-changed'
      ) {
        return
      }
      if (reloadTimer.current !== null) clearTimeout(reloadTimer.current)
      reloadTimer.current = setTimeout(() => {
        reloadTimer.current = null
        void reload()
      }, REFRESH_THROTTLE_MS)
    })
    return () => {
      unsubscribe()
      if (reloadTimer.current !== null) clearTimeout(reloadTimer.current)
      reloadGeneration.current += 1
      reloadController.current?.abort()
      reloadController.current = null
    }
  }, [reload])

  const createSessionResult = useCallback(async (
    options: Omit<SessionCreateRequest, 'sessionId'> = {},
  ): Promise<SessionCreationResult> => {
    try {
      const value = await callUnary(window.location.origin, 'session.create', options)
      await reload()
      return { sessionId: value.sessionId, error: null }
    } catch (failure) {
      const message = rpcErrorMessage('创建会话', failure)
      setError(message)
      return { sessionId: null, error: message }
    }
  }, [reload])

  const createSession = useCallback(async (
    options: Omit<SessionCreateRequest, 'sessionId'> = {},
  ): Promise<SessionId | null> => (await createSessionResult(options)).sessionId, [createSessionResult])

  const renameSession = useCallback(
    async (sessionId: SessionId, title: string) => {
      try {
        await callUnary(window.location.origin, 'session.rename', { sessionId, title })
        await reload()
      } catch (failure) {
        setError(rpcErrorMessage('重命名会话', failure))
      }
    },
    [reload],
  )

  const archiveSession = useCallback(
    async (sessionId: SessionId): Promise<boolean> => {
      try {
        await callUnary(window.location.origin, 'workspace.archiveSession', { sessionId })
        await reload()
        return true
      } catch (failure) {
        setError(rpcErrorMessage('归档会话', failure))
        return false
      }
    },
    [reload],
  )

  const dismissError = useCallback(() => setError(null), [])

  return {
    items,
    archived,
    workspaces,
    loading,
    error,
    reload,
    createSession,
    createSessionResult,
    renameSession,
    archiveSession,
    dismissError,
  }
}

function isAbortFailure(failure: unknown): boolean {
  return failure instanceof Error && failure.name === 'AbortError'
}
