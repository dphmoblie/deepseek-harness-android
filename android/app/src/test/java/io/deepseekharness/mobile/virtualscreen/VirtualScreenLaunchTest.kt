package io.deepseekharness.mobile.virtualscreen

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳转应用（`action = "launch"`）与跟随（`action = "follow"`）的纯函数契约：
 * 链接白名单与禁用字符、包名与组件规则、`resolve-activity`/`dumpsys` 输出解析、
 * 跟随排除清单（本应用 / 输入法 / 桌面 / 系统壳）与「没有需要跟随的应用」这一正常结论。
 */
class VirtualScreenLaunchTest {
    @Test fun `链接只放行 http https market 且拒绝 shell 元字符`() {
        assertEquals("https://example.com/a?b=1", VirtualScreenPolicy.launchUri("https://example.com/a?b=1"))
        assertEquals("market://details?id=com.tencent.mm", VirtualScreenPolicy.launchUri("market://details?id=com.tencent.mm"))
        // scheme 大小写不敏感，且原样交给 `am start -d`，不改写调用方给的链接。
        assertEquals("HTTPS://Example.com", VirtualScreenPolicy.launchUri("HTTPS://Example.com"))
        for (value in listOf("file:///sdcard/x", "javascript:alert(1)", "content://media/external/images", "intent://x")) {
            val error = assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.launchUri(value) }
            assertTrue(error.message!!, error.message!!.contains("只支持 http/https/market 链接"))
        }
        for (value in listOf(
            "https://a b",
            "https://a;id",
            "https://a|id",
            "https://a`id`",
            "https://a\nb",
            "https://a\$x",
            "https://a\"b",
            "https://a<b>",
            "https://a\\b",
            "https://a'b",
        )) {
            val error = assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.launchUri(value) }
            assertTrue(error.message!!, error.message!!.contains("链接不能包含"))
        }
        // 控制字符（这里用制表符）同样拒绝：它们是命令参数里最容易被误解释的一类字符。
        val control = assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.launchUri("https://a\tb") }
        assertTrue(control.message!!, control.message!!.contains("链接不能包含"))
        val empty = assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.launchUri("") }
        assertTrue(empty.message!!, empty.message!!.contains("链接长度必须在 1～2048 个字符内"))
        val oversized = assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenPolicy.launchUri("https://" + "a".repeat(VirtualScreenPolicy.MAX_LAUNCH_URI_CHARS))
        }
        assertTrue(oversized.message!!, oversized.message!!.contains("链接长度必须在 1～2048 个字符内"))
    }

    @Test fun `包名与组件规则沿用既有校验`() {
        assertEquals("com.tencent.mm", VirtualScreenPolicy.launchPackage("com.tencent.mm"))
        for (value in listOf("com", "1com.a", "com.tencent.mm/", "", "com..a")) {
            val error = assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.launchPackage(value) }
            assertTrue(error.message!!, error.message!!.contains("包名格式无效"))
        }
        // 组件必须带 `包名/类名`，只给包名要被 `component` 既有规则挡下。
        val error = assertThrows(IllegalArgumentException::class.java) {
            VirtualScreenPolicy.launchRequest(JSONObject().put("component", "com.tencent.mm"))
        }
        assertTrue(error.message!!, error.message!!.contains("目标应用入口无效"))
    }

    @Test fun `launch 三选一必须且只能给一个`() {
        assertEquals(
            VirtualScreenPolicy.LaunchRequest.Component("com.tencent.mm/.ui.LauncherUI"),
            VirtualScreenPolicy.launchRequest(JSONObject().put("component", "com.tencent.mm/.ui.LauncherUI")),
        )
        assertEquals(
            VirtualScreenPolicy.LaunchRequest.Package("com.a.b"),
            VirtualScreenPolicy.launchRequest(JSONObject().put("package", "com.a.b")),
        )
        assertEquals(
            VirtualScreenPolicy.LaunchRequest.Uri("https://a.com"),
            VirtualScreenPolicy.launchRequest(JSONObject().put("uri", "https://a.com")),
        )
        // 同时给两个也拒绝：无法判断 AI 想要哪一个，猜一个等于替调用方做决定。
        for (p in listOf(
            JSONObject(),
            JSONObject().put("component", "com.a.b/.C").put("package", "com.a.b"),
            JSONObject().put("package", "com.a.b").put("uri", "https://a.com"),
            JSONObject().put("component", "com.a.b/.C").put("uri", "https://a.com"),
        )) {
            val error = assertThrows(IllegalArgumentException::class.java) { VirtualScreenPolicy.launchRequest(p) }
            assertTrue(error.message!!, error.message!!.contains("必须且只能提供 component、package、uri 中的一个"))
        }
        // 类型错误交给 JSONException，由既有 errorCode 统一映射成 VIRTUAL_SCREEN_INVALID。
        val wrongType = assertThrows(JSONException::class.java) {
            VirtualScreenPolicy.launchRequest(JSONObject().put("uri", 5))
        }
        assertEquals("VIRTUAL_SCREEN_INVALID", VirtualScreenPolicy.errorCode(wrongType))
    }

    @Test fun `resolve-activity 与 am start 输出都能解析出组件`() {
        // `cmd package resolve-activity --brief` 的典型输出：独占一行的组件。
        assertEquals("com.tencent.mm/.ui.LauncherUI", VirtualScreenPolicy.resolvedComponent("com.tencent.mm/.ui.LauncherUI\n"))
        assertEquals("com.tencent.mm/.ui.LauncherUI", VirtualScreenPolicy.resolvedComponent("  com.tencent.mm/.ui.LauncherUI  \n"))
        assertEquals("com.a/.B", VirtualScreenPolicy.resolvedComponent("Starting: Intent { cmp=com.a/.B }"))
        assertEquals("com.a/.B", VirtualScreenPolicy.resolvedComponent("Status: ok\nActivity: com.a/.B\nThisTime: 123"))
        // URL 的 `域名/路径` 不是组件：宽松的行内匹配会把链接当成启动入口。
        assertNull(VirtualScreenPolicy.resolvedComponent("https://example.com/some/path"))
        assertNull(VirtualScreenPolicy.resolvedComponent("no component here"))
        assertNull(VirtualScreenPolicy.resolvedComponent(""))
    }

    @Test fun `resolve-activity 真机输出跳过元信息行只认组件行`() {
        // 2026-10-05 HONOR AAP-AN00 / Android 17 实测原文：`cmd package resolve-activity --brief <包名>`
        // 先打印一行 key=value 元信息、再打印组件行。解析必须跳过元信息行只认组件行，
        // 不能取第一行，也不能把整段 stdout 直接 trim。
        val device = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.microsoft.emmx/com.microsoft.ruby.Main
        """.trimIndent()
        assertEquals("com.microsoft.emmx/com.microsoft.ruby.Main", VirtualScreenPolicy.resolvedComponent(device))
        // 只有元信息行时必须返回空：`match=0x108000` 这类 key=value 绝不能被当成解析成功。
        assertNull(
            VirtualScreenPolicy.resolvedComponent(
                "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n",
            ),
        )
        // 解析不到启动入口时设备输出里没有组件行，同样返回空（调用方据此报「没有启动入口」）。
        assertNull(VirtualScreenPolicy.resolvedComponent("No activity found\n"))
        assertNull(VirtualScreenPolicy.resolvedComponent("Activity not found: Intent { act=android.intent.action.MAIN }\n"))
        // 多行候选时以最后一行为准：前缀摘要里的组件不当作结果。
        assertEquals("com.a/.Last", VirtualScreenPolicy.resolvedComponent("com.a/.First\ncom.a/.Last\n"))
        assertEquals(
            "com.a/.Real",
            VirtualScreenPolicy.resolvedComponent("Starting: Intent { cmp=com.a/.Real }\nActivity: com.a/.Real\n"),
        )
    }

    @Test fun `主屏最前台按 Display 0 绑定而不是取第一个匹配`() {
        // 2026-10-05 HONOR AAP-AN00 / Android 17 实测：同一次 `dumpsys activity activities` 里
        // `topResumedActivity=` 每个显示器一行（主屏是 maaend，另两行属于 MAA 的虚拟屏）。
        // 下面三行逐字取自实测输出，外层 `Display #` 段头按 AOSP 输出形态补上（实测片段没带段头）。
        val main = "com.aliothmoon.maafw.maaend/com.aliothmoon.maafw.MainActivity"
        val endfield = "com.hypergryph.endfield/com.u8.sdk.U8UnityContext"
        val arknights = "com.hypergryph.arknights/com.u8.sdk.U8UnityContext"
        val dump = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
            Display #0 (activities from top to bottom):
              * Task{38692625 #6041 type=standard A=10345:com.aliothmoon.maafw.maaend}
                topResumedActivity=ActivityRecord{38692625 u0 com.aliothmoon.maafw.maaend/com.aliothmoon.maafw.MainActivity t6041}
            Display #17 (activities from top to bottom):
              * Task{93166160 #6132 type=standard A=10456:com.hypergryph.endfield}
                topResumedActivity=ActivityRecord{93166160 u0 com.hypergryph.endfield/com.u8.sdk.U8UnityContext t6132}
            Display #27 (activities from top to bottom):
              * Task{83666860 #6131 type=standard A=10457:com.hypergryph.arknights}
                topResumedActivity=ActivityRecord{83666860 u0 com.hypergryph.arknights/com.u8.sdk.U8UnityContext t6131}
            ResumedActivity: ActivityRecord{38692625 u0 com.aliothmoon.maafw.maaend/com.aliothmoon.maafw.MainActivity t6041}
        """.trimIndent()
        assertEquals(main, VirtualScreenPolicy.resumedComponent(dump, 0))
        assertEquals(endfield, VirtualScreenPolicy.resumedComponent(dump, 17))
        assertEquals(arknights, VirtualScreenPolicy.resumedComponent(dump, 27))
        assertNull(VirtualScreenPolicy.resumedComponent(dump, 5))
        // 显式绑定 display 0：即使第一个 `topResumedActivity=` 属于虚拟屏、主屏段排在它后面，
        // 主屏候选也只能来自 Display #0 段（取「第一个匹配」会错拿 endfield）。
        val virtualFirst = """
            Display #17 (activities from top to bottom):
              * Task{93166160 #6132 type=standard A=10456:com.hypergryph.endfield}
                topResumedActivity=ActivityRecord{93166160 u0 com.hypergryph.endfield/com.u8.sdk.U8UnityContext t6132}
            Display #0 (activities from top to bottom):
              * Task{38692625 #6041 type=standard A=10345:com.aliothmoon.maafw.maaend}
                topResumedActivity=ActivityRecord{38692625 u0 com.aliothmoon.maafw.maaend/com.aliothmoon.maafw.MainActivity t6041}
        """.trimIndent()
        assertEquals(main, VirtualScreenPolicy.resumedComponent(virtualFirst, 0))
        assertEquals(endfield, VirtualScreenPolicy.resumedComponent(virtualFirst, 17))
    }

    private val dump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{7ba5a4c #31 type=standard A=10123:com.main.app}
            mResumedActivity: ActivityRecord{1a2b3c4 u0 com.main.app/.MainActivity t31}
          ResumedActivity: ActivityRecord{deadbeef u0 com.other.app/.Ghost t9}
        Display #2 (activities from top to bottom):
          * Task{9c8d7e6 #5 type=standard A=10456:com.target.app}
            mResumedActivity: ActivityRecord{5f6g7h8 u0 com.target.app/.DetailActivity t5}
        mHomeProcess: ProcessRecord{abc123 4321:com.android.launcher3/u0a12}
    """.trimIndent()

    @Test fun `resumedComponent 按显示器分段且只认 mResumedActivity`() {
        assertEquals("com.main.app/.MainActivity", VirtualScreenPolicy.resumedComponent(dump, 0))
        assertEquals("com.target.app/.DetailActivity", VirtualScreenPolicy.resumedComponent(dump, 2))
        assertNull(VirtualScreenPolicy.resumedComponent(dump, 3))
        assertNull(VirtualScreenPolicy.resumedComponent(dump, -1))
        // 裸 `ResumedActivity:` 不是结果：它排在 mResumedActivity 前面也必须被跳过。
        val bare = """
            Display #0 (activities from top to bottom):
              ResumedActivity: ActivityRecord{deadbeef u0 com.other.app/.Ghost t9}
              mResumedActivity: ActivityRecord{1a2b3c4 u0 com.main.app/.MainActivity t31}
        """.trimIndent()
        assertEquals("com.main.app/.MainActivity", VirtualScreenPolicy.resumedComponent(bare, 0))
        // 只有裸 ResumedActivity 时返回空，而不是把它当成跟随候选。
        val onlyBare = """
            Display #0 (activities from top to bottom):
              ResumedActivity: ActivityRecord{deadbeef u0 com.other.app/.Ghost t9}
        """.trimIndent()
        assertNull(VirtualScreenPolicy.resumedComponent(onlyBare, 0))
    }

    @Test fun `homePackage 从 mHomeProcess 行解析桌面包名`() {
        assertEquals("com.android.launcher3", VirtualScreenPolicy.homePackage(dump))
        assertNull(VirtualScreenPolicy.homePackage("Display #0 (activities from top to bottom):\n"))
    }

    @Test fun `跟随判定排除本应用 输入法 桌面与系统壳`() {
        val self = "io.deepseekharness.mobile"
        val inputMethods = setOf("com.sohu.inputmethod.sogou", "com.baidu.input")
        val home = "com.android.launcher3"
        assertTrue(VirtualScreenPolicy.followable("com.tencent.mm", self, inputMethods, home))
        // 本应用必须排除：副屏预览界面就跑在本应用里，把自己拉到副屏等于盖掉会话。
        assertFalse(VirtualScreenPolicy.followable(self, self, inputMethods, home))
        assertFalse(VirtualScreenPolicy.followable("com.sohu.inputmethod.sogou", self, inputMethods, home))
        assertFalse(VirtualScreenPolicy.followable(home, self, inputMethods, home))
        assertFalse(VirtualScreenPolicy.followable("com.android.systemui", self, inputMethods, home))
        assertFalse(VirtualScreenPolicy.followable("com.android.shell", self, inputMethods, home))
        assertFalse(VirtualScreenPolicy.followable("com", self, inputMethods, home))
        assertFalse(VirtualScreenPolicy.followable("", self, inputMethods, home))
    }

    @Test fun `跟随空结果给出可区分的中文原因`() {
        val self = "io.deepseekharness.mobile"
        val inputMethods = setOf("com.sohu.inputmethod.sogou")
        val home = "com.android.launcher3"
        assertTrue(VirtualScreenPolicy.followNoneReason(null, self, inputMethods, home).contains("主屏没有解析到最前台应用"))
        assertTrue(VirtualScreenPolicy.followNoneReason("com", self, inputMethods, home).contains("主屏包名格式无法识别"))
        assertTrue(VirtualScreenPolicy.followNoneReason(self, self, inputMethods, home).contains("本应用自己"))
        assertTrue(VirtualScreenPolicy.followNoneReason(home, self, inputMethods, home).contains("桌面"))
        assertTrue(VirtualScreenPolicy.followNoneReason("com.sohu.inputmethod.sogou", self, inputMethods, home).contains("输入法"))
        assertTrue(VirtualScreenPolicy.followNoneReason("com.android.systemui", self, inputMethods, home).contains("系统界面"))
        // 可跟随的候选不会走到空结果分支，兜底文案保持通用。
        assertEquals(
            VirtualScreenPolicy.FOLLOW_NONE_MESSAGE,
            VirtualScreenPolicy.followNoneReason("com.tencent.mm", self, inputMethods, home),
        )
        assertEquals("VIRTUAL_SCREEN_FOLLOW_NONE", VirtualScreenPolicy.FOLLOW_NONE_CODE)
    }

    @Test fun `launch 的失败码与文案指向下一步`() {
        val unresolved = VirtualScreenPolicy.launchUnresolvedFailure("com.not.installed")
        assertEquals("VIRTUAL_SCREEN_LAUNCH_UNRESOLVED", unresolved.code)
        assertTrue(unresolved.message.orEmpty().contains("未能在设备上解析到 com.not.installed 的启动入口"))
        val failed = VirtualScreenPolicy.launchFailedFailure("https://example.com")
        assertEquals("VIRTUAL_SCREEN_LAUNCH_FAILED", failed.code)
        assertTrue(failed.message.orEmpty().contains("在副屏上启动 https://example.com 失败"))
        // 聚合入口的两种语义：给不出包名（链接）走启动失败，给了包名走解析失败。
        assertEquals("VIRTUAL_SCREEN_LAUNCH_FAILED", VirtualScreenPolicy.launchFailure(null, "x").code)
        assertEquals("VIRTUAL_SCREEN_LAUNCH_UNRESOLVED", VirtualScreenPolicy.launchFailure("com.a", "x").code)
        // 错误码统一从 errorCode 出口，不会被 else 兜底改成「副屏不可用」。
        assertEquals("VIRTUAL_SCREEN_LAUNCH_UNRESOLVED", VirtualScreenPolicy.errorCode(unresolved))
        assertEquals("VIRTUAL_SCREEN_LAUNCH_FAILED", VirtualScreenPolicy.errorCode(failed))
    }

    @Test fun `selfPackage 字段名与宿主注入保持一致`() {
        assertEquals("selfPackage", VirtualScreenPolicy.SELF_PACKAGE_FIELD)
        // 宿主没注入时按空串处理，只是少一层「排除本应用」的自我保护。
        assertEquals("", JSONObject().optString(VirtualScreenPolicy.SELF_PACKAGE_FIELD))
    }
}
