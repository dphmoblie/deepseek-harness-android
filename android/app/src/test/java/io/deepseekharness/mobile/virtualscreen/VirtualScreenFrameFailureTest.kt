package io.deepseekharness.mobile.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误码细分：一个 `VIRTUAL_SCREEN_UNAVAILABLE` 承担多义情况的缺陷。
 *
 * 真机现象：副屏会话还在、目标却跳走了（或画面取不到）时，用户只看到「副屏不可用」，
 * 排查方向被指向「重启副屏」——而实际要做的是把目标拉回副屏、或者稍后重试取帧。
 * 这里把三类情形的判据与码钉死，防止再退回一个笼统的兜底码。
 */
class VirtualScreenFrameFailureTest {
    private val current = "3f1a5c2e-9d4b-4f7a-8e11-2b6c0d9a7e34"
    private val other = "8b7c1d40-2e59-4a3f-9c88-5d1e6f0a2b47"

    @Test fun `会话存活判据同时看显示编号与会话标识`() {
        assertTrue(VirtualScreenPolicy.sessionAlive(4, current, current))
        assertFalse("显示编号无效说明虚拟显示器已释放", VirtualScreenPolicy.sessionAlive(0, current, current))
        assertFalse(VirtualScreenPolicy.sessionAlive(-1, current, current))
        assertFalse("请求的会话不是当前会话", VirtualScreenPolicy.sessionAlive(4, current, other))
        assertFalse(VirtualScreenPolicy.sessionAlive(4, "", current))
    }

    @Test fun `画面过期判据覆盖取帧报错-从未取到帧-超出窗口`() {
        val now = 100_000L
        assertFalse(VirtualScreenPolicy.frameStale(now, now - 100, ""))
        assertTrue("取帧报过错就不能再拿旧帧充数", VirtualScreenPolicy.frameStale(now, now - 100, "IllegalStateException"))
        assertTrue("从未取到帧", VirtualScreenPolicy.frameStale(now, 0L, ""))
        assertTrue("负数时间戳同样算没有帧", VirtualScreenPolicy.frameStale(now, -1L, ""))
        assertFalse("窗口内（1999 毫秒）不算过期", VirtualScreenPolicy.frameStale(now, now - 1999, ""))
        assertTrue("恰好两秒整算过期", VirtualScreenPolicy.frameStale(now, now - VirtualScreenPolicy.FRAME_STALE_AFTER_MILLIS, ""))
        assertTrue(VirtualScreenPolicy.frameStale(now, now - 5000, ""))
    }

    @Test fun `三类细分错误码互斥且都不退化成不可用`() {
        val dead = VirtualScreenPolicy.frameFailure(sessionAlive = false, targetVisible = false, frameStale = true)
        val left = VirtualScreenPolicy.frameFailure(sessionAlive = true, targetVisible = false, frameStale = false)
        val stale = VirtualScreenPolicy.frameFailure(sessionAlive = true, targetVisible = true, frameStale = true)

        assertEquals(VirtualScreenPolicy.SESSION_DEAD_CODE, dead?.code)
        assertEquals(VirtualScreenPolicy.TARGET_LEFT_CODE, left?.code)
        assertEquals(VirtualScreenPolicy.STALE_FRAME_CODE, stale?.code)
        assertNull("会话活着、目标在副屏、画面新鲜时没有失败", VirtualScreenPolicy.frameFailure(true, true, false))

        // 三个码两两不同，也必须都不是旧兜底码：调用方靠码决定「重开会话 / 拉回目标 / 稍后重试」。
        assertEquals(3, setOf(dead!!.code, left!!.code, stale!!.code).size)
        for (code in listOf(dead.code, left.code, stale.code)) {
            assertNotEquals("VIRTUAL_SCREEN_UNAVAILABLE", code)
            assertNotEquals("VIRTUAL_SCREEN_STOPPED", code)
        }
        // 运行时失败码必须被 errorCode 原样保留（否则系统命令层又会把它拍成兜底码）。
        assertEquals(VirtualScreenPolicy.STALE_FRAME_CODE, VirtualScreenPolicy.errorCode(stale))
        assertEquals(VirtualScreenPolicy.TARGET_LEFT_CODE, VirtualScreenPolicy.errorCode(left))
        assertEquals(VirtualScreenPolicy.SESSION_DEAD_CODE, VirtualScreenPolicy.errorCode(dead))
    }

    @Test fun `每个细分码都配一句可操作的处置提示`() {
        assertEquals(VirtualScreenPolicy.SESSION_DEAD_MESSAGE, VirtualScreenPolicy.failure(VirtualScreenPolicy.SESSION_DEAD_CODE, VirtualScreenPolicy.SESSION_DEAD_MESSAGE).message)
        assertEquals(VirtualScreenPolicy.TARGET_LEFT_MESSAGE, VirtualScreenPolicy.failure(VirtualScreenPolicy.TARGET_LEFT_CODE, VirtualScreenPolicy.TARGET_LEFT_MESSAGE).message)
        assertEquals(VirtualScreenPolicy.STALE_FRAME_MESSAGE, VirtualScreenPolicy.failure(VirtualScreenPolicy.STALE_FRAME_CODE, VirtualScreenPolicy.STALE_FRAME_MESSAGE).message)
        // 三句提示必须互不相同，并且都要说清下一步怎么做（否则用户只能看到「失败了」）。
        assertEquals(3, setOf(VirtualScreenPolicy.SESSION_DEAD_MESSAGE, VirtualScreenPolicy.TARGET_LEFT_MESSAGE, VirtualScreenPolicy.STALE_FRAME_MESSAGE).size)
        assertTrue(VirtualScreenPolicy.SESSION_DEAD_MESSAGE.contains("重新"))
        assertTrue(VirtualScreenPolicy.TARGET_LEFT_MESSAGE.contains("切回副屏"))
        assertTrue(VirtualScreenPolicy.STALE_FRAME_MESSAGE.contains("稍后重试"))
        // 过期窗口是设备端与调用方共用的稳定常量。
        assertEquals(2000L, VirtualScreenPolicy.FRAME_STALE_AFTER_MILLIS)
    }
}
