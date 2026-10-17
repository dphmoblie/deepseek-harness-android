package io.deepseekharness.mobile.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

            val handle = RuntimeTerminalPluginBackup.backup(runtimeParent, sourceRoot)

            assertNotNull("A third-party plugin should produce a backup", handle)
            val backup = requireNotNull(handle)
            val snapshot = File(backup.directory, "profile.json")
            assertTrue("The recovery journal should be written", snapshot.isFile)
            val entry = JSONObject(snapshot.readText()).getJSONArray("entries").getJSONObject(0)
            assertEquals("third-party", entry.getString("name"))
            assertTrue(File(backup.directory, entry.getString("relative")).isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
