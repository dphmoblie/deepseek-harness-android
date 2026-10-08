import { validatePluginRequest } from './plugins'
import type {
  AppUpdateState,
  DiagnosticLogState,
  AccessibilityAutomationState,
  DiagnosticLogText,
  HarnessLog,
  KeepAliveState,
  MailboxRoot,
  MailboxDirectoryState,
  ListenerHandle,
  MailboxState,
  ModelProviderId,
  OverlayBallState,
  ProviderApiKeys,
  RuntimeBridge,
  RuntimeInstallResult,
  RuntimeProgress,
  RuntimeReleaseList,
  RuntimeResidueCleanupState,
  RuntimeResidueState,
  RuntimeSessionListResult,
  RuntimeSessionSnapshotRestoreResult,
  RuntimeSessionSnapshotState,
  RuntimeSettings,
  RuntimeState,
  RuntimeVersionsState,
  ShizukuState,
  StorageAccessState,
  StorageDirectoryState,
  StorageDirsState,
  TerminalChunk,
  TerminalExit,
  TerminalKind,
} from './types'
import { ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, DIAGNOSTIC_RETENTION_DEFAULT, MAX_STORAGE_DIRECTORIES, MODEL_PROVIDER_IDS } from './types'
import { validateSelfCheckOperation, type SelfCheckOperation, type SelfCheckReport } from '../runtimeSelfCheck'
import { assertMailboxSubdirectory, assertRuntimeVersionTarget, assertSessionId, assertStorageDirPath, validateAccessibilityPasswordInput, validateDeviceCommand, validateDeviceCommandParam, validateSettings, validateSettingsUpdate, validateRuntimeSource } from './validation'

const SETTINGS_KEY = 'dsh-mobile-settings-v1'
/** 文档里的固定投递区路径；浏览器预览只用来**展示**，不声称它可用（见 getMailboxState）。 */
const BROWSER_MAILBOX_ROOT = '/storage/emulated/0/Documents/DSH'
/** 包名格式与原生侧逐字一致：预览环境也不接受一份原生永远回不出来的状态。 */
const PACKAGE_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*){1,12}$/u
const encoder = new TextEncoder()
const decoder = new TextDecoder()

const DEFAULT_SETTINGS: RuntimeSettings = {
  manifestUrl: 'https://downloads.example.invalid/deepseek-harness/android/manifest.json',
  manifestSha256: '0'.repeat(64),
  keepScreenAwake: true,
  terminalFontSize: 14,
  configuredModelProviders: [],
  autoLaunch: false,
  // 浏览器预览没有前台服务：保持关闭，避免给出错误的保活预期。
  keepRuntimeInBackground: false,
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
  let currentSettings = { ...DEFAULT_SETTINGS }
  const configuredProviders = new Set<ModelProviderId>()
  const volatileProviderKeys: ProviderApiKeys = {}
  const configuredCustomProviders = new Set<string>()
  let state: RuntimeState = {
    phase: 'not-installed',
    architecture: 'arm64-v8a',
    updateAvailable: false,
    downloadedBytes: 0,
    totalBytes: 640 * 1024 * 1024,
    runnerAvailable: true,
  }
  let shizuku: ShizukuState = {
    installed: true,
    running: true,
    permission: 'undetermined',
    connected: false,
    // 浏览器预览没有原生侧：这里给一个固定的 Shizuku 应用版本，只为让界面上的
    // 「Shizuku 应用 · 服务端 API」两种版本都有值可显示。
    appVersion: '13.6.0',
  }
  // 浏览器预览里没有原生侧可用，这份状态**只活在内存中**（刷新即丢）。
  // 验证密码同样只存在这个变量里，绝不写进 localStorage：浏览器桥不是凭据存储。
  let accessibility = {
    enabled: false,
    allowedPackages: [] as string[],
    alwaysAllowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES],
    passwordConfigured: false,
  }
  let accessibilityPassword: string | undefined
  let diagnosticState: DiagnosticLogState = {
    enabled: false,
    retentionDays: DIAGNOSTIC_RETENTION_DEFAULT,
    fileCount: 0,
    totalBytes: 0,
    lastEntryAtMillis: 0,
  }
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

  /**
   * 无障碍状态的对外快照：每次都新建数组，调用方改不到桥内部的这份状态。
   *
   * 快照里**只有那一个布尔** `passwordConfigured`，没有任何密码字段——
   * 预览环境也不给「顺手把密码读出来」留口子。
   */
  const accessibilitySnapshot = (): AccessibilityAutomationState => ({
    enabled: accessibility.enabled,
    allowedPackages: [...accessibility.allowedPackages],
    alwaysAllowedPackages: [...accessibility.alwaysAllowedPackages],
    passwordConfigured: accessibility.passwordConfigured,
  })

  return {
    listInstalledApplications: () => Promise.reject(new Error('应用列表仅在安卓设备上可用')),
    getDeviceShellAccess: () => Promise.resolve({ enabled: false }),
    setDeviceShellAccess: () => Promise.reject(new Error('AI Shell 仅在安卓设备上可用')),
    managePlugins: request => {
      validatePluginRequest(request)
      if (request.operation !== 'list') return Promise.reject(new Error('浏览器预览不支持修改设备插件'))
      return Promise.resolve({ plugins: [] })
    },
    setAppLanguage: language => language === 'zh-CN' || language === 'en'
      ? Promise.resolve()
      : Promise.reject(new Error('不支持的应用语言')),
    // 浏览器预览没有窗口装饰可染色：如实按「已接受」返回，不做假的成功提示。
    // DOM 侧的主题落地由 src/theme.ts 自己完成，不经过这条桥。
    setAppTheme: mode => mode === 'system' || mode === 'light' || mode === 'dark'
      ? Promise.resolve()
      : Promise.reject(new Error('不支持的主题模式')),
    getState: () => Promise.resolve({ ...state }),
    getSettings: () => {
      const saved = localStorage.getItem(SETTINGS_KEY)
      if (saved === null) return Promise.resolve({ ...currentSettings })
      try {
        const raw = JSON.parse(saved) as RuntimeSettings
        currentSettings = validateSettings(raw)
        currentSettings.configuredModelProviders.forEach(provider => configuredProviders.add(provider))
        currentSettings.configuredCustomModelProviders?.forEach(id => configuredCustomProviders.add(id))
        if (typeof raw.apiKey === 'string' && raw.apiKey.trim() !== '') volatileProviderKeys.deepseek = raw.apiKey.trim()
        currentSettings = { ...currentSettings, configuredModelProviders: MODEL_PROVIDER_IDS.filter(provider => configuredProviders.has(provider)) }
        // Browser preview storage mirrors production by retaining only masked credential state.
        localStorage.setItem(SETTINGS_KEY, JSON.stringify(currentSettings))
        return Promise.resolve({ ...currentSettings })
      } catch {
        currentSettings = { ...DEFAULT_SETTINGS }
        return Promise.resolve({ ...currentSettings })
      }
    },
    saveSettings: settings => {
      const validated = validateSettingsUpdate(settings)
      Object.entries(validated.providerApiKeys ?? {}).forEach(([provider, key]) => {
        volatileProviderKeys[provider as ModelProviderId] = key
        configuredProviders.add(provider as ModelProviderId)
      })
      validated.clearProviderApiKeys?.forEach(provider => {
        delete volatileProviderKeys[provider]
        configuredProviders.delete(provider)
      })
      const allowedCustomIds = new Set(validated.customModelProviders?.map(provider => provider.id))
      for (const id of configuredCustomProviders) if (!allowedCustomIds.has(id)) configuredCustomProviders.delete(id)
      Object.keys(validated.customProviderApiKeys ?? {}).forEach(id => configuredCustomProviders.add(id))
      validated.clearCustomProviderApiKeys?.forEach(id => configuredCustomProviders.delete(id))
      currentSettings = validateSettings({
        ...validated,
        harnessPermissionMode: validated.harnessPermissionMode ?? currentSettings.harnessPermissionMode ?? 'workspace-write',
        // The native bridge treats an omitted field as "leave unchanged" so an
        // overlay-ball menu action cannot be overwritten by an unrelated save.
        overlayBallEnabled: settings.overlayBallEnabled === undefined
          ? currentSettings.overlayBallEnabled ?? false
          : validated.overlayBallEnabled,
        configuredModelProviders: MODEL_PROVIDER_IDS.filter(provider => configuredProviders.has(provider)),
        configuredCustomModelProviders: [...configuredCustomProviders],
      })
      localStorage.setItem(SETTINGS_KEY, JSON.stringify(currentSettings))
      return Promise.resolve({ ...currentSettings })
    },
    install: async (source): Promise<RuntimeInstallResult> => {
      const validatedSource = source === undefined ? undefined : validateRuntimeSource(source)
      const acquisitionPhase = validatedSource === undefined || validatedSource.manifestUrl === '' ? 'preparing' : 'downloading'
      state = { ...state, phase: acquisitionPhase, downloadedBytes: 0, errorCode: undefined }
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
      for (const percent of [0.18, 0.47, 0.73, 1]) {
        await new Promise(resolve => window.setTimeout(resolve, 80))
        state = { ...state, downloadedBytes: Math.round(state.totalBytes * percent) }
        emitProgress()
      }
      state = { ...state, phase: 'ready', installedVersion: '2026.08.1', updateAvailable: false }
      emitProgress()
      // 预览里没有原生侧的会话快照能力：安装结果不带 autoSnapshot，不编造「已自动备份」的结论。
      return {}
    },
    startHarness: () => {
      state = { ...state, phase: 'running', harnessUrl: 'http://127.0.0.1:3080/' }
      return Promise.resolve({ ...state })
    },
    openHarness: () => {
      if (state.phase !== 'running' || state.harnessUrl === undefined) throw new Error('Harness 尚未运行')
      const url = new URL(state.harnessUrl)
      window.open(url.toString(), '_blank', 'noopener,noreferrer')
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
        updateAvailable: false,
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
    execDeviceCommand: (sessionId, command, param) => {
      assertSessionId(sessionId)
      validateDeviceCommand(command)
      validateDeviceCommandParam(param)
      // 浏览器预览环境没有真实设备 Shell：按失败返回（fail-closed）。
      return Promise.resolve({ ok: false, exitCode: -1, text: '', truncated: false })
    },
    getShizukuState: () => Promise.resolve({ ...shizuku }),
    requestShizukuPermission: () => {
      shizuku = { ...shizuku, permission: 'granted', connected: true }
      return Promise.resolve({ ...shizuku })
    },
    connectShizuku: () => {
      if (shizuku.permission === 'granted') shizuku = { ...shizuku, connected: true }
      return Promise.resolve({ ...shizuku })
    },
    openShizuku: () => Promise.resolve(),
    getAccessibilityAutomationState: (): Promise<AccessibilityAutomationState> => Promise.resolve(accessibilitySnapshot()),
    // 白名单的修改必须带验证密码（与原生侧同一语义）；未设置密码时放行。
    setAccessibilityAutomationPackages: (packages: string[], password?: string): Promise<AccessibilityAutomationState> => {
      if (!Array.isArray(packages) ||
          packages.some(value => typeof value !== 'string' || !PACKAGE_NAME_PATTERN.test(value))) {
        return Promise.reject(new Error('无障碍白名单格式无效'))
      }
      // 已设置密码却没带、或带错：**明确拒绝**，不谎报成功（预览环境也不能假装改成功了）。
      if (accessibility.passwordConfigured) {
        if (password === undefined) return Promise.reject(new Error('需要验证密码'))
        const validated = validateAccessibilityPasswordInput(password, '验证密码')
        if (validated !== accessibilityPassword) return Promise.reject(new Error('验证密码不正确'))
      } else if (password !== undefined) {
        // 未设置密码时也允许带密码（用户在界面上可能刚设过）：照样按格式校验，只是不做比对。
        validateAccessibilityPasswordInput(password, '验证密码')
      }
      // 自动项：无论用户传什么都在结果里，且排在前面（原生侧返回的有效白名单就是这个形态）。
      accessibility = {
        ...accessibility,
        allowedPackages: [...new Set([...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, ...packages])],
      }
      return Promise.resolve(accessibilitySnapshot())
    },
    setAccessibilityPassword: (password: string, currentPassword?: string): Promise<AccessibilityAutomationState> => {
      const next = validateAccessibilityPasswordInput(password, '验证密码')
      if (accessibility.passwordConfigured) {
        if (currentPassword === undefined) return Promise.reject(new Error('需要当前验证密码'))
        const current = validateAccessibilityPasswordInput(currentPassword, '当前验证密码')
        if (current !== accessibilityPassword) return Promise.reject(new Error('当前验证密码不正确'))
      }
      accessibilityPassword = next
      accessibility = { ...accessibility, passwordConfigured: true }
      return Promise.resolve(accessibilitySnapshot())
    },
    clearAccessibilityPassword: (currentPassword: string): Promise<AccessibilityAutomationState> => {
      if (!accessibility.passwordConfigured) return Promise.reject(new Error('尚未设置验证密码'))
      const current = validateAccessibilityPasswordInput(currentPassword, '当前验证密码')
      if (current !== accessibilityPassword) return Promise.reject(new Error('当前验证密码不正确'))
      // 只清密码，**白名单原样保留**——用户要清掉的是那串数字，不是自己配好的目标清单。
      accessibilityPassword = undefined
      accessibility = { ...accessibility, passwordConfigured: false }
      return Promise.resolve(accessibilitySnapshot())
    },
    // 浏览器预览没有系统生物识别 / 锁屏：如实拒绝，不谎报「重置成功」。
    resetAccessibilityPasswordWithBiometric: (): Promise<AccessibilityAutomationState> =>
      Promise.reject(new Error('浏览器预览不支持系统生物识别验证')),
    openAccessibilitySettings: () => Promise.reject(new Error('浏览器预览不支持打开系统无障碍设置')),
    openVirtualScreen: () => Promise.reject(new Error('目标应用副屏需要 Android 10 以上设备与 Shizuku')),
    /*
     * 副屏的设置与状态在浏览器预览里**没有对应物**：虚拟显示器、取帧读数、副屏前台应用
     * 都只有 Android 上存在，因此这四条一律如实拒绝。
     *
     * 刻意不返回一份「看起来正常」的默认设置（档位 60 FPS、方向自动、未运行）：
     * 那会让界面把「这里根本没有副屏」显示成「已读取，只是没启动」，
     * 用户接着点启动、再收到一个说不清原因的成功或失败。
     */
    getVirtualScreenSettings: () => Promise.reject(new Error('目标应用副屏需要 Android 10 以上设备与 Shizuku')),
    setVirtualScreenSettings: () => Promise.reject(new Error('目标应用副屏需要 Android 10 以上设备与 Shizuku')),
    startVirtualScreen: () => Promise.reject(new Error('目标应用副屏需要 Android 10 以上设备与 Shizuku')),
    stopVirtualScreen: () => Promise.reject(new Error('目标应用副屏需要 Android 10 以上设备与 Shizuku')),
    getVirtualScreenState: () => Promise.reject(new Error('目标应用副屏需要 Android 10 以上设备与 Shizuku')),
    // 浏览器预览没有 Android 前台服务：如实报告未运行，避免误导保活预期。
    getKeepAliveState: (): Promise<KeepAliveState> => Promise.resolve({
      keepRuntimeInBackground: currentSettings.keepRuntimeInBackground === true,
      foregroundServiceActive: false,
      notificationPermission: 'unsupported',
      deviceShellReady: shizuku.installed && shizuku.running && shizuku.permission === 'granted',
      reconnectRequired: false,
      lastIntent: state.phase === 'running' ? 'running' : 'stopped',
    }),
    requestNotificationPermission: () => Promise.resolve({ granted: false, supported: false }),
    // 浏览器预览没有系统悬浮窗：始终报告未开启且无权限，界面据此隐藏入口。
    getOverlayBallState: (): Promise<OverlayBallState> => Promise.resolve({
      enabled: false,
      canDrawOverlays: false,
      serviceActive: false,
    }),
    // 浏览器里没有可跳转的系统设置页；静默无操作，不抛错以免打断预览。
    openOverlaySettings: (): Promise<void> => Promise.resolve(),
    /**
     * 浏览器预览没有 Android 的公共存储与 PRoot 访客：投递区**如实报不可用**。
     *
     * 刻意不走「编造一份可用的状态」这条捷径：路径是文档里的固定路径，
     * 但 `available` 恒为 false、计数恒为 0，界面因此只会显示「不支持」而不会给出可点的按钮。
     * 授权档位按「系统不存在这一档」上报（`unsupported` / T0），因为浏览器里确实没有
     * 「所有文件访问」这个权限可授予。
     */
    getMailboxState: (): Promise<MailboxState> => Promise.resolve({
      availability: 'unsupported',
      level: 'T0',
      available: false,
      supported: false,
      granted: false,
      inboxPath: `${BROWSER_MAILBOX_ROOT}/inbox`,
      outboxPath: `${BROWSER_MAILBOX_ROOT}/outbox`,
      guestInboxPath: '/mnt/inbox',
      guestOutboxPath: '/mnt/outbox',
      inboxFileCount: 0,
      inboxTars: [],
      exportTarName: 'dsh-workspace.tar',
      exportManifestName: 'dsh-workspace.manifest.json',
      importDirectory: 'mailbox-import',
    }),
    getMailboxDirectory: (root: MailboxRoot, subdirectory?: string): Promise<MailboxDirectoryState> => {
      if (root !== 'inbox' && root !== 'outbox') throw new Error('投递区根目录格式无效')
      assertMailboxSubdirectory(subdirectory)
      return Promise.reject(new Error('浏览器预览不支持浏览投递区'))
    },
    createMailboxFolder: (root: MailboxRoot, subdirectory: string): Promise<MailboxDirectoryState> => {
      if (root !== 'inbox' && root !== 'outbox') throw new Error('投递区根目录格式无效')
      assertMailboxSubdirectory(subdirectory)
      return Promise.reject(new Error('浏览器预览不支持创建投递区目录'))
    },
    // 浏览器模式下没有共享目录：白名单（`/mnt/user/<序号>`）只存在于设备上。
    // 明确拒绝，而不是回一份空快照——空快照会被界面显示成「这个目录是空的」。
    getStorageDirectory: (): Promise<StorageDirectoryState> =>
      Promise.reject(new Error('浏览器模式下没有共享目录')),
    createStorageFolder: (): Promise<StorageDirectoryState> =>
      Promise.reject(new Error('浏览器模式下没有共享目录')),
    getStorageAccessState: (): Promise<StorageAccessState> => Promise.resolve({
      mediaGranted: false,
      allFilesGranted: false,
      allFilesSupported: false,
      sdkInt: 0,
    }),
    // 浏览器里没有可申请的 Android 权限：不弹任何东西，也不假装已授权。
    requestMediaPermission: () => Promise.resolve({ granted: false }),
    openAllFilesAccessSettings: () => Promise.resolve({ supported: false, granted: false }),
    // 没有真实文件系统可搬运：明确拒绝，不编造条目数与摘要。
    importMailbox: () => Promise.reject(new Error('浏览器预览不支持导入投递区')),
    exportMailbox: (subdirectory, destinationDirectory) => {
      assertMailboxSubdirectory(subdirectory)
      assertMailboxSubdirectory(destinationDirectory)
      return Promise.reject(new Error('浏览器预览不支持导出投递区'))
    },
    /**
     * 浏览器预览没有 Android 的共享存储、没有 SAF 选择器，也没有 PRoot 访客：
     * 目录白名单**如实报不可用**（空列表 + `supported`/`granted` 全 false）。
     *
     * 与投递区同一口径：不编造一份「看起来能用」的状态。上限照实回传 8 —— 它是文档里的固定值，
     * 界面据此显示「0/8」而不是把上限也藏起来。
     */
    getStorageDirs: (): Promise<StorageDirsState> => Promise.resolve({
      entries: [],
      maxDirectories: MAX_STORAGE_DIRECTORIES,
      count: 0,
      supported: false,
      granted: false,
      level: 'T0',
      active: false,
    }),
    // 浏览器里没有可弹的目录选择器：明确拒绝，不假装加了一条。
    addStorageDirectory: () => Promise.reject(new Error('浏览器预览不支持选择存储目录')),
    removeStorageDirectory: path => {
      assertStorageDirPath(path)
      return Promise.reject(new Error('浏览器预览不支持移除存储目录'))
    },
    // 浏览器预览里没有访客进程，也就没有可读的输出尾部：如实返回不可用，不编造内容。
    getHarnessLog: (options): Promise<HarnessLog> => Promise.resolve({
      available: false,
      text: '',
      maxBytes: options?.maxBytes === 64 * 1024 || options?.maxBytes === 256 * 1024 ? options.maxBytes : 8 * 1024,
    }),
    // 浏览器预览没有访客运行时，也就没有可自检的链路：如实拒绝，不编造一份「全部正常」的结果。
    runRuntimeSelfCheck: (operation: SelfCheckOperation): Promise<SelfCheckReport> => {
      validateSelfCheckOperation(operation)
      return Promise.reject(new Error('浏览器预览不支持运行时自检'))
    },
    // 浏览器预览里没有访客运行时，也就没有版本槽可列出或切换：如实拒绝，不编造版本列表。
    getRuntimeVersions: (): Promise<RuntimeVersionsState> => Promise.reject(new Error('浏览器预览不支持运行时版本管理')),
    switchRuntimeVersion: (target): Promise<RuntimeVersionsState> => {
      assertRuntimeVersionTarget(target)
      return Promise.reject(new Error('浏览器预览不支持运行时版本管理'))
    },
    deleteRuntimeVersion: (target): Promise<RuntimeVersionsState> => {
      assertRuntimeVersionTarget(target)
      return Promise.reject(new Error('浏览器预览不支持运行时版本管理'))
    },
    // 预览里既没有运行时目录，也谈不上「残留」：如实拒绝，不编造「0 份 0 字节」——
    // 那会被界面显示成「没有可回收的残留」，等于把「做不到」说成「确认干净」。
    getRuntimeResidue: (): Promise<RuntimeResidueState> => Promise.reject(new Error('浏览器预览不支持运行时占用盘点')),
    cleanRuntimeResidue: (): Promise<RuntimeResidueCleanupState> => Promise.reject(new Error('浏览器预览不支持运行时残留清理')),
    // 会话工作区在预览里没有原生侧那条只读通道：如实拒绝，让界面显示「读不到会话列表」+
    // 一句诚实说明。**不要**返回 `{ status: 'ready', sessions: [] }`——那会显示成「暂无会话」，
    // 等于把「读不到」编造成「确认没有」。
    listSessions: (): Promise<RuntimeSessionListResult> =>
      Promise.reject(new Error('浏览器模式下没有运行时会话列表')),
    // 预览里没有原生侧的会话快照能力：四条一律如实拒绝。
    // **不要**返回空状态对象——那会让界面显示成「还没有快照」，等于编造一次成功。
    getRuntimeSessionSnapshotState: (): Promise<RuntimeSessionSnapshotState> =>
      Promise.reject(new Error('浏览器模式下没有运行时会话快照')),
    createRuntimeSessionSnapshot: (): Promise<RuntimeSessionSnapshotState> =>
      Promise.reject(new Error('浏览器模式下不能创建运行时会话快照')),
    restoreRuntimeSessionSnapshot: (): Promise<RuntimeSessionSnapshotRestoreResult> =>
      Promise.reject(new Error('浏览器模式下不能恢复运行时会话快照')),
    deleteRuntimeSessionSnapshot: (): Promise<RuntimeSessionSnapshotState> =>
      Promise.reject(new Error('浏览器模式下不能删除运行时会话快照')),
    // 浏览器预览里没有原生侧的更新渠道，也没有可下载安装的 APK：如实拒绝，
    // 不编造版本列表或更新状态，否则界面会把「预览里的假版本」当成真能装的东西。
    listRuntimeReleases: (): Promise<RuntimeReleaseList> => Promise.reject(new Error('浏览器模式下没有更新渠道')),
    getAppUpdateState: (): Promise<AppUpdateState> => Promise.reject(new Error('浏览器模式下没有应用更新')),
    downloadAppUpdate: (): Promise<void> => Promise.reject(new Error('浏览器模式下不能下载安装包')),
    installAppUpdate: (): Promise<void> => Promise.reject(new Error('浏览器模式下不能安装应用')),
    openAppUpdateInstallSettings: (): Promise<void> => Promise.reject(new Error('浏览器模式下不能打开安装授权页面')),
    // 浏览器预览没有原生诊断日志：保持关闭且不可导出，避免给出「已经采集到东西」的错觉。
    getDiagnosticLogState: (): Promise<DiagnosticLogState> => Promise.resolve({ ...diagnosticState }),
    // 同理，预览里没有可查看的正文；返回空窗口而不是编造几条假记录。
    readDiagnosticLog: (options): Promise<DiagnosticLogText> => Promise.resolve({
      text: '',
      maxBytes: options?.maxBytes === 256 * 1024 ? 256 * 1024 : 64 * 1024,
      totalBytes: diagnosticState.totalBytes,
      truncated: false,
    }),
    setDiagnosticLogSettings: (enabled: boolean, retentionDays: number) => {
      diagnosticState = { ...diagnosticState, enabled, retentionDays }
      return Promise.resolve({ ...diagnosticState })
    },
    shareDiagnosticLog: () => Promise.reject(new Error('浏览器预览不支持导出诊断日志')),
    shareRuntimeWorkspace: () => Promise.reject(new Error('浏览器预览不支持分享运行时工作区')),
    listRuntimeWorkspaceFiles: () => Promise.reject(new Error('浏览器预览不支持读取运行时工作区')),
    shareRuntimeWorkspaceFile: () => Promise.reject(new Error('浏览器预览不支持分享运行时文件')),
    openRuntimeWorkspaceFile: () => Promise.reject(new Error('浏览器预览不支持打开运行时文件')),
    deleteRuntimeWorkspaceFile: () => Promise.reject(new Error('浏览器预览不支持删除运行时文件')),
    clearDiagnosticLog: () => {
      diagnosticState = { ...diagnosticState, fileCount: 0, totalBytes: 0, lastEntryAtMillis: 0 }
      return Promise.resolve({ ...diagnosticState })
    },
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
