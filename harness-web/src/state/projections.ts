import type { GoalRef, SessionHistoryValue, TodoItem } from '../api/types'
import { hasUnsafeDiagnosticControls } from './textSafety'

export type GoalPhase = 'active' | 'paused' | 'blocked' | 'complete'

export type GoalSnapshotView = GoalRef & {
  objective: string
  phase: GoalPhase
  maxGoalRounds?: number
  blockedReason?: { message: string; code?: string }
}

const MAX_TODO_ITEMS = 500
const MAX_TODO_CONTENT_LENGTH = 10_000
const TODO_STATUSES = new Set<TodoItem['status']>(['pending', 'in_progress', 'completed'])

/** Parse the merge-extensible goal projection without trusting its runtime shape. */
export function parseGoalProjection(value: unknown): GoalSnapshotView | null | undefined {
  if (value === null) return null
  if (!isRecord(value)) return undefined
  const candidate = 'goal' in value ? value.goal : value
  if (candidate === null) return null
  if (!isRecord(candidate)) return undefined

  const { id, revision, objective, phase } = candidate
  if (
    typeof id !== 'string'
    || id.length === 0
    || id.length > 200
    || !Number.isInteger(revision)
    || (revision as number) < 0
    || typeof objective !== 'string'
    || objective.length === 0
    || objective.length > 12_000
    || !isGoalPhase(phase)
  ) return undefined

  const maxGoalRounds = candidate.maxGoalRounds
  const blockedReason = candidate.blockedReason
  return {
    id,
    revision: revision as number,
    objective,
    phase,
    ...(typeof maxGoalRounds === 'number' && Number.isInteger(maxGoalRounds) && maxGoalRounds > 0
      ? { maxGoalRounds }
      : {}),
    ...(isRecord(blockedReason) && typeof blockedReason.message === 'string'
      ? {
          blockedReason: {
            message: blockedReason.message.slice(0, 2_000),
            ...(typeof blockedReason.code === 'string' ? { code: blockedReason.code.slice(0, 120) } : {}),
          },
        }
      : {}),
  }
}

/** Parse the whole `todos` projection or todo/write snapshot at the UI boundary. */
export function parseTodoProjection(value: unknown): TodoItem[] | undefined {
  if (value === null) return []
  if (!Array.isArray(value) || value.length > MAX_TODO_ITEMS) return undefined

  const result: TodoItem[] = []
  const seen = new Set<string>()
  for (const item of value) {
    if (!isRecord(item)) return undefined
    const { content, status } = item
    if (
      typeof content !== 'string'
      || content.length === 0
      || content.length > MAX_TODO_CONTENT_LENGTH
      || content.trim() !== content
      || hasUnsafeDiagnosticControls(content)
      || typeof status !== 'string'
      || !TODO_STATUSES.has(status as TodoItem['status'])
      || seen.has(content)
    ) return undefined
    seen.add(content)
    result.push({ content, status: status as TodoItem['status'] })
  }
  return result
}

/** Recover the standing plan, preferring the authoritative history projection. */
export function todosFromHistory(history: SessionHistoryValue): TodoItem[] {
  const projections = history.projections
  if (
    projections !== undefined
    && isRecord(projections.values)
    && Object.prototype.hasOwnProperty.call(projections.values, 'todos')
  ) {
    const projected = parseTodoProjection(projections.values.todos)
    if (projected !== undefined) return projected
  }

  let current: TodoItem[] = []
  const entries = [...history.events].sort((left, right) => left.event.seq - right.event.seq)
  for (const { event } of entries) {
    if (event.type === 'turn/start') {
      current = []
      continue
    }
    if (event.type !== 'todo/write') continue
    const snapshot = parseTodoProjection(event.data.todos)
    if (snapshot !== undefined) current = snapshot
  }
  return current
}

function isGoalPhase(value: unknown): value is GoalPhase {
  return value === 'active' || value === 'paused' || value === 'blocked' || value === 'complete'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
