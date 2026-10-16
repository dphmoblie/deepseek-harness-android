package io.deepseekharness.mobile.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 状态读取闸门的行为。
 *
 * 真机场景：Shizuku 服务端卡住时，`state()` 里的 binder 调用既不返回也不超时。
 * 前端每 5 秒轮询一次，若每次都真的去问 binder，执行线程会被逐个占满，
 * 整个运行时的桥调用跟着一起僵住。这里固定住「同一时刻只读一次」的语义：
 *  - 有读取在途时返回上一次成功结果（有过成功读取）；
 *  - 从未成功读取过时返回兜底状态，且**不发起**新的读取；
 *  - 首次读取也有等待上限，超时后仍保留同代单飞且后台结果可以回填；
 *  - 读取失败不堵死闸门；
 *  - 服务端换了一茬（binder 到达/断开）时 `invalidate()` 必须能立刻重读，
 *    而晚回来的旧读数既不许写缓存、也不许清掉新一代的在途标记；
 *  - 服务反复重连或新建缓存也不能无限增加挂起的读取线程。
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
            readTimeoutMillis = 5_000,
        )

        // 第一次读取成功，闸门后留下可用结果。
        assertEquals("第一次读取", "1", cache.current().version)

        val workerResult = AtomicReference<ShizukuState>()
        val worker = Thread { workerResult.set(cache.current()) }
        try {
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
        } finally {
            release.countDown()
            worker.join(5_000)
        }
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
            readTimeoutMillis = 5_000,
        )

        val worker = Thread { cache.current() }
        try {
            worker.start()
            assertTrue("首次读取未进入", entered.await(5, TimeUnit.SECONDS))

            val fallback = cache.current()
            assertTrue("兜底状态必须按未运行处理", !fallback.running)
            assertEquals("兜底状态不带服务端版本", "", fallback.version)
            assertEquals("兜底时不应发起第二次读取", 1, reads.get())

            release.countDown()
            worker.join(5_000)
            assertEquals("首次读取完成后取真实结果", "13", cache.current().version)
        } finally {
            release.countDown()
            worker.join(5_000)
        }
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
            readTimeoutMillis = 5_000,
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
            readTimeoutMillis = 5_000,
        )

        val stale = Thread { cache.current() }
        val second = Thread { cache.current() }
        try {
            stale.start()
            assertTrue("旧读取未进入", enteredOld.await(5, TimeUnit.SECONDS))

            // 用户重启了 Shizuku 服务：binder 到达，上一茬的读数与卡死在途的读取一起作废。
            cache.invalidate()
            assertEquals("作废后立刻读到新一代的真实状态", "fresh", cache.current().version)
            assertEquals("作废后确实重新读了一次", 2, reads.get())

            // 再发起一次读取并卡住，用来观察缓存与在途标记有没有被晚回来的旧读数动过。
            second.start()
            assertTrue("新一代读取未进入", enteredNew.await(5, TimeUnit.SECONDS))

            releaseOld.countDown()
            stale.join(5_000)

            // 旧读数晚回来：既不许覆盖缓存，也不许清掉新一代正在进行的读取标记。
            assertEquals("旧读数不得覆盖新缓存", "fresh", cache.current().version)
            assertEquals("旧读数不得清掉新一代的在途标记", 3, reads.get())
        } finally {
            releaseOld.countDown()
            releaseNew.countDown()
            stale.join(5_000)
            second.join(5_000)
        }
    }

    @Test
    fun 首次读取超时返回兜底且后续同代轮询不会增加读取() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        val reads = AtomicInteger(0)
        val cache = ShizukuStateCache(
            read = {
                reader.set(Thread.currentThread())
                reads.incrementAndGet()
                entered.countDown()
                assertTrue("等待放行超时", release.await(5, TimeUnit.SECONDS))
                state("late")
            },
            fallback = { state("fallback", running = false) },
            readTimeoutMillis = 25,
        )

        try {
            val started = System.nanoTime()
            assertEquals("首个调用必须有兜底结果", "fallback", cache.current().version)
            assertTrue("首个调用不得等待 Binder 完成", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
            assertTrue("首次读取未进入", entered.await(1, TimeUnit.SECONDS))
            assertTrue("Binder 读取仍在后台", reader.get().isAlive)
            repeat(20) {
                assertEquals("读取未完成前继续使用兜底", "fallback", cache.current().version)
            }
            assertEquals("超时不能取消单飞标记", 1, reads.get())
        } finally {
            release.countDown()
            reader.get()?.join(5_000)
        }
    }

    @Test
    fun 超时后后台晚返回的真实结果仍能更新缓存() {
        val enteredFirst = CountDownLatch(1)
        val enteredSecond = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val firstReader = AtomicReference<Thread>()
        val secondReader = AtomicReference<Thread>()
        val reads = AtomicInteger(0)
        val cache = ShizukuStateCache(
            read = {
                if (reads.incrementAndGet() == 1) {
                    firstReader.set(Thread.currentThread())
                    enteredFirst.countDown()
                    assertTrue("首次读取等待放行超时", releaseFirst.await(5, TimeUnit.SECONDS))
                    state("late")
                } else {
                    secondReader.set(Thread.currentThread())
                    enteredSecond.countDown()
                    assertTrue("第二次读取等待放行超时", releaseSecond.await(5, TimeUnit.SECONDS))
                    state("new")
                }
            },
            fallback = { state("fallback", running = false) },
            readTimeoutMillis = 25,
        )

        try {
            assertEquals("首次超时使用兜底", "fallback", cache.current().version)
            assertTrue("首次读取未进入", enteredFirst.await(1, TimeUnit.SECONDS))
            releaseFirst.countDown()
            firstReader.get().join(5_000)

            // 下一次刷新也卡住时，应复用已经晚返回的真实结果，而非一直停留在兜底。
            assertEquals("后台结果应已回填", "late", cache.current().version)
            assertTrue("第二次读取未进入", enteredSecond.await(1, TimeUnit.SECONDS))
            assertEquals("刷新在途仍保留已知结果", "late", cache.current().version)
            assertEquals("完成后只启动一次新刷新", 2, reads.get())
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            firstReader.get()?.join(5_000)
            secondReader.get()?.join(5_000)
        }
    }

    @Test
    fun 超时读取作废后旧结果晚返回不能解除新代单飞() {
        val enteredOld = CountDownLatch(1)
        val enteredNew = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val oldReader = AtomicReference<Thread>()
        val newReader = AtomicReference<Thread>()
        val reads = AtomicInteger(0)
        val cache = ShizukuStateCache(
            read = {
                if (reads.incrementAndGet() == 1) {
                    oldReader.set(Thread.currentThread())
                    enteredOld.countDown()
                    assertTrue("旧读取等待放行超时", releaseOld.await(5, TimeUnit.SECONDS))
                    state("stale")
                } else {
                    newReader.set(Thread.currentThread())
                    enteredNew.countDown()
                    assertTrue("新读取等待放行超时", releaseNew.await(5, TimeUnit.SECONDS))
                    state("fresh")
                }
            },
            fallback = { state("fallback", running = false) },
            readTimeoutMillis = 25,
        )

        try {
            assertEquals("旧代超时", "fallback", cache.current().version)
            assertTrue("旧读取未进入", enteredOld.await(1, TimeUnit.SECONDS))
            cache.invalidate()
            assertEquals("新代也未完成", "fallback", cache.current().version)
            assertTrue("新读取未进入", enteredNew.await(1, TimeUnit.SECONDS))
            releaseOld.countDown()
            oldReader.get().join(5_000)

            assertEquals("旧结果不得写入新缓存", "fallback", cache.current().version)
            assertEquals("旧结果不得解除新代单飞", 2, reads.get())
        } finally {
            releaseOld.countDown()
            releaseNew.countDown()
            oldReader.get()?.join(5_000)
            newReader.get()?.join(5_000)
        }
    }

    @Test
    fun 多代和多个缓存共用读取限额且释放后可以继续读取() {
        val entered = List(3) { CountDownLatch(1) }
        val releases = List(3) { CountDownLatch(1) }
        val readers = List(3) { AtomicReference<Thread>() }
        val reads = AtomicInteger(0)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val caches = List(3) { index ->
            ShizukuStateCache(
                read = {
                    readers[index].set(Thread.currentThread())
                    reads.incrementAndGet()
                    val currentActive = active.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, currentActive) }
                    entered[index].countDown()
                    try {
                        assertTrue("读取等待放行超时", releases[index].await(5, TimeUnit.SECONDS))
                        state(index.toString())
                    } finally {
                        active.decrementAndGet()
                    }
                },
                fallback = { state("fallback", running = false) },
                readTimeoutMillis = 25,
            )
        }

        try {
            assertEquals("第一个读取仍挂起", "fallback", caches[0].current().version)
            assertTrue("第一个读取未进入", entered[0].await(1, TimeUnit.SECONDS))
            assertEquals("第二个读取仍挂起", "fallback", caches[1].current().version)
            assertTrue("第二个读取未进入", entered[1].await(1, TimeUnit.SECONDS))
            repeat(20) {
                caches[2].invalidate()
                assertEquals("限额占满时直接降级", "fallback", caches[2].current().version)
            }
            assertEquals("不能因缓存重建或代次失效无限起线程", 2, reads.get())
            assertEquals("进程级并发上限", 2, peak.get())

            releases[0].countDown()
            readers[0].get().join(5_000)
            assertEquals("释放一个名额后可重新发起读取", "fallback", caches[2].current().version)
            assertTrue("释放名额后读取未进入", entered[2].await(1, TimeUnit.SECONDS))
            assertEquals("读取名额必须归还", 3, reads.get())
            assertEquals("恢复时也不超出并发限额", 2, peak.get())
        } finally {
            releases.forEach { it.countDown() }
            readers.forEach { it.get()?.join(5_000) }
        }
    }
}
