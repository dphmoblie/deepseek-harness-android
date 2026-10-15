import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { DeviceCapabilityEntry, DeviceCapabilityReport, RuntimeBridge } from '../platform/types'
import { DeviceCapabilityCard } from './DeviceCapabilityCard'

/**
 * 「设备能力」卡片：五项按原生给的顺序列全，只有非就绪的行才讲原因、下一步与替代方式，
 * 读不到清单时如实说读不到（而不是显示成「五项都不可用」）。
 *
 * 桥用假实现注入（同 `RuntimeCleanupCard.test.tsx`）：这些用例不会碰任何真实设备状态。
 */
function entry(id: string, overrides: Partial<DeviceCapabilityEntry> = {}): DeviceCapabilityEntry {
  return {
    id: id as DeviceCapabilityEntry['id'],
    state: 'ready',
    reason: 'READY',
    action: null,
    alternative: null,
    ...overrides,
  }
}

/** 六项全就绪：多数用例只改其中一项。 */
function report(overrides: DeviceCapabilityEntry[] = []): DeviceCapabilityReport {
  const base = ['shizuku', 'kernelsu_root', 'accessibility', 'virtual_screen', 'screenshot', 'chinese_input'].map(id => entry(id))
  return { atMs: 1_700_000_000_000, sdkInt: 34, capabilities: overrides.length > 0 ? overrides : base }
}

function card(bridge: Partial<RuntimeBridge>): void {
  render(<DeviceCapabilityCard bridge={bridge as unknown as RuntimeBridge} />)
}

/**
 * 按整行文字找那一行。
 *
 * 行里能力名是 `<strong>`，Testing Library 的 `getByText` 只拼**直接文本节点**，
 * 所以整段文字不会落在任何一个元素上；这里改用 `textContent` 全等匹配。
 */
function row(text: string): HTMLElement {
  const matches = screen.getAllByText((_, element) => element?.textContent === text)
  expect(matches).toHaveLength(1)
  return matches[0]
}

async function findRow(text: string): Promise<HTMLElement> {
  let found: HTMLElement | null = null
  await waitFor(() => { found = row(text) })
  return found as unknown as HTMLElement
}

describe('设备能力卡片', () => {
  it('AI Shell 关闭时说明副屏阻断原因并跳转设备 Shell 设置，不直接开启权限', async () => {
    const onOpenDeviceShell = vi.fn()
    const bridge = { getDeviceCapabilities: vi.fn().mockResolvedValue(report([
      entry('virtual_screen', { state: 'needs_action', reason: 'DEVICE_SHELL_DISABLED', action: 'enable_device_shell' }),
    ])) } as unknown as RuntimeBridge
    render(<DeviceCapabilityCard bridge={bridge} onOpenDeviceShell={onOpenDeviceShell} />)
    const button = await screen.findByRole('button', { name: '设备 Shell 设置' })
    expect(button.closest('p')?.textContent).toContain('AI Shell 开关没有打开')
    expect(button.closest('p')?.textContent).toContain('到「设备 Shell 设置」开启 AI Shell')
    fireEvent.click(button)
    expect(onOpenDeviceShell).toHaveBeenCalledTimes(1)
  })

  it('六项按原生给的顺序列全，名称与技术标识分开显示', async () => {
    card({ getDeviceCapabilities: vi.fn().mockResolvedValue(report()) })

    await findRow('Shizuku（设备 Shell） 可用')
    row('根通道（KernelSU） 可用')
    row('无障碍服务 可用')
    row('副屏 可用')
    row('整屏截图 可用')
    row('中文输入 可用')
  })

  it('降级的那一项说「能用但保护降了一档」，并讲清少了什么', async () => {
    card({
      getDeviceCapabilities: vi.fn().mockResolvedValue(report([
        entry('kernelsu_root', { state: 'degraded', reason: 'ROOT_SANDBOX_DEGRADED' }),
      ])),
    })

    const root = await findRow('根通道（KernelSU） 可用，但保护降了一档 · 命令照常执行，但这台设备给不了完整的隔离：静态审查已按严格档收紧，对 /dev 的遮蔽没有生效')
    /* 降级**不许**出现「下一步」：用户没有可点的一步，指路反而会让人以为再设一步就能补回来。 */
    expect(root.textContent).not.toContain('下一步')
  })

  it('就绪的行不教用户去设置：不说原因、不说下一步、不说替代方式', async () => {
    card({ getDeviceCapabilities: vi.fn().mockResolvedValue(report()) })

    await findRow('Shizuku（设备 Shell） 可用')
    expect(screen.queryByText(/下一步：/u)).toBeNull()
    expect(screen.queryByText(/替代方式：/u)).toBeNull()
    expect(screen.queryByText(/Shizuku 没有在运行/u)).toBeNull()
  })

  it('需要一步设置的行把原因与下一步都说清', async () => {
    card({
      getDeviceCapabilities: vi.fn().mockResolvedValue(report([
        entry('shizuku', { state: 'needs_action', reason: 'SHIZUKU_NOT_RUNNING', action: 'open_shizuku' }),
      ])),
    })

    await findRow('Shizuku（设备 Shell） 需要一步设置 · Shizuku 没有在运行 · 下一步：打开 Shizuku')
  })

  it('被别的前置挡住的行说明缺的是哪一环，并给出替代方式', async () => {
    card({
      getDeviceCapabilities: vi.fn().mockResolvedValue(report([
        entry('shizuku', { state: 'unsupported', reason: 'SHIZUKU_NOT_INSTALLED', action: 'install_shizuku', alternative: 'NO_DEVICE_SHELL' }),
        entry('chinese_input', { state: 'blocked', reason: 'ACCESSIBILITY_OFF', action: 'open_accessibility_settings', alternative: 'SHELL_ASCII_ONLY' }),
      ])),
    })

    await findRow(
      'Shizuku（设备 Shell） 这台设备不支持 · 还没有安装 Shizuku · 下一步：安装 Shizuku · 替代方式：没有设备 Shell，需要整屏操作的功能都用不了',
    )
    row(
      '中文输入 被别的前置挡住 · 无障碍服务没有开启 · 下一步：到系统设置里开启本应用的无障碍服务 · 替代方式：只能通过 Shell 发送可打印的 ASCII 字符',
    )
  })

  it('版本不够的项报的是版本原因，不是「被挡住」', async () => {
    card({
      getDeviceCapabilities: vi.fn().mockResolvedValue({
        atMs: 1_700_000_000_000,
        sdkInt: 28,
        capabilities: ['shizuku', 'accessibility', 'virtual_screen', 'screenshot', 'chinese_input'].map(id =>
          id === 'virtual_screen' ? entry(id, { state: 'unsupported', reason: 'ANDROID_TOO_OLD' }) : entry(id)),
      }),
    })

    await findRow('副屏 这台设备不支持 · 需要 Android 10 或更高版本')
  })

  it('讲不出原因的码照原样显示，不编一句解释', async () => {
    card({
      getDeviceCapabilities: vi.fn().mockResolvedValue(report([
        entry('screenshot', { state: 'blocked', reason: 'SOMETHING_NEW', action: 'do_something_new' }),
      ])),
    })

    await findRow('整屏截图 被别的前置挡住 · 暂时说不上来具体原因 · 下一步：do_something_new')
  })

  it('读不到清单时说读不到，并给一次重试（不显示成「五项都不可用」）', async () => {
    const failing = vi.fn().mockRejectedValueOnce(new Error('读不到')).mockResolvedValue(report())
    card({ getDeviceCapabilities: failing })

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('无法读取设备能力清单')
    expect(screen.queryByText(/不可用/u)).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await findRow('Shizuku（设备 Shell） 可用')
    expect(failing).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('「重新检测」会再读一次，能力清单跟着变', async () => {
    const bridge = {
      getDeviceCapabilities: vi.fn()
        .mockResolvedValueOnce(report())
        .mockResolvedValueOnce(report([entry('accessibility', { state: 'needs_action', reason: 'ACCESSIBILITY_OFF', action: 'open_accessibility_settings' })])),
    }
    card(bridge)

    await findRow('无障碍服务 可用')
    fireEvent.click(screen.getByRole('button', { name: '重新检测' }))
    await findRow('无障碍服务 需要一步设置 · 无障碍服务没有开启 · 下一步：到系统设置里开启本应用的无障碍服务')
    expect(bridge.getDeviceCapabilities).toHaveBeenCalledTimes(2)
  })

  it('较早的请求迟到时不会覆盖较新的能力快照', async () => {
    let finishOld!: (value: DeviceCapabilityReport) => void
    let finishNew!: (value: DeviceCapabilityReport) => void
    const bridge = {
      getDeviceCapabilities: vi.fn()
        .mockImplementationOnce(() => new Promise<DeviceCapabilityReport>(resolve => { finishOld = resolve }))
        .mockImplementationOnce(() => new Promise<DeviceCapabilityReport>(resolve => { finishNew = resolve })),
    }
    card(bridge)
    fireEvent(window, new Event('focus'))
    finishNew(report([entry('accessibility', { state: 'needs_action', reason: 'ACCESSIBILITY_OFF', action: 'open_accessibility_settings' })]))
    await findRow('无障碍服务 需要一步设置 · 无障碍服务没有开启 · 下一步：到系统设置里开启本应用的无障碍服务')
    finishOld(report())
    await new Promise(resolve => setTimeout(resolve, 0))
    row('无障碍服务 需要一步设置 · 无障碍服务没有开启 · 下一步：到系统设置里开启本应用的无障碍服务')
  })

  it('切回前台时立即重新读取能力', async () => {
    const bridge = {
      getDeviceCapabilities: vi.fn()
        .mockResolvedValueOnce(report())
        .mockResolvedValueOnce(report([entry('shizuku', { state: 'needs_action', reason: 'SHIZUKU_NOT_RUNNING', action: 'open_shizuku' })])),
    }
    card(bridge)
    await findRow('Shizuku（设备 Shell） 可用')
    fireEvent(window, new Event('focus'))
    await findRow('Shizuku（设备 Shell） 需要一步设置 · Shizuku 没有在运行 · 下一步：打开 Shizuku')
    expect(bridge.getDeviceCapabilities).toHaveBeenCalledTimes(2)
  })
})
