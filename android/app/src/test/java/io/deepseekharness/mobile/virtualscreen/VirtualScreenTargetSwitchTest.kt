package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标应用热切换的确认契约：`am start -W` 返回不等于目标已在新屏 resume，
 * 必须在有界预算内轮询，超时报语义准确的 `VIRTUAL_SCREEN_TARGET_TIMEOUT`，
 * 绝不能再落到 `errorCode` 的 `else` 兜底变成 `VIRTUAL_SCREEN_UNAVAILABLE`。
 */
class VirtualScreenTargetSwitchTest {
    /** 假时钟：单测里 3 秒预算瞬间跑完，同时记录每次等待时长，用于证明「总耗时始终有界」。 */
    private class FakeClock {
        var now = 0L
        val slept = mutableListOf<Long>()
        fun elapsed(): Long = now
        fun sleep(millis: Long) {
            slept += millis
            now += millis
        }
    }

    @Test fun `轮询在预算内重试直到目标出现在副屏`() {
        // 表驱动：切换后目标第 N 次查询才被确认为前台（0 表示一次就确认）。
        for (attemptsBeforeVisible in listOf(0, 1, 2, 5, 14)) {
            val clock = FakeClock()
            var queries = 0
            val visible = VirtualScreenPolicy.waitUntilVisible(
                timeoutMillis = 3000L,
                intervalMillis = 200L,
                elapsedMillis = clock::elapsed,
                sleepMillis = clock::sleep,
            ) {
                queries++
                queries > attemptsBeforeVisible
            }
            assertTrue("第 $attemptsBeforeVisible 次后确认应视为切换成功", visible)
            assertEquals(attemptsBeforeVisible + 1, queries)
            // 成功即返回：等待总时长不超过确认所必需的间隔数。
            assertEquals(attemptsBeforeVisible * 200L, clock.now)
            assertEquals(attemptsBeforeVisible * 200L, clock.slept.sum())
        }
    }

    @Test fun `轮询超时只返回失败并给出语义准确的目标超时码`() {
        val clock = FakeClock()
        var queries = 0
        val visible = VirtualScreenPolicy.waitUntilVisible(
            timeoutMillis = 3000L,
            intervalMillis = 200L,
            elapsedMillis = clock::elapsed,
            sleepMillis = clock::sleep,
        ) {
            queries++
            false
        }
        assertFalse("目标始终没在前台，确认必须失败", visible)
        // 有界：最多询问 预算/间隔 + 1 次，等待总量不超过预算，不阻塞式死循环、不无限等。
        assertEquals(16, queries)
        assertEquals(3000L, clock.now)
        assertEquals(3000L, clock.slept.sum())
        assertTrue("每次等待都必须是正数", clock.slept.all { it > 0 })

        val failure = VirtualScreenPolicy.targetSwitchFailure()
        assertEquals("VIRTUAL_SCREEN_TARGET_TIMEOUT", failure.code)
        assertEquals("目标应用未能在副屏上进入前台，请稍后重试或改用原生入口切换", failure.message)
        // 这就是本缺陷的核心：切换没确认成功是「可重试的一次切换」，不是「副屏不可用」。
        assertEquals("VIRTUAL_SCREEN_TARGET_TIMEOUT", VirtualScreenPolicy.errorCode(failure))
        assertNotEquals("VIRTUAL_SCREEN_UNAVAILABLE", VirtualScreenPolicy.errorCode(failure))
    }

    @Test fun `轮询期间单次查询异常不能让已实现的切换判死`() {
        val clock = FakeClock()
        var queries = 0
        val visible = VirtualScreenPolicy.waitUntilVisible(
            timeoutMillis = 3000L,
            intervalMillis = 200L,
            elapsedMillis = clock::elapsed,
            sleepMillis = clock::sleep,
        ) {
            queries++
            // 模拟并发的 dumpsys 抖动：这一拍问不出来，不代表副屏或切换没有实现。
            if (queries <= 2) throw IllegalStateException("系统命令未成功执行")
            true
        }
        assertTrue(visible)
        assertEquals(3, queries)
    }

    @Test fun `查询全程异常时按超时收场而不是把异常透出成不可用`() {
        val clock = FakeClock()
        var queries = 0
        val visible = VirtualScreenPolicy.waitUntilVisible(
            timeoutMillis = 600L,
            intervalMillis = 200L,
            elapsedMillis = clock::elapsed,
            sleepMillis = clock::sleep,
        ) {
            queries++
            throw IllegalStateException("系统命令未成功执行")
        }
        assertFalse(visible)
        assertEquals(4, queries)
        assertEquals(600L, clock.now)
        // 透传出去的必须是明确的超时码：未捕获的 IllegalStateException 会被兜底成「副屏不可用」。
        assertEquals("VIRTUAL_SCREEN_TARGET_TIMEOUT", VirtualScreenPolicy.errorCode(VirtualScreenPolicy.targetSwitchFailure()))
    }

    @Test fun `最后一次等待不超过剩余预算`() {
        val clock = FakeClock()
        VirtualScreenPolicy.waitUntilVisible(
            timeoutMillis = 1000L,
            intervalMillis = 400L,
            elapsedMillis = clock::elapsed,
            sleepMillis = clock::sleep,
        ) { false }
        assertEquals(listOf(400L, 400L, 200L), clock.slept)
        assertEquals(1000L, clock.now)
    }

    @Test fun `轮询参数非法属于调用方输入错误`() {
        val clock = FakeClock()
        for (timeout in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                VirtualScreenPolicy.waitUntilVisible(timeout, 200L, clock::elapsed, clock::sleep) { false }
            }
        }
        val invalid = assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenPolicy.waitUntilVisible(3000L, 0L, clock::elapsed, clock::sleep) { false }
        }
        assertEquals("副屏轮询参数无效", invalid.message)
        assertEquals("VIRTUAL_SCREEN_INVALID", VirtualScreenPolicy.errorCode(invalid))
        // 参数非法时一次都没睡、一次都没查：校验发生在轮询之前。
        assertEquals(0L, clock.now)
    }

    @Test fun `目标超时码与既有可重试码互不混淆`() {
        val codes = listOf(
            VirtualScreenPolicy.targetSwitchFailure().code,
            VirtualScreenPolicy.sessionFailure("", "3f1a5c2e-9d4b-4f7a-8e11-2b6c0d9a7e34")!!.code,
            VirtualScreenPolicy.sessionFailure("3f1a5c2e-9d4b-4f7a-8e11-2b6c0d9a7e34", "8b7c1d40-2e59-4a3f-9c88-5d1e6f0a2b47")!!.code,
        )
        assertEquals(3, codes.toSet().size)
        assertTrue(codes.all { Regex("^VIRTUAL_SCREEN_[A-Z_]+$").matches(it) })
        // 每次请求失败对象都是新的，避免调用方把上一个失败的消息改成下一个场景的。
        assertNotEquals(VirtualScreenPolicy.targetSwitchFailure(), VirtualScreenPolicy.targetSwitchFailure())
    }
}
