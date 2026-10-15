import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 设置首页：按用途分组的入口，以及分类芯片的筛选。
 *
 * 为什么要有这一组：入口按用途细分模型、外观、设备、运行、插件、文件、版本和诊断，
 * 芯片只决定这一屏显示哪些入口（不重读设置、不改任何状态），点下去走的还是同一条路。
 */
/**
 * 设置首页出现的等待上限。
 *
 * 与 `src/__tests__/appTestHarness.ts:626` 的 `SETTINGS_NAV_TIMEOUT_MS` 同一个理由：
 * `findBy*` 默认只等 1 s，而启动链把视图推到设置首页要将近 1 s（并行跑更慢），
 * 于是失败信息看起来像「首页没渲染」，实际只是等待上限太短。放宽的是等待，不是断言。
 */
const SETTINGS_HOME_TIMEOUT_MS = 5_000

describe('设置首页：分组与分类筛选', () => {
  beforeEach(beforeEachAppTest)

  afterEach(() => {
    vi.restoreAllMocks()
  })

  /**
   * 打开设置首页：**不需要点任何入口按钮**。
   *
   * 实测（见 `src/__tests__/renderProfile.test.tsx:170`、`renderCounts.test.tsx:279` 的注释）：
   * 夹具里开着自动启动，启动链跑完会把视图推到设置首页；而启动链没跑完时同步查询必然失败。
   * 所以这里只做两件事：等自动启动发生，再有界等待首页标题出现。
   * 刻意不去点「打开应用设置」——那个按钮不是这条路径上的必经入口（点它反而找不到，见本轮踩坑）。
   */
  async function openSettingsHome(): Promise<void> {
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))
    await screen.findByRole('heading', { name: '设置' }, { timeout: SETTINGS_HOME_TIMEOUT_MS })
  }

  it('按用途分组列出入口', async () => {
    await openSettingsHome()

    const runtimeGroup = screen.getByRole('region', { name: '后台' })
    expect(within(runtimeGroup).getByRole('button', { name: /Ubuntu 运行时/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: '设备与自动化' })).getByText('设备 Shell')).toBeVisible()
    // 服务行的说明跟着运行状态走，三种真实状态之一，不写死其中一种。
    expect(within(runtimeGroup).getByText('Harness 服务')).toBeVisible()
    expect(within(runtimeGroup).getByText(/正在本机运行|已停止，可随时启动|等待安装运行环境/)).toBeVisible()

    const settingsGroup = screen.getByRole('region', { name: '模型连接' })
    expect(within(settingsGroup).getByRole('button', { name: /模型与密钥/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: '外观与显示' })).getByRole('button', { name: /主题/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: '设备与自动化' })).getByRole('button', { name: /设备 Shell 设置/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: '设备与自动化' })).getByRole('button', { name: /自动化设置/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: '日志与排查' })).getByRole('button', { name: /诊断与日志/ })).toBeVisible()

    // 「后台」既是分组名也是二级页入口，两种身份不能混成两行入口。
    expect(within(settingsGroup).queryByRole('button', { name: /后台/ })).toBeNull()
    // 插件管理只在底部导航里，设置首页不再有它的入口与分组。
    const directory = screen.getByRole('region', { name: '后台' }).closest('.settings-directory')
    expect(directory).not.toBeNull()
    expect(screen.queryByRole('region', { name: '插件扩展' })).toBeNull()
    expect(within(directory as HTMLElement).queryByRole('button', { name: /插件管理/ })).toBeNull()
    expect(screen.getByRole('region', { name: '后台' })).toBeVisible()
  })

  it('分类筛选只留这一类，并且点进去到对应设置', async () => {
    await openSettingsHome()

    // 分类芯片默认收起，先展开；展开前它们不在页面上，不会挤占首屏。
    expect(screen.queryByRole('listbox', { name: '筛选功能分类' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '按分类筛选' }))
    fireEvent.click(screen.getByRole('option', { name: /筛选分类： 设备与自动化/ }))
    // 收起后筛选钮仍提示「列表正被筛选」。
    fireEvent.click(screen.getByRole('button', { name: '按分类筛选' }))
    expect(screen.getByRole('button', { name: '按分类筛选' })).toHaveClass('is-filtered')
    expect(screen.queryByRole('button', { name: /模型与密钥/ })).toBeNull()
    expect(screen.queryByRole('region', { name: '模型连接' })).toBeNull()
    expect(within(screen.getByRole('region', { name: '设备与自动化' })).getByText('设备 Shell')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: /自动化设置/ }))
    expect(await screen.findByRole('heading', { name: '无障碍应用自动化' })).toBeVisible()
  })

  it('筛选只是显示层：不重读设置，也不写任何设置', async () => {
    await openSettingsHome()
    const readsBefore = bridge.getSettings.mock.calls.length

    fireEvent.click(screen.getByRole('button', { name: '按分类筛选' }))
    fireEvent.click(screen.getByRole('option', { name: /筛选分类： 日志与排查/ }))
    fireEvent.click(screen.getByRole('option', { name: /筛选分类：全部分类/ }))

    expect(bridge.getSettings.mock.calls.length).toBe(readsBefore)
    expect(bridge.saveSettings).not.toHaveBeenCalled()
  })

  it('「版本管理」是一级页：入口进得去，返回键回设置首页', async () => {
    await openSettingsHome()

    const versionsGroup = screen.getByRole('region', { name: '版本管理' })
    fireEvent.click(within(versionsGroup).getByRole('button', { name: /版本管理/ }))

    // 版本面板已经不在「运行与后台」页里：这一屏自己是标题，页内有版本列表与两个操作。
    expect(await screen.findByRole('heading', { name: '版本管理' })).toBeVisible()
    expect(await screen.findByRole('heading', { name: '运行时版本管理' })).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()
  })

  it('「文件管理」是一级页：投递区与共享目录在同一个浏览器里', async () => {
    await openSettingsHome()

    const filesGroup = screen.getByRole('region', { name: '文件管理' })
    fireEvent.click(within(filesGroup).getByRole('button', { name: /文件管理/ }))

    // 进得去，而且是一个浏览器：标题是这一页自己的，投递区与共享目录都在里面。
    expect(await screen.findByRole('heading', { level: 1, name: '文件管理' })).toBeVisible()
    expect(screen.getByRole('tablist')).toBeVisible()
    expect(screen.getByRole('region', { name: '目录' })).toBeVisible()
  })

  it('切到英文后整屏入口都是英文', async () => {
    // 入口表的标题与说明是裸字符串，仓库外的 locales-check.cjs 看不见它们，
    // 所以用这条用例兜底：漏了词条这里会直接显示中文，一眼能看出来。
    window.localStorage.setItem('dsh-mobile-language-v1', 'en')
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    const runtimeGroup = await screen.findByRole('region', { name: 'Background' }, { timeout: SETTINGS_HOME_TIMEOUT_MS })
    expect(within(runtimeGroup).getByRole('button', { name: /Ubuntu runtime/ })).toBeVisible()
    expect(within(runtimeGroup).getByText('Harness service')).toBeVisible()
    // 插件管理只在底部导航里：设置首页既没有「插件扩展」分组，也没有入口。
    const directory = runtimeGroup.closest('.settings-directory') as HTMLElement
    expect(screen.queryByRole('region', { name: 'Plugin extensions' })).toBeNull()
    expect(within(directory).queryByRole('button', { name: /Plugins/ })).toBeNull()
    expect(within(screen.getByRole('region', { name: 'Appearance and display' })).getByRole('button', { name: /^Theme/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: 'Device and automation' })).getByText('Device Shell')).toBeVisible()
    expect(within(screen.getByRole('region', { name: 'Device and automation' })).getByText('Device Shell settings')).toBeVisible()
    expect(within(screen.getByRole('region', { name: 'Device and automation' })).getByText('Automation settings')).toBeVisible()

    const settingsGroup = screen.getByRole('region', { name: 'Model connections' })
    expect(within(settingsGroup).getByRole('button', { name: /Models and keys/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: 'Logs and troubleshooting' })).getByRole('button', { name: /Diagnostics and logs/ })).toBeVisible()

    // 「版本管理」是独立分组：连标题带说明一起查有没有中文残留（漏词条时 t() 会回落成中文键本身）。
    const versionsGroup = screen.getByRole('region', { name: 'Version management' })
    const versionsEntry = within(versionsGroup).getByRole('button', { name: /Version management/ })
    expect(versionsEntry).toBeVisible()
    expect(versionsEntry.textContent ?? '').not.toMatch(/[\u4e00-\u9fff]/)

    // 「文件管理」同样是独立分组，也一样查中文残留。
    const filesGroup = screen.getByRole('region', { name: 'File management' })
    const filesEntry = within(filesGroup).getByRole('button', { name: /File management/ })
    expect(filesEntry).toBeVisible()
    expect(filesEntry.textContent ?? '').not.toMatch(/[\u4e00-\u9fff]/)
  })

  it('按功能词搜索过滤入口，点搜索图标或回车才生效，支持清空及空结果提示', async () => {
    await openSettingsHome()

    const searchInput = screen.getByRole('searchbox', { name: '按功能词查找入口' })
    expect(searchInput).toBeVisible()

    // 只输入不确认：列表不筛选——输入法组合中途不能被列表刷新打断。
    fireEvent.change(searchInput, { target: { value: '主题' } })
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByRole('region', { name: '模型连接' })).toBeVisible()

    // 点搜索图标确认：应只保留外观与显示分组，隐藏其他分组如模型连接
    fireEvent.click(screen.getByRole('button', { name: '搜索入口' }))
    expect(screen.getByRole('status')).toHaveTextContent('找到 1 个匹配的入口')
    expect(screen.getByRole('region', { name: '外观与显示' })).toBeVisible()
    expect(screen.getByRole('button', { name: /主题/ })).toBeVisible()
    expect(screen.queryByRole('region', { name: '模型连接' })).toBeNull()

    // 点击清空按钮：草稿与已确认词一起清掉，恢复全部入口
    const clearButton = screen.getByRole('button', { name: '清空查找词' })
    fireEvent.click(clearButton)
    expect(searchInput).toHaveValue('')
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByRole('region', { name: '模型连接' })).toBeVisible()

    // 回车确认无匹配词：显示空提示
    fireEvent.change(searchInput, { target: { value: '不存在的设置项xyz' } })
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.getByRole('status')).toHaveTextContent('没有匹配的入口')
    expect(screen.queryByRole('region', { name: '外观与显示' })).toBeNull()
  })

  it('输入法组合中的回车只确认候选词，不提交搜索', async () => {
    await openSettingsHome()

    const searchInput = screen.getByRole('searchbox', { name: '按功能词查找入口' })

    // 组合开始 → 输入半成品 → 回车。中文输入法里这个回车是「选中候选词」，
    // 不是「提交搜索」：吞掉它会让候选词无法确认，正好破坏本组件想保护的输入。
    fireEvent.compositionStart(searchInput)
    fireEvent.change(searchInput, { target: { value: '主' } })
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByRole('region', { name: '模型连接' })).toBeVisible()

    // 组合结束（候选词落定）后再回车，这才该提交。
    fireEvent.compositionEnd(searchInput, { target: { value: '主题' } })
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.getByRole('status')).toHaveTextContent('找到 1 个匹配的入口')
  })

  it('组合中失焦也能恢复提交能力', async () => {
    await openSettingsHome()

    const searchInput = screen.getByRole('searchbox', { name: '按功能词查找入口' })

    // 移动端软键盘选词或直接收起键盘时会丢 compositionend：不靠失焦兜底，
    // `isComposing` 会永久卡在 true，搜索框从此再也提交不了（静默失效）。
    fireEvent.compositionStart(searchInput)
    fireEvent.change(searchInput, { target: { value: '主题' } })
    fireEvent.blur(searchInput)
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.getByRole('status')).toHaveTextContent('找到 1 个匹配的入口')
  })
})
