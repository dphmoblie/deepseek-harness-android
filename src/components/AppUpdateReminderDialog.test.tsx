import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AppUpdateReminderDialog } from './AppUpdateReminderDialog'

describe('应用更新提醒弹窗', () => {
  beforeEach(() => { localStorage.clear() })

  it('展示简短纯文本，并分别处理稍后与查看更新操作', () => {
    const onDismiss = vi.fn()
    const onUpdate = vi.fn()
    render(<AppUpdateReminderDialog version={'<img src=x>'} onDismiss={onDismiss} onUpdate={onUpdate} />)
    const dialog = screen.getByRole('dialog', { name: '发现应用新版本' })
    expect(dialog).toHaveTextContent('应用 <img src=x> 已可更新。前往版本管理查看公告和更新选项。')
    expect(dialog.querySelector('img')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '暂不更新' }))
    expect(onDismiss).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: '查看更新' }))
    expect(onUpdate).toHaveBeenCalledTimes(1)
  })

  it('进入时聚焦暂不更新，Tab 留在弹窗，Escape 关闭并恢复焦点', () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const onDismiss = vi.fn()
    const view = render(<AppUpdateReminderDialog version="0.3.0" onDismiss={onDismiss} onUpdate={vi.fn()} />)
    const later = screen.getByRole('button', { name: '暂不更新' })
    const update = screen.getByRole('button', { name: '查看更新' })
    expect(later).toHaveFocus()
    fireEvent.keyDown(later, { key: 'Tab', shiftKey: true })
    expect(update).toHaveFocus()
    fireEvent.keyDown(update, { key: 'Tab' })
    expect(later).toHaveFocus()
    fireEvent.keyDown(later, { key: 'Escape' })
    expect(onDismiss).toHaveBeenCalledTimes(1)
    view.unmount()
    expect(trigger).toHaveFocus()
    trigger.remove()
  })

  it('点击内容不关闭，点击背景可关闭', () => {
    const onDismiss = vi.fn()
    render(<AppUpdateReminderDialog version="0.3.0" onDismiss={onDismiss} onUpdate={vi.fn()} />)
    fireEvent.pointerDown(screen.getByRole('dialog'))
    expect(onDismiss).not.toHaveBeenCalled()
    fireEvent.pointerDown(screen.getByRole('presentation'))
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })
})
