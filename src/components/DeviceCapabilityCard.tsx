import { useCallback, useEffect, useState } from 'react'
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
 * 名字来自原生 `DeviceCapabilityPolicy` 的五项。没登记过的标识**回原样**而不是「未知项」：
 * 真出现说明两侧不同步，把技术名字显示出来比藏起来更容易查出问题。
 */
const CAPABILITY_LABELS: Record<string, string> = {
  shizuku: 'Shizuku（设备 Shell）',
  accessibility: '无障碍服务',
  virtual_screen: '副屏',
  screenshot: '整屏截图',
  chinese_input: '中文输入',
}

/** 四种状态各一句话。`needs_action` 与 `blocked` 必须分开：一个是「点这里就行」，一个是「缺的是别处」。 */
const STATE_LABELS: Record<DeviceCapabilityState, string> = {
  ready: '可用',
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
}

/** 动作码 → 下一步做什么。这里是**指路**，不替用户点：真正的入口在各自的设置区里。 */
const ACTION_NOTES: Record<string, string> = {
  install_shizuku: '安装 Shizuku',
  open_shizuku: '打开 Shizuku',
  grant_shizuku_permission: '授予 Shizuku 权限',
  connect_shizuku: '连接 Shizuku',
  open_accessibility_settings: '到系统设置里开启本应用的无障碍服务',
}

/** 替代码 → 退一步还能怎么做。没有替代方式时原生侧给 `null`，这一行就不显示。 */
const ALTERNATIVE_NOTES: Record<string, string> = {
  NO_DEVICE_SHELL: '没有设备 Shell，需要整屏操作的功能都用不了',
  SHELL_ASCII_ONLY: '只能通过 Shell 发送可打印的 ASCII 字符',
}

/**
 * 「设备能力」卡片：一次列清五项能力现在能不能用、不能用是因为什么、下一步点哪里。
 *
 * 四条边界：
 *  - **判定只在原生侧做一次**：界面按 `reason`/`action`/`alternative` 三个码翻词条，不自己推导状态。
 *    两处各判一次，迟早会出现「这里说能用、那里说不能用」。
 *  - **读不到就说读不到**：浏览器预览与查询失败时显示「无法读取设备能力清单」并给重试，
 *    绝不显示成「五项都不可用」——把「读不到」说成「确认不可用」与事实相反。
 *  - **就绪的行不教用户去设置**：`ready` 的行不显示原因、下一步与替代方式，免得让人以为还要做什么。
 *  - 卡片本身**不写入任何浏览器存储**，也不触发任何动作。
 */
export function DeviceCapabilityCard({ bridge = runtimeBridge }: { bridge?: RuntimeBridge }) {
  const [report, setReport] = useState<DeviceCapabilityReport | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [failed, setFailed] = useState(false)

  const refresh = useCallback(async () => {
    setLoaded(false); setFailed(false)
    try { setReport(await bridge.getDeviceCapabilities()); setLoaded(true) }
    catch { setFailed(true) }
  }, [bridge])
  useEffect(() => { void refresh() }, [refresh])

  const ready = loaded && !failed && report !== null

  return <div className="settings-subsection" aria-labelledby="device-capabilities">
    <div className="section-title"><span className="section-icon"><Cpu size={19} /></span>
      <div><h3 id="device-capabilities">{t('设备能力')}</h3>
        <p>{t('一次看清 Shizuku、无障碍、副屏、截图与中文输入现在能不能用，以及不能用的原因。')}</p></div>
    </div>
    {!loaded && !failed && <p className="settings-note">{t('正在检测设备能力…')}</p>}
    {ready && report.capabilities.map(entry => <p className="settings-note" key={entry.id}>
      <strong>{t(CAPABILITY_LABELS[entry.id] ?? entry.id)}</strong>
      {' '}
      {t(STATE_LABELS[entry.state])}
      {entry.state !== 'ready' && ` · ${t(REASON_NOTES[entry.reason] ?? '暂时说不上来具体原因')}`}
      {entry.action !== null && ` · ${t('下一步：{0}', t(ACTION_NOTES[entry.action] ?? entry.action))}`}
      {entry.alternative !== null && ` · ${t('替代方式：{0}', t(ALTERNATIVE_NOTES[entry.alternative] ?? entry.alternative))}`}
    </p>)}
    {ready && <p className="settings-note">{t('能力会随连接状态变化，需要时重新检测一次。')}</p>}
    {ready && <button type="button" onClick={() => { void refresh() }}>{t('重新检测')}</button>}
    {failed && <p role="alert">{t('无法读取设备能力清单')} <button type="button" onClick={() => { void refresh() }}>{t('重试')}</button></p>}
  </div>
}
