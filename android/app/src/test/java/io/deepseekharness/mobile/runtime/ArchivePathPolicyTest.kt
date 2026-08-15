package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Paths

class ArchivePathPolicyTest {
    private val root = Paths.get(System.getProperty("java.io.tmpdir"), "runtime-staging")
        .toAbsolutePath()
        .normalize()

    @Test
    fun resolvesRegularEntryInsideRoot() {
        assertEquals(
            root.resolve("usr/bin/node"),
            ArchivePathPolicy.resolveEntry(root, "usr/bin/node"),
        )
    }

    @Test
    fun acceptsConventionalLeadingDotEntry() {
        assertEquals(
            root.resolve("usr/bin/node"),
            ArchivePathPolicy.resolveEntry(root, "./usr/bin/node"),
        )
    }

    @Test
    fun rejectsTraversalAndAbsoluteEntries() {
        listOf("../outside", "usr/../../outside", "/system/bin/sh", "usr\\bin\\node", "usr//bin").forEach { value ->
            assertThrows(RuntimeFailure::class.java) { ArchivePathPolicy.resolveEntry(root, value) }
        }
    }

    @Test
    fun permitsRelativeSymlinkThatNormalizesInsideRoot() {
        val link = root.resolve("usr/bin/tool")
        ArchivePathPolicy.validateSymlinkTarget(root, link, "../lib/tool")
    }

    @Test
    fun rewritesAbsoluteSymlinkInsideRoot() {
        val link = root.resolve("bin")
        assertEquals(
            "usr/bin",
            ArchivePathPolicy.normalizeSymlinkTarget(root, link, "/usr/bin"),
        )
    }

    @Test
    fun resolvesHardlinkFromArchiveRoot() {
        assertEquals(
            root.resolve("usr/bin/tool"),
            ArchivePathPolicy.resolveHardlinkTarget(root, "/usr/bin/tool"),
        )
    }

    @Test
    fun rejectsSymlinkThatNormalizesOutsideRoot() {
        val link = root.resolve("bin/tool")
        assertThrows(RuntimeFailure::class.java) {
            ArchivePathPolicy.validateSymlinkTarget(root, link, "../../../system/bin/sh")
        }
    }

    @Test
    fun rejectsAbsoluteSymlinkTraversalAndHardlinkTraversal() {
        assertThrows(RuntimeFailure::class.java) {
            ArchivePathPolicy.normalizeSymlinkTarget(root, root.resolve("usr/bin/tool"), "/../../system/bin/sh")
        }
        assertThrows(RuntimeFailure::class.java) {
            ArchivePathPolicy.resolveHardlinkTarget(root, "../../system/bin/sh")
        }
    }
}
