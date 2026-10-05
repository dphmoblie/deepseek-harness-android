import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RuntimeBridge } from '../platform/types'
import { RuntimeResidueCard } from './RuntimeResidueCard'

/**
 * 「运行时占用」卡片：份数与体积的显示、二次确认、清理后的剩余量必须重新查一次、失败如实显示。
 *
 * 桥用假实现注入（同 `DeviceShellSettings.test.tsx` 的写法）：这些用例一个字节都不会真的删。
 */
const GIGABYTE = 1024 * 1024 * 1024

function card(bridge: Partial<RuntimeBridge>): void {
  render(<RuntimeResidueCard bridge={bridge as unknown as RuntimeBridge} />)
}

async function clickThroughConfirmation(): Promise<void> {
  const button = screen.getByRole('button', { name: '清理残留' })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  await screen.findByRole('alertdialog')
  fireEvent.click(screen.getByRole('button', { name: '确认清理' }))
}

describe('运行时占用卡片', () => {
  it('有残留时显示份数、人类可读体积与清理入口', async () => {
    const get = vi.fn().mockResolvedValue({ count: 3, bytes: 3 * 960 * 1024 * 1024, truncated: false })
    card({ getRuntimeResidue: get })

    await screen.findByText(/可回收的残留：3 份，共 2\.8 GB/)
    expect(screen.getByRole('button', { name: '清理残留' })).toBeEnabled()
  })

  it('没有残留时显示没有可回收的残留，且按钮不可用', async () => {
    const get = vi.fn().mockResolvedValue({ count: 0, bytes: 0, truncated: false })
    card({ getRuntimeResidue: get })

    await screen.findByText('没有可回收的残留')
    expect(screen.getByRole('button', { name: '清理残留' })).toBeDisabled()
  })

  it('体积被条目上限截断时说明上面的数字只是下界', async () => {
    const get = vi.fn().mockResolvedValue({ count: 5, bytes: 5 * GIGABYTE, truncated: true })
    card({ getRuntimeResidue: get })

    await screen.findByText(/可回收的残留：5 份，共 5\.0 GB/)
    await screen.findByText('体积统计因条目上限提前停止，上面的数字只是下界。')
  })

  it('点清理先要二次确认，确认后才调用桥，并在清理后重新查一次剩余量', async () => {
    const get = vi.fn()
      .mockResolvedValueOnce({ count: 2, bytes: 2 * GIGABYTE, truncated: false })
      .mockResolvedValueOnce({ count: 0, bytes: 0, truncated: false })
    const clean = vi.fn().mockResolvedValue({
      cleaned: 2,
      failed: 0,
      reclaimedBytes: 1_500_000_000,
      message: '已回收 2 份，详见诊断与日志',
    })
    card({ getRuntimeResidue: get, cleanRuntimeResidue: clean })

    await clickThroughConfirmation()
    await waitFor(() => expect(clean).toHaveBeenCalledOnce())
    // 「还剩几份」必须重新问一次原生侧：cleaned/failed 只描述这一次尝试。
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))
    await screen.findByText('本次已回收 2 份，共 1.4 GB。')
    await screen.findByText('没有可回收的残留')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('二次确认写清只删 stale-* 残留，取消则什么都不做', async () => {
    const get = vi.fn().mockResolvedValue({ count: 1, bytes: GIGABYTE, truncated: false })
    const clean = vi.fn()
    card({ getRuntimeResidue: get, cleanRuntimeResidue: clean })

    const button = screen.getByRole('button', { name: '清理残留' })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog.textContent).toContain('只删除运行时目录下由本应用生成的 stale-* 残留，不影响会话、设置与当前运行时。')

    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(clean).not.toHaveBeenCalled()
  })

  it('清理后仍有剩余时如实显示还剩几份，并附上原生侧说明', async () => {
    const get = vi.fn()
      .mockResolvedValueOnce({ count: 3, bytes: 3 * GIGABYTE, truncated: false })
      .mockResolvedValueOnce({ count: 1, bytes: GIGABYTE, truncated: false })
    const clean = vi.fn().mockResolvedValue({
      cleaned: 2,
      failed: 1,
      reclaimedBytes: 2 * GIGABYTE,
      message: '已回收 2 份，1 份未能删除，详见诊断与日志',
    })
    card({ getRuntimeResidue: get, cleanRuntimeResidue: clean })

    await clickThroughConfirmation()
    await screen.findByText(/仍有 1 份未清理，详见诊断与日志。/)
    // 原生侧那句话是**连在标签后面**渲染的（同一个 <p> 里的两个文本节点），所以按正则找整句。
    expect(screen.getByText(/原生侧说明：已回收 2 份，1 份未能删除，详见诊断与日志/)).toBeTruthy()
    // 还剩 1 份，所以按钮仍然可用——这一次清理没有被说成「已清干净」。
    expect(screen.getByRole('button', { name: '清理残留' })).toBeEnabled()
  })

  it('读取失败时如实显示失败并可重试，不把读不到说成没有残留', async () => {
    const get = vi.fn().mockRejectedValue(new Error('桥不可用'))
    card({ getRuntimeResidue: get })

    await screen.findByText(/无法读取运行时占用/)
    expect(screen.queryByText('没有可回收的残留')).toBeNull()
    expect(screen.getByRole('button', { name: '清理残留' })).toBeDisabled()

    get.mockResolvedValueOnce({ count: 1, bytes: GIGABYTE, truncated: false })
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await screen.findByText(/可回收的残留：1 份，共 1\.0 GB/)
  })

  it('清理自身失败时如实报错，不谎报回收结果', async () => {
    const get = vi.fn().mockResolvedValue({ count: 2, bytes: 2 * GIGABYTE, truncated: false })
    const clean = vi.fn().mockRejectedValue(new Error('清理通道不可用'))
    card({ getRuntimeResidue: get, cleanRuntimeResidue: clean })

    await clickThroughConfirmation()
    await screen.findByText('清理没能完成，请稍后重试；仍然失败请查看诊断与日志。')
    expect(screen.queryByText(/本次已回收/)).toBeNull()
    expect(clean).toHaveBeenCalledOnce()
  })
})
