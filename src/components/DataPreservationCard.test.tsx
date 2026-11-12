import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PreserveState, RuntimeBridge } from '../platform/types'

/**
 * 「数据保留」卡片：待处理副本的呈现与两个动作。
 *
 * 桥用假实现注入（同 `RuntimeCleanupCard.test.tsx`）：一个字节都不会真的复制或删除。
 * 桩一律用 `Promise.resolve/reject` 而不是 `async`：ESLint 要求 async 函数里必须有 await。
 */
import { DataPreservationCard } from './DataPreservationCard'

const PENDING_ID = 'preserve-0f8fad5b-d9cb-469f-a165-70867728950e'

function sampleState(): PreserveState {
  return {
    pending: [
      {
        id: PENDING_ID,
        updatedAt: Date.UTC(2026, 0, 2),
        bytes: 2048,
        items: [
          { slot: 'sessions', category: 'conversations', path: 'root/.dsh/sessions', bytes: 1024, files: 3 },
          { slot: 'workspace', category: 'files', path: 'root/1', bytes: 1024, files: 1 },
        ],
        report: { restored: 1, merged: 2, conflicts: ['workspace/notes.md'] },
      },
    ],
  }
}

function bridge(overrides: Partial<RuntimeBridge> = {}): RuntimeBridge {
  return {
    getPreserveState: vi.fn(() => Promise.resolve(sampleState())),
    recoverPreserveItems: vi.fn(() => Promise.resolve({
      id: PENDING_ID, target: `recovered/${PENDING_ID}`, files: 4, bytes: 4096, skippedSymlinks: 0,
    })),
    discardPreserveItems: vi.fn(() => Promise.resolve({ id: PENDING_ID, discarded: true })),
    ...overrides,
  } as unknown as RuntimeBridge
}

describe('数据保留卡片', () => {
  beforeEach(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
  })

  it('没有待处理副本时如实说明，不显示动作', async () => {
    render(<DataPreservationCard bridge={bridge({
      getPreserveState: vi.fn(() => Promise.resolve({ pending: [] })),
    })} />)
    expect(await screen.findByText('没有待处理的数据副本。运行时更新留下的东西都已经合并回新运行时。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '取回' })).not.toBeInTheDocument()
  })

  it('按分类列出待处理副本，并显示上次回填的结果', async () => {
    render(<DataPreservationCard bridge={bridge()} />)
    expect(await screen.findByText('会话 1 · 文件 1')).toBeInTheDocument()
    expect(screen.getByText(/上次更新：回填 1 项、合并 2 项；仍有 1 项待处理/)).toBeInTheDocument()
    expect(screen.getByText(/2\.0 KB/)).toBeInTheDocument()
  })

  it('取回会把副本落到工作区并重新读一次状态', async () => {
    const target = bridge()
    render(<DataPreservationCard bridge={target} />)
    fireEvent.click(await screen.findByRole('button', { name: '取回' }))
    await waitFor(() => expect(target.recoverPreserveItems).toHaveBeenCalledWith(PENDING_ID))
    expect(await screen.findByText(/已取回 4 个文件/)).toBeInTheDocument()
    expect(target.getPreserveState).toHaveBeenCalledTimes(2)
  })

  it('删除必须二次确认，确认前不会调用桥', async () => {
    const target = bridge({ discardPreserveItems: vi.fn(() => Promise.resolve({ id: PENDING_ID, discarded: true })) })
    render(<DataPreservationCard bridge={target} />)

    fireEvent.click(await screen.findByRole('button', { name: '删除' }))
    expect(screen.getByText(/删除只影响这份待处理副本/)).toBeInTheDocument()
    expect(target.discardPreserveItems).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '确认删除' }))
    await waitFor(() => expect(target.discardPreserveItems).toHaveBeenCalledWith(PENDING_ID))
    expect(await screen.findByText('已删除这份数据副本')).toBeInTheDocument()
  })

  it('读不到状态与动作失败都如实显示，不假装成功', async () => {
    const failing = bridge({
      getPreserveState: vi.fn(() => Promise.reject(new Error('bridge down'))),
    })
    render(<DataPreservationCard bridge={failing} />)
    expect(await screen.findByText(/读不到待处理数据/)).toBeInTheDocument()

    const recoverFails = bridge({
      recoverPreserveItems: vi.fn(() => Promise.reject(new Error('busy'))),
    })
    render(<DataPreservationCard bridge={recoverFails} />)
    const buttons = await screen.findAllByRole('button', { name: '取回' })
    fireEvent.click(buttons[buttons.length - 1])
    expect(await screen.findByText(/操作没有完成/)).toBeInTheDocument()
  })
})
