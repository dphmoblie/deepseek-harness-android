import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RuntimeBridge } from '../platform/types'
import { DeviceShellSettings } from './DeviceShellSettings'

describe('AI Shell 持续授权', () => {
  it('副屏入口跟随授权开关，点击后交给上层跳到独立设置页', async () => {
    const open = vi.fn()
    const authorized = { getDeviceShellAccess: vi.fn().mockResolvedValue({ enabled: true }) } as unknown as RuntimeBridge
    const { unmount } = render(<DeviceShellSettings bridge={authorized} disabled={false} onOpenVirtualScreen={open} />)
    const button = screen.getByRole('button', { name: '目标应用副屏设置' })
    expect(button).toBeDisabled()
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    // 入口只负责跳转：跳转本身不会再失败，所以这里不该报错（打开系统原生页面那条路已经不在这个组件里）。
    expect(open).toHaveBeenCalledOnce()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    unmount()
    const unauthorized = { getDeviceShellAccess: vi.fn().mockResolvedValue({ enabled: false }) } as unknown as RuntimeBridge
    render(<DeviceShellSettings bridge={unauthorized} disabled={false} onOpenVirtualScreen={open} />)
    await waitFor(() => expect(screen.getByRole('switch')).toBeEnabled())
    expect(screen.getByRole('button', { name: '目标应用副屏设置' })).toBeDisabled()
    expect(open).toHaveBeenCalledOnce()
  })
  it('读取之前不可操作，保存后以原生返回值为准', async () => {
    let resolve!: (value: { enabled: boolean }) => void
    const get = vi.fn(() => new Promise<{ enabled: boolean }>(done => { resolve = done }))
    const set = vi.fn().mockResolvedValue({ enabled: true })
    const bridge = { getDeviceShellAccess: get, setDeviceShellAccess: set } as unknown as RuntimeBridge
    render(<DeviceShellSettings bridge={bridge} disabled={false} onOpenVirtualScreen={() => {}} />)
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
    render(<DeviceShellSettings bridge={bridge} disabled={false} onOpenVirtualScreen={() => {}} />)
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

  it('给出无障碍自动化的免责说明，且不依赖读取授权状态是否成功', () => {
    const bridge = { getDeviceShellAccess: vi.fn().mockResolvedValue({ enabled: false }) } as unknown as RuntimeBridge
    const { container } = render(<DeviceShellSettings bridge={bridge} disabled={false} onOpenVirtualScreen={() => {}} />)
    const disclaimer = container.querySelector('[aria-labelledby="accessibility-disclaimer"]')
    expect(disclaimer).not.toBeNull()
    expect(disclaimer?.textContent).toContain('免责说明')
    // 三件事必须说全：只对自己有权操作的应用启用、误点与敏感页面的风险、后果自负。
    expect(disclaimer?.textContent).toContain('请只对自己有权操作的应用启用')
    expect(disclaimer?.textContent).toContain('涉及支付、验证码与隐私信息')
    expect(disclaimer?.textContent).toContain('由使用者承担')
  })
})
