/**
 * `App.*.test.tsx` 系列的共享夹具：桥接 mock、固定状态数据与「默认桩」。
 *
 * 为什么要有这个文件（而不是每个拆分文件各抄一份）：
 *  - `vi.mock` 是**文件级**的，拆分后每个测试文件都必须自己写一行
 *    `vi.mock('../platform/native', ...)` 与终端面板的 mock——这部分辅助模块代替不了；
 *    但被 mock 的**内容**（四十多个桥方法桩）与固定数据（readyState / settings / mailbox …）
 *    没有任何理由抄六份。抄六份的下场是改一处漏五处，而且各文件的「默认桩」会悄悄漂移：
 *    同一个界面在不同文件里被测到的初始前提就不一样了，失败时也没人知道该信哪一份。
 *  - 这里导出的 [bridge] 是**每个测试文件各自一份**实例（vitest 默认按测试文件隔离模块注册表），
 *    因此 `vi.clearAllMocks()` 只影响当前文件，隔离性与原先 `vi.hoisted` 的写法完全等价。
 *
 * 用法（固定三步，顺序不能变）：
 *  1. `vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))`；
 *  2. `beforeEach(beforeEachAppTest)`；
 *  3. 用例内按需覆盖某个桥方法的返回值，用例结束无需手动还原（beforeEach 会重设）。
 */
import { fireEvent, screen } from '@testing-library/react'
import { vi } from 'vitest'
import type {
  DiagnosticLogState,
  KeepAliveState,
  MailboxState,
  RuntimeSettings,
  RuntimeSettingsUpdate,
  RuntimeState,
  ShizukuState,
  StorageAccessState,
} from '../platform/types'

/**
 * 桥接 mock 本体。
 *
 * 用普通模块级常量而不是 `vi.hoisted`：`vi.hoisted` 的回调会在 import 之前执行，
 * 因此它不可能引用别的模块；而 `vi.mock` 的**工厂函数是惰性调用**的（首次 import 被 mock 的模块时才执行），
 * 那时本模块早已求值完毕，所以这里可以安全地被各测试文件的 `vi.mock` 工厂引用。
 */
export const bridge = {
  setAppLanguage: vi.fn(),
  /**
   * 主题模式同步给原生（§5.4 状态栏配色）。
   *
   * 这个桩是本文件里**唯一不是为了 §5.6-B 而加**的，补它是为了让仓库回到全绿：
   * `src/theme.ts` 的 `applyTheme()` 会调用它，而 `AppearanceSettings` 在挂载时就会调一次
   * `applyTheme`。提交 `caadeae`（状态栏跟随应用主题）把 `setAppTheme` 加进了
   * `src/platform/{native,types,browser}.ts` 与 `src/theme.ts`，却漏了这个共享桩 ——
   * 实测少了它，已提交的 `App.test.tsx` 里有 2 条会失败（「saves source and terminal
   * preferences from settings」与「设置切换立即生效且重新挂载后保留语言」）。
   *
   * 失败形态很难认：异常发生在 effect 阶段，React 直接**卸载整棵树**，
   * 表现成「找不到任何元素」，与真正的界面缺陷几乎无法区分。
   */
  setAppTheme: vi.fn(),
  getState: vi.fn(),
  getSettings: vi.fn(),
  saveSettings: vi.fn(),
  install: vi.fn(),
  startHarness: vi.fn(),
  openHarness: vi.fn(),
  stopRuntime: vi.fn(),
  reset: vi.fn(),
  createTerminal: vi.fn(),
  writeTerminal: vi.fn(),
  resizeTerminal: vi.fn(),
  closeTerminal: vi.fn(),
  getShizukuState: vi.fn(),
  requestShizukuPermission: vi.fn(),
  connectShizuku: vi.fn(),
  openShizuku: vi.fn(),
  getKeepAliveState: vi.fn(),
  getOverlayBallState: vi.fn(),
  openOverlaySettings: vi.fn(),
  getMailboxState: vi.fn(),
  getStorageAccessState: vi.fn(),
  // 存储目录白名单（§5.1）。界面接线尚未做，但桩必须先在：桥加了方法而夹具没加，
  // 用到它的界面会在 effect 阶段抛错并**整棵树卸载**，表现成「找不到任何元素」——
  // 与真正的界面缺陷几乎无法区分（这个坑已经踩过一次）。`appTestHarness.test.ts` 里有守卫。
  getStorageDirs: vi.fn(),
  addStorageDirectory: vi.fn(),
  removeStorageDirectory: vi.fn(),
  execDeviceCommand: vi.fn(),
  requestMediaPermission: vi.fn(),
  openAllFilesAccessSettings: vi.fn(),
  importMailbox: vi.fn(),
  exportMailbox: vi.fn(),
  requestNotificationPermission: vi.fn(),
  getHarnessLog: vi.fn(),
  runRuntimeSelfCheck: vi.fn(),
  // 运行时版本管理（双槽）：界面进「运行环境」页就会读一次列表，
  // 桩必须先在，否则那次 effect 会抛错并整棵树卸载（同上一条注释里的坑）。
  getRuntimeVersions: vi.fn(),
  switchRuntimeVersion: vi.fn(),
  deleteRuntimeVersion: vi.fn(),
  managePlugins: vi.fn(),
  getDiagnosticLogState: vi.fn(),
  readDiagnosticLog: vi.fn(),
  setDiagnosticLogSettings: vi.fn(),
  shareDiagnosticLog: vi.fn(),
  shareRuntimeWorkspace: vi.fn(),
  listRuntimeWorkspaceFiles: vi.fn(),
  shareRuntimeWorkspaceFile: vi.fn(),
  openRuntimeWorkspaceFile: vi.fn(),
  deleteRuntimeWorkspaceFile: vi.fn(),
  clearDiagnosticLog: vi.fn(),
  addRuntimeProgressListener: vi.fn(),
  addTerminalOutputListener: vi.fn(),
  addTerminalExitListener: vi.fn(),
}

export const readyState: RuntimeState = {
  phase: 'ready',
  architecture: 'arm64-v8a',
  installedVersion: '2026.08.17',
  updateAvailable: false,
  downloadedBytes: 640 * 1024 * 1024,
  totalBytes: 640 * 1024 * 1024,
  runnerAvailable: true,
}

export const runningState: RuntimeState = {
  ...readyState,
  phase: 'running',
  harnessUrl: 'http://127.0.0.1:3080/',
}

export const notInstalledState: RuntimeState = {
  phase: 'not-installed',
  architecture: 'arm64-v8a',
  updateAvailable: false,
  downloadedBytes: 0,
  totalBytes: readyState.totalBytes,
  runnerAvailable: true,
}

export const settings: RuntimeSettings = {
  manifestUrl: 'https://downloads.example.invalid/runtime.json',
  manifestSha256: 'a'.repeat(64),
  keepScreenAwake: true,
  terminalFontSize: 14,
  // 默认视为「本机已保存过一次模型密钥」：没有密钥时应用会拦住「打开 Harness」，
  // 而这里多数用例关心的是设置与启动流程本身，门禁行为有专门用例覆盖。
  configuredModelProviders: ['deepseek'],
}

export const shizuku: ShizukuState = {
  installed: true,
  running: true,
  permission: 'undetermined',
  connected: false,
}

export const keepAlive: KeepAliveState = {
  keepRuntimeInBackground: false,
  foregroundServiceActive: false,
  notificationPermission: 'granted',
  deviceShellReady: false,
  reconnectRequired: false,
  lastIntent: 'stopped',
}

export const diagnostic: DiagnosticLogState = {
  enabled: false,
  retentionDays: 3,
  fileCount: 0,
  totalBytes: 0,
  lastEntryAtMillis: 0,
}

/**
 * 投递区状态：默认取「系统支持但尚未授予所有文件访问」。
 *
 * 这是真机首次安装后的真实状态，也是界面必须如实降级的那一档；
 * 需要「可用」的用例自行覆盖（见 [availableMailbox]）。
 */
export const unavailableMailbox: MailboxState = {
  availability: 'needsPermission',
  level: 'T0',
  available: false,
  supported: true,
  granted: false,
  inboxPath: '/storage/emulated/0/Documents/DSH/inbox',
  outboxPath: '/storage/emulated/0/Documents/DSH/outbox',
  guestInboxPath: '/mnt/inbox',
  guestOutboxPath: '/mnt/outbox',
  inboxFileCount: 0,
  inboxTars: [],
  exportTarName: 'dsh-workspace.tar',
  exportManifestName: 'dsh-workspace.manifest.json',
  importDirectory: 'mailbox-import',
}

export const availableMailbox: MailboxState = {
  ...unavailableMailbox,
  availability: 'available',
  level: 'T2',
  available: true,
  granted: true,
  inboxFileCount: 2,
  inboxTars: [{ name: 'dsh-workspace.tar', bytes: 2048 }],
}

export const storageAccess: StorageAccessState = {
  mediaGranted: false,
  allFilesGranted: false,
  allFilesSupported: true,
  sdkInt: 34,
}

/**
 * `src/App.tsx` 里 `FOREGROUND_SERVICE_SETTLE_MS` 的镜像值。
 *
 * 「保存后复核前台服务」是应用侧的真实 1.5 秒定时器：用例必须把它推快，
 * 否则每条都要真实睡满 1.5 秒，而这段真实等待正是登记册 5.6-J 记下的那次 5 秒超时 flake 的来源。
 * 这个常量只用于测试里推进假定时器，改了 `App.tsx` 的宽限期就必须同步改这里。
 */
export const FOREGROUND_SERVICE_SETTLE_MS = 1500

/**
 * 各 `App.*.test.tsx` 的 beforeEach 统一入口。
 *
 * 固定成「本地存储复位 → 清空所有 mock → 铺默认桩」的顺序：
 * 默认桩必须建立在 clear 之后，否则某条用例自己设过的返回值会漏到下一条用例里。
 */
export function beforeEachAppTest(): void {
  window.localStorage.clear()
  window.localStorage.setItem('dsh-mobile-language-v1', 'zh-CN')
  window.localStorage.setItem('dsh-mobile-onboarding-v1', '1')
  vi.clearAllMocks()
  bridge.setAppLanguage.mockResolvedValue(undefined)
  bridge.setAppTheme.mockResolvedValue(undefined)
  bridge.getState.mockResolvedValue({ ...readyState })
  bridge.getSettings.mockResolvedValue({ ...settings })
  bridge.getShizukuState.mockResolvedValue({ ...shizuku })
  bridge.getKeepAliveState.mockResolvedValue({ ...keepAlive })
  // 默认已授予「显示在其他应用上层」权限：与用例无关的测试不该被一个禁用开关影响。
  bridge.getOverlayBallState.mockResolvedValue({ enabled: false, canDrawOverlays: true, serviceActive: false })
  bridge.openOverlaySettings.mockResolvedValue(undefined)
  // 默认投递区不可用（未授予「所有文件访问」）：与真机首次安装后的状态一致。
  bridge.getMailboxState.mockResolvedValue({ ...unavailableMailbox })
  bridge.getStorageAccessState.mockResolvedValue({ ...storageAccess })
  // 与「未授予所有文件访问」的真机初始状态一致：白名单为空、档位 T0、上限照实回 8。
  // 给默认值而不只是 vi.fn()，是为了让桩在**被调用**时也返回符合契约的形状——
  // 返回 undefined 会让界面侧的载荷校验抛错，那同样会整棵树卸载。
  const emptyStorageDirs = {
    entries: [],
    maxDirectories: 8,
    count: 0,
    supported: false,
    granted: false,
    level: 'T0' as const,
    active: false,
  }
  bridge.getStorageDirs.mockResolvedValue({ ...emptyStorageDirs })
  bridge.addStorageDirectory.mockResolvedValue({ ...emptyStorageDirs })
  bridge.removeStorageDirectory.mockResolvedValue({ ...emptyStorageDirs })
  bridge.execDeviceCommand.mockResolvedValue({ ok: false, exitCode: 1, text: '', truncated: false, errorCode: 'DEVICE_COMMAND_UNAVAILABLE' })
  bridge.requestMediaPermission.mockResolvedValue({ granted: false })
  bridge.openAllFilesAccessSettings.mockResolvedValue({ supported: true, granted: false })
  bridge.requestNotificationPermission.mockResolvedValue({ granted: true, supported: true })
  bridge.getHarnessLog.mockResolvedValue({ available: true, text: 'Error: tool call failed\n    at run (dsh.js:1:1)', maxBytes: 8 * 1024 })
  // 默认是一次「全部正常」的自检：多数用例只关心设置页本身，不该被一个非 ok 项影响。
  bridge.runRuntimeSelfCheck.mockResolvedValue({
    operation: 'check',
    availableBytes: 4 * 1024 * 1024 * 1024,
    dshVersion: '0.1.5-rc.2',
    checks: [{ id: 'shell', status: 'ok' }, { id: 'node', status: 'ok' }],
  })
  // 默认只有「当前版本 + 内置版本」，没有上一版本：多数用例只关心设置页本身，
  // 不该被一个可切换的版本槽影响；涉及切换/删除的用例自己覆盖这三条桩。
  bridge.getRuntimeVersions.mockResolvedValue({
    versions: [
      {
        slot: 'current',
        version: '2026.08.17',
        dshVersion: '0.1.5-rc.2',
        runtimeId: 'ubuntu-24.04-arm64-deepseek-harness',
        extractedBytes: 640 * 1024 * 1024,
        active: true,
      },
      {
        slot: 'bundled',
        version: '2026.09.01',
        runtimeId: 'ubuntu-24.04-arm64-deepseek-harness',
        extractedBytes: 660 * 1024 * 1024,
        active: false,
      },
    ],
    canSwitch: false,
    canDelete: false,
  })
  bridge.switchRuntimeVersion.mockResolvedValue({ versions: [], canSwitch: false, canDelete: false })
  bridge.deleteRuntimeVersion.mockResolvedValue({ versions: [], canSwitch: false, canDelete: false })
  bridge.managePlugins.mockResolvedValue({ plugins: [] })
  bridge.getDiagnosticLogState.mockResolvedValue({ ...diagnostic })
  bridge.readDiagnosticLog.mockResolvedValue({
    text: '2026-09-12T10:20:30Z|INFO|RUNTIME_PHASE|phase=running\n2026-09-12T10:21:04Z|WARN|MODULE_GRAPH|result=failed|count=2|files=4\n',
    maxBytes: 64 * 1024,
    totalBytes: 4096,
    truncated: false,
  })
  bridge.setDiagnosticLogSettings.mockImplementation((enabled: boolean, retentionDays: number) =>
    Promise.resolve({ ...diagnostic, enabled, retentionDays }))
  bridge.clearDiagnosticLog.mockResolvedValue({ ...diagnostic })
  bridge.shareDiagnosticLog.mockResolvedValue({ ...diagnostic, fileName: 'dsh-diagnostic-20260912-102030.txt', exportedBytes: 512 })
  bridge.addRuntimeProgressListener.mockResolvedValue({ remove: vi.fn().mockResolvedValue(undefined) })
  bridge.saveSettings.mockImplementation((value: RuntimeSettingsUpdate) => Promise.resolve(value))
  bridge.install.mockResolvedValue(undefined)
  bridge.startHarness.mockResolvedValue({ ...runningState })
  bridge.openHarness.mockResolvedValue(undefined)
  bridge.stopRuntime.mockResolvedValue({ ...readyState })
  bridge.reset.mockResolvedValue({ ...notInstalledState })
  bridge.requestShizukuPermission.mockResolvedValue({ ...shizuku, permission: 'granted', connected: true })
  bridge.connectShizuku.mockResolvedValue({ ...shizuku, permission: 'granted', connected: true })
  bridge.openShizuku.mockResolvedValue(undefined)
}

/**
 * 进入某个设置二级页：设置首页只保留分类入口。
 *
 * 入口按钮刻意用 `findByRole` 而不是 `getByRole`：应用是**先自动启动运行时、再切到设置视图**的，
 * 「进设置」这个动作能不能立刻点，取决于启动流程跑到哪一步。用同步查询等于把用例的成败
 * 押在「启动链是否已经在同一个微任务里跑完」上——实测在并行跑多个测试文件（CPU 争抢）时会直接失败。
 * 改成有界等待后，前置条件变成「设置首页出现了」，用例自己的断言一条都没有放宽。
 *
 * **为什么显式给 5 s**：`findBy*` 的默认上限只有 1 s，而这个用例**单独跑**时这条等待就要
 * 842 ms（实测），全量并行跑（CPU 争抢 + 4 个 worker）时越过 1 s 直接失败——
 * 失败信息是「找不到入口按钮」，看起来像功能坏了，实际上是等待上限太短。
 * 5 s 只放宽**等待**，不放宽断言：入口真的不出现时依然失败，只是失败得更慢一点。
 */
const SETTINGS_NAV_TIMEOUT_MS = 5_000

export async function openSettingsPage(name: string): Promise<void> {
  const entry = await screen.findByRole('button', { name: new RegExp(name) }, {
    timeout: SETTINGS_NAV_TIMEOUT_MS,
  })
  fireEvent.click(entry)
  await screen.findByRole('heading', { name }, { timeout: SETTINGS_NAV_TIMEOUT_MS })
}
