package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次启动内「运行时准备」去重的用例。
 *
 * 这条去重能成立的前提只有一个：**只在同一次启动事务、同一个运行时指纹内复用**。
 * 用例把四个边界钉死 —— 事务外不缓存、事务结束即失效、指纹不同不复用、
 * begin 丢弃上一次残留 —— 任何一条破了，都可能出现「拿上一次启动的 DNS/脚本去启动」。
 */
class RuntimePreparationReuseTest {
    private val fingerprint = "/data/user/0/io.deepseekharness.mobile/no_backup/dsh-runtime/current:runtime-1:0.2.14:${"a".repeat(64)}"

    @Test
    fun `reuses a prepared runtime inside the same start transaction`() {
        val reuse = RuntimePreparationReuse()
        reuse.begin()
        assertFalse(reuse.canReuse(fingerprint))

        reuse.markPrepared(fingerprint)
        assertTrue(reuse.canReuse(fingerprint))
    }

    @Test
    fun `a different runtime fingerprint is prepared again`() {
        val reuse = RuntimePreparationReuse()
        reuse.begin()
        reuse.markPrepared(fingerprint)

        assertFalse(reuse.canReuse("$fingerprint:next-version"))
    }

    @Test
    fun `closing the transaction drops the prepared result even after a successful start`() {
        val reuse = RuntimePreparationReuse()
        reuse.begin()
        reuse.markPrepared(fingerprint)
        reuse.end()

        // 下一次启动必须重新写 DNS/hosts：网络可能在两次启动之间变化。
        assertFalse(reuse.canReuse(fingerprint))
        reuse.begin()
        assertFalse(reuse.canReuse(fingerprint))
    }

    @Test
    fun `preparing outside a transaction is never cached`() {
        val reuse = RuntimePreparationReuse()
        // 终端 / 插件管理 / 运行时自检这些单独调用 launch 的路径没有启动事务。
        reuse.markPrepared(fingerprint)
        reuse.begin()

        assertFalse(reuse.canReuse(fingerprint))
    }

    @Test
    fun `begin discards a fingerprint left over from a previous transaction`() {
        val reuse = RuntimePreparationReuse()
        reuse.begin()
        reuse.markPrepared(fingerprint)
        // 模拟「上一次启动在 end 之前异常中断」后又一次 begin。
        reuse.begin()

        assertFalse(reuse.canReuse(fingerprint))
    }
}
