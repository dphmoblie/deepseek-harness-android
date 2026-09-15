import { describe, expect, it } from 'vitest'
import { createBrowserBridge } from '../platform/browser'
import { bridge } from './appTestHarness'

/**
 * 共享测试夹具的桥接桩必须**覆盖桥的每一个方法**。
 *
 * 加这条守卫的直接原因是一次真实事故：给桥加 `setAppTheme` 时改了
 * `src/platform/{native,types,browser}.ts` 与 `src/theme.ts`，**却漏了这份夹具**。
 * 后果不是「少测了一个方法」，而是：`applyTheme()` 调了一个不存在的桩 →
 * 异常发生在 effect 阶段 → React **直接卸载整棵树** → 测试表现成「找不到任何元素」，
 * 与真正的界面缺陷几乎无法区分。当时提交前只跑了单文件测试，于是红着进了 main。
 *
 * 守卫的写法：拿**真实实现**（浏览器桥的工厂）的键集合当基准去比夹具，而不是维护一份
 * 手写清单——手写清单会在同一次改动里被一起漏掉，那就等于没有守卫。
 */
describe('共享测试夹具的桥接桩', () => {
  it('覆盖 RuntimeBridge 的每一个方法', () => {
    const real = Object.keys(createBrowserBridge()).sort()
    const mocked = new Set(Object.keys(bridge))
    const missing = real.filter(name => !mocked.has(name))
    expect(
      missing,
      `appTestHarness 的 bridge 缺少这些方法：${missing.join('、')}。` +
        '补上桩（通常是 vi.fn() + 一个 mockResolvedValue 默认值），否则用到它的界面会在 effect 阶段抛错并整棵树卸载。',
    ).toEqual([])
  })

  it('不多出桥里不存在的方法名', () => {
    // 反向也要查：夹具里留一个已经改名的旧方法，会让「测试通过但生产调用的是另一个名字」长期潜伏。
    const real = new Set(Object.keys(createBrowserBridge()))
    const extra = Object.keys(bridge).filter(name => !real.has(name)).sort()
    expect(extra, `appTestHarness 的 bridge 多出这些方法：${extra.join('、')}`).toEqual([])
  })
})
