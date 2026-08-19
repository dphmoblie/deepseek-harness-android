// @vitest-environment jsdom

import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginMarketplaceView } from './PluginMarketplaceView'
import {
  APPROVE_BUILDS_PATH,
  CANCEL_PATH,
  INSTALLED_PATH,
  INSTALL_PATH,
  REGISTRY_PATH,
  SETUP_PNPM_PATH,
  STATUS_PATH,
  UPDATES_PATH,
} from './pluginMarketplaceConstants'

const REGISTRY = {
  registry: {
    count: 2,
    categories: { utility: { zh: '工具' } },
    plugins: [
      {
        name: 'plugin-alpha',
        owner: 'alice',
        url: 'https://github.com/alice/plugin-alpha',
        category: 'utility',
        description: { zh: 'Alpha 工具' },
      },
      {
        name: 'plugin-beta',
        owner: 'bob',
        url: 'https://github.com/bob/plugin-beta',
        category: 'utility',
        description: { zh: 'Beta 工具' },
      },
    ],
  },
}

type InstalledFixture = {
  installed: Record<string, string>
  disabled: string[]
  activation: Record<string, { state: 'live' | 'restart' | 'inert' | 'broken' | 'missing'; reasons: string[] }>
}

const INSTALLED: InstalledFixture = {
  installed: {
    'plugin-alpha': 'github:alice/plugin-alpha',
    'plugin-beta': 'github:bob/plugin-beta',
  },
  disabled: ['plugin-beta'],
  activation: {
    'plugin-alpha': { state: 'live', reasons: [] },
    'plugin-beta': { state: 'restart', reasons: ['需要重启'] },
  },
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

function pathOf(input: RequestInfo | URL): string {
  return typeof input === 'string' ? input : input instanceof URL ? input.pathname : new URL(input.url).pathname
}

function parsedBody(init: RequestInit | undefined): unknown {
  const body = init?.body
  if (typeof body !== 'string') throw new Error('expected a JSON request body')
  return JSON.parse(body) as unknown
}

function rpcResponse(init: RequestInit | undefined, value: unknown): Response {
  const body = parsedBody(init) as { rpcId: string }
  return jsonResponse({ type: 'server-response', rpcId: body.rpcId, result: { ok: true, value } })
}

async function loadMarket(expectData = true): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: '加载插件市场' }))
  if (expectData) {
    await screen.findByRole('heading', { name: /已安装/ })
  } else {
    await waitFor(() => expect(screen.getByRole('button', { name: /加载插件市场|刷新插件市场/ })).not.toBeDisabled())
  }
}

describe('PluginMarketplaceView', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = pathOf(input)
      if (path === REGISTRY_PATH) return Promise.resolve(jsonResponse(REGISTRY))
      if (path === INSTALLED_PATH) return Promise.resolve(jsonResponse(INSTALLED))
      if (path === STATUS_PATH) return Promise.resolve(jsonResponse({ active: false, busy: false, pnpm: true, restart: false }))
      if (path === UPDATES_PATH) return Promise.resolve(jsonResponse({ updates: {} }))
      if (path === '/api/pluginInventory/list') return Promise.resolve(rpcResponse(init, { entries: [] }))
      throw new Error(`unexpected request: ${path} ${init?.method ?? 'GET'}`)
    })
  })

  afterEach(cleanup)

  it('loads registry and installed state, then installs only the selected registry URL', async () => {
    const initialInstalled: InstalledFixture = {
      installed: { 'plugin-beta': 'github:bob/plugin-beta' },
      disabled: ['plugin-beta'],
      activation: { 'plugin-beta': { state: 'restart', reasons: ['需要重启'] } },
    }
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const path = pathOf(input)
      if (path === REGISTRY_PATH) return Promise.resolve(jsonResponse(REGISTRY))
      if (path === INSTALLED_PATH) return Promise.resolve(jsonResponse(initialInstalled))
      throw new Error(`unexpected request: ${path}`)
    })
    const view = render(<PluginMarketplaceView fetchFn={fetchMock as unknown as typeof fetch} />)
    expect(await screen.findByText('插件市场')).toBeInTheDocument()
    await loadMarket()

    fireEvent.change(screen.getByRole('searchbox', { name: '搜索插件' }), { target: { value: 'missing' } })
    expect(screen.getByText('没有匹配的插件')).toBeInTheDocument()

    fireEvent.change(screen.getByRole('searchbox', { name: '搜索插件' }), { target: { value: 'plugin-alpha' } })
    expect(screen.getByText('plugin-alpha')).toBeInTheDocument()
    view.unmount()

    let installedRefreshes = 0
    let installedAfterInstall = false
    fetchMock.mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const path = pathOf(input)
      if (path === REGISTRY_PATH) return Promise.resolve(jsonResponse(REGISTRY))
      if (path === INSTALLED_PATH) {
        installedRefreshes += 1
        return Promise.resolve(jsonResponse(installedAfterInstall ? INSTALLED : initialInstalled))
      }
      if (path === '/dsh-market/install') {
        expect(init?.method).toBe('POST')
        expect(parsedBody(init)).toEqual({ url: 'https://github.com/alice/plugin-alpha' })
        installedAfterInstall = true
        return Promise.resolve(jsonResponse({ ok: true }))
      }
      throw new Error(`unexpected request: ${path}`)
    })
    render(<PluginMarketplaceView fetchFn={fetchMock as unknown as typeof fetch} />)
    await loadMarket()
    expect(screen.getByText(/原因：需要重启/)).toBeInTheDocument()
    const install = await screen.findByRole('button', { name: '安装' })
    fireEvent.click(install)
    await screen.findByRole('status')
    expect(installedRefreshes).toBeGreaterThan(0)
    await waitFor(() => expect(screen.queryByRole('button', { name: '安装' })).not.toBeInTheDocument())
  })

  it('requires uninstall confirmation and sends toggle/uninstall payloads', async () => {
    let current = INSTALLED
    let uninstallRequested = false
    fetchMock.mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const path = pathOf(input)
      if (path === REGISTRY_PATH) return Promise.resolve(jsonResponse(REGISTRY))
      if (path === INSTALLED_PATH) return Promise.resolve(jsonResponse(current))
      if (path === '/dsh-market/toggle') {
        expect(parsedBody(init)).toEqual({ name: 'plugin-beta', enabled: true })
        current = { ...current, disabled: [], activation: { ...current.activation, 'plugin-beta': { state: 'live', reasons: [] } } }
        return Promise.resolve(jsonResponse({ ok: true, disabled: [] }))
      }
      if (path === '/dsh-market/uninstall') {
        uninstallRequested = true
        expect(parsedBody(init)).toEqual({ name: 'plugin-alpha' })
        current = { ...current, installed: { 'plugin-beta': current.installed['plugin-beta'] ?? 'github:bob/plugin-beta' }, disabled: ['plugin-beta'] }
        return Promise.resolve(jsonResponse({ ok: true }))
      }
      throw new Error(`unexpected request: ${path}`)
    })

    render(<PluginMarketplaceView fetchFn={fetchMock as unknown as typeof fetch} />)
    await loadMarket()
    await screen.findByRole('button', { name: '启用' })
    fireEvent.click(screen.getByRole('button', { name: '启用' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '停用' })).toBeInTheDocument())

    fireEvent.click(screen.getAllByRole('button', { name: '卸载' })[0]!)
    expect(screen.getByRole('button', { name: '确认卸载' })).toBeInTheDocument()
    expect(uninstallRequested).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: '确认卸载' }))
    await waitFor(() => expect(uninstallRequested).toBe(true))
  })

  it('shows a concrete redacted error when the registry request fails', async () => {
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      if (pathOf(input) === REGISTRY_PATH) return Promise.resolve(jsonResponse({ error: 'registry token=secret-value unavailable' }, 503))
      return Promise.resolve(jsonResponse(INSTALLED))
    })

    render(<PluginMarketplaceView fetchFn={fetchMock as unknown as typeof fetch} />)
    await loadMarket(false)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('刷新插件市场失败')
    expect(alert).toHaveTextContent('[已隐藏]')
    expect(alert).not.toHaveTextContent('secret-value')
    expect(alert).not.toHaveTextContent('本轮因错误终止')
  })

  it('rejects a registry payload with a non-HTTPS install URL before rendering actions', async () => {
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      if (pathOf(input) === REGISTRY_PATH) {
        return Promise.resolve(jsonResponse({ registry: { count: 1, plugins: [{ name: 'bad', owner: 'x', url: 'http://example.invalid/plugin', category: 'utility' }] } }))
      }
      return Promise.resolve(jsonResponse({ installed: {} }))
    })

    render(<PluginMarketplaceView fetchFn={fetchMock as unknown as typeof fetch} />)
    await loadMarket(false)
    expect(await screen.findByRole('alert')).toHaveTextContent('插件目录包含无效安装地址')
    expect(screen.queryByRole('button', { name: '安装' })).not.toBeInTheDocument()
  })

  it('loads pluginInventory/list with the required args envelope and renders its state', async () => {
    fetchMock.mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const path = pathOf(input)
      if (path === REGISTRY_PATH) return Promise.resolve(jsonResponse(REGISTRY))
      if (path === INSTALLED_PATH) return Promise.resolve(jsonResponse(INSTALLED))
      if (path === STATUS_PATH) return Promise.resolve(jsonResponse({ active: false, busy: false, pnpm: true, restart: false }))
      if (path === UPDATES_PATH) return Promise.resolve(jsonResponse({ updates: {} }))
      if (path === '/api/pluginInventory/list') {
        const request = parsedBody(init) as { payload: unknown }
        expect(request.payload).toEqual({ args: {} })
        return Promise.resolve(rpcResponse(init, { entries: [{ entryId: 'loader-one', moduleName: 'cordis:one', enabled: true, fiberPhase: 'active' }] }))
      }
      throw new Error(`unexpected request: ${path}`)
    })

    render(<PluginMarketplaceView fetchFn={fetchMock as unknown as typeof fetch} />)
    await loadMarket()
    expect(await screen.findByText('cordis:one')).toBeInTheDocument()
    expect(screen.getByText('运行中')).toBeInTheDocument()
  })

  it('paginates a large registry instead of rendering every plugin at once', async () => {
    const plugins = Array.from({ length: 30 }, (_, index) => ({
      name: `plugin-${String(index + 1).padStart(2, '0')}`,
      owner: 'owner',
      url: `https://github.com/owner/plugin-${index + 1}`,
      category: 'utility',
    }))
    fetchMock.mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const path = pathOf(input)
      if (path === REGISTRY_PATH) return Promise.resolve(jsonResponse({ registry: { count: plugins.length, plugins } }))
      if (path === INSTALLED_PATH) return Promise.resolve(jsonResponse({ installed: {} }))
      if (path === STATUS_PATH) return Promise.resolve(jsonResponse({ active: false, busy: false, pnpm: true, restart: false }))
      if (path === UPDATES_PATH) return Promise.resolve(jsonResponse({ updates: {} }))
      if (path === '/api/pluginInventory/list') return Promise.resolve(rpcResponse(init, { entries: [] }))
      throw new Error(`unexpected request: ${path}`)
    })

    render(<PluginMarketplaceView fetchFn={fetchMock as unknown as typeof fetch} />)
    await loadMarket()
    expect(screen.getByText('plugin-24')).toBeInTheDocument()
    expect(screen.queryByText('plugin-25')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /显示更多插件/ }))
    expect(screen.getByText('plugin-30')).toBeInTheDocument()
  })

  it('shows redacted reason and stderr details and can approve blocked builds before retrying', async () => {
    const initial = { installed: {}, disabled: [], activation: {} }
    let installCalls = 0
    let approved = false
    fetchMock.mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const path = pathOf(input)
      if (path === REGISTRY_PATH) return Promise.resolve(jsonResponse({ registry: { count: 1, plugins: [REGISTRY.registry.plugins[0]] } }))
      if (path === INSTALLED_PATH) return Promise.resolve(jsonResponse(approved ? INSTALLED : initial))
      if (path === STATUS_PATH) return Promise.resolve(jsonResponse({ active: false, busy: false, pnpm: true, restart: false }))
      if (path === UPDATES_PATH) return Promise.resolve(jsonResponse({ updates: {} }))
      if (path === '/api/pluginInventory/list') return Promise.resolve(rpcResponse(init, { entries: [] }))
      if (path === INSTALL_PATH) {
        installCalls += 1
        if (!approved) return Promise.resolve(jsonResponse({
          ok: false,
          reason: 'native build token=private-value was blocked',
          stderr: 'prepare script denied',
          ignoredBuilds: ['native-dep@1.2.3'],
        }, 502))
        return Promise.resolve(jsonResponse({ ok: true }))
      }
      if (path === APPROVE_BUILDS_PATH) {
        expect(parsedBody(init)).toEqual({ packages: ['native-dep'] })
        approved = true
        return Promise.resolve(jsonResponse({ ok: true, approved: ['native-dep'] }))
      }
      throw new Error(`unexpected request: ${path}`)
    })

    render(<PluginMarketplaceView fetchFn={fetchMock as unknown as typeof fetch} />)
    await loadMarket()
    fireEvent.click(screen.getByRole('button', { name: '安装' }))
    const alert = (await screen.findByText(/安装插件失败/)).closest('[role="alert"]')
    expect(alert).not.toBeNull()
    expect(alert).toHaveTextContent('[已隐藏]')
    expect(alert).toHaveTextContent('prepare script denied')
    expect(alert).not.toHaveTextContent('private-value')
    fireEvent.click(screen.getByRole('button', { name: '放行并重试' }))
    await waitFor(() => expect(installCalls).toBe(2))
    await waitFor(() => expect(screen.queryByRole('button', { name: '安装' })).not.toBeInTheDocument())
  })

  it('renders live status, cancels the active operation, and initializes a missing pnpm environment', async () => {
    let active = true
    let pnpm = false
    fetchMock.mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const path = pathOf(input)
      if (path === REGISTRY_PATH) return Promise.resolve(jsonResponse(REGISTRY))
      if (path === INSTALLED_PATH) return Promise.resolve(jsonResponse(INSTALLED))
      if (path === STATUS_PATH) return Promise.resolve(jsonResponse({
        active,
        busy: active,
        cancelling: false,
        pnpm,
        restart: true,
        phase: active ? 'downloading' : null,
        currentPackage: active ? 'plugin-alpha' : null,
      }))
      if (path === UPDATES_PATH) return Promise.resolve(jsonResponse({ updates: {} }))
      if (path === '/api/pluginInventory/list') return Promise.resolve(rpcResponse(init, { entries: [] }))
      if (path === CANCEL_PATH) {
        expect(init?.method).toBe('POST')
        expect(parsedBody(init)).toEqual({})
        active = false
        return Promise.resolve(jsonResponse({ ok: true, cancelled: true }))
      }
      if (path === SETUP_PNPM_PATH) {
        expect(parsedBody(init)).toEqual({})
        pnpm = true
        return Promise.resolve(jsonResponse({ ok: true }))
      }
      throw new Error(`unexpected request: ${path}`)
    })

    render(<PluginMarketplaceView fetchFn={fetchMock as unknown as typeof fetch} />)
    await loadMarket()
    expect(await screen.findByText(/正在下载.*plugin-alpha/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '取消操作' }))
    await waitFor(() => expect(screen.getByText('已请求取消当前插件操作')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '刷新插件市场' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '初始化 pnpm' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: '初始化 pnpm' }))
    await waitFor(() => expect(screen.getByText('pnpm 环境初始化完成')).toBeInTheDocument())
  })
})
