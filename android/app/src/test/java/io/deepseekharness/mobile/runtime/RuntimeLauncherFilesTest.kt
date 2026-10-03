package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 运行器布局的不变量测试。
 *
 * 这组断言守的是真机上一条已经被复现过一次的故障链：loader 若落在运行时根之外（私有目录，
 * 或根内指向 `nativeLibraryDir` 的符号链接 —— Landlock 按最终 inode 判定），受限进程执行
 * 沙箱内命令时会拿到 `landlock-run: exec failed: Permission denied` 与退出码 125，
 * 而 `--probe` 因为不 exec 依旧通过，表现为「探测正常、沙箱内命令与 PTY 全失败」。
 */
class RuntimeLauncherFilesTest {
    private val noBackup = File("/data/user/0/io.deepseekharness.mobile/no_backup")
    private val currentRoot = File(noBackup, "dsh-runtime/current")

    @Test
    fun keepsLoaderInsideTheRuntimeRoot() {
        val loader = RuntimeLauncherFiles.loaderFile(currentRoot)

        assertTrue(RuntimeLauncherFiles.isInside(loader, currentRoot))
        assertEquals(
            "root/.dsh-mobile/dsh-runner/loader",
            loader.absoluteFile.toPath().normalize()
                .let { currentRoot.absoluteFile.toPath().normalize().relativize(it).toString() }
                .replace(File.separatorChar, '/'),
        )
    }

    @Test
    fun keepsRunnerOutsideTheRuntimeRoot() {
        val runner = RuntimeLauncherFiles.runnerFile(noBackup)

        assertFalse(RuntimeLauncherFiles.isInside(runner, currentRoot))
        assertTrue(RuntimeLauncherFiles.isInside(runner, noBackup))
    }

    @Test
    fun separatesRunnerAndLoaderDirectories() {
        val runnerDirectory = RuntimeLauncherFiles.runnerDirectory(noBackup)
        val loaderDirectory = RuntimeLauncherFiles.loaderDirectory(currentRoot)

        assertFalse(runnerDirectory.absolutePath == loaderDirectory.absolutePath)
        // loader 目录必须跟着运行时根走：换运行时后由 prepareLaunchFiles() 重新落一份。
        assertTrue(RuntimeLauncherFiles.isInside(loaderDirectory, currentRoot))
    }

    /**
     * 兜底 loader 必须留在运行时根之外：它只在根内实体拷贝确实执行不了时使用，
     * 与 proot 本体同目录，好让「未受限启动」至少可用。
     */
    @Test
    fun keepsFallbackLoaderOutsideTheRuntimeRoot() {
        val fallback = RuntimeLauncherFiles.privateLoaderFile(noBackup)

        assertFalse(RuntimeLauncherFiles.isInside(fallback, currentRoot))
        assertEquals(
            "dsh-runner/loader",
            fallback.absoluteFile.toPath().normalize()
                .let { noBackup.absoluteFile.toPath().normalize().relativize(it).toString() }
                .replace(File.separatorChar, '/'),
        )
        assertEquals(RuntimeLauncherFiles.runnerDirectory(noBackup), fallback.parentFile)
    }

    @Test
    fun treatsRootItselfAndSiblingsAsOutside() {
        assertFalse(RuntimeLauncherFiles.isInside(currentRoot, currentRoot))
        assertFalse(RuntimeLauncherFiles.isInside(File(noBackup, "dsh-runner"), currentRoot))
        assertTrue(RuntimeLauncherFiles.isInside(File(currentRoot, "root/.dsh"), currentRoot))
    }

    /** 归一化路径里出现 `..` 时不能把根外文件误判成根内。 */
    @Test
    fun doesNotAcceptTraversalOutsideTheRoot() {
        val escaping = File(File(currentRoot, "root/../../dsh-runner"), "loader")

        assertFalse(RuntimeLauncherFiles.isInside(escaping, currentRoot))
    }
}
