import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEachAppTest, bridge, openSettingsPage } from './__tests__/appTestHarness'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 自动化设置的结构：无障碍 / 副屏 / 自动参数拆成三个三级页。
 *
 * 原来这一页是一条从头到尾的子区块长龙（服务说明、总开关、任务、运行记录、规则、
 * 每应用参数、白名单、密码、副屏、免责），手机上一屏只看得到两个标题。拆分只改入口与
 * 页面边界，不改任何区块内部：所有行为断言仍在 `App.accessibility.test.tsx` 与
 * `VirtualScreenSettings.test.tsx` 里，这里只锁「谁在哪个页面」。
 */
describe('自动化设置：三条腿分成三个页面', () => {
  beforeEach(beforeEachAppTest)

  afterEach(() => {
    vi.restoreAllMocks()
  })

  async function openAutomation(sub?: string): Promise<void> {
    render(<App />)
    await openSettingsPage('自动化设置', sub)
  }

  it('自动化设置页只给出三个入口，不在这里展开控件', async () => {
    await openAutomation()
    expect(screen.getByRole('button', { name: /无障碍/ })).toBeVisible()
    expect(screen.getByRole('button', { name: /副屏/ })).toBeVisible()
    expect(screen.getByRole('button', { name: /自动参数/ })).toBeVisible()
    // 入口页不直接摆控件：总开关、白名单这些都在各自的三级页里。
    expect(screen.queryByRole('heading', { name: '自动化总开关' })).toBeNull()
    expect(screen.queryByRole('heading', { name: '副屏状态' })).toBeNull()
    expect(screen.queryByRole('button', { name: '保存白名单' })).toBeNull()
  })

  it('自动参数页放总开关、运行记录、规则与每应用参数', async () => {
    await openAutomation('自动参数')
    expect(await screen.findByRole('heading', { name: '自动化总开关' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '本次会话的运行记录' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '自动化规则' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '每应用参数' })).toBeVisible()
    // 白名单与密码属于无障碍页，不能混进来。
    expect(screen.queryByRole('heading', { name: '白名单验证密码' })).toBeNull()
  })

  it('无障碍页放服务说明、白名单与验证密码', async () => {
    await openAutomation('无障碍')
    expect(await screen.findByRole('heading', { name: '无障碍应用自动化' })).toBeVisible()
    expect(screen.getByRole('switch', { name: '启用无障碍白名单限制' })).toBeVisible()
    expect(screen.getByRole('button', { name: '保存白名单' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '白名单验证密码' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '自动化总开关' })).toBeNull()
  })

  it('副屏页放副屏状态', async () => {
    await openAutomation('副屏')
    expect(await screen.findByRole('heading', { name: '副屏状态' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '自动化总开关' })).toBeNull()
  })

  it('三级页的返回键回到自动化设置', async () => {
    await openAutomation('无障碍')
    await screen.findByRole('heading', { name: '无障碍应用自动化' })
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { name: '自动化设置', level: 1 })).toBeVisible()
    expect(screen.queryByRole('heading', { name: '无障碍应用自动化' })).toBeNull()
  })
})
