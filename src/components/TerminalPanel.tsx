import { t } from '../i18n'
import { useCallback, useEffect, useRef, useState } from 'react'
import { FitAddon } from '@xterm/addon-fit'
import { Terminal } from '@xterm/xterm'
// 样式跟着组件走：本组件是懒加载的，xterm 的样式表也就只会在终端真正打开时下载。
// 若把它放在 src/main.tsx 里静态引入，这条 CSS 依赖会把整个 xterm 块拉进首屏静态依赖图
// （Vite 会为入口的静态依赖生成 modulepreload），首屏会白下 ~290 KB 的终端代码。
import '@xterm/xterm/css/xterm.css'
import { CheckCircle2, Copy, Loader2 } from 'lucide-react'
import type { RuntimeBridge, TerminalChunk, TerminalExit, TerminalKind } from '../platform/types'

interface TerminalPanelProps {
  bridge: RuntimeBridge
  fontSize: number
  kind: TerminalKind
  onError: (message: string) => void
}

function bytesToBase64(value: string): string {
  const bytes = new TextEncoder().encode(value)
  let binary = ''
  const chunkSize = 8192
  for (let index = 0; index < bytes.length; index += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunkSize))
  }
  return btoa(binary)
}

function base64ToBytes(value: string): Uint8Array {
  if (value.length > 128 * 1024 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) {
    throw new Error(t("终端输出格式无效"))
  }
  const binary = atob(value)
  return Uint8Array.from(binary, character => character.charCodeAt(0))
}

/**
 * 单块字节上限。
 *
 * 它限制的是**单块容量**，不是「能收多少字节」：块写满就开新块，
 * 到达的数据只是被重新分块，不会因为条数或总量被丢掉。
 * 每 64 KiB 数据才占一个数组槽位，容器开销因此有界（约 1/65536），
 * 不会像「一条事件一个数组元素」那样在突发输出下膨胀成上万个条目。
 */
const MAX_PENDING_BLOCK_BYTES = 64 * 1024

/**
 * 握手窗口内单个会话的输出缓冲。
 *
 * 为什么需要它：原生侧的终端输出是一次性事件流 —— `notifyListeners("terminalOutput", ...)`
 * 之后既没有重放接口也没有落盘副本，WebView 里丢掉一个事件就是永久丢字节。
 * 因此这里**只合并、不丢弃**：同一会话的连续输出按到达顺序拼进定长块，
 * 交付时顺序拼接回原来的字节流（块边界不改变任何字节的内容与先后）。
 */
interface PendingSessionOutput {
  /** 已分配的块；除最后一格外的块都已写满，顺序即到达顺序。 */
  blocks: Uint8Array[]
  /** 最后一格已写入的字节数。 */
  filled: number
  /** 该会话的退出事件；会话尚未结束时为 null。 */
  exitCode: number | null
}

/** 把一段输出按到达顺序并入该会话的缓冲：只重新分块，不丢字节。 */
function appendPendingOutput(pending: Map<string, PendingSessionOutput>, sessionId: string, bytes: Uint8Array): void {
  if (bytes.length === 0) return
  let entry = pending.get(sessionId)
  if (entry === undefined) {
    entry = { blocks: [], filled: 0, exitCode: null }
    pending.set(sessionId, entry)
  }
  let offset = 0
  while (offset < bytes.length) {
    let block = entry.blocks[entry.blocks.length - 1]
    if (block === undefined || entry.filled === block.length) {
      block = new Uint8Array(MAX_PENDING_BLOCK_BYTES)
      entry.blocks.push(block)
      entry.filled = 0
    }
    const take = Math.min(block.length - entry.filled, bytes.length - offset)
    block.set(bytes.subarray(offset, offset + take), entry.filled)
    entry.filled += take
    offset += take
  }
}

/**
 * 记住握手窗口内到达的终态事件。
 *
 * 终态是会话的最后一条事件，正常情况下只会到达一次；真的重复到达时保留先到的那条，
 * 退出码以第一次为准，不因为「后来的覆盖先来的」而改变已经交付的结论。
 */
function rememberPendingExit(pending: Map<string, PendingSessionOutput>, event: TerminalExit): void {
  const entry = pending.get(event.sessionId)
  if (entry === undefined) {
    pending.set(event.sessionId, { blocks: [], filled: 0, exitCode: event.exitCode })
    return
  }
  if (entry.exitCode === null) entry.exitCode = event.exitCode
}

export function TerminalPanel({ bridge, fontSize, kind, onError }: TerminalPanelProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const terminalRef = useRef<Terminal | null>(null)
  const [connecting, setConnecting] = useState(true)
  const [copied, setCopied] = useState(false)

  const copyOutput = useCallback(async () => {
    const terminal = terminalRef.current
    if (terminal === null) return
    let text = terminal.getSelection()
    if (text === '') {
      terminal.selectAll()
      text = terminal.getSelection()
      terminal.clearSelection()
    }
    if (text === '') return
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      onError(t("复制失败，请长按选择文本后复制"))
    }
  }, [onError])

  useEffect(() => {
    const container = containerRef.current
    if (container === null) return

    let cancelled = false
    let sessionId: string | undefined
    let resizeFrame = 0
    let inputQueue = Promise.resolve()
    let disposeInput: (() => void) | undefined
    let sessionEnded = false
    /**
     * 握手窗口（`sessionId` 还没就绪）内到达的事件，按会话缓冲。
     *
     * 窗口的起点是注册监听器，终点是 `bridge.createTerminal` 落定：原生侧在
     * `create` 里就已经 spawn 了 pty 并开始读输出，所以「会话已经在吐字节、但 JS 还没拿到
     * sessionId」是真实存在的窗口 —— 之前的实现按 32 条截断，超出的输出与终态事件会被
     * 静默丢弃（输出丢字节，终态丢「会话已结束」的收尾）。这里改成只合并不丢弃。
     */
    const pending = new Map<string, PendingSessionOutput>()
    /**
     * 握手是否已判定失败。
     *
     * 失败后本面板永远拿不到 sessionId，缓冲必须就此关闭：否则监听器会一直把
     * 其它会话的输出攒在内存里，直到面板卸载才释放 —— 那才是真正的无界增长。
     */
    let handshakeFailed = false
    const listenerRemovers: Array<() => Promise<void>> = []

    const terminal = new Terminal({
      allowProposedApi: true,
      allowTransparency: false,
      convertEol: false,
      cursorBlink: true,
      cursorStyle: 'bar',
      fontFamily: '"JetBrains Mono", "Cascadia Mono", "SFMono-Regular", Consolas, monospace',
      fontSize,
      lineHeight: 1.25,
      scrollback: 5000,
      theme: {
        background: '#101419',
        foreground: '#dbe2ec',
        cursor: '#70a0ff',
        cursorAccent: '#101419',
        selectionBackground: '#315ca866',
        black: '#14191f',
        red: '#f07178',
        green: '#8ccf7e',
        yellow: '#e5c07b',
        blue: '#70a0ff',
        magenta: '#c792ea',
        cyan: '#89ddff',
        white: '#dbe2ec',
        brightBlack: '#65737e',
        brightRed: '#ff8b92',
        brightGreen: '#a7df9b',
        brightYellow: '#f0d399',
        brightBlue: '#93b8ff',
        brightMagenta: '#d7a9f3',
        brightCyan: '#a9e8ff',
        brightWhite: '#ffffff',
      },
    })
    const fitAddon = new FitAddon()
    terminal.loadAddon(fitAddon)
    terminal.open(container)
    terminalRef.current = terminal

    const reportError = (error: unknown): void => {
      if (!cancelled) onError(error instanceof Error ? error.message : t("终端操作失败"))
    }

    const writeBytes = (bytes: Uint8Array): void => {
      try {
        terminal.write(bytes)
      } catch (error) {
        reportError(error)
      }
    }

    /**
     * 窗口结束后的直通路径：解码并交付。解码失败（格式/体积校验）只报错，不猜测内容 ——
     * 缓冲路径用同一处校验，两条路径都不允许安静地少写一段。
     */
    const writeOutput = (event: TerminalChunk): void => {
      try {
        writeBytes(base64ToBytes(event.dataBase64))
      } catch (error) {
        reportError(error)
      }
    }

    /** 握手窗口内到达的输出先并入缓冲；解码失败与直通路径同样上报。 */
    const bufferOutput = (event: TerminalChunk): void => {
      try {
        appendPendingOutput(pending, event.sessionId, base64ToBytes(event.dataBase64))
      } catch (error) {
        reportError(error)
      }
    }

    const writeExit = (exitCode: number): void => {
      sessionEnded = true
      disposeInput?.()
      disposeInput = undefined
      terminal.writeln(t("\r\n[会话已结束，退出码 {0}]", exitCode))
      setConnecting(false)
    }

    /**
     * 交付握手窗口内的缓冲：先按到达顺序补写本会话的输出，再补发终态事件。
     *
     * 交付是同步的 —— 调用点紧跟 `sessionId` 赋值，中间没有 await，事件回调不可能插队，
     * 所以「窗口内的字节」与「窗口后的字节」严格保持到达顺序。
     * 交付后立刻清空缓冲：其它会话的残留（例如上一块面板正在收尾的会话）一并释放，
     * 不留在内存里等一个永远不会匹配的 sessionId。
     */
    const flushPending = (activeSessionId: string): void => {
      const entry = pending.get(activeSessionId)
      if (entry !== undefined) {
        entry.blocks.forEach((block, index) => {
          writeBytes(index === entry.blocks.length - 1 ? block.subarray(0, entry.filled) : block)
        })
        if (entry.exitCode !== null) writeExit(entry.exitCode)
      }
      pending.clear()
    }

    const fit = (): void => {
      if (cancelled || container.clientWidth === 0 || container.clientHeight === 0) return
      try {
        fitAddon.fit()
        if (sessionId !== undefined) {
          void bridge.resizeTerminal(
            sessionId,
            Math.max(20, terminal.cols),
            Math.max(4, terminal.rows),
          ).catch(reportError)
        }
      } catch (error) {
        reportError(error)
      }
    }

    const resizeObserver = new ResizeObserver(() => {
      window.cancelAnimationFrame(resizeFrame)
      resizeFrame = window.requestAnimationFrame(fit)
    })
    resizeObserver.observe(container)

    void (async () => {
      try {
        const outputHandle = await bridge.addTerminalOutputListener(event => {
          if (cancelled) return
          if (sessionId === undefined) {
            if (!handshakeFailed) bufferOutput(event)
            return
          }
          if (event.sessionId === sessionId) writeOutput(event)
        })
        if (cancelled) {
          await outputHandle.remove()
          return
        }
        listenerRemovers.push(outputHandle.remove)

        const exitHandle = await bridge.addTerminalExitListener(event => {
          if (cancelled) return
          if (sessionId === undefined) {
            if (!handshakeFailed) rememberPendingExit(pending, event)
            return
          }
          if (event.sessionId === sessionId) writeExit(event.exitCode)
        })
        if (cancelled) {
          await exitHandle.remove()
          return
        }
        listenerRemovers.push(exitHandle.remove)

        fit()
        const session = await bridge.createTerminal(
          kind,
          Math.max(20, terminal.cols),
          Math.max(4, terminal.rows),
        )
        if (cancelled) {
          await bridge.closeTerminal(session.sessionId)
          return
        }

        sessionId = session.sessionId
        flushPending(sessionId)
        if (!sessionEnded) {
          const inputDisposable = terminal.onData(data => {
            if (sessionId === undefined || cancelled) return
            const activeSessionId = sessionId
            inputQueue = inputQueue
              .then(() => bridge.writeTerminal(activeSessionId, bytesToBase64(data)))
              .catch(reportError)
          })
          disposeInput = () => inputDisposable.dispose()
        }
        listenerRemovers.push(() => {
          disposeInput?.()
          disposeInput = undefined
          return Promise.resolve()
        })
        setConnecting(false)
        fit()
        terminal.focus()
      } catch (error) {
        // 握手失败：关闭缓冲并释放已攒下的字节。本面板不会再有匹配的会话，
        // 这些字节没有交付对象，留着只会一直占内存（不做没有意义的重放）。
        handshakeFailed = true
        pending.clear()
        setConnecting(false)
        reportError(error)
      }
    })()

    return () => {
      cancelled = true
      pending.clear()
      resizeObserver.disconnect()
      window.cancelAnimationFrame(resizeFrame)
      listenerRemovers.forEach(remove => { void remove() })
      if (sessionId !== undefined) void bridge.closeTerminal(sessionId).catch(() => undefined)
      terminalRef.current = null
      terminal.dispose()
    }
  }, [bridge, fontSize, kind, onError])

  return (
    <div className="terminal-frame" aria-label={kind === 'ubuntu' ? t("Ubuntu 终端") : t("设备终端")}>
      <button
        type="button"
        className="terminal-copy"
        onClick={() => void copyOutput()}
        disabled={connecting}
        title={t("复制全部或选中内容")}
        aria-label={t("复制终端输出")}
      >
        {copied ? <CheckCircle2 size={14} /> : <Copy size={14} />}
        {copied ? t("已复制") : t("复制")}
      </button>
      {connecting && (
        <div className="terminal-connecting" role="status">
          <Loader2 size={18} className="spin" />
          {t("正在连接")}</div>
      )}
      <div ref={containerRef} className="terminal-canvas" />
    </div>
  )
}
