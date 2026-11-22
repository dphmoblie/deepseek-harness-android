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
    // 原始字符串里的换行跟着文件走（仓库里是 CRLF）；多行断言前统一成 LF，否则「守卫行 +
    // 下一行」这种邻近性检查永远匹配不上。
    private val script = HarnessPolyfill.SCRIPT.replace("\r\n", "\n")

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
    fun `旧 WebView 缺 throwIfAborted 时补上，而且补在 AbortSignal 的早退之前`() {
        assertTrue(script.contains("typeof AbortSignal.prototype.throwIfAborted !== 'function'"))
        assertTrue(
            script.contains("Object.defineProperty(AbortSignal.prototype, 'throwIfAborted'"),
        )
        // 脚本末尾对「已经有 AbortSignal.any」的 WebView 会 return；补丁排在它后面就永远不生效。
        assertTrue(
            script.indexOf("Object.defineProperty(AbortSignal.prototype, 'throwIfAborted'") <
                script.indexOf("AbortSignal.any = function"),
        )
    }

    @Test
    fun `缺 reason 时自己记住 abort 的原因，throwIfAborted 抛的是那个原因`() {
        assertTrue(script.contains("var nativeReason = 'reason' in AbortSignal.prototype"))
        assertTrue(script.contains("Object.defineProperty(AbortSignal.prototype, 'reason'"))
        assertTrue(script.contains("state.reason = arguments.length > 0 ? reason : abortReasonFallback()"))
        assertTrue(script.contains("if (this.aborted) { throw reasonOf(this); }"))
        // 未中止时 throwIfAborted 必须静默返回。
        assertFalse(script.contains("value: function () {\n          throw reasonOf(this);"))
    }

    @Test
    fun `新 WebView 上不代理 AbortController abort`() {
        // reason 与 EventTarget 都在时这段整体跳过：不覆盖、不代理，只在缺东西时才包一层。
        assertTrue(script.contains("if (!nativeReason || !nativeEvents)"))
        assertTrue(script.indexOf("if (!nativeReason || !nativeEvents)") < script.indexOf("nativeAbort.call(this, reason)"))
    }

    @Test
    fun `默认中止原因不依赖 DOMException 构造器`() {
        assertTrue(script.contains("try { return new DOMException(message, 'AbortError'); } catch (error) { }"))
        assertTrue(script.contains("fallback.name = 'AbortError'"))
    }

    @Test
    fun `补齐旧 WebView 缺少的 ES2023 数组方法`() {
        for (method in listOf("toSorted", "toReversed", "toSpliced", "with")) {
            assertTrue(
                "missing Array.prototype.$method guard",
                script.contains("typeof Array.prototype.$method !== 'function'"),
            )
        }
        // 先判缺失再 defineProperty：顺序反了会把新 WebView 的原生实现替换掉。
        assertTrue(
            script.indexOf("typeof Array.prototype.toSorted !== 'function'") <
                script.indexOf("Array.prototype, 'toSorted'"),
        )
    }

    @Test
    fun `补齐 Promise withResolvers 与 URL canParse`() {
        assertTrue(script.contains("typeof Promise.withResolvers !== 'function'"))
        assertTrue(script.contains("typeof URL.canParse !== 'function'"))
    }

    @Test
    fun `脚本保持 ES5 语法，document-start 注入不会在旧 WebView 解析失败`() {
        assertFalse(script.contains("=>"))
        assertFalse(script.contains("const "))
        assertFalse(script.contains("let "))
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

    @Test
    fun `补齐 Android 10 出厂 WebView 缺少的一层 API`() {
        // 清单来自 scripts/webview-compat-scan.mjs 在官方产物里数到的调用点，不是凭感觉列的。
        val guards = listOf(
            "typeof Object.hasOwn !== 'function'",
            "typeof Array.prototype.at !== 'function'",
            "typeof String.prototype.at !== 'function'",
            "typeof String.prototype.replaceAll !== 'function'",
            "typeof Promise.allSettled !== 'function'",
            "typeof Array.prototype.findLast !== 'function'",
            "typeof Array.prototype.findLastIndex !== 'function'",
            "typeof AbortSignal.timeout !== 'function'",
            "typeof Element.prototype.replaceChildren !== 'function'",
            "typeof Blob.prototype.text !== 'function'",
        )
        for (guard in guards) {
            assertTrue("缺少守卫：$guard", script.contains(guard))
        }
    }

    @Test
    fun `每一条 android10 补丁都紧跟在它的 typeof 守卫后面，不会无条件覆盖新内核`() {
        // 逐对检查「守卫行 + 下一行的赋值」：顺序反了就是把新内核的原生实现顶掉。
        val guarded = listOf(
            "if (typeof Object.hasOwn !== 'function') {\n    Object.defineProperty(Object, 'hasOwn'",
            "if (typeof Promise !== 'undefined' && typeof Promise.allSettled !== 'function') {\n    Promise.allSettled = function",
            "if (typeof String.prototype.replaceAll !== 'function') {\n    Object.defineProperty(String.prototype, 'replaceAll'",
            "if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout !== 'function') {\n    AbortSignal.timeout = function",
            "if (typeof Element !== 'undefined' && typeof Element.prototype.replaceChildren !== 'function') {\n    Element.prototype.replaceChildren = function",
        )
        for (pair in guarded) {
            assertTrue("补丁没有紧跟守卫：\n$pair", script.contains(pair))
        }
    }

    @Test
    fun `AbortSignal timeout 必须排在 any 的早退之前`() {
        val timeoutAt = script.indexOf("AbortSignal.timeout = function")
        val earlyReturnAt = script.indexOf("typeof AbortSignal.any === 'function') return")
        assertTrue("找不到 timeout 补丁", timeoutAt > 0)
        assertTrue("找不到 any 的早退", earlyReturnAt > 0)
        assertTrue(
            "timeout 排在早退之后，在已经有 any 的内核上永远不会补上",
            timeoutAt < earlyReturnAt,
        )
    }
}
