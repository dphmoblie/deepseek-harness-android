import type {
  ListenerHandle,
  RuntimeBridge,
  RuntimeProgress,
  RuntimeSettings,
  RuntimeState,
  ShizukuState,
  TerminalChunk,
  TerminalExit,
  TerminalKind,
} from './types'
import { validateSettings, validateRuntimeSource } from './validation'

const SETTINGS_KEY = 'dsh-mobile-settings-v1'
const encoder = new TextEncoder()
const decoder = new TextDecoder()

const DEFAULT_SETTINGS: RuntimeSettings = {
  manifestUrl: 'https://downloads.example.invalid/deepseek-harness/android/manifest.json',
  manifestSha256: '0'.repeat(64),
  keepScreenAwake: true,
  terminalFontSize: 14,
}

function listenerHandle(remove: () => void): ListenerHandle {
  return {
    remove: () => {
      remove()
      return Promise.resolve()
    },
  }
}

export function createBrowserBridge(): RuntimeBridge {
  let state: RuntimeState = {
    phase: 'not-installed',
    architecture: 'arm64-v8a',
    downloadedBytes: 0,
    totalBytes: 640 * 1024 * 1024,
    runnerAvailable: true,
  }
  let shizuku: ShizukuState = { installed: true, running: true, permission: 'undetermined' }
  const progressListeners = new Set<(event: RuntimeProgress) => void>()
  const outputListeners = new Set<(event: TerminalChunk) => void>()
  const exitListeners = new Set<(event: TerminalExit) => void>()
  const sessions = new Map<string, TerminalKind>()

  const emitProgress = (): void => {
    const event: RuntimeProgress = {
      phase: state.phase,
      downloadedBytes: state.downloadedBytes,
      totalBytes: state.totalBytes,
    }
    progressListeners.forEach(listener => listener(event))
  }

  return {
    getState: () => Promise.resolve({ ...state }),
    getSettings: () => {
      const saved = localStorage.getItem(SETTINGS_KEY)
      if (saved === null) return Promise.resolve({ ...DEFAULT_SETTINGS })
      try {
        return Promise.resolve(validateSettings(JSON.parse(saved) as RuntimeSettings))
      } catch {
        return Promise.resolve({ ...DEFAULT_SETTINGS })
      }
    },
    saveSettings: settings => {
      const validated = validateSettings(settings)
      localStorage.setItem(SETTINGS_KEY, JSON.stringify(validated))
      return Promise.resolve(validated)
    },
    install: async source => {
      if (source !== undefined) validateRuntimeSource(source)
      state = { ...state, phase: 'downloading', downloadedBytes: 0, errorCode: undefined }
      emitProgress()
      for (const percent of [0.12, 0.31, 0.56, 0.78, 1]) {
        await new Promise(resolve => window.setTimeout(resolve, 120))
        state = { ...state, downloadedBytes: Math.round(state.totalBytes * percent) }
        emitProgress()
      }
      state = { ...state, phase: 'verifying' }
      emitProgress()
      await new Promise(resolve => window.setTimeout(resolve, 180))
      state = { ...state, phase: 'extracting' }
      emitProgress()
      await new Promise(resolve => window.setTimeout(resolve, 260))
      state = { ...state, phase: 'ready', installedVersion: '2026.08.1' }
      emitProgress()
    },
    startHarness: () => {
      state = { ...state, phase: 'running', harnessUrl: 'http://127.0.0.1:3080/' }
      return Promise.resolve({ ...state })
    },
    openHarness: () => {
      if (state.phase !== 'running' || state.harnessUrl === undefined) throw new Error('Harness 尚未运行')
      window.open(state.harnessUrl, '_blank', 'noopener,noreferrer')
      return Promise.resolve()
    },
    stopRuntime: () => {
      state = { ...state, phase: state.installedVersion === undefined ? 'not-installed' : 'ready', harnessUrl: undefined }
      return Promise.resolve({ ...state })
    },
    reset: confirmation => {
      if (confirmation !== 'RESET_RUNTIME') throw new Error('重置确认无效')
      state = {
        phase: 'not-installed',
        architecture: state.architecture,
        downloadedBytes: 0,
        totalBytes: state.totalBytes,
        runnerAvailable: state.runnerAvailable,
      }
      return Promise.resolve({ ...state })
    },
    createTerminal: (kind, columns, rows) => {
      if (kind === 'device' && shizuku.permission !== 'granted') throw new Error('需要 Shizuku 授权')
      const sessionId = crypto.randomUUID()
      sessions.set(sessionId, kind)
      window.setTimeout(() => {
        const prefix = kind === 'ubuntu' ? 'ubuntu@dsh:/workspace$ ' : 'shell@android:/ $ '
        outputListeners.forEach(listener => listener({
          sessionId,
          dataBase64: btoa(String.fromCharCode(...encoder.encode(`\r\n${prefix}`))),
        }))
      }, 40)
      void columns
      void rows
      return Promise.resolve({ sessionId })
    },
    writeTerminal: (sessionId, dataBase64) => {
      if (!sessions.has(sessionId)) throw new Error('终端会话不存在')
      const bytes = Uint8Array.from(atob(dataBase64), char => char.charCodeAt(0))
      const input = decoder.decode(bytes)
      const output = input === '\r' ? '\r\n' : input
      outputListeners.forEach(listener => listener({
        sessionId,
        dataBase64: btoa(String.fromCharCode(...encoder.encode(output))),
      }))
      return Promise.resolve()
    },
    resizeTerminal: () => Promise.resolve(),
    closeTerminal: sessionId => {
      if (sessions.delete(sessionId)) exitListeners.forEach(listener => listener({ sessionId, exitCode: 0 }))
      return Promise.resolve()
    },
    getShizukuState: () => Promise.resolve({ ...shizuku }),
    requestShizukuPermission: () => {
      shizuku = { ...shizuku, permission: 'granted' }
      return Promise.resolve({ ...shizuku })
    },
    openShizuku: () => Promise.resolve(),
    addRuntimeProgressListener: listener => {
      progressListeners.add(listener)
      return Promise.resolve(listenerHandle(() => progressListeners.delete(listener)))
    },
    addTerminalOutputListener: listener => {
      outputListeners.add(listener)
      return Promise.resolve(listenerHandle(() => outputListeners.delete(listener)))
    },
    addTerminalExitListener: listener => {
      exitListeners.add(listener)
      return Promise.resolve(listenerHandle(() => exitListeners.delete(listener)))
    },
  }
}
