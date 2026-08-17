// @vitest-environment jsdom

import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SessionId, SessionSummary } from './api/types'
import type { SessionsController } from './state/sessions'
import { App } from './App'

const mocks = vi.hoisted(() => ({
  eventStart: vi.fn(),
  eventStop: vi.fn(),
  useSessions: vi.fn(),
}))

afterEach(cleanup)

vi.mock('./state/events', () => ({
  eventBus: {
    start: mocks.eventStart,
    stop: mocks.eventStop,
  },
}))

vi.mock('./state/sessions', () => ({
  useSessions: mocks.useSessions,
}))

vi.mock('./ui/ChatView', () => ({
  ChatView: (props: { sessionId: SessionId; onOpenMenu: () => void }) => (
    <section>
      <span data-testid="chat-session">{props.sessionId}</span>
      <button type="button" onClick={props.onOpenMenu}>打开菜单</button>
    </section>
  ),
}))

vi.mock('./ui/FilesView', () => ({ FilesView: () => null }))
vi.mock('./ui/SettingsView', () => ({ SettingsView: () => null }))
vi.mock('./ui/TasksView', () => ({ TasksView: () => null }))

function session(sessionId: SessionId, title: string, updatedAt: number): SessionSummary {
  return {
    sessionId,
    updatedAt,
    running: false,
    blank: false,
    projections: { asOfSeq: 1, values: { title } },
  }
}

function controller(overrides: Partial<SessionsController> = {}): SessionsController {
  return {
    items: [
      session('active-a', '当前会话', 30),
      session('active-b', '备用会话', 20),
      session('archived-a', '旧会话', 40),
    ],
    archived: ['archived-a'],
    loading: false,
    error: null,
    reload: vi.fn().mockResolvedValue(undefined),
    createSession: vi.fn().mockResolvedValue('created-session'),
    renameSession: vi.fn().mockResolvedValue(undefined),
    archiveSession: vi.fn().mockResolvedValue(true),
    dismissError: vi.fn(),
    ...overrides,
  }
}

async function openDrawer(): Promise<void> {
  expect(await screen.findByTestId('chat-session')).toHaveTextContent('active-a')
  fireEvent.click(screen.getByRole('button', { name: '打开菜单' }))
  expect(screen.getByRole('dialog', { name: '会话与功能' })).toBeInTheDocument()
}

describe('conversation navigation drawer', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('offers a visible row menu and can open an archived session', async () => {
    mocks.useSessions.mockReturnValue(controller())
    render(<App />)
    await openDrawer()

    expect(screen.getByRole('button', { name: '当前会话的操作' })).toBeVisible()
    fireEvent.click(screen.getByText('已归档'))
    fireEvent.click(screen.getByRole('button', { name: '旧会话 已归档会话' }))

    expect(screen.getByTestId('chat-session')).toHaveTextContent('archived-a')
    expect(screen.queryByRole('dialog', { name: '会话与功能' })).not.toBeInTheDocument()
  })

  it('trims a valid rename and blocks empty or over-budget titles', async () => {
    const sessions = controller()
    mocks.useSessions.mockReturnValue(sessions)
    render(<App />)
    await openDrawer()

    fireEvent.click(screen.getByRole('button', { name: '当前会话的操作' }))
    const actions = screen.getByRole('dialog', { name: '会话操作' })
    fireEvent.click(within(actions).getByRole('button', { name: '重命名' }))

    const input = within(actions).getByRole('textbox', { name: '会话标题' })
    const confirm = within(actions).getByRole('button', { name: '确定' })
    expect(input).toHaveAttribute('maxlength', '80')

    fireEvent.change(input, { target: { value: '   ' } })
    expect(confirm).toBeDisabled()

    fireEvent.change(input, { target: { value: '测'.repeat(27) } })
    expect(within(actions).getByRole('alert')).toHaveTextContent('80 个 UTF-8 字节')
    expect(confirm).toBeDisabled()

    fireEvent.change(input, { target: { value: '\u200b' } })
    expect(within(actions).getByRole('alert')).toHaveTextContent('不可见字符')
    expect(confirm).toBeDisabled()

    fireEvent.change(input, { target: { value: '  新标题  ' } })
    fireEvent.click(confirm)
    await waitFor(() => expect(sessions.renameSession).toHaveBeenCalledWith('active-a', '新标题'))
  })

  it('switches to another active session after archiving the current one', async () => {
    const sessions = controller()
    mocks.useSessions.mockReturnValue(sessions)
    render(<App />)
    await openDrawer()

    fireEvent.click(screen.getByRole('button', { name: '当前会话的操作' }))
    const actions = screen.getByRole('dialog', { name: '会话操作' })
    fireEvent.click(within(actions).getByRole('button', { name: '归档会话' }))

    await waitFor(() => expect(sessions.archiveSession).toHaveBeenCalledWith('active-a'))
    expect(screen.getByTestId('chat-session')).toHaveTextContent('active-b')
    expect(sessions.createSession).not.toHaveBeenCalled()
  })

  it('creates a replacement when the archived session was the only active one', async () => {
    const sessions = controller({
      items: [session('active-a', '当前会话', 30), session('archived-a', '旧会话', 20)],
      archived: ['archived-a'],
    })
    mocks.useSessions.mockReturnValue(sessions)
    render(<App />)
    await openDrawer()

    fireEvent.click(screen.getByRole('button', { name: '当前会话的操作' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: '会话操作' })).getByRole('button', { name: '归档会话' }))

    await waitFor(() => expect(sessions.createSession).toHaveBeenCalledOnce())
    expect(screen.getByTestId('chat-session')).toHaveTextContent('created-session')
  })

  it('keeps the current session when archiving fails', async () => {
    const sessions = controller({ archiveSession: vi.fn().mockResolvedValue(false) })
    mocks.useSessions.mockReturnValue(sessions)
    render(<App />)
    await openDrawer()

    fireEvent.click(screen.getByRole('button', { name: '当前会话的操作' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: '会话操作' })).getByRole('button', { name: '归档会话' }))

    await waitFor(() => expect(sessions.archiveSession).toHaveBeenCalledWith('active-a'))
    expect(screen.getByTestId('chat-session')).toHaveTextContent('active-a')
    expect(sessions.createSession).not.toHaveBeenCalled()
  })
})
