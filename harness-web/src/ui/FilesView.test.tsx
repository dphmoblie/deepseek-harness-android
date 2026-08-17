// @vitest-environment jsdom

import '@testing-library/jest-dom/vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type * as WireModule from '../api/wire'

const wire = vi.hoisted(() => ({ callUnary: vi.fn() }))

vi.mock('../api/wire', async (importOriginal) => {
  const actual = await importOriginal<typeof WireModule>()
  return { ...actual, ...wire }
})

import { RpcFailure } from '../api/wire'
import { FilesView } from './FilesView'
import { validateDirectoryName } from './fileValidation'

const ROOT_LISTING = {
  path: '/home/user',
  home: '/home/user',
  crumbs: [
    { name: '/', path: '/', hidden: false },
    { name: 'home', path: '/home', hidden: false },
    { name: 'user', path: '/home/user', hidden: false },
  ],
  entries: [
    { name: 'project', path: '/home/user/project', hidden: false },
    { name: '.config', path: '/home/user/.config', hidden: true },
  ],
  truncated: false,
}

beforeEach(() => {
  vi.clearAllMocks()
  wire.callUnary.mockImplementation((_baseUrl: string, method: string, payload: unknown) => {
    if (method !== 'host.listDirectory') throw new Error(`Unexpected RPC: ${method}`)
    const path = (payload as { path?: string }).path
    if (path === '/home/user/project') {
      return Promise.resolve({
        ...ROOT_LISTING,
        path,
        crumbs: [...ROOT_LISTING.crumbs, { name: 'project', path, hidden: false }],
        entries: [],
      })
    }
    return Promise.resolve(ROOT_LISTING)
  })
})

afterEach(cleanup)

describe('FilesView directory contract', () => {
  it('将没有尾斜杠的协议条目作为目录进入，不调用文件读取 API', async () => {
    render(<FilesView onBack={vi.fn()} />)

    const project = await screen.findByRole('button', { name: '打开目录 project' })
    fireEvent.click(project)

    await waitFor(() => expect(wire.callUnary).toHaveBeenCalledWith(
      expect.any(String),
      'host.listDirectory',
      { path: '/home/user/project' },
      expect.anything(),
    ))
    const listCall = wire.callUnary.mock.calls.find((call) => call[1] === 'host.listDirectory' && (call[2] as { path?: unknown }).path === '/home/user/project')
    expect((listCall?.[3] as { signal?: unknown } | undefined)?.signal).toBeInstanceOf(AbortSignal)
    expect(await screen.findByText('该目录没有子目录')).toBeInTheDocument()
    expect(wire.callUnary.mock.calls.some((call) => call[1] === 'host.openPath')).toBe(false)
  })

  it('新建期间锁定操作且只提交一次，完成后刷新当前目录', async () => {
    let resolveCreate: ((value: unknown) => void) | undefined
    const pendingCreate = new Promise((resolve) => { resolveCreate = resolve })
    let listCalls = 0
    wire.callUnary.mockImplementation((_baseUrl: string, method: string) => {
      if (method === 'host.createDirectory') return pendingCreate
      if (method === 'host.listDirectory') {
        listCalls += 1
        return Promise.resolve(listCalls === 1 ? ROOT_LISTING : {
          ...ROOT_LISTING,
          entries: [...ROOT_LISTING.entries, { name: 'new-folder', path: '/home/user/new-folder', hidden: false }],
        })
      }
      throw new Error(`Unexpected RPC: ${method}`)
    })

    render(<FilesView onBack={vi.fn()} />)
    await screen.findByRole('button', { name: '打开目录 project' })
    fireEvent.click(screen.getByRole('button', { name: '新建目录' }))
    fireEvent.change(screen.getByRole('textbox', { name: '目录名' }), { target: { value: 'new-folder' } })
    const create = screen.getByRole('button', { name: '创建' })
    fireEvent.click(create)
    fireEvent.click(create)

    expect(screen.getByRole('main')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('button', { name: '创建中…' })).toBeDisabled()
    expect(wire.callUnary.mock.calls.filter((call) => call[1] === 'host.createDirectory')).toHaveLength(1)
    expect(screen.getByRole('button', { name: '打开目录 project' })).toBeDisabled()

    await act(async () => {
      resolveCreate?.({ path: '/home/user/new-folder' })
      await pendingCreate
    })

    expect(await screen.findByRole('button', { name: '打开目录 new-folder' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toHaveAttribute('aria-busy', 'false')
    expect(wire.callUnary.mock.calls.filter((call) => call[1] === 'host.listDirectory')).toHaveLength(2)
  })

  it('本地拒绝非法目录名，不发送创建 RPC', async () => {
    render(<FilesView onBack={vi.fn()} />)
    await screen.findByRole('button', { name: '打开目录 project' })
    fireEvent.click(screen.getByRole('button', { name: '新建目录' }))
    fireEvent.change(screen.getByRole('textbox', { name: '目录名' }), { target: { value: '../escape' } })
    fireEvent.click(screen.getByRole('button', { name: '创建' }))

    expect(screen.getByRole('alert')).toHaveTextContent('目录名不能包含斜杠或反斜杠')
    expect(wire.callUnary.mock.calls.some((call) => call[1] === 'host.createDirectory')).toBe(false)
  })

  it('目录 RPC 错误展示服务端原因、错误码并脱敏', async () => {
    wire.callUnary.mockRejectedValue(new RpcFailure({
      code: 'directory-unreadable',
      message: 'cannot list /private?token=sk-1234567890abcdef',
      details: {},
    }))

    render(<FilesView onBack={vi.fn()} />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('目录不可读')
    expect(alert).toHaveTextContent('cannot list /private')
    expect(alert).toHaveTextContent('错误代码：directory-unreadable')
    expect(alert).toHaveTextContent('[已隐藏]')
    expect(alert).not.toHaveTextContent('sk-1234567890abcdef')
  })
})

describe('validateDirectoryName', () => {
  it.each([
    ['', '目录名不能为空'],
    ['   ', '目录名不能为空'],
    [' name', '目录名首尾不能包含空白字符'],
    ['.', '目录名不能是 . 或 ..'],
    ['..', '目录名不能是 . 或 ..'],
    ['a/b', '目录名不能包含斜杠或反斜杠'],
    ['a\\b', '目录名不能包含斜杠或反斜杠'],
    [`bad${String.fromCharCode(0)}name`, '目录名不能包含控制字符'],
  ])('拒绝 %j', (name, message) => {
    expect(validateDirectoryName(name)).toEqual({ ok: false, message })
  })

  it('按 UTF-8 字节限制长度并允许普通隐藏目录名', () => {
    expect(validateDirectoryName('目录'.repeat(50))).toEqual({ ok: false, message: '目录名不能超过 255 字节' })
    expect(validateDirectoryName('.config')).toEqual({ ok: true, value: '.config' })
  })
})
