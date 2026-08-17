// @vitest-environment jsdom

import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Composer } from './Composer'

afterEach(cleanup)

describe('Composer', () => {
  it('空闲时禁用引导模式', () => {
    render(<Composer running={false} onSend={vi.fn()} onCancel={vi.fn()} />)

    const queueButton = screen.getByRole('button', { name: '排队' })
    const steerButton = screen.getByRole('button', { name: '引导' })

    expect(queueButton).toHaveAttribute('aria-pressed', 'true')
    expect(steerButton).toBeDisabled()
    expect(steerButton).toHaveAttribute('aria-pressed', 'false')
  })

  it('任务结束后将引导模式复位为排队', () => {
    const { rerender } = render(
      <Composer running onSend={vi.fn()} onCancel={vi.fn()} />,
    )

    fireEvent.click(screen.getByRole('button', { name: '引导' }))
    expect(screen.getByRole('button', { name: '引导' })).toHaveAttribute('aria-pressed', 'true')

    rerender(<Composer running={false} onSend={vi.fn()} onCancel={vi.fn()} />)

    expect(screen.getByRole('button', { name: '排队' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '引导' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '引导' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('从引导态切至空闲后发送时防御性地使用排队模式', () => {
    const onSend = vi.fn()
    const { rerender } = render(
      <Composer running onSend={onSend} onCancel={vi.fn()} />,
    )

    fireEvent.click(screen.getByRole('button', { name: '引导' }))
    fireEvent.change(screen.getByRole('textbox', { name: '发送给 DeepSeek Harness 的消息' }), {
      target: { value: '继续检查' },
    })
    rerender(<Composer running={false} onSend={onSend} onCancel={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '发送并排队' }))

    expect(onSend).toHaveBeenCalledOnce()
    expect(onSend).toHaveBeenCalledWith('继续检查', 'queue')
  })

  it('支持从剪贴板添加图片并随消息发送', async () => {
    const onSend = vi.fn()
    const file = new File([new Uint8Array([1, 2, 3])], 'screen.png', { type: 'image/png' })
    render(<Composer running={false} onSend={onSend} onCancel={vi.fn()} />)

    fireEvent.paste(screen.getByRole('textbox', { name: '发送给 DeepSeek Harness 的消息' }), {
      clipboardData: { files: [file] },
    })

    expect(await screen.findByRole('img', { name: 'screen.png' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '发送并排队' }))

    await waitFor(() => expect(onSend).toHaveBeenCalledWith(
      '',
      'queue',
      [expect.objectContaining({ type: 'image', mediaType: 'image/png', name: 'screen.png' })],
    ))
  })
})
