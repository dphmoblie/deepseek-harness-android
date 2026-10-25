import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

// 共享夹具里已经有整套桥接桩（含 setAppTheme 这类主题副作用）；这里只补本主题关心的默认值。
import { bridge, beforeEachAppTest } from '../__tests__/appTestHarness'
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))

import { AppearanceSettings } from './AppearanceSettings'
import { saveAccentSync } from '../adaptiveAccent'

const background = { uri: 'content://media/external/video/media/42', mime: 'video/mp4', kind: 'video' as const }

const checkbox = () => screen.getByRole<HTMLInputElement>('checkbox', { name: '连背景一起同步' })

beforeEach(() => {
  beforeEachAppTest()
  window.localStorage.clear()
  document.documentElement.removeAttribute('style')
  bridge.applyHarnessTheme.mockResolvedValue(undefined)
  bridge.clearHarnessTheme.mockResolvedValue(undefined)
  bridge.clearHarnessBackground.mockResolvedValue(undefined)
  bridge.pickHarnessBackground.mockResolvedValue(background)
  bridge.setHarnessSyncBackground.mockResolvedValue(undefined)
  bridge.harnessThemeState.mockResolvedValue({ syncBackground: false, syncedAt: 0, background: null })
  // 同步分区本身要能显示「上次同步」，否则重放那条路走不到。
  saveAccentSync({
    mode: 'dark', accent: '#315fcf', background: '#101820', surface: '#18222c', surfaceOpacity: 86, syncBackground: false,
  }, 1700000000000)
})

describe('外观设置：连背景一起同步的持久化', () => {
  it('重启后从原生读回开关与已选背景', async () => {
    bridge.harnessThemeState.mockResolvedValue({ syncBackground: true, syncedAt: 1700000000000, background })
    render(<AppearanceSettings />)

    // 回读是异步过桥：等它落到界面上，而不是先渲染一次未勾的空状态。
    await waitFor(() => expect(checkbox().checked).toBe(true))
    expect(screen.getByText('已选：video/mp4')).toBeTruthy()
    expect(screen.getByRole('button', { name: '重新选择背景文件' })).toBeTruthy()
  })

  it('开关为真但没有文件时显示未勾：不让界面停在画不出背景的状态', async () => {
    bridge.harnessThemeState.mockResolvedValue({ syncBackground: true, syncedAt: 0, background: null })
    render(<AppearanceSettings />)

    await waitFor(() => expect(bridge.harnessThemeState).toHaveBeenCalled())
    expect(checkbox().checked).toBe(false)
  })

  it('关掉开关会落盘为 false，并让已同步的页面立刻重放成不带背景', async () => {
    bridge.harnessThemeState.mockResolvedValue({ syncBackground: true, syncedAt: 1700000000000, background })
    render(<AppearanceSettings />)
    await waitFor(() => expect(checkbox().checked).toBe(true))

    fireEvent.click(checkbox())

    await waitFor(() => expect(bridge.setHarnessSyncBackground).toHaveBeenCalledWith(false))
    await waitFor(() => expect(bridge.applyHarnessTheme).toHaveBeenCalled())
    // 重放的载荷必须自己说明不带背景：界面上取消勾选、页面却还在画背景，是最难发现的那类不一致。
    expect(bridge.applyHarnessTheme.mock.lastCall?.[0]).toMatchObject({ syncBackground: false })
    expect(checkbox().checked).toBe(false)
  })

  it('勾上开关并同步时，主题载荷带上 syncBackground 与已有背景', async () => {
    bridge.harnessThemeState.mockResolvedValue({ syncBackground: true, syncedAt: 1700000000000, background })
    render(<AppearanceSettings />)
    await waitFor(() => expect(checkbox().checked).toBe(true))

    fireEvent.click(screen.getByRole('button', { name: '让 DSH 使用当前主题' }))

    await waitFor(() => expect(bridge.applyHarnessTheme).toHaveBeenCalled())
    expect(bridge.applyHarnessTheme.mock.lastCall?.[0]).toMatchObject({ syncBackground: true })
    // 背景地址不进载荷：原生自己记着，页面从 /__shell/background 取。
    expect(JSON.stringify(bridge.applyHarnessTheme.mock.lastCall?.[0])).not.toContain('content://')
    expect(screen.getByText('已让 DSH 使用当前主题与背景视频')).toBeTruthy()
  })

  it('遮背景的面板、左侧栏与右侧面板透明度分开调，并随载荷带给 DSH', async () => {
    render(<AppearanceSettings />)

    const overlay = screen.getByRole<HTMLInputElement>('slider', { name: /遮盖背景的界面不透明度/ })
    const sidebar = screen.getByRole<HTMLInputElement>('slider', { name: /侧边栏不透明度/ })
    const rightPanel = screen.getByRole<HTMLInputElement>('slider', { name: /右侧面板不透明度/ })
    // 初值取上次同步的载荷：beforeEach 里存的是 86；侧栏与右栏字段缺失分别按 100、40 迁移。
    expect(overlay.value).toBe('86')
    expect(sidebar.value).toBe('100')
    expect(rightPanel.value).toBe('40')

    fireEvent.change(overlay, { target: { value: '55' } })
    fireEvent.change(sidebar, { target: { value: '20' } })
    fireEvent.change(rightPanel, { target: { value: '72' } })
    fireEvent.click(screen.getByRole('button', { name: '让 DSH 使用当前主题' }))

    await waitFor(() => expect(bridge.applyHarnessTheme).toHaveBeenCalled())
    expect(bridge.applyHarnessTheme.mock.lastCall?.[0]).toMatchObject({
      surfaceOpacity: 55,
      sidebarOpacity: 20,
      rightPanelOpacity: 72,
    })
  })

  it('没有文件时勾选如实说明只同步颜色，不伪造成功', async () => {
    render(<AppearanceSettings />)
    await waitFor(() => expect(bridge.harnessThemeState).toHaveBeenCalled())

    fireEvent.click(checkbox())

    await waitFor(() => expect(bridge.setHarnessSyncBackground).toHaveBeenCalledWith(false))
    expect(screen.getByText('还没有选择背景文件，已只同步颜色')).toBeTruthy()
    expect(screen.getByText(/重开后仍按上次的选择显示|重启后仍按上次的选择显示/u)).toBeTruthy()
  })
})
