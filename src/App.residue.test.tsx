import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, readyState } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({ TerminalPanel: () => <div data-testid="terminal-panel" /> }))

import { App } from './App'

/**
 * 「运行时占用」卡片在版本管理页里的接线。
 *
 * 卡片自己渲染什么、按钮怎么确认，由 `components/RuntimeResidueCard.test.tsx` 管；这一层只钉两件事：
 * 卡片**确实被渲染进版本管理页**，而且真的调了桥。这条链路的接头（import 与渲染点）都在 `App.tsx` 里，
 * 接错了不会有别的用例发现——卡片组件测试全绿、界面看起来什么都没少。
 */
describe('版本管理：运行时占用卡片接线', () => {
  beforeEach(beforeEachAppTest)

  /** 进「版本管理」一级页：等启动链把视图推到设置首页，再点进去（与 App.runtime.test.tsx 同一口径）。 */
  async function openVersionsPage(): Promise<void> {
    await screen.findByRole('heading', { name: '设置' }, { timeout: 5_000 })
    fireEvent.click(await screen.findByRole('button', { name: /版本管理/ }, { timeout: 5_000 }))
    await screen.findByRole('heading', { name: '版本管理' }, { timeout: 5_000 })
  }

  it('版本管理页渲染运行时占用卡片，并读取真实残留量', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.getRuntimeResidue.mockResolvedValue({ count: 3, bytes: 2.5 * 1024 * 1024 * 1024, truncated: false })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    // 卡片只属于版本管理页：还在设置首页时就不该出现。
    expect(screen.queryByRole('heading', { name: '运行时占用' })).toBeNull()

    await openVersionsPage()

    expect(await screen.findByRole('heading', { name: '运行时占用' })).toBeVisible()
    expect(screen.getByText('可回收的残留：3 份，共 2.5 GB')).toBeVisible()
    expect(bridge.getRuntimeResidue).toHaveBeenCalledTimes(1)
  })

  it('没有残留时卡片如实说明，清理按钮不可点', async () => {
    bridge.getState.mockResolvedValue({ ...readyState })
    bridge.getRuntimeResidue.mockResolvedValue({ count: 0, bytes: 0, truncated: false })
    render(<App />)
    await waitFor(() => expect(bridge.openHarness).toHaveBeenCalledTimes(1))

    await openVersionsPage()

    expect(await screen.findByText('没有可回收的残留')).toBeVisible()
    expect(screen.getByRole('button', { name: '清理残留' })).toBeDisabled()
    expect(bridge.cleanRuntimeResidue).not.toHaveBeenCalled()
  })
})
