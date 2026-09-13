package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面「运行日志」的取尾部逻辑测试。
 *
 * 这里有两件容易写错、且错了也不报错的事：
 *  1. 按字节截断必须落在 UTF-8 字符边界上，否则复制出去的日志开头是残缺字符；
 *  2. 发布点的登记/清空语义 —— 它决定「运行时释放后进程输出是否还留在内存里」。
 */
class HarnessOutputTailTest {
    private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    @Test
    fun returnsTextUnchangedWhenItFitsTheLimit() {
        val text = "工具调用失败"

        assertEquals(text, utf8TailWithin(text, utf8Size(text)))
        assertEquals(text, utf8TailWithin(text, utf8Size(text) + 1))
        assertEquals("", utf8TailWithin("", HARNESS_OUTPUT_TAIL_BYTES))
    }

    @Test
    fun keepsOnlyTheTailWithinTheByteLimit() {
        val text = "prepare: Cannot read properties of undefined"

        val tail = utf8TailWithin(text, 20)

        assertEquals(20, utf8Size(tail))
        assertEquals(text.takeLast(20), tail)
        assertTrue(text.endsWith(tail))
    }

    @Test
    fun cutsOnCharacterBoundariesInsteadOfSplittingMultibyteCharacters() {
        // 每个汉字 3 字节：从末尾取 8 字节会切在第 2 个汉字中间，必须回退到第 3 个汉字之前
        assertEquals("你你", utf8TailWithin("你你你你", 8))
        assertEquals("你", utf8TailWithin("你你", 3))

        val text = "工具调用失败：Cannot read properties of undefined (reading 'prepare')"
        for (limit in 1..utf8Size(text)) {
            val tail = utf8TailWithin(text, limit)
            assertTrue("截断结果不得出现残缺字符：limit=$limit", !tail.contains('\uFFFD'))
            assertTrue("截断结果必须是不超过上限的尾部：limit=$limit", utf8Size(tail) <= limit)
            assertTrue("截断结果必须是原文的后缀：limit=$limit", text.endsWith(tail))
        }
    }

    @Test
    fun dropsTheWholeCharacterWhenTheLimitCannotHoldIt() {
        // 4 字节字符（代理对）只留 2 个字节时，既不产生半个字符，也不返回残缺编码
        assertEquals("", utf8TailWithin("\uD83D\uDE00", 2))
        assertEquals("\uD83D\uDE00", utf8TailWithin("\uD83D\uDE00", 4))
        assertEquals("a\uD83D\uDE00", utf8TailWithin("a\uD83D\uDE00", 5))
    }

    @Test
    fun returnsEmptyForNonPositiveLimit() {
        assertEquals("", utf8TailWithin("工具调用失败", 0))
        assertEquals("", utf8TailWithin("工具调用失败", -8))
    }

    @Test
    fun sourceReadsThroughTheRegisteredReader() {
        val requested = mutableListOf<Int>()
        HarnessOutputTailSource.register { limit ->
            requested += limit
            "尾部内容"
        }

        try {
            assertEquals("尾部内容", HarnessOutputTailSource.read())
            assertEquals(HARNESS_OUTPUT_TAIL_BYTES, requested.first())
            // 上限必须原样透传给读取方：截断只在那一层做一次，避免两处各截一半
            assertEquals("尾部内容", HarnessOutputTailSource.read(64))
            assertEquals(64, requested.last())
        } finally {
            HarnessOutputTailSource.clear()
        }
    }

    @Test
    fun sourceKeepsOnlyTheLatestRegistrationAndForgetsItOnClear() {
        HarnessOutputTailSource.clear()
        assertNull("没有登记时必须如实返回 null", HarnessOutputTailSource.read())

        HarnessOutputTailSource.register { "旧运行时" }
        HarnessOutputTailSource.register { "新运行时" }
        assertEquals("新运行时", HarnessOutputTailSource.read())

        // 运行时释放：登记项被清空，进程输出尾部不再可达
        HarnessOutputTailSource.clear()
        assertNull(HarnessOutputTailSource.read())
    }

    @Test
    fun sourcePassesThroughWhatTheSupervisorDecides() {
        // supervisor 的取值顺序（运行中缓冲区 -> 留存快照 -> null）在 RuntimeSupervisor 内决定，
        // 发布点不猜测、不伪造：读取方说没有，就如实报没有。
        HarnessOutputTailSource.register { null }

        try {
            assertNull(HarnessOutputTailSource.read())
        } finally {
            HarnessOutputTailSource.clear()
        }
    }
}
