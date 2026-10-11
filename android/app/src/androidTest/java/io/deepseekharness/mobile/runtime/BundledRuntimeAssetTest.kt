package io.deepseekharness.mobile.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledRuntimeAssetTest {
    @Test
    fun bundledArchiveReadsExactlyItsManifestBytesAfterReopen() {
        val store = RuntimeStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val artifact = store.openBundledManifest().use {
            RuntimeManifest.parse(it.readBytes()).rootfs
        }
        // 模拟检查更新时打开即关闭，随后安装仍必须从归档起点读取。
        store.openBundledRootfs().use { assertTrue(it.read() >= 0) }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        store.openBundledRootfs().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                assertTrue("Asset read must make progress", count > 0)
                total += count
                assertTrue("Asset must stop at the ZIP entry boundary", total <= artifact.compressedBytes)
                digest.update(buffer, 0, count)
            }
            assertEquals(-1, input.read())
        }
        assertEquals(artifact.compressedBytes, total)
        assertEquals(artifact.sha256, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
