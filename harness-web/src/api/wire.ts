/**
 * dsh wire 传输层：单播 RPC（fetch POST /api/<method>）、应答（/api/respond）、
 * 下行事件流（WebSocket /api/events.mux|host，只读）。
 *
 * 错误分两类：
 * - TransportError：载体层失败（网络错误、非 2xx 状态码、响应信封畸形）；
 * - RpcFailure：业务失败（HTTP 200 且 result.ok=false，携带 code/message/details）。
 *
 * 鉴权说明：Android WebView 对 401 Basic 挑战由 onReceivedHttpAuthRequest
 * 透明处理；WebSocket 握手的鉴权依赖宿主注入的 Cookie（见 mobile-auth-preload），
 * 本层不携带任何凭据。
 */

import type {
  ApprovalResponsePayload,
  ClientResponse,
  DshRpcMethods,
  QuestionResponsePayload,
  RpcError,
  RpcMethodName,
  RpcResult,
  ServerRequest,
  ServerResponse,
} from './types'

/** 载体层失败：请求未到达业务层，或响应信封不合法。 */
export class TransportError extends Error {
  readonly status?: number

  constructor(message: string, status?: number) {
    super(message)
    this.name = 'TransportError'
    this.status = status
  }
}

/** 业务失败：服务端返回了结构化的 RpcError。 */
export class RpcFailure extends Error {
  readonly code: string
  readonly details: Record<string, unknown>

  constructor(error: RpcError) {
    super(error.message)
    this.name = 'RpcFailure'
    this.code = error.code
    this.details = error.details
  }
}

/** 传输依赖注入点（默认走全局 fetch/WebSocket，测试可替换）。 */
export type WireDeps = {
  fetchFn?: typeof fetch
  webSocketFactory?: (url: string) => WebSocket
  randomId?: () => string
}

const API_PREFIX = '/api'
const MUX_EVENTS_PATH = `${API_PREFIX}/events.mux`
const HOST_EVENTS_PATH = `${API_PREFIX}/events.host`
const RESPOND_PATH = `${API_PREFIX}/respond`

/** 生成 rpcId（不透明回显令牌，无格式约束）。 */
export function newRpcId(randomId: () => string = () => crypto.randomUUID()): string {
  return randomId()
}

/** 解析单播响应正文；信封畸形返回 null（调用方转为 TransportError）。 */
export function parseServerResponse(raw: string): ServerResponse | null {
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof value !== 'object' || value === null) return null
  const envelope = value as Record<string, unknown>
  if (envelope.type !== 'server-response') return null
  if (typeof envelope.rpcId !== 'string') return null
  const result = envelope.result as Record<string, unknown> | null | undefined
  if (typeof result !== 'object' || result === null) return null
  const ok = result.ok
  if (ok === true) return envelope as unknown as ServerResponse
  if (ok === false && typeof result.error === 'object' && result.error !== null) {
    return envelope as unknown as ServerResponse
  }
  return null
}

/** 解析 WebSocket 文本帧；信封畸形返回 null（调用方跳过并告警）。 */
export function parseServerFrame(raw: string): ServerRequest | null {
  if (raw.length > 8 * 1024 * 1024) return null
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof value !== 'object' || value === null) return null
  const envelope = value as Record<string, unknown>
  if (envelope.type !== 'server-request') return null
  if (typeof envelope.rpcId !== 'string' || envelope.rpcId.length === 0 || envelope.rpcId.length > 512) return null
  if (typeof envelope.method !== 'string' || !/^[A-Za-z0-9._/-]{1,128}$/.test(envelope.method)) return null
  if (typeof envelope.payload !== 'object' || envelope.payload === null || Array.isArray(envelope.payload)) return null
  const payload = envelope.payload as Record<string, unknown>
  if (typeof payload.type !== 'string' || !/^[A-Za-z0-9._/-]{1,128}$/.test(payload.type)) return null
  if (envelope.method !== payload.type) return null
  return envelope as unknown as ServerRequest
}

/** 把业务结果拆成成功值或抛出 RpcFailure。 */
function unwrapResult<T>(result: RpcResult<unknown>): T {
  if (result.ok) return result.value as T
  throw new RpcFailure(result.error)
}

/**
 * 单播 RPC：POST /api/<method>。
 * 载体层错误（非 2xx、信封畸形、rpcId 不匹配）抛 TransportError，
 * 业务失败抛 RpcFailure。
 */
export async function callUnary<M extends RpcMethodName>(
  baseUrl: string,
  method: M,
  payload: DshRpcMethods[M]['payload'],
  options: { signal?: AbortSignal; deps?: WireDeps } = {},
): Promise<DshRpcMethods[M]['value']> {
  const { signal, deps } = options
  const fetchFn = deps?.fetchFn ?? globalThis.fetch
  const randomId = deps?.randomId ?? (() => crypto.randomUUID())
  const rpcId = newRpcId(randomId)
  const envelope = { type: 'client-request', rpcId, method, payload }

  let response: Response
  try {
    response = await fetchFn(new URL(`${API_PREFIX}/${method}`, baseUrl), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(envelope),
      signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new TransportError('无法连接 Harness 后端：网络不可用、服务未启动或连接被拒绝')
  }
  if (!response.ok) {
    throw new TransportError(`Harness 后端返回 HTTP ${response.status}`, response.status)
  }
  const parsed = parseServerResponse(await response.text())
  if (parsed === null) {
    throw new TransportError('Harness 后端响应格式无效')
  }
  if (parsed.rpcId !== rpcId) {
    throw new TransportError('Harness 后端响应 rpcId 不匹配')
  }
  return unwrapResult<DshRpcMethods[M]['value']>(parsed.result)
}

/** 应答 pending 的审批或提问（POST /api/respond，client-response 信封）。 */
export async function sendResponse(
  baseUrl: string,
  rpcId: string,
  value: ApprovalResponsePayload | QuestionResponsePayload,
  options: { signal?: AbortSignal; deps?: WireDeps } = {},
): Promise<void> {
  const { signal, deps } = options
  const fetchFn = deps?.fetchFn ?? globalThis.fetch
  const envelope: ClientResponse = {
    type: 'client-response',
    rpcId,
    result: { ok: true, value },
  }
  let response: Response
  try {
    response = await fetchFn(new URL(RESPOND_PATH, baseUrl), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(envelope),
      signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new TransportError('无法连接 Harness 后端：网络不可用、服务未启动或连接被拒绝')
  }
  if (!response.ok) {
    throw new TransportError(`Harness 后端返回 HTTP ${response.status}`, response.status)
  }
}

/**
 * 打开下行事件流（/api/events.mux 或 /api/events.host），产出服务端请求帧。
 * 流是只读的：绝不能向 socket 发送任何数据（服务端视客户端消息为协议违规，
 * 会以 1008 关闭连接）。服务端正常关闭时迭代结束；连接失败抛 TransportError。
 */
export async function* openEventStream(
  baseUrl: string,
  path: typeof MUX_EVENTS_PATH | typeof HOST_EVENTS_PATH,
  options: { signal?: AbortSignal; deps?: WireDeps; onOpen?: () => void } = {},
): AsyncGenerator<ServerRequest> {
  const { signal, deps, onOpen } = options
  const webSocketFactory = deps?.webSocketFactory ?? ((url: string) => new WebSocket(url))
  const url = new URL(path, baseUrl)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'

  if (signal?.aborted) return

  const socket = webSocketFactory(url.toString())
  const inbox: ({ kind: 'frame'; frame: ServerRequest } | { kind: 'end' })[] = []
  let wake: (() => void) | undefined
  let failure: Error | undefined
  let opened = false

  const enqueue = (item: { kind: 'frame'; frame: ServerRequest } | { kind: 'end' }): void => {
    inbox.push(item)
    wake?.()
    wake = undefined
  }

  const handleOpen = (): void => {
    opened = true
    onOpen?.()
  }
  const handleMessage = (event: MessageEvent): void => {
    if (typeof event.data !== 'string') return
    const frame = parseServerFrame(event.data)
    if (frame === null) {
      failure = new TransportError('Harness 事件流收到格式无效的下行数据')
      enqueue({ kind: 'end' })
      return
    }
    enqueue({ kind: 'frame', frame })
  }
  const handleClose = (event: CloseEvent): void => {
    if (!signal?.aborted && opened && event.code !== 1000 && failure === undefined) {
      const detail = event.reason.trim()
      failure = new TransportError(
        detail === ''
          ? `Harness 事件流异常关闭（代码 ${event.code}）`
          : `Harness 事件流异常关闭（代码 ${event.code}）：${detail}`,
      )
    }
    enqueue({ kind: 'end' })
  }
  const handleError = (): void => {
    if (socket.readyState === WebSocket.CONNECTING) {
      failure = new TransportError('无法建立 Harness 事件流连接')
      enqueue({ kind: 'end' })
      return
    }
    if (!signal?.aborted && failure === undefined) {
      failure = new TransportError('Harness 事件流连接意外中断')
      enqueue({ kind: 'end' })
    }
  }
  const handleAbort = (): void => {
    if (socket.readyState === WebSocket.CONNECTING || socket.readyState === WebSocket.OPEN) {
      socket.close()
    }
  }

  socket.addEventListener('open', handleOpen)
  socket.addEventListener('message', handleMessage)
  socket.addEventListener('close', handleClose, { once: true })
  socket.addEventListener('error', handleError)
  signal?.addEventListener('abort', handleAbort, { once: true })

  try {
    while (true) {
      while (inbox.length > 0) {
        const item = inbox.shift()
        if (item === undefined) break
        if (item.kind === 'end') {
          if (failure !== undefined) throw failure
          return
        }
        yield item.frame
      }
      await new Promise<void>((resolve) => {
        wake = resolve
      })
    }
  } finally {
    signal?.removeEventListener('abort', handleAbort)
    socket.removeEventListener('open', handleOpen)
    socket.removeEventListener('message', handleMessage)
    socket.removeEventListener('close', handleClose)
    socket.removeEventListener('error', handleError)
    handleAbort()
  }
}

export { API_PREFIX, HOST_EVENTS_PATH, MUX_EVENTS_PATH, RESPOND_PATH }
