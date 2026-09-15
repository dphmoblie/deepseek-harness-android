package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * 入口页缓存键与缓存模式的契约。
 *
 * 这里覆盖的是「WebView 能不能安全复用入口页」这一件事：入口 index.html 没有内容哈希，
 * 唯一的失效手段就是 URL 变化，所以两维版本键、未知态回退与缓存模式的对应关系都必须钉在测试里。
 */
class HarnessPageCacheTest {
    private val entry = "http://127.0.0.1:3080/"

    @Test
    fun apkVersionAloneChangesTheEntryCacheKey() {
        assertEquals(
            "http://127.0.0.1:3080/?appVersion=0.1.21-preview&runtimeVersion=2026.08.1",
            HarnessPageUrl.withVersions(entry, "0.1.21-preview", "2026.08.1"),
        )
        assertNotEquals(
            HarnessPageUrl.withVersions(entry, "0.1.21-preview", "2026.08.1"),
            HarnessPageUrl.withVersions(entry, "0.1.22", "2026.08.1"),
        )
    }

    @Test
    fun runtimeVersionAloneChangesTheEntryCacheKey() {
        // 在线更新运行时之后必须换键，否则 WebView 会继续复用旧 rootfs 里那份旧前端。
        assertNotEquals(
            HarnessPageUrl.withVersions(entry, "0.1.21-preview", "2026.08.1"),
            HarnessPageUrl.withVersions(entry, "0.1.21-preview", "2026.08.2"),
        )
    }

    @Test
    fun everyVersionCombinationGetsItsOwnEntryUrl() {
        val urls = listOf("0.1.21-preview", "0.1.22").flatMap { app ->
            listOf("2026.08.1", "2026.08.2").map { runtime ->
                HarnessPageUrl.withVersions(entry, app, runtime)
            }
        }

        assertEquals(urls.size, urls.toSet().size)
        assertTrue(urls.all { HarnessPageCache.modeFor(it) == HarnessCacheMode.NORMAL })
    }

    @Test
    fun identicalVersionInputsAlwaysProduceTheSameEntryUrl() {
        val expected = HarnessPageUrl.withVersions(entry, "0.1.21-preview", "2026.08.1")

        repeat(3) {
            assertEquals(expected, HarnessPageUrl.withVersions(entry, "0.1.21-preview", "2026.08.1"))
        }
    }

    @Test
    fun versionKeysNeverMoveIntoTheEntryPath() {
        // 版本只能待在查询串里：一旦混进路径，运行时就不再返回 index.html 了。
        val page = URI(HarnessPageUrl.withVersions(entry, "0.1.21-preview", "2026.08.1"))

        assertEquals("/", page.rawPath)
        assertEquals("127.0.0.1", page.host)
        assertEquals(3080, page.port)
        assertNull(page.rawFragment)
    }

    @Test
    fun missingRuntimeVersionFallsBackToAFixedMarker() {
        val unknown = HarnessPageUrl.withVersions(entry, "0.1.21-preview", null)

        assertEquals(
            "http://127.0.0.1:3080/?appVersion=0.1.21-preview&runtimeVersion=none",
            unknown,
        )
        // 空串同样按「未知」处理：它不含任何信息，不能变成第三个缓存键。
        assertEquals(unknown, HarnessPageUrl.withVersions(entry, "0.1.21-preview", ""))
        // 连续多次「未知」必须落在同一个 URL 上，否则清单读取时机一变就重新下载入口页。
        assertEquals(unknown, HarnessPageUrl.withVersions(entry, "0.1.21-preview", null))
    }

    @Test
    fun unknownRuntimeVersionIsNeverCached() {
        val unknown = HarnessPageUrl.withVersions(entry, "0.1.21-preview", null)

        assertEquals(HarnessCacheMode.BYPASS, HarnessPageCache.modeFor(unknown))
        // 占位值不代表任何一份真实前端产物：即使清单版本恰好写成 none，也按未知处理，
        // 否则「未知」与「版本为 none」会共享同一条入口页缓存。
        assertEquals(
            HarnessCacheMode.BYPASS,
            HarnessPageCache.modeFor(HarnessPageUrl.withVersions(entry, "0.1.21-preview", "none")),
        )
        // 装好运行时之后键必须换掉，用户不能继续吃「未安装」时缓存下来的入口页。
        assertNotEquals(unknown, HarnessPageUrl.withVersions(entry, "0.1.21-preview", "2026.08.1"))
        assertNotEquals(
            HarnessCacheMode.BYPASS,
            HarnessPageCache.modeFor(HarnessPageUrl.withVersions(entry, "0.1.21-preview", "2026.08.1")),
        )
    }

    @Test
    fun malformedRuntimeVersionStillFailsLoudly() {
        // 非空的非法值是调用方契约错误，必须当场抛错，不能悄悄降级成「未知」而掩盖问题。
        assertThrows(IllegalArgumentException::class.java) {
            HarnessPageUrl.withVersions(entry, "0.1.21-preview", "bad&injected=1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HarnessPageUrl.withVersions(entry, "0.1.21-preview", "v".repeat(97))
        }
    }

    @Test
    fun cacheModeIsNormalOnlyForFullyVersionedEntryUrls() {
        assertEquals(
            HarnessCacheMode.NORMAL,
            HarnessPageCache.modeFor(HarnessPageUrl.withVersions(entry, "0.1.21-preview", "2026.08.1")),
        )
        // 参数书写顺序不影响判断：只要两维都在，缓存键就是唯一的。
        assertEquals(
            HarnessCacheMode.NORMAL,
            HarnessPageCache.modeFor(
                "http://127.0.0.1:3080/?runtimeVersion=2026.08.1&appVersion=0.1.21-preview",
            ),
        )
    }

    @Test
    fun cacheModeBypassesCacheForEntryUrlsMissingAVersionDimension() {
        listOf(
            // 完全没有版本键
            entry,
            // 只有 APK 版本：旧版外壳拼出来的形状，同样不能复用缓存
            "http://127.0.0.1:3080/?appVersion=0.1.21-preview",
            // 只有运行时版本
            "http://127.0.0.1:3080/?runtimeVersion=2026.08.1",
            // 维度在但取值为空
            "http://127.0.0.1:3080/?appVersion=&runtimeVersion=2026.08.1",
            "http://127.0.0.1:3080/?appVersion=0.1.21-preview&runtimeVersion=",
            // 取值不合规
            "http://127.0.0.1:3080/?appVersion=0.1.21-preview&runtimeVersion=${"v".repeat(97)}",
            "http://127.0.0.1:3080/?appVersion=0.1.21 preview&runtimeVersion=2026.08.1",
            // 不是合法 URL
            "not a url",
        ).forEach { url ->
            assertEquals(url, HarnessCacheMode.BYPASS, HarnessPageCache.modeFor(url))
            assertNull(url, HarnessPageUrl.versionCacheKey(url))
        }
    }

    @Test
    fun cacheKeySurvivesTheAuthenticatedEntryShape() {
        val token = "A".repeat(43)
        val url = HarnessPageUrl.withVersions("http://127.0.0.1:3080/?token=$token", "0.1.21-preview", "2026.08.1")

        assertTrue(url.contains("token=$token"))
        assertEquals("0.1.21-preview" to "2026.08.1", HarnessPageUrl.versionCacheKey(url))
        assertEquals(HarnessCacheMode.NORMAL, HarnessPageCache.modeFor(url))
    }
}
