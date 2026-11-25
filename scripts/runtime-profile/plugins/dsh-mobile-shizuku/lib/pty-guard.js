import { AsyncLocalStorage } from 'node:async_hooks'
import { constants as fsConstants } from 'node:fs'
import { access as fsAccess, stat as fsStat } from 'node:fs/promises'

/**
 * Android + PRoot 环境的 PTY 启动诊断与工作目录自愈守卫。
 *
 * 解决真机排查出的两类阻断性问题（不修改上游 `node_modules/@deepseek-ai/*`）：
 * 1. `dsh-working-directory` 的 `ensure()` 只做 `stat` 判目录、不检 `X_OK`：
 *    目录被 `chmod` 去掉执行位后仍被放行，后续每次 PTY `chdir` 都在 1~6ms 内死亡，
 *    且自愈分支永远不会触发。这里补齐 `X_OK` 检查并在不可进入时自动回落到会话原始目录、
 *    默认目录或 `/root`。
 * 2. `dsh-terminal-bash` 在 PTY 就绪前退出时只抛固定文案 `PTY shell exited during startup`，
 *    把退出码、信号与 PTY 首行真因（`chdir(2) failed` / `execvp(3) failed` /
 *    `dsh-sandbox-runner: ...`）全部吞掉，模型无法自救只能盲目重试。
 *    这里通过 `AsyncLocalStorage` 在启动期轻量捕获退出状态与前 512 字节输出，
 *    启动成功后立刻解绑监听（零常驻开销）；仅在启动失败时把真因与下一步建议拼到报错里。
 */

const PTY_STARTUP_EXIT_PREFIX = 'PTY shell exited during startup'
const MAX_CAPTURE_BYTES = 512
const MAX_DETAIL_CHARS = 220
const EXIT_SETTLE_WAIT_MS = 50
// 剥离 ANSI 转义序列（CSI / OSC / 单字符 ESC）以便日志与报错可读。
const ANSI_ESCAPE_PATTERN = /\u001b(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001b]*(?:\u0007|\u001b\\)|[@-Z\\-_])/gu
const CONTROL_CHAR_PATTERN = /[\u0000-\u001f\u007f]+/gu

const spawnCaptureStorage = new AsyncLocalStorage()
const wrappedBackends = new WeakSet()
const wrappedWorkingDirectories = new WeakSet()
const wrappedTerminalServices = new WeakSet()

const defaultFsOps = {
  access: fsAccess,
  stat: fsStat,
}

function sanitizePtyDetail(raw) {
  if (typeof raw !== 'string' || raw.length === 0) return ''
  const cleaned = raw
    .replace(ANSI_ESCAPE_PATTERN, '')
    .replace(/\r\n/gu, '\n')
    .replace(/\r/gu, '\n')
    .split('\n')
    .map(line => line.replace(CONTROL_CHAR_PATTERN, ' ').replace(/\s+/gu, ' ').trim())
    .filter(line => line.length > 0)
    .join(' | ')
  if (cleaned.length <= MAX_DETAIL_CHARS) return cleaned
  return `${cleaned.slice(0, MAX_DETAIL_CHARS)}...`
}

async function probePath(target, expectDirectory, fsOps) {
  if (typeof target !== 'string' || target.length === 0) return 'unknown'
  try {
    const info = await fsOps.stat(target)
    if (expectDirectory && !info.isDirectory()) return 'not_directory'
    if (!expectDirectory && info.isDirectory()) return 'is_directory'
  } catch (error) {
    const code = error && typeof error === 'object' ? error.code : undefined
    if (code === 'ENOENT') return 'missing'
    if (code === 'ENOTDIR') return 'not_directory'
    if (code === 'EACCES' || code === 'EPERM') return 'no_permission'
    return code ? `stat_${String(code)}` : 'stat_failed'
  }
  try {
    await fsOps.access(target, fsConstants.X_OK)
    return 'ok'
  } catch (error) {
    const code = error && typeof error === 'object' ? error.code : undefined
    if (code === 'ENOENT') return 'missing'
    if (code === 'ENOTDIR') return 'not_directory'
    if (code === 'EACCES' || code === 'EPERM') return 'no_exec_bit'
    return code ? `access_${String(code)}` : 'access_failed'
  }
}

function recoveryGuidance({ cwdStatus, shellStatus, detail, exitCode }) {
  const lower = detail.toLowerCase()
  if (detail.includes('dsh-sandbox-runner:') || exitCode === 125) {
    return '沙箱运行器拒绝启动：请检查工作目录路径或改用无沙箱兼容模式，不要盲目重复执行'
  }
  if (cwdStatus !== 'ok' && cwdStatus !== 'unknown' || lower.includes('chdir(2) failed')) {
    return '工作目录不可进入：请先调用 working_directory 切换到 /root 等有效目录后再试，不要重复执行失败命令'
  }
  if (shellStatus !== 'ok' && shellStatus !== 'unknown' || lower.includes('execvp(3) failed')) {
    return 'Shell 可执行文件不可用：请停止重试并提示用户检查或修复运行环境'
  }
  return '请先用 working_directory 切换到 /root 试一次；若仍失败请停止重试并向用户报告上述诊断信息'
}

export async function diagnosePtyStartupFailure(capture, options = {}) {
  const fsOps = options.fsOps ?? defaultFsOps
  const cwd = typeof capture?.cwd === 'string' ? capture.cwd : ''
  const shellPath = Array.isArray(capture?.argv) && typeof capture.argv[0] === 'string'
    ? capture.argv[0]
    : ''
  const [cwdStatus, shellStatus] = await Promise.all([
    cwd ? probePath(cwd, true, fsOps) : Promise.resolve('unknown'),
    shellPath ? probePath(shellPath, false, fsOps) : Promise.resolve('unknown'),
  ])

  const detail = sanitizePtyDetail(capture?.output ?? '')
  const exitCode = Number.isInteger(capture?.exitCode) ? capture.exitCode : null
  const exitSignal = capture?.signal !== null && capture?.signal !== undefined && capture?.signal !== 0
    ? capture.signal
    : null

  const parts = []
  if (exitCode !== null) parts.push(`exit=${exitCode}`)
  if (exitSignal !== null) parts.push(`signal=${String(exitSignal)}`)
  if (cwdStatus !== 'unknown' && cwdStatus !== 'ok') parts.push(`cwd=${cwdStatus}`)
  if (shellStatus !== 'unknown' && shellStatus !== 'ok') parts.push(`shell=${shellStatus}`)
  if (detail.length > 0) parts.push(`detail=${JSON.stringify(detail)}`)

  const hint = recoveryGuidance({ cwdStatus, shellStatus, detail, exitCode })
  const summary = parts.length > 0 ? `${parts.join(', ')}; ${hint}` : hint
  return `${PTY_STARTUP_EXIT_PREFIX} (${summary})`
}

function isTraversableDirectory(dir, fsOps) {
  return (async () => {
    if (typeof dir !== 'string' || !dir.startsWith('/')) return false
    try {
      const info = await fsOps.stat(dir)
      if (!info.isDirectory()) return false
      await fsOps.access(dir, fsConstants.X_OK)
      return true
    } catch {
      return false
    }
  })()
}

export async function healWorkingDirectory(workingDirectory, originalEnsure, agent, signal, options = {}) {
  const fsOps = options.fsOps ?? defaultFsOps
  const current = await originalEnsure.call(workingDirectory, agent, signal)
  if (typeof current !== 'string' || current.length === 0) return current
  if (await isTraversableDirectory(current, fsOps)) return current

  const fallbackDirs = [
    agent?.session?.header?.cwd,
    workingDirectory?.defaultDirectory,
    '/root',
  ]
  const seen = new Set([current])
  for (const candidate of fallbackDirs) {
    if (typeof candidate !== 'string' || !candidate.startsWith('/') || seen.has(candidate)) continue
    seen.add(candidate)
    if (await isTraversableDirectory(candidate, fsOps)) {
      if (typeof workingDirectory?.set === 'function') {
        try {
          await workingDirectory.set(agent, candidate, signal)
        } catch {
          // 即使持久化切换失败，本次 spawn 仍使用可进入的回落目录避免秒退。
        }
      }
      return candidate
    }
  }
  return current
}

function isPtyStartupExitError(error) {
  return error instanceof Error && typeof error.message === 'string' &&
    error.message.startsWith(PTY_STARTUP_EXIT_PREFIX)
}

function waitBriefly(promise, timeoutMs) {
  if (!promise || typeof promise.then !== 'function') return Promise.resolve()
  return new Promise(resolve => {
    let settled = false
    const finish = () => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve()
    }
    const timer = setTimeout(finish, timeoutMs)
    if (typeof timer.unref === 'function') timer.unref()
    promise.then(finish, finish)
  })
}

export function wrapTerminalBackend(backend, options = {}) {
  if (!backend || typeof backend !== 'object' || wrappedBackends.has(backend)) return () => {}
  if (typeof backend.spawn !== 'function') return () => {}
  wrappedBackends.add(backend)

  const originalSpawn = backend.spawn
  const hasSpawnTerminal = typeof backend.spawnTerminal === 'function'
  const originalSpawnTerminal = hasSpawnTerminal ? backend.spawnTerminal : null

  if (hasSpawnTerminal) {
    backend.spawnTerminal = async function guardedSpawnTerminal(termSpec) {
      const capture = spawnCaptureStorage.getStore()
      if (capture && termSpec && typeof termSpec === 'object') {
        capture.cwd = typeof termSpec.cwd === 'string' ? termSpec.cwd : capture.cwd
        capture.argv = Array.isArray(termSpec.argv) ? termSpec.argv : capture.argv
      }
      const terminal = await originalSpawnTerminal.call(this, termSpec)
      if (capture && terminal && typeof terminal === 'object') {
        capture.terminal = terminal
        const output = terminal.output
        if (output && typeof output.on === 'function') {
          const onData = chunk => {
            if (capture.bytes >= MAX_CAPTURE_BYTES) return
            const text = typeof chunk === 'string'
              ? chunk
              : Buffer.isBuffer(chunk)
                ? chunk.toString('utf8')
                : chunk instanceof Uint8Array
                  ? Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength).toString('utf8')
                  : ''
            if (text.length === 0) return
            const remaining = MAX_CAPTURE_BYTES - capture.bytes
            const slice = Buffer.byteLength(text) > remaining
              ? Buffer.from(text, 'utf8').subarray(0, remaining).toString('utf8')
              : text
            capture.output += slice
            capture.bytes += Buffer.byteLength(slice)
          }
          output.on('data', onData)
          capture.detach = () => {
            if (typeof output.off === 'function') output.off('data', onData)
            else if (typeof output.removeListener === 'function') output.removeListener('data', onData)
          }
        }
        if (terminal.done && typeof terminal.done.then === 'function') {
          capture.donePromise = terminal.done.then(outcome => {
            if (outcome && typeof outcome === 'object') {
              capture.exitCode = outcome.exitCode ?? outcome.code ?? null
              capture.signal = outcome.signal ?? null
            }
          }, () => {})
        }
      }
      return terminal
    }
  }

  backend.spawn = async function guardedSpawn(spec) {
    const capture = {
      cwd: typeof spec?.cwd === 'string' ? spec.cwd : '',
      argv: null,
      output: '',
      bytes: 0,
      exitCode: null,
      signal: null,
      donePromise: null,
      detach: null,
    }
    return spawnCaptureStorage.run(capture, async () => {
      try {
        return await originalSpawn.call(this, spec)
      } catch (error) {
        if (isPtyStartupExitError(error)) {
          await waitBriefly(capture.donePromise, options.settleWaitMs ?? EXIT_SETTLE_WAIT_MS)
          const enrichedMessage = await diagnosePtyStartupFailure(capture, options)
          const enriched = new Error(enrichedMessage, { cause: error })
          enriched.name = error.name
          throw enriched
        }
        throw error
      } finally {
        try {
          capture.detach?.()
        } catch {
          // 监听器解绑失败不影响主流程。
        }
      }
    })
  }

  return () => {
    backend.spawn = originalSpawn
    if (hasSpawnTerminal) backend.spawnTerminal = originalSpawnTerminal
    wrappedBackends.delete(backend)
  }
}

function patchWorkingDirectoryService(workingDirectory, options) {
  if (!workingDirectory || typeof workingDirectory.ensure !== 'function' ||
    wrappedWorkingDirectories.has(workingDirectory)) {
    return () => {}
  }
  wrappedWorkingDirectories.add(workingDirectory)
  const originalEnsure = workingDirectory.ensure
  workingDirectory.ensure = function guardedEnsure(agent, signal) {
    return healWorkingDirectory(this, originalEnsure, agent, signal, options)
  }
  return () => {
    workingDirectory.ensure = originalEnsure
    wrappedWorkingDirectories.delete(workingDirectory)
  }
}

function patchTerminalService(terminals, options) {
  if (!terminals || typeof terminals !== 'object' || wrappedTerminalServices.has(terminals)) {
    return () => {}
  }
  wrappedTerminalServices.add(terminals)
  const disposers = []
  if (terminals.backends instanceof Map) {
    for (const backend of terminals.backends.values()) {
      disposers.push(wrapTerminalBackend(backend, options))
    }
  }
  const hasRegister = typeof terminals.registerBackend === 'function'
  const originalRegister = hasRegister ? terminals.registerBackend : null
  if (hasRegister) {
    terminals.registerBackend = function guardedRegisterBackend(backend) {
      disposers.push(wrapTerminalBackend(backend, options))
      return originalRegister.call(this, backend)
    }
  }
  return () => {
    if (hasRegister) terminals.registerBackend = originalRegister
    for (const dispose of disposers.splice(0)) {
      try { dispose() } catch { /* ignore */ }
    }
    wrappedTerminalServices.delete(terminals)
  }
}

/**
 * 安装移动端 PTY 诊断与工作目录自愈守卫。
 *
 * 使用可选子注入（`ctx.inject`），任何服务缺失或未来上游接口变化都不会阻塞插件加载。
 */
export function installPtyGuard(ctx, options = {}) {
  if (!ctx || typeof ctx !== 'object') return () => {}
  const disposers = []

  const bindService = (serviceName, patcher) => {
    try {
      // Cordis v4 的 ctx 代理对未声明在顶层 `inject` 里的服务属性会同步抛出
      // `cannot get property "<name>" without inject`，因此必须先走 `ctx.inject`，
      // 绝不能先读 `ctx[serviceName]` 试探。
      if (typeof ctx.inject === 'function') {
        const fiber = ctx.inject([serviceName], child => {
          try {
            const dispose = patcher(child?.[serviceName], options)
            if (typeof child?.effect === 'function') {
              child.effect(() => dispose)
            } else {
              disposers.push(dispose)
            }
          } catch {
            // 可选守卫：注入回调异常绝不拖垮主插件。
          }
        })
        if (typeof fiber?.dispose === 'function') {
          disposers.push(() => fiber.dispose())
        }
        return
      }
      if (ctx[serviceName]) {
        disposers.push(patcher(ctx[serviceName], options))
      }
    } catch {
      // 可选守卫：任何服务访问或注入异常绝不拖垮主插件。
    }
  }

  bindService('workingDirectory', patchWorkingDirectoryService)
  bindService('terminals', patchTerminalService)

  return () => {
    for (const dispose of disposers.splice(0)) {
      try { dispose() } catch { /* ignore */ }
    }
  }
}
