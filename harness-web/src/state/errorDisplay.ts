/**
 * 把后端保留的结构化失败投影成适合直接展示的短文本。
 * 原始诊断仍留在会话日志中；这里仅允许白名单字段进入界面，并在展示前脱敏。
 */

import type { FinishReason, ModelCatalogFailure, SessionEvent } from '../api/types'
import { stripUnsafeDiagnosticControls } from './textSafety'

export const MAX_ERROR_REASON_LENGTH = 240

const MAX_DIAGNOSTIC_INPUT = 4_096
const FALLBACK_REASON = '未提供详细原因'
const HIDDEN = '[已隐藏]'

const MESSAGE_FIELDS = ['message', 'detail', 'description', 'error_description'] as const
const NESTED_FAILURE_FIELDS = ['error', 'failure', 'cause', 'details'] as const
const PLACEHOLDER_MESSAGES = new Set([
  '本轮因错误终止',
  '本轮运行失败',
  '本轮因错误结束',
  '本轮以错误结束',
])

function recordOf(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : null
}

function readField(record: Record<string, unknown>, field: string): unknown {
  try {
    return record[field]
  } catch {
    return undefined
  }
}

function textField(record: Record<string, unknown>, field: string): string | null {
  const value = readField(record, field)
  return typeof value === 'string' ? value : null
}

function safeCode(record: Record<string, unknown>): string | null {
  const value = textField(record, 'code')?.trim()
  if (value === undefined || value === null || !/^[A-Za-z0-9][A-Za-z0-9._/-]{0,63}$/.test(value)) return null
  return value
}

function safeStatus(record: Record<string, unknown>): number | null {
  const value = readField(record, 'status')
  return typeof value === 'number' && Number.isInteger(value) && value >= 100 && value <= 599 ? value : null
}

function truncate(text: string): string {
  const characters = [...text]
  if (characters.length <= MAX_ERROR_REASON_LENGTH) return text
  return `${characters.slice(0, MAX_ERROR_REASON_LENGTH - 1).join('')}…`
}

function redactSensitiveHeaders(text: string): string {
  return text
    // Authorization 可包含 Digest 参数或自定义多段方案，必须隐藏到当前行末。
    .replace(/\b((?:proxy-)?authorization\s*:\s*)[^\r\n]*/gi, `$1${HIDDEN}`)
    // Cookie 属性以分号分隔，逐字段处理容易遗漏，故隐藏到当前行末。
    .replace(/\b((?:set-)?cookie\s*:\s*)[^\r\n]*/gi, `$1${HIDDEN}`)
}

function redact(text: string): string {
  return text
    // 先处理带空格的认证头，避免后续字段规则只隐藏 Bearer/Basic 单词。
    .replace(/\b(Bearer|Basic)\s+[A-Za-z0-9+/=._~-]{4,}/gi, `$1 ${HIDDEN}`)
    .replace(
      /((?:["']?(?:api[-_ ]?key|access[-_ ]?token|refresh[-_ ]?token|id[-_ ]?token|token|auth(?:orization)?|cookie|credential|pass(?:word|wd)?|secret|signature)["']?)\s*[:=]\s*)(?!\[已隐藏\])(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;}\]]+)/gi,
      `$1${HIDDEN}`,
    )
    .replace(/([?&](?:api[-_]?key|access[-_]?token|refresh[-_]?token|token|password|secret|signature)=)[^&#\s]+/gi, `$1${HIDDEN}`)
    .replace(/\b([a-z][a-z0-9+.-]*:\/\/[^\s/:@]+:)[^\s/@]+@/gi, `$1${HIDDEN}@`)
    .replace(/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g, HIDDEN)
    .replace(/\b(?:sk|rk|pk)-[A-Za-z0-9_-]{12,}\b/gi, HIDDEN)
    .replace(/\bAKIA[0-9A-Z]{16}\b/g, HIDDEN)
    .replace(/\b(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})\b/g, '[私网地址]')
    .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, '[邮箱已隐藏]')
    .replace(/(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)/g, '[手机号已隐藏]')
    .replace(
      /(?<!\d)(?:\d{6}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx]|\d{15})(?!\d)/g,
      '[身份证号已隐藏]',
    )
    .replace(/\b[A-Za-z]:\\Users\\[^\\\s]+/gi, '%USERPROFILE%')
    .replace(/\/(?:home|Users)\/[^/\s]+/g, '$HOME')
}

function parseStructuredText(text: string, seen: Set<unknown>, depth: number): string | null {
  const candidate = text.trim()
  if (!candidate.startsWith('{') && !candidate.startsWith('[')) return null
  try {
    return extractReason(JSON.parse(candidate), seen, depth + 1)
  } catch {
    return null
  }
}

function cleanDiagnostic(value: string, seen: Set<unknown>, depth: number): string | null {
  // eslint-disable-next-line no-control-regex -- ANSI CSI sequences begin with the ESC control byte.
  const withoutAnsi = value.slice(0, MAX_DIAGNOSTIC_INPUT).replace(/\u001B\[[0-?]*[ -/]*[@-~]/g, '')
  const bounded = stripUnsafeDiagnosticControls(withoutAnsi).trim()
  if (bounded.length === 0) return null

  const withoutPrefix = bounded.replace(/^(?:[A-Za-z_$][\w.$-]*(?:Error|Exception)|Error)\s*:\s*/i, '').trim()
  const normalizedPlaceholder = withoutPrefix.replace(/[\s。.!！:：]+$/g, '')
  if (PLACEHOLDER_MESSAGES.has(normalizedPlaceholder)) return null
  const looksLikeStructuredValue = withoutPrefix.startsWith('{')
    || /^\[\s*(?:[{"\d]|true\b|false\b|null\b)/.test(withoutPrefix)
  const structured = parseStructuredText(withoutPrefix, seen, depth)
  if (structured !== null) return structured
  // 畸形或无白名单消息字段的对象不能回退为整段 JSON 展示。
  if (looksLikeStructuredValue) return null

  const lines = redactSensitiveHeaders(withoutPrefix)
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line.length > 0)
  const pythonTraceback = lines.some(line => /^Traceback \(most recent call last\):$/i.test(line))
  const nonStackLines = lines.filter(line => !/^(?:Traceback \(most recent call last\):|at\s+|File\s+".*",\s+line\s+\d+|\.\.\. \d+ more$|Suppressed:\s*|goroutine\s+\d+|\d+:\s+0x[\da-f]+)/i.test(line))
  // Python 把异常摘要放在 traceback 末尾；其他运行时通常放在第一行。
  const meaningfulLines = pythonTraceback ? nonStackLines.slice(-1) : nonStackLines.slice(0, 2)
  if (meaningfulLines.length === 0) return null

  const withoutInlineStack = meaningfulLines.join(' ')
    .replace(/\s+at\s+(?:new\s+)?[\w$.[\]<>]+\s*\([^)]*\.(?:[cm]?[jt]sx?|java|kt|py):\d+(?::\d+)?\).*$/i, '')
  const cleaned = redact(withoutInlineStack).replace(/\s+/g, ' ').trim()
  if (cleaned.length === 0 || cleaned === '[object Object]') return null
  const normalizedCleaned = cleaned.replace(/[\s。.!！:：]+$/g, '')
  if (PLACEHOLDER_MESSAGES.has(normalizedCleaned)) return null
  return truncate(cleaned)
}

function extractReason(value: unknown, seen: Set<unknown>, depth: number): string | null {
  if (depth > 4) return null
  if (typeof value === 'string') return cleanDiagnostic(value, seen, depth)
  const record = recordOf(value)
  if (record === null || seen.has(value)) return null
  seen.add(value)

  const code = safeCode(record)
  if (code !== null && /^(?:AUTH|UNAUTHORIZED|INVALID_API_KEY)$/i.test(code)) return 'API 密钥无效'

  for (const field of MESSAGE_FIELDS) {
    const message = textField(record, field)
    if (message === null) continue
    const cleaned = cleanDiagnostic(message, seen, depth)
    if (cleaned !== null) return cleaned
  }
  for (const field of NESTED_FAILURE_FIELDS) {
    const nested = extractReason(readField(record, field), seen, depth + 1)
    if (nested !== null) return nested
  }

  const status = safeStatus(record)
  if (code !== null && code !== 'UNKNOWN') return `错误代码 ${code}`
  if (status !== null) return `HTTP ${status}`
  return null
}

/** 从任意受支持的失败载荷中提取安全、可读的原因；绝不直接 stringify 对象。 */
export function failureReason(value: unknown): string | null {
  return extractReason(value, new Set<unknown>(), 0)
}

/** 从模型流 finish 分块中提取原因，可作为缺少细节的 turn/end 的回退。 */
export function finishFailureReason(reason: FinishReason | undefined): string | null {
  if (reason === undefined || (reason.kind !== 'error' && reason.kind !== 'aborted')) return null
  const record = recordOf(reason)
  return record === null
    ? null
    : failureReason(readField(record, 'failure')) ?? failureReason(readField(record, 'error'))
}

/** 生成统一的轮次失败通知。 */
export function turnFailureNotice(reason: unknown): string {
  return `本轮运行失败：${failureReason(reason) ?? FALLBACK_REASON}`
}

/** host/agent-error 是没有会话事件坐标的实时轮次失败，使用相同的通知格式。 */
export function agentErrorNotice(message: unknown): string {
  return turnFailureNotice(message)
}

/** stream/error 属于连接层，不冒充轮次事件，但仍展示服务端给出的具体原因。 */
export function streamErrorNotice(error: unknown): string {
  return `Harness 事件流异常：${failureReason(error) ?? FALLBACK_REASON}`
}

/** 从 turn/end 事件生成轮次失败通知，必要时使用模型流 finish 原因补足细节。 */
export function turnEndFailureNotice(event: SessionEvent, finishReason?: FinishReason): string | null {
  if (event.type !== 'turn/end') return null
  const reason = recordOf(event.data.reason)
  if (reason === null || readField(reason, 'kind') !== 'error') return null
  const detail = failureReason(readField(reason, 'error'))
    ?? failureReason(readField(reason, 'failure'))
    ?? failureReason(reason)
    ?? failureReason(event.data.error)
    ?? failureReason(event.data.failure)
    ?? finishFailureReason(finishReason)
  return `本轮运行失败：${detail ?? FALLBACK_REASON}`
}

/** 汇总模型目录中的提供商失败；标签与原因均经过同一展示边界。 */
export function modelCatalogFailureNotice(failures: ModelCatalogFailure[]): string | null {
  if (failures.length === 0) return null
  const visible = failures.slice(0, 3).map((failure) => {
    const provider = safeProviderLabel(failure.name) ?? safeProviderLabel(failure.id) ?? '未知提供商'
    const reason = failureReason(failure) ?? FALLBACK_REASON
    return `${provider}：${reason}`
  })
  const remaining = failures.length - visible.length
  return `模型目录加载失败：${visible.join('；')}${remaining > 0 ? `；另有 ${remaining} 个提供商失败` : ''}`
}

function safeProviderLabel(value: unknown): string | null {
  const label = failureReason(typeof value === 'string' ? { message: value } : value)
  if (label === null) return null
  return [...label].slice(0, 64).join('')
}
