import { useEffect, useRef, useState } from 'react'
import { readLanguage, type Language } from './i18n'

/**
 * 下载/解压进度的派生遥测：速度、已用时间、预计剩余、停滞提示。
 *
 * 原生侧（APK 与 rootfs 两条通道）都已经按节流推送真实字节数，界面却只显示百分比与
 * 体积——网络慢（GitHub 在国内经常几十 KB/s）时百分比几分钟不动，用户无法分辨「在下」
 * 还是「卡死」。本 hook **不碰下载链路**，只从已有的字节流里派生「它还活着」的信号：
 *
 * - 速度：每笔进度算瞬时速率后做 EMA 平滑，避免数字在两个事件之间乱跳；
 * - 已用时间：活跃期间每秒走字，即使字节暂时不动，界面也在变化（活着的最低保证）；
 * - 预计剩余：剩余字节 / 当前速度，速度未知或总量未知时明确给 null，不编一个倒计时；
 * - 停滞：连续 [STALL_MS] 没有新字节时置位，由界面提示「网络较慢，正在等待数据」。
 *
 * `phaseKey` 用于跨阶段重置：rootfs 下载完成后进入解压，字节口径从压缩包换成解压体积，
 * 会出现归零/换总量，必须重新计速，不能把两段拼成一个荒谬的平均速度。
 */
export interface DownloadTelemetry {
  /** 平滑后的瞬时速度（字节/秒）；尚无样本或停滞时为 0。 */
  speedBytesPerSec: number
  /** 本轮活跃已用时（毫秒）。 */
  elapsedMs: number
  /** 按当前速度估算的剩余时间（毫秒）；总量未知、速度为 0 或已完成时为 null。 */
  etaMs: number | null
  /** 连续超过阈值没有收到新字节：界面据此提示仍在等待数据，而不是假装一切正常。 */
  stalled: boolean
}

const TICK_MS = 1000
const STALL_MS = 5000
/** EMA 平滑系数：越大越贴近瞬时值；0.4 在抗抖与跟手之间取中。 */
const SPEED_ALPHA = 0.4

const IDLE: DownloadTelemetry = { speedBytesPerSec: 0, elapsedMs: 0, etaMs: null, stalled: false }

export function useDownloadTelemetry(
  downloadedBytes: number,
  totalBytes: number,
  active: boolean,
  phaseKey?: string,
): DownloadTelemetry {
  const [snapshot, setSnapshot] = useState<DownloadTelemetry>(IDLE)

  const downloadedRef = useRef(downloadedBytes)
  downloadedRef.current = downloadedBytes
  const totalRef = useRef(totalBytes)
  totalRef.current = totalBytes

  const startedAtRef = useRef(0)
  /** 上次用于计速的样本字节与时刻；字节原地踏步时只更新时刻的口径要分开。 */
  const sampleBytesRef = useRef(downloadedBytes)
  const sampleAtRef = useRef(0)
  /** 上次真正收到新字节的时刻：停滞判定只认它。 */
  const lastDataAtRef = useRef(0)
  const speedRef = useRef(0)

  useEffect(() => {
    if (!active) {
      startedAtRef.current = 0
      sampleBytesRef.current = downloadedRef.current
      sampleAtRef.current = 0
      lastDataAtRef.current = 0
      speedRef.current = 0
      setSnapshot(IDLE)
      return
    }
    const now = Date.now()
    startedAtRef.current = now
    sampleBytesRef.current = downloadedRef.current
    sampleAtRef.current = now
    lastDataAtRef.current = now
    speedRef.current = 0
    setSnapshot({ speedBytesPerSec: 0, elapsedMs: 0, etaMs: null, stalled: false })

    const timer = window.setInterval(() => {
      const tickAt = Date.now()
      const stalled = tickAt - lastDataAtRef.current >= STALL_MS
      const speed = stalled ? 0 : speedRef.current
      const remaining = totalRef.current - downloadedRef.current
      const eta = speed > 0 && remaining > 0 ? Math.round((remaining * 1000) / speed) : null
      setSnapshot({
        speedBytesPerSec: speed,
        elapsedMs: tickAt - startedAtRef.current,
        etaMs: eta,
        stalled,
      })
    }, TICK_MS)
    return () => window.clearInterval(timer)
  }, [active, phaseKey])

  useEffect(() => {
    if (!active || startedAtRef.current === 0) return
    // 跨阶段（或任何非单调值）：字节口径已变，按新段重新计速，而不是算出负速度。
    if (downloadedBytes < sampleBytesRef.current) {
      const now = Date.now()
      sampleBytesRef.current = downloadedBytes
      sampleAtRef.current = now
      lastDataAtRef.current = now
      speedRef.current = 0
      return
    }
    const delta = downloadedBytes - sampleBytesRef.current
    if (delta <= 0) return
    const now = Date.now()
    const dt = now - sampleAtRef.current
    sampleBytesRef.current = downloadedBytes
    sampleAtRef.current = now
    lastDataAtRef.current = now
    if (dt > 0) {
      const instant = (delta * 1000) / dt
      speedRef.current = speedRef.current === 0 ? instant : SPEED_ALPHA * instant + (1 - SPEED_ALPHA) * speedRef.current
    }
    const remaining = totalRef.current - downloadedBytes
    setSnapshot({
      speedBytesPerSec: speedRef.current,
      elapsedMs: now - startedAtRef.current,
      etaMs: speedRef.current > 0 && remaining > 0 ? Math.round((remaining * 1000) / speedRef.current) : null,
      stalled: false,
    })
  }, [downloadedBytes, active, phaseKey])

  return snapshot
}

/** 体积/速率共用的人类可读字节数；与界面原有的 formatBytes 同口径（1024 进制，一位小数）。 */
export function formatByteCount(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / (1024 ** index)
  return `${value >= 10 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`
}

/**
 * 时长格式化：
 * 不到一分钟给秒；不到一小时给「分 + 秒」；再长给「小时 + 分」。
 * 剩余时间也复用它——ETA 本身是估算，秒级精度在长下载时反而是噪声。
 * 语言跟随应用设置（插值数据不进词典，单位得在这里本地化）。
 */
export function formatDuration(ms: number): string {
  const lang: Language | null = readLanguage()
  const totalSeconds = Math.max(0, Math.round(ms / 1000))
  if (lang === 'en') {
    if (totalSeconds < 60) return `${totalSeconds}s`
    const enMinutes = Math.floor(totalSeconds / 60)
    const enSeconds = totalSeconds % 60
    if (enMinutes < 60) return enSeconds === 0 ? `${enMinutes}m` : `${enMinutes}m ${String(enSeconds).padStart(2, '0')}s`
    return `${Math.floor(enMinutes / 60)}h ${enMinutes % 60}m`
  }
  if (totalSeconds < 60) return `${totalSeconds} 秒`
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (minutes < 60) return seconds === 0 ? `${minutes} 分钟` : `${minutes} 分 ${String(seconds).padStart(2, '0')} 秒`
  const hours = Math.floor(minutes / 60)
  return `${hours} 小时 ${minutes % 60} 分`
}
