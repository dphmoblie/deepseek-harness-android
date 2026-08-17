import { Check, Edit3, Flag, Pause, Play, Trash2, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import type { GoalRef, SessionId } from '../api/types'
import { callUnary } from '../api/wire'
import { failureReason } from '../state/errorDisplay'
import type { GoalSnapshotView } from '../state/projections'
import { rpcErrorMessage } from '../state/rpcError'

const MAX_OBJECTIVE_LENGTH = 12_000

export function GoalDock(props: {
  sessionId: SessionId
  goal: GoalSnapshotView | null | undefined
  onFailure: (message: string) => void
}): ReactElement | null {
  const { sessionId, goal, onFailure } = props
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const pendingRef = useRef(false)

  useEffect(() => {
    setEditing(false)
    setDraft(goal?.objective ?? '')
  }, [goal?.id, goal?.objective])

  if (goal === undefined || goal === null || goal.phase === 'complete') return null

  const run = async (action: () => Promise<unknown>): Promise<boolean> => {
    if (pendingRef.current) return false
    pendingRef.current = true
    setPending(true)
    try {
      await action()
      return true
    } catch (failure) {
      onFailure(goalFailureText(failure))
      return false
    } finally {
      pendingRef.current = false
      setPending(false)
    }
  }

  const ref: GoalRef = { id: goal.id, revision: goal.revision }
  const save = async (): Promise<void> => {
    const objective = draft.trim()
    if (objective === '' || objective.length > MAX_OBJECTIVE_LENGTH) return
    if (await run(() => callUnary(window.location.origin, 'goal.edit', { sessionId, ref, objective }))) {
      setEditing(false)
    }
  }

  if (editing) {
    return (
      <section className="dock-card goal-dock" aria-label="编辑目标">
        <Flag className="dock-leading" size={15} aria-hidden="true" />
        <input
          className="dock-editor"
          aria-label="目标"
          autoFocus
          maxLength={MAX_OBJECTIVE_LENGTH}
          value={draft}
          onChange={event => setDraft(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Escape') setEditing(false)
            if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
              event.preventDefault()
              void save()
            }
          }}
        />
        <span className="dock-actions">
          <button type="button" className="mini-icon-button" aria-label="保存目标" disabled={pending || draft.trim() === ''} onClick={() => void save()}><Check size={14} /></button>
          <button type="button" className="mini-icon-button" aria-label="取消编辑目标" disabled={pending} onClick={() => setEditing(false)}><X size={14} /></button>
        </span>
      </section>
    )
  }

  return (
    <section className="dock-card goal-dock" aria-label="当前目标" title={failureReason(goal.blockedReason) ?? undefined}>
      <Flag className="dock-leading" size={15} aria-hidden="true" />
      <span className={`goal-phase ${goal.phase}`}>{goalPhaseLabel(goal.phase)}</span>
      <span className="dock-preview">{goal.objective}</span>
      <span className="dock-actions">
        {goal.phase === 'active' && (
          <button type="button" className="mini-icon-button" aria-label="暂停目标" title="暂停" disabled={pending} onClick={() => void run(() => callUnary(window.location.origin, 'goal.pause', { sessionId, ref }))}><Pause size={14} /></button>
        )}
        {goal.phase === 'paused' && (
          <button type="button" className="mini-icon-button" aria-label="恢复目标" title="恢复" disabled={pending} onClick={() => void run(() => callUnary(window.location.origin, 'goal.resume', { sessionId, ref }))}><Play size={14} /></button>
        )}
        <button type="button" className="mini-icon-button" aria-label="编辑目标" title="编辑" disabled={pending} onClick={() => { setDraft(goal.objective); setEditing(true) }}><Edit3 size={14} /></button>
        <button type="button" className="mini-icon-button" aria-label="完成目标" title="标记完成" disabled={pending} onClick={() => void run(() => callUnary(window.location.origin, 'goal.complete', { sessionId, ref }))}><Check size={14} /></button>
        <button type="button" className="mini-icon-button" aria-label="清除目标" title="清除" disabled={pending} onClick={() => void run(() => callUnary(window.location.origin, 'goal.clear', { sessionId, ref }))}><Trash2 size={14} /></button>
      </span>
    </section>
  )
}

function goalPhaseLabel(phase: GoalSnapshotView['phase']): string {
  if (phase === 'active') return '进行中的目标'
  if (phase === 'paused') return '已暂停'
  if (phase === 'blocked') return '受阻'
  return '已完成'
}

function goalFailureText(failure: unknown): string {
  return rpcErrorMessage('更新目标', failure)
}
