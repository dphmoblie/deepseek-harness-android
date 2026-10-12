import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RuntimeBridge, RuntimeCleanupOutcome, RuntimeCleanupState } from '../platform/types'
import { RuntimeCleanupCard } from './RuntimeCleanupCard'

/**
 * 「数据清理」卡片：两档分开（缓存 / 插件数据）、运行中与账本不可用时**不给点**、
 * 清理之后必须重新查一次占用、失败如实显示。
 *
 * 桥用假实现注入（同 `RuntimeResidueCard.test.tsx`）：这些用例一个字节都不会真的删。
 */
const MEGABYTE = 1024 * 1024
const SIZE = 3 * MEGABYTE + 512 * 1024

function state(overrides: Partial<RuntimeCleanupState> = {}): RuntimeCleanupState {
  return {
    runtimeRunning: false,
    cache: {
      entries: [
        { name: 'diagnostics', present: true, files: 3, bytes: 3 * MEGABYTE },
        { name: 'share', present: true, files: 1, bytes: 512 * 1024 },
        { name: 'proot-tmp', present: false, files: 0, bytes: 0 },
      ],
      files: 4,
      bytes: SIZE,
    },
    plugin: { ledger: 'ok', checked: 2, packages: [
      { id: 'left-pad', present: true, files: 10, bytes: 1024 * 1024 },
      { id: '@scope/tool', present: false, files: 0, bytes: 0 },
    ] },
    ...overrides,
  }
}

function outcome(overrides: Partial<RuntimeCleanupOutcome> = {}): RuntimeCleanupOutcome {
  return {
    scope: 3, targets: 3, removed: 3, fallback: 0, failed: 0, files: 4, bytes: SIZE, skipped: 1, ok: true,
    ...overrides,
  }
}

function card(bridge: Partial<RuntimeBridge>): void {
  render(<RuntimeCleanupCard bridge={bridge as unknown as RuntimeBridge} />)
}

async function confirm(buttonName: string): Promise<void> {
  // 用 findByRole：状态是异步读回来的，逐包那些行要等一次 getRuntimeCleanupState 才有。
  const button = await screen.findByRole('button', { name: buttonName })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  const dialog = await screen.findByRole('alertdialog')
  fireEvent.click(within(dialog).getByRole('button', { name: '确认清理' }))
}

describe('数据清理卡片', () => {
  it('逐条列出真实存在的缓存、合计与插件登记数，两条入口都可用', async () => {
    card({ getRuntimeCleanupState: vi.fn().mockResolvedValue(state()) })

    await screen.findByText('诊断导出：3 个文件，共 3.0 MB')
    expect(screen.getByText('工作区分享暂存：1 个文件，共 512 KB')).toBeTruthy()
    expect(screen.getByText('缓存合计：4 个文件，共 3.5 MB')).toBeTruthy()
    expect(screen.getByText('插件数据：已登记 2 个包')).toBeTruthy()
    // 不存在的条目不上榜：显示一个 0 字节的目录只会让人以为那里还有东西。
    expect(screen.queryByText(/PRoot 临时目录/)).toBeNull()
    expect(screen.getByRole('button', { name: '清理缓存' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '清理插件数据' })).toBeEnabled()
  })

  it('运行时正在跑时两条入口都禁用并说明原因，不把临时目录删成打断会话', async () => {
    card({ getRuntimeCleanupState: vi.fn().mockResolvedValue(state({ runtimeRunning: true })) })

    await screen.findByText('运行时正在运行：请先停止 Harness 与 Ubuntu 终端再清理；正在使用的临时目录与插件数据不能删。')
    expect(screen.getByRole('button', { name: '清理缓存' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '清理插件数据' })).toBeDisabled()
    expect(screen.queryByText('没有可清理的缓存')).toBeNull()
  })

  it('账本损坏时只禁用插件数据档并讲清原因，缓存档照常可用', async () => {
    card({ getRuntimeCleanupState: vi.fn().mockResolvedValue(state({ plugin: { ledger: 'invalid', checked: 0, packages: [] } })) })

    await screen.findByText('插件版本账本已损坏，暂时无法确定哪些目录是插件数据。')
    expect(screen.getByRole('button', { name: '清理插件数据' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '清理缓存' })).toBeEnabled()
  })

  it('没有可清理的缓存时如实说明，缓存档禁用，插件数据档不受连带', async () => {
    const empty = state()
    empty.cache.entries = empty.cache.entries.map(entry => ({ ...entry, present: false }))
    card({ getRuntimeCleanupState: vi.fn().mockResolvedValue(empty) })

    await screen.findByText('没有可清理的缓存')
    expect(screen.getByRole('button', { name: '清理缓存' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '清理插件数据' })).toBeEnabled()
  })

  it('缓存档的二次确认写清删哪三个目录、不删什么，取消则什么都不做', async () => {
    const clean = vi.fn()
    card({ getRuntimeCleanupState: vi.fn().mockResolvedValue(state()), clearRuntimeCache: clean })

    const button = screen.getByRole('button', { name: '清理缓存' })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog.textContent).toContain('只删除诊断导出、工作区分享暂存与 PRoot 临时目录；不删待安装的更新包，也不碰运行环境的数据。')

    fireEvent.click(within(dialog).getByRole('button', { name: '取消' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(clean).not.toHaveBeenCalled()
  })

  it('清理成功后显示本次结果，并重新查一次占用而不是拿结果自己加减', async () => {
    const get = vi.fn()
      .mockResolvedValueOnce(state())
      .mockResolvedValueOnce({ runtimeRunning: false, cache: { entries: [], files: 0, bytes: 0 }, plugin: { ledger: 'ok', checked: 2, packages: [] } })
    const clean = vi.fn().mockResolvedValue(outcome())
    card({ getRuntimeCleanupState: get, clearRuntimeCache: clean })

    await confirm('清理缓存')
    await waitFor(() => expect(clean).toHaveBeenCalledOnce())
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))
    await screen.findByText('缓存：本次已清理 3 项，共 3.5 MB。')
    await screen.findByText('另有 1 项本来就不存在，已跳过。')
    await screen.findByText('没有可清理的缓存')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('有删不掉的项目时如实报失败并单独说清兜底条数，不谎称已清干净', async () => {
    const get = vi.fn().mockResolvedValue(state())
    const clean = vi.fn().mockResolvedValue(outcome({ removed: 2, fallback: 1, failed: 1, skipped: 0, ok: false }))
    card({ getRuntimeCleanupState: get, clearRuntimeCache: clean })

    await confirm('清理缓存')
    await screen.findByText('缓存：本次有 1 项未能清理，详见诊断与日志。')
    expect(screen.getByText('其中 1 项走了兜底删除路径。')).toBeTruthy()
    expect(screen.queryByText(/本次已清理/)).toBeNull()
  })

  it('运行时拒绝清理时把受控码翻成下一步，且失败后仍然重读占用', async () => {
    const get = vi.fn().mockResolvedValue(state())
    const clean = vi.fn().mockRejectedValue({ code: 'RUNTIME_BUSY', message: '请先停止 Harness 和 Ubuntu 终端' })
    card({ getRuntimeCleanupState: get, clearRuntimeCache: clean })

    await confirm('清理缓存')
    await screen.findByText('清理没能完成：请先停止 Harness 和 Ubuntu 终端')
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))
    expect(screen.queryByText(/本次已清理/)).toBeNull()
  })

  it('插件数据档遇到账本不可用时如实说明一个字节都没删', async () => {
    card({
      getRuntimeCleanupState: vi.fn().mockResolvedValue(state()),
      purgePluginData: vi.fn().mockRejectedValue({ code: 'CLEANUP_LEDGER_UNAVAILABLE', message: '未清理任何数据' }),
    })

    await confirm('清理插件数据')
    await screen.findByText('清理没能完成：插件版本账本不可用，未清理任何数据')
  })

  it('读取失败时如实显示并可重试，不把读不到说成没有可清理的缓存', async () => {
    const get = vi.fn().mockRejectedValue(new Error('桥不可用'))
    card({ getRuntimeCleanupState: get })

    await screen.findByText(/无法读取数据占用/)
    expect(screen.queryByText('没有可清理的缓存')).toBeNull()
    expect(screen.getByRole('button', { name: '清理缓存' })).toBeDisabled()

    get.mockResolvedValueOnce(state())
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await screen.findByText('缓存合计：4 个文件，共 3.5 MB')
  })

  it('逐包列出账本里的每个插件，没有数据的也列出来并说清', async () => {
    card({ getRuntimeCleanupState: vi.fn().mockResolvedValue(state()) })

    await screen.findByText('left-pad：10 个文件，共 1.0 MB')
    // 一个数据目录都没有的包也要列出来（不列会让人以为插件没装上），但没有可清的东西就不给点。
    expect(screen.getByText('@scope/tool：还没有数据')).toBeTruthy()
    expect(screen.getByRole('button', { name: '只清插件 left-pad 的数据' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '只清插件 @scope/tool 的数据' })).toBeDisabled()
  })

  it('逐行「只清这个插件」把那个包的 id 原样传下去，确认文案点名那个包', async () => {
    const get = vi.fn().mockResolvedValue(state())
    const purge = vi.fn().mockResolvedValue(outcome({ scope: 1, targets: 4, removed: 4, skipped: 0 }))
    card({ getRuntimeCleanupState: get, purgePluginData: purge })

    const button = await screen.findByRole('button', { name: '只清插件 left-pad 的数据' })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog.textContent).toContain('确认只清理插件 left-pad 的数据？')
    expect(dialog.textContent).toContain('其它插件的数据不动')
    fireEvent.click(within(dialog).getByRole('button', { name: '确认清理' }))

    await waitFor(() => expect(purge).toHaveBeenCalledOnce())
    expect(purge).toHaveBeenCalledWith({ packageName: 'left-pad' })
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))
    await screen.findByText('插件 left-pad 的数据：本次已清理 4 项，共 3.5 MB。')
  })

  it('整档「清理插件数据」不带任何参数，不拿 undefined 冒充省略', async () => {
    const purge = vi.fn().mockResolvedValue(outcome())
    card({ getRuntimeCleanupState: vi.fn().mockResolvedValue(state()), purgePluginData: purge })

    await confirm('清理插件数据')
    await waitFor(() => expect(purge).toHaveBeenCalledOnce())
    expect(purge).toHaveBeenCalledWith()
    await screen.findByText('全部已登记插件的数据：本次已清理 3 项，共 3.5 MB。')
  })

  it('运行时在跑时逐行按钮也禁用，不让人把正在用的数据删一半', async () => {
    card({ getRuntimeCleanupState: vi.fn().mockResolvedValue(state({ runtimeRunning: true })) })

    await screen.findByText(/运行时正在运行/)
    expect(screen.getByRole('button', { name: '只清插件 left-pad 的数据' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '清理插件数据' })).toBeDisabled()
  })

  it('账本不可用时不渲染逐包列表，一个按包清理的入口都不给', async () => {
    card({ getRuntimeCleanupState: vi.fn().mockResolvedValue(state({ plugin: { ledger: 'invalid', checked: 0, packages: [] } })) })

    await screen.findByText('插件版本账本已损坏，暂时无法确定哪些目录是插件数据。')
    expect(screen.queryByRole('button', { name: /只清插件/ })).toBeNull()
  })

  it('账本里核对不到的包被拒后翻成下一步文案，失败照样重读一次占用', async () => {
    const get = vi.fn().mockResolvedValue(state())
    const purge = vi.fn().mockRejectedValue({ code: 'CLEANUP_PLUGIN_UNKNOWN', message: '账本里核对不到这个插件的包目录，未清理任何数据' })
    card({ getRuntimeCleanupState: get, purgePluginData: purge })

    await confirm('只清插件 left-pad 的数据')
    await screen.findByText('清理没能完成：账本里没有这个插件：刷新列表后再试')
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2))
  })
})
