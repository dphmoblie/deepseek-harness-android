package io.deepseekharness.mobile.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回填合并的文件系统行为：逐项搬移、**同名不覆盖**、符号链接按冲突处理。
 *
 * 为什么单独测：这部分不再走「整项二选一」，而是真的在用户数据与新镜像之间做目录合并，
 * 一步写错就是覆盖用户文件。临时目录足以覆盖它需要的全部语义。
 */
class PreservedMergeTest {
    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    @Test
    fun `非空目录逐项合并，同名不覆盖，冲突项留在原处`() {
        val source = tempDir("merge-src-")
        val target = tempDir("merge-dst-")

        // 源：新文件 + 与目标同名的文件（内容不同）+ 一个可递归合并的子目录。
        File(source, "notes.md").writeText("user")
        File(source, "same.txt").writeText("user-version")
        File(source, "audit").mkdirs()
        File(source, "audit/report.md").writeText("report")
        File(source, "audit/same.md").writeText("user-report")
        // 目标：同名文件与同名子目录（其中又有一个同名文件）。
        File(target, "same.txt").writeText("image-version")
        File(target, "audit").mkdirs()
        File(target, "audit/same.md").writeText("image-report")
        File(target, "audit/sample.md").writeText("sample")

        val result = PreservedMerge.merge(source, target)

        // 搬进去的：notes.md、audit/report.md。
        assertEquals(2, result.moved)
        assertEquals("user", File(target, "notes.md").readText())
        assertEquals("report", File(target, "audit/report.md").readText())
        // 同名文件绝不覆盖：新镜像里的版本原样保留，用户版本留在暂存目录里等处理。
        assertEquals("image-version", File(target, "same.txt").readText())
        assertEquals("user-version", File(source, "same.txt").readText())
        assertEquals("image-report", File(target, "audit/same.md").readText())
        assertEquals(listOf("audit/same.md", "same.txt"), result.conflicts.sorted())
        // 已搬空的子目录不删除（调用方负责清理空壳），但里面不能再有搬过的文件。
        assertEquals(listOf("same.md"), File(source, "audit").listFiles()?.map { it.name })
    }

    @Test
    fun `符号链接按冲突处理，绝不跟随`() {
        val source = tempDir("merge-link-src-")
        val target = tempDir("merge-link-dst-")
        val secret = tempDir("merge-secret-")
        File(secret, "token.txt").writeText("secret")
        // 源里的符号链接指向源外部：如果实现跟随链接，就会把外部文件搬进新根目录。
        try {
            Files.createSymbolicLink(File(source, "link").toPath(), File(secret, "token.txt").toPath())
        } catch (error: Exception) {
            // Windows 上创建符号链接需要额外权限；这条分支只在无特权宿主上跳过，
            // 真机（Linux）与 CI 上照常执行。
            org.junit.Assume.assumeNoException("当前宿主不允许创建符号链接", error)
            return
        }

        val result = PreservedMerge.merge(source, target)

        assertEquals(0, result.moved)
        assertEquals(listOf("link"), result.conflicts)
        assertTrue("符号链接不得被搬走", Files.isSymbolicLink(File(source, "link").toPath()))
        assertTrue("目标里不得出现被跟随的副本", !File(target, "token.txt").exists())
    }

    @Test
    fun `目标是空目录时照常合并，源目录不存在时什么都不做`() {
        val source = tempDir("merge-empty-src-")
        val target = tempDir("merge-empty-dst-")
        File(source, "a.txt").writeText("a")
        val result = PreservedMerge.merge(source, target)
        assertEquals(1, result.moved)
        assertEquals("a", File(target, "a.txt").readText())

        // 源不存在（例如白名单项本来就没有）：什么都不做，也不能假装搬过东西。
        val missing = File(tempDir("merge-missing-"), "nope")
        val skipped = PreservedMerge.merge(missing, target)
        assertEquals(0, skipped.moved)
        assertEquals(emptyList<String>(), skipped.conflicts)
    }

    @Test
    fun `冲突项按相对路径逐层记账`() {
        val source = tempDir("merge-deep-src-")
        val target = tempDir("merge-deep-dst-")
        File(source, "a/b/c").mkdirs()
        File(source, "a/b/c/deep.txt").writeText("deep")
        File(source, "a/b/c/blocked.txt").writeText("user")
        File(target, "a/b/c").mkdirs()
        File(target, "a/b/c/blocked.txt").writeText("image")

        val result = PreservedMerge.merge(source, target)

        assertEquals(1, result.moved)
        assertEquals("deep", File(target, "a/b/c/deep.txt").readText())
        assertEquals(listOf("a/b/c/blocked.txt"), result.conflicts)
        assertEquals("image", File(target, "a/b/c/blocked.txt").readText())
    }
}
