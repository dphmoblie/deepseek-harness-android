import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { arriveAtSettingsHome } from './__tests__/appTestHarness'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, shizuku } from './__tests__/appTestHarness'

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
  await arriveAtSettingsHome()
      await screen.findByRole('heading', { name: '设置' }, { timeout: SETTINGS_HOME_TIMEOUT_MS })
  }

  it('按用途分成四大组列出入口', async () => {
    await openSettingsHome()

    const generalGroup = screen.getByRole('region', { name: '基础设置' })
    expect(within(generalGroup).getByRole('button', { name: /模型与密钥/ })).toBeVisible()
    expect(within(generalGroup).getByRole('button', { name: /外观与显示/ })).toBeVisible()

    const runtimeGroup = screen.getByRole('region', { name: '运行与数据' })
    expect(within(runtimeGroup).getByRole('button', { name: /运行与网络/ })).toBeVisible()
    expect(within(runtimeGroup).getByRole('button', { name: /Ubuntu 运行时/ })).toBeVisible()
    expect(within(runtimeGroup).getByRole('button', { name: /版本管理/ })).toBeVisible()
    expect(within(runtimeGroup).getByRole('button', { name: /文件管理/ })).toBeVisible()
    // Harness 服务的启停统一在首页启动卡片，设置首页不再单列服务行。
    expect(within(runtimeGroup).queryByText('Harness 服务')).toBeNull()

    const deviceGroup = screen.getByRole('region', { name: '设备与安全' })
    expect(within(deviceGroup).getByRole('button', { name: /设备控制/ })).toBeVisible()
    expect(within(deviceGroup).getByRole('button', { name: /安全与权限/ })).toBeVisible()
    expect(within(deviceGroup).getByRole('button', { name: /自动化/ })).toBeVisible()
    // 设备 Shell 不再单独占一行：入口并进了「设备控制 › Shizuku 与设备 Shell」。
    expect(within(deviceGroup).queryByText('设备 Shell')).toBeNull()

    const diagnosticsGroup = screen.getByRole('region', { name: '诊断与关于' })
    expect(within(diagnosticsGroup).getByRole('button', { name: /诊断与自检/ })).toBeVisible()
    expect(within(diagnosticsGroup).getByRole('button', { name: /关于与反馈/ })).toBeVisible()

    // 插件管理只在底部导航里，设置首页不再有它的入口与分组。
    const directory = runtimeGroup.closest('.settings-directory')
    expect(directory).not.toBeNull()
    expect(screen.queryByRole('region', { name: '插件扩展' })).toBeNull()
    expect(within(directory as HTMLElement).queryByRole('button', { name: /插件管理/ })).toBeNull()
  })

  it('首页提供常用设置快捷入口，搜索或分类筛选时收起', async () => {
    await openSettingsHome()

    const quick = screen.getByRole('region', { name: '常用设置' })
    expect(within(quick).getByRole('link', { name: /模型与密钥/ })).toBeVisible()
    expect(within(quick).getByRole('link', { name: /外观与显示/ })).toBeVisible()
    expect(within(quick).getByRole('link', { name: /运行与网络/ })).toBeVisible()
    expect(within(quick).getByRole('link', { name: /安全与权限/ })).toBeVisible()

    fireEvent.change(screen.getByRole('searchbox', { name: '搜索设置项或功能' }), { target: { value: '主题' } })
    fireEvent.click(screen.getByRole('button', { name: '按分类筛选' }))
    fireEvent.click(screen.getByRole('button', { name: '搜索入口' }))
    expect(screen.queryByRole('listbox', { name: '筛选功能分类' })).toBeNull()
    expect(screen.queryByRole('region', { name: '常用设置' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '清空查找词' }))
    fireEvent.click(screen.getByRole('button', { name: '按分类筛选' }))
    fireEvent.click(screen.getByRole('option', { name: /筛选分类： 设备与安全/ }))
    expect(screen.queryByRole('region', { name: '常用设置' })).toBeNull()
  })

  it.each([
    { badge: '未安装', state: { ...shizuku, installed: false, running: false } },
    { badge: '待授权', state: { ...shizuku } },
    { badge: '已授权', state: { ...shizuku, permission: 'granted' } },
    { badge: '已连接', state: { ...shizuku, permission: 'granted', connected: true } },
  ])('设备控制入口显示 Shizuku 状态：$badge', async ({ badge, state }) => {
    bridge.getShizukuState.mockResolvedValue(state)
    await openSettingsHome()

    const entry = screen.getByRole('button', { name: /设备控制/ })
    expect(within(entry).getByText(`Shizuku、设备 Shell 与 Root 通道 · ${badge}`)).toBeVisible()
  })

  it('分类筛选只留这一类，并且点进去到对应设置', async () => {
    await openSettingsHome()

    // 分类芯片默认收起，先展开；收起时容器 hidden（aria-controls 约定），读屏树中不存在，不会挤占首屏。
    expect(screen.queryByRole('listbox', { name: '筛选功能分类' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '按分类筛选' }))
    fireEvent.click(screen.getByRole('option', { name: /筛选分类： 设备与安全/ }))
    // 选中后自动收起，避免用户还要再点一次；筛选钮仍提示「列表正被筛选」。
    expect(screen.queryByRole('listbox', { name: '筛选功能分类' })).toBeNull()
    expect(screen.getByRole('button', { name: '按分类筛选' })).toHaveClass('is-filtered')
    expect(screen.queryByRole('button', { name: /模型与密钥/ })).toBeNull()
    expect(screen.queryByRole('region', { name: '基础设置' })).toBeNull()
    expect(within(screen.getByRole('region', { name: '设备与安全' })).getByRole('button', { name: /设备控制/ })).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: /自动化/ }))
    expect(await screen.findByRole('heading', { name: '自动化', level: 1 })).toBeVisible()
    // 三级页入口在这一页：无障碍与白名单、目标应用副屏、运行控制与规则
    for (const name of ['无障碍与白名单', '目标应用副屏', '运行控制与规则']) {
      expect(screen.getByRole('button', { name: new RegExp(name) })).toBeVisible()
    }
  })

  it('筛选只是显示层：不重读设置，也不写任何设置', async () => {
    await openSettingsHome()
    const readsBefore = bridge.getSettings.mock.calls.length

    fireEvent.click(screen.getByRole('button', { name: '按分类筛选' }))
    fireEvent.click(screen.getByRole('option', { name: /筛选分类： 诊断与关于/ }))
    fireEvent.click(screen.getByRole('button', { name: '按分类筛选' }))
    fireEvent.click(screen.getByRole('option', { name: /筛选分类：全部分类/ }))

    expect(bridge.getSettings.mock.calls.length).toBe(readsBefore)
    expect(bridge.saveSettings).not.toHaveBeenCalled()
  })

  it('「版本管理」入口在运行与数据组：进得去，返回键回设置首页', async () => {
    await openSettingsHome()

    const runtimeGroup = screen.getByRole('region', { name: '运行与数据' })
    fireEvent.click(within(runtimeGroup).getByRole('button', { name: /版本管理/ }))

    expect(await screen.findByRole('heading', { name: '版本管理' })).toBeVisible()
    expect(await screen.findByRole('heading', { name: '运行时版本管理' })).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()
  })

  it('「文件管理」入口在运行与数据组：投递区与共享目录在同一个浏览器里', async () => {
    await openSettingsHome()

    const runtimeGroup = screen.getByRole('region', { name: '运行与数据' })
    fireEvent.click(within(runtimeGroup).getByRole('button', { name: /文件管理/ }))

    expect(await screen.findByRole('heading', { level: 1, name: '文件管理' })).toBeVisible()
    expect(screen.getByRole('tablist')).toBeVisible()
    expect(screen.getByRole('region', { name: '外部文件与工作区' })).toBeVisible()
    expect(screen.getByRole('region', { name: '挂载目录' })).toBeVisible()
  })

  it('切到英文后整屏四个分组的入口都是英文，无中文残留', async () => {
    // 入口表的标题与说明是裸字符串，仓库外的 locales-check.cjs 看不见它们，
    // 所以用这条用例兜底：漏了词条这里会直接显示中文，一眼能看出来。
    window.localStorage.setItem('dsh-mobile-language-v1', 'en')
    render(<App />)
    await arriveAtSettingsHome()

    const generalGroup = await screen.findByRole('region', { name: 'General' }, { timeout: SETTINGS_HOME_TIMEOUT_MS })
    expect(within(generalGroup).getByRole('button', { name: /Models and keys/ })).toBeVisible()
    expect(within(generalGroup).getByRole('button', { name: /Appearance and display/ })).toBeVisible()

    const runtimeGroup = screen.getByRole('region', { name: 'Runtime & Data' })
    expect(within(runtimeGroup).getByRole('button', { name: /Runtime & Network/ })).toBeVisible()
    expect(within(runtimeGroup).getByRole('button', { name: /Ubuntu runtime/ })).toBeVisible()
    expect(within(runtimeGroup).getByRole('button', { name: /Version management/ })).toBeVisible()
    expect(within(runtimeGroup).getByRole('button', { name: /File management/ })).toBeVisible()
    expect(within(runtimeGroup).queryByText('Harness service')).toBeNull()

    const deviceGroup = screen.getByRole('region', { name: 'Device & Security' })
    expect(within(deviceGroup).getByRole('button', { name: /Device Control/ })).toBeVisible()
    expect(within(deviceGroup).getByRole('button', { name: /Security & Permissions/ })).toBeVisible()
    expect(within(deviceGroup).getByRole('button', { name: /Automation/ })).toBeVisible()

    const diagnosticsGroup = screen.getByRole('region', { name: 'Diagnostics & About' })
    expect(within(diagnosticsGroup).getByRole('button', { name: /Diagnostics & Self-Check/ })).toBeVisible()
    expect(within(diagnosticsGroup).getByRole('button', { name: /About & Feedback/ })).toBeVisible()

    // 插件管理只在底部导航里：设置首页既没有「插件扩展」分组，也没有入口。
    const directory = runtimeGroup.closest('.settings-directory') as HTMLElement
    expect(screen.queryByRole('region', { name: 'Plugin extensions' })).toBeNull()
    expect(within(directory).queryByRole('button', { name: /Plugins/ })).toBeNull()
    // 整个设置目录区不能有任何中文字符残留
    expect(directory.textContent ?? '').not.toMatch(/[\u4e00-\u9fff]/)
  })

  it('按功能词搜索过滤入口（含子页面直达），点搜索图标或回车才生效，支持清空及空结果提示', async () => {
    await openSettingsHome()

    const searchInput = screen.getByRole('searchbox', { name: '搜索设置项或功能' })
    expect(searchInput).toBeVisible()

    // 只输入不确认：列表不筛选——输入法组合中途不能被列表刷新打断。
    fireEvent.change(searchInput, { target: { value: '高危' } })
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByRole('region', { name: '基础设置' })).toBeVisible()

    // 点搜索图标确认：命中「安全与权限」及其三级子页「AI 高危操作控制」
    fireEvent.click(screen.getByRole('button', { name: '搜索入口' }))
    expect(screen.getByRole('status')).toHaveTextContent('找到 2 项匹配结果')
    expect(screen.getByRole('region', { name: '设备与安全' })).toBeVisible()
    const subResult = screen.getByRole('button', { name: /^AI 高危操作控制/ })
    expect(subResult).toBeVisible()
    expect(screen.queryByRole('region', { name: '基础设置' })).toBeNull()

    // 点击子页面搜索结果可直接跳到对应三级页
    fireEvent.click(subResult)
    expect(await screen.findByRole('heading', { level: 1, name: 'AI 高危操作控制' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '返回上一级' }))
    expect(await screen.findByRole('heading', { level: 1, name: '安全与权限' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { level: 1, name: '设置' })).toBeVisible()

    // 重新搜索并测试清空与空结果提示
    const searchInputAgain = screen.getByRole('searchbox', { name: '搜索设置项或功能' })
    fireEvent.change(searchInputAgain, { target: { value: '悬浮球' } })
    fireEvent.click(screen.getByRole('button', { name: '搜索入口' }))
    expect(screen.getByRole('status')).toHaveTextContent('找到 2 项匹配结果')

    // 点击清空按钮：草稿与已确认词一起清掉，恢复全部入口
    const clearButton = screen.getByRole('button', { name: '清空查找词' })
    fireEvent.click(clearButton)
    expect(searchInputAgain).toHaveValue('')
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByRole('region', { name: '基础设置' })).toBeVisible()

    // 回车确认无匹配词：显示空提示
    fireEvent.change(searchInputAgain, { target: { value: '不存在的设置项xyz' } })
    fireEvent.keyDown(searchInputAgain, { key: 'Enter' })
    expect(screen.getByRole('status')).toHaveTextContent('未找到匹配项')
    expect(screen.queryByRole('region', { name: '基础设置' })).toBeNull()
  })

  it('输入法组合中的回车只确认候选词，不提交搜索', async () => {
    await openSettingsHome()

    const searchInput = screen.getByRole('searchbox', { name: '搜索设置项或功能' })

    // 组合开始 → 输入半成品 → 回车。中文输入法里这个回车是「选中候选词」，
    // 不是「提交搜索」：吞掉它会让候选词无法确认，正好破坏本组件想保护的输入。
    fireEvent.compositionStart(searchInput)
    fireEvent.change(searchInput, { target: { value: '高' } })
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByRole('region', { name: '基础设置' })).toBeVisible()

    // 组合结束（候选词落定）后再回车，这才该提交。
    fireEvent.compositionEnd(searchInput, { target: { value: '高危' } })
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.getByRole('status')).toHaveTextContent('找到 2 项匹配结果')
  })

  it.each([false, true])('原生直接改 DOM 值后仍能搜索（先失焦：%s）', async blurBeforeSearch => {
    await openSettingsHome()

    const searchInput = screen.getByRole<HTMLInputElement>('searchbox', { name: '搜索设置项或功能' })
    // Capacitor 的 captureInput 兼容路径可能只写 DOM value，不派发 input/change，
    // 这正是 React 受控状态与用户眼前文字不同步的情况。
    if (blurBeforeSearch) fireEvent.compositionStart(searchInput)
    searchInput.value = '高危'
    expect(searchInput).toHaveValue('高危')
    if (blurBeforeSearch) fireEvent.blur(searchInput)

    fireEvent.click(screen.getByRole('button', { name: '搜索入口' }))
    expect(screen.getByRole('status')).toHaveTextContent('找到 2 项匹配结果')
    expect(searchInput).toHaveValue('高危')
  })

  /**
   * 回归：设置首页的「打开 Harness」必须只把会话标识递给桥。
   *
   * 曾经的写法是 `onClick={onLaunch}`：React 把合成事件当成 `sessionId` 传下去，
   * 事件对象里存着指回 Fiber 的循环引用，Capacitor 过桥序列化时抛
   * `TypeError: Converting circular structure to JSON`。调用就此断在半路，
   * `busy` 停在 `'launch'`，界面卡在「正在进入对话」直到看门狗超时——实体机上表现为
   * 「从 Harness 返回后再点回来一直转圈」。
   */
  it('点「打开 Harness」不把点击事件当会话标识递给桥', async () => {
    await openSettingsHome()

    // 启动链自己已经开过一次，先清空，才能看清这一次点击递了什么。
    bridge.openHarness.mockClear()

    const launchButton = screen.getByRole('button', { name: /打开 Harness/ })
    // 忙锁没释放时按钮禁用，点了也只会在 JS 里早退，测不到这条路径。
    await waitFor(() => expect(launchButton).toBeEnabled())

    fireEvent.click(launchButton)

    await arriveAtSettingsHome()
    expect(bridge.openHarness).toHaveBeenCalledWith(undefined)
    expect(bridge.openHarness.mock.calls[0]).toHaveLength(1)
    expect(bridge.openHarness.mock.calls[0][0]).not.toBeInstanceOf(Object)
  })

  it('组合中失焦也能恢复提交能力', async () => {
    await openSettingsHome()

    const searchInput = screen.getByRole('searchbox', { name: '搜索设置项或功能' })

    // 移动端软键盘选词或直接收起键盘时会丢 compositionend：不靠失焦兜底，
    // `isComposing` 会永久卡在 true，搜索框从此再也提交不了（静默失效）。
    fireEvent.compositionStart(searchInput)
    fireEvent.change(searchInput, { target: { value: '高危' } })
    fireEvent.blur(searchInput)
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.getByRole('status')).toHaveTextContent('找到 2 项匹配结果')
  })
})
