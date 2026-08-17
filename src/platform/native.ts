import { Capacitor, registerPlugin } from '@capacitor/core'
import type { PluginListenerHandle } from '@capacitor/core'
import { createBrowserBridge } from './browser'
import type {
  DeviceCommand,
  DeviceCommandResult,
  RuntimeBridge,
  RuntimeProgress,
  RuntimeSettings,
  RuntimeSource,
  RuntimeState,
  ShizukuState,
  TerminalChunk,
  TerminalExit,
  TerminalKind,
} from './types'
import {
  assertBase64Input,
  assertSessionId,
  assertTerminalKind,
  assertTerminalSize,
  validateDeviceCommand,
  validateDeviceCommandParam,
  validateDeviceCommandResult,
  validateRuntimeProgress,
  validateRuntimeState,
  validateSettings,
  validateShizukuState,
  validateStoredSettings,
  validateRuntimeSource,
  validateTerminalChunk,
  validateTerminalExit,
  validateTerminalSession,
} from './validation'

interface NativeRuntimePlugin {
  getState(): Promise<RuntimeState>
  getSettings(): Promise<RuntimeSettings>
  saveSettings(settings: RuntimeSettings): Promise<RuntimeSettings>
  install(source?: RuntimeSource): Promise<void>
  startHarness(): Promise<RuntimeState>
  openHarness(): Promise<void>
  stopRuntime(): Promise<RuntimeState>
  reset(options: { confirmation: string }): Promise<RuntimeState>
  createTerminal(options: { kind: TerminalKind; columns: number; rows: number }): Promise<{ sessionId: string }>
  writeTerminal(options: { sessionId: string; dataBase64: string }): Promise<void>
  resizeTerminal(options: { sessionId: string; columns: number; rows: number }): Promise<void>
  closeTerminal(options: { sessionId: string }): Promise<void>
  execDeviceCommand(options: { sessionId: string; command: DeviceCommand; param?: string }): Promise<DeviceCommandResult>
  getShizukuState(): Promise<ShizukuState>
  requestShizukuPermission(): Promise<ShizukuState>
  connectShizuku(): Promise<ShizukuState>
  openShizuku(): Promise<void>
  addListener(eventName: 'runtimeProgress', listener: (event: RuntimeProgress) => void): Promise<PluginListenerHandle>
  addListener(eventName: 'terminalOutput', listener: (event: TerminalChunk) => void): Promise<PluginListenerHandle>
  addListener(eventName: 'terminalExit', listener: (event: TerminalExit) => void): Promise<PluginListenerHandle>
}

const MAX_TERMINAL_INPUT_BYTES = 256 * 1024
const NativeRuntime = registerPlugin<NativeRuntimePlugin>('MobileRuntime')

function validatedListener<T>(validator: (value: unknown) => T, listener: (event: T) => void): (event: T) => void {
  return event => {
    try {
      listener(validator(event))
    } catch {
      // Native event callbacks are outside Promise chains; malformed payloads fail closed here.
    }
  }
}

function createNativeBridge(): RuntimeBridge {
  return {
    getState: () => NativeRuntime.getState().then(validateRuntimeState),
    getSettings: () => NativeRuntime.getSettings().then(validateStoredSettings),
    saveSettings: settings => NativeRuntime.saveSettings(validateSettings(settings)).then(validateSettings),
    install: source => NativeRuntime.install(source === undefined ? undefined : validateRuntimeSource(source)),
    startHarness: () => NativeRuntime.startHarness().then(validateRuntimeState),
    openHarness: () => NativeRuntime.openHarness(),
    stopRuntime: () => NativeRuntime.stopRuntime().then(validateRuntimeState),
    reset: confirmation => {
      if (confirmation !== 'RESET_RUNTIME') return Promise.reject(new Error('重置确认无效'))
      return NativeRuntime.reset({ confirmation }).then(validateRuntimeState)
    },
    createTerminal: (kind, columns, rows) => {
      assertTerminalKind(kind)
      assertTerminalSize(columns, rows)
      return NativeRuntime.createTerminal({ kind, columns, rows }).then(validateTerminalSession)
    },
    writeTerminal: (sessionId, dataBase64) => {
      assertSessionId(sessionId)
      assertBase64Input(dataBase64, MAX_TERMINAL_INPUT_BYTES)
      return NativeRuntime.writeTerminal({ sessionId, dataBase64 })
    },
    resizeTerminal: (sessionId, columns, rows) => {
      assertSessionId(sessionId)
      assertTerminalSize(columns, rows)
      return NativeRuntime.resizeTerminal({ sessionId, columns, rows })
    },
    closeTerminal: sessionId => NativeRuntime.closeTerminal({ sessionId: assertSessionId(sessionId) }),
    execDeviceCommand: (sessionId, command, param) => {
      const validated = {
        sessionId: assertSessionId(sessionId),
        command: validateDeviceCommand(command),
        param: validateDeviceCommandParam(param),
      }
      return NativeRuntime.execDeviceCommand(validated).then(validateDeviceCommandResult)
    },
    getShizukuState: () => NativeRuntime.getShizukuState().then(validateShizukuState),
    requestShizukuPermission: () => NativeRuntime.requestShizukuPermission().then(validateShizukuState),
    connectShizuku: () => NativeRuntime.connectShizuku().then(validateShizukuState),
    openShizuku: () => NativeRuntime.openShizuku(),
    addRuntimeProgressListener: listener => NativeRuntime.addListener('runtimeProgress', validatedListener(validateRuntimeProgress, listener)),
    addTerminalOutputListener: listener => NativeRuntime.addListener('terminalOutput', validatedListener(validateTerminalChunk, listener)),
    addTerminalExitListener: listener => NativeRuntime.addListener('terminalExit', validatedListener(validateTerminalExit, listener)),
  }
}

export const runtimeBridge: RuntimeBridge = Capacitor.isNativePlatform()
  ? createNativeBridge()
  : createBrowserBridge()
