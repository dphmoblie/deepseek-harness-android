import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import {
  TURN_SIGNAL_COMMAND,
  TURN_SIGNAL_MAX_PARAM_CHARS,
  TURN_SIGNAL_MAX_SESSIONS,
  TURN_SIGNAL_REASON,
  TURN_SIGNAL_TIMEOUT_MS,
  createTurnSignalTable,
  installTurnSignal,
  sessionDigest,
  turnSignalParam,
} from './runtime-profile/plugins/dsh-mobile-shizuku/lib/turn-signal.js'

const PORT = 43119
const TOKEN = 'A'.repeat(43)
const SESSION_ID = 'session-9f2c1a77-4b0e-4c2f-8d51-0f7a3d9e6b12'
const DIGEST = createHash('sha256').update(SESSION_ID, 'utf8').digest('hex').slice(0, 16)

/** 假 ctx：只提供 `on`，并记录订阅，便于手动派发会话事件。 */
function fakeCtx() {
  const listeners = []
  const dispose = () => {}
  return {
    listeners,
    dispose,
    on(name, handler) {
      listeners.push({ name, handler })
      return dispose
    },
    // 返回 listener 的返回值：同步 listener 必须返回 undefined（不是 Promise）。
    emit(name, ...args) {
      let returned
      for (const listener of listeners) if (listener.name === name) returned = listener.handler(...args)
      return returned
    },
  }
}

/** 假 fetch：记录每次调用，立即成功，不联网。 */
function recordingFetch(respond = () => Promise.resolve({ ok: true, status: 200 })) {
  const calls = []
  return {
    calls,
    fetchImpl: (url, init) => {
      calls.push({ url, init })
      return respond()
    },
  }
}

const bridgeOk = () => ({ numericPort: PORT, token: TOKEN })

function turnEvent(type, slot, kind = 'completed') {
  return { type, seq: 7, time: 1, data: { turn: slot, reason: { kind } } }
}

test('turn/end 上报一次：命令名、信封与 param 形态逐字符合契约', () => {
  const ctx = fakeCtx()
  const { calls, fetchImpl } = recordingFetch()
  installTurnSignal(ctx, { bridgeConfig: bridgeOk, fetch: fetchImpl })

  ctx.emit('session/event', { id: SESSION_ID }, turnEvent('turn/end', 3))

  assert.equal(calls.length, 1)
  const [call] = calls
  assert.equal(call.url, `http://127.0.0.1:${PORT}/device-command`)
  assert.equal(call.init.method, 'POST')
  assert.equal(call.init.redirect, 'error')
  assert.equal(call.init.headers['Content-Type'], 'application/json')
  assert.equal(call.init.headers.Authorization, `Bearer ${TOKEN}`)

  const body = JSON.parse(call.init.body)
  assert.deepEqual(Object.keys(body), ['command', 'param'])
  assert.equal(body.command, 'notify-turn-complete')
  assert.equal(body.command, TURN_SIGNAL_COMMAND)
  assert.ok(body.param.length <= TURN_SIGNAL_MAX_PARAM_CHARS)
  assert.equal(body.param, `{"session":"${DIGEST}","reason":"turn-end"}`)

  const parsed = JSON.parse(body.param)
  assert.deepEqual(parsed, { session: DIGEST, reason: TURN_SIGNAL_REASON })
  assert.match(parsed.session, /^[0-9a-f]{16}$/u)
  assert.equal(parsed.session.length, 16)
  // 摘要不可含会话标识本身，也不得夹带路径、cwd 或消息正文。
  assert.equal(body.param.includes(SESSION_ID), false)
  assert.equal(body.param.includes('session-'), false)
  assert.equal(body.param.includes('/'), false)
  assert.equal(body.param.includes('\\'), false)
  assert.equal(body.param.includes('D:'), false)
})

test('同一会话同一个 turn 的 turn/end 重放不会被重复发送', () => {
  const ctx = fakeCtx()
  const { calls, fetchImpl } = recordingFetch()
  installTurnSignal(ctx, { bridgeConfig: bridgeOk, fetch: fetchImpl })
  const session = { id: SESSION_ID }

  ctx.emit('session/event', session, turnEvent('turn/end', 3))
  ctx.emit('session/event', session, turnEvent('turn/end', 3))
  ctx.emit('session/event', session, turnEvent('turn/end', 3))

  assert.equal(calls.length, 1)
})

test('同一会话的另一个 turn 仍会通知（去重键是摘要，值为 turn 号）', () => {
  const ctx = fakeCtx()
  const { calls, fetchImpl } = recordingFetch()
  installTurnSignal(ctx, { bridgeConfig: bridgeOk, fetch: fetchImpl })
  const session = { id: SESSION_ID }

  ctx.emit('session/event', session, turnEvent('turn/end', 1))
  ctx.emit('session/event', session, turnEvent('turn/end', 2))
  // 第二个会话独立计数。
  ctx.emit('session/event', { id: 'session-other' }, turnEvent('turn/end', 1))

  assert.equal(calls.length, 3)
  assert.equal(calls[0].init.body, calls[1].init.body)
})

test('turn/start 与其它会话事件都不发通知', () => {
  const ctx = fakeCtx()
  const { calls, fetchImpl } = recordingFetch()
  installTurnSignal(ctx, { bridgeConfig: bridgeOk, fetch: fetchImpl })
  const session = { id: SESSION_ID }

  ctx.emit('session/event', session, turnEvent('turn/start', 1))
  ctx.emit('session/event', session, { type: 'step/end', seq: 8, time: 2, data: { turn: 1, step: 1 } })
  ctx.emit('session/event', session, { type: 'assistant/message', seq: 9, time: 3, data: {} })

  assert.equal(calls.length, 0)
})

test('resume 补写与 fork seed 的收口不发通知，避免恢复会话时误报', () => {
  const ctx = fakeCtx()
  const { calls, fetchImpl } = recordingFetch()
  installTurnSignal(ctx, { bridgeConfig: bridgeOk, fetch: fetchImpl })
  const session = { id: SESSION_ID }

  ctx.emit('session/event', session, turnEvent('turn/end', 1, 'interrupted'))
  ctx.emit('session/event', session, turnEvent('turn/end', 2, 'forked'))
  // 真实收口（包括报错与取消）照常通知。
  ctx.emit('session/event', session, turnEvent('turn/end', 3, 'error'))
  ctx.emit('session/event', session, turnEvent('turn/end', 4, 'aborted'))

  assert.equal(calls.length, 2)
})

test('端口或令牌缺失（bridgeConfig 抛 DEVICE_BRIDGE_UNAVAILABLE）时静默放弃', () => {
  const ctx = fakeCtx()
  const { calls, fetchImpl } = recordingFetch()
  const bridgeConfig = () => {
    throw new Error('DEVICE_BRIDGE_UNAVAILABLE')
  }
  installTurnSignal(ctx, { bridgeConfig, fetch: fetchImpl })

  assert.doesNotThrow(() => {
    ctx.emit('session/event', { id: SESSION_ID }, turnEvent('turn/end', 1))
  })
  assert.equal(calls.length, 0)
})

test('fetch 同步抛错或异步拒绝都不外泄，也不影响派发', async () => {
  const syncCtx = fakeCtx()
  let syncCalls = 0
  installTurnSignal(syncCtx, {
    bridgeConfig: bridgeOk,
    fetch: () => {
      syncCalls += 1
      throw new Error('network down')
    },
  })
  assert.doesNotThrow(() => {
    syncCtx.emit('session/event', { id: SESSION_ID }, turnEvent('turn/end', 1))
  })
  assert.equal(syncCalls, 1)

  const asyncCtx = fakeCtx()
  let asyncCalls = 0
  installTurnSignal(asyncCtx, {
    bridgeConfig: bridgeOk,
    fetch: () => {
      asyncCalls += 1
      return Promise.reject(new Error('network down'))
    },
  })
  assert.doesNotThrow(() => {
    asyncCtx.emit('session/event', { id: 'session-async' }, turnEvent('turn/end', 1))
  })
  await new Promise(resolve => setTimeout(resolve, 5))
  assert.equal(asyncCalls, 1)

  // 全局 fetch 缺失时同样只是放弃。
  const missingCtx = fakeCtx()
  const originalFetch = globalThis.fetch
  globalThis.fetch = undefined
  try {
    installTurnSignal(missingCtx, { bridgeConfig: bridgeOk })
    assert.doesNotThrow(() => {
      missingCtx.emit('session/event', { id: 'session-nofetch' }, turnEvent('turn/end', 1))
    })
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('请求超时限制在 2 秒量级，且派发不被请求拖住', t => {
  const originalTimeout = AbortSignal.timeout
  const recorded = []
  AbortSignal.timeout = ms => {
    recorded.push(ms)
    return originalTimeout.call(AbortSignal, ms)
  }
  t.after(() => {
    AbortSignal.timeout = originalTimeout
  })

  const ctx = fakeCtx()
  const hanging = recordingFetch(() => new Promise(() => {}))
  installTurnSignal(ctx, { bridgeConfig: bridgeOk, fetch: hanging.fetchImpl })

  assert.equal(TURN_SIGNAL_TIMEOUT_MS, 2_000)
  const returned = ctx.emit('session/event', { id: SESSION_ID }, turnEvent('turn/end', 1))

  // 同步 listener：返回 undefined（不是 Promise），且请求已经同步发出，不 await。
  assert.equal(returned, undefined)
  assert.equal(hanging.calls.length, 1)
  assert.deepEqual(recorded, [2_000])
  assert.ok(hanging.calls[0].init.signal instanceof AbortSignal)
})

test('去重表有上限，淘汰最旧的一条后不会无限增长', () => {
  const table = createTurnSignalTable(2)
  table.shouldSend('a', 1)
  table.shouldSend('b', 1)
  table.shouldSend('c', 1)
  assert.equal(table.size, 2)
  // 'a' 已被淘汰：同一个 turn 重放会重新放行（只影响内存，不写盘）。
  assert.equal(table.shouldSend('a', 1), true)
  assert.equal(table.size, 2)
  assert.equal(table.shouldSend('c', 1), false)

  const ctx = fakeCtx()
  const { calls, fetchImpl } = recordingFetch()
  const bounded = createTurnSignalTable(4)
  installTurnSignal(ctx, { bridgeConfig: bridgeOk, fetch: fetchImpl, table: bounded })
  for (let index = 0; index < 50; index += 1) {
    ctx.emit('session/event', { id: `session-${index}` }, turnEvent('turn/end', 1))
  }
  assert.equal(calls.length, 50)
  assert.equal(bounded.size, 4)
  assert.equal(TURN_SIGNAL_MAX_SESSIONS, 256)
})

test('拿不到会话标识时退化为 param=""，仍尽力上报一次', () => {
  const ctx = fakeCtx()
  const { calls, fetchImpl } = recordingFetch()
  installTurnSignal(ctx, { bridgeConfig: bridgeOk, fetch: fetchImpl })

  ctx.emit('session/event', undefined, turnEvent('turn/end', 1))
  ctx.emit('session/event', {}, turnEvent('turn/end', 1))

  assert.equal(calls.length, 1)
  assert.equal(JSON.parse(calls[0].init.body).param, '')
  assert.equal(sessionDigest(''), '')
  assert.equal(sessionDigest(undefined), '')
  assert.equal(turnSignalParam(''), '')
  assert.equal(turnSignalParam('0123456789abcdef').length <= TURN_SIGNAL_MAX_PARAM_CHARS, true)
})

test('installTurnSignal 复用 ctx.on 的 disposer；参数不齐时是空操作', () => {
  const ctx = fakeCtx()
  const dispose = installTurnSignal(ctx, { bridgeConfig: bridgeOk, fetch: () => Promise.resolve({}) })
  assert.equal(dispose, ctx.dispose)
  assert.equal(typeof installTurnSignal(ctx, {}), 'function')
  assert.equal(typeof installTurnSignal({}, { bridgeConfig: bridgeOk }), 'function')
  assert.equal(typeof installTurnSignal(undefined, { bridgeConfig: bridgeOk }), 'function')
})
