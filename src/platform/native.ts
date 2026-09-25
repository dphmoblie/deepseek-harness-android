import { validatePluginCatalog, validatePluginRequest } from './plugins'
import { Capacitor, registerPlugin } from '@capacitor/core'
import type { PluginListenerHandle } from '@capacitor/core'
import { createBrowserBridge } from './browser'
import { validateSelfCheckOperation, type SelfCheckOperation } from '../runtimeSelfCheck'
import type {
  AppThemeMode,
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
  assertMailboxSubdirectory,
  assertRuntimeVersionTarget,
  assertSessionId,
  assertStorageDirPath,
  assertTerminalKind,
  assertTerminalSize,
  validateAllFilesAccessResult,
  validateDeviceCommand,
  validateDeviceCommandParam,
  validateDeviceCommandResult,
  validateDiagnosticLogExport,
  validateDiagnosticLogState,
  validateDiagnosticLogText,
  validateHarnessLog,
  validateKeepAliveState,
  validateMailboxExportResult,
  validateMailboxImportResult,
  validateMailboxState,
  validateMediaPermissionResult,
  validateNotificationPermissionResult,
  validateOverlayBallState,
  validateRuntimeProgress,
  validateRuntimeSelfCheckReport,
  validateRuntimeState,
  validateRuntimeVersions,
  validateSettings,
  validateSettingsUpdate,
  validateShizukuState,
  validateStorageAccessState,
  validateStorageDirsState,
  validateStoredSettings,
  validateRuntimeSource,
  validateTerminalChunk,
  validateTerminalExit,
  validateTerminalSession,
} from './validation'

interface NativeRuntimePlugin {
  managePlugins(options: PluginRequest): Promise<PluginCatalog>
  setAppLanguage(options: { language: 'zh-CN' | 'en' }): Promise<void>
  /** 同步主题模式给原生，由原生把它落到状态栏（登记册 5.4）。 */
  setAppTheme(options: { mode: AppThemeMode }): Promise<void>
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
  overlayBallState(): Promise<unknown>
  openOverlaySettings(): Promise<void>
  mailboxState(): Promise<unknown>
  getStorageAccessState(): Promise<unknown>
  requestMediaPermission(): Promise<unknown>
  openAllFilesAccessSettings(): Promise<unknown>
  importMailbox(): Promise<unknown>
  exportMailbox(options: { subdirectory?: string }): Promise<unknown>
  storageDirsState(): Promise<unknown>
  addStorageDirectory(): Promise<unknown>
  removeStorageDirectory(options: { path: string }): Promise<unknown>
  getHarnessLog(options: { maxBytes?: number }): Promise<unknown>
  runRuntimeSelfCheck(options: { operation: SelfCheckOperation }): Promise<unknown>
  runtimeVersions(): Promise<unknown>
  switchRuntimeVersion(options: { target: string }): Promise<unknown>
  deleteRuntimeVersion(options: { target: string }): Promise<unknown>
  getDiagnosticLogState(): Promise<DiagnosticLogState>
  readDiagnosticLog(options: { maxBytes?: number }): Promise<unknown>
  setDiagnosticLogSettings(options: { enabled: boolean; retentionDays: number }): Promise<DiagnosticLogState>
  shareDiagnosticLog(): Promise<DiagnosticLogExport>
  shareRuntimeWorkspace(): Promise<void>
  listRuntimeWorkspaceFiles(): Promise<{ files: string[] }>
  shareRuntimeWorkspaceFile(options: { path: string }): Promise<void>
  openRuntimeWorkspaceFile(options: { path: string }): Promise<void>
  deleteRuntimeWorkspaceFile(options: { path: string }): Promise<void>
  clearDiagnosticLog(): Promise<DiagnosticLogState>
  addListener(eventName: 'runtimeProgress', listener: (event: RuntimeProgress) => void): Promise<PluginListenerHandle>
  addListener(eventName: 'terminalOutput', listener: (event: TerminalChunk) => void): Promise<PluginListenerHandle>
  addListener(eventName: 'terminalExit', listener: (event: TerminalExit) => void): Promise<PluginListenerHandle>
}

const MAX_TERMINAL_INPUT_BYTES = 256 * 1024
const NativeRuntime = registerPlugin<NativeRuntimePlugin>('MobileRuntime')

function assertWorkspaceFilePath(path: string): string {
  const segments = path.split('/')
  if (path.length < 1 || path.length > 240 || path.startsWith('/') || path.includes('\\') ||
      segments.some(segment => segment === '' || segment === '.' || segment === '..')) {
    throw new Error('工作区文件路径无效')
  }
  return path
}

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
    setAppTheme: mode => {
      // 与原生侧的校验保持同一组取值：非法值在过桥之前就拒绝，不让原生去猜。
      if (mode !== 'system' && mode !== 'light' && mode !== 'dark') {
        return Promise.reject(new Error('不支持的主题模式'))
      }
      return NativeRuntime.setAppTheme({ mode })
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
    getOverlayBallState: () => NativeRuntime.overlayBallState().then(validateOverlayBallState),
    openOverlaySettings: () => NativeRuntime.openOverlaySettings(),
    getMailboxState: () => NativeRuntime.mailboxState().then(validateMailboxState),
    getStorageAccessState: () => NativeRuntime.getStorageAccessState().then(validateStorageAccessState),
    requestMediaPermission: () => NativeRuntime.requestMediaPermission().then(validateMediaPermissionResult),
    openAllFilesAccessSettings: () => NativeRuntime.openAllFilesAccessSettings().then(validateAllFilesAccessResult),
    importMailbox: () => NativeRuntime.importMailbox().then(validateMailboxImportResult),
    // 导出起点先在前端拦一道明显非法的取值（绝对路径、`..`），原生侧还有同一套规则兜底。
    exportMailbox: subdirectory => {
      const target = assertMailboxSubdirectory(subdirectory)
      return NativeRuntime.exportMailbox(target === undefined ? {} : { subdirectory: target })
        .then(validateMailboxExportResult)
    },
    // 目录白名单：选区与校验都在原生侧（SAF 回调里做），这里只负责校验载荷与路径入参。
    getStorageDirs: () => NativeRuntime.storageDirsState().then(validateStorageDirsState),
    addStorageDirectory: () => NativeRuntime.addStorageDirectory().then(validateStorageDirsState),
    removeStorageDirectory: path => NativeRuntime
      .removeStorageDirectory({ path: assertStorageDirPath(path) })
      .then(validateStorageDirsState),
    // 窗口参数由原生侧收敛到受控档位；这里只负责透传用户选择的字节数。
    getHarnessLog: options => NativeRuntime.getHarnessLog({ maxBytes: options?.maxBytes }).then(validateHarnessLog),
    // 操作类型只允许 check / repair：未知取值在进入原生侧之前就被拒绝。
    runRuntimeSelfCheck: operation => NativeRuntime
      .runRuntimeSelfCheck({ operation: validateSelfCheckOperation(operation) })
      .then(validateRuntimeSelfCheckReport),
    // 版本槽与体积由原生侧回传；目标取值在前端就拦死（目前只有上一版本可切换或删除）。
    getRuntimeVersions: () => NativeRuntime.runtimeVersions().then(validateRuntimeVersions),
    switchRuntimeVersion: target => NativeRuntime
      .switchRuntimeVersion({ target: assertRuntimeVersionTarget(target) })
      .then(validateRuntimeVersions),
    deleteRuntimeVersion: target => NativeRuntime
      .deleteRuntimeVersion({ target: assertRuntimeVersionTarget(target) })
      .then(validateRuntimeVersions),
    readDiagnosticLog: options => NativeRuntime.readDiagnosticLog({ maxBytes: options?.maxBytes }).then(validateDiagnosticLogText),
    getDiagnosticLogState: () => NativeRuntime.getDiagnosticLogState().then(validateDiagnosticLogState),
    setDiagnosticLogSettings: (enabled, retentionDays) => {
      const days = assertDiagnosticRetentionDays(retentionDays)
      return NativeRuntime.setDiagnosticLogSettings({ enabled, retentionDays: days }).then(validateDiagnosticLogState)
    },
    shareDiagnosticLog: () => NativeRuntime.shareDiagnosticLog().then(validateDiagnosticLogExport),
    shareRuntimeWorkspace: () => NativeRuntime.shareRuntimeWorkspace(),
    listRuntimeWorkspaceFiles: () => NativeRuntime.listRuntimeWorkspaceFiles().then(value => value.files),
    shareRuntimeWorkspaceFile: path => NativeRuntime.shareRuntimeWorkspaceFile({ path: assertWorkspaceFilePath(path) }),
    openRuntimeWorkspaceFile: path => NativeRuntime.openRuntimeWorkspaceFile({ path: assertWorkspaceFilePath(path) }),
    deleteRuntimeWorkspaceFile: path => NativeRuntime.deleteRuntimeWorkspaceFile({ path: assertWorkspaceFilePath(path) }),
    clearDiagnosticLog: () => NativeRuntime.clearDiagnosticLog().then(validateDiagnosticLogState),
    addRuntimeProgressListener: listener => NativeRuntime.addListener('runtimeProgress', validatedListener(validateRuntimeProgress, listener)),
    addTerminalOutputListener: listener => NativeRuntime.addListener('terminalOutput', validatedListener(validateTerminalChunk, listener)),
    addTerminalExitListener: listener => NativeRuntime.addListener('terminalExit', validatedListener(validateTerminalExit, listener)),
  }
}

export const runtimeBridge: RuntimeBridge = Capacitor.isNativePlatform()
  ? createNativeBridge()
  : createBrowserBridge()
