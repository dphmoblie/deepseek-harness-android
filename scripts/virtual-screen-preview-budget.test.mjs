// 副屏预览的性能预算契约：这些数字是「不卡不烫、不无限吃内存」的底线，改动时必须有人
// 明确地改这一处断言，而不是悄悄漂移。只看源码切片，不需要设备也不需要 android.*。
//
// 预算覆盖四项：
//   1. 拍子：档位采集间隔不得低于 4ms 下限，延迟不得为 0（0 会变成忙轮询）；
//   2. 采样：每帧最多 96×128 个 Int（约 48KB），且复用同一块行缓冲，不每帧重新分配；
//   3. 线程：预览只允许「取帧」「触摸」两个单线程执行器，且只在空值守卫里创建；
//   4. 缓冲：同时在手的硬件缓冲只有「已显示」与「在途」各一块；
//   5. 置前：小窗置前有 1200ms 节流，避免反复 removeView/addView。
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const source = name => readFile(new URL(`../android/app/src/main/java/io/deepseekharness/mobile/virtualscreen/${name}`, import.meta.url), 'utf8')

const policy = await source('VirtualScreenPolicy.kt')
const service = await source('VirtualScreenService.kt')
// 悬浮小窗的生命周期（含置前节流）已随第 4 步抽取搬进 VirtualScreenOverlayMachine：
// 常量仍留在服务里、构造机器时传进去，判定本身在机器里。断言的意思不变 —— 1200 毫秒内必须提前返回。
const machine = await source('VirtualScreenOverlayMachine.kt')

test('frame cadence stays inside the documented budget table and never busy-polls', () => {
  const table = policy.slice(policy.indexOf('val FRAME_MODES'), policy.indexOf('虚拟显示器请求的合成刷新率'))
  const pairs = [...table.matchAll(/"([a-z0-9]+)" to (\d+)/gu)].map(match => ({ mode: match[1], interval: Number(match[2]) }))
  assert.equal(pairs.length, 10, `档位表必须恰好 10 档：${JSON.stringify(pairs)}`)

  const floor = policy.match(/const val PREVIEW_PULL_FLOOR_MILLIS = (\d+)/u)
  assert.equal(floor?.[1], '4', '取帧下限必须显式写成 4ms')

  for (const { mode, interval } of pairs) {
    assert.ok(interval >= 4, `${mode} 的采集间隔 ${interval}ms 低于 4ms 下限，会变成连续取帧`)
    assert.ok(interval <= 1000, `${mode} 的采集间隔 ${interval}ms 超过 1 秒，实时预览失去意义`)
  }

  assert.match(policy, /fun previewPullInterval\(mode: String\): Int = maxOf\(PREVIEW_PULL_FLOOR_MILLIS, frameInterval\(mode\)\)/u)
  // 延迟必须扣掉本拍耗时，且至少 1ms：0 会让 Handler 立刻重跑，等于忙轮询。
  assert.match(
    policy,
    /fun previewPullDelay\(mode: String, elapsedMillis: Long\): Long =\s*\(previewPullInterval\(mode\)\.toLong\(\) - elapsedMillis\.coerceAtLeast\(0L\)\)\.coerceAtLeast\(1L\)/u,
  )
})

test('per-frame sampling reuses one row buffer and stays within the 96x128 grid', () => {
  const rows = Number(policy.match(/const val PREVIEW_SAMPLE_ROWS = (\d+)/u)?.[1])
  const columns = Number(policy.match(/const val PREVIEW_SAMPLE_COLUMNS = (\d+)/u)?.[1])
  assert.ok(rows > 0 && columns > 0, '采样网格常量必须存在')
  assert.ok(
    rows * columns <= 96 * 128,
    `采样网格 ${rows}×${columns} 超过 96×128（每帧约 48KB Int，超出即放弃「采样只用于变化检测」的前提）`,
  )

  const start = service.indexOf('private fun sampleFrame(')
  assert.ok(start > 0, 'sampleFrame 必须存在')
  const sample = service.slice(start, service.indexOf('\n    private val tick', start))
  // 每个采样行只调一次 getPixels：逐像素 JNI 调用是这条路径最大的开销。
  assert.equal((sample.match(/getPixels\(/gu) ?? []).length, 1, 'sampleFrame 每个采样行只允许一次 getPixels')
  assert.match(sample, /bitmap\.getPixels\(line, 0, bitmap\.width, 0, row, bitmap\.width, 1\)/u)
  // 行缓冲复用（宽度没变大就不重新分配），采样数组按采样网格尺寸分配，与整屏像素数无关。
  assert.match(sample, /rowPixels\.takeIf \{ it\.size >= bitmap\.width \} \?: IntArray\(bitmap\.width\)\.also \{ rowPixels = it \}/u)
  assert.match(sample, /IntArray\(rows\.size \* columns\.size\)/u)
  assert.equal((sample.match(/IntArray\(/gu) ?? []).length, 3, '只允许三处 IntArray：空网格兜底、行缓冲、采样数组')
})

test('the preview owns exactly two single-thread executors, both created behind null guards', () => {
  // 服务级那一处执行器（:44）承担的是别的重活，不计入预览预算；ShellVirtualScreen 的
  // newFixedThreadPool(2) 属于截图编码子系统，同样是独立预算。
  const created = service.match(/Executors\.newSingleThreadExecutor\(\)/gu) ?? []
  assert.equal(created.length, 3, '整文件只允许三处单线程执行器：服务级 1 处 + 预览「取帧/触摸」各 1 处')
  assert.equal(
    (service.match(/if \(executor == null\) executor = Executors\.newSingleThreadExecutor\(\)/gu) ?? []).length,
    1,
    '取帧执行器必须在空值守卫里创建，重挂时不能重复建池',
  )
  assert.equal(
    (service.match(/if \(touchExecutor == null\) touchExecutor = Executors\.newSingleThreadExecutor\(\)/gu) ?? []).length,
    1,
    '触摸执行器必须在空值守卫里创建，重挂时不能重复建池',
  )
  assert.doesNotMatch(service, /newCachedThreadPool|newFixedThreadPool|newScheduledThreadPool/u)
})

test('at most one displayed and one in-flight hardware buffer are held at a time', () => {
  const buffers = service.match(/var (fetchedHardware|displayedHardware): android\.hardware\.HardwareBuffer\?/gu) ?? []
  assert.equal(buffers.length, 2, `硬件缓冲只允许 fetchedHardware 与 displayedHardware 两个：${JSON.stringify(buffers)}`)
  assert.doesNotMatch(
    service,
    /(MutableList|ArrayList|ArrayDeque|Array)<[^>]*HardwareBuffer/u,
    '不允许把硬件缓冲收进集合：收进集合就意味着「在途帧」数量不再有上界',
  )
})

test('bringing the preview overlay to the front is throttled instead of rebuilt on every tap', () => {
  assert.match(service, /private const val FRONT_THROTTLE_MILLIS = 1200L/u)
  // 常量的唯一来源仍是服务；机器从构造函数拿到它，判定在同一处提前返回。
  assert.match(service, /frontThrottleMillis = FRONT_THROTTLE_MILLIS/u, '置前节流常量必须传给状态机')
  assert.match(machine, /if \(now - lastFrontAtMs < frontThrottleMillis\) return/u, '节流窗口内必须直接返回，不重建窗口')
})
