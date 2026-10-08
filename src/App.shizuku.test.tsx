import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, openSettingsPage } from './__tests__/appTestHarness'
import type { ShizukuState } from './platform/types'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/** 桥错误：Capacitor 过桥后码在 `error.code` 上（`native-bridge.js` 把 {message, code} 拷进 Error）。 */
function bridgeError(message: string, code?: string): Error {
  const error = new Error(message)
  if (code !== undefined) Object.assign(error, { code })
  return error
}

/**
 * Shizuku 版本显示（真机反馈：「更新到 13.6.0 后版本识别不对」）。
 *
 * 这一组守一条契约：界面上出现的两个版本号必须各说各话——
 *  - `version` 是**服务端 API 版本**，13.x 的 Shizuku 应用上报的都是 `13`；
 *  - `appVersion` 才是**已安装应用的版本名**（`13.6.0` 这种）。
 *
 * 以前页面只显示前者（拼成「未运行（v13）」），用户拿它跟 Shizuku 里的 `13.6.0`
 * 一比就以为识别错了；现在两者分开显示，且**原生没返回应用版本时不许编造**。
 */
describe('Shizuku 版本显示', () => {
  beforeEach(beforeEachAppTest)

  /** 进入「Shizuku 与设备 Shell」二级页——Shizuku 区块就在这一页。 */
  async function openShizukuPage(state: Partial<ShizukuState>): Promise<HTMLElement> {
    bridge.getShizukuState.mockResolvedValue({
      installed: true,
      running: true,
      permission: 'granted',
      connected: true,
      version: '13',
      ...state,
    })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await openSettingsPage('Shizuku 与设备 Shell')
    return screen.findByRole('region', { name: 'Shizuku' })
  }

  it('应用版本与服务端 API 版本分开显示，不合并成一个数', async () => {
    const section = await openShizukuPage({ appVersion: '13.6.0' })

    // 同一行里两件事都在：左边是应用版本名，右边是服务端 API 版本。
    expect(within(section).getByText(/Shizuku 应用 13\.6\.0 · 服务端 API 13/)).toBeVisible()
    // 状态标签照旧只说状态，不掺版本号。
    expect(within(section).getByText('已连接')).toBeVisible()
    expect(within(section).getByText('连接可用')).toBeVisible()
  })

  it('原生还没返回应用版本时只显示服务端 API 版本，不编造', async () => {
    const section = await openShizukuPage({ appVersion: undefined })

    expect(within(section).queryByText(/Shizuku 应用/)).toBeNull()
    expect(within(section).getByText(/服务端 API 13/)).toBeVisible()
  })

  it('未运行时照样给出应用版本，并保留打开 Shizuku 的入口', async () => {
    const section = await openShizukuPage({
      running: false,
      permission: 'undetermined',
      connected: false,
      version: '',
      appVersion: '13.6.0',
    })

    // 没运行就没有服务端 API 版本，但应用版本还是要说清楚。
    expect(within(section).getByText(/设备 Shell ·未运行 · Shizuku 应用 13\.6\.0/)).toBeVisible()
    expect(within(section).queryByText(/服务端 API/)).toBeNull()

    fireEvent.click(within(section).getByRole('button', { name: /打开 Shizuku/ }))
    await waitFor(() => expect(bridge.openShizuku).toHaveBeenCalledTimes(1))
    // 只是打开 Shizuku：不能顺手去请求授权或连接。
    expect(bridge.requestShizukuPermission).not.toHaveBeenCalled()
    expect(bridge.connectShizuku).not.toHaveBeenCalled()
  })

  it('连接超时后提示去重启 Shizuku 服务，而不是只报一句超时', async () => {
    const section = await openShizukuPage({ connected: false })
    bridge.connectShizuku.mockRejectedValue(bridgeError('连接 Shizuku 用户服务超时', 'SHIZUKU_SERVICE_TIMEOUT'))

    fireEvent.click(within(section).getByRole('button', { name: /连接 Shizuku/ }))

    // 更新 Shizuku 后旧服务端仍在跑的典型症状：绑定超时。文案必须给出下一步（重启服务/重启手机），
    // 否则用户只能反复点「连接 Shizuku」——本应用没有重启 Shizuku 服务的接口。
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('若刚更新过 Shizuku，请在 Shizuku 中停止服务后重新启动')
  })
})
