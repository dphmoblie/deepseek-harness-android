import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { HarnessProgress } from '../platform/harnessVersions'
import type { RuntimeBridge, RuntimeState } from '../platform/types'
import { HarnessVersionsSection } from './HarnessVersionsSection'

const runtime: RuntimeState = { phase: 'error', installedVersion: '0.2.0-rc.2', architecture: 'arm64-v8a', updateAvailable: false, downloadedBytes: 0, totalBytes: 0, runnerAvailable: true }
const local = { active: 'bundled', bundledVersion: '0.2.0-rc.2', installed: [] }

/**
 * 安装是原生侧的一次阻塞调用，界面只能靠 `harnessProgress` 事件知道走到哪一步。
 * 这里用一个永不结束的安装让界面停在「安装中」，再手动推送阶段事件。
 */
function setup(install: () => Promise<never>) {
  let listener: ((event: HarnessProgress) => void) | null = null
  const bridge = {
    listHarnessReleases: vi.fn().mockResolvedValue({ entries: [{ version: '0.2.1-rc.2', tags: ['latest'] }] }),
    manageHarnessVersions: vi.fn((operation: string) => operation === 'list' ? Promise.resolve(local) : install()),
    addHarnessProgressListener: vi.fn(async (callback: (event: HarnessProgress) => void) => {
      listener = callback
      return { remove: vi.fn().mockResolvedValue(undefined) }
    }),
  } as unknown as RuntimeBridge
  const view = render(<HarnessVersionsSection bridge={bridge} runtime={runtime} refreshRuntime={() => Promise.resolve()} />)
  return { ...view, bridge, emit: (event: HarnessProgress) => act(() => { listener?.(event) }) }
}

describe('Harness 版本安装进度', () => {
  it('按阶段事件推进进度条并显示已用时间，而不是一直转圈', async () => {
    const view = setup(() => new Promise<never>(() => undefined))
    fireEvent.click(await screen.findByRole('button', { name: /安装 Harness/u }))
    await waitFor(() => expect(view.bridge.addHarnessProgressListener).toHaveBeenCalled())
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('0')
    view.emit({ stage: 'fetch', percent: 35 })
    expect(screen.getByText('下载并安装依赖')).toBeTruthy()
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('35')
    view.emit({ stage: 'native', percent: 84 })
    expect(screen.getByText('准备原生模块')).toBeTruthy()
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('84')
    expect(screen.getByText(/已用/u)).toBeTruthy()
  })

  it('失败时把停在哪一阶段原样显示出来', async () => {
    setup(() => Promise.reject(new Error('停在「下载并安装依赖」阶段：Harness 包操作失败，原版本仍保留')))
    fireEvent.click(await screen.findByRole('button', { name: /安装 Harness/u }))
    expect(await screen.findByText(/停在「下载并安装依赖」阶段/u)).toBeTruthy()
    expect(screen.queryByRole('progressbar')).toBeNull()
  })
})
