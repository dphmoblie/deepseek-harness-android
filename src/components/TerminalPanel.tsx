import { useCallback, useEffect, useRef, useState } from 'react'
import { FitAddon } from '@xterm/addon-fit'
import { Terminal } from '@xterm/xterm'
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
    throw new Error('终端输出格式无效')
  }
  const binary = atob(value)
  return Uint8Array.from(binary, character => character.charCodeAt(0))
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
      onError('复制失败，请长按选择文本后复制')
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
    const pendingOutput: TerminalChunk[] = []
    const pendingExit: TerminalExit[] = []
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
      if (!cancelled) onError(error instanceof Error ? error.message : '终端操作失败')
    }

    const writeOutput = (event: TerminalChunk): void => {
      try {
        terminal.write(base64ToBytes(event.dataBase64))
      } catch (error) {
        reportError(error)
      }
    }

    const writeExit = (event: TerminalExit): void => {
      sessionEnded = true
      disposeInput?.()
      disposeInput = undefined
      terminal.writeln(`\r\n[会话已结束，退出码 ${event.exitCode}]`)
      setConnecting(false)
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
            if (pendingOutput.length < MAX_PENDING_EVENTS) pendingOutput.push(event)
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
            if (pendingExit.length < MAX_PENDING_EVENTS) pendingExit.push(event)
            return
          }
          if (event.sessionId === sessionId) writeExit(event)
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
        pendingOutput.filter(event => event.sessionId === sessionId).forEach(writeOutput)
        pendingExit.filter(event => event.sessionId === sessionId).forEach(writeExit)
        pendingOutput.length = 0
        pendingExit.length = 0
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
        setConnecting(false)
        reportError(error)
      }
    })()

    return () => {
      cancelled = true
      resizeObserver.disconnect()
      window.cancelAnimationFrame(resizeFrame)
      listenerRemovers.forEach(remove => { void remove() })
      if (sessionId !== undefined) void bridge.closeTerminal(sessionId).catch(() => undefined)
      terminalRef.current = null
      terminal.dispose()
    }
  }, [bridge, fontSize, kind, onError])

  return (
    <div className="terminal-frame" aria-label={kind === 'ubuntu' ? 'Ubuntu 终端' : '设备终端'}>
      <button
        type="button"
        className="terminal-copy"
        onClick={() => void copyOutput()}
        disabled={connecting}
        title="复制全部或选中内容"
        aria-label="复制终端输出"
      >
        {copied ? <CheckCircle2 size={14} /> : <Copy size={14} />}
        {copied ? '已复制' : '复制'}
      </button>
      {connecting && (
        <div className="terminal-connecting" role="status">
          <Loader2 size={18} className="spin" />
          正在连接
        </div>
      )}
      <div ref={containerRef} className="terminal-canvas" />
    </div>
  )
}

const MAX_PENDING_EVENTS = 32
