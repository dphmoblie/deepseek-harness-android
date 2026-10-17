import { validatePluginCatalog, validatePluginRequest } from './plugins'
import { harnessVersionRequest, validateHarnessReleases, validateHarnessVersions, type HarnessVersionOperation } from './harnessVersions'
import { validateInstalledApplications } from './installedApplications'
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
  MailboxRoot,
  NotificationPermissionResult,
  RuntimeBridge,
  AppUpdateProgress,
  RuntimeProgress,
  RuntimeSettings,
  RuntimeSettingsUpdate,
  RuntimeSource,
  RuntimeState,
  ShizukuState,
  TerminalChunk,
  TerminalExit,
  TerminalKind,
  VirtualScreenSettingsUpdate,
  VirtualScreenStartRequest,
} from './types'
import {
  ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES,
  type AccessibilityAutomationAction,
  type AccessibilityAutomationRuleInput,
  type AccessibilityAutomationRuleWriteMode,
} from './types'
import {
  assertBase64Input,
  assertDiagnosticRetentionDays,
  assertMailboxSubdirectory,
  assertRuntimeVersionTarget,
  assertRuntimeVersionPinTarget,
  assertSessionId,
  assertStorageDirPath,
  assertTerminalKind,
  assertTerminalSize,
  assertVirtualScreenSettingsUpdate,
  assertVirtualScreenStartRequest,
  validateAllFilesAccessResult,
  validateAppUpdateState,
  assertAccessibilityAutomationAction,
  assertAccessibilityAutomationAppConfigInput,
  assertAccessibilityAutomationRuleInput,
  assertAccessibilityPackageName,
  assertAccessibilityRuleId,
  assertAccessibilityRuleWriteMode,
  assertBooleanFlag,
  validateAccessibilityAutomationAppConfigDetail,
  validateAccessibilityAutomationRuleDetail,
  validateAccessibilityAutomationRules,
  validateAccessibilityAutomationRuns,
  validateAccessibilityAutomationState,
  validateAccessibilityPasswordInput,
  validateDeviceShellAccess,
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
  validateMailboxDirectoryState,
  validateMailboxState,
  validateMediaPermissionResult,
  validateModelConnectivityResult,
  validateNotificationPermissionResult,
  validateOverlayBallState,
  validateRuntimeProgress,
  validateAppUpdateProgress,
  validateRuntimeInstallResult,
  validateRuntimeReleaseList,
  validateRuntimeCleanupOutcome,
  validateRuntimeCleanupState,
  validateDeviceCapabilityReport,
  validateRootEngineStatus,
  validateAutomationTaskState,
  validatePluginCleanupRequest,
  validateRuntimeResidue,
  validateRuntimeResidueCleanup,
  validateRuntimeSessionListResult,
  validateRuntimeSessionSnapshotRestoreResult,
  validateRuntimeSessionSnapshotState,
  validateRuntimeWorkspaceFileList,
  validateRuntimeWorkspaceFilePath,
  validateRuntimeSelfCheckReport,
  validateRuntimeState,
  validateRuntimeVersions,
  validateSettings,
  validateSettingsUpdate,
  validateShizukuState,
  validateStorageAccessState,
  validateStorageDirectoryState,
  validateStorageDirsState,
  validateStoredSettings,
  validateRuntimeSource,
  validateTerminalChunk,
  validateTerminalExit,
  validateTerminalSession,
  validateVirtualScreenSettings,
  validateVirtualScreenState,
} from './validation'

interface NativeRuntimePlugin {
  listHarnessReleases(): Promise<unknown>
  manageHarnessVersions(options: { operation: HarnessVersionOperation; version?: string }): Promise<unknown>
  managePlugins(options: PluginRequest): Promise<PluginCatalog>
  setAppLanguage(options: { language: 'zh-CN' | 'en' }): Promise<void>
  /** 同步主题模式给原生，由原生把它落到状态栏（登记册 5.4）。 */
  setAppTheme(options: { mode: AppThemeMode }): Promise<void>
  getState(): Promise<RuntimeState>
  getSettings(): Promise<RuntimeSettings>
  saveSettings(settings: RuntimeSettingsUpdate): Promise<RuntimeSettings>
  install(source?: RuntimeSource): Promise<unknown>
  /** 会话工作区的会话元数据；返回值在 TS 侧走校验（`unknown` → validate）。 */
  sessionList(): Promise<unknown>
  getRuntimeSessionSnapshotState(): Promise<unknown>
  createRuntimeSessionSnapshot(): Promise<unknown>
  restoreRuntimeSessionSnapshot(options: { id: string }): Promise<unknown>
  deleteRuntimeSessionSnapshot(options: { id: string }): Promise<unknown>
  startHarness(): Promise<RuntimeState>
  openHarness(options?: { sessionId?: string }): Promise<void>
  testModelConnectivity(options: { providerId: string; baseUrl?: string; apiKey?: string; api?: string }): Promise<unknown>
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
  openVirtualScreen(): Promise<void>
  /** 副屏设置：读全量、写局部；写请求只带这次要改的字段。 */
  getVirtualScreenSettings(): Promise<unknown>
  setVirtualScreenSettings(options: VirtualScreenSettingsUpdate): Promise<void>
  /** 目标应用副屏的启动/停止；启动参数里的目标必须是包名或完整组件名。 */
  startVirtualScreen(options: VirtualScreenStartRequest): Promise<void>
  stopVirtualScreen(): Promise<void>
  /** 副屏运行期读数；读不到时原生侧应抛错，本层不做兜底。 */
  getVirtualScreenState(): Promise<unknown>
  getAccessibilityAutomationState(): Promise<unknown>
  listInstalledApplications(options: { query: string; offset: number }): Promise<unknown>
  getDeviceShellAccess(): Promise<unknown>
  setDeviceShellAccess(options: { enabled: boolean }): Promise<unknown>
  /** 保存白名单；`password` 只在用户已设置验证密码时出现，原生侧负责比对。 */
  setAccessibilityAutomationPackages(options: { packages: string[]; password?: string; whitelistEnabled?: boolean }): Promise<unknown>
  /** 设置/修改验证密码；首次设置时 `currentPassword` 不出现。 */
  setAccessibilityPassword(options: { password: string; currentPassword?: string }): Promise<unknown>
  /** 清除验证密码（白名单保留）；必须带当前密码。 */
  clearAccessibilityPassword(options: { currentPassword: string }): Promise<unknown>
  /** 用系统生物识别 / 锁屏密码重置验证密码；只清密码、保留白名单。 */
  resetAccessibilityPasswordWithBiometric(): Promise<unknown>
  /** 改自动化总开关；只接受四个受控动作名，未知取值原生侧拒收。 */
  setAccessibilityAutomationRuntime(options: { action: AccessibilityAutomationAction }): Promise<unknown>
  /** 读自动化运行记录与拦截原因；仅在本次无障碍服务会话内有效。 */
  getAccessibilityAutomationRuns(options: { packageName?: string }): Promise<unknown>
  /** 读某个包已保存的自动化规则（只读，不打开任何东西）；包名必填。 */
  getAccessibilityAutomationRules(options: { packageName: string }): Promise<unknown>
  /** 读一条规则的全文（选择器与动作参数），给编辑表单预填；只读，白名单外的包也读得出。 */
  getAccessibilityAutomationRule(options: { packageName: string; ruleId: string }): Promise<unknown>
  /** 启用 / 停用一条规则；白名单外的包原生侧拒写。 */
  setAccessibilityAutomationRuleEnabled(options: {
    packageName: string
    ruleId: string
    enabled: boolean
  }): Promise<unknown>
  /** 新建或整条改写一条规则；`mode` 不隐式推断（撞 id / 找不到都必须报出来）。 */
  saveAccessibilityAutomationRule(options: {
    packageName: string
    mode: AccessibilityAutomationRuleWriteMode
    rule: AccessibilityAutomationRuleInput
  }): Promise<unknown>
  /** 删除一条规则；找不到就报错，不做静默成功。 */
  deleteAccessibilityAutomationRule(options: { packageName: string; ruleId: string }): Promise<unknown>
  /**
   * 读某个应用的自动化参数（按应用暂停开关 + 默认值）；只读，白名单外的包也读得出。
   *
   * 坏数据不抛错：原生把它按「已暂停」返回并带上 `corrupt`，由界面说明。
   */
  getAccessibilityAutomationAppConfig(options: { packageName: string }): Promise<unknown>
  /** 整条覆盖某个应用的自动化参数（四个字段全给，不合并）；白名单外的包原生侧拒写。 */
  setAccessibilityAutomationAppConfig(options: {
    packageName: string
    paused: boolean
    defaultMaxActions: number
    defaultActionCoolDownMs: number
    defaultMatchDelayMs: number
  }): Promise<unknown>
  openAccessibilitySettings(): Promise<void>
  getKeepAliveState(): Promise<KeepAliveState>
  requestNotificationPermission(): Promise<NotificationPermissionResult>
  openNotificationSettings(): Promise<void>
  openApplicationSettings(): Promise<void>
  overlayBallState(): Promise<unknown>
  openOverlaySettings(): Promise<void>
  mailboxState(): Promise<unknown>
  mailboxDirectory(options: { root: MailboxRoot; subdirectory?: string }): Promise<unknown>
  createMailboxFolder(options: { root: MailboxRoot; subdirectory: string }): Promise<unknown>
  storageDirectory(options: { guestPath: string; subdirectory?: string }): Promise<unknown>
  createStorageFolder(options: { guestPath: string; subdirectory: string }): Promise<unknown>
  getStorageAccessState(): Promise<unknown>
  requestMediaPermission(): Promise<unknown>
  openAllFilesAccessSettings(): Promise<unknown>
  importMailbox(): Promise<unknown>
  exportMailbox(options: { subdirectory?: string; destinationDirectory?: string }): Promise<unknown>
  storageDirsState(): Promise<unknown>
  addStorageDirectory(): Promise<unknown>
  removeStorageDirectory(options: { path: string }): Promise<unknown>
  getHarnessLog(options: { maxBytes?: number }): Promise<unknown>
  runRuntimeSelfCheck(options: { operation: SelfCheckOperation }): Promise<unknown>
  runtimeVersions(): Promise<unknown>
  switchRuntimeVersion(options: { target: string }): Promise<unknown>
  deleteRuntimeVersion(options: { target: string }): Promise<unknown>
  setRuntimeVersionPinned(options: { target: string; pinned: boolean }): Promise<unknown>
  getRuntimeResidue(): Promise<unknown>
  cleanRuntimeResidue(): Promise<unknown>
  getRuntimeCleanupState(): Promise<unknown>
  getDeviceCapabilities(): Promise<unknown>
  /** 根通道状态：`refresh` 为真时让原生丢弃 60 秒探测器缓存重测。 */
  rootEngineState(options?: { refresh?: boolean }): Promise<unknown>
  setRootEngineEnabled(options: { enabled: boolean }): Promise<unknown>
  getAutomationTaskState(): Promise<unknown>
  cancelAutomationTask(): Promise<unknown>
  clearRuntimeCache(): Promise<unknown>
  purgePluginData(options?: { packageName?: string }): Promise<unknown>
  /** 列出原生侧已知的运行时可用版本；结果由 TS 侧校验后再交给界面。 */
  listRuntimeReleases(): Promise<unknown>
  downloadRuntimeRelease(options: { manifestUrl: string; manifestSha256: string }): Promise<unknown>
  getAppUpdateState(options?: { forceRefresh: boolean }): Promise<unknown>
  setAppUpdateChannel(options: { channel: string }): Promise<unknown>
  downloadAppUpdate(): Promise<void>
  installAppUpdate(): Promise<void>
  openAppUpdateInstallSettings(): Promise<void>
  getDiagnosticLogState(): Promise<DiagnosticLogState>
  readDiagnosticLog(options: { maxBytes?: number }): Promise<unknown>
  setDiagnosticLogSettings(options: { enabled: boolean; retentionDays: number }): Promise<DiagnosticLogState>
  shareDiagnosticLog(): Promise<DiagnosticLogExport>
  shareRuntimeWorkspace(): Promise<void>
  listRuntimeWorkspaceFiles(): Promise<unknown>
  shareRuntimeWorkspaceFile(options: { path: string }): Promise<void>
  openRuntimeWorkspaceFile(options: { path: string }): Promise<void>
  deleteRuntimeWorkspaceFile(options: { path: string }): Promise<void>
  clearDiagnosticLog(): Promise<DiagnosticLogState>
  addListener(eventName: 'runtimeProgress', listener: (event: RuntimeProgress) => void): Promise<PluginListenerHandle>
  addListener(eventName: 'appUpdateProgress', listener: (event: AppUpdateProgress) => void): Promise<PluginListenerHandle>
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
    listHarnessReleases: () => NativeRuntime.listHarnessReleases().then(validateHarnessReleases),
    manageHarnessVersions: (operation, version) => NativeRuntime.manageHarnessVersions(harnessVersionRequest(operation, version)).then(validateHarnessVersions),
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
    install: source => NativeRuntime
      .install(source === undefined ? undefined : validateRuntimeSource(source))
      .then(validateRuntimeInstallResult),
    startHarness: () => NativeRuntime.startHarness().then(validateRuntimeState),
    openHarness: (sessionId?: string) => NativeRuntime.openHarness(sessionId ? { sessionId } : undefined),
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
    getDeviceShellAccess: () => NativeRuntime.getDeviceShellAccess().then(validateDeviceShellAccess),
    setDeviceShellAccess: enabled => {
      if (typeof enabled !== 'boolean') return Promise.reject(new Error('AI Shell 授权状态格式无效'))
      return NativeRuntime.setDeviceShellAccess({ enabled }).then(validateDeviceShellAccess)
    },
    requestShizukuPermission: () => NativeRuntime.requestShizukuPermission().then(validateShizukuState),
    connectShizuku: () => NativeRuntime.connectShizuku().then(validateShizukuState),
    openShizuku: () => NativeRuntime.openShizuku(),
    openVirtualScreen: () => NativeRuntime.openVirtualScreen(),
    // 副屏：读回来的两份一律过校验（字段缺失就抛错，不补默认值，否则「读不到」会被显示成默认设置）；
    // 发出去的两条在过桥前自检，档位/方向/自动跟随与宽高 DPI 的非法取值在这里就被拒绝。
    getVirtualScreenSettings: () => NativeRuntime.getVirtualScreenSettings().then(validateVirtualScreenSettings),
    setVirtualScreenSettings: update => NativeRuntime.setVirtualScreenSettings(assertVirtualScreenSettingsUpdate(update)),
    startVirtualScreen: request => NativeRuntime.startVirtualScreen(assertVirtualScreenStartRequest(request)),
    stopVirtualScreen: () => NativeRuntime.stopVirtualScreen(),
    getVirtualScreenState: () => NativeRuntime.getVirtualScreenState().then(validateVirtualScreenState),
    getAccessibilityAutomationState: () => NativeRuntime.getAccessibilityAutomationState().then(validateAccessibilityAutomationState),
    listInstalledApplications: (query, offset) => {
      if (typeof query !== 'string' || query.length > 160 || [...query].some(char => char.charCodeAt(0) < 0x20 || char.charCodeAt(0) === 0x7f) || !Number.isSafeInteger(offset) || offset < 0) {
        return Promise.reject(new Error('应用筛选参数无效'))
      }
      return NativeRuntime.listInstalledApplications({ query, offset }).then(validateInstalledApplications)
    },
    // 白名单：发送前先自检一遍形状（含自动项与密码状态这几个必填字段），
    // 密码只在用户带了的时候校验——未设置密码的设备上，undefined 是合法取值。
    setAccessibilityAutomationPackages: (packages, password, whitelistEnabled) => {
      if (!Array.isArray(packages) || packages.some(value => typeof value !== 'string')) {
        return Promise.reject(new Error('无障碍白名单格式无效'))
      }
      const validatedPassword = password === undefined
        ? undefined
        : validateAccessibilityPasswordInput(password, '验证密码')
      validateAccessibilityAutomationState({
        enabled: false,
        whitelistEnabled,
        allowedPackages: packages,
        alwaysAllowedPackages: ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES,
        passwordConfigured: false,
      })
      // 可选字段只在有值时出现：与文件内其他方法保持同一写法（原生侧据「有没有这个键」区分
      // 「本次不带密码」和「带了一个空密码」）。
      return NativeRuntime.setAccessibilityAutomationPackages({
        packages,
        ...(whitelistEnabled === undefined ? {} : { whitelistEnabled }),
        ...(validatedPassword === undefined ? {} : { password: validatedPassword }),
      }).then(validateAccessibilityAutomationState)
    },
    setAccessibilityPassword: (password, currentPassword) => {
      const next = validateAccessibilityPasswordInput(password, '验证密码')
      const current = currentPassword === undefined
        ? undefined
        : validateAccessibilityPasswordInput(currentPassword, '当前验证密码')
      return NativeRuntime.setAccessibilityPassword({
        password: next,
        ...(current === undefined ? {} : { currentPassword: current }),
      }).then(validateAccessibilityAutomationState)
    },
    clearAccessibilityPassword: currentPassword => NativeRuntime
      .clearAccessibilityPassword({ currentPassword: validateAccessibilityPasswordInput(currentPassword, '当前验证密码') })
      .then(validateAccessibilityAutomationState),
    resetAccessibilityPasswordWithBiometric: () => NativeRuntime
      .resetAccessibilityPasswordWithBiometric()
      .then(validateAccessibilityAutomationState),
    // 总开关：动作名在这里先按白名单卡一道（原生侧也会拒收未知取值），读回的状态仍过同一套校验。
    // 未知动作名要走**被拒绝的 Promise**（同步抛出会绕过调用方的失败分支），所以先折进 `Promise.resolve()`。
    setAccessibilityAutomationRuntime: action => Promise.resolve()
      .then(() => assertAccessibilityAutomationAction(action))
      .then(validated => NativeRuntime.setAccessibilityAutomationRuntime({ action: validated }))
      .then(validateAccessibilityAutomationState),
    // 运行记录：包名留空即跨包读；给了包名先按同一套规则自检，避免把任意字符串递给原生。
    getAccessibilityAutomationRuns: packageName => Promise.resolve()
      .then(() => (packageName === undefined ? {} : { packageName: assertAccessibilityPackageName(packageName) }))
      .then(options => NativeRuntime.getAccessibilityAutomationRuns(options))
      .then(validateAccessibilityAutomationRuns),
    // 规则：只读某个包已保存的规则；包名必填且先按同一套格式自检（不提供「省略即前台包」的兜底）。
    getAccessibilityAutomationRules: packageName => Promise.resolve()
      .then(() => ({ packageName: assertAccessibilityPackageName(packageName) }))
      .then(options => NativeRuntime.getAccessibilityAutomationRules(options))
      .then(validateAccessibilityAutomationRules),
    // 一条规则的全文：给编辑表单预填用。只读、不写盘；判断「能不能改」是界面的事（看返回里的 allowed）。
    getAccessibilityAutomationRule: ({ packageName, ruleId }) => Promise.resolve()
      .then(() => ({
        owner: assertAccessibilityPackageName(packageName),
        id: assertAccessibilityRuleId(ruleId),
      }))
      .then(({ owner, id }) =>
        NativeRuntime.getAccessibilityAutomationRule({ packageName: owner, ruleId: id }).then(value =>
          validateAccessibilityAutomationRuleDetail(value, owner, id),
        ),
      ),
    // 单条规则的启用 / 停用：白名单校验在原生侧（写规则必须过白名单），这里只保证参数形态正确。
    setAccessibilityAutomationRuleEnabled: ({ packageName, ruleId, enabled }) => Promise.resolve()
      .then(() => ({
        packageName: assertAccessibilityPackageName(packageName),
        ruleId: assertAccessibilityRuleId(ruleId),
        enabled: assertBooleanFlag(enabled, '规则启用状态'),
      }))
      .then(options => NativeRuntime.setAccessibilityAutomationRuleEnabled(options))
      .then(validateAccessibilityAutomationRules),
    // 新建 / 改写：白名单与内容规则都在原生侧判定，这边只保证「结构明显不合法」的东西不过桥。
    // 写法与上一条一致——入参校验折进 Promise 链，失败同样落在调用方的 catch 分支里。
    saveAccessibilityAutomationRule: ({ packageName, mode, rule }) => Promise.resolve()
      .then(() => {
        const owner = assertAccessibilityPackageName(packageName)
        return {
          packageName: owner,
          mode: assertAccessibilityRuleWriteMode(mode),
          rule: assertAccessibilityAutomationRuleInput(rule, owner),
        }
      })
      .then(options => NativeRuntime.saveAccessibilityAutomationRule(options))
      .then(validateAccessibilityAutomationRules),
    // 删除：只按 id 删；返回的仍是写完之后整包读数，界面据此刷新，不再发一次读请求。
    deleteAccessibilityAutomationRule: ({ packageName, ruleId }) => Promise.resolve()
      .then(() => ({
        packageName: assertAccessibilityPackageName(packageName),
        ruleId: assertAccessibilityRuleId(ruleId),
      }))
      .then(options => NativeRuntime.deleteAccessibilityAutomationRule(options))
      .then(validateAccessibilityAutomationRules),
    // 每应用参数：读是只读（坏数据也不抛错，原生按「已暂停」返回并带 corrupt），写是整条替换。
    // 两边都先自检包名——写参数的路径上「包名写错」与写规则一样代价很大。
    getAccessibilityAutomationAppConfig: ({ packageName }) => Promise.resolve()
      .then(() => ({ owner: assertAccessibilityPackageName(packageName) }))
      .then(({ owner }) =>
        NativeRuntime.getAccessibilityAutomationAppConfig({ packageName: owner }).then(value =>
          validateAccessibilityAutomationAppConfigDetail(value, owner),
        ),
      ),
    // 四个字段全给：这里不做「缺省补默认值」，缺了就在上桥前报错（原生也会拒收）。
    setAccessibilityAutomationAppConfig: ({
      packageName,
      paused,
      defaultMaxActions,
      defaultActionCoolDownMs,
      defaultMatchDelayMs,
    }) => Promise.resolve()
      .then(() => {
        const owner = assertAccessibilityPackageName(packageName)
        return {
          owner,
          options: {
            packageName: owner,
            ...assertAccessibilityAutomationAppConfigInput({
              paused,
              defaultMaxActions,
              defaultActionCoolDownMs,
              defaultMatchDelayMs,
            }),
          },
        }
      })
      .then(({ owner, options }) =>
        NativeRuntime.setAccessibilityAutomationAppConfig(options).then(value =>
          validateAccessibilityAutomationAppConfigDetail(value, owner),
        ),
      ),
    openAccessibilitySettings: () => NativeRuntime.openAccessibilitySettings(),
    getKeepAliveState: () => NativeRuntime.getKeepAliveState().then(validateKeepAliveState),
    requestNotificationPermission: () => NativeRuntime.requestNotificationPermission().then(validateNotificationPermissionResult),
    openNotificationSettings: () => NativeRuntime.openNotificationSettings(),
    openApplicationSettings: () => NativeRuntime.openApplicationSettings(),
    getOverlayBallState: () => NativeRuntime.overlayBallState().then(validateOverlayBallState),
    openOverlaySettings: () => NativeRuntime.openOverlaySettings(),
    getMailboxState: () => NativeRuntime.mailboxState().then(validateMailboxState),
    getMailboxDirectory: (root, subdirectory) => NativeRuntime
      .mailboxDirectory({ root, ...(subdirectory === undefined ? {} : { subdirectory }) })
      .then(validateMailboxDirectoryState),
    createMailboxFolder: (root, subdirectory) => NativeRuntime
      .createMailboxFolder({ root, subdirectory })
      .then(validateMailboxDirectoryState),
    // 共享目录：与投递区逐字对齐的两条。`guestPath` 只来自白名单条目的同名字段，
    // 其形态由原生侧与 validateStorageDirectoryState 两侧各守一遍。
    getStorageDirectory: (guestPath, subdirectory) => NativeRuntime
      .storageDirectory({ guestPath, ...(subdirectory === undefined ? {} : { subdirectory }) })
      .then(validateStorageDirectoryState),
    createStorageFolder: (guestPath, subdirectory) => NativeRuntime
      .createStorageFolder({ guestPath, subdirectory })
      .then(validateStorageDirectoryState),
    getStorageAccessState: () => NativeRuntime.getStorageAccessState().then(validateStorageAccessState),
    requestMediaPermission: () => NativeRuntime.requestMediaPermission().then(validateMediaPermissionResult),
    openAllFilesAccessSettings: () => NativeRuntime.openAllFilesAccessSettings().then(validateAllFilesAccessResult),
    importMailbox: () => NativeRuntime.importMailbox().then(validateMailboxImportResult),
    // 导出起点先在前端拦一道明显非法的取值（绝对路径、`..`），原生侧还有同一套规则兜底。
    exportMailbox: (subdirectory, destinationDirectory) => {
      const target = assertMailboxSubdirectory(subdirectory)
      const destination = assertMailboxSubdirectory(destinationDirectory)
      return NativeRuntime.exportMailbox({
        ...(target === undefined ? {} : { subdirectory: target }),
        ...(destination === undefined ? {} : { destinationDirectory: destination }),
      })
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
    // 锁定/解锁只改「满额时谁可以被自动淘汰」，不影响当前运行环境，所以随时可调。
    // 目标必须是版本库里的归档 ID（`previous` / `bundled` 是槽位、不是归档）；
    // 布尔值走 assertBooleanFlag，不让 `'false'` 这类真值字符串把版本悄悄锁上。
    setRuntimeVersionPinned: (target, pinned) => NativeRuntime
      .setRuntimeVersionPinned({
        target: assertRuntimeVersionPinTarget(target),
        pinned: assertBooleanFlag(pinned, '版本库锁定状态'),
      })
      .then(validateRuntimeVersions),
    // 残留载荷只有份数/字节数/截断标记与一句说明：原生侧连目录名都不回传，这里也就无从泄漏；
    // 清理幂等且不抛，失败（含单份删不掉）由 failed 与 message 如实体现。
    getRuntimeResidue: () => NativeRuntime.getRuntimeResidue().then(validateRuntimeResidue),
    cleanRuntimeResidue: () => NativeRuntime.cleanRuntimeResidue().then(validateRuntimeResidueCleanup),
    // 分级清理：读数是「白名单条目的现状 + 账本的结论 + 逐包现状」，写操作只有两档，都要求运行时
    // 已停止（在跑时原生侧抛 `RUNTIME_BUSY`）；`ok` 由 `failed === 0` 推出，载荷里的同名字段不采信。
    getRuntimeCleanupState: () => NativeRuntime.getRuntimeCleanupState().then(validateRuntimeCleanupState),
    getDeviceCapabilities: () => NativeRuntime.getDeviceCapabilities().then(validateDeviceCapabilityReport),
    // 根通道：关着时原生只读设置（不会拉 `su`），所以轮询它是便宜的；切换开关时原生会顺手清缓存，
    // 因此这里的返回值就是重测结果，界面不必再补一次 refresh。
    getRootEngineStatus: refresh => NativeRuntime.rootEngineState({ refresh: refresh === true }).then(validateRootEngineStatus),
    setRootEngineEnabled: enabled => NativeRuntime.setRootEngineEnabled({ enabled }).then(validateRootEngineStatus),
    // 任务台账：`connected=false` 表示无障碍服务没连上（此时 `task` 一定是 null）——界面必须把
    // 「读不到」与「现在没有任务」分开说；校验器也按这个前提拒收自相矛盾的载荷。
    getAutomationTaskState: () => NativeRuntime.getAutomationTaskState().then(validateAutomationTaskState),
    // 取消是**人**的刹车（设置页按钮）：只把未收口的步骤记成 skipped，
    // 已经发出去的那一次动作不会被假装没发生。AI 侧没有这条路。
    cancelAutomationTask: () => NativeRuntime.cancelAutomationTask().then(validateAutomationTaskState),
    clearRuntimeCache: () => NativeRuntime.clearRuntimeCache().then(validateRuntimeCleanupOutcome),
    // 逐包清理：入参先在这里自检（折进 Promise 链，见下面的注释），只把合法的包名送到原生侧 ——
    // 原生侧还会再判一次，两处都必须过；省略包名表示整档清空。
    purgePluginData: (options?: { packageName?: string }) =>
      Promise.resolve()
        .then(() => validatePluginCleanupRequest(options))
        .then(request => NativeRuntime.purgePluginData(request))
        .then(validateRuntimeCleanupOutcome),
    // 会话工作区：只取元数据（标识/标题/更新时间），读不到时原生侧回受控错误字段而不是空列表，
    // 这里也不把「读不到」改写成空列表——校验器只认这两种形状。
    listSessions: () => NativeRuntime.sessionList().then(validateRuntimeSessionListResult),
    // 快照标识的形态由原生侧把关（`RUNTIME_SNAPSHOT_ID_INVALID`）：前端原样传过去，
    // 不在这里另造一套错误文案，避免两边对「什么算合法 id」说法不一。
    getRuntimeSessionSnapshotState: () => NativeRuntime
      .getRuntimeSessionSnapshotState()
      .then(validateRuntimeSessionSnapshotState),
    createRuntimeSessionSnapshot: () => NativeRuntime
      .createRuntimeSessionSnapshot()
      .then(validateRuntimeSessionSnapshotState),
    restoreRuntimeSessionSnapshot: id => NativeRuntime
      .restoreRuntimeSessionSnapshot({ id })
      .then(validateRuntimeSessionSnapshotRestoreResult),
    deleteRuntimeSessionSnapshot: id => NativeRuntime
      .deleteRuntimeSessionSnapshot({ id })
      .then(validateRuntimeSessionSnapshotState),
    // 可用版本列表由原生侧给出：缺清单的条目只可展示，校验侧不允许它混成「可安装」。
    listRuntimeReleases: () => NativeRuntime.listRuntimeReleases().then(validateRuntimeReleaseList),
    downloadRuntimeRelease: (manifestUrl, manifestSha256) =>
      NativeRuntime.downloadRuntimeRelease({ manifestUrl, manifestSha256 }).then(() => undefined),
    getAppUpdateState: (forceRefresh = false) => {
      if (typeof forceRefresh !== 'boolean') return Promise.reject(new Error('应用更新刷新参数无效'))
      return NativeRuntime.getAppUpdateState({ forceRefresh }).then(validateAppUpdateState)
    },
    setAppUpdateChannel: channel => {
      if (channel !== 'stable' && channel !== 'beta') return Promise.reject(new Error('应用更新频道参数无效'))
      return NativeRuntime.setAppUpdateChannel({ channel }).then(validateAppUpdateState)
    },
    // 下载与安装都不接收 URL：地址由原生侧自己决定，前端不能让它去取任意地址。
    downloadAppUpdate: () => NativeRuntime.downloadAppUpdate(),
    installAppUpdate: () => NativeRuntime.installAppUpdate(),
    openAppUpdateInstallSettings: () => NativeRuntime.openAppUpdateInstallSettings(),
    readDiagnosticLog: options => NativeRuntime.readDiagnosticLog({ maxBytes: options?.maxBytes }).then(validateDiagnosticLogText),
    getDiagnosticLogState: () => NativeRuntime.getDiagnosticLogState().then(validateDiagnosticLogState),
    setDiagnosticLogSettings: (enabled, retentionDays) => {
      const days = assertDiagnosticRetentionDays(retentionDays)
      return NativeRuntime.setDiagnosticLogSettings({ enabled, retentionDays: days }).then(validateDiagnosticLogState)
    },
    shareDiagnosticLog: () => NativeRuntime.shareDiagnosticLog().then(validateDiagnosticLogExport),
    shareRuntimeWorkspace: () => NativeRuntime.shareRuntimeWorkspace(),
    listRuntimeWorkspaceFiles: () => NativeRuntime.listRuntimeWorkspaceFiles().then(validateRuntimeWorkspaceFileList),
    shareRuntimeWorkspaceFile: path => NativeRuntime.shareRuntimeWorkspaceFile({ path: validateRuntimeWorkspaceFilePath(path) }),
    openRuntimeWorkspaceFile: path => NativeRuntime.openRuntimeWorkspaceFile({ path: validateRuntimeWorkspaceFilePath(path) }),
    deleteRuntimeWorkspaceFile: path => NativeRuntime.deleteRuntimeWorkspaceFile({ path: validateRuntimeWorkspaceFilePath(path) }),
    clearDiagnosticLog: () => NativeRuntime.clearDiagnosticLog().then(validateDiagnosticLogState),
    addRuntimeProgressListener: listener => NativeRuntime.addListener('runtimeProgress', validatedListener(validateRuntimeProgress, listener)),
    addAppUpdateProgressListener: listener => NativeRuntime.addListener('appUpdateProgress', validatedListener(validateAppUpdateProgress, listener)),
    addTerminalOutputListener: listener => NativeRuntime.addListener('terminalOutput', validatedListener(validateTerminalChunk, listener)),
    addTerminalExitListener: listener => NativeRuntime.addListener('terminalExit', validatedListener(validateTerminalExit, listener)),
    testModelConnectivity: options => NativeRuntime.testModelConnectivity(options).then(validateModelConnectivityResult),
  }
}

export const runtimeBridge: RuntimeBridge = Capacitor.isNativePlatform()
  ? createNativeBridge()
  : createBrowserBridge()
