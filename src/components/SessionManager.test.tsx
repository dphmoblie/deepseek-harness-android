import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RuntimeSessionListResult } from '../platform/types'

/**
 * 假桥：只实现这条用例需要的那个只读端点。
 *
 * `vi.mock` 会被提升到文件最顶部，工厂里直接引用模块级的 `const` 会在它的初始化之前取值
 * （实测报 `ReferenceError: Cannot access 'listSessions' before initialization`），
 * 所以桩本身也要用 `vi.hoisted` 一起提前建好。
 */
const { listSessions } = vi.hoisted(() => ({ listSessions: vi.fn<() => Promise<RuntimeSessionListResult>>() }))
vi.mock('../platform/native', () => ({ runtimeBridge: { listSessions } }))

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

    fireEvent.change(screen.getByRole('textbox', { name: '搜索会话' }), { target: { value: '不存在' } })
    expect(screen.getByText('没有匹配的会话')).toBeInTheDocument()

    fireEvent.change(screen.getByRole('textbox', { name: '搜索会话' }), { target: { value: '' } })
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
})
