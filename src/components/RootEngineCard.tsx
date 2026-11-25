import { useCallback, useEffect, useRef, useState } from 'react'
import { KeyRound, Power, RotateCcw } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { RootEngineStatus, RootGuardProfile, RuntimeBridge } from '../platform/types'

/**
 * 根通道的 KernelSU 三态 → 一句人话。
 *
 * 与原生 `KernelSuProbe` 的三态逐字对应：`ready`（能跑）、`needs_action`（装好了但还缺一步）、
 * `unsupported`（这台设备没有可用的 KernelSU）。
 *
 * 注意 `unsupported` **只在开关开着时**才是「没装」：开关关着时原生不拉 `su`（`RootEngineAccess.status`
 * 直接回 `unsupported` 且原因是 `ROOT_ENGINE_DISABLED`），那一档的真实含义是「还没查」。
 * 所以下面渲染时先看 `mode`——关着就说关着，绝不说成「这台设备没有可用的 KernelSU」。
 */
const KERNEL_SU_STATE_LABELS: Record<string, string> = {
  ready: '已就绪',
  needs_action: '需进一步授权',
  unsupported: '当前设备不支持 KernelSU',
}

const REASON_NOTES: Record<string, string> = {
  ROOT_ENGINE_DISABLED: '已关闭',
  KERNELSU_NOT_INSTALLED: '当前设备未检测到可用的 KernelSU',
  KERNELSU_PERMISSION_REQUIRED: 'KernelSU 尚未授权给本应用',
  ROOT_IMPL_NOT_KERNELSU: '设备上的 Root 方案不是 KernelSU，本应用仅支持 KernelSU',
  ROOT_SANDBOX_DEGRADED: '命令可继续执行，但当前设备无法提供完整隔离',
}

/** 降级原因码 → 清晰说明（词表来自 `RootSecurityWrapper.DEGRADATION_CODES`）。 */
const DEGRADATION_NOTES: Record<string, string> = {
  NAMESPACE_UNAVAILABLE: '当前设备不支持 unshare，命令将在宿主命名空间运行',
  PID_ISOLATION_UNAVAILABLE: '无法启用 PID 隔离，命令可见并可能影响宿主进程',
  PROPAGATION_FAILED: '无法隔离挂载传播关系，隔离环境内的挂载可能影响宿主',
  BLOCK_MASK_FAILED: '未能屏蔽 /dev/block，命令理论上可读写块设备',
  BLOCK_MASK_SKIPPED: '当前档位未启用 /dev 屏蔽',
  CORE_PARTITION_RO_FAILED: '部分核心分区未能重新挂载为只读',
  PROC_REMOUNT_FAILED: '/proc 未能按隔离模式重新挂载',
  HARDENING_UNAVAILABLE: '当前设备缺少 setpriv，命令将以完整 Root 能力集运行',
}

type StatusLoad =
  | { status: 'loading' }
  | { status: 'ready'; value: RootEngineStatus }
  | { status: 'failed' }

/**
 * 两份读数是否完全一样。
 *
 * 这张卡 10 秒轮询一次，而根通道的状态在两次授权之间几乎不变：一样的读数再 `setState` 只会白渲染一次，
 * 而「设置页空转不能产生提交」是这个仓库的硬约束（`renderProfile` / `renderCounts` 会直接抓出来）。
 * 不比较 `atMs`——原生这份状态里没有取样时刻，它本来就不是按时间比较的读数。
 */
function sameStatus(previous: RootEngineStatus, next: RootEngineStatus): boolean {
  if (previous.enabled !== next.enabled || previous.usable !== next.usable) return false
  if (previous.mode !== next.mode || previous.guardProfile !== next.guardProfile) return false
  if (previous.kernelSuStatus !== next.kernelSuStatus || previous.reason !== next.reason) return false
  if (previous.degraded !== next.degraded || previous.suVersion !== next.suVersion) return false
  if (previous.degradations.length !== next.degradations.length) return false
  return previous.degradations.every((code, index) => code === next.degradations[index])
}

/**
 * 「根通道」设置卡片：模式/护栏档位 + 设备电源（仅手动）。
 *
 * 三条边界：
 *  - 关着时原生不拉 `su`（第一句话就不能把授权窗怼到用户脸上），因此轮询是便宜的。
 *  - **降级不隐瞒**：`degraded` 时逐条列出少了哪层保护，并说明静态审查已收紧；
 *    把降级说成「可用」等于让用户在不知情的情况下少一层保护。
 *  - 电源按钮只在这里；AI 工具与设备桥都没有电源命令。每次点击由原生弹系统身份确认，
 *    取消/不可用一律拒绝，不退化成「直接执行」。
 */
export function RootEngineCard({ bridge = runtimeBridge }: { bridge?: RuntimeBridge }) {
  const [load, setLoad] = useState<StatusLoad>({ status: 'loading' })
  const [busy, setBusy] = useState(false)
  const [writeError, setWriteError] = useState<string | null>(null)
  const [powerBusy, setPowerBusy] = useState<'reboot' | 'powerOff' | null>(null)
  const [powerNotice, setPowerNotice] = useState<string | null>(null)
  const [powerError, setPowerError] = useState<string | null>(null)
  const requestId = useRef(0)

  const refresh = useCallback(async () => {
    const currentRequest = ++requestId.current
    try {
      const next = await bridge.getRootEngineStatus()
      if (currentRequest !== requestId.current) return
      setLoad(previous => (previous.status === 'ready' && sameStatus(previous.value, next)
        ? previous
        : { status: 'ready', value: next }))
    } catch {
      if (currentRequest !== requestId.current) return
      setLoad(previous => (previous.status === 'failed' ? previous : { status: 'failed' }))
    }
  }, [bridge])

  useEffect(() => {
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') void refresh()
    }
    refreshWhenVisible()
    /*
     * 根通道的状态只会被两件事改变：用户在 KernelSU 管理器里授权（本应用收不到通知）、
     * 用户自己开机后重新挂载。所以这里只做低频轮询——10 秒一次足以让授权后的界面在一个
     * 呼吸内跟上，又不至于让每次轮询都去问一遍包管理器。
     */
    const timer = window.setInterval(refreshWhenVisible, 10_000)
    document.addEventListener('visibilitychange', refreshWhenVisible)
    window.addEventListener('focus', refreshWhenVisible)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', refreshWhenVisible)
      window.removeEventListener('focus', refreshWhenVisible)
      requestId.current += 1
    }
  }, [refresh])

  const value = load.status === 'ready' ? load.value : null

  const change = useCallback(async (mode: RootEngineStatus['mode'], guardProfile: RootGuardProfile) => {
    setBusy(true)
    setWriteError(null)
    try {
      /* 原生侧保存后会顺手丢弃两个探测器的缓存，所以这一条返回的就是重测结果，
         不必再补一次查询——补了反而会出现两次状态变化、白渲染一遍。 */
      const next = await bridge.setRootEngineMode(mode, guardProfile)
      setLoad({ status: 'ready', value: next })
    } catch (error) {
      setWriteError(error instanceof Error ? error.message : String(error))
      /* 写失败也重读一次：界面上的选择必须反映**设备上真实的**取值，
         不能停在我以为它变成了的那个值上。 */
      await refresh()
    } finally {
      setBusy(false)
    }
  }, [bridge, refresh])

  const power = useCallback(async (action: 'reboot' | 'powerOff') => {
    setPowerBusy(action)
    setPowerError(null)
    setPowerNotice(null)
    try {
      await bridge.requestDevicePowerAction(action)
      setPowerNotice(action === 'reboot' ? t('已请求重启设备；设备即将重启') : t('已请求关机；设备即将关机'))
    } catch (error) {
      setPowerError(error instanceof Error ? error.message : String(error))
    } finally {
      setPowerBusy(null)
    }
  }, [bridge])

  const mode = value?.mode ?? 'off'
  const profile = value?.guardProfile ?? 'strict'
  const powerAvailable = value !== null && value.enabled && value.usable

  return <div className="settings-subsection" aria-labelledby="root-engine">
    <div className="section-title"><span className="section-icon"><KeyRound size={19} /></span>
      <div><h3 id="root-engine">{t('Root 通道设置')}</h3>
        <p>{t('通过 KernelSU 以 Root（UID 0）执行设备命令，默认关闭')}</p></div>
    </div>
    {load.status === 'loading' && <p className="settings-note">{t('正在读取 Root 通道状态…')}</p>}
    {value !== null && <fieldset className="settings-note">
      <legend>{t('运行模式')}</legend>
      <label>
        <input
          type="radio"
          name="root-engine-mode"
          checked={mode === 'off'}
          disabled={busy}
          onChange={() => { void change('off', profile) }}
        />
        {' '}{t('关闭')}
      </label>
      {' '}
      <label>
        <input
          type="radio"
          name="root-engine-mode"
          checked={mode === 'read_write' && profile === 'strict'}
          disabled={busy}
          onChange={() => { void change('read_write', 'strict') }}
        />
        {' '}{t('读写（严格护栏）')}
      </label>
      {' '}
      <label>
        <input
          type="radio"
          name="root-engine-mode"
          checked={mode === 'read_write' && profile === 'maximum'}
          disabled={busy}
          onChange={() => { void change('read_write', 'maximum') }}
        />
        {' '}{t('读写（最大放行）')}
      </label>
    </fieldset>}
    {value !== null && <p className="settings-note">
      {value.enabled
        ? t(KERNEL_SU_STATE_LABELS[value.kernelSuStatus] ?? value.kernelSuStatus)
        : t('已关闭')}
      {value.enabled && value.reason !== 'READY' && ` · ${t(REASON_NOTES[value.reason] ?? '暂无法确定具体原因')}`}
    </p>}
    {/* 关闭时不主动调用 su，避免无故弹出 Root 授权提示。 */}
    {value !== null && !value.enabled && <p className="settings-note">
      {t('关闭状态下不会调用 su；开启后才会检测设备的 KernelSU 环境。')}
    </p>}
    {value !== null && value.suVersion !== null && <p className="settings-note">{t('su 版本：{0}', value.suVersion)}</p>}
    {value !== null && value.enabled && !value.usable && <p className="settings-note">
      {t('Root 通道已开启但暂不可用，当前设备命令仍通过 Shizuku 执行（无 Root 权限）。')}
    </p>}
    {value !== null && value.enabled && value.usable && <p className="settings-note">
      {profile === 'maximum'
        ? t('最大放行档：放行 mount、dd（普通文件）、insmod、setprop（普通属性）及动态脚本；电源、SELinux、核心分区写入及刷机/块设备/数据销毁等操作仍需在应用警告页确认。')
        : t('严格护栏档：mount、dd、insmod、setprop 及动态脚本需经 dsh 审批（完全权限模式下自动放行）；高危与极危操作均需在应用警告页确认。')}
    </p>}
    {value !== null && value.degraded && <>
      <p className="settings-note">{t('当前设备的隔离保护不完整，缺少以下防护：')}</p>
      <ul className="settings-note">
        {value.degradations.map(code => <li key={code}>
          {/* 认不出的码照原样显示：那是两侧词表不同步的证据，比换成「未知」更容易查。 */}
          {code in DEGRADATION_NOTES ? t(DEGRADATION_NOTES[code]) : code}
        </li>)}
      </ul>
      <p className="settings-note">{t('命令仍可执行，但静态审查已按当前档位收紧；最大放行档在降级设备上等同于无沙箱 Root，风险更高。')}</p>
    </>}
    {value !== null && <p className="settings-note">
      {t('安全护栏仅为静态文本检查，并非硬件级沙箱；如不确定请保持关闭。')}
    </p>}
    {writeError !== null && <p role="alert">{t('保存 Root 通道设置失败：{0}', writeError)}</p>}
    {value !== null && <button className="button button-secondary compact-button" type="button" disabled={busy} onClick={() => { void refresh() }}>{t('重新检测')}</button>}

    {value !== null && <>
      <p className="settings-note">{t('设备电源（手动快捷操作）')}</p>
      <p className="settings-note">{t('手动重启或关机需通过系统身份验证；AI 发起的电源命令需在应用警告页确认')}</p>
      <div className="settings-note">
        <button
          className="button button-secondary compact-button"
          type="button"
          disabled={!powerAvailable || powerBusy !== null}
          onClick={() => { void power('reboot') }}
        >
          <RotateCcw size={16} /> {powerBusy === 'reboot' ? t('正在确认…') : t('重启设备')}
        </button>
        {' '}
        <button
          className="button button-secondary compact-button"
          type="button"
          disabled={!powerAvailable || powerBusy !== null}
          onClick={() => { void power('powerOff') }}
        >
          <Power size={16} /> {powerBusy === 'powerOff' ? t('正在确认…') : t('关机')}
        </button>
      </div>
      {!value.enabled && <p className="settings-note">{t('请先开启 Root 通道，再使用手动重启或关机。')}</p>}
      {value.enabled && !value.usable && <p className="settings-note">{t('KernelSU 未就绪，暂无法执行手动重启或关机。')}</p>}
      {powerNotice !== null && <p className="settings-note" role="status">{powerNotice}</p>}
      {powerError !== null && <p role="alert">{t('设备电源操作失败：{0}', powerError)}</p>}
    </>}

    {load.status === 'failed' && <p role="alert">{t('无法读取 Root 通道状态')} <button className="button button-quiet" type="button" onClick={() => { void refresh() }}>{t('重试')}</button></p>}
  </div>
}
