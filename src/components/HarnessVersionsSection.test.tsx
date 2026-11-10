import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { HarnessProgress, HarnessVersionsState } from '../platform/harnessVersions'
import type { RuntimeBridge, RuntimeState } from '../platform/types'
import { HarnessVersionsSection } from './HarnessVersionsSection'

const runtime: RuntimeState = { phase: 'error', installedVersion: '0.2.0-rc.2', architecture: 'arm64-v8a', updateAvailable: false, downloadedBytes: 0, totalBytes: 0, runnerAvailable: true }
const local: HarnessVersionsState = { active: 'bundled', bundledVersion: '0.2.0-rc.2', installed: [] }

/**
 * 安装是原生侧的一次阻塞调用，界面只能靠 `harnessProgress` 事件知道走到哪一步。
 * 这里用一个永不结束的安装让界面停在「安装中」，再手动推送阶段事件。
 */
function setup(
  install: () => Promise<unknown>,
  options: { onBusyChange?: (busy: boolean) => void; externalBusy?: boolean; runtime?: RuntimeState; onStop?: () => void } = {},
) {
  let listener: ((event: HarnessProgress) => void) | null = null
  const bridge = {
    listHarnessReleases: vi.fn().mockResolvedValue({ entries: [{ version: '0.2.1-rc.2', tags: ['latest'] }] }),
    manageHarnessVersions: vi.fn((operation: string) => operation === 'list' ? Promise.resolve(local) : install()),
    addHarnessProgressListener: vi.fn((callback: (event: HarnessProgress) => void) => {
      listener = callback
      return Promise.resolve({ remove: vi.fn().mockResolvedValue(undefined) })
    }),
  } as unknown as RuntimeBridge
  const view = render(<HarnessVersionsSection bridge={bridge} runtime={options.runtime ?? runtime} refreshRuntime={() => Promise.resolve()}
    externalBusy={options.externalBusy} onBusyChange={options.onBusyChange} onStop={options.onStop} />)
  return { ...view, bridge, emit: (event: HarnessProgress) => act(() => { listener?.(event) }) }
}

describe('Harness 版本安装进度', () => {
  it('本地刷新迟于切换完成时，不覆盖新默认版本或留下刷新锁', async () => {
    let finishOld!: (value: HarnessVersionsState) => void
    const before = { ...local, installed: [{ version: '8.8.8', bytes: 1, active: false, compatible: true }] }
    const after = { ...before, active: '8.8.8', installed: [{ ...before.installed[0], active: true }] }
    let reads = 0
    const bridge = setupBridge({ list: () => ++reads === 1 ? Promise.resolve(before) : new Promise(resolve => { finishOld = resolve }) })
    vi.mocked(bridge.manageHarnessVersions).mockImplementation(operation => operation === 'list' ? (++reads === 1 ? Promise.resolve(before) : new Promise(resolve => { finishOld = resolve })) : Promise.resolve(after))
    render(<HarnessVersionsSection bridge={bridge} runtime={runtime} refreshRuntime={() => Promise.resolve()} />)
    const use = await screen.findByRole('button', { name: '使用并设为默认' })
    fireEvent.click(screen.getByRole('button', { name: '刷新本地 Harness 版本' }))
    fireEvent.click(use)
    await screen.findByRole('button', { name: '切回内置' })

    await act(async () => { finishOld(before); await Promise.resolve() })
    expect(screen.getByRole('button', { name: '切回内置' })).toBeVisible()
    expect(screen.getByRole('button', { name: '刷新本地 Harness 版本' })).toBeEnabled()
  })

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

  it('安装开始与结束都通知外层，卸载后也必须复位', async () => {
    let finishInstall: (value: typeof local) => void = () => undefined
    const onBusyChange = vi.fn()
    const view = setup(
      () => new Promise<typeof local>(resolve => { finishInstall = resolve }),
      { onBusyChange },
    )
    fireEvent.click(await screen.findByRole('button', { name: /安装 Harness/u }))
    await waitFor(() => expect(onBusyChange).toHaveBeenCalledWith(true))

    // 组件先卸载（用户切到别的页面），底层调用仍在跑：外层不能在卸载时自己复位。
    view.unmount()
    expect(onBusyChange).not.toHaveBeenLastCalledWith(false)

    await act(async () => {
      finishInstall(local)
      await Promise.resolve()
    })
    await waitFor(() => expect(onBusyChange).toHaveBeenLastCalledWith(false))
  })

  it('外层仍在忙时禁用重复安装', async () => {
    setup(() => Promise.resolve(local), { externalBusy: true })

    expect(await screen.findByRole('button', { name: /安装 Harness/u })).toBeDisabled()
    expect(screen.getByText(/Harness 版本操作仍在进行/u)).toBeTruthy()
  })

  it('运行中提供就地停止入口，停止不受版本列表刷新失败影响', async () => {
    const onStop = vi.fn()
    const view = setup(() => Promise.resolve(local), { runtime: { ...runtime, phase: 'running' }, onStop })
    vi.mocked(view.bridge.manageHarnessVersions).mockRejectedValueOnce(new Error('read failed'))
    fireEvent.click(await screen.findByRole('button', { name: '刷新本地 Harness 版本' }))
    await screen.findByText('无法读取本地 Harness 版本。')
    expect(await screen.findByRole('button', { name: '停止 Harness' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '停止 Harness' }))
    expect(onStop).toHaveBeenCalledOnce()
  })

  it('探测失败不误报 ABI 变化，仍保留切回内置和删除坏包的恢复入口', async () => {
    const unknown: HarnessVersionsState = {
      active: '8.8.8', bundledVersion: '0.2.0-rc.2', environmentAvailable: false,
      installed: [
        { version: '8.8.8', bytes: 1, active: true, compatible: false },
        { version: '9.9.9', bytes: 0, active: false, compatible: false, invalid: true },
      ],
    }
    const bridge = setupBridge({ list: () => Promise.resolve(unknown) })
    render(<HarnessVersionsSection bridge={bridge} runtime={runtime} refreshRuntime={() => Promise.resolve()} />)
    expect(await screen.findByText('无法确认运行环境，刷新后重试；暂不判断已安装 Harness 是否兼容。')).toBeVisible()
    expect(screen.queryByText('当前运行时的 Node.js ABI 已变化，需删除后重装此包。')).toBeNull()
    expect(screen.getByRole('button', { name: '切回内置' })).toBeEnabled()
    const remove = screen.getByRole('button', { name: '删除 Harness 9.9.9' })
    expect(remove).toHaveTextContent('删除后重装')
    expect(remove).toBeEnabled()
    fireEvent.click(remove)
    expect(screen.getByRole('button', { name: '确认删除' })).toBeEnabled()
  })

  it('运行中跳过环境探测时仍可切换，交由原生在停止后校验', async () => {
    const bridge = setupBridge({ list: () => Promise.resolve({
      ...local, environmentAvailable: false,
      installed: [{ version: '8.8.8', bytes: 1, active: false, compatible: false }],
    }) })
    render(<HarnessVersionsSection bridge={bridge} runtime={{ ...runtime, phase: 'running' }} refreshRuntime={() => Promise.resolve()} />)
    expect(await screen.findByText('Harness 正在运行，切换版本时会重新检查兼容性。')).toBeVisible()
    const use = screen.getByRole('button', { name: '使用并设为默认' })
    expect(use).toBeEnabled()
    expect(screen.queryByText(/ABI 已变化/)).toBeNull()
    fireEvent.click(use)
    await waitFor(() => expect(bridge.manageHarnessVersions).toHaveBeenCalledWith('select', '8.8.8'))
  })

  it('安装结果保留明显的启用入口，后台状态刷新挂起也不阻塞版本页', async () => {
    const installed: HarnessVersionsState = { ...local, installed: [{ version: '0.2.1-rc.2', bytes: 1, active: false, compatible: true }] }
    const bridge = setupBridge({ list: () => Promise.resolve(local) })
    vi.mocked(bridge.listHarnessReleases).mockResolvedValue({ entries: [{ version: '0.2.1-rc.2', tags: ['latest'] }] })
    vi.mocked(bridge.manageHarnessVersions).mockImplementation(operation => Promise.resolve(operation === 'list' ? local : installed))
    render(<HarnessVersionsSection bridge={bridge} runtime={runtime} refreshRuntime={() => new Promise(() => undefined)} />)
    fireEvent.click(await screen.findByRole('button', { name: '安装 Harness' }))
    await screen.findByText('Harness 已安装，可以离线切换。')
    expect(screen.getByRole('button', { name: '使用并设为默认' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '刷新本地 Harness 版本' })).toBeEnabled()
    expect(screen.queryByRole('progressbar')).toBeNull()
  })
})

function setupBridge(options: { list: () => Promise<HarnessVersionsState> }) {
  return {
    listHarnessReleases: vi.fn().mockResolvedValue({ entries: [] }),
    manageHarnessVersions: vi.fn((operation: string) => operation === 'list' ? options.list() : Promise.resolve(local)),
    addHarnessProgressListener: vi.fn().mockResolvedValue({ remove: vi.fn().mockResolvedValue(undefined) }),
  } as unknown as RuntimeBridge
}
