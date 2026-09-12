import { validatePluginCatalog, validatePluginRequest } from './plugins'
import { Capacitor, registerPlugin } from '@capacitor/core'
import type { PluginListenerHandle } from '@capacitor/core'
import { createBrowserBridge } from './browser'
import type {
  PluginRequest,
  PluginCatalog,
  DeviceCommand,
  DeviceCommandResult,
  DiagnosticLogExport,
  DiagnosticLogState,
  KeepAliveState,
  NotificationPermissionResult,
  RuntimeBridge,
  RuntimeProgress,
  RuntimeSettings,
  RuntimeSettingsUpdate,
  RuntimeSource,
  RuntimeState,
  ShizukuState,
  TerminalChunk,
  TerminalExit,
  TerminalKind,
} from './types'
import {
  assertBase64Input,
  assertDiagnosticRetentionDays,
  assertSessionId,
  assertTerminalKind,
  assertTerminalSize,
  validateDeviceCommand,
  validateDeviceCommandParam,
  validateDeviceCommandResult,
  validateDiagnosticLogExport,
  validateDiagnosticLogState,
  validateKeepAliveState,
  validateNotificationPermissionResult,
  validateRuntimeProgress,
  validateRuntimeState,
  validateSettings,
  validateSettingsUpdate,
  validateShizukuState,
  validateStoredSettings,
  validateRuntimeSource,
  validateTerminalChunk,
  validateTerminalExit,
  validateTerminalSession,
} from './validation'

interface NativeRuntimePlugin {
  managePlugins(options: PluginRequest): Promise<PluginCatalog>
  setAppLanguage(options: { language: 'zh-CN' | 'en' }): Promise<void>
  getState(): Promise<RuntimeState>
  getSettings(): Promise<RuntimeSettings>
  saveSettings(settings: RuntimeSettingsUpdate): Promise<RuntimeSettings>
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
  getKeepAliveState(): Promise<KeepAliveState>
  requestNotificationPermission(): Promise<NotificationPermissionResult>
  getDiagnosticLogState(): Promise<DiagnosticLogState>
  setDiagnosticLogSettings(options: { enabled: boolean; retentionDays: number }): Promise<DiagnosticLogState>
  shareDiagnosticLog(): Promise<DiagnosticLogExport>
  clearDiagnosticLog(): Promise<DiagnosticLogState>
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
    managePlugins: request => NativeRuntime.managePlugins(validatePluginRequest(request)).then(validatePluginCatalog),
    setAppLanguage: language => {
      if (language !== 'zh-CN' && language !== 'en') return Promise.reject(new Error('不支持的应用语言'))
      return NativeRuntime.setAppLanguage({ language })
    },
    getState: () => NativeRuntime.getState().then(validateRuntimeState),
    getSettings: () => NativeRuntime.getSettings().then(validateStoredSettings),
    saveSettings: settings => NativeRuntime.saveSettings(validateSettingsUpdate(settings)).then(validateSettings),
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
    getKeepAliveState: () => NativeRuntime.getKeepAliveState().then(validateKeepAliveState),
    requestNotificationPermission: () => NativeRuntime.requestNotificationPermission().then(validateNotificationPermissionResult),
    getDiagnosticLogState: () => NativeRuntime.getDiagnosticLogState().then(validateDiagnosticLogState),
    setDiagnosticLogSettings: (enabled, retentionDays) => {
      const days = assertDiagnosticRetentionDays(retentionDays)
      return NativeRuntime.setDiagnosticLogSettings({ enabled, retentionDays: days }).then(validateDiagnosticLogState)
    },
    shareDiagnosticLog: () => NativeRuntime.shareDiagnosticLog().then(validateDiagnosticLogExport),
    clearDiagnosticLog: () => NativeRuntime.clearDiagnosticLog().then(validateDiagnosticLogState),
    addRuntimeProgressListener: listener => NativeRuntime.addListener('runtimeProgress', validatedListener(validateRuntimeProgress, listener)),
    addTerminalOutputListener: listener => NativeRuntime.addListener('terminalOutput', validatedListener(validateTerminalChunk, listener)),
    addTerminalExitListener: listener => NativeRuntime.addListener('terminalExit', validatedListener(validateTerminalExit, listener)),
  }
}

export const runtimeBridge: RuntimeBridge = Capacitor.isNativePlatform()
  ? createNativeBridge()
  : createBrowserBridge()
