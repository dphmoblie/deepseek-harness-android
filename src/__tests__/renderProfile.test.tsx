import React, { Profiler, useState } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, keepAlive, openSettingsPage, settings, shizuku } from './appTestHarness'

vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('../components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from '../App'

/**
 * 渲染测量夹具（登记册 §5.6-B「先用 Profiler 定位」）。
 *
 * 这个文件是**先于任何重构存在的测量台**，只回答一个问题：`App.tsx` 里有没有可避免的重渲染。
 * 它只用 React 自带的 `Profiler`，不引入任何第三方测量依赖，也不需要为了测量去改 `App.tsx`。
 *
 * ## 判据：「这次提交是不是白做的」
 *
 * `Profiler.onRender` 在 commit 的 layout 阶段被调用，此时 DOM 变更**已经**写进真实节点。
 * 于是在回调里读一次 `container.innerHTML`、与上一次提交的快照比对，就得到一个客观判据：
 *
 *   - DOM 变了  → 这次提交产出了用户可见的变化，属于**必要渲染**；
 *   - DOM 一字未变 → React 把整棵子树 reconcile + render 跑了一遍，却没产出任何可见变化，
 *     这次提交是**可避免的浪费**。
 *
 * `innerHTML` 是渲染输出的确定性函数，两次相同即输出相同 —— 这个判据不靠猜。
 * 夹具自检用例（第一个 `it`）专门钉住它：一次已知会改 DOM 的交互必须被记成 `domChanged=true`，
 * 否则说明 `onRender` 的调用时机与 DOM 变更之间错位，整套数字都不可信。
 *
 * ## 为什么桥接桩必须返回「值相同、引用不同」的快照
 *
 * 真机上三路轮询读到的东西绝大多数时候一个字都没变，但**引用每次都变**：
 * `src/platform/native.ts` 的每个读取都走 `NativeRuntime.*()`（Capacitor 桥，JSON 序列化），
 * 再过一遍 `src/platform/validation.ts` 的 `validateShizukuState` / `validateKeepAliveState` /
 * `validateOverlayBallState` —— 这三个校验函数都以**新的对象字面量**返回。
 * 所以真机上 `Object.is` 的免渲染短路永远不会命中。
 *
 * 共享夹具 `beforeEachAppTest` 里的写法恰好相反：`mockResolvedValue({ ...keepAlive })` 每次返回
 * **同一个引用**，`setState` 会命中 `Object.is` 短路、根本不重渲染 —— 那会让「轮询引起的白渲染」
 * 在测试里整体消失。因此本文件显式换成 `mockImplementation(() => Promise.resolve({ ...x }))`，
 * 把真机的引用语义搬进测量窗口。这一点是本文件与其余 `App.*.test.tsx` 的关键差别。
 */

interface CommitRecord {
  index: number
  /** 本次提交整棵树的渲染耗时（React 18 dev 构建下非零）。 */
  actualDuration: number
  /** 不做任何记忆化时的估算耗时，用于判断记忆化的理论收益。 */
  baseDuration: number
  /** 本次提交是否改变了可见 DOM。`false` 即「白做的提交」。 */
  domChanged: boolean
  domBytes: number
}

interface RenderProfile {
  reset(): void
  sinceReset(): CommitRecord[]
}

/** 把 `<App/>` 包进 `Profiler`，逐次提交记录耗时与「DOM 有没有变」。 */
function mountProfiledApp(): { profile: RenderProfile; container: HTMLDivElement } {
  const container = document.createElement('div')
  document.body.appendChild(container)
  let lastHtml: string | null = null
  const commits: CommitRecord[] = []
  let measuredFrom = 0

  const onRender = (
    _id: string,
    _phase: 'mount' | 'update' | 'nested-update',
    actualDuration: number,
    baseDuration: number,
  ): void => {
    // 用调用方自己的容器而不是 document.body：body 里还有测试框架追加的节点，
    // 把它们算进来会让「DOM 未变」永远不成立。
    const html = container.innerHTML
    commits.push({
      index: commits.length + 1,
      actualDuration,
      baseDuration,
      domChanged: html !== lastHtml,
      domBytes: html.length,
    })
    lastHtml = html
  }

  render(
    <Profiler id="app" onRender={onRender}>
      <App />
    </Profiler>,
    { container },
  )

  return {
    container,
    profile: {
      reset: () => {
        measuredFrom = commits.length
      },
      sinceReset: () => commits.slice(measuredFrom),
    },
  }
}

/** 打印一段测量结果：控制台输出即证据，不再额外落盘。 */
function report(label: string, rows: CommitRecord[]): void {
  const wasted = rows.filter(row => !row.domChanged)
  const total = rows.reduce((sum, row) => sum + row.actualDuration, 0)
  const wastedMs = wasted.reduce((sum, row) => sum + row.actualDuration, 0)
  const share = total === 0 ? 0 : Math.round((wastedMs / total) * 100)
  const table = rows
    .map(
      row =>
        `      #${row.index} actual=${row.actualDuration.toFixed(2)}ms base=${row.baseDuration.toFixed(2)}ms ` +
        `domChanged=${row.domChanged ? 'Y' : 'n'} bytes=${row.domBytes}`,
    )
    .join('\n')
  console.log(
    `\n[RENDER-PROFILE] ${label}\n` +
      `      commits=${rows.length} wasted(DOM 未变)=${wasted.length} ` +
      `actual 合计=${total.toFixed(2)}ms 其中白做=${wastedMs.toFixed(2)}ms (${share}%)\n${table}\n`,
  )
}

/** 让三路轮询读到「值相同、引用不同」的快照 —— 与真机桥接的引用语义一致。 */
function freshButEqualSnapshots(): void {
  bridge.getShizukuState.mockImplementation(() => Promise.resolve({ ...shizuku }))
  bridge.getKeepAliveState.mockImplementation(() => Promise.resolve({ ...keepAlive }))
  bridge.getOverlayBallState.mockImplementation(() =>
    Promise.resolve({ enabled: false, canDrawOverlays: true, serviceActive: false }),
  )
}

/** 推进假时钟，并把这次 tick 引起的 React 更新全部刷完。 */
async function tick(milliseconds: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(milliseconds)
  })
}

/**
 * 只在需要的用例里开假时钟，并保证异常路径也会还原。
 *
 * `shouldAdvanceTime: true` 是必需的：testing-library 的 `waitFor` 在本仓库里走真实计时器路径
 * （vitest 没开 globals，RTL 认不出 jest 假定时器），假时钟不随真实时间前进就会把 `waitFor` 挂死。
 * 这个坑登记册 5.6-C 的轮询用例里已经踩过一次，这里沿用同一写法。
 */
async function withFakeTimers(body: () => Promise<void>): Promise<void> {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  try {
    await body()
  } finally {
    vi.useRealTimers()
  }
}

describe('§5.6-B 渲染测量', () => {
  beforeEach(beforeEachAppTest)

  /**
   * 夹具自检：一次已知会改 DOM 的交互必须被记成 `domChanged=true`。
   * 这条**必须真实失败**的能力来自「把 onRender 的读取改成提前一帧」之类的错误改动；
   * 它是其余所有数字的前提，所以单独钉住。
   */
  it('夹具自检：纯导航的每一次提交都必须改变 DOM', async () => {
    await withFakeTimers(async () => {
      const { profile } = mountProfiledApp()
      // 先等外壳稳定在设置首页（自动启动会把视图推到这里），再开始计数：
      // 否则启动链的尾巴会被算进来，那属于「测量边界切早了」而不是「可避免的重渲染」。
      await screen.findByRole('heading', { name: '设置' })
      await tick(6_000)
      profile.reset()

      await openSettingsPage('诊断与日志')

      const rows = profile.sinceReset()
      report('夹具自检：设置首页 → 诊断与日志', rows)
      expect(rows.length).toBeGreaterThan(0)
      // 一次真实导航必须每一次提交都改动了 DOM。这条同时是「DOM 未变 = 白做」判据的对照组：
      // 如果判据坏了（例如恒为 true），这里就会挂。
      expect(rows.filter(row => !row.domChanged)).toHaveLength(0)
    })
  })

  /**
   * 冷启动到外壳稳定这一整段的提交表（**只测量，不下紧断言**）。
   *
   * 这一段里夹着启动链自己的异步回填（`startHarness` 的返回值、`openHarness` 里那次
   * 「后台状态稍后回填」的读取），因此提交次数受调度影响，不适合钉死具体数字；
   * 它的用处是看「尾部回填有没有产出 DOM 一字未变的提交」。
   */
  it('冷启动测量：挂载到外壳稳定在设置首页', async () => {
    await withFakeTimers(async () => {
      const { profile } = mountProfiledApp()
      await screen.findByRole('heading', { name: '设置' })
      await tick(6_000)
      report('冷启动：挂载 → 设置首页稳定', profile.sinceReset())
      expect(profile.sinceReset().length).toBeGreaterThan(0)
    })
  })

  /**
   * 主 CTA「打开 Harness」：`openHarness()` 里有一次 fire-and-forget 的后台状态回填，
   * 它是这条路径上唯一「与用户可见变化无关」的写入。这里量它值不值得做同值短路。
   */
  it('主 CTA 测量：在设置首页点「打开 Harness」', async () => {
    await withFakeTimers(async () => {
      const { profile } = mountProfiledApp()
      await screen.findByRole('heading', { name: '设置' })
      await tick(6_000)
      profile.reset()

      fireEvent.click(screen.getByRole('button', { name: '打开 Harness' }))
      await tick(2_000)

      report('主 CTA：点击「打开 Harness」', profile.sinceReset())
      expect(profile.sinceReset().length).toBeGreaterThan(0)
    })
  })

  /**
   * 钉住三种「同值更新」在 React 18 下的实际语义。
   * 这决定了修复该用哪种写法，所以它必须由测量（而不是记忆）给出答案。
   */
  it('React 语义探针：三种同值更新各自引起多少子树渲染', () => {
    const childRenders = { count: 0 }
    const initial = { n: 1 }
    let bump: React.Dispatch<React.SetStateAction<{ n: number }>> = () => undefined

    function Child({ value }: { value: { n: number } }) {
      childRenders.count += 1
      return <span data-testid="probe-child">{value.n}</span>
    }

    function Host() {
      const [value, setValue] = useState(initial)
      bump = setValue
      return <Child value={value} />
    }

    /**
     * 三种写法的差别只在 `setState` 的参数上，不需要等任何 promise：
     * 用同步 `act` 就能把这次更新刷完，也就不必引入 `async`。
     */
    const applyUpdate = (style: 'same-ref' | 'new-ref' | 'functional'): void => {
      act(() => {
        if (style === 'same-ref') bump(initial)
        else if (style === 'new-ref') bump({ n: 1 })
        else bump(previous => (previous.n === 1 ? previous : { n: 1 }))
      })
    }

    render(<Host />)
    const afterMount = childRenders.count

    applyUpdate('same-ref')
    const afterSameRef = childRenders.count
    applyUpdate('new-ref')
    const afterNewRef = childRenders.count
    applyUpdate('functional')
    const afterFunctional = childRenders.count

    console.log(
      `\n[REACT-SEMANTICS] 子树渲染次数：挂载=${afterMount} 传同一引用后=${afterSameRef}` +
        ` 传同值新引用后=${afterNewRef} 函数式更新返回旧引用后=${afterFunctional}\n`,
    )

    // 同一引用：React 直接短路，子树一次都不渲染。
    expect(afterSameRef).toBe(afterMount)
    // 同值但新引用：React 无从得知值没变，子树必须重渲染 —— 这正是轮询白渲染的成因。
    expect(afterNewRef).toBeGreaterThan(afterSameRef)
  })

  it('稳态轮询（设置→诊断与日志）：3 个空转 tick 的提交与耗时', async () => {
    await withFakeTimers(async () => {
      freshButEqualSnapshots()
      const { profile } = mountProfiledApp()

      await openSettingsPage('诊断与日志')
      await waitFor(() => expect(bridge.getKeepAliveState).toHaveBeenCalled())
      // 让挂载期与进页面时的那些读取全部落地，从稳态之后再开始数。
      await tick(6_000)
      const callsBefore = bridge.getKeepAliveState.mock.calls.length
      profile.reset()

      await tick(5_000)
      await tick(5_000)
      await tick(5_000)

      const rows = profile.sinceReset()
      const ticks = bridge.getKeepAliveState.mock.calls.length - callsBefore
      report(`设置→诊断与日志：3 个空转 tick（实际触发 ${ticks} 轮）`, rows)
      /*
       * 先钉住「轮询真的跑了」：没有这一条，下面的 0 次提交既可能是「修好了」，
       * 也可能是「轮询根本没启动」—— 后者是更严重的缺陷却会静默通过。
       */
      expect(ticks).toBe(3)
      // 三路快照值都没变，一次提交都不该发生。
      expect(rows).toHaveLength(0)
    })
  })

  it('稳态轮询（设置首页）：3 个空转 tick 的提交与耗时', async () => {
    await withFakeTimers(async () => {
      freshButEqualSnapshots()
      const { profile } = mountProfiledApp()

      await screen.findByRole('heading', { name: '设置' })
      await tick(6_000)
      const callsBefore = bridge.getKeepAliveState.mock.calls.length
      profile.reset()

      await tick(5_000)
      await tick(5_000)
      await tick(5_000)

      const rows = profile.sinceReset()
      const ticks = bridge.getKeepAliveState.mock.calls.length - callsBefore
      report(`设置首页：3 个空转 tick（实际触发 ${ticks} 轮）`, rows)
      expect(ticks).toBe(3)
      expect(rows).toHaveLength(0)
    })
  })

  it('稳态轮询（主视图）：3 个空转 tick 的提交与耗时', async () => {
    await withFakeTimers(async () => {
      freshButEqualSnapshots()
      // 关掉自动启动，外壳才会停在主视图（否则启动链会把视图推到设置首页）。
      bridge.getSettings.mockResolvedValue({ ...settings, autoLaunch: false })
      const { profile } = mountProfiledApp()

      await screen.findByText('Harness 对话')
      await tick(6_000)
      const callsBefore = bridge.getKeepAliveState.mock.calls.length
      profile.reset()

      await tick(5_000)
      await tick(5_000)
      await tick(5_000)

      const rows = profile.sinceReset()
      const ticks = bridge.getKeepAliveState.mock.calls.length - callsBefore
      report(`主视图：3 个空转 tick（实际触发 ${ticks} 轮）`, rows)
      expect(ticks).toBe(3)
      expect(rows).toHaveLength(0)
    })
  })

  it('设置草稿输入：3 次击键的提交与耗时（对照：属于必要渲染）', async () => {
    const { profile } = mountProfiledApp()

    await openSettingsPage('模型与密钥')
    // 密钥输入框只在选中某个供应商之后才出现（默认停在已配置的 deepseek），
    // 所以先切供应商再开始计数 —— 切换供应商只改「正在看什么」，不算草稿脏。
    fireEvent.change(screen.getByRole('combobox', { name: '供应商' }), { target: { value: 'openai' } })
    const input = await screen.findByLabelText(/OpenAI API Key/)
    profile.reset()

    fireEvent.change(input, { target: { value: 'sk-a' } })
    fireEvent.change(input, { target: { value: 'sk-ab' } })
    fireEvent.change(input, { target: { value: 'sk-abc' } })

    const rows = profile.sinceReset()
    report('设置草稿：3 次击键', rows)
    expect(rows.some(row => row.domChanged)).toBe(true)
  })

  it('设置区内切页：一次二级页切换旅程的提交与耗时（对照）', async () => {
    const { profile } = mountProfiledApp()

    await openSettingsPage('模型与密钥')
    profile.reset()

    for (const name of ['运行与后台', '终端与外观', 'Shizuku 与设备 Shell', '诊断与日志']) {
      fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
      await screen.findByRole('heading', { name: '设置' })
      await openSettingsPage(name)
    }

    const rows = profile.sinceReset()
    report('设置区：一次二级页切换旅程', rows)
    expect(rows.length).toBeGreaterThan(0)
  })
})
