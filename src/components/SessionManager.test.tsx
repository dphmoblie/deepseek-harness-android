import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionManager } from './SessionManager'

describe('会话管理页', () => {
  beforeEach(() => {
    window.localStorage.setItem('dsh-mobile-language-v1', 'zh-CN')
  })

  it('显示会话列表并支持搜索、删除与新建入口', () => {
    const onBack = vi.fn()
    const onOpenHarness = vi.fn()
    render(<SessionManager onBack={onBack} onOpenHarness={onOpenHarness} />)

    expect(screen.getByRole('heading', { name: '会话管理' })).toBeInTheDocument()
    expect(screen.getByText('暂无会话')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '新建会话' }))
    expect(onOpenHarness).toHaveBeenCalledTimes(1)
    expect(screen.getByText('新会话 1')).toBeInTheDocument()

    fireEvent.change(screen.getByRole('textbox', { name: '搜索会话' }), { target: { value: '不存在' } })
    expect(screen.getByText('没有匹配的会话')).toBeInTheDocument()

    fireEvent.change(screen.getByRole('textbox', { name: '搜索会话' }), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: '删除会话 新会话 1' }))
    expect(screen.getByText('暂无会话')).toBeInTheDocument()
  })

  it('首页内嵌列表有真实的新建入口，忙碌时不能重复启动', () => {
    const onOpenHarness = vi.fn()
    const { rerender } = render(<SessionManager embedded disabled onBack={vi.fn()} onOpenHarness={onOpenHarness} />)
    expect(screen.getByRole('button', { name: '新建会话' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '新建会话' }))
    expect(onOpenHarness).not.toHaveBeenCalled()
    rerender(<SessionManager embedded onBack={vi.fn()} onOpenHarness={onOpenHarness} />)
    fireEvent.click(screen.getByRole('button', { name: '新建会话' }))
    expect(onOpenHarness).toHaveBeenCalledTimes(1)
  })
})
