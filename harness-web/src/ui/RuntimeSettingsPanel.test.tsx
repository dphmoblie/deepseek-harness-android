// @vitest-environment jsdom

import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SettingsDescribeValue, SettingsNamespaceView } from '../api/types'
import { RuntimeSettingsPanel } from './RuntimeSettingsPanel'

const NAMESPACE: SettingsNamespaceView = {
  ns: 'shell',
  schema: {},
  value: { executable: '/bin/bash', timeout: 30, enabled: true, apiToken: null },
  applies: 'live',
  secrets: [
    { path: ['apiToken'], set: true },
    { path: ['optionalSecret'], set: false },
  ],
  revision: 4,
}

const DESCRIBE: SettingsDescribeValue = {
  writable: true,
  hasDocument: true,
  namespaces: [NAMESPACE],
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('RuntimeSettingsPanel', () => {
  it('saves a valid string value instead of treating it as an error', async () => {
    const onUpdated = vi.fn()
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      if (typeof init?.body !== 'string') throw new Error('expected JSON request body')
      const request = JSON.parse(init.body) as {
        rpcId: string
        payload: { ops: Array<{ value: unknown }> }
      }
      expect(request.payload.ops[0]?.value).toBe('/system/bin/sh')
      return Promise.resolve(new Response(JSON.stringify({
        type: 'server-response',
        rpcId: request.rpcId,
        result: { ok: true, value: { ...NAMESPACE, value: { ...NAMESPACE.value as object, executable: '/system/bin/sh' }, revision: 5 } },
      })))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<RuntimeSettingsPanel describe={DESCRIBE} onUpdated={onUpdated} />)
    const field = screen.getByLabelText('executable')
    fireEvent.change(field, { target: { value: '/system/bin/sh' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 executable' }))

    await waitFor(() => expect(onUpdated).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows each secret status without rendering a secret editor or value', () => {
    render(<RuntimeSettingsPanel describe={DESCRIBE} onUpdated={vi.fn()} />)

    expect(screen.getByText('apiToken')).toBeInTheDocument()
    expect(screen.getByText('optionalSecret')).toBeInTheDocument()
    expect(screen.getByText('已设置')).toBeInTheDocument()
    expect(screen.getByText('未设置')).toBeInTheDocument()
    expect(screen.queryByLabelText('apiToken')).not.toBeInTheDocument()
    expect(screen.queryByText(/任意插件/)).not.toBeInTheDocument()
  })

  it('rejects invisible control characters before sending a mutation', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    render(<RuntimeSettingsPanel describe={DESCRIBE} onUpdated={vi.fn()} />)

    fireEvent.change(screen.getByLabelText('executable'), { target: { value: 'bad\u200bvalue' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 executable' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('文本设置长度或字符格式无效')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
