// @vitest-environment jsdom

import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MessageItem } from './Messages'

afterEach(cleanup)

describe('MessageItem actions', () => {
  it('剪贴板拒绝时展示具体且脱敏的原因', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new DOMException('Clipboard permission denied', 'NotAllowedError')) },
    })
    render(<MessageItem
      sessionId="session-a"
      entry={{
        kind: 'user',
        seq: 1,
        time: 0,
        message: {
          id: 'message-a',
          role: 'user',
          content: [{ type: 'text', text: '待复制内容' }],
          source: { kind: 'user' },
        },
      }}
    />)

    fireEvent.click(screen.getByRole('button', { name: '复制消息' }))

    expect(await screen.findByRole('status')).toHaveTextContent('复制消息失败：Clipboard permission denied')
  })
})
