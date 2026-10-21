package io.deepseekharness.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这段脚本是塞进访客页面的字符串，编译期不会检查它，所以只能在这里钉住几条**语义约束**：
 * 只在缺失时补、不覆盖已有实现、按规范取第一个中止信号的 reason、用完摘监听。
 * 真机上是否生效属于 WebView 行为，由 instrumented 测试或手动验证覆盖，JVM 单测做不到。
 */
class HarnessPolyfillTest {
    private val script = HarnessPolyfill.SCRIPT

    @Test
    fun `只在 AbortSignal 缺少 any 时才补，已经是函数就原样退出`() {
        assertTrue(script.contains("typeof AbortSignal.any === 'function'"))
        assertTrue(script.contains("AbortSignal.any = function (signals)"))
        // 先判缺失再赋值：顺序反了会把新 WebView 的原生实现替换掉。
        assertTrue(script.indexOf("typeof AbortSignal.any === 'function'") < script.indexOf("AbortSignal.any = function"))
    }

    @Test
    fun `按规范取第一个已中止信号的 reason，并摘掉所有监听`() {
        assertTrue(script.contains("if (list[early].aborted)"))
        assertTrue(script.contains("controller.abort(list[early].reason)"))
        assertTrue(script.contains("removeEventListener('abort', onAbort)"))
        assertTrue(script.contains("controller.abort(event.target.reason)"))
    }

    @Test
    fun `非 AbortSignal 入参按 TypeError 拒绝，不静默放过`() {
        assertTrue(script.contains("throw new TypeError('AbortSignal.any: member is not an AbortSignal')"))
        assertTrue(script.contains("typeof signal.aborted !== 'boolean'"))
    }

    @Test
    fun `脚本自包含且可重复执行`() {
        // 整段是立即执行函数：重复注入只会再走一次「已经是函数」的早退，不产生副作用。
        assertTrue(script.startsWith("(function () {"))
        assertTrue(script.trimEnd().endsWith("})();"))
        // 不引用页面全局变量，也不依赖任何注入顺序。
        assertFalse(script.contains("window."))
        assertFalse(script.contains("document."))
        // 内联进页面时才不会把 <script> 提前截断。
        assertFalse(script.contains("</script"))
    }
}
