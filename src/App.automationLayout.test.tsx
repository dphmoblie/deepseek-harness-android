import { render, screen, within } from '@testing-library/react'
import { beforeEachAppTest, bridge, openSettingsPage } from './__tests__/appTestHarness'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 自动化设置的分组：副屏 / 参数与规则 / 无障碍。
 *
 * 这一页原来是一条从头到尾的子区块长龙（服务说明、总开关、任务、运行记录、规则、
 * 每应用参数、白名单、密码、副屏、免责），手机上一屏只看得到两个标题。分组只调顺序
 * 与层级，不改任何区块内部：所有行为断言仍在 `App.accessibility.test.tsx` 与
 * `VirtualScreenSettings.test.tsx` 里，这里只锁「谁在哪个组」。
 */
describe('自动化设置：三组结构', () => {
  beforeEach(beforeEachAppTest)

  afterEach(() => {
    vi.restoreAllMocks()
  })

  async function openAutomation(): Promise<void> {
    render(<App />)
    await openSettingsPage('自动化设置')
  }

  function group(id: string): HTMLElement {
    const element = document.getElementById(id)
    expect(element, `缺少分组容器 #${id}`).not.toBeNull()
    return element as HTMLElement
  }

  it('三个组标题按副屏、参数与规则、无障碍的顺序出现', async () => {
    await openAutomation()
    const names = (await screen.findAllByRole('heading', { level: 2 })).map(node => node.textContent ?? '')
    const groups = names.filter(name => ['副屏', '参数与规则', '无障碍'].includes(name))
    expect(groups).toEqual(['副屏', '参数与规则', '无障碍'])
  })

  it('页内跳转指向三个分组容器', async () => {
    await openAutomation()
    const nav = screen.getByRole('navigation', { name: '本页分组' })
    const links = within(nav).getAllByRole('link')
    expect(links.map(link => link.getAttribute('href'))).toEqual([
      '#automation-group-display',
      '#automation-group-parameters',
      '#automation-group-accessibility',
    ])
    for (const link of links) {
      const id = (link.getAttribute('href') ?? '').slice(1)
      expect(document.getElementById(id)).not.toBeNull()
    }
  })

  it('副屏组放取帧与显示相关设置', async () => {
    await openAutomation()
    const display = group('automation-group-display')
    // 夹具里副屏能力未授权，只渲染状态卡；其余档位卡要有启用状态才会出现，
    // 所以这里只锁「状态卡在副屏组里」，不假设能力可用。
    expect(within(display).getByRole('heading', { name: '副屏状态' })).toBeVisible()
    expect(within(group('automation-group-parameters')).queryByRole('heading', { name: '副屏状态' })).toBeNull()
  })

  it('参数与规则组放总开关、规则与每应用参数', async () => {
    await openAutomation()
    const parameters = group('automation-group-parameters')
    expect(within(parameters).getByRole('heading', { name: '自动化总开关' })).toBeVisible()
    expect(within(parameters).getByRole('heading', { name: '本次会话的运行记录' })).toBeVisible()
    expect(within(parameters).getByRole('heading', { name: '自动化规则' })).toBeVisible()
    expect(within(parameters).getByRole('heading', { name: '每应用参数' })).toBeVisible()
    expect(within(group('automation-group-display')).queryByRole('heading', { name: '自动化规则' })).toBeNull()
  })

  it('无障碍组放服务说明、白名单与验证密码', async () => {
    await openAutomation()
    const accessibility = group('automation-group-accessibility')
    expect(within(accessibility).getByRole('heading', { name: '无障碍应用自动化' })).toBeVisible()
    expect(within(accessibility).getByRole('switch', { name: '启用无障碍白名单限制' })).toBeVisible()
    expect(within(accessibility).getByRole('button', { name: '保存白名单' })).toBeVisible()
    expect(within(accessibility).getByRole('heading', { name: '白名单验证密码' })).toBeVisible()
    expect(within(group('automation-group-parameters')).queryByRole('heading', { name: '白名单验证密码' })).toBeNull()
  })
})
