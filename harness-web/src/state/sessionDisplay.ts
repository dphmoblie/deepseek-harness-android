import type { SessionId, SessionProjectionsBlock, SessionSummary } from '../api/types'

const TITLE_KEYS = ['title', 'session.title', 'session/title', 'sessionTitle'] as const
const MAX_DISPLAY_TITLE = 160

function cleanTitle(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const cleaned = Array.from(value, character => {
    const code = character.charCodeAt(0)
    return code <= 31 || code === 127 ? ' ' : character
  }).join('').replace(/\s+/g, ' ').trim()
  if (cleaned === '') return null
  return cleaned.slice(0, MAX_DISPLAY_TITLE)
}

/**
 * dsh 的投影集合是可扩展记录，不同后端版本可能把标题放在 title，
 * session.title，或 `{ title }` 包装值中。只接受短文本，React 渲染时继续转义。
 */
export function titleFromProjections(projections?: SessionProjectionsBlock): string | null {
  if (projections === undefined) return null
  for (const key of TITLE_KEYS) {
    const value = projections.values[key]
    const direct = cleanTitle(value)
    if (direct !== null) return direct
    if (typeof value === 'object' && value !== null) {
      const wrapped = cleanTitle((value as { title?: unknown; value?: unknown }).title)
        ?? cleanTitle((value as { value?: unknown }).value)
      if (wrapped !== null) return wrapped
    }
  }
  return null
}

export function titleFromProjectionFrame(key: string, value: unknown): string | null {
  if (!TITLE_KEYS.includes(key as (typeof TITLE_KEYS)[number])) return null
  return cleanTitle(value)
    ?? (typeof value === 'object' && value !== null
      ? cleanTitle((value as { title?: unknown; value?: unknown }).title)
        ?? cleanTitle((value as { value?: unknown }).value)
      : null)
}

export function sessionDisplayTitle(session: SessionSummary): string {
  return titleFromProjections(session.projections) ?? `会话 ${session.sessionId.slice(0, 8)}`
}

/** 取最近更新且未归档的会话；调用方在 null 时创建新会话。 */
export function selectRecentActiveSession(
  sessions: SessionSummary[],
  archivedSessionIds: SessionId[],
): SessionId | null {
  const archived = new Set(archivedSessionIds)
  let latest: SessionSummary | undefined
  for (const session of sessions) {
    if (archived.has(session.sessionId)) continue
    if (latest === undefined || session.updatedAt > latest.updatedAt) latest = session
  }
  return latest?.sessionId ?? null
}
