package io.deepseekharness.mobile.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 状态读取闸门的行为。
 *
 * 真机场景：Shizuku 服务端卡住时，`state()` 里的 binder 调用既不返回也不超时。
 * 前端每 5 秒轮询一次，若每次都真的去问 binder，执行线程会被逐个占满，
 * 整个运行时的桥调用跟着一起僵住。这里固定住「同一时刻只读一次」的语义：
 *  - 有读取在途时返回上一次成功结果（有过成功读取）；
 *  - 从未成功读取过时返回兜底状态，且**不发起**新的读取；
 *  - 读取失败不堵死闸门；
 *  - 服务端换了一茬（binder 到达/断开）时 `invalidate()` 必须能立刻重读，
 *    而晚回来的旧读数既不许写缓存、也不许清掉新一代的在途标记。
 */
class ShizukuStateCacheTest {
    private fun state(version: String, running: Boolean = true): ShizukuState = ShizukuState(
        installed = true,
        running = running,
        permission = "granted",
        connected = running,
        version = version,
        appVersion = "13.6.0",
    )

    @Test
    fun 有读取在途时返回上一次成功结果且不再发起读取() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger(0)
        val cache = ShizukuStateCache(
            read = {
                val index = reads.incrementAndGet()
                if (index == 2) {
                    entered.countDown()
                    assertTrue("等待放行超时", release.await(5, TimeUnit.SECONDS))
                }
                state(index.toString())
            },
            fallback = { state("fallback", running = false) },
        )

        // 第一次读取成功，闸门后留下可用结果。
        assertEquals("第一次读取", "1", cache.current().version)

        val workerResult = java.util.concurrent.atomic.AtomicReference<ShizukuState>()
        val worker = Thread { workerResult.set(cache.current()) }
        worker.start()
        assertTrue("第二次读取未进入", entered.await(5, TimeUnit.SECONDS))

        // 在途期间的调用拿到的是上一次结果，而不是排队去问 binder。
        assertEquals("在途期间复用上一次结果", "1", cache.current().version)
        assertEquals("在途期间不应发起新的读取", 2, reads.get())

        release.countDown()
        worker.join(5_000)
        // 在途读取完成后，本次调用拿到的是自己的真实读数。
        assertEquals("在途读取返回本次真实读数", "2", workerResult.get().version)
        assertEquals("两次读取各发起一次", 2, reads.get())
    }

    @Test
    fun 从未成功读取过时在途读取返回兜底状态() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger(0)
        val cache = ShizukuStateCache(
            read = {
                reads.incrementAndGet()
                entered.countDown()
                assertTrue("等待放行超时", release.await(5, TimeUnit.SECONDS))
                state("13")
            },
            fallback = { state("", running = false) },
        )

        val worker = Thread { cache.current() }
        worker.start()
        assertTrue("首次读取未进入", entered.await(5, TimeUnit.SECONDS))

        val fallback = cache.current()
        assertTrue("兜底状态必须按未运行处理", !fallback.running)
        assertEquals("兜底状态不带服务端版本", "", fallback.version)
        assertEquals("兜底时不应发起第二次读取", 1, reads.get())

        release.countDown()
        worker.join(5_000)
        assertEquals("首次读取完成后取真实结果", "13", cache.current().version)
    }

    @Test
    fun 读取抛异常后闸门自动放开() {
        var failing = true
        val reads = AtomicInteger(0)
        val cache = ShizukuStateCache(
            read = {
                reads.incrementAndGet()
                if (failing) throw IllegalStateException("binder haven't been received")
                state("13")
            },
            fallback = { state("", running = false) },
        )

        try {
            cache.current()
            fail("读取失败必须抛出")
        } catch (expected: IllegalStateException) {
            assertEquals("binder haven't been received", expected.message)
        }

        failing = false
        assertEquals("失败后仍能重新读取", "13", cache.current().version)
        assertEquals("两次调用各读一次", 2, reads.get())
    }

    @Test
    fun 作废在途读取后立刻能重读且旧读数晚回来不覆盖新缓存() {
        val enteredOld = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val enteredNew = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val reads = AtomicInteger(0)
        val cache = ShizukuStateCache(
            read = {
                when (reads.incrementAndGet()) {
                    1 -> {
                        // 旧服务端卡死：这一次读取永远不返回（由测试放行模拟）。
                        enteredOld.countDown()
                        assertTrue("旧读取等待放行超时", releaseOld.await(5, TimeUnit.SECONDS))
                        state("stale", running = false)
                    }
                    2 -> state("fresh")
                    3 -> {
                        enteredNew.countDown()
                        assertTrue("新一代读取等待放行超时", releaseNew.await(5, TimeUnit.SECONDS))
                        state("third")
                    }
                    else -> state("extra")
                }
            },
            fallback = { state("", running = false) },
        )

        val stale = Thread { cache.current() }
        stale.start()
        assertTrue("旧读取未进入", enteredOld.await(5, TimeUnit.SECONDS))

        // 用户重启了 Shizuku 服务：binder 到达，上一茬的读数与卡死在途的读取一起作废。
        cache.invalidate()
        assertEquals("作废后立刻读到新一代的真实状态", "fresh", cache.current().version)
        assertEquals("作废后确实重新读了一次", 2, reads.get())

        // 再发起一次读取并卡住，用来观察缓存与在途标记有没有被晚回来的旧读数动过。
        val second = Thread { cache.current() }
        second.start()
        assertTrue("新一代读取未进入", enteredNew.await(5, TimeUnit.SECONDS))

        releaseOld.countDown()
        stale.join(5_000)

        // 旧读数晚回来：既不许覆盖缓存（否则这里会看到 stale），也不许清掉在途标记（否则会再多读一次）。
        assertEquals("旧读数不得覆盖新缓存", "fresh", cache.current().version)
        assertEquals("旧读数不得清掉新一代的在途标记", 3, reads.get())

        releaseNew.countDown()
        second.join(5_000)
    }
}
