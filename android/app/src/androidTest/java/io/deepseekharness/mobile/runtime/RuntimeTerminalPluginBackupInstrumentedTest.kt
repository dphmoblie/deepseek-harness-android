package io.deepseekharness.mobile.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android filesystem path used when preserving terminal plugins. */
@RunWith(AndroidJUnit4::class)
class RuntimeTerminalPluginBackupInstrumentedTest {
    @Test
    fun backupWritesRecoveryJournalWithThirdPartyPlugin() {
        val root = Files.createTempDirectory(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),
            "terminal-plugin-backup-test-",
        ).toFile()
        try {
            val runtimeParent = File(root, "runtime-parent").apply { mkdirs() }
            val sourceRoot = File(root, "source-root").apply { mkdirs() }
            val plugin = File(sourceRoot, "root/.dsh/profiles/web/node_modules/third-party")
                .apply { mkdirs() }
            File(plugin, "package.json").writeText("{}")
            File(sourceRoot, "root/.dsh/profiles/web/package.json").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    {"dependencies":{"third-party":"1.2.3"},"dsh":{"profile":{"bundles":["existing"]}}}
                    """.trimIndent(),
                )
            }

            val handle = RuntimeTerminalPluginBackup.backup(runtimeParent, sourceRoot)

            assertNotNull("A third-party plugin should produce a backup", handle)
            val backup = requireNotNull(handle)
            val snapshot = File(backup.directory, "profile.json")
            assertTrue("The recovery journal should be written", snapshot.isFile)
            val entry = JSONObject(snapshot.readText()).getJSONArray("entries").getJSONObject(0)
            assertEquals("third-party", entry.getString("name"))
            assertTrue(File(backup.directory, entry.getString("relative")).isDirectory)
            assertFalse("Backup must move the plugin out of the old runtime", plugin.exists())

            val targetRoot = File(root, "target-root").apply { mkdirs() }
            val targetProfile = File(targetRoot, "root/.dsh/profiles/web/package.json").apply {
                parentFile?.mkdirs()
                writeText("{\"dsh\":{\"profile\":{\"bundles\":[\"new-runtime\"]}}}")
            }
            assertEquals(1, RuntimeTerminalPluginBackup.restore(backup, targetRoot, strict = true))
            assertTrue(File(targetRoot, entry.getString("relative")).isDirectory)
            assertFalse("A completed restore must consume the backup journal", backup.directory.exists())

            val mergedProfile = JSONObject(targetProfile.readText())
            val bundles = mergedProfile.getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles")
            assertEquals("new-runtime", bundles.getString(0))
            assertEquals("third-party", bundles.getString(1))
            assertEquals("1.2.3", mergedProfile.getJSONObject("dependencies").getString("third-party"))
        } finally {
            root.deleteRecursively()
        }
    }
}
