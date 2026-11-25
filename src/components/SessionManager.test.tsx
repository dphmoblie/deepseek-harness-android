import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RuntimeSessionDeleteResult, RuntimeSessionListResult } from '../platform/types'

/**
 * 假桥：只实现这条用例需要的那个只读端点。
 *
 * `vi.mock` 会被提升到文件最顶部，工厂里直接引用模块级的 `const` 会在它的初始化之前取值
 * （实测报 `ReferenceError: Cannot access 'listSessions' before initialization`），
 * 所以桩本身也要用 `vi.hoisted` 一起提前建好。
 */
const { listSessions, deleteSession } = vi.hoisted(() => ({
  listSessions: vi.fn<() => Promise<RuntimeSessionListResult>>(),
  deleteSession: vi.fn<(sessionId: string) => Promise<RuntimeSessionDeleteResult>>(),
}))
vi.mock('../platform/native', () => ({ runtimeBridge: { listSessions, deleteSession } }))

import { SessionManager } from './SessionManager'

/** 会话正文哨兵：任何一层把它渲染出来或写进存储，都算越过了「只取元数据」的边界。 */
const BODY_SENTINEL = 'ZZ-CONVERSATION-BODY-SENTINEL-ZZ'

describe('会话管理页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
    window.sessionStorage.clear()
    window.localStorage.setItem('dsh-mobile-language-v1', 'zh-CN')
    // 默认「读得到，而且真的没有会话」：多数用例只关心别的行为，不该被一份假会话影响。
    listSessions.mockResolvedValue({ status: 'ready', sessions: [], truncated: false })
  })

  it('显示会话列表并支持搜索、删除与新建入口', async () => {
    const onBack = vi.fn()
    const onOpenHarness = vi.fn()
    render(<SessionManager onBack={onBack} onOpenHarness={onOpenHarness} />)

    expect(screen.getByRole('heading', { name: '会话管理' })).toBeInTheDocument()
    // 「暂无会话」只在**真的**读完且一条都没有时出现。
    expect(await screen.findByText('暂无会话')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '新建会话' }))
    expect(onOpenHarness).toHaveBeenCalledTimes(1)
    expect(screen.getByText('新会话 1')).toBeInTheDocument()

    const searchBox = screen.getByRole('searchbox', { name: '搜索会话' })
    fireEvent.change(searchBox, { target: { value: '不存在' } })
    expect(screen.getByText('没有匹配的会话')).toBeInTheDocument()

    // 测试点击一键清空按钮
    fireEvent.click(screen.getByRole('button', { name: '清空查找词' }))
    expect(searchBox).toHaveValue('')
    expect(screen.getByText('新会话 1')).toBeInTheDocument()

    // 测试输入法组字完成（compositionEnd）
    fireEvent.compositionEnd(searchBox, { currentTarget: { value: '新会话' }, target: { value: '新会话' } })
    expect(searchBox).toHaveValue('新会话')
    expect(screen.getByText('新会话 1')).toBeInTheDocument()

    fireEvent.change(searchBox, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: '删除会话 新会话 1' }))
    expect(screen.getByText('暂无会话')).toBeInTheDocument()
  })

  it('渲染真实会话的标题、最近更新时间与数量，点开仍然进 Harness', async () => {
    const onOpenHarness = vi.fn()
    const updatedAt = Date.UTC(2026, 0, 2, 3, 4, 5)
    listSessions.mockResolvedValue({
      status: 'ready',
      truncated: false,
      sessions: [
        { id: 'session-aaa', title: '真实会话标题', updatedAt },
        { id: 'session-bbb', title: '', updatedAt: updatedAt - 1_000 },
      ],
    })

    render(<SessionManager onBack={vi.fn()} onOpenHarness={onOpenHarness} />)

    expect(await screen.findByText('真实会话标题')).toBeInTheDocument()
    expect(screen.getByText('2 个会话')).toBeInTheDocument()
    // 最近更新时间：两条卡片各一处，至少一处带上了年份（格式沿用 toLocaleString）。
    expect(screen.getAllByText(/最近更新/)).toHaveLength(2)
    expect(screen.getAllByText(/最近更新/)[0].textContent).toMatch(/2026/)
    // 读不到标题的会话保留在列表里，但**不**拿 id 或时间去冒充标题。
    expect(screen.getByText('未命名会话')).toBeInTheDocument()
    expect(screen.queryByText('session-bbb')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /真实会话标题/ }))
    expect(onOpenHarness).toHaveBeenCalledTimes(1)
    expect(onOpenHarness).toHaveBeenCalledWith('session-aaa')
  })

  it('子代理折叠在父会话下面：默认不占会话入口，徽标展开后点开的是父会话', async () => {
    const onOpenHarness = vi.fn()
    const updatedAt = Date.UTC(2026, 0, 2, 3, 4, 5)
    listSessions.mockResolvedValue({
      status: 'ready',
      truncated: false,
      sessions: [
        { id: 'session-parent', title: '主会话标题', updatedAt, depth: 0 },
        { id: 'session-sub1', title: '', updatedAt: updatedAt - 1_000, depth: 1, parentId: 'session-parent', agentName: 'cordis' },
        { id: 'session-sub2', title: '子任务产物', updatedAt: updatedAt - 2_000, depth: 1, parentId: 'session-parent', agentName: 'standard' },
      ],
    })

    const { container } = render(<SessionManager onBack={vi.fn()} onOpenHarness={onOpenHarness} />)

    expect(await screen.findByText('主会话标题')).toBeInTheDocument()
    // 计数如实分开：子代理不是「会话」，但也不隐藏。
    expect(screen.getByText('1 个会话 · 2 个子代理')).toBeInTheDocument()
    // 默认折叠：子代理标题不出现在列表里，只有父行右侧的徽标。
    expect(screen.queryByText('子任务产物')).not.toBeInTheDocument()
    expect(container.querySelectorAll('[data-session-depth="0"]')).toHaveLength(1)
    const badge = screen.getByRole('button', { name: '展开 2 个子代理' })
    expect(badge).toHaveAttribute('aria-expanded', 'false')
    expect(badge).toHaveTextContent('2 个子代理')

    fireEvent.click(badge)
    expect(screen.getByRole('button', { name: '收起 2 个子代理' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('子任务产物')).toBeInTheDocument()
    // 没标题的子代理用预设名，不再显示成「未命名会话」。
    expect(screen.getByText('子代理 · cordis')).toBeInTheDocument()
    expect(screen.queryByText('未命名会话')).not.toBeInTheDocument()

    // 点子代理打开的是**父会话**：把 childId 当普通会话打开只会得到空白页。
    fireEvent.click(screen.getByRole('button', { name: '打开所属会话：子任务产物' }))
    expect(onOpenHarness).toHaveBeenCalledWith('session-parent')

    fireEvent.click(screen.getByRole('button', { name: '收起 2 个子代理' }))
    expect(screen.queryByText('子任务产物')).not.toBeInTheDocument()
  })

  it('搜索能用预设名命中子代理，并把父行一起带出来', async () => {
    listSessions.mockResolvedValue({
      status: 'ready',
      truncated: false,
      sessions: [
        { id: 'session-parent', title: '主会话标题', updatedAt: Date.UTC(2026, 0, 2), depth: 0 },
        { id: 'session-sub1', title: '后台子任务', updatedAt: Date.UTC(2026, 0, 1), depth: 1, parentId: 'session-parent', agentName: 'cordis' },
      ],
    })

    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)
    expect(await screen.findByText('主会话标题')).toBeInTheDocument()

    const searchBox = screen.getByRole('searchbox', { name: '搜索会话' })
    fireEvent.change(searchBox, { target: { value: 'cordis' } })
    // 只命中子代理：父行必须在结果里，否则搜到了却看不见。
    expect(screen.getByText('主会话标题')).toBeInTheDocument()
    expect(screen.getByText('后台子任务')).toBeInTheDocument()

    // 命中父标题时它的子代理一起带出来。
    fireEvent.change(searchBox, { target: { value: '主会话' } })
    expect(screen.getByText('后台子任务')).toBeInTheDocument()

    fireEvent.change(searchBox, { target: { value: '不存在' } })
    expect(screen.getByText('没有匹配的会话')).toBeInTheDocument()
  })

  it('筛选是「主会话 / 全部 / 已归档」：子代理不再单独成档，只在「全部」里随父行展开', async () => {
    listSessions.mockResolvedValue({
      status: 'ready',
      truncated: false,
      sessions: [
        { id: 'session-parent', title: '主会话标题', updatedAt: Date.UTC(2026, 0, 2), depth: 0 },
        { id: 'session-sub1', title: '子任务产物', updatedAt: Date.UTC(2026, 0, 1), depth: 1, parentId: 'session-parent', agentName: 'standard' },
      ],
    })

    const { container } = render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)
    expect(await screen.findByText('主会话标题')).toBeInTheDocument()
    expect(screen.queryByText('子任务产物')).not.toBeInTheDocument()

    // 三档就是主会话 / 全部 / 已归档——原来的「子代理」档位已经被归档取代。
    expect(screen.getAllByRole('tab').map(tab => tab.textContent)).toEqual(['主会话', '全部', '已归档'])
    expect(screen.queryByRole('tab', { name: '子代理' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: '全部' }))
    expect(screen.getByText('子任务产物')).toBeInTheDocument()
    // 子代理仍能看出它属于谁，且父行还在（不单独成列）。
    expect(screen.getByText(/属于「主会话标题」/)).toBeInTheDocument()
    expect(container.querySelectorAll('[data-session-depth="0"]')).toHaveLength(1)

    // 这一组会话都没归档：已归档档是空态，并且说清楚「归档后才会出现在这里」。
    fireEvent.click(screen.getByRole('tab', { name: '已归档' }))
    expect(await screen.findByText('没有已归档的会话')).toBeInTheDocument()
    expect(screen.queryByText('子任务产物')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: '主会话' }))
    expect(screen.getByText('主会话标题')).toBeInTheDocument()
  })

  it('父会话不在列表里的子代理进「未关联子代理」，连父都指不出来的按钮禁用', async () => {
    listSessions.mockResolvedValue({
      status: 'ready',
      truncated: false,
      sessions: [
        { id: 'session-parent', title: '主会话标题', updatedAt: Date.UTC(2026, 0, 3), depth: 0 },
        { id: 'session-orphan', title: '孤儿子代理', updatedAt: Date.UTC(2026, 0, 2), depth: 1, parentId: 'session-missing', agentName: 'standard' },
        { id: 'session-noparent', title: '', updatedAt: Date.UTC(2026, 0, 1), depth: 1 },
      ],
    })

    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)
    expect(await screen.findByText('未关联子代理')).toBeInTheDocument()
    // 不静默隐藏：父会话被 50 条上限截断时，子代理仍然看得见、点得开。
    expect(screen.getByText('孤儿子代理')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '打开所属会话：孤儿子代理' })).toBeEnabled()
    // 连父标识都没有：不给一个点了只会空白页的入口。
    expect(screen.getByRole('button', { name: '未关联子代理：子代理' })).toBeDisabled()
  })

  it('真实会话不提供删除按钮：只读通道不承诺删得掉', async () => {
    listSessions.mockResolvedValue({
      status: 'ready',
      truncated: false,
      sessions: [{ id: 'session-aaa', title: '真实会话标题', updatedAt: Date.UTC(2026, 0, 2) }],
    })

    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)

    expect(await screen.findByText('真实会话标题')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /删除会话/ })).not.toBeInTheDocument()
  })

  it('读不到会话列表时显示诚实提示，而不是「暂无会话」', async () => {
    listSessions.mockResolvedValue({ status: 'unavailable', reason: 'RUNTIME_NOT_INSTALLED' })
    const { unmount } = render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)

    expect(await screen.findByText('读不到会话列表')).toBeInTheDocument()
    expect(screen.getByText('运行环境还没安装，读不到会话列表；这不代表没有会话。')).toBeInTheDocument()
    expect(screen.queryByText('暂无会话')).not.toBeInTheDocument()
    unmount()

    // 桥本身失败（或载荷没通过校验）时同样落到「读不到」，而不是静默成空列表。
    listSessions.mockRejectedValue(new Error('桥接调用失败'))
    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)
    expect(await screen.findByText('读不到会话列表')).toBeInTheDocument()
    expect(screen.getByText('暂时读不到会话列表；这不代表没有会话。')).toBeInTheDocument()
    expect(screen.queryByText('暂无会话')).not.toBeInTheDocument()
  })

  it('首次读取失败后可以重试成功，并且重试期间不能重复请求', async () => {
    let settleRetry: (value: RuntimeSessionListResult) => void = () => {}
    listSessions
      .mockResolvedValueOnce({ status: 'unavailable', reason: 'SESSION_CATALOG_TIMEOUT' })
      .mockReturnValueOnce(new Promise<RuntimeSessionListResult>(resolve => { settleRetry = resolve }))

    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)

    expect(await screen.findByText('读取会话列表超时；这不代表没有会话。')).toBeInTheDocument()
    const retry = screen.getByRole('button', { name: '重试' })
    fireEvent.click(retry)
    expect(retry).toBeDisabled()
    fireEvent.click(retry)
    await waitFor(() => expect(listSessions).toHaveBeenCalledTimes(2))

    settleRetry({
      status: 'ready',
      truncated: false,
      sessions: [{ id: 'session-retry', title: '重试后的会话', updatedAt: Date.UTC(2026, 0, 2) }],
    })
    expect(await screen.findByText('重试后的会话')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument()
  })

  it('外部 disabled 时不允许重试', async () => {
    listSessions.mockResolvedValue({ status: 'unavailable', reason: 'SESSION_CATALOG_FAILED' })
    render(<SessionManager disabled onBack={vi.fn()} onOpenHarness={vi.fn()} />)

    const retry = await screen.findByRole('button', { name: '重试' })
    expect(retry).toBeDisabled()
    fireEvent.click(retry)
    expect(listSessions).toHaveBeenCalledTimes(1)
  })

  it('读取中显示加载态：还没读完就显示「暂无会话」等于替用户下结论', async () => {
    let settle: (value: RuntimeSessionListResult) => void = () => {}
    listSessions.mockReturnValue(new Promise<RuntimeSessionListResult>(resolve => { settle = resolve }))

    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)

    expect(screen.getByText('正在读取会话…')).toBeInTheDocument()
    expect(screen.queryByText('暂无会话')).not.toBeInTheDocument()

    settle({ status: 'ready', sessions: [], truncated: false })
    expect(await screen.findByText('暂无会话')).toBeInTheDocument()
  })

  it('没读全时如实说明，而不是假装这就是全部', async () => {
    listSessions.mockResolvedValue({
      status: 'ready',
      truncated: true,
      sessions: [{ id: 'session-aaa', title: '真实会话标题', updatedAt: Date.UTC(2026, 0, 2) }],
    })

    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)

    expect(await screen.findByText('真实会话标题')).toBeInTheDocument()
    expect(screen.getByText('会话较多，这里只列出最近更新的一部分。')).toBeInTheDocument()
  })

  it.each([false, true])('列表未读全且当前没有结果时可以重试（有未归档会话：%s）', async hasLiveSession => {
    const updatedAt = Date.UTC(2026, 0, 2)
    listSessions
      .mockResolvedValueOnce({
        status: 'ready',
        truncated: true,
        sessions: hasLiveSession ? [{ id: 'live', title: '在用的会话', updatedAt }] : [],
      })
      .mockResolvedValueOnce({
        status: 'ready',
        truncated: false,
        sessions: [{ id: 'archived', title: '重试读到的归档会话', updatedAt, archived: true }],
      })

    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)
    if (hasLiveSession) await screen.findByText('在用的会话')
    else await screen.findByText('会话列表读取不完整')
    fireEvent.click(screen.getByRole('tab', { name: '已归档' }))

    expect(screen.getByText('会话列表读取不完整')).toBeInTheDocument()
    expect(screen.queryByText('暂无会话')).not.toBeInTheDocument()
    expect(screen.queryByText('没有已归档的会话')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('重试读到的归档会话')).toBeInTheDocument()
  })

  it('只渲染元数据：正文片段不进 DOM，也不写任何浏览器存储', async () => {
    const localSet = vi.spyOn(window.localStorage, 'setItem')
    const sessionSet = vi.spyOn(window.sessionStorage, 'setItem')
    // 故意多塞几个字段：界面只认 id/标题/时间，别的字段即便混进载荷也到不了 DOM。
    listSessions.mockResolvedValue({
      status: 'ready',
      truncated: false,
      sessions: [{
        id: 'session-aaa',
        title: '真实会话标题',
        updatedAt: Date.UTC(2026, 0, 2),
        body: BODY_SENTINEL,
        snippet: BODY_SENTINEL,
        cwd: '/root/secret-project',
      }],
    } as unknown as RuntimeSessionListResult)

    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)
    expect(await screen.findByText('真实会话标题')).toBeInTheDocument()

    expect(document.body.textContent).not.toContain(BODY_SENTINEL)
    expect(document.body.textContent).not.toContain('secret-project')
    // 存储里既没有正文片段，也没有会话标识；挂载期间一次都没写过。
    expect(localSet).not.toHaveBeenCalled()
    expect(sessionSet).not.toHaveBeenCalled()
    expect(JSON.stringify(Object.keys(window.localStorage))).not.toContain('session-aaa')
    expect(Object.values(window.localStorage)).not.toContain('真实会话标题')
    expect(Object.keys(window.sessionStorage)).toHaveLength(0)
  })

  it('空态用满幅徽标而不是 24px 线性图标，且不额外占用无障碍名称', async () => {
    const { container } = render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)
    const mark = container.querySelector('.session-empty .session-empty-mark')
    // 徽标必须落在空态框内，并且真的是仓库那张 app-mark（由 Vite URL 导入解析出路径）。
    expect(mark).toBeInstanceOf(HTMLImageElement)
    expect(mark?.getAttribute('src')).toContain('app-mark')
    // 纯装饰图：alt 为空，不参与无障碍树，语义仍由「暂无会话」文案承担。
    expect(mark?.getAttribute('alt')).toBe('')
    expect(await screen.findByText('暂无会话')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('首页内嵌列表有真实的新建入口，忙碌时不能重复启动', async () => {
    const onOpenHarness = vi.fn()
    const { rerender } = render(<SessionManager embedded disabled onBack={vi.fn()} onOpenHarness={onOpenHarness} />)
    expect(screen.getByRole('button', { name: '新建会话' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '新建会话' }))
    expect(onOpenHarness).not.toHaveBeenCalled()
    rerender(<SessionManager embedded onBack={vi.fn()} onOpenHarness={onOpenHarness} />)
    fireEvent.click(screen.getByRole('button', { name: '新建会话' }))
    expect(onOpenHarness).toHaveBeenCalledTimes(1)
    // 新建会先落一条**本地占位**（真实会话由运行时创建），空态因此让位给这条占位。
    expect(await screen.findByText('新会话 1')).toBeInTheDocument()
    expect(screen.queryByText('暂无会话')).not.toBeInTheDocument()
  })

  /**
   * 归档标记：`archived` 来自 dsh 自己的 workspace 注册表（原生侧只读转发）。
   *
   * 两件事同时成立：主会话档只列未归档的会话；「已归档」这一档才列归档会话，并就地提供删除。
   * 子代理始终折叠在父行下面，不单独成档。
   */
  it('已归档是独立一档：主会话档不列归档会话，归档档里可删除', async () => {
    const updatedAt = Date.UTC(2026, 0, 2, 3, 4, 5)
    listSessions.mockResolvedValue({
      status: 'ready',
      truncated: false,
      sessions: [
        { id: 'session-live', title: '正在用的会话', updatedAt },
        { id: 'session-old', title: '早就结束的会话', updatedAt: updatedAt - 86_400_000, archived: true },
        { id: 'session-child', title: '子代理会话', updatedAt, depth: 1, parentId: 'session-old', agentName: 'cordis' },
      ],
    })
    deleteSession.mockResolvedValue({ deleted: true })

    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)

    // 主会话档：只列未归档的会话；归档的那条不在这一档里，而是去了单独的「已归档」。
    expect(await screen.findByText('正在用的会话')).toBeInTheDocument()
    expect(screen.queryByText('早就结束的会话')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '删除会话 正在用的会话' })).not.toBeInTheDocument()

    // 全部档：两条父行都在，归档行带标记（混合列表里才需要标记）。
    fireEvent.click(screen.getByRole('tab', { name: '全部' }))
    expect(await screen.findByText('早就结束的会话')).toBeInTheDocument()
    expect(screen.getAllByText('已归档', { selector: '.session-archived-pill' })).toHaveLength(2) // 父行 + 跟随它的子代理
    expect(screen.getByText('子代理会话')).toBeInTheDocument()

    // 已归档档：只列归档会话；整档都是归档的，所以不再逐行挂标记。
    fireEvent.click(screen.getByRole('tab', { name: '已归档' }))
    expect(await screen.findByText('早就结束的会话')).toBeInTheDocument()
    expect(screen.queryByText('正在用的会话')).not.toBeInTheDocument()
    expect(screen.queryByText('已归档', { selector: '.session-archived-pill' })).not.toBeInTheDocument()
    // 子代理折叠在父行下面：徽标展开后才出现，且仍显示「属于谁」。
    const badge = screen.getByRole('button', { name: /展开 1 个子代理/ })
    fireEvent.click(badge)
    expect(await screen.findByText('子代理会话')).toBeInTheDocument()
    expect(screen.getByText(/属于「早就结束的会话」/)).toBeInTheDocument()

    // 删除就在这一档里：确认条 → 桥调用 → 行消失，未归档的会话不动。
    fireEvent.click(screen.getByRole('button', { name: '删除会话 早就结束的会话' }))
    expect(screen.getByText('删除后无法恢复：会话日志与投影缓存都会被删掉。')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '删除' }))

    await waitFor(() => expect(deleteSession).toHaveBeenCalledWith('session-old'))
    await waitFor(() => expect(screen.queryByText('早就结束的会话')).not.toBeInTheDocument())
    // 归档档删空后回到空态；另一条会话仍在主会话档里。
    expect(await screen.findByText('没有已归档的会话')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: '主会话' }))
    expect(screen.getByText('正在用的会话')).toBeInTheDocument()
  })

  it('删除被拒绝时如实说明原因，不把行从列表里抹掉', async () => {
    const updatedAt = Date.UTC(2026, 0, 2, 3, 4, 5)
    listSessions.mockResolvedValue({
      status: 'ready',
      truncated: false,
      sessions: [{ id: 'session-old', title: '早就结束的会话', updatedAt, archived: true }],
    })
    deleteSession.mockResolvedValue({ deleted: false, reason: 'RUNTIME_RUNNING' })

    render(<SessionManager onBack={vi.fn()} onOpenHarness={vi.fn()} />)
    fireEvent.click(await screen.findByRole('tab', { name: '已归档' }))
    fireEvent.click(await screen.findByRole('button', { name: '删除会话 早就结束的会话' }))
    fireEvent.click(screen.getByRole('button', { name: '删除' }))

    expect(await screen.findByText('没有删除这条会话')).toBeInTheDocument()
    expect(screen.getByText('运行环境还在运行，先停止后再删除会话。')).toBeInTheDocument()
    expect(screen.getByText('早就结束的会话')).toBeInTheDocument()
  })
})
