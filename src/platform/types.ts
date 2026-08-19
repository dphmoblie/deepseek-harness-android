export type RuntimePhase =
  | 'not-installed'
  | 'preparing'
  | 'downloading'
  | 'verifying'
  | 'extracting'
  | 'ready'
  | 'running'
  | 'stopping'
  | 'error'

export type TerminalKind = 'ubuntu' | 'device'

export type DeviceCommand = 'screenshot' | 'uiDump' | 'tap' | 'inputText'

export interface RuntimeState {
  phase: RuntimePhase
  architecture: string
  installedVersion?: string
  updateAvailable: boolean
  downloadedBytes: number
  totalBytes: number
  runnerAvailable: boolean
  harnessUrl?: string
  errorCode?: string
}

export interface RuntimeSource {
  manifestUrl: string
  manifestSha256: string
}

export interface RuntimeSettings extends RuntimeSource {
  keepScreenAwake: boolean
  terminalFontSize: number
  /** 模型 API Key（如 DeepSeek），注入 rootfs 的 DEEPSEEK_API_KEY 环境变量；空串表示未配置。 */
  apiKey?: string
  /** 打开应用时自动启动 Harness（默认 true）；旧存储/测试可能缺省。 */
  autoLaunch?: boolean
}
export interface RuntimeProgress {
  phase: RuntimePhase
  downloadedBytes: number
  totalBytes: number
  errorCode?: string
}

export interface ShizukuState {
  installed: boolean
  running: boolean
  permission: 'granted' | 'denied' | 'undetermined'
  connected: boolean
  /** Shizuku 服务端版本（诊断用；未安装为空串）。 */
  version?: string
}

export interface TerminalChunk {
  sessionId: string
  dataBase64: string
}

export interface TerminalExit {
  sessionId: string
  exitCode: number
}

export interface DeviceCommandResult {
  ok: boolean
  exitCode: number
  text: string
  truncated: boolean
  errorCode?: string
}

export interface ListenerHandle {
  remove: () => Promise<void>
}

export interface RuntimeBridge {
  getState: () => Promise<RuntimeState>
  getSettings: () => Promise<RuntimeSettings>
  saveSettings: (settings: RuntimeSettings) => Promise<RuntimeSettings>
  install: (source?: RuntimeSource) => Promise<void>
  startHarness: () => Promise<RuntimeState>
  openHarness: () => Promise<void>
  stopRuntime: () => Promise<RuntimeState>
  reset: (confirmation: 'RESET_RUNTIME') => Promise<RuntimeState>
  createTerminal: (kind: TerminalKind, columns: number, rows: number) => Promise<{ sessionId: string }>
  writeTerminal: (sessionId: string, dataBase64: string) => Promise<void>
  resizeTerminal: (sessionId: string, columns: number, rows: number) => Promise<void>
  closeTerminal: (sessionId: string) => Promise<void>
  execDeviceCommand: (sessionId: string, command: DeviceCommand, param?: string) => Promise<DeviceCommandResult>
  getShizukuState: () => Promise<ShizukuState>
  requestShizukuPermission: () => Promise<ShizukuState>
  connectShizuku: () => Promise<ShizukuState>
  openShizuku: () => Promise<void>
  addRuntimeProgressListener: (listener: (event: RuntimeProgress) => void) => Promise<ListenerHandle>
  addTerminalOutputListener: (listener: (event: TerminalChunk) => void) => Promise<ListenerHandle>
  addTerminalExitListener: (listener: (event: TerminalExit) => void) => Promise<ListenerHandle>
}
