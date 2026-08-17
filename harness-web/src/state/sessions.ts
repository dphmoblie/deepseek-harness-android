/**
 * 会话列表状态：session.list + workspace.list（归档集合），
 * host 帧驱动的节流刷新。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { callUnary, RpcFailure, TransportError } from '../api/wire'
import { describeFailure } from '../errors'
import { eventBus } from './events'
import type { SessionId, SessionSummary } from '../api/types'

const REFRESH_THROTTLE_MS = 500

export type SessionsController = {
  items: SessionSummary[]
  archived: SessionId[]
  loading: boolean
  error: string | null
  reload: () => Promise<void>
  createSession: () => Promise<SessionId | null>
  renameSession: (sessionId: SessionId, title: string) => Promise<void>
  archiveSession: (sessionId: SessionId) => Promise<boolean>
  dismissError: () => void
}

export function useSessions(): SessionsController {
  const [items, setItems] = useState<SessionSummary[]>([])
  const [archived, setArchived] = useState<SessionId[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const reloadTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const toErrorText = useCallback((failure: unknown): string => {
    if (failure instanceof RpcFailure) return describeFailure(failure.code, failure.message)
    if (failure instanceof TransportError) return failure.message
    return String(failure)
  }, [])

  const reload = useCallback(async () => {
    try {
      const [sessions, workspaces] = await Promise.all([
        callUnary(window.location.origin, 'session.list', {}),
        callUnary(window.location.origin, 'workspace.list', {}),
      ])
      setItems([...sessions.items].sort((a, b) => b.updatedAt - a.updatedAt))
      setArchived(workspaces.archivedSessionIds)
      setError(null)
    } catch (failure) {
      setError(toErrorText(failure))
    } finally {
      setLoading(false)
    }
  }, [toErrorText])

  useEffect(() => {
    void reload()
    const unsubscribe = eventBus.subscribe(({ stream, frame }) => {
      if (stream !== 'host') return
      const type = (frame as { type?: string }).type
      if (
        type !== 'host/session-added' &&
        type !== 'host/session-removed' &&
        type !== 'host/session-status' &&
        type !== 'host/archived-sessions-changed' &&
        type !== 'host/workspace-changed'
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
    }
  }, [reload])

  const createSession = useCallback(async (): Promise<SessionId | null> => {
    try {
      const value = await callUnary(window.location.origin, 'session.create', {})
      await reload()
      return value.sessionId
    } catch (failure) {
      setError(toErrorText(failure))
      return null
    }
  }, [reload, toErrorText])

  const renameSession = useCallback(
    async (sessionId: SessionId, title: string) => {
      try {
        await callUnary(window.location.origin, 'session.rename', { sessionId, title })
        await reload()
      } catch (failure) {
        setError(toErrorText(failure))
      }
    },
    [reload, toErrorText],
  )

  const archiveSession = useCallback(
    async (sessionId: SessionId): Promise<boolean> => {
      try {
        await callUnary(window.location.origin, 'workspace.archiveSession', { sessionId })
        await reload()
        return true
      } catch (failure) {
        setError(toErrorText(failure))
        return false
      }
    },
    [reload, toErrorText],
  )

  const dismissError = useCallback(() => setError(null), [])

  return {
    items,
    archived,
    loading,
    error,
    reload,
    createSession,
    renameSession,
    archiveSession,
    dismissError,
  }
}
