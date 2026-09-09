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

export const MODEL_PROVIDER_IDS = [
  'deepseek',
  'openai',
  'anthropic',
  'google',
  'openrouter',
  'groq',
  'xai',
  'mistral',
] as const

export type ModelProviderId = typeof MODEL_PROVIDER_IDS[number]

export type ProviderApiKeys = Partial<Record<ModelProviderId, string>>

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
  /** 已配置凭据的供应商；只返回状态，不向 WebView 回传凭据明文。 */
  configuredModelProviders: ModelProviderId[]
  /** 旧版原生桥接兼容字段；校验后只迁移为 DeepSeek 的已配置状态。 */
  apiKey?: string
  /** 打开应用时自动启动 Harness（默认 true）；旧存储/测试可能缺省。 */
  autoLaunch?: boolean
}

export interface RuntimeSettingsUpdate extends RuntimeSettings {
  /** 本次写入的凭据增量；原生端只接受固定供应商白名单。 */
  providerApiKeys?: ProviderApiKeys
  /** 本次明确清除的供应商凭据。 */
  clearProviderApiKeys?: ModelProviderId[]
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
  /** 保存应用语言，仅接受简体中文和英语。 */
  setAppLanguage: (language: 'zh-CN' | 'en') => Promise<void>
  getState: () => Promise<RuntimeState>
  getSettings: () => Promise<RuntimeSettings>
  saveSettings: (settings: RuntimeSettingsUpdate) => Promise<RuntimeSettings>
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
