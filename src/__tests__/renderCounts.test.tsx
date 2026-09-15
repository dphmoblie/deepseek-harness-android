import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, keepAlive, openSettingsPage, settings, shizuku } from './appTestHarness'

/**
 * 逐组件统计渲染次数与「props 身份有没有变」（登记册 §5.6-B 的定位测量）。
 *
 * ## 为什么要拦截 JSX 运行时
 *
 * `App.tsx` 里的十几个视图组件都是**模块内局部函数**，没有导出，也不接受任何注入点，
 * 所以从测试侧无法直接数到「`SettingsScreen` 这次提交渲染了几次」。React 自带的
 * `Profiler` 只能给出一棵子树的提交耗时，给不出**逐组件**的渲染次数。
 *
 * 本文件用的办法是把 JSX 运行时包一层：元素类型若是函数组件，就换成它在
 * `WeakMap` 里缓存过的稳定代理，代理里先计数再调用原函数。
 *
 * `WeakMap` 缓存是必须的：如果每次创建元素都现造一个包装函数，React 会认为
 * 「组件类型变了」，于是**卸载再挂载**整棵子树 —— 那测到的就不是重渲染而是重建，
 * 数字会彻底失真。按原函数缓存保证同一组件永远拿到同一个包装，协调语义不变。
 *
 * `memo` / `forwardRef` 的返回值是**对象**而不是函数（`lucide-react` 的图标就属于这一类），
 * 一律跳过 —— 它们本来就不是「每次父组件渲染都会重跑的普通函数组件」。
 *
 * ## 这个文件测的两件事
 *
 * 1. **渲染次数**：空转轮询下，当前挂载的视图子树被渲染了几次。理想是 **0 次**。
 * 2. **props 身份**：每次渲染里，哪些 props 的引用变了。
 *    这直接回答「给视图组件套 `React.memo` 有没有用」—— 如果每次渲染都有一批
 *    内联箭头函数换了身份，`memo` 的浅比较**必然全部失败**，加了等于没加。
 *    自检用例把这一点也钉成断言，免得它只是一次性的观察。
 *
 * 本文件的包装会给每次元素创建加一层开销，所以**耗时数字只看 `renderProfile.test.tsx`**，
 * 这里只看次数。
 */

interface ComponentStat {
  renders: number
  /** prop 名 → 相对上一次渲染「引用变了」的累计次数。 */
  changed: Map<string, number>
}

const probe = vi.hoisted(() => {
  const stats = new Map<string, ComponentStat>()
  const previousProps = new Map<string, Record<string, unknown>>()
  const baseline = new Map<string, { renders: number; changed: Map<string, number> }>()
  const wrappers = new WeakMap<object, unknown>()

  /**
   * 包装一个函数组件类型。
   *
   * 直接以普通函数调用原组件（而不是 `createElement`）：React 已经把 dispatcher 装好了，
   * 组件里照样能正常用 hook —— 这正是 React 调用函数组件本体的方式。
   */
  function wrap(original: (...args: unknown[]) => unknown): unknown {
    const cached = wrappers.get(original)
    if (cached !== undefined) return cached
    const wrapper = function instrumented(this: unknown, ...args: unknown[]): unknown {
      const props = (args[0] ?? {}) as Record<string, unknown>
      const name = original.name === '' ? '<anonymous>' : original.name
      let stat = stats.get(name)
      if (stat === undefined) {
        stat = { renders: 0, changed: new Map() }
        stats.set(name, stat)
      }
      stat.renders += 1
      const before = previousProps.get(name)
      if (before !== undefined) {
        for (const key of Object.keys(props)) {
          if (!Object.is(before[key], props[key])) {
            stat.changed.set(key, (stat.changed.get(key) ?? 0) + 1)
          }
        }
      }
      previousProps.set(name, props)
      return original.apply(this, args)
    }
    // 保留原函数名，报错与 DevTools 里才认得出是哪个组件。
    Object.defineProperty(wrapper, 'name', { value: original.name, configurable: true })
    wrappers.set(original, wrapper)
    return wrapper
  }

  /** 通用元素工厂包装：函数类型的 type 换成稳定代理，其余原样透传。 */
  function instrument(original: (...args: unknown[]) => unknown) {
    return function instrumentedFactory(...args: unknown[]): unknown {
      if (typeof args[0] === 'function') args[0] = wrap(args[0] as (...a: unknown[]) => unknown)
      return original(...args)
    }
  }

  /** 相对最近一次 `reset()` 的渲染次数。 */
  function rendersSince(name: string): number {
    const stat = stats.get(name)
    if (stat === undefined) return 0
    return stat.renders - (baseline.get(name)?.renders ?? 0)
  }

  /** 相对最近一次 `reset()` 的「某 prop 引用变化」次数。 */
  function changesSince(name: string, prop: string): number {
    const stat = stats.get(name)
    if (stat === undefined) return 0
    return (stat.changed.get(prop) ?? 0) - (baseline.get(name)?.changed.get(prop) ?? 0)
  }

  return {
    wrap,
    instrument,
    rendersSince,
    changesSince,
    /** 记下当前计数作为基线：此后 `rendersSince` 只反映这段窗口里的渲染。 */
    reset(): void {
      baseline.clear()
      for (const [name, stat] of stats) {
        baseline.set(name, { renders: stat.renders, changed: new Map(stat.changed) })
      }
      // 同时丢掉上一次 props 对照：否则「窗口内第一次渲染」会拿去和重置前的 props 比，
      // 让「引用变化次数」比窗口内的渲染次数还多，断言无法写成确定值。
      previousProps.clear()
    },
    /** 打印关心的组件：窗口内渲染次数 + 每个 prop 的引用变化次数。 */
    report(label: string, only: readonly string[]): void {
      const lines: string[] = []
      for (const name of only) {
        if (!stats.has(name)) continue
        const changed = [...(stats.get(name)?.changed ?? [])]
          .map(([key, value]) => [key, value - (baseline.get(name)?.changed.get(key) ?? 0)] as const)
          .filter(([, value]) => value > 0)
          .sort((left, right) => right[1] - left[1])
          .map(([key, value]) => `${key}=${value}`)
          .join(' ')
        lines.push(`      ${name}: renders=${rendersSince(name)} 引用变化的 props → ${changed === '' ? '（无）' : changed}`)
      }
      console.log(`\n[RENDER-COUNT] ${label}\n${lines.join('\n')}\n`)
    },
  }
})

vi.mock('react/jsx-dev-runtime', async importOriginal => {
  const actual = await importOriginal<Record<string, unknown>>()
  const patched: Record<string, unknown> = { ...actual }
  for (const key of ['jsxDEV', 'jsx', 'jsxs']) {
    if (typeof actual[key] === 'function') {
      patched[key] = probe.instrument(actual[key] as (...args: unknown[]) => unknown)
    }
  }
  return patched
})

vi.mock('react/jsx-runtime', async importOriginal => {
  const actual = await importOriginal<Record<string, unknown>>()
  const patched: Record<string, unknown> = { ...actual }
  for (const key of ['jsx', 'jsxs']) {
    if (typeof actual[key] === 'function') {
      patched[key] = probe.instrument(actual[key] as (...args: unknown[]) => unknown)
    }
  }
  return patched
})

vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('../components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from '../App'

/** 关心这些组件：当前挂载的视图 + 外壳里始终存在的部分。 */
const WATCHED = [
  'SettingsScreen',
  'SettingsHomeScreen',
  'ConversationScreen',
  'DiagnosticLogPanel',
  'HarnessLogPanel',
  'RuntimeSelfCheckPanel',
  'Brand',
  'PhaseBadge',
] as const

/** 与真机一致：值相同、引用不同。 */
function freshButEqualSnapshots(): void {
  bridge.getShizukuState.mockImplementation(() => Promise.resolve({ ...shizuku }))
  bridge.getKeepAliveState.mockImplementation(() => Promise.resolve({ ...keepAlive }))
  bridge.getOverlayBallState.mockImplementation(() =>
    Promise.resolve({ enabled: false, canDrawOverlays: true, serviceActive: false }),
  )
}

async function tick(milliseconds: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(milliseconds)
  })
}

describe('§5.6-B 逐组件渲染计数', () => {
  beforeEach(beforeEachAppTest)

  /**
   * 拦截器自检 + 「`React.memo` 为什么没用」的证据。
   *
   * 一次真实导航必须数得到渲染（否则所有计数都是 0，断言会「永远通过」），
   * 同时把「视图组件的 props 里有一批内联回调每次渲染都换身份」钉成断言 ——
   * 这正是「只给视图组件套 `React.memo` 不会有任何效果」的原因。
   */
  it('拦截器自检：真实交互数得到渲染，且内联回调 prop 每次都换身份', async () => {
    render(<App />)
    await openSettingsPage('诊断与日志')
    probe.reset()

    // 一次真实的状态变化（切换采集开关）：App 重渲染，当前设置页跟着重渲染。
    const toggle = await screen.findByRole('switch', { name: /收集诊断日志/ })
    await waitFor(() => expect(toggle).toBeEnabled())
    fireEvent.click(toggle)
    await waitFor(() => expect(screen.getByRole('switch', { name: /收集诊断日志/ })).toBeChecked())

    probe.report('自检：诊断页切换采集开关', WATCHED)
    const renders = probe.rendersSince('SettingsScreen')
    expect(renders).toBeGreaterThan(1)
    // 第二次渲染起，这些**内联箭头函数** prop 的引用每一次都不同：
    // `memo` 的浅比较因此必然失败，只要还有它们在，套 `memo` 就等于没套。
    expect(probe.changesSince('SettingsScreen', 'onBack')).toBe(renders - 1)
    expect(probe.changesSince('SettingsScreen', 'onReloadSettings')).toBe(renders - 1)
    /*
     * 对照组：走 `useCallback` 的 prop 引用是**稳的**（`onSave={saveSettings}`）。
     * 这条断言是为了把结论说准：问题不是「memo 一律无效」，而是
     * 「同一个组件上内联箭头与稳定回调混在一起，浅比较照样每次都失败」。
     */
    expect(probe.changesSince('SettingsScreen', 'onSave')).toBe(0)
  })

  it('空转轮询（设置→诊断与日志）：3 个 tick 让视图子树渲染多少次', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      freshButEqualSnapshots()
      render(<App />)
      await openSettingsPage('诊断与日志')
      await waitFor(() => expect(bridge.getKeepAliveState).toHaveBeenCalled())
      await tick(6_000)

      // 非空性前提：进页面这一段里这些组件确实被数到了（拦截器在工作、视图确实挂着）。
      expect(probe.rendersSince('SettingsScreen')).toBeGreaterThan(0)
      probe.reset()
      await tick(5_000)
      await tick(5_000)
      await tick(5_000)

      probe.report('设置→诊断与日志：3 个空转 tick', WATCHED)
      // 三路快照值都没变 ⇒ 当前视图子树一次都不该渲染。
      for (const name of WATCHED) expect(probe.rendersSince(name)).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('空转轮询（设置首页）：3 个 tick 让视图子树渲染多少次', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      freshButEqualSnapshots()
      render(<App />)
      await screen.findByRole('heading', { name: '设置' })
      await tick(6_000)

      expect(probe.rendersSince('SettingsHomeScreen')).toBeGreaterThan(0)
      probe.reset()
      await tick(5_000)
      await tick(5_000)
      await tick(5_000)

      probe.report('设置首页：3 个空转 tick', WATCHED)
      for (const name of WATCHED) expect(probe.rendersSince(name)).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('空转轮询（主视图）：3 个 tick 让视图子树渲染多少次', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      freshButEqualSnapshots()
      // 关掉自动启动，外壳才会停在主视图（否则启动链会把视图推到设置首页）。
      bridge.getSettings.mockResolvedValue({ ...settings, autoLaunch: false })
      render(<App />)
      // 主视图标题随运行阶段变化（准备运行环境 / 正在进入对话 / 更新运行环境），
      // 用固定不动的眉标题做锚点，避免把断言押在阶段文案上。
      await screen.findByText('Harness 对话')
      await tick(6_000)

      expect(probe.rendersSince('ConversationScreen')).toBeGreaterThan(0)
      probe.reset()
      await tick(5_000)
      await tick(5_000)
      await tick(5_000)

      probe.report('主视图：3 个空转 tick', WATCHED)
      for (const name of WATCHED) expect(probe.rendersSince(name)).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })
})
