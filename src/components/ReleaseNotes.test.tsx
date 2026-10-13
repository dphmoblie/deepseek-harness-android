import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ReleaseNotes } from './ReleaseNotes'

describe('更新公告折叠', () => {
  it('不折叠恰好 320 字、6 行以内的公告', () => {
    const notes = '字'.repeat(315) + '\n'.repeat(5)
    const { container } = render(<ReleaseNotes notes={notes} />)
    expect(container.querySelector('.release-notes-content')?.textContent).toBe(notes)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('长单行默认折叠，使用原生按钮展开完整文本，并可在内容区外收起', () => {
    const notes = '长'.repeat(4000) + '最后一行'
    const { container } = render(<ReleaseNotes notes={notes} />)
    const button = screen.getByRole('button', { name: '展开完整更新公告' })
    const content = container.querySelector('.release-notes-content')!
    expect(button.tagName).toBe('BUTTON')
    expect(button).toHaveAttribute('type', 'button')
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(button).toHaveAttribute('aria-controls', content.id)
    expect(content.textContent).toBe('长'.repeat(320) + '…')
    expect(content).toHaveClass('is-collapsed')
    button.focus()
    expect(button).toHaveFocus()
    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('region', { name: '完整更新公告' })).toBe(content)
    expect(content).toHaveAttribute('tabindex', '0')
    expect(content.textContent).toBe(notes)
    expect(content).toHaveClass('is-expanded')
    expect(content).not.toContainElement(button)
    fireEvent.click(screen.getByRole('button', { name: '收起更新公告' }))
    expect(content).toHaveClass('is-collapsed')
    expect(content).not.toHaveAttribute('tabindex')
  })

  it('七行即折叠，连续空行也不产生无限长的预览', () => {
    const { container, rerender } = render(<ReleaseNotes notes={'一\n二\n三\n四\n五\n六\n七'} />)
    expect(container.querySelector('.release-notes-content')?.textContent).toBe('一\n二\n三\n四\n五\n六…')
    rerender(<ReleaseNotes notes={'第一行' + '\n'.repeat(1000) + '最后一行'} />)
    expect(container.querySelector('.release-notes-content')?.textContent).toBe('第一行' + '\n'.repeat(5) + '…')
    fireEvent.click(screen.getByRole('button', { name: '展开完整更新公告' }))
    expect(container.querySelector('.release-notes-content')?.textContent).toBe('第一行' + '\n'.repeat(1000) + '最后一行')
  })

  it('兼容 CRLF、CR 换行且展开后保留原始公告', () => {
    const notes = Array.from({ length: 7 }, (_, i) => String(i)).join('\r\n') + '\r结束'
    const { container } = render(<ReleaseNotes notes={notes} />)
    expect(container.querySelector('.release-notes-content')?.textContent).toBe('0\n1\n2\n3\n4\n5…')
    fireEvent.click(screen.getByRole('button', { name: '展开完整更新公告' }))
    expect(container.querySelector('.release-notes-content')?.textContent).toBe(notes)
  })

  it('预览不截断 Unicode 代理对', () => {
    const { container } = render(<ReleaseNotes notes={'😀'.repeat(321)} />)
    expect(container.querySelector('.release-notes-content')?.textContent).toBe('😀'.repeat(320) + '…')
  })

  it('切换公告后重置折叠状态，切回之前的公告也不恢复展开', () => {
    const first = '甲'.repeat(321)
    const second = '乙'.repeat(321)
    const { rerender } = render(<ReleaseNotes notes={first} />)
    fireEvent.click(screen.getByRole('button', { name: '展开完整更新公告' }))
    rerender(<ReleaseNotes notes={second} />)
    expect(screen.getByRole('button', { name: '展开完整更新公告' })).toHaveAttribute('aria-expanded', 'false')
    rerender(<ReleaseNotes notes={first} />)
    expect(screen.getByRole('button', { name: '展开完整更新公告' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('多份公告各自独立，远端文本不能注入 HTML', () => {
    const notes = '<img src=x onerror=alert(1)>' + '字'.repeat(321)
    const { container } = render(<><ReleaseNotes notes={notes} /><ReleaseNotes notes={'第二份'.repeat(150)} /></>)
    const buttons = screen.getAllByRole('button', { name: '展开完整更新公告' })
    expect(buttons[0].getAttribute('aria-controls')).not.toBe(buttons[1].getAttribute('aria-controls'))
    fireEvent.click(buttons[0])
    expect(buttons[1]).toHaveAttribute('aria-expanded', 'false')
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('.release-notes-content')?.textContent).toBe(notes)
  })
})
