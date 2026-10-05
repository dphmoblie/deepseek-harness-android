import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 设置首页：分组入口 + 按功能词本地过滤。
 *
 * 为什么要有这一组：改动前入口是一张平列表，用户想找「保活」「日志」「导入」这类功能时
 * 只能逐行读标题 —— 而界面上根本没有这几个词。这里钉住三件事：
 *  1. 按用途细分模型、外观、设备、运行、插件、文件、版本和诊断；
 *  2. 过滤是本地的：只影响显示哪些入口，不重读设置、不改任何状态；
 *  3. 搜不到就如实说搜不到，不装作「本来就没有这个功能」。
 */
/**
 * 设置首页出现的等待上限。
 *
 * 与 `src/__tests__/appTestHarness.ts:346` 的 `SETTINGS_NAV_TIMEOUT_MS` 同一个理由：
 * `findBy*` 默认只等 1 s，而启动链把视图推到设置首页要将近 1 s（并行跑更慢），
 * 于是失败信息看起来像「首页没渲染」，实际只是等待上限太短。放宽的是等待，不是断言。
 */
const SETTINGS_HOME_TIMEOUT_MS = 5_000

describe('设置首页：分组与功能词过滤', () => {
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

  /** 搜索框：`type="search"` 的可及名就是占位文案。 */
  function searchBox(): HTMLElement {
    return screen.getByRole('searchbox', { name: '按功能词查找入口' })
  }

  it('按用途分组列出入口', async () => {
    await openSettingsHome()

    const runtimeGroup = screen.getByRole('region', { name: '运行与后台' })
    expect(within(screen.getByRole('region', { name: '插件扩展' })).getByRole('button', { name: /插件管理/ })).toBeVisible()
    expect(within(runtimeGroup).getByRole('button', { name: /Ubuntu 运行时/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: '设备与自动化' })).getByRole('button', { name: /终端与设备 Shell/ })).toBeVisible()
    // 服务行的说明跟着运行状态走，三种真实状态之一，不写死其中一种。
    expect(within(runtimeGroup).getByText('Harness 服务')).toBeVisible()
    expect(within(runtimeGroup).getByText(/正在本机运行|已停止，可随时启动|等待安装运行环境/)).toBeVisible()

    const settingsGroup = screen.getByRole('region', { name: '模型连接' })
    expect(within(settingsGroup).getByRole('button', { name: /模型与密钥/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: '外观与显示' })).getByRole('button', { name: /终端与外观/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: '设备与自动化' })).getByRole('button', { name: /Shizuku 与设备 Shell/ })).toBeVisible()
    expect(within(screen.getByRole('region', { name: '日志与排查' })).getByRole('button', { name: /诊断与日志/ })).toBeVisible()

    // 「运行与后台」既是分组名也是二级页入口，两种身份不能混成两行入口。
    expect(within(settingsGroup).queryByRole('button', { name: /运行与后台/ })).toBeNull()
    expect(within(settingsGroup).queryByRole('button', { name: /插件管理/ })).toBeNull()
    expect(screen.getByRole('region', { name: '运行与后台' })).toBeVisible()
  })

  it('按功能词过滤：界面上没有的词也能定位到入口', async () => {
    await openSettingsHome()

    // 「保活」只出现在关键词里（界面上写的是「后台保持」），这正是这次改造要解决的场景。
    fireEvent.change(searchBox(), { target: { value: '保活' } })

    expect(within(screen.getByRole('region', { name: '运行与后台' })).getByRole('button', { name: /运行与后台/ })).toBeVisible()
    expect(screen.queryByRole('button', { name: /模型与密钥/ })).toBeNull()
    // 命中的分组才留下：设置分组整组消失，不留一个空标题。
    expect(screen.queryByRole('region', { name: '模型连接' })).toBeNull()
    expect(screen.getByText('找到 1 个匹配的入口')).toBeVisible()
  })

  it('分类筛选后搜索仍覆盖所有分类，并能直接进入对应设置', async () => {
    await openSettingsHome()
    fireEvent.click(screen.getByRole('option', { name: /筛选分类： 外观与显示/ }))
    expect(screen.queryByRole('button', { name: /模型与密钥/ })).toBeNull()
    expect(screen.getByRole('button', { name: /终端与外观/ })).toBeVisible()
    fireEvent.change(searchBox(), { target: { value: '无障碍 白名单' } })
    fireEvent.click(screen.getByRole('button', { name: /Shizuku 与设备 Shell/ }))
    expect(await screen.findByRole('heading', { name: '无障碍应用自动化' })).toBeVisible()
  })

  it('背景视频和卡片透明度均可检索到外观入口', async () => {
    await openSettingsHome()
    for (const value of ['视频', '卡片 透明', 'mp4']) {
      fireEvent.change(searchBox(), { target: { value } })
      expect(screen.getByRole('button', { name: /终端与外观/ })).toBeVisible()
      expect(screen.getByText('找到 1 个匹配的入口')).toBeVisible()
    }
  })

  it('英文功能词同样命中，且大小写不敏感', async () => {
    await openSettingsHome()

    fireEvent.change(searchBox(), { target: { value: 'LOG' } })

    expect(screen.getByRole('button', { name: /诊断与日志/ })).toBeVisible()
    expect(screen.queryByRole('button', { name: /Ubuntu 运行时/ })).toBeNull()
    expect(screen.getByText('找到 1 个匹配的入口')).toBeVisible()
  })

  it('多个词一起输入时必须全部命中', async () => {
    await openSettingsHome()

    // 「终端」两个入口都沾边，「终端 外观」只剩一个：多词等于缩小范围，而不是并集。
    fireEvent.change(searchBox(), { target: { value: '终端 外观' } })
    expect(screen.getByRole('button', { name: /终端与外观/ })).toBeVisible()
    expect(screen.queryByRole('button', { name: /终端与设备 Shell/ })).toBeNull()

    fireEvent.change(searchBox(), { target: { value: '终端 不存在的外观词' } })
    expect(screen.queryByRole('button', { name: /终端与外观/ })).toBeNull()
    expect(screen.getByText(/没有匹配的入口/)).toBeVisible()
  })

  it('搜不到时如实说没有匹配，并且不显示任何入口', async () => {
    await openSettingsHome()

    fireEvent.change(searchBox(), { target: { value: '量子纠缠' } })

    expect(screen.getByText('没有匹配的入口：试试「导入」「日志」「保活」这类功能词')).toBeVisible()
    expect(screen.queryByRole('region', { name: '运行与后台' })).toBeNull()
    expect(screen.queryByRole('region', { name: '模型连接' })).toBeNull()
    expect(screen.queryByRole('button', { name: /模型与密钥/ })).toBeNull()
  })

  it('过滤只是显示层：不重读设置，也不写任何设置', async () => {
    await openSettingsHome()
    const readsBefore = bridge.getSettings.mock.calls.length

    fireEvent.change(searchBox(), { target: { value: '日志' } })
    fireEvent.change(searchBox(), { target: { value: '导入' } })
    fireEvent.change(searchBox(), { target: { value: '' } })

    expect(bridge.getSettings.mock.calls.length).toBe(readsBefore)
    expect(bridge.saveSettings).not.toHaveBeenCalled()
  })

  it('清空查找词后恢复全部入口', async () => {
    await openSettingsHome()

    fireEvent.change(searchBox(), { target: { value: '保活' } })
    expect(screen.queryByRole('region', { name: '模型连接' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '清空查找词' }))

    expect(screen.getByRole('region', { name: '模型连接' })).toBeVisible()
    expect(screen.getByRole('button', { name: /模型与密钥/ })).toBeVisible()
    // 结果条也一起收掉：没有过滤词时不该还挂着「找到几个」。
    expect(screen.queryByText(/找到 \d+ 个匹配的入口/)).toBeNull()
  })

  it('过滤后点入口仍然进得去对应页面', async () => {
    await openSettingsHome()

    fireEvent.change(searchBox(), { target: { value: '保活' } })
    fireEvent.click(screen.getByRole('button', { name: /运行与后台/ }))

    expect(await screen.findByRole('heading', { name: '运行与后台' })).toBeVisible()
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

  it('输入「回退」也能定位到版本管理入口', async () => {
    await openSettingsHome()

    // 「回退」「切回」都不是界面上的字（界面上写的是「切换到上一版本」），只能靠关键词命中。
    fireEvent.change(searchBox(), { target: { value: '回退' } })

    expect(screen.getByRole('button', { name: /版本管理/ })).toBeVisible()
    expect(screen.queryByRole('button', { name: /模型与密钥/ })).toBeNull()
    expect(screen.getByText('找到 1 个匹配的入口')).toBeVisible()
  })

  it('「文件管理」是一级页：投递区与共享目录的功能词都能定位到它', async () => {
    await openSettingsHome()

    const filesGroup = screen.getByRole('region', { name: '文件管理' })
    expect(within(filesGroup).getByRole('button', { name: /文件管理/ })).toBeVisible()

    // 「导入」「搬运」是搬运语义的词，改造后跟着入口一起从「运行与后台」搬了过来：
    // 只剩一个入口命中，说明旧入口的关键词确实删干净了（没留下两份）。
    fireEvent.change(searchBox(), { target: { value: '导入' } })
    expect(within(screen.getByRole('region', { name: '文件管理' })).getByRole('button', { name: /文件管理/ })).toBeVisible()
    expect(screen.queryByRole('button', { name: /运行与后台/ })).toBeNull()
    expect(screen.getByText('找到 1 个匹配的入口')).toBeVisible()

    fireEvent.change(searchBox(), { target: { value: '搬运' } })
    fireEvent.click(within(screen.getByRole('region', { name: '文件管理' })).getByRole('button', { name: /文件管理/ }))

    // 进得去，而且是一个浏览器：标题是这一页自己的，投递区与共享目录都在里面。
    expect(await screen.findByRole('heading', { level: 1, name: '文件管理' })).toBeVisible()
    expect(screen.getByRole('tablist')).toBeVisible()
    expect(screen.getByRole('region', { name: '共享目录' })).toBeVisible()
  })

  it('切到英文后整屏入口都是英文', async () => {
    // 入口表的标题与说明是裸字符串，仓库外的 locales-check.cjs 看不见它们，
    // 所以用这条用例兜底：漏了词条这里会直接显示中文，一眼能看出来。
    window.localStorage.setItem('dsh-mobile-language-v1', 'en')
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    const runtimeGroup = await screen.findByRole('region', { name: 'Runtime and background' }, { timeout: SETTINGS_HOME_TIMEOUT_MS })
    expect(within(screen.getByRole('region', { name: 'Plugin extensions' })).getByRole('button', { name: /Plugins/ })).toBeVisible()
    expect(within(runtimeGroup).getByRole('button', { name: /Ubuntu runtime/ })).toBeVisible()
    expect(within(runtimeGroup).getByText('Harness service')).toBeVisible()

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

    fireEvent.change(screen.getByRole('searchbox', { name: 'Search entries by keyword' }), { target: { value: 'log' } })
    expect(screen.getByText('Matching entries: 1')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Clear search' })).toBeVisible()
  })
})