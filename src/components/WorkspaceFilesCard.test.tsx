import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RuntimeWorkspaceFileList } from '../platform/types'

/**
 * 假桥：只实现这张卡片用到的方法。
 *
 * `vi.mock` 会被提升到文件顶部，工厂里引用模块级 `const` 会取到未初始化的值，
 * 所以桩本身用 `vi.hoisted` 一起提前建好（与 SessionManager.test.tsx 同一口径）。
 */
const { listFiles, shareFile, openFile } = vi.hoisted(() => ({
  listFiles: vi.fn<() => Promise<RuntimeWorkspaceFileList>>(),
  shareFile: vi.fn<(path: string) => Promise<void>>(),
  openFile: vi.fn<(path: string) => Promise<void>>(),
}))
vi.mock('../platform/native', () => ({
  runtimeBridge: {
    listRuntimeWorkspaceFiles: listFiles,
    shareRuntimeWorkspaceFile: shareFile,
    openRuntimeWorkspaceFile: openFile,
  },
}))

import { WorkspaceFilesCard } from './WorkspaceFilesCard'

describe('工作区文件卡片', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
    window.localStorage.setItem('dsh-mobile-language-v1', 'zh-CN')
    listFiles.mockResolvedValue({ files: ['reports/summary.md', 'result.json'], truncated: false })
    shareFile.mockResolvedValue(undefined)
    openFile.mockResolvedValue(undefined)
  })

  it('点「读取工作区文件」才读列表：未读之前不冒充「工作区是空的」', async () => {
    render(<WorkspaceFilesCard />)

    expect(screen.getByRole('heading', { name: '工作区文件' })).toBeInTheDocument()
    expect(screen.getByText('点「读取工作区文件」查看工作区里有哪些文件。')).toBeInTheDocument()
    expect(screen.queryByText('工作区里还没有文件。')).not.toBeInTheDocument()
    expect(listFiles).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '读取工作区文件' }))

    expect(await screen.findByText('reports/summary.md')).toBeInTheDocument()
    expect(screen.getByText('result.json')).toBeInTheDocument()
    expect(listFiles).toHaveBeenCalledTimes(1)
  })

  it('StrictMode 重挂载后仍能完成读取', async () => {
    render(<StrictMode><WorkspaceFilesCard /></StrictMode>)

    fireEvent.click(screen.getByRole('button', { name: '读取工作区文件' }))

    expect(await screen.findByText('reports/summary.md')).toBeInTheDocument()
  })

  it('分享与打开都把原生给的相对路径原样回传', async () => {
    render(<WorkspaceFilesCard />)
    fireEvent.click(screen.getByRole('button', { name: '读取工作区文件' }))
    await screen.findByText('reports/summary.md')

    fireEvent.click(screen.getByRole('button', { name: '分享工作区文件 reports/summary.md' }))
    await waitFor(() => expect(shareFile).toHaveBeenCalledWith('reports/summary.md'))

    fireEvent.click(screen.getByRole('button', { name: '打开工作区文件 result.json' }))
    await waitFor(() => expect(openFile).toHaveBeenCalledWith('result.json'))
  })

  it('读不到时如实报读不到，不退成「没有文件」', async () => {
    listFiles.mockRejectedValue(new Error('系统不支持安全访问工作区文件'))
    render(<WorkspaceFilesCard />)

    fireEvent.click(screen.getByRole('button', { name: '读取工作区文件' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('读不到工作区文件')
    expect(alert).toHaveTextContent('系统不支持安全访问工作区文件')
    expect(screen.queryByText('工作区里还没有文件。')).not.toBeInTheDocument()
  })

  it('空列表是「还没有文件」，截断则如实说明只列了前几个', async () => {
    listFiles.mockResolvedValueOnce({ files: [], truncated: false })
    const { unmount } = render(<WorkspaceFilesCard />)
    fireEvent.click(screen.getByRole('button', { name: '读取工作区文件' }))
    expect(await screen.findByText('工作区里还没有文件。')).toBeInTheDocument()
    unmount()

    listFiles.mockResolvedValue({ files: ['a.txt', 'b.txt'], truncated: true })
    render(<WorkspaceFilesCard />)
    fireEvent.click(screen.getByRole('button', { name: '读取工作区文件' }))
    expect(await screen.findByText('文件较多，只列出前 2 个。')).toBeInTheDocument()
  })

  it('分享失败时把原生说明显示出来，并把按钮恢复可用', async () => {
    shareFile.mockRejectedValue(new Error('工作区文件不可用'))
    render(<WorkspaceFilesCard />)
    fireEvent.click(screen.getByRole('button', { name: '读取工作区文件' }))
    await screen.findByText('result.json')

    const shareButton = screen.getByRole('button', { name: '分享工作区文件 result.json' })
    fireEvent.click(shareButton)

    expect(await screen.findByText('工作区文件操作失败')).toBeInTheDocument()
    expect(screen.getByText('工作区文件不可用')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: '分享工作区文件 result.json' })).toBeEnabled())
  })
})
