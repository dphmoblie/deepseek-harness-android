import { createHash } from 'node:crypto'

/**
 * 访客侧的「一轮任务收口」信号。
 *
 * dsh 的一轮任务在会话日志里以 `turn/end` 收口；原生外壳看不到访客内部的会话状态
 * （会话数据只在访客的 /root/.dsh/sessions 下），所以这里由访客插件订阅
 * `session/event`，把收口这件事**尽力而为**地经 `/device-command` 告知原生，
 * 由原生决定怎么提示用户（前台/后台判定在原生，这里不抑制）。
 *
 * 与原生收单点逐字一致的约定：
 * - 命令名固定 `notify-turn-complete`，信封沿用 `POST /device-command` + Bearer token；
 * - `param` 只有两种形态：`""`，或 `{"session":"<16 位小写十六进制>","reason":"turn-end"}`；
 * - `session` 只是会话标识的摘要，绝不是路径、cwd、会话标题或消息正文；
 * - best-effort：不重试、不抛错、不阻塞会话事件派发、不落盘。
 */
export const TURN_SIGNAL_COMMAND = 'notify-turn-complete'
export const TURN_SIGNAL_REASON = 'turn-end'
/** 短超时：这是后台通知，绝不能像工具调用那样占用 75 秒。 */
export const TURN_SIGNAL_TIMEOUT_MS = 2_000
/** `param` 总长上限（契约 ≤ 96 字符）。 */
export const TURN_SIGNAL_MAX_PARAM_CHARS = 96
/** 去重表上限：只记最近若干个会话，只存在内存里，永不落盘。 */
export const TURN_SIGNAL_MAX_SESSIONS = 256
/**
 * 事后补写／合成来源的收口。`interrupted` 只在 resume 时补写、`forked` 只出现在
 * fork seed 里，都不是「任务刚跑完」；命中它们不发通知，避免恢复会话时误报。
 * 其余 kind（`completed`、`error`、`aborted`、`blocked`、`max-tokens` 以及插件
 * 合并进来的自定义 kind）都算真实收口。
 */
const RETROSPECTIVE_TURN_REASONS = new Set(['interrupted', 'forked'])

/**
 * 会话标识的摘要：sha256 后取前 16 位小写十六进制。
 * 上报的值必须与「会话标识、路径、cwd、标题、正文」都不可逆，故一律走摘要。
 * @param {unknown} sessionId - 会话标识（`session.id`）。
 * @returns {string} 16 位小写十六进制；拿不到合法标识时返回空串。
 */
export function sessionDigest(sessionId) {
  if (typeof sessionId !== 'string' || sessionId === '') return ''
  return createHash('sha256').update(sessionId, 'utf8').digest('hex').slice(0, 16)
}

/**
 * 把摘要编成契约允许的 `param`。
 * @param {string} digest - `sessionDigest()` 的结果。
 * @returns {string} `""`，或极小 JSON；超出长度上限时退化为 `""`。
 */
export function turnSignalParam(digest) {
  if (typeof digest !== 'string' || digest === '') return ''
  const param = JSON.stringify({ session: digest, reason: TURN_SIGNAL_REASON })
  return param.length <= TURN_SIGNAL_MAX_PARAM_CHARS ? param : ''
}

/**
 * 去重表：按会话摘要记住「这个会话的哪个 turn 已经报过」，只放行没报过的。
 *
 * 键是摘要（不是路径/标题），值是最近报过的 turn 号——同一个 turn 的 `turn/end`
 * 重放会被吞掉，同一会话的**另一个** turn 仍然会通知。表有上限，超出后按插入顺序
 * 淘汰最旧的一条，靠 `delete` + `set` 把刚用过的键移到队尾。
 * @param {number} maxSessions - 表上限。
 */
export function createTurnSignalTable(maxSessions = TURN_SIGNAL_MAX_SESSIONS) {
  const seen = new Map()
  return {
    shouldSend(digest, turn) {
      if (seen.has(digest) && seen.get(digest) === turn) return false
      seen.delete(digest)
      seen.set(digest, turn)
      while (seen.size > maxSessions) seen.delete(seen.keys().next().value)
      return true
    },
    get size() {
      return seen.size
    },
  }
}

/**
 * 发一次通知，绝不抛出、绝不 await。
 * 端口/令牌缺失（`bridgeConfig()` 抛 DEVICE_BRIDGE_UNAVAILABLE）、`fetch` 同步抛错、
 * 请求超时或响应非 2xx 都只是放弃——不重试，也不影响调用方。
 */
function sendTurnSignal({ bridgeConfig, fetchImpl, digest }) {
  try {
    const { numericPort, token } = bridgeConfig()
    const request = fetchImpl(`http://127.0.0.1:${numericPort}/device-command`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ command: TURN_SIGNAL_COMMAND, param: turnSignalParam(digest) }),
      signal: AbortSignal.timeout(TURN_SIGNAL_TIMEOUT_MS),
      redirect: 'error',
    })
    // 不 await：会话事件的派发绝不被这个请求拖住，失败也没有人要接。
    Promise.resolve(request).catch(() => {})
  } catch {
    // 端口/令牌缺失、fetch 同步抛错、AbortSignal 不可用：一律当没发生。
  }
}

/**
 * 订阅会话事件并上报 `turn/end`。
 * @param {{ on?: Function }} ctx - 插件上下文（只需 `on`）。
 * @param {object} [options]
 * @param {Function} [options.bridgeConfig] - 返回 `{ numericPort, token }`，不合法时抛错。
 * @param {Function} [options.fetch] - 覆盖全局 fetch（测试用）。
 * @param {object} [options.table] - 覆盖去重表（测试用）。
 * @param {number} [options.maxSessions] - 去重表上限。
 * @returns {Function} 取消订阅的 disposer；参数不齐时返回空操作。
 */
export function installTurnSignal(ctx, options = {}) {
  const { bridgeConfig } = options
  if (typeof bridgeConfig !== 'function' || typeof ctx?.on !== 'function') return () => {}
  const fetchImpl = options.fetch ?? ((...args) => globalThis.fetch(...args))
  const table = options.table ?? createTurnSignalTable(options.maxSessions)
  return ctx.on('session/event', (session, event) => {
    if (event?.type !== 'turn/end') return
    const kind = event.data?.reason?.kind
    if (RETROSPECTIVE_TURN_REASONS.has(kind)) return
    const digest = sessionDigest(session?.id)
    if (!table.shouldSend(digest, event.data?.turn)) return
    sendTurnSignal({ bridgeConfig, fetchImpl, digest })
  })
}
