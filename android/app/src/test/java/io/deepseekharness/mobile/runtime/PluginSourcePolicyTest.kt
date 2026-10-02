package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 插件导入来源判定的纯逻辑测试。
 *
 * 这层的价值在于：同一条规则同时活在访客脚本（`validateImportSource`）与前端
 * （`src/platform/plugins.ts`）里，三处只要有一处放松，别的入口就能把危险字符串
 * 送进 `npm install`。用例逐条对着那两处的用例写，保证三份实现拒绝的是同一批输入。
 */
class PluginSourcePolicyTest {
    @Test
    fun acceptsPackageNamesAndHttpsAddresses() {
        assertEquals("npm", PluginSourcePolicy.classify("dsh-plugin-demo"))
        assertEquals("npm", PluginSourcePolicy.classify("@scope/plugin"))
        assertEquals("url", PluginSourcePolicy.classify("https://example.com/plugin.tgz"))
        assertEquals("url", PluginSourcePolicy.classify("https://example.com:8443/a/b.tgz?x=1#frag"))
        assertEquals("git", PluginSourcePolicy.classify("git+https://github.com/u/r.git"))
        assertEquals("git", PluginSourcePolicy.classify("git+https://github.com/u/r.git#v1.2.3"))
    }

    @Test
    fun rejectsNonHttpsSchemes() {
        // 明文 http、git 协议、ssh、本地文件与 data URL：一律不允许进入安装路径。
        listOf(
            "http://example.com/x.tgz",
            "git://example.com/u/r.git",
            "ssh://git@example.com/u/r.git",
            "file:///etc/passwd",
            "data:text/plain,x",
            "npm:foo",
            "//example.com/x.tgz",
        ).forEach { assertNull(it, PluginSourcePolicy.classify(it)) }
    }

    @Test
    fun rejectsOptionInjectionAndTraversal() {
        listOf(
            "-foo",
            "--registry=http://evil",
            "plugin..name",
            "https://example.com/../x.tgz",
            "https://example.com/%2e%2e/x.tgz",
            "https://example.com/%2E/x.tgz",
        ).forEach { assertNull(it, PluginSourcePolicy.classify(it)) }
    }

    @Test
    fun rejectsUserinfoAndMalformedHosts() {
        // userinfo 会随地址一路进 npm 的日志与错误信息，必须在结构检查处就拒掉。
        listOf(
            "https://user:pass@example.com/x.tgz",
            "https://token@example.com/x.tgz",
            "https://@example.com/x.tgz",
            "https://exa mple.com/x.tgz",
            "https://example.com:999999/x.tgz",
        ).forEach { assertNull(it, PluginSourcePolicy.classify(it)) }
    }

    @Test
    fun rejectsWhitespaceQuotesAndControlCharacters() {
        listOf(
            "",
            " ",
            "foo bar",
            "foo\"bar",
            "foo<bar>",
            "foo`bar`",
            "foo\nbar",
            "foo\tbar",
            "'foo'",
            "foo\\bar",
        ).forEach { assertNull(it, PluginSourcePolicy.classify(it)) }
    }

    @Test
    fun rejectsOverlongInput() {
        val long = "https://example.com/" + "a".repeat(PluginSourcePolicy.MAX) + ".tgz"
        assertNull(PluginSourcePolicy.classify(long))
        assertNull(PluginSourcePolicy.classify(null))
        // 边界：正好 512 字符仍然合法。
        val prefix = "https://example.com/"
        val exact = prefix + "a".repeat(PluginSourcePolicy.MAX - prefix.length - 4) + ".tgz"
        assertEquals(PluginSourcePolicy.MAX, exact.length)
        assertEquals("url", PluginSourcePolicy.classify(exact))
    }

    @Test
    fun rejectsInvalidGitRefs() {
        // ref 会作为 `#<ref>` 交给 npm 解析：空 ref 与带特殊字符的 ref 都不接受。
        listOf(
            "git+https://github.com/u/r.git#",
            "git+https://github.com/u/r.git#a b",
            "git+https://github.com/u/r.git#" + "a".repeat(129),
        ).forEach { assertNull(it, PluginSourcePolicy.classify(it)) }
        assertEquals("git", PluginSourcePolicy.classify("git+https://github.com/u/r.git#" + "a".repeat(128)))
    }
}
