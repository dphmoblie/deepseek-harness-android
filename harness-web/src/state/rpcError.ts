import { RpcFailure, TransportError } from '../api/wire'
import { describeFailure } from '../errors'
import { failureReason } from './errorDisplay'

const UNKNOWN_FAILURE = '未提供详细原因'

/**
 * Convert an RPC/transport failure into a bounded, redacted message. Raw
 * objects are never stringified because their diagnostic payload may contain
 * credentials or other host details.
 */
export function rpcErrorMessage(action: string, failure: unknown): string {
  if (failure instanceof RpcFailure) {
    const translated = describeFailure(failure.code, failure.message)
    const safeServerMessage = failureReason(failure.message)
    // describeFailure deliberately returns the original server message for an
    // unknown code, so use its redacted form at this final display boundary.
    const summary = translated === failure.message
      ? safeServerMessage ?? 'Harness 请求被拒绝'
      : translated
    const detail = failureReason(failure.details) ?? safeServerMessage
    const message = detail !== null && detail !== summary
      ? `${summary}：${detail}`
      : summary
    const code = safeErrorCode(failure.code)
    return `${action}失败：${message}${code === null ? '' : `（错误代码：${code}）`}`
  }

  if (failure instanceof TransportError) {
    return `${action}失败：${failureReason(failure.message) ?? UNKNOWN_FAILURE}`
  }

  return `${action}失败：${failureReason(failure) ?? UNKNOWN_FAILURE}`
}

function safeErrorCode(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const code = value.trim()
  return /^[A-Za-z0-9][A-Za-z0-9._/-]{0,63}$/.test(code) ? code : null
}
