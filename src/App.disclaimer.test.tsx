import { Capacitor } from '@capacitor/core'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { arriveAtSettingsHome, beforeEachAppTest, bridge, settings } from './__tests__/appTestHarness'
import { APP_DISCLAIMER_STORAGE_KEY, APP_DISCLAIMER_VERSION, appDisclaimerStorageKey } from './appDisclaimer'
import { FIRST_RUN_PERMISSIONS_STORAGE_KEY } from './components/FirstRunPermissions'
import { ONBOARDING_STORAGE_KEY } from './components/Onboarding'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({ TerminalPanel: () => <div data-testid="terminal-panel" /> }))

import { App } from './App'

describe('应用免责条款：首次启动门禁与设置页查看', () => {
  beforeEach(beforeEachAppTest)
  afterEach(() => vi.restoreAllMocks())

  it('首次启动弹出免责条款，不同意时直接退出应用且不进入主界面或预启动服务', async () => {
    window.localStorage.removeItem(APP_DISCLAIMER_STORAGE_KEY)
    window.localStorage.removeItem(ONBOARDING_STORAGE_KEY)
    bridge.getSettings.mockResolvedValue({ ...settings, prewarmHarness: true })

    render(<App />)

    const dialog = await screen.findByRole('dialog', { name: '免责条款' })
    expect(dialog).toBeVisible()
    // 未同意前不能进入主界面、权限说明或引导，也不能预启动 Harness。
    expect(screen.queryByRole('heading', { name: '对话已就绪' })).toBeNull()
    expect(screen.queryByRole('dialog', { name: '免费声明' })).toBeNull()
    expect(bridge.startHarness).not.toHaveBeenCalled()

    const agreeButton = within(dialog).getByRole('button', { name: '同意并继续' })
    expect(agreeButton).toBeDisabled()

    fireEvent.click(within(dialog).getByRole('button', { name: '不同意并退出' }))

    await waitFor(() => expect(bridge.exitApp).toHaveBeenCalledTimes(1))
    expect(window.localStorage.getItem(APP_DISCLAIMER_STORAGE_KEY)).toBeNull()
    expect(screen.getByRole('dialog', { name: '免责条款' })).toBeVisible()
    expect(screen.getByRole('alert')).toHaveTextContent('未同意免责条款无法使用本应用')
    expect(screen.queryByRole('heading', { name: '对话已就绪' })).toBeNull()
  })

  it('首次启动勾选同意后写入当前版本标记，按顺序进入权限说明/引导，再次启动不再弹出', async () => {
    vi.spyOn(Capacitor, 'isNativePlatform').mockReturnValue(true)
    window.localStorage.removeItem(APP_DISCLAIMER_STORAGE_KEY)
    window.localStorage.removeItem(FIRST_RUN_PERMISSIONS_STORAGE_KEY)
    window.localStorage.removeItem(ONBOARDING_STORAGE_KEY)

    const first = render(<App />)
    const dialog = await screen.findByRole('dialog', { name: '免责条款' })
    // 免责条款优先于首次权限说明与引导。
    expect(screen.queryByRole('dialog', { name: '权限说明与授权' })).toBeNull()
    expect(screen.queryByRole('dialog', { name: '免费声明' })).toBeNull()

    fireEvent.click(within(dialog).getByRole('checkbox'))
    fireEvent.click(within(dialog).getByRole('button', { name: '同意并继续' }))

    expect(window.localStorage.getItem(APP_DISCLAIMER_STORAGE_KEY)).toBe('1')
    expect(await screen.findByRole('dialog', { name: '权限说明与授权' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '暂不授权' }))
    expect(await screen.findByRole('dialog', { name: '免费声明' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '跳过引导' }))
    expect(await screen.findByRole('heading', { name: '对话已就绪' })).toBeVisible()

    first.unmount()

    render(<App />)
    expect(await screen.findByRole('heading', { name: '对话已就绪' })).toBeVisible()
    expect(screen.queryByRole('dialog', { name: '免责条款' })).toBeNull()
  })

  it('旧版本条款同意记录失效，启动时重新弹出当前版本条款', async () => {
    window.localStorage.removeItem(APP_DISCLAIMER_STORAGE_KEY)
    window.localStorage.setItem(appDisclaimerStorageKey(APP_DISCLAIMER_VERSION - 1), '1')

    render(<App />)

    expect(await screen.findByRole('dialog', { name: '免责条款' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '对话已就绪' })).toBeNull()
  })

  it('存储不可写时勾选同意仍可进入本次会话，不把用户永久锁在门禁里', async () => {
    window.localStorage.removeItem(APP_DISCLAIMER_STORAGE_KEY)
    render(<App />)
    const dialog = await screen.findByRole('dialog', { name: '免责条款' })

    const original = window.localStorage.setItem.bind(window.localStorage)
    vi.spyOn(window.Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
      if (key === APP_DISCLAIMER_STORAGE_KEY) throw new Error('存储不可用')
      return original.call(this, key, value)
    })

    fireEvent.click(within(dialog).getByRole('checkbox'))
    fireEvent.click(within(dialog).getByRole('button', { name: '同意并继续' }))

    expect(await screen.findByRole('heading', { name: '对话已就绪' })).toBeVisible()
  })

  it('可在设置页「关于与反馈」中随时打开免责条款并关闭回看，不出现退出按钮', async () => {
    render(<App />)
    await arriveAtSettingsHome()

    const diagnosticsGroup = await screen.findByRole('region', { name: '诊断与关于' })
    fireEvent.click(within(diagnosticsGroup).getByRole('button', { name: /关于与反馈/ }))
    expect(await screen.findByRole('heading', { level: 1, name: '关于与反馈' })).toBeVisible()

    const entry = screen.getByRole('button', { name: '查看免责条款' })
    expect(entry).toBeVisible()
    fireEvent.click(entry)

    const dialog = await screen.findByRole('dialog', { name: '免责条款' })
    expect(dialog).toBeVisible()
    expect(within(dialog).queryByRole('button', { name: '不同意并退出' })).toBeNull()
    expect(within(dialog).queryByRole('checkbox')).toBeNull()

    fireEvent.click(within(dialog).getByRole('button', { name: '知道了' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '免责条款' })).toBeNull())
    expect(bridge.exitApp).not.toHaveBeenCalled()
  })

  it('英文模式下设置页「关于与反馈 / 查看免责条款」入口无中文残留', async () => {
    window.localStorage.setItem('dsh-mobile-language-v1', 'en')
    render(<App />)
    await arriveAtSettingsHome()

    const diagnosticsGroup = await screen.findByRole('region', { name: 'Diagnostics & About' })
    const aboutEntry = within(diagnosticsGroup).getByRole('button', { name: /About & Feedback/ })
    expect(aboutEntry).toBeVisible()
    expect(aboutEntry.textContent ?? '').not.toMatch(/[\u4e00-\u9fff]/)
    fireEvent.click(aboutEntry)

    const viewButton = await screen.findByRole('button', { name: 'View disclaimer' })
    expect(viewButton).toBeVisible()
    expect(viewButton.textContent ?? '').not.toMatch(/[\u4e00-\u9fff]/)
  })
})
