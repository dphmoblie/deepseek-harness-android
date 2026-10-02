import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RuntimeBridge } from '../platform/types'
import { DeviceShellSettings } from './DeviceShellSettings'

describe('AI Shell 持续授权', () => {
  it('副屏入口跟随授权开关，并报告原生页面打开失败', async () => {
    const open = vi.fn().mockRejectedValue(new Error('设备不支持'))
    const bridge = { getDeviceShellAccess: vi.fn().mockResolvedValue({ enabled: true }), openVirtualScreen: open } as unknown as RuntimeBridge
    render(<DeviceShellSettings bridge={bridge} disabled={false} />)
    const button = screen.getByRole('button', { name: '目标应用副屏（实验功能）' })
    expect(button).toBeDisabled()
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    await screen.findByRole('alert')
    expect(open).toHaveBeenCalledOnce()
    expect(button).toBeEnabled()
  })
  it('读取之前不可操作，保存后以原生返回值为准', async () => {
    let resolve!: (value: { enabled: boolean }) => void
    const get = vi.fn(() => new Promise<{ enabled: boolean }>(done => { resolve = done }))
    const set = vi.fn().mockResolvedValue({ enabled: true })
    const bridge = { getDeviceShellAccess: get, setDeviceShellAccess: set } as unknown as RuntimeBridge
    render(<DeviceShellSettings bridge={bridge} disabled={false} />)
    const toggle = screen.getByRole('switch', { name: '允许 AI 调用 Shell' })
    expect(toggle).toBeDisabled()
    resolve({ enabled: false })
    await waitFor(() => expect(toggle).toBeEnabled())
    fireEvent.click(toggle)
    await waitFor(() => expect(toggle).toBeChecked())
    expect(set).toHaveBeenCalledOnce()
    expect(set).toHaveBeenCalledWith(true)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('保存失败后重新读取真实状态，不误报授权成功', async () => {
    const get = vi.fn().mockResolvedValue({ enabled: false })
    const set = vi.fn().mockRejectedValue(new Error('保存失败'))
    const bridge = { getDeviceShellAccess: get, setDeviceShellAccess: set } as unknown as RuntimeBridge
    render(<DeviceShellSettings bridge={bridge} disabled={false} />)
    const toggle = screen.getByRole('switch')
    await waitFor(() => expect(toggle).toBeEnabled())
    fireEvent.click(toggle)
    await screen.findByRole('alert')
    expect(toggle).not.toBeChecked()
    expect(toggle).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(toggle).toBeEnabled())
    expect(get).toHaveBeenCalledTimes(2)
  })
})
