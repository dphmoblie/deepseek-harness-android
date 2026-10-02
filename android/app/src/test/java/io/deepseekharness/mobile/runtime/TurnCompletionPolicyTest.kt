package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `notify-turn-complete` 收单点的判定表（登记册 §5.5）。
 *
 * 这里钉的是「访客侧发来的东西能不能被收下」：参数形状、限流窗口、以及**限流是忽略而不是报错**。
 * 收单点是访客进程唯一能碰到的原生入口之一，它的宽容度直接决定两件事：
 *  - 太宽：容器里任何东西都能往通知栏刷条目（尽管文案固定），用户会把整条通道关掉；
 *  - 太严：正常的完成事件被当成协议错误，访客侧开始重试或报错，而它本不该关心通知。
 */
class TurnCompletionPolicyTest {

    @Test
    fun `空参数合法 空对象不合法`() {
        // 访客侧可以什么都不说，只要「完成了一轮」这个事实。
        assertEquals(TurnCompletionPolicy.Parsed.Valid(null), TurnCompletionPolicy.parseParam(""))
        // 但「说了话却什么都没说」（`{}`）被当作畸形请求拒绝：发一个空对象说明调用方
        // 以为自己带了信息，这更可能是协议实现错了，早一点暴露比静默接受好。
        assertTrue(TurnCompletionPolicy.parseParam("{}") is TurnCompletionPolicy.Parsed.Invalid)
    }

    @Test
    fun `只接受白名单里的三个字段`() {
        assertEquals(
            TurnCompletionPolicy.Parsed.Valid("0123456789abcdef"),
            TurnCompletionPolicy.parseParam("""{"session":"0123456789abcdef","reason":"turn-end"}"""),
        )
        // 多一个字段就整条拒绝：桥的另一侧是容器，不能让「顺手多带一点信息」变成既成事实。
        // 会话正文、路径、凭据都可能被塞进这种额外字段。
        val rejected = listOf(
            """{"session":"0123456789abcdef","prompt":"帮我改一下这个文件"}""",
            """{"path":"/data/user/0/io.deepseekharness.mobile/files/x"}""",
            """{"token":"secret"}""",
        )
        for (param in rejected) {
            assertTrue(
                "应当拒绝：$param",
                TurnCompletionPolicy.parseParam(param) is TurnCompletionPolicy.Parsed.Invalid,
            )
        }
    }

    @Test
    fun `session 必须是十六位小写十六进制`() {
        assertTrue(
            TurnCompletionPolicy.parseParam("""{"session":"0123456789abcdef"}""")
                is TurnCompletionPolicy.Parsed.Valid,
        )
        // 会话标识只允许以摘要形式出现：原样 UUID、大小写混写、更长或更短的串全部拒绝，
        // 避免「反正本来也不是敏感信息」把原标识直接送进原生侧。
        val rejected = listOf(
            "\"00000000-0000-0000-0000-000000000000\"",
            "\"0123456789ABCDEF\"",
            "\"0123456789abcde\"",
            "\"0123456789abcdef0\"",
            "\"g123456789abcdef\"",
        )
        for (session in rejected) {
            val param = """{"session":$session}"""
            assertTrue(
                "应当拒绝：$param",
                TurnCompletionPolicy.parseParam(param) is TurnCompletionPolicy.Parsed.Invalid,
            )
        }
    }

    @Test
    fun `reason 与 sessions 的取值范围固定`() {
        for (reason in TurnCompletionPolicy.REASON_CODES) {
            assertTrue(
                "应当接受：$reason",
                TurnCompletionPolicy.parseParam("""{"reason":"$reason"}""")
                    is TurnCompletionPolicy.Parsed.Valid,
            )
        }
        assertTrue(
            TurnCompletionPolicy.parseParam("""{"sessions":3}""") is TurnCompletionPolicy.Parsed.Valid,
        )
        // reason 是固定集合，访客侧不得自造；sessions 只能是 1..MAX_SESSIONS 的整数。
        assertTrue(
            TurnCompletionPolicy.parseParam("""{"reason":"user-cancelled"}""")
                is TurnCompletionPolicy.Parsed.Invalid,
        )
        assertTrue(
            TurnCompletionPolicy.parseParam("""{"reason":"TURN-END"}""")
                is TurnCompletionPolicy.Parsed.Invalid,
        )
        assertTrue(
            TurnCompletionPolicy.parseParam("""{"sessions":0}""") is TurnCompletionPolicy.Parsed.Invalid,
        )
        assertTrue(
            TurnCompletionPolicy.parseParam("""{"sessions":${TurnCompletionPolicy.MAX_SESSIONS + 1}}""")
                is TurnCompletionPolicy.Parsed.Invalid,
        )
        assertTrue(
            TurnCompletionPolicy.parseParam("""{"sessions":"3"}""") is TurnCompletionPolicy.Parsed.Invalid,
        )
    }

    @Test
    fun `非 JSON 与超长参数一律拒绝`() {
        assertTrue(TurnCompletionPolicy.parseParam("not json") is TurnCompletionPolicy.Parsed.Invalid)
        assertTrue(TurnCompletionPolicy.parseParam("[1,2,3]") is TurnCompletionPolicy.Parsed.Invalid)
        // 长度上限先于解析：一个几百字符的 param 不值得被 JSON 解析器碰。
        val long = "x".repeat(TurnCompletionPolicy.MAX_PARAM_CHARS + 1)
        assertTrue(TurnCompletionPolicy.parseParam(long) is TurnCompletionPolicy.Parsed.Invalid)
    }

    @Test
    fun `首个事件受理 最小间隔内的重复被静默忽略`() {
        val state = TurnCompletionPolicy.State()
        assertNull(state.claim(1_000L))
        assertEquals(TurnCompletionPolicy.DropReason.TOO_SOON, state.claim(1_000L + 1L))
        assertEquals(
            TurnCompletionPolicy.DropReason.TOO_SOON,
            state.claim(1_000L + TurnCompletionPolicy.MIN_INTERVAL_MS - 1L),
        )
    }

    @Test
    fun `恰好等于最小间隔时受理`() {
        val state = TurnCompletionPolicy.State()
        assertNull(state.claim(0L))
        // 边界写成 `<` 还是 `<=` 是可验证的，别留悬念。
        assertNull(state.claim(TurnCompletionPolicy.MIN_INTERVAL_MS))
    }

    @Test
    fun `受理频率被限制在每秒一次`() {
        val state = TurnCompletionPolicy.State()
        assertNull(state.claim(0L))
        assertNull(state.claim(1_000L))
        assertNull(state.claim(2_000L))
        // 第 4 次紧跟在第 3 次之后：被拒。
        assertEquals(TurnCompletionPolicy.DropReason.TOO_SOON, state.claim(2_500L))
        // 再等满一秒又恢复：限流是「退让」而不是「封禁」。
        assertNull(state.claim(3_500L))
    }

    @Test
    fun `最小间隔先于每秒配额生效`() {
        // 这一条记录的是实现的实际行为，而不是期望行为：`claim` 先重置秒窗口再判配额，
        // 而最小间隔 1s 恰好等于窗口长度，于是「同一秒内被受理 3 次」在当前参数下
        // 根本不可能发生 —— DropReason.LIMIT 是**当前不可达**的分支（见报告里的取舍一条）。
        val state = TurnCompletionPolicy.State()
        // 每 100ms 发一次，连续 10 次：只有第 1 次能过最小间隔。
        var accepted = 0
        for (index in 0 until 10) {
            if (state.claim(index * 100L) == null) accepted++
        }
        assertEquals(1, accepted)
    }

    @Test
    fun `密集请求被压成每秒一次`() {
        val state = TurnCompletionPolicy.State()
        // 每 250ms 发一次，覆盖 0..2750ms：通过的只落在 0/1000/2000 三处。
        var accepted = 0
        for (index in 0 until 12) {
            if (state.claim(index * 250L) == null) accepted++
        }
        assertEquals(3, accepted)
    }

    @Test
    fun `时钟回拨后不会把事件永久卡住`() {
        val state = TurnCompletionPolicy.State()
        assertNull(state.claim(10_000L))
        // 回拨到这个时刻（差值 -9000，小于最小间隔）：被忽略，但**不能**把后续事件也卡死。
        assertNotNull(state.claim(1_000L))
        // 时间继续正常推进到远超最小间隔之后：必须重新受理。
        assertNull(state.claim(20_000L))
    }

    @Test
    fun `限流命中的原因只有两种`() {
        val state = TurnCompletionPolicy.State()
        state.claim(0L)
        val dropped = state.claim(1L)
        assertNotNull(dropped)
        // 契约写在 TaskNotification.TurnCompletionReceipt 的 KDoc 里：命中限流时
        // ok 仍然是 true，只是 accepted=false；dropped 是给审计用的枚举，访客侧不解析文案。
        assertTrue(
            dropped == TurnCompletionPolicy.DropReason.TOO_SOON ||
                dropped == TurnCompletionPolicy.DropReason.LIMIT,
        )
    }
}
