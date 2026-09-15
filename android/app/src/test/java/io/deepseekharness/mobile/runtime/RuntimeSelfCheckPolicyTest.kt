package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时自检受控载荷策略的纯逻辑测试。
 *
 * 这层要挡住两件会直接误导排障的事：把脚本里的**未知枚举**当成有效条目回给界面，
 * 以及把「首个失败码」的**顺序**取错 —— 顺序错了，诊断日志就会把沙箱问题写成 PTY 问题，
 * 而这两者的处置完全不同（前者补执行位、后者查 node-pty）。
 */
class RuntimeSelfCheckPolicyTest {
    private fun raw(id: String, status: String, code: String? = null) =
        RuntimeSelfCheckPolicy.RawCheck(id, status, code)

    /** 十一项全通过的健康载荷；顺序刻意与契约不同，用来验证归一化。 */
    private val healthy = listOf(
        raw("rg", "ok"),
        raw("hardlink", "ok"),
        raw("attachments", "ok"),
        raw("dsh_home", "ok"),
        raw("pty_sandbox", "ok"),
        raw("pty", "ok"),
        raw("sandbox_exec", "ok"),
        raw("sandbox_probe", "ok"),
        raw("sandbox_launcher", "ok"),
        raw("node", "ok"),
        raw("shell", "ok"),
    )

    @Test
    fun keepsAWellFormedPayloadInContractOrder() {
        val checks = RuntimeSelfCheckPolicy.sanitize(healthy)
        assertEquals(RuntimeSelfCheckPolicy.CHECK_IDS, checks.map { it.id })
        assertTrue(checks.all { it.status == "ok" && it.code == null })
        // 全 ok 时汇总必须是「没有失败」：否则界面会显示一个并不存在的失败码。
        assertEquals(RuntimeSelfCheckPolicy.Summary(null, 0), RuntimeSelfCheckPolicy.summarize(checks))
        // 操作名同样只认这两个值。
        assertEquals("check", RuntimeSelfCheckPolicy.operation("check"))
        assertEquals("repair", RuntimeSelfCheckPolicy.operation("repair"))
        assertNull(RuntimeSelfCheckPolicy.operation("reset"))
        assertNull(RuntimeSelfCheckPolicy.operation(null))
    }

    @Test
    fun dropsUnknownIdentifiers() {
        val checks = RuntimeSelfCheckPolicy.sanitize(healthy + raw("sandbox", "fail", "PROBE_UNUSABLE"))
        assertEquals(RuntimeSelfCheckPolicy.CHECK_IDS, checks.map { it.id })
    }

    @Test
    fun dropsUnknownCodesAndMismatchedStatuses() {
        val checks = RuntimeSelfCheckPolicy.sanitize(
            listOf(
                // 不在受控码集合里。
                raw("rg", "fail", "RG_FIXED"),
                // 属于别的检查项：rg 不允许报沙箱的码。
                raw("rg", "fail", "PROBE_UNUSABLE"),
                // 状态与码的语义不一致：这两个码只能是 warn。
                raw("attachments", "fail", "ATTACHMENTS_MISSING"),
                raw("pty", "warn", "PTY_TIMEOUT"),
            ),
        )
        assertTrue(checks.isEmpty())
    }

    @Test
    fun dropsOkEntriesThatCarryACodeAndNonOkEntriesWithoutOne() {
        val checks = RuntimeSelfCheckPolicy.sanitize(
            listOf(
                raw("rg", "ok", "RG_MISSING"),
                raw("pty", "fail"),
                raw("attachments", "skipped"),
                // 同一次载荷里的合法条目必须留下：丢弃只针对不合法条目。
                raw("rg", "warn", "RG_NOT_EXECUTABLE"),
            ),
        )
        assertEquals(listOf("rg"), checks.map { it.id })
        assertEquals("RG_NOT_EXECUTABLE", checks.single().code)
    }

    @Test
    fun acceptsOnlyTheHardlinkDeniedCodeOnTheHardlinkCheck() {
        // 契约里的 id 顺序（脚本 / 原生 / 界面三处逐字一致）：hardlink 紧跟 attachments、在 rg 之前。
        assertEquals(
            listOf(
                "shell", "node", "sandbox_launcher", "sandbox_probe", "sandbox_exec",
                "pty", "pty_sandbox", "dsh_home", "attachments", "hardlink", "rg",
            ),
            RuntimeSelfCheckPolicy.CHECK_IDS,
        )

        val checks = RuntimeSelfCheckPolicy.sanitize(
            listOf(
                raw("hardlink", "ok"),
                // 同一个 id 重复出现时保留首个：因此这一行被丢弃，验证的是「ok 不带码」那一支。
                raw("hardlink", "fail", "HARDLINK_DENIED"),
            ),
        )
        assertEquals(listOf("hardlink"), checks.map { it.id })
        assertEquals("ok", checks.single().status)
        assertNull(checks.single().code)

        val denied = RuntimeSelfCheckPolicy.sanitize(listOf(raw("hardlink", "fail", "HARDLINK_DENIED")))
        assertEquals(listOf("hardlink"), denied.map { it.id })
        assertEquals("fail", denied.single().status)
        assertEquals("HARDLINK_DENIED", denied.single().code)

        // `ok` 带码、码属于别的检查项、状态与码不一致、码不在受控集合里：四种都整条丢弃。
        assertTrue(
            RuntimeSelfCheckPolicy.sanitize(
                listOf(
                    raw("hardlink", "ok", "HARDLINK_DENIED"),
                    raw("hardlink", "fail", "ATTACHMENTS_NOT_WRITABLE"),
                    raw("hardlink", "warn", "HARDLINK_DENIED"),
                    raw("hardlink", "fail", "HARDLINK_OK"),
                ),
            ).isEmpty(),
        )

        // 顺序证据：hardlink 在 attachments 之后、rg 之前，首个失败码必须按契约顺序取到它。
        val ordered = RuntimeSelfCheckPolicy.sanitize(
            listOf(
                raw("rg", "warn", "RG_MISSING"),
                raw("attachments", "ok"),
                raw("hardlink", "fail", "HARDLINK_DENIED"),
            ),
        )
        assertEquals(listOf("attachments", "hardlink", "rg"), ordered.map { it.id })
        assertEquals(listOf("ok", "fail", "warn"), ordered.map { it.status })
        // `warn` 不算失败：失败项只有 hardlink 一个。
        assertEquals(
            RuntimeSelfCheckPolicy.Summary("HARDLINK_DENIED", 1),
            RuntimeSelfCheckPolicy.summarize(ordered),
        )
    }

    @Test
    fun summarizesFirstFailureCodeAndFailureCount() {
        val checks = RuntimeSelfCheckPolicy.sanitize(
            listOf(
                raw("shell", "ok"),
                raw("sandbox_exec", "fail", "EXEC_LAUNCHER_FAILED"),
                raw("sandbox_probe", "warn", "PROBE_PARTIAL"),
                raw("pty", "skipped", "PTY_MODULE_MISSING"),
                raw("rg", "warn", "RG_MISSING"),
                raw("sandbox_launcher", "fail", "LAUNCHER_NOT_EXECUTABLE"),
            ),
        )
        // 首个失败码按契约顺序取：sandbox_launcher 排在 sandbox_exec 之前。
        assertEquals(
            RuntimeSelfCheckPolicy.Summary("LAUNCHER_NOT_EXECUTABLE", 2),
            RuntimeSelfCheckPolicy.summarize(checks),
        )
        // warn 与 skipped 是「有情况」但不是失败，不改变整体结论。
        assertEquals(0, RuntimeSelfCheckPolicy.summarize(checks.filter { it.status != "fail" }).failedCount)
    }

    @Test
    fun skipsEveryLauncherDependentCheckWhenTheLauncherIsUnusable() {
        val checks = RuntimeSelfCheckPolicy.sanitize(
            listOf(
                raw("sandbox_launcher", "fail", "LAUNCHER_MISSING"),
                raw("sandbox_probe", "skipped", "LAUNCHER_MISSING"),
                raw("sandbox_exec", "skipped", "LAUNCHER_MISSING"),
                raw("pty_sandbox", "skipped", "LAUNCHER_MISSING"),
            ),
        )
        assertEquals(
            listOf("sandbox_launcher", "sandbox_probe", "sandbox_exec", "pty_sandbox"),
            checks.map { it.id },
        )
        // 依赖启动器的三项都是「没测」（skipped）：真正的失败项只有 launcher 那一行。
        assertEquals(listOf("fail", "skipped", "skipped", "skipped"), checks.map { it.status })
        assertEquals(
            RuntimeSelfCheckPolicy.Summary("LAUNCHER_MISSING", 1),
            RuntimeSelfCheckPolicy.summarize(checks),
        )
        // 「没测」不得伪装成断言：启动器不可用时 PROBE_UNUSABLE / EXEC_* 都是无根据的结论，
        // 只能取 skipped；这几条必须被丢弃。
        assertTrue(
            RuntimeSelfCheckPolicy.sanitize(
                listOf(
                    raw("sandbox_probe", "skipped", "PROBE_UNUSABLE"),
                    raw("sandbox_probe", "fail", "LAUNCHER_MISSING"),
                    raw("sandbox_exec", "skipped", "EXEC_LAUNCHER_FAILED"),
                    raw("sandbox_exec", "fail", "LAUNCHER_MISSING"),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun parsesRepairCounts() {
        assertEquals(RuntimeSelfCheckPolicy.RepairSummary(2, 3), RuntimeSelfCheckPolicy.repairSummary(2, 3))
        assertEquals(RuntimeSelfCheckPolicy.RepairSummary(0, 0), RuntimeSelfCheckPolicy.repairSummary(0, 0))
        // 缺失、负数、超出上限，以及「改动数多于目标数」都不可信。
        assertNull(RuntimeSelfCheckPolicy.repairSummary(null, 3))
        assertNull(RuntimeSelfCheckPolicy.repairSummary(2, null))
        assertNull(RuntimeSelfCheckPolicy.repairSummary(-1, 3))
        assertNull(RuntimeSelfCheckPolicy.repairSummary(3, 2))
        assertNull(
            RuntimeSelfCheckPolicy.repairSummary(
                RuntimeSelfCheckPolicy.MAX_COUNT + 1,
                RuntimeSelfCheckPolicy.MAX_COUNT + 1,
            ),
        )
    }
}
