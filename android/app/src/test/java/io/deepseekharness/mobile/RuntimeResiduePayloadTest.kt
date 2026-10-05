package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeResidueInventory
import io.deepseekharness.mobile.runtime.RuntimeResidueSweepResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「运行时占用」载荷整形：份数 / 字节数 / 截断标记 / 幂等 / 失败计数，以及两条硬规则
 * （载荷里绝不出现路径；清理结果不拿来自算剩余量）。
 *
 * 全部用假实现注入：`report` 与 `sweep` 都是 λ，这里一个字节都不会真的落盘或删除。
 */
class RuntimeResiduePayloadTest {

    private fun category(token: String, entries: Int, bytes: Long) = RuntimeResidueInventory.Category(token, entries, bytes)

    private fun report(categories: List<RuntimeResidueInventory.Category>, truncated: Boolean = false) =
        RuntimeResidueInventory.Report(categories, categories.sumOf { it.entries }, categories.sumOf { it.bytes }, truncated)

    private fun payload(
        report: () -> RuntimeResidueInventory.Report,
        sweep: () -> RuntimeResidueSweepResult = { error("本次用例不该调用清理") },
    ) = RuntimeResiduePayload(report = report, sweep = sweep)

    // ---------------------------------------------------------------- 查询

    @Test
    fun `查询只统计可回收的 stale 类别`() {
        val payload = payload(
            report = {
                report(
                    listOf(
                        category(RuntimeResidueInventory.STALE_TOKEN, entries = 3, bytes = 3L * 960L * 1024 * 1024),
                        // 下面这些是**在用**的运行时目录，不是残留：份数与字节都不许混进来。
                        category("current", entries = 1, bytes = 640L * 1024 * 1024),
                        category("preserve", entries = 2, bytes = 12L * 1024 * 1024),
                    ),
                )
            },
        )

        assertEquals(
            mapOf<String, Any>("count" to 3, "bytes" to 3L * 960L * 1024 * 1024, "truncated" to false),
            payload.status(),
        )
    }

    @Test
    fun `没有残留时查询回 0 份 0 字节且不报错`() {
        val payload = payload(report = { report(listOf(category("current", entries = 1, bytes = 640L * 1024 * 1024))) })

        assertEquals(mapOf<String, Any>("count" to 0, "bytes" to 0L, "truncated" to false), payload.status())
    }

    @Test
    fun `查询把截断标记透传`() {
        val payload = payload(
            report = { report(listOf(category(RuntimeResidueInventory.STALE_TOKEN, entries = 1, bytes = 4096L)), truncated = true) },
        )

        assertEquals(mapOf<String, Any>("count" to 1, "bytes" to 4096L, "truncated" to true), payload.status())
    }

    @Test
    fun `盘点读不到时不谎称确认没有残留`() {
        val payload = payload(report = { throw java.io.IOException("目录列举失败") })

        // 份数只能回 0（没有任何一份被数到），但 truncated=true 说明这个 0 只是下界，不是结论。
        assertEquals(mapOf<String, Any>("count" to 0, "bytes" to 0L, "truncated" to true), payload.status())
    }

    @Test
    fun `查询载荷的字段恰好是份数、字节数与截断标记`() {
        val payload = payload(report = { report(listOf(category(RuntimeResidueInventory.STALE_TOKEN, entries = 2, bytes = 2048L))) })

        assertEquals(setOf("count", "bytes", "truncated"), payload.status().keys)
        // 残留目录名里带版本号、时间戳与 uuid：任何字符串型载荷字段都可能是路径泄漏的口子。
        assertTrue(payload.status().values.none { it is String })
    }

    // ---------------------------------------------------------------- 清理

    @Test
    fun `清理成功时份数、字节数与兜底说明都如实`() {
        val payload = payload(
            report = { error("清理不该顺带盘点：剩余量必须由界面重新查一次") },
            sweep = { RuntimeResidueSweepResult(cleaned = 3, fallbackCleaned = 1, failed = 0, reclaimedBytes = 3L * 960L * 1024 * 1024) },
        )

        assertEquals(
            mapOf<String, Any>(
                "cleaned" to 3,
                "failed" to 0,
                "reclaimedBytes" to 3L * 960L * 1024 * 1024,
                "message" to "已回收 3 份（其中 1 份走了兜底删除器），详见诊断与日志",
            ),
            payload.clean(),
        )
    }

    @Test
    fun `部分失败时说明里同时有回收与未删除`() {
        val payload = payload(
            report = { error("清理不该顺带盘点") },
            sweep = { RuntimeResidueSweepResult(cleaned = 2, fallbackCleaned = 0, failed = 1, reclaimedBytes = 1024L) },
        )

        val cleaned = payload.clean()
        assertEquals(2, cleaned["cleaned"])
        assertEquals(1, cleaned["failed"])
        assertEquals(1024L, cleaned["reclaimedBytes"])
        assertEquals("已回收 2 份，1 份未能删除，详见诊断与日志", cleaned["message"])
    }

    @Test
    fun `全部失败时回收份数为 0 且说明如实`() {
        val payload = payload(
            report = { error("清理不该顺带盘点") },
            sweep = { RuntimeResidueSweepResult(cleaned = 0, fallbackCleaned = 0, failed = 2, reclaimedBytes = 0L) },
        )

        val cleaned = payload.clean()
        assertEquals(0, cleaned["cleaned"])
        assertEquals(2, cleaned["failed"])
        assertEquals("2 份未能删除，详见诊断与日志", cleaned["message"])
    }

    @Test
    fun `没有残留时清理是幂等的`() {
        val payload = payload(
            report = { error("清理不该顺带盘点") },
            sweep = { RuntimeResidueSweepResult(cleaned = 0, fallbackCleaned = 0, failed = 0, reclaimedBytes = 0L) },
        )

        assertEquals(
            mapOf<String, Any>("cleaned" to 0, "failed" to 0, "reclaimedBytes" to 0L, "message" to "没有可回收的残留，无需清理"),
            payload.clean(),
        )
    }

    @Test
    fun `清扫器抛异常时清理不抛且给出一句说明`() {
        val payload = payload(
            report = { error("清理不该顺带盘点") },
            sweep = { throw IllegalStateException("清扫器不该抛；真抛了也不能让它冒到界面") },
        )

        assertEquals(
            mapOf<String, Any>("cleaned" to 0, "failed" to 0, "reclaimedBytes" to 0L, "message" to "清理没能执行，详见诊断与日志"),
            payload.clean(),
        )
    }

    @Test
    fun `清理载荷的字段恰好是四项且说明里没有路径分隔符`() {
        val payload = payload(
            report = { error("清理不该顺带盘点") },
            sweep = { RuntimeResidueSweepResult(cleaned = 1, fallbackCleaned = 0, failed = 1, reclaimedBytes = 512L) },
        )

        val cleaned = payload.clean()
        assertEquals(setOf("cleaned", "failed", "reclaimedBytes", "message"), cleaned.keys)
        val message = cleaned["message"] as String
        assertTrue("说明里不该出现路径分隔符：$message", !message.contains("/") && !message.contains("\\"))
    }
}
