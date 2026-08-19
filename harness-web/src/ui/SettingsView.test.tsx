// @vitest-environment jsdom

import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type * as WireModule from '../api/wire'

const wire = vi.hoisted(() => ({
  callUnary: vi.fn(),
}))

vi.mock('../api/wire', async (importOriginal) => {
  const actual = await importOriginal<typeof WireModule>()
  return { ...actual, ...wire }
})

import { RpcFailure } from '../api/wire'
import { SettingsView } from './SettingsView'

const WORKSPACE = {
  workspaceId: 'workspace-1',
  path: '/home/user/project',
  title: 'Project Alpha',
  sessionIds: ['session-a'],
  createdAt: '2026-08-17T00:00:00.000Z',
  updatedAt: '2026-08-17T00:00:00.000Z',
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function defaultResponse(method: string): unknown {
  switch (method) {
    case 'llm.providers':
      return { providers: [] }
    case 'settings.describe':
      return { writable: true, hasDocument: false, namespaces: [] }
    case 'host.describe':
      return { version: '1.0.0', cwd: '/home/user', attachedSessions: 2, canOpenPath: false }
    case 'workspace.list':
      return { items: [WORKSPACE], archivedSessionIds: [] }
    case 'agentPreset.list':
      return {
        presets: [{
          id: 'planner',
          trust: 'system',
          isDefault: false,
          name: 'Planner',
          description: 'Plans changes before editing',
        }],
        authorable: true,
        hasDocument: true,
      }
    case 'session.list':
      return {
        items: [
          { sessionId: 'session-a', updatedAt: 20, running: false, blank: false },
          { sessionId: 'session-b', updatedAt: 10, running: false, blank: true },
        ],
      }
    case 'workspace.create':
      return { workspace: WORKSPACE, created: true }
    case 'workspace.rename':
      return { workspace: { ...WORKSPACE, title: '新项目' } }
    case 'workspace.delete':
      return { deleted: true }
    case 'agentPreset.read':
      return {
        agentPreset: 'planner',
        trust: 'system',
        name: 'Planner',
        description: 'Plans changes before editing',
        content: '# Planner\nInspect before editing.',
      }
    case 'agentPreset.select':
      return { agentPreset: 'planner' }
    default:
      throw new Error(`Unexpected RPC: ${method}`)
  }
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  wire.callUnary.mockImplementation((_baseUrl: string, method: string) =>
    Promise.resolve(defaultResponse(method)),
  )
})

afterEach(cleanup)

describe('SettingsView workspace management', () => {
  it('完整插件工作台是按需入口且不会替换移动根页面', async () => {
    render(<SettingsView onBack={vi.fn()} />)

    const link = await screen.findByRole('link', { name: '打开完整插件工作台' })
    expect(link).toHaveAttribute('href', '/?surface=plugins')
  })

  it('校验路径后以结构化参数创建工作区', async () => {
    render(<SettingsView onBack={vi.fn()} />)
    await screen.findByText('Project Alpha')

    const pathInput = screen.getByRole('textbox', { name: '工作区绝对路径' })
    fireEvent.change(pathInput, { target: { value: 'relative/project' } })
    fireEvent.click(screen.getByRole('button', { name: '添加' }))

    expect(screen.getByRole('alert')).toHaveTextContent('工作区路径必须是绝对路径')
    expect(wire.callUnary).not.toHaveBeenCalledWith(expect.anything(), 'workspace.create', expect.anything())

    fireEvent.change(pathInput, { target: { value: '/home/user/\u200bproject' } })
    fireEvent.click(screen.getByRole('button', { name: '添加' }))
    expect(screen.getByRole('alert')).toHaveTextContent('工作区路径不能包含控制字符、零宽字符或双向文本控制符')

    fireEvent.change(pathInput, { target: { value: '  /home/user/project  ' } })
    fireEvent.click(screen.getByRole('button', { name: '添加' }))

    await waitFor(() => expect(wire.callUnary).toHaveBeenCalledWith(
      window.location.origin,
      'workspace.create',
      { path: '/home/user/project' },
    ))
  })

  it('支持重命名以及二次确认删除', async () => {
    render(<SettingsView onBack={vi.fn()} />)
    await screen.findByText('Project Alpha')

    fireEvent.click(screen.getByRole('button', { name: '重命名工作区 Project Alpha' }))
    const titleInput = screen.getByRole('textbox', { name: '工作区名称' })
    fireEvent.change(titleInput, { target: { value: '新\u200b项目' } })
    fireEvent.click(screen.getByRole('button', { name: '保存工作区 Project Alpha 的新名称' }))
    expect(screen.getByRole('alert')).toHaveTextContent('工作区名称不能包含控制字符、零宽字符或双向文本控制符')

    fireEvent.change(titleInput, { target: { value: ' 新项目 ' } })
    fireEvent.click(screen.getByRole('button', { name: '保存工作区 Project Alpha 的新名称' }))
    await waitFor(() => expect(wire.callUnary).toHaveBeenCalledWith(
      window.location.origin,
      'workspace.rename',
      { workspaceId: 'workspace-1', title: '新项目' },
    ))

    await waitFor(() => expect(screen.getByRole('button', { name: '删除工作区 Project Alpha' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '删除工作区 Project Alpha' }))
    expect(wire.callUnary).not.toHaveBeenCalledWith(expect.anything(), 'workspace.delete', expect.anything())

    fireEvent.click(screen.getByRole('button', { name: '确认删除' }))
    await waitFor(() => expect(wire.callUnary).toHaveBeenCalledWith(
      window.location.origin,
      'workspace.delete',
      { workspaceId: 'workspace-1' },
    ))
  })

  it('显示具体的业务错误分类和服务端原始原因', async () => {
    wire.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'workspace.create') {
        return Promise.reject(new RpcFailure({
          code: 'workspace-invalid-path',
          message: 'Path does not exist: /missing',
          details: {},
        }))
      }
      return Promise.resolve(defaultResponse(method))
    })

    render(<SettingsView onBack={vi.fn()} />)
    await screen.findByText('Project Alpha')
    fireEvent.change(screen.getByRole('textbox', { name: '工作区绝对路径' }), {
      target: { value: '/missing' },
    })
    fireEvent.click(screen.getByRole('button', { name: '添加' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '创建工作区失败：工作区路径无效：Path does not exist: /missing（错误代码：workspace-invalid-path）',
    )
    expect(screen.queryByText('本轮因错误终止')).not.toBeInTheDocument()
  })
})

describe('SettingsView Agent preset management', () => {
  it('读取 preset 内容并应用到打开设置前的当前会话', async () => {
    localStorage.setItem('dsh-mobile-last-session-v1', 'session-b')
    render(<SettingsView onBack={vi.fn()} />)
    await screen.findByText('Planner')
    expect(screen.getByText('应用目标：session-b')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Planner/ }))
    expect(await screen.findByRole('textbox', { name: 'Planner preset 内容' })).toHaveValue(
      '# Planner\nInspect before editing.',
    )
    expect(wire.callUnary).toHaveBeenCalledWith(
      window.location.origin,
      'agentPreset.read',
      { agentPreset: 'planner' },
    )

    fireEvent.click(screen.getByRole('button', { name: '应用到当前会话' }))
    await waitFor(() => expect(wire.callUnary).toHaveBeenCalledWith(
      window.location.origin,
      'agentPreset.select',
      { sessionId: 'session-b', agentPreset: 'planner' },
    ))
    expect(await screen.findByRole('button', { name: '已应用到当前会话' })).toBeDisabled()
  })

  it('不允许把 preset 应用到已有消息的会话', async () => {
    localStorage.setItem('dsh-mobile-last-session-v1', 'session-b')
    render(<SettingsView currentSessionId="session-a" onBack={vi.fn()} />)

    expect(await screen.findByText('只有尚未发送消息的空白会话可以切换 preset')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Planner/ }))
    expect(await screen.findByRole('button', { name: '应用到当前会话' })).toBeDisabled()
  })
})

describe('SettingsView model credentials', () => {
  it('通过 credentials API 保存密钥，不把明文写入 settings', async () => {
    wire.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'llm.providers') {
        return Promise.resolve({
          providers: [{
            provider: 'deepseek-official',
            displayName: 'DeepSeek',
            settingsNs: 'llm-deepseek',
            settingsPath: [],
            active: true,
          }],
        })
      }
      if (method === 'settings.describe') {
        return Promise.resolve({
          writable: true,
          hasDocument: false,
          namespaces: [{
            ns: 'llm-deepseek',
            schema: {},
            value: { apiKeyEnv: 'DEEPSEEK_API_KEY' },
            applies: 'live',
            secrets: [],
            revision: 3,
          }],
        })
      }
      if (method === 'credentials.describe') {
        return Promise.resolve({
          credentials: {
            DEEPSEEK_API_KEY: { configured: false, writable: true },
          },
        })
      }
      if (method === 'credentials.set') return Promise.resolve({})
      return Promise.resolve(defaultResponse(method))
    })

    render(<SettingsView onBack={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: /DeepSeek/ }))
    fireEvent.change(screen.getByLabelText('API 密钥'), { target: { value: 'test-placeholder-value' } })
    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(wire.callUnary).toHaveBeenCalledWith(
      window.location.origin,
      'credentials.set',
      { ref: 'DEEPSEEK_API_KEY', value: 'test-placeholder-value' },
    ))
    expect(wire.callUnary).not.toHaveBeenCalledWith(
      expect.anything(),
      'settings.mutate',
      expect.anything(),
    )
  })

  it('以 namespace 和 settingsPath 区分 profile，并为缺少引用的 profile 只写凭据引用', async () => {
    const namespace = {
      ns: 'llm-pi-ai',
      schema: {},
      value: {
        providers: {
          openai: { apiKeyEnv: 'OPENAI_API_KEY' },
          anthropic: {},
        },
      },
      applies: 'live' as const,
      secrets: [],
      revision: 7,
    }
    wire.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'llm.providers') {
        return Promise.resolve({
          providers: [
            {
              provider: 'openai', displayName: 'OpenAI', settingsNs: 'llm-pi-ai',
              settingsPath: ['providers', 'openai'], active: true,
            },
            {
              provider: 'anthropic', displayName: 'Anthropic', settingsNs: 'llm-pi-ai',
              settingsPath: ['providers', 'anthropic'], active: false,
            },
          ],
        })
      }
      if (method === 'settings.describe') {
        return Promise.resolve({ writable: true, hasDocument: false, namespaces: [namespace] })
      }
      if (method === 'credentials.describe') {
        return Promise.resolve({
          credentials: {
            OPENAI_API_KEY: { configured: true, writable: true },
            ANTHROPIC_API_KEY: { configured: false, writable: true },
          },
        })
      }
      if (method === 'settings.mutate') {
        return Promise.resolve({
          ...namespace,
          value: {
            providers: {
              ...(namespace.value.providers),
              anthropic: { apiKeyEnv: 'ANTHROPIC_API_KEY' },
            },
          },
          revision: 8,
        })
      }
      if (method === 'credentials.set') return Promise.resolve({})
      return Promise.resolve(defaultResponse(method))
    })

    render(<SettingsView onBack={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: /Anthropic/ }))
    expect(screen.getByText(/凭据引用：ANTHROPIC_API_KEY/)).toBeInTheDocument()
    expect(screen.queryByText(/凭据引用：OPENAI_API_KEY/)).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('API 密钥'), { target: { value: 'credential-placeholder' } })
    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(wire.callUnary).toHaveBeenCalledWith(
      window.location.origin,
      'settings.mutate',
      {
        ns: 'llm-pi-ai',
        ops: [{ op: 'set', path: ['providers', 'anthropic', 'apiKeyEnv'], value: 'ANTHROPIC_API_KEY' }],
        expectedRevision: 7,
      },
    ))
    expect(wire.callUnary).toHaveBeenCalledWith(
      window.location.origin,
      'credentials.set',
      { ref: 'ANTHROPIC_API_KEY', value: 'credential-placeholder' },
    )
    const settingsPayloads = wire.callUnary.mock.calls
      .filter((call) => call[1] === 'settings.mutate')
      .map((call) => JSON.stringify(call[2]))
    expect(settingsPayloads.every((payload) => !payload.includes('credential-placeholder'))).toBe(true)
  })

  it('尊重 settings 与 credential 的只读状态，并通过 credentials.unset 清除', async () => {
    let settingsWritable = false
    let credentialWritable = true
    wire.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'llm.providers') {
        return Promise.resolve({ providers: [{
          provider: 'deepseek-official', displayName: 'DeepSeek', settingsNs: 'llm-deepseek',
          settingsPath: [], active: true,
        }] })
      }
      if (method === 'settings.describe') {
        return Promise.resolve({
          writable: settingsWritable,
          hasDocument: false,
          namespaces: [{
            ns: 'llm-deepseek', schema: {}, value: { apiKeyEnv: 'DEEPSEEK_API_KEY' },
            applies: 'live', secrets: [], revision: 4,
          }],
        })
      }
      if (method === 'credentials.describe') {
        return Promise.resolve({ credentials: {
          DEEPSEEK_API_KEY: { configured: true, writable: credentialWritable },
        } })
      }
      if (method === 'credentials.unset') return Promise.resolve({})
      return Promise.resolve(defaultResponse(method))
    })

    const view = render(<SettingsView onBack={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: /DeepSeek/ }))
    expect(screen.getByLabelText('API 密钥')).toBeDisabled()
    expect(screen.getByRole('button', { name: '删除' })).toBeDisabled()
    expect(wire.callUnary).not.toHaveBeenCalledWith(expect.anything(), 'credentials.unset', expect.anything())

    settingsWritable = true
    credentialWritable = true
    view.unmount()
    render(<SettingsView onBack={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: /DeepSeek/ }))
    fireEvent.click(screen.getByRole('button', { name: '删除' }))
    await waitFor(() => expect(wire.callUnary).toHaveBeenCalledWith(
      window.location.origin,
      'credentials.unset',
      { ref: 'DEEPSEEK_API_KEY' },
    ))
  })

  it('脱敏凭据写入错误，不在设置页回显密钥', async () => {
    wire.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'llm.providers') return Promise.resolve({ providers: [{
        provider: 'deepseek-official', displayName: 'DeepSeek', settingsNs: 'llm-deepseek',
        settingsPath: [], active: true,
      }] })
      if (method === 'settings.describe') return Promise.resolve({
        writable: true,
        hasDocument: false,
        namespaces: [{
          ns: 'llm-deepseek', schema: {}, value: { apiKeyEnv: 'DEEPSEEK_API_KEY' },
          applies: 'live', secrets: [], revision: 1,
        }],
      })
      if (method === 'credentials.describe') return Promise.resolve({
        credentials: { DEEPSEEK_API_KEY: { configured: false, writable: true } },
      })
      if (method === 'credentials.set') return Promise.reject(new RpcFailure({
        code: 'credential-rejected',
        message: 'api_key=sk-1234567890abcdef was rejected',
        details: {},
      }))
      return Promise.resolve(defaultResponse(method))
    })

    render(<SettingsView onBack={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: /DeepSeek/ }))
    fireEvent.change(screen.getByLabelText('API 密钥'), { target: { value: 'typed-placeholder' } })
    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('[已隐藏]')
    expect(alert).not.toHaveTextContent('sk-1234567890abcdef')
  })
})

describe('SettingsView reload ownership', () => {
  it('新 generation 胜出，旧 session.list 响应不会改写当前会话 owner', async () => {
    const oldSessions = deferred<{ items: Array<{
      sessionId: string; updatedAt: number; running: boolean; blank: boolean
    }> }>()
    let sessionLoads = 0
    wire.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'session.list') {
        sessionLoads += 1
        if (sessionLoads === 1) return oldSessions.promise
        return Promise.resolve({
          items: [{ sessionId: 'session-b', updatedAt: 30, running: false, blank: true }],
        })
      }
      return Promise.resolve(defaultResponse(method))
    })

    const view = render(<SettingsView currentSessionId="session-a" onBack={vi.fn()} />)
    view.rerender(<SettingsView currentSessionId="session-b" onBack={vi.fn()} />)
    expect(await screen.findByText('应用目标：session-b')).toBeInTheDocument()

    oldSessions.resolve({
      items: [{ sessionId: 'session-a', updatedAt: 40, running: false, blank: true }],
    })
    await Promise.resolve()
    expect(screen.getByText('应用目标：session-b')).toBeInTheDocument()
    expect(screen.queryByText('应用目标：session-a')).not.toBeInTheDocument()
  })
})
