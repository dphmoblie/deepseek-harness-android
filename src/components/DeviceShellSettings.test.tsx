import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RuntimeBridge } from '../platform/types'
import { DeviceShellSettings } from './DeviceShellSettings'

describe('AI Shell 持续授权', () => {
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

  /**
   * 副屏入口与免责说明已经搬去「自动化设置」页：这一页只留 AI Shell 本身，
   * 所以这里反向钉住——不要在这页再长出副屏按钮或免责说明块。
   */
  it('只负责 AI Shell：不再有副屏入口与免责说明块', async () => {
    const bridge = { getDeviceShellAccess: vi.fn().mockResolvedValue({ enabled: true }) } as unknown as RuntimeBridge
    const { container } = render(<DeviceShellSettings bridge={bridge} disabled={false} />)
    await waitFor(() => expect(screen.getByRole('switch')).toBeEnabled())
    expect(screen.queryByRole('button', { name: '目标应用副屏设置' })).toBeNull()
    expect(container.querySelector('[aria-labelledby="accessibility-disclaimer"]')).toBeNull()
    expect(screen.getByRole('heading', { name: 'AI Shell' })).toBeVisible()
  })
})
