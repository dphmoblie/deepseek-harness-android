package io.deepseekharness.mobile.runtime

import java.io.File

/**
 * 运行器（PRoot）与 loader 的文件布局。
 *
 * 这里刻意做成纯函数：布局本身就是一条安全不变量，单测直接盯住它，不需要构造 Context。
 *
 * ## 为什么 loader 必须落在运行时根**之内**
 *
 * dsh 的 Landlock 启动器（`landlock-run`）只把运行时根授权给受限进程。PRoot 在 tracee 里
 * 执行程序时，会把 tracee 的 `execve` 改写成对 `PROOT_LOADER` 的 execve；loader 一旦落在
 * 授权树之外（例如放在 `noBackupFilesDir` 再约定指向 APK 的 `nativeLibraryDir`），这个
 * execve 直接返回 `EACCES`：`landlock-run: exec failed: Permission denied`、退出码 125。
 *
 * 于是故障表现为「`--probe` 通过（探测只建规则集、不 exec），沙箱内命令与沙箱内 PTY 全部失败」。
 * **Landlock 按最终 inode 判定**，所以根内的符号链接同样无效（实测仍然 125），必须真拷一份。
 *
 * 相对地，PRoot 本体由应用自身执行、不受访客沙箱约束，留在私有目录里，访客看不到它 ——
 * 这条信任边界不要一起搬进根内。
 */
object RuntimeLauncherFiles {
    /** 私有启动目录的名字：proot 本体与它的临时链接都放这里，位于运行时根之外。 */
    const val RUNNER_DIRECTORY_NAME = "dsh-runner"

    /** loader 在访客根内的落点（相对运行时根）：与 launcher 配置同目录，随运行时一起被替换。 */
    const val GUEST_LOADER_DIRECTORY_PATH = "root/.dsh-mobile/dsh-runner"

    /** 私有启动目录里的文件名：给 `PROOT_LOADER` 之外的本体用。 */
    const val LAUNCH_RUNNER_NAME = "proot"

    /** 根内 loader 的文件名。 */
    const val LAUNCH_LOADER_NAME = "loader"

    fun runnerDirectory(noBackupFilesDir: File): File = File(noBackupFilesDir, RUNNER_DIRECTORY_NAME)

    fun runnerFile(noBackupFilesDir: File): File = File(runnerDirectory(noBackupFilesDir), LAUNCH_RUNNER_NAME)

    fun loaderDirectory(currentRoot: File): File = File(currentRoot, GUEST_LOADER_DIRECTORY_PATH)

    fun loaderFile(currentRoot: File): File = File(loaderDirectory(currentRoot), LAUNCH_LOADER_NAME)

    /**
     * 私有目录里的 loader 落点（0.2.3 的老位置），**只作为兜底**。
     *
     * 正常情况下必须用 [loaderFile]：受限进程的 execve 只有在 Landlock 授权树内才被放行。
     * 但当根内实体拷贝在这台设备上怎么都执行不了时（真机出现过「内容对、权限对、系统仍拒绝」的
     * 形态），退回这里至少能让**未受限启动**可用 —— 沙箱内命令会退化，好过完全起不来，
     * 并且每次启动都会重试根内拷贝，设备一旦允许就自动回到正常形态。
     */
    fun privateLoaderFile(noBackupFilesDir: File): File =
        File(runnerDirectory(noBackupFilesDir), LAUNCH_LOADER_NAME)

    /**
     * 只读判定：[candidate] 是否严格位于 [root] 之内（`root` 自身不算）。
     *
     * 供单测守住「loader 在运行时根内、proot 在运行时根外」这条不变量：一旦有人把 loader
     * 挪回私有目录，真机上就会复现「探测通过、沙箱内命令与 PTY 全失败」。
     */
    fun isInside(candidate: File, root: File): Boolean {
        val rootPath = root.absoluteFile.toPath().normalize()
        val candidatePath = candidate.absoluteFile.toPath().normalize()
        return candidatePath != rootPath && candidatePath.startsWith(rootPath)
    }
}
