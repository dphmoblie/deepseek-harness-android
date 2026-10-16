import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RootEngineStatus, RuntimeBridge } from '../platform/types'
import { RootEngineCard } from './RootEngineCard'

/**
 * 「根通道」卡片：开关是**写**操作，状态必须反映设备上真实的取值，降级必须逐条说明。
 *
 * 桥用假实现注入（同 `DeviceCapabilityCard.test.tsx`）：这些用例不碰任何真实设备状态。
 */
function status(overrides: Partial<RootEngineStatus> = {}): RootEngineStatus {
  return {
    enabled: false,
    usable: false,
    kernelSuStatus: 'unsupported',
    reason: 'ROOT_ENGINE_DISABLED',
    degraded: false,
    degradations: [],
    suVersion: null,
    ...overrides,
  }
}

function card(bridge: Partial<RuntimeBridge>): void {
  render(<RootEngineCard bridge={bridge as unknown as RuntimeBridge} />)
}

const checkbox = (): HTMLInputElement => screen.getByRole('checkbox')

describe('根通道卡片', () => {
  it('关着的时候说清是「还没查」，不说成「这台设备没有 KernelSU」，开关是关的', async () => {
    card({ getRootEngineStatus: vi.fn().mockResolvedValue(status()) })

    await waitFor(() => { expect(checkbox().checked).toBe(false) })
    /*
     * 关着时原生不拉 su（`RootEngineAccess.status` 回 `unsupported` + `ROOT_ENGINE_DISABLED`），
     * `unsupported` 这一档此刻只是「还没查」：说成「这台设备没有可用的 KernelSU」会与能力行的
     * 「开关没有打开」自相矛盾（清单 M-4 的现场）。
     */
    expect(screen.getByText(/关着的时候本应用不拉 su/)).toBeTruthy()
    expect(screen.queryByText(/这台设备没有可用的 KernelSU/)).toBeNull()
    /* 关着时不该出现「开关开着但还没法用」那句——它只在开着却没就绪时出现。 */
    expect(screen.queryByText(/开关开着但还没法用/)).toBeNull()
    /* 关着就不该讲降级：没有隔离可降。 */
    expect(screen.queryByText(/隔离不完整/)).toBeNull()
  })

  it('打开开关后按原生返回的**新**状态渲染，不自己猜结果', async () => {
    const getRootEngineStatus = vi.fn().mockResolvedValue(status())
    const setRootEngineEnabled = vi.fn().mockResolvedValue(status({
      enabled: true,
      kernelSuStatus: 'needs_action',
      reason: 'KERNELSU_PERMISSION_REQUIRED',
      suVersion: '3.1.0:KernelSU',
    }))
    card({ getRootEngineStatus, setRootEngineEnabled })

    await waitFor(() => { expect(checkbox().checked).toBe(false) })
    fireEvent.click(checkbox())

    await waitFor(() => { expect(checkbox().checked).toBe(true) })
    expect(setRootEngineEnabled).toHaveBeenCalledWith(true)
    expect(screen.getByText(/需要一步设置/)).toBeTruthy()
    expect(screen.getByText(/KernelSU 还没有给本应用授权/)).toBeTruthy()
    expect(screen.getByText(/su 版本：3\.1\.0:KernelSU/)).toBeTruthy()
    /* 开着但没就绪：必须说清命令此刻仍走 Shizuku，而不是让用户以为已经能用了。 */
    expect(screen.getByText(/开关开着但还没法用/)).toBeTruthy()
  })

  it('降级时逐条列出少了哪层保护，并说明审查已收紧', async () => {
    card({
      getRootEngineStatus: vi.fn().mockResolvedValue(status({
        enabled: true,
        usable: true,
        kernelSuStatus: 'ready',
        reason: 'READY',
        degraded: true,
        degradations: ['NAMESPACE_UNAVAILABLE', 'HARDENING_UNAVAILABLE'],
      })),
    })

    await waitFor(() => { expect(screen.getByText(/隔离不完整，少了这些保护：/)).toBeTruthy() })
    expect(screen.getByText(/unshare 用不了/)).toBeTruthy()
    expect(screen.getByText(/没有 setpriv/)).toBeTruthy()
    expect(screen.getByText(/静态审查已按严格档收紧/)).toBeTruthy()
    /* 认不出的码照原样显示：那是两侧词表不同步的证据。 */
    expect(screen.queryByText('BLOCK_MASK_FAILED')).toBeNull()
  })

  it('认不出的降级码照原样显示，不换成「未知」', async () => {
    card({
      getRootEngineStatus: vi.fn().mockResolvedValue(status({
        enabled: true,
        usable: true,
        kernelSuStatus: 'ready',
        reason: 'READY',
        degraded: true,
        degradations: ['NEW_CODE_FROM_FUTURE'],
      })),
    })

    await waitFor(() => { expect(screen.getByText('NEW_CODE_FROM_FUTURE')).toBeTruthy() })
  })

  it('读不到状态时说读不到，而不是画一个关着的开关', async () => {
    card({ getRootEngineStatus: vi.fn().mockRejectedValue(new Error('浏览器预览不支持根通道状态查询')) })

    await waitFor(() => { expect(screen.getByText(/无法读取根通道状态/)).toBeTruthy() })
    expect(screen.queryByRole('checkbox')).toBeNull()
  })

  it('保存失败时如实报错，并重读一次以反映设备上真实的取值', async () => {
    const getRootEngineStatus = vi.fn()
      .mockResolvedValueOnce(status())
      .mockResolvedValue(status({ enabled: false, reason: 'ROOT_ENGINE_DISABLED' }))
    const setRootEngineEnabled = vi.fn().mockRejectedValue(new Error('根通道开关取值无效'))
    card({ getRootEngineStatus, setRootEngineEnabled })

    await waitFor(() => { expect(checkbox().checked).toBe(false) })
    fireEvent.click(checkbox())

    await waitFor(() => { expect(screen.getByRole('alert').textContent).toContain('保存根通道开关失败：根通道开关取值无效') })
    /* 开关必须回到设备的真实取值（这里仍是关），不能停在我以为它变成了的那个值上。 */
    expect(checkbox().checked).toBe(false)
    expect(getRootEngineStatus).toHaveBeenCalledTimes(2)
  })
})
