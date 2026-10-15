import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { saveLanguage } from './i18n'
import { formatByteCount, formatDuration, useDownloadTelemetry } from './downloadTelemetry'

describe('useDownloadTelemetry', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  function advance(ms: number) {
    return act(async () => {
      await vi.advanceTimersByTimeAsync(ms)
    })
  }

  it('未激活时始终是空遥测，不估算任何时间', () => {
    const { result, rerender } = renderHook(
      ({ downloaded }) => useDownloadTelemetry(downloaded, 1000, false),
      { initialProps: { downloaded: 0 } },
    )
    expect(result.current).toEqual({ speedBytesPerSec: 0, elapsedMs: 0, etaMs: null, stalled: false })
    rerender({ downloaded: 500 })
    expect(result.current.elapsedMs).toBe(0)
  })

  it('激活后从 0 开始计时，并按事件计算平滑速度与剩余时间', async () => {
    const { result, rerender } = renderHook(
      ({ downloaded, total, active }) => useDownloadTelemetry(downloaded, total, active),
      { initialProps: { downloaded: 0, total: 4_000_000, active: true } },
    )
    expect(result.current.elapsedMs).toBe(0)
    expect(result.current.speedBytesPerSec).toBe(0)

    await advance(1000)
    rerender({ downloaded: 1_000_000, total: 4_000_000, active: true })
    expect(result.current.speedBytesPerSec).toBe(1_000_000)
    expect(result.current.etaMs).toBe(3000)

    // 每秒走字：哪怕字节不动，已用时间也在推进，界面不会呈现「死」态。
    await advance(1000)
    expect(result.current.elapsedMs).toBe(2000)
  })

  it('连续 5 秒没有新字节判定为停滞：速度归零并显式标记', async () => {
    const { result, rerender } = renderHook(
      ({ downloaded, active }) => useDownloadTelemetry(downloaded, 10_000_000, active),
      { initialProps: { downloaded: 0, active: true } },
    )
    await advance(1000)
    rerender({ downloaded: 2_000_000, active: true })
    expect(result.current.stalled).toBe(false)

    await advance(5000)
    expect(result.current.stalled).toBe(true)
    expect(result.current.speedBytesPerSec).toBe(0)
    expect(result.current.etaMs).toBeNull()

    // 恢复来数后立即解除停滞。
    await advance(1000)
    rerender({ downloaded: 4_000_000, active: true })
    expect(result.current.stalled).toBe(false)
    expect(result.current.speedBytesPerSec).toBeGreaterThan(0)
  })

  it('关闭后重置；重新激活重新计时而不是沿用旧段', async () => {
    const { result, rerender } = renderHook(
      ({ downloaded, active }) => useDownloadTelemetry(downloaded, 10_000_000, active),
      { initialProps: { downloaded: 0, active: true } },
    )
    await advance(1000)
    rerender({ downloaded: 1_000_000, active: true })
    expect(result.current.elapsedMs).toBe(1000)

    rerender({ downloaded: 1_000_000, active: false })
    expect(result.current).toEqual({ speedBytesPerSec: 0, elapsedMs: 0, etaMs: null, stalled: false })

    rerender({ downloaded: 1_000_000, active: true })
    expect(result.current.elapsedMs).toBe(0)
  })

  it('phaseKey 变化或字节倒退时按新段重新计速，不算出负速度', async () => {
    const { result, rerender } = renderHook(
      ({ downloaded, total, phase }) => useDownloadTelemetry(downloaded, total, true, phase),
      { initialProps: { downloaded: 8_000_000, total: 8_000_000, phase: 'downloading' } },
    )
    expect(result.current.speedBytesPerSec).toBe(0)

    // 下载完成进入解压：字节归零、总量换成解压体积。
    rerender({ downloaded: 0, total: 30_000_000, phase: 'extracting' })
    await advance(1000)
    rerender({ downloaded: 3_000_000, total: 30_000_000, phase: 'extracting' })
    expect(result.current.speedBytesPerSec).toBe(3_000_000)
    expect(result.current.etaMs).toBe(9000)

    // 防御：即便没有 phaseKey，非单调字节也只会重置而不会产生负速度。
    const single = renderHook(
      ({ downloaded }) => useDownloadTelemetry(downloaded, 1000, true),
      { initialProps: { downloaded: 800 } },
    )
    single.rerender({ downloaded: 100 })
    expect(single.result.current.speedBytesPerSec).toBe(0)
  })

  it('总量未知时不给出伪造的剩余时间', async () => {
    const { result, rerender } = renderHook(
      ({ downloaded }) => useDownloadTelemetry(downloaded, 0, true),
      { initialProps: { downloaded: 0 } },
    )
    await advance(1000)
    rerender({ downloaded: 500_000 })
    expect(result.current.speedBytesPerSec).toBe(500_000)
    expect(result.current.etaMs).toBeNull()
  })
})

describe('formatByteCount', () => {
  it('按 1024 进制输出，并对小于 10 的值保留一位小数', () => {
    expect(formatByteCount(0)).toBe('0 B')
    expect(formatByteCount(512)).toBe('512 B')
    expect(formatByteCount(2048)).toBe('2.0 KB')
    expect(formatByteCount(1.5 * 1024 * 1024)).toBe('1.5 MB')
    expect(formatByteCount(12 * 1024 * 1024)).toBe('12 MB')
  })
})

describe('formatDuration', () => {
  afterEach(() => {
    window.localStorage.clear()
  })

  it('中文按量级在秒/分秒/时分之间切换', () => {
    expect(formatDuration(0)).toBe('0 秒')
    expect(formatDuration(45_000)).toBe('45 秒')
    expect(formatDuration(3 * 60_000)).toBe('3 分钟')
    expect(formatDuration(185_000)).toBe('3 分 05 秒')
    expect(formatDuration((2 * 3600 + 15 * 60) * 1000)).toBe('2 小时 15 分')
  })

  it('英文模式使用紧凑英文单位', () => {
    expect(saveLanguage('en')).toBe(true)
    expect(formatDuration(45_000)).toBe('45s')
    expect(formatDuration(3 * 60_000)).toBe('3m')
    expect(formatDuration(185_000)).toBe('3m 05s')
    expect(formatDuration((2 * 3600 + 15 * 60) * 1000)).toBe('2h 15m')
  })
})
