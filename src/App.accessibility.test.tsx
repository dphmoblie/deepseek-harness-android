import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES } from './platform/types'
import { beforeEachAppTest, bridge, openSettingsPage } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 无障碍白名单：自动包含本应用 + 按次验证密码。
 *
 * 这一组守两条用户可见的契约：
 *  1. 本应用（`io.deepseekharness.mobile`）**始终**在白名单里，不用用户维护；
 *  2. 一旦设置了验证密码，**每一次**修改白名单都要当场输入它，界面不保留输入内容。
 *
 * 密码本身归原生侧（只存盐与哈希），这里断言的是界面与桥的交互：
 * 什么时候必须带上密码、什么时候在本地就拦下来、以及密码不出现在任何提示文案里。
 */
describe('无障碍白名单与验证密码', () => {
  beforeEach(beforeEachAppTest)
  afterEach(() => vi.restoreAllMocks())

  /** 原生侧返回的白名单状态：有效白名单里已包含自动项，与真机语义一致。 */
  function accessibilityState(passwordConfigured: boolean, allowed: string[] = [], whitelistEnabled = true) {
    return {
      enabled: false,
      whitelistEnabled,
      allowedPackages: [...new Set([...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, ...allowed])],
      alwaysAllowedPackages: [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES],
      passwordConfigured,
    }
  }

  /** 进入「Shizuku 与设备 Shell」二级页——无障碍区块就在这一页。 */
  async function openAccessibilityPage(passwordConfigured: boolean, allowed: string[] = []): Promise<void> {
    bridge.getAccessibilityAutomationState.mockResolvedValue(accessibilityState(passwordConfigured, allowed))
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('Shizuku 与设备 Shell')
  }

  it('本应用始终在白名单里，并如实显示当前白名单', async () => {
    await openAccessibilityPage(false, ['com.example.reader'])

    expect(
      await screen.findByText('本应用（io.deepseekharness.mobile）始终在白名单里：服务重启、连接重建都不会掉，也不需要写进下面的列表。'),
    ).toBeInTheDocument()
    expect(screen.getByText('当前白名单：io.deepseekharness.mobile、com.example.reader')).toBeInTheDocument()
  })

  it('未设置密码时保存白名单不带密码', async () => {
    await openAccessibilityPage(false)

    // 没设置密码就不该出现密码输入框：多一个空框只会让人以为必须填。
    expect(screen.queryByLabelText('修改白名单的验证密码')).not.toBeInTheDocument()

    fireEvent.change(await screen.findByLabelText('目标应用包名'), { target: { value: 'com.example.reader' } })
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))

    await waitFor(() => expect(bridge.setAccessibilityAutomationPackages).toHaveBeenCalledWith(['com.example.reader'], undefined, true))
    expect(await screen.findByText('无障碍应用白名单已保存')).toBeInTheDocument()
  })

  it('已设置密码时，没输入密码就点保存会被本地拦下，桥一次都不调用', async () => {
    await openAccessibilityPage(true)

    fireEvent.change(await screen.findByLabelText('目标应用包名'), { target: { value: 'com.example.reader' } })
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))

    expect(await screen.findByText('修改无障碍白名单需要先输入验证密码')).toBeInTheDocument()
    expect(bridge.setAccessibilityAutomationPackages).not.toHaveBeenCalled()
  })

  it('已设置密码时，带上密码保存，并在提交后立刻清空输入框', async () => {
    await openAccessibilityPage(true)

    fireEvent.change(await screen.findByLabelText('目标应用包名'), { target: { value: 'com.example.reader\ncom.example.notes' } })
    // 用泛型参数而不是 as 断言：断言会被 eslint 的 no-unnecessary-type-assertion 挡下，
    // 而裸的 HTMLElement 读 .value 会过不了 tsc。
    const passwordInput = screen.getByLabelText<HTMLInputElement>('修改白名单的验证密码')
    fireEvent.change(passwordInput, { target: { value: 'passphrase-1' } })
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))

    await waitFor(() => expect(bridge.setAccessibilityAutomationPackages).toHaveBeenCalledWith(
      ['com.example.reader', 'com.example.notes'],
      'passphrase-1',
      true,
    ))
    // 密码只用于本次过桥：提交后界面不留存（原生侧也只保存盐与哈希）。
    await waitFor(() => expect(passwordInput.value).toBe(''))
  })

  it('默认开启限制，关闭并保存后保留名单，再开启恢复限制', async () => {
    await openAccessibilityPage(false, ['com.example.reader'])
    const toggle = screen.getByRole('switch', { name: '启用无障碍白名单限制' })
    const packages = [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, 'com.example.reader']
    expect(toggle).toBeChecked()
    fireEvent.click(toggle)
    expect(screen.getByText('当前生效：白名单限制已开启')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))
    await waitFor(() => expect(bridge.setAccessibilityAutomationPackages).toHaveBeenCalledWith(packages, undefined, false))
    expect(await screen.findByText('当前生效：白名单限制已关闭')).toBeInTheDocument()
    expect(screen.getByLabelText('目标应用包名')).toHaveValue(packages.join('\n'))
    fireEvent.click(toggle)
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))
    await waitFor(() => expect(bridge.setAccessibilityAutomationPackages).toHaveBeenLastCalledWith(packages, undefined, true))
    expect(await screen.findByText('当前生效：白名单限制已开启')).toBeInTheDocument()
  })

  it('关闭限制仍需密码；保存失败不改变当前生效状态', async () => {
    await openAccessibilityPage(true, ['com.example.reader'])
    fireEvent.click(screen.getByRole('switch', { name: '启用无障碍白名单限制' }))
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))
    expect(await screen.findByText('修改无障碍白名单需要先输入验证密码')).toBeInTheDocument()
    expect(bridge.setAccessibilityAutomationPackages).not.toHaveBeenCalled()
    bridge.setAccessibilityAutomationPackages.mockRejectedValueOnce(new Error('验证密码不正确'))
    fireEvent.change(screen.getByLabelText('修改白名单的验证密码'), { target: { value: 'wrong-passphrase' } })
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))
    expect(await screen.findByText('验证密码不正确')).toBeInTheDocument()
    expect(screen.getByText('当前生效：白名单限制已开启')).toBeInTheDocument()
    expect(screen.queryByText('当前生效：白名单限制已关闭')).not.toBeInTheDocument()
    expect(screen.getByLabelText('修改白名单的验证密码')).toHaveValue('')
    bridge.setAccessibilityAutomationPackages.mockResolvedValueOnce(accessibilityState(true, ['com.example.reader'], false))
    fireEvent.change(screen.getByLabelText('修改白名单的验证密码'), { target: { value: 'test-passphrase' } })
    fireEvent.click(screen.getByRole('button', { name: '保存白名单' }))
    expect(await screen.findByText('当前生效：白名单限制已关闭')).toBeInTheDocument()
    expect(bridge.setAccessibilityAutomationPackages).toHaveBeenLastCalledWith(
      [...ALWAYS_ALLOWED_ACCESSIBILITY_PACKAGES, 'com.example.reader'], 'test-passphrase', false,
    )
  })

  it('重新读取已关闭的配置时正确回显开关与名单', async () => {
    bridge.getAccessibilityAutomationState.mockResolvedValue(accessibilityState(false, ['com.example.reader'], false))
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('Shizuku 与设备 Shell')
    expect(screen.getByRole('switch', { name: '启用无障碍白名单限制' })).not.toBeChecked()
    expect(screen.getByText('当前生效：白名单限制已关闭')).toBeInTheDocument()
    expect(screen.getByLabelText('目标应用包名')).toHaveValue('io.deepseekharness.mobile\ncom.example.reader')
  })

  it('两次输入的新密码不一致时在本地报错，桥一次都不调用', async () => {
    await openAccessibilityPage(false)

    fireEvent.change(await screen.findByLabelText('新验证密码'), { target: { value: 'passphrase-1' } })
    fireEvent.change(screen.getByLabelText('确认新验证密码'), { target: { value: 'passphrase-2' } })
    fireEvent.click(screen.getByRole('button', { name: '设置密码' }))

    expect(await screen.findByText('两次输入的新密码不一致')).toBeInTheDocument()
    expect(bridge.setAccessibilityPassword).not.toHaveBeenCalled()
  })

  it('新密码不合规（太短）时也在本地报错', async () => {
    await openAccessibilityPage(false)

    fireEvent.change(await screen.findByLabelText('新验证密码'), { target: { value: 'short' } })
    fireEvent.change(screen.getByLabelText('确认新验证密码'), { target: { value: 'short' } })
    fireEvent.click(screen.getByRole('button', { name: '设置密码' }))

    // 复用平台层同一套规则：文案里出现的是字段名与长度要求，不是用户输入的内容。
    expect(await screen.findByText('新验证密码需要 6 到 64 个字符')).toBeInTheDocument()
    expect(bridge.setAccessibilityPassword).not.toHaveBeenCalled()
  })

  it('首次设置密码不带当前密码', async () => {
    await openAccessibilityPage(false)

    // 还没设置过：不该出现「当前密码」这一栏（没有东西可以验）。
    expect(screen.queryByLabelText('当前验证密码')).not.toBeInTheDocument()

    fireEvent.change(await screen.findByLabelText('新验证密码'), { target: { value: 'passphrase-1' } })
    fireEvent.change(screen.getByLabelText('确认新验证密码'), { target: { value: 'passphrase-1' } })
    fireEvent.click(screen.getByRole('button', { name: '设置密码' }))

    await waitFor(() => expect(bridge.setAccessibilityPassword).toHaveBeenCalledWith('passphrase-1', undefined))
  })

  it('已设置密码时修改密码，必须带上当前密码', async () => {
    await openAccessibilityPage(true)

    fireEvent.change(await screen.findByLabelText('当前验证密码'), { target: { value: 'passphrase-1' } })
    fireEvent.change(screen.getByLabelText('新验证密码'), { target: { value: 'passphrase-2' } })
    fireEvent.change(screen.getByLabelText('确认新验证密码'), { target: { value: 'passphrase-2' } })
    fireEvent.click(screen.getByRole('button', { name: '修改密码' }))

    await waitFor(() => expect(bridge.setAccessibilityPassword).toHaveBeenCalledWith('passphrase-2', 'passphrase-1'))
  })

  it('清除密码要带当前密码，缺了就不给点', async () => {
    await openAccessibilityPage(true)

    const clear = await screen.findByRole('button', { name: '清除密码' })
    expect(clear).toBeDisabled()

    fireEvent.change(screen.getByLabelText('当前验证密码'), { target: { value: 'passphrase-1' } })
    await waitFor(() => expect(clear).toBeEnabled())
    fireEvent.click(clear)

    await waitFor(() => expect(bridge.clearAccessibilityPassword).toHaveBeenCalledWith('passphrase-1'))
  })

  it('忘记密码时走系统生物识别重置', async () => {
    await openAccessibilityPage(true)

    fireEvent.click(await screen.findByRole('button', { name: '用生物识别重置' }))

    // 重置只清密码、保留白名单：这条路是「本人证明身份」，不是绕过保护。
    await waitFor(() => expect(bridge.resetAccessibilityPasswordWithBiometric).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('验证密码已重置')).toBeInTheDocument()
  })
})
