import { useCallback, useEffect, useRef, useState } from 'react'
import { Cpu } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type {
  DeviceCapabilityReport,
  DeviceCapabilityState,
  RuntimeBridge,
} from '../platform/types'

/**
 * 能力标识的界面名称。
 *
 * 名字来自原生 `DeviceCapabilityPolicy` 的六项。没登记过的标识**回原样**而不是「未知项」：
 * 真出现说明两侧不同步，把技术名字显示出来比藏起来更容易查出问题。
 */
const CAPABILITY_LABELS: Record<string, string> = {
  shizuku: 'Shizuku（设备 Shell）',
  kernelsu_root: '根通道（KernelSU）',
  accessibility: '无障碍服务',
  virtual_screen: '副屏',
  screenshot: '整屏截图',
  chinese_input: '中文输入',
}

/**
 * 五种状态各一句话。`needs_action` 与 `blocked` 必须分开：一个是「点这里就行」，一个是「缺的是别处」。
 * `degraded` 与 `ready` 也必须分开：都能用，但一个少了一层保护，说成「可用」是在瞒用户。
 */
const STATE_LABELS: Record<DeviceCapabilityState, string> = {
  ready: '可用',
  degraded: '可用，但保护降了一档',
  needs_action: '需要一步设置',
  blocked: '被别的前置挡住',
  unsupported: '这台设备不支持',
}

/** 原因码 → 一句人话。讲不出原因的码一律回落到「暂时说不上来」，不编。 */
const REASON_NOTES: Record<string, string> = {
  SHIZUKU_NOT_INSTALLED: '还没有安装 Shizuku',
  SHIZUKU_NOT_RUNNING: 'Shizuku 没有在运行',
  SHIZUKU_PERMISSION_DENIED: 'Shizuku 权限被拒绝',
  SHIZUKU_PERMISSION_UNDETERMINED: '还没有授予 Shizuku 权限',
  SHIZUKU_NOT_CONNECTED: 'Shizuku 已授权，但服务还没连上',
  ACCESSIBILITY_OFF: '无障碍服务没有开启',
  ACCESSIBILITY_ENABLED_DISCONNECTED: '无障碍服务在系统里开着，但本应用还没连上',
  ANDROID_TOO_OLD: '需要 Android 10 或更高版本',
  TEXT_INPUT_NEEDS_ACCESSIBILITY: '中文等非 ASCII 文字只能由无障碍服务写入',
  ROOT_ENGINE_DISABLED: '根通道开关没有打开',
  KERNELSU_NOT_INSTALLED: '这台设备没有可用的 KernelSU',
  KERNELSU_PERMISSION_REQUIRED: 'KernelSU 还没有给本应用授权',
  ROOT_IMPL_NOT_KERNELSU: '设备上的 root 不是 KernelSU，本应用只走 KernelSU',
  ROOT_SANDBOX_DEGRADED: '命令照常执行，但这台设备给不了完整的隔离：静态审查已按严格档收紧，'
    + '对 /dev 的遮蔽没有生效',
}

/** 动作码 → 下一步做什么。这里是**指路**，不替用户点：真正的入口在各自的设置区里。 */
const ACTION_NOTES: Record<string, string> = {
  install_shizuku: '安装 Shizuku',
  open_shizuku: '打开 Shizuku',
  grant_shizuku_permission: '授予 Shizuku 权限',
  connect_shizuku: '连接 Shizuku',
  open_accessibility_settings: '到系统设置里开启本应用的无障碍服务',
  enable_root_engine: '到「根通道」设置里打开开关',
  grant_root_permission: '到 KernelSU 管理器里给本应用授权',
}

/** 替代码 → 退一步还能怎么做。没有替代方式时原生侧给 `null`，这一行就不显示。 */
const ALTERNATIVE_NOTES: Record<string, string> = {
  NO_DEVICE_SHELL: '没有设备 Shell，需要整屏操作的功能都用不了',
  SHELL_ASCII_ONLY: '只能通过 Shell 发送可打印的 ASCII 字符',
  SHIZUKU_SHELL: '没有根通道时，Shizuku 的设备 Shell 仍可用（命令以 shell 身份执行，没有 UID 0）',
}

/**
 * 一次检测的状态。
 *
 * 三态放在**同一个** state 里是有意的：先前拆成 `report` / `loaded` / `failed` 三个 state 时，
 * 一次检测会派发两次更新、提交两遍，而其中一遍的 DOM 与上一遍完全一样——
 * 那正是 `renderProfile` 的「纯导航每次提交都必须改 DOM」要挡的白做提交。一次检测只该有一次状态变化。
 */
type CapabilityLoad =
  | { status: 'loading' }
  | { status: 'ready'; report: DeviceCapabilityReport }
  | { status: 'failed' }

/**
 * 两份读数是否完全一样（含取样时刻）。
 *
 * 这张卡每 5 秒轮询一次，而能力大多时候不变：一样的读数再 `setState` 只会白渲染一次。
 * 取样时刻不同就得更新，因为界面上写着「取样时间」——那是给用户判断数据新旧用的，不能瞒着不刷新。
 */
function sameReport(previous: DeviceCapabilityReport, next: DeviceCapabilityReport): boolean {
  if (previous.atMs !== next.atMs || previous.sdkInt !== next.sdkInt) return false
  if (previous.capabilities.length !== next.capabilities.length) return false
  return previous.capabilities.every((entry, index) => {
    const other = next.capabilities[index]
    return entry.id === other.id && entry.state === other.state && entry.reason === other.reason
      && entry.action === other.action && entry.alternative === other.alternative
  })
}

/**
 * 「设备能力」卡片：一次列清六项能力现在能不能用、不能用是因为什么、下一步点哪里。
 *
 * 四条边界：
 *  - **判定只在原生侧做一次**：界面按 `reason`/`action`/`alternative` 三个码翻词条，不自己推导状态。
 *    两处各判一次，迟早会出现「这里说能用、那里说不能用」。
 *  - **读不到就说读不到**：浏览器预览与查询失败时显示「无法读取设备能力清单」并给重试，
 *    绝不显示成「六项都不可用」——把「读不到」说成「确认不可用」与事实相反。
 *  - **就绪的行不教用户去设置**：`ready` 的行不显示原因、下一步与替代方式，免得让人以为还要做什么；
 *    但 `degraded` 的行**要**说原因——那正是「能用但不完整」里最该知道的那一半。
 *  - 卡片本身**不写入任何浏览器存储**，也不触发任何动作。
 */
export function DeviceCapabilityCard({ bridge = runtimeBridge }: { bridge?: RuntimeBridge }) {
  const [load, setLoad] = useState<CapabilityLoad>({ status: 'loading' })
  const requestId = useRef(0)
  const invalidate = useCallback(() => { requestId.current += 1 }, [])

  const refresh = useCallback(async () => {
    const currentRequest = ++requestId.current
    try {
      const next = await bridge.getDeviceCapabilities()
      if (currentRequest !== requestId.current) return
      setLoad(previous => (previous.status === 'ready' && sameReport(previous.report, next)
        ? previous
        : { status: 'ready', report: next }))
    } catch {
      if (currentRequest !== requestId.current) return
      setLoad(previous => (previous.status === 'failed' ? previous : { status: 'failed' }))
    }
  }, [bridge])
  useEffect(() => {
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') void refresh()
      else invalidate()
    }
    refreshWhenVisible()
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refresh()
    }, 5_000)
    document.addEventListener('visibilitychange', refreshWhenVisible)
    window.addEventListener('focus', refreshWhenVisible)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', refreshWhenVisible)
      window.removeEventListener('focus', refreshWhenVisible)
      invalidate()
    }
  }, [invalidate, refresh])

  const report = load.status === 'ready' ? load.report : null
  const failed = load.status === 'failed'

  return <div className="settings-subsection" aria-labelledby="device-capabilities">
    <div className="section-title"><span className="section-icon"><Cpu size={19} /></span>
      <div><h3 id="device-capabilities">{t('设备能力')}</h3>
        <p>{t('一次看清 Shizuku、根通道、无障碍、副屏、截图与中文输入现在能不能用，以及不能用的原因。')}</p></div>
    </div>
    {load.status === 'loading' && <p className="settings-note">{t('正在检测设备能力…')}</p>}
    {report !== null && report.capabilities.map(entry => <p className="settings-note" key={entry.id}>
      <strong>{t(CAPABILITY_LABELS[entry.id] ?? entry.id)}</strong>
      {' '}
      {t(STATE_LABELS[entry.state])}
      {entry.state !== 'ready' && ` · ${t(REASON_NOTES[entry.reason] ?? '暂时说不上来具体原因')}`}
      {entry.action !== null && ` · ${t('下一步：{0}', t(ACTION_NOTES[entry.action] ?? entry.action))}`}
      {entry.alternative !== null && ` · ${t('替代方式：{0}', t(ALTERNATIVE_NOTES[entry.alternative] ?? entry.alternative))}`}
    </p>)}
    {report !== null && <p className="settings-note">{t('取样时间：{0}', new Date(report.atMs).toLocaleTimeString())} · {t('连接状态变化后会自动重新检测。')}</p>}
    {report !== null && <button type="button" onClick={() => { void refresh() }}>{t('重新检测')}</button>}
    {failed && <p role="alert">{t('无法读取设备能力清单')} <button type="button" onClick={() => { void refresh() }}>{t('重试')}</button></p>}
  </div>
}
