import { useCallback, useEffect, useRef, useState } from 'react'
import { KeyRound } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { RootEngineStatus, RuntimeBridge } from '../platform/types'

/**
 * 根通道的 KernelSU 三态 → 一句人话。
 *
 * 与原生 `KernelSuProbe` 的三态逐字对应：`ready`（能跑）、`needs_action`（装好了但还缺一步）、
 * `unsupported`（这台设备没有可用的 KernelSU）。
 */
const KERNEL_SU_STATE_LABELS: Record<string, string> = {
  ready: '当前可用',
  needs_action: '需要一步设置',
  unsupported: '这台设备没有可用的 KernelSU',
}

/**
 * 原因码 → 一句人话。
 *
 * 这张表只有根通道用到的五个码；全量表在 `DeviceCapabilityCard`（那边还要管 Shizuku 与无障碍）。
 * 两处都回落到「暂时说不上来」而不是编一句，讲不出原因的码一律照实说。
 */
const REASON_NOTES: Record<string, string> = {
  ROOT_ENGINE_DISABLED: '开关关着',
  KERNELSU_NOT_INSTALLED: '这台设备没有可用的 KernelSU',
  KERNELSU_PERMISSION_REQUIRED: 'KernelSU 还没有给本应用授权',
  ROOT_IMPL_NOT_KERNELSU: '设备上的 root 不是 KernelSU，本应用只走 KernelSU',
  ROOT_SANDBOX_DEGRADED: '命令照常执行，但这台设备给不了完整的隔离',
}

/** 降级原因码 → 一句人话（词表来自 `RootSecurityWrapper.DEGRADATION_CODES`）。 */
const DEGRADATION_NOTES: Record<string, string> = {
  NAMESPACE_UNAVAILABLE: '这台设备的 unshare 用不了，命令会跑在宿主命名空间里',
  PID_ISOLATION_UNAVAILABLE: '拿不到 PID 隔离，命令看不到、也影响不到别的进程组之外的东西会更多',
  PROPAGATION_FAILED: '挂载传播关系切不开，隔离里做的挂载可能影响到外面',
  BLOCK_MASK_FAILED: '没能把 /dev/block 遮掉，命令理论上能看到块设备节点',
  BLOCK_MASK_SKIPPED: '这一档没有做 /dev 遮蔽',
  CORE_PARTITION_RO_FAILED: '有核心分区没能重新挂成只读',
  PROC_REMOUNT_FAILED: '/proc 没能按隔离重新挂载',
  HARDENING_UNAVAILABLE: '这台设备没有 setpriv，命令跑在完整的 root 能力集里',
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
  if (previous.kernelSuStatus !== next.kernelSuStatus || previous.reason !== next.reason) return false
  if (previous.degraded !== next.degraded || previous.suVersion !== next.suVersion) return false
  if (previous.degradations.length !== next.degradations.length) return false
  return previous.degradations.every((code, index) => code === next.degradations[index])
}

/**
 * 「根通道」设置卡片：一个开关 + 它现在到底能不能用、为什么、缺了哪一层保护。
 *
 * 为什么单独立一张卡而不是塞进能力清单：
 *  - 能力清单**只读**，回答「这台机器现在能做什么」；这里要**写**（开关），写操作得有自己的
 *    busy 与失败说明，不能和 5 秒轮询共用一份状态，否则一次保存失败会被下一次轮询悄悄抹平。
 *  - 打开它是**允许**，不是授权：真正的授权在 KernelSU 管理器里由用户点，本应用不代点，
 *    所以开关打开后大概率停在 `needs_action`——那正是这张卡要说清的地方。
 *
 * 三条边界：
 *  - 关着时原生不拉 `su`（第一句话就不能把授权窗怼到用户脸上），因此轮询是便宜的。
 *  - **降级不隐瞒**：`degraded` 时逐条列出少了哪层保护，并说明静态审查已收紧；
 *    把降级说成「可用」等于让用户在不知情的情况下少一层保护。
 *  - 读不到就说读不到：预览与查询失败显示「无法读取」，**不要**画成一个关着的开关——
 *    「没开」与「读不到」对用户的下一步完全不同（前者点开关，后者重试）。
 */
export function RootEngineCard({ bridge = runtimeBridge }: { bridge?: RuntimeBridge }) {
  const [load, setLoad] = useState<StatusLoad>({ status: 'loading' })
  const [busy, setBusy] = useState(false)
  const [writeError, setWriteError] = useState<string | null>(null)
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

  const toggle = useCallback(async (enabled: boolean) => {
    setBusy(true)
    setWriteError(null)
    try {
      /* 原生侧保存开关后会顺手丢弃两个探测器的缓存，所以这一条返回的就是重测结果，
         不必再补一次查询——补了反而会出现两次状态变化、白渲染一遍。 */
      const next = await bridge.setRootEngineEnabled(enabled)
      setLoad({ status: 'ready', value: next })
    } catch (error) {
      setWriteError(error instanceof Error ? error.message : String(error))
      /* 写失败也重读一次：界面上的开关必须反映**设备上真实的**取值，
         不能停在我以为它变成了的那个值上。 */
      await refresh()
    } finally {
      setBusy(false)
    }
  }, [bridge, refresh])

  return <div className="settings-subsection" aria-labelledby="root-engine">
    <div className="section-title"><span className="section-icon"><KeyRound size={19} /></span>
      <div><h3 id="root-engine">{t('根通道设置')}</h3>
        <p>{t('打开后，AI 的设备命令可以以 UID 0 执行：命令先过静态审查，再在私有命名空间里跑，并尽量把核心分区挂成只读。默认关闭。')}</p></div>
    </div>
    {load.status === 'loading' && <p className="settings-note">{t('正在读取根通道状态…')}</p>}
    {value !== null && <p className="settings-note">
      <label>
        <input
          type="checkbox"
          checked={value.enabled}
          disabled={busy}
          onChange={event => { void toggle(event.target.checked) }}
        />
        {' '}{t('启用根通道')}
      </label>
      {' '}
      {t(KERNEL_SU_STATE_LABELS[value.kernelSuStatus] ?? value.kernelSuStatus)}
      {value.reason !== 'READY' && ` · ${t(REASON_NOTES[value.reason] ?? '暂时说不上来具体原因')}`}
    </p>}
    {value !== null && value.suVersion !== null && <p className="settings-note">{t('su 版本：{0}', value.suVersion)}</p>}
    {value !== null && value.enabled && !value.usable && <p className="settings-note">
      {t('开关开着但还没法用：设备命令仍走 Shizuku（shell 身份，没有 UID 0）。')}
    </p>}
    {value !== null && value.degraded && <>
      <p className="settings-note">{t('隔离不完整，少了这些保护：')}</p>
      <ul className="settings-note">
        {value.degradations.map(code => <li key={code}>
          {/* 认不出的码照原样显示：那是两侧词表不同步的证据，比换成「未知」更容易查。 */}
          {code in DEGRADATION_NOTES ? t(DEGRADATION_NOTES[code]) : code}
        </li>)}
      </ul>
      <p className="settings-note">{t('命令仍会执行，但静态审查已按严格档收紧：挂载、写 /dev 下的设备节点、加载内核模块这类命令一律拒绝。')}</p>
    </>}
    {value !== null && <p className="settings-note">
      {t('UID 0 的命令能改动系统分区与内核参数。审查放行的命令会在私有命名空间里执行，但它仍然是 UID 0——不确定时请保持关闭。')}
    </p>}
    {writeError !== null && <p role="alert">{t('保存根通道开关失败：{0}', writeError)}</p>}
    {value !== null && <button className="button button-secondary compact-button" type="button" disabled={busy} onClick={() => { void refresh() }}>{t('重新检测')}</button>}
    {load.status === 'failed' && <p role="alert">{t('无法读取根通道状态')} <button className="button button-secondary compact-button" type="button" onClick={() => { void refresh() }}>{t('重试')}</button></p>}
  </div>
}
