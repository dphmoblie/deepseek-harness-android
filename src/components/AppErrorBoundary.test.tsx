import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { LANGUAGE_STORAGE_KEY } from '../i18n'

const bridge = vi.hoisted(() => ({ shareDiagnosticLog: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))

import { AppErrorBoundary } from './AppErrorBoundary'

/** 一个必然在渲染期抛错的子组件——错误边界要处理的正是这种错误。 */
function Exploding(): never {
  throw new Error('渲染期故障')
}

beforeEach(() => {
  bridge.shareDiagnosticLog.mockReset()
  window.localStorage.clear()
  // 渲染期的 console.error 会被 React 与边界各自打一次，测试里静音以免噪声。
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

it('正常子树原样渲染，不显示错误屏', () => {
  render(<AppErrorBoundary><p>正常内容</p></AppErrorBoundary>)
  expect(screen.getByText('正常内容')).toBeTruthy()
  expect(screen.queryByRole('alert')).toBeNull()
})

it('子树渲染失败时显示错误屏，并保留重新载入入口', () => {
  render(<AppErrorBoundary><Exploding /></AppErrorBoundary>)
  const alert = screen.getByRole('alert')
  expect(alert).toHaveTextContent('页面暂时无法显示')
  // 重新载入必须始终可用：没有它用户只能杀进程。
  expect(screen.getByRole('button', { name: '重新载入' })).toBeTruthy()
  // 也不能只剩一个按钮：诊断日志入口是这块界面存在的第二个理由。
  expect(screen.getByRole('button', { name: '导出诊断日志' })).toBeTruthy()
})

it('导出诊断日志：成功后如实显示文件名', async () => {
  bridge.shareDiagnosticLog.mockResolvedValue({
    enabled: true,
    retentionDays: 7,
    fileCount: 1,
    totalBytes: 10,
    lastEntryAtMillis: 1,
    fileName: 'dsh-diagnostic-2026-09-15.log',
    exportedBytes: 10,
  })
  render(<AppErrorBoundary><Exploding /></AppErrorBoundary>)
  fireEvent.click(screen.getByRole('button', { name: '导出诊断日志' }))
  await waitFor(() => expect(bridge.shareDiagnosticLog).toHaveBeenCalledTimes(1))
  expect(await screen.findByRole('status')).toHaveTextContent('dsh-diagnostic-2026-09-15.log')
})

it('导出失败时明确报错，不静默当成成功', async () => {
  bridge.shareDiagnosticLog.mockRejectedValue(new Error('EXPORT_FAILED'))
  render(<AppErrorBoundary><Exploding /></AppErrorBoundary>)
  fireEvent.click(screen.getByRole('button', { name: '导出诊断日志' }))
  expect(await screen.findByText('导出诊断日志失败，请重试。')).toBeTruthy()
  // 失败后按钮要能再试一次，而不是永久禁用。
  expect(screen.getByRole('button', { name: '导出诊断日志' })).not.toBeDisabled()
})

it('崩溃界面的文案跟随应用语言，不写死中文', () => {
  window.localStorage.setItem(LANGUAGE_STORAGE_KEY, 'en')
  render(<AppErrorBoundary><Exploding /></AppErrorBoundary>)
  expect(screen.getByRole('button', { name: 'Reload' })).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Export diagnostic log' })).toBeTruthy()
})
