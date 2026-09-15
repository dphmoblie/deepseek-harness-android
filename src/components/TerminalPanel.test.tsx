import { act, render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createBrowserBridge } from '../platform/browser'
import { saveLanguage } from '../i18n'
import type { RuntimeBridge, TerminalChunk, TerminalExit } from '../platform/types'
import { TerminalPanel } from './TerminalPanel'

/**
 * xterm 的替身。
 *
 * jsdom 里没有 Canvas，真实 Terminal 无法渲染；而这里要验证的是**组件按什么顺序、
 * 把哪些字节交给终端**，与渲染无关。替身把每次 write / writeln 按调用顺序排进一条队列，
 * 断言直接比对交付出去的字节流，而不是只看某个内部数组的长度。
 */
const xtermSpy = vi.hoisted(() => ({
  events: [] as Array<{ kind: 'write'; bytes: Uint8Array } | { kind: 'exit'; text: string }>,
  dataHandlers: new Set<(data: string) => void>(),
}))

vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    cols = 80
    rows = 24
    loadAddon(): void {}
    open(): void {}
    focus(): void {}
    dispose(): void {}
    clearSelection(): void {}
    selectAll(): void {}
    getSelection(): string { return '' }
    write(data: Uint8Array | string): void {
      xtermSpy.events.push({
        kind: 'write',
        bytes: typeof data === 'string' ? new TextEncoder().encode(data) : data,
      })
    }
    writeln(data: string): void {
      xtermSpy.events.push({ kind: 'exit', text: data })
    }
    onData(handler: (data: string) => void): { dispose: () => void } {
      xtermSpy.dataHandlers.add(handler)
      return { dispose: () => { xtermSpy.dataHandlers.delete(handler) } }
    }
  },
}))

vi.mock('@xterm/addon-fit', () => ({ FitAddon: class { fit(): void {} } }))

/** 浏览器里没有 ResizeObserver：替身只满足构造与释放，不触发任何回调。 */
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

/**
 * 在 act 里推进一次握手状态，并让出一个微任务。
 *
 * `createTerminal` 的续体（补写缓冲、开启输入）挂在同一条 Promise 链上，
 * 必须让它先跑完，断言才能看到交付结果；`act` 的异步回调里因此需要一次真实的 await。
 */
async function settleAct(action: () => void): Promise<void> {
  await act(async () => {
    action()
    await Promise.resolve()
  })
}

interface Handshake {
  bridge: RuntimeBridge
  /** createTerminal 是否已经被调用；为 true 说明两个监听器都已注册，握手窗口已打开。 */
  hasStarted: () => boolean
  /** 原生侧推来一段输出（按原生一样的 base64 载荷）。 */
  emitOutput: (sessionId: string, text: string) => void
  /** 原生侧推来终态事件。 */
  emitExit: (sessionId: string, exitCode: number) => void
  /** 让 createTerminal 成功返回，握手窗口结束。 */
  settle: (sessionId: string) => Promise<void>
  /** 让 createTerminal 失败。 */
  fail: (error: Error) => Promise<void>
  /** 模拟用户在终端里打字（走组件的 onData 回调）。 */
  type: (data: string) => void
  writeTerminal: (sessionId: string, dataBase64: string) => Promise<void>
}

function toBase64(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let binary = ''
  for (let index = 0; index < bytes.length; index += 8192) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 8192))
  }
  return btoa(binary)
}

/**
 * 假桥接：createTerminal 返回一个由用例控制的 Promise，从而复现
 * 「原生已经在吐输出、JS 还没拿到 sessionId」的握手窗口。
 */
function createHandshake(): Handshake {
  const outputListeners = new Set<(event: TerminalChunk) => void>()
  const exitListeners = new Set<(event: TerminalExit) => void>()
  let started = false
  let settleCreate: ((session: { sessionId: string }) => void) | undefined
  let rejectCreate: ((error: Error) => void) | undefined
  const created = new Promise<{ sessionId: string }>((resolve, reject) => {
    settleCreate = resolve
    rejectCreate = reject
  })
  const writeTerminal = vi.fn(() => Promise.resolve())
  const bridge: RuntimeBridge = {
    ...createBrowserBridge(),
    createTerminal: () => {
      started = true
      return created
    },
    writeTerminal,
    addTerminalOutputListener: listener => {
      outputListeners.add(listener)
      return Promise.resolve({
        remove: () => {
          outputListeners.delete(listener)
          return Promise.resolve()
        },
      })
    },
    addTerminalExitListener: listener => {
      exitListeners.add(listener)
      return Promise.resolve({
        remove: () => {
          exitListeners.delete(listener)
          return Promise.resolve()
        },
      })
    },
  }
  return {
    bridge,
    writeTerminal,
    hasStarted: () => started,
    emitOutput: (sessionId, text) => {
      outputListeners.forEach(listener => listener({ sessionId, dataBase64: toBase64(text) }))
    },
    emitExit: (sessionId, exitCode) => {
      exitListeners.forEach(listener => listener({ sessionId, exitCode }))
    },
    settle: sessionId => settleAct(() => { settleCreate?.({ sessionId }) }),
    fail: error => settleAct(() => { rejectCreate?.(error) }),
    type: data => {
      xtermSpy.dataHandlers.forEach(handler => handler(data))
    },
  }
}

/** 交付给终端的全部输出字节，按交付顺序拼接。 */
function writtenBytes(): Uint8Array {
  const chunks = xtermSpy.events.flatMap(event => (event.kind === 'write' ? [event.bytes] : []))
  const merged = new Uint8Array(chunks.reduce((total, chunk) => total + chunk.length, 0))
  let offset = 0
  for (const chunk of chunks) {
    merged.set(chunk, offset)
    offset += chunk.length
  }
  return merged
}

function writtenText(): string {
  return new TextDecoder().decode(writtenBytes())
}

function exitLines(): string[] {
  return xtermSpy.events.flatMap(event => (event.kind === 'exit' ? [event.text] : []))
}

/** 每次 write 的字节数，用来验证「单块上限」而不是「总量上限」。 */
function writeSizes(): number[] {
  return xtermSpy.events.flatMap(event => (event.kind === 'write' ? [event.bytes.length] : []))
}

/** 首个不同字节的下标；完全一致时返回 -1。断言失败时直接指出位置，便于定位。 */
function firstMismatch(actual: Uint8Array, expected: Uint8Array): number {
  const limit = Math.min(actual.length, expected.length)
  for (let index = 0; index < limit; index += 1) {
    if (actual[index] !== expected[index]) return index
  }
  return actual.length === expected.length ? -1 : limit
}

/** 定长文本：内容随下标变化，任何丢失或乱序都会让逐字节比对失败。 */
function chunkText(index: number, length: number): string {
  const unit = `[${index}:0123456789abcdef]`
  return unit.repeat(Math.ceil(length / unit.length)).slice(0, length)
}

async function renderPanel(handshake: Handshake, onError = vi.fn()) {
  render(<TerminalPanel bridge={handshake.bridge} fontSize={14} kind="ubuntu" onError={onError} />)
  await waitFor(() => { expect(handshake.hasStarted()).toBe(true) })
  return onError
}

beforeEach(() => {
  saveLanguage('zh-CN')
  xtermSpy.events.length = 0
  xtermSpy.dataHandlers.clear()
  vi.stubGlobal('ResizeObserver', ResizeObserverStub)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('终端面板的握手窗口缓冲', () => {
  it('窗口内超过旧上限（32 条）的突发输出逐字节按序补齐，不丢内容', async () => {
    const handshake = createHandshake()
    const onError = await renderPanel(handshake)

    const texts = Array.from({ length: 100 }, (_, index) => `line-${String(index).padStart(3, '0')}\n`)
    for (const text of texts) handshake.emitOutput('session-a', text)

    // 窗口内不该有任何字节落到终端：此时还不知道哪个会话是自己的。
    expect(writtenBytes().length).toBe(0)

    await handshake.settle('session-a')

    // 100 条事件全部交付，且顺序与到达顺序一致（旧实现只留前 32 条）。
    expect(writtenText()).toBe(texts.join(''))
    expect(onError).not.toHaveBeenCalled()
  })

  it('窗口内超过单块上限（64 KiB）的输出被重新分块，但字节流完全一致', async () => {
    const handshake = createHandshake()
    await renderPanel(handshake)

    const chunkBytes = 16 * 1024
    const texts = Array.from({ length: 8 }, (_, index) => chunkText(index, chunkBytes))
    for (const text of texts) handshake.emitOutput('session-a', text)
    await handshake.settle('session-a')

    const expected = new TextEncoder().encode(texts.join(''))
    expect(writtenBytes().length).toBe(expected.length)
    expect(firstMismatch(writtenBytes(), expected)).toBe(-1)
    // 8 次到达被合并成 2 块交付，每块都不超过单块上限：
    // 上限限制的是单块容量，不是能收多少字节。
    expect(writeSizes()).toEqual([64 * 1024, 64 * 1024])
  })

  it('窗口内到达的退出事件在就绪后补发，并真的关闭输入', async () => {
    const handshake = createHandshake()
    await renderPanel(handshake)

    handshake.emitOutput('session-a', 'booting\n')
    handshake.emitExit('session-a', 7)
    await handshake.settle('session-a')

    expect(writtenText()).toBe('booting\n')
    expect(exitLines()).toEqual(['\r\n[会话已结束，退出码 7]'])
    // 终态必须排在输出之后：它是这个会话的最后一条事件。
    expect(xtermSpy.events[xtermSpy.events.length - 1].kind).toBe('exit')
    // 终态处理真的生效了：输入回调已注销，打字不再写回原生（旧实现丢掉终态时会继续写）。
    expect(xtermSpy.dataHandlers.size).toBe(0)
    handshake.type('ls\r')
    expect(handshake.writeTerminal).not.toHaveBeenCalled()
  })

  it('对照组：会话正常时的输出直通、打字写回原生，跨窗口仍保序', async () => {
    const handshake = createHandshake()
    await renderPanel(handshake)

    handshake.emitOutput('session-a', 'before\n')
    await handshake.settle('session-a')
    handshake.emitOutput('session-a', 'after\n')

    expect(writtenText()).toBe('before\nafter\n')
    // 同一个替身里打字**会**写回原生，说明上一条用例的「不写回」来自终态处理本身。
    handshake.type('whoami\r')
    await waitFor(() => { expect(handshake.writeTerminal).toHaveBeenCalledTimes(1) })
    expect(handshake.writeTerminal).toHaveBeenCalledWith('session-a', toBase64('whoami\r'))
  })

  it('只交付本会话的事件：其它会话的输出与终态既不交付也不残留', async () => {
    const handshake = createHandshake()
    await renderPanel(handshake)

    handshake.emitOutput('session-other', 'FOREIGN\n')
    handshake.emitExit('session-other', 9)
    await handshake.settle('session-a')

    expect(writtenBytes().length).toBe(0)
    expect(exitLines()).toEqual([])

    handshake.emitOutput('session-other', 'FOREIGN-2\n')
    expect(writtenBytes().length).toBe(0)
  })

  it('握手失败后缓冲关闭：不再累积，也不会有任何延迟交付', async () => {
    const handshake = createHandshake()
    const onError = await renderPanel(handshake)

    handshake.emitOutput('session-a', 'partial\n')
    await handshake.fail(new Error('PTY_START_FAILED'))

    expect(onError).toHaveBeenCalledWith('PTY_START_FAILED')
    await waitFor(() => { expect(document.querySelector('.terminal-connecting')).toBeNull() })

    for (let index = 0; index < 200; index += 1) handshake.emitOutput('session-a', chunkText(index, 1024))
    expect(writtenBytes().length).toBe(0)
    expect(exitLines()).toEqual([])
  })
})
