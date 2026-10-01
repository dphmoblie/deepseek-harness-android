package io.deepseekharness.mobile.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 已下载更新包的落地位置。
 *
 * 放在应用私有 `cacheDir` 下：安装时要用 FileProvider 把它交给系统安装器，
 * 而仓库里「临时文件 + FileProvider 暴露」的既有约定就是 cacheDir（诊断导出、工作区分享），
 * 更新包与它们同类。这套与 [RuntimeStorageDirs] 无关——那边讲的是访客挂载点与 SAF 白名单。
 *
 * 文件名带版本号；**先写 `.part`，核对摘要通过后再改名成 `.apk`**，所以目录里存在的 `.apk`
 * 一定是完整的包，不会把半截下载当成可安装的更新递给系统安装器。旧包只保留最近一份。
 */
internal object AppUpdateStorage {
    const val DIRECTORY_NAME = "app-updates"
    private const val FILE_PREFIX = "app-release-"
    private const val FILE_SUFFIX = ".apk"
    private const val PART_SUFFIX = ".apk.part"

    /** 准备好落地目录；不是目录或竟是软链接时报错，不悄悄换一个位置继续。 */
    fun prepare(cacheDir: File): File {
        val directory = File(cacheDir, DIRECTORY_NAME)
        if ((!directory.isDirectory && !directory.mkdirs()) || Files.isSymbolicLink(directory.toPath())) {
            throw RuntimeFailure("APP_UPDATE_STORAGE_FAILED", "无法准备更新下载目录")
        }
        return directory
    }

    /** 下载目标（`.part`）：断点续传就落在它上面。 */
    fun partialFile(cacheDir: File, version: String): File =
        File(prepare(cacheDir), "$FILE_PREFIX${requireVersion(version)}$FILE_SUFFIX$PART_SUFFIX")

    /** 校验通过后把 `.part` 改名成正式安装包，并清掉旧的更新包。 */
    fun commit(cacheDir: File, version: String): File {
        val partial = partialFile(cacheDir, version)
        if (!partial.isFile) throw RuntimeFailure("APP_UPDATE_FILE_MISSING", "更新安装包不存在，请重新下载")
        val target = File(prepare(cacheDir), "$FILE_PREFIX$version$FILE_SUFFIX")
        try {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IOException) {
            throw RuntimeFailure("APP_UPDATE_STORAGE_FAILED", "无法保存更新安装包", error)
        }
        retainOnly(cacheDir, target)
        return target
    }

    /** 已下载好的安装包；多个时取最新的一个，没有就是 null。 */
    fun downloaded(cacheDir: File): File? {
        val directory = File(cacheDir, DIRECTORY_NAME)
        if (!directory.isDirectory) return null
        return directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
    }

    /** 只保留刚下好的那一份；同目录里别的更新包（含半截的 `.part`）都清掉。删除失败不报错——缓存目录随时可清。 */
    private fun retainOnly(cacheDir: File, keep: File) {
        val directory = File(cacheDir, DIRECTORY_NAME)
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { file ->
            if (file.name == keep.name) return@forEach
            if (file.isFile && file.name.startsWith(FILE_PREFIX)) file.delete()
        }
    }

    private fun requireVersion(version: String): String {
        // 版本号已过标识形态校验：不含路径分隔符，拼不出目录外的路径。
        if (!GitHubReleases.isIdentifier(version)) throw RuntimeFailure("APP_UPDATE_VERSION_INVALID", "更新版本号无效")
        return version
    }
}

/**
 * 安装前的受控检查。
 *
 * 抽成纯函数是为了让「没授权」和「文件没了」这两条路能在单测里钉住，
 * 而不必真的去调起系统安装器（那需要设备，而且只能由用户点确认）。
 */
internal object AppUpdateInstallPolicy {
    /** 返回可以交给系统安装器的文件；任何前置条件不满足都抛 [RuntimeFailure]。 */
    fun requireInstallable(installAllowed: Boolean, apk: File?): File {
        if (!installAllowed) {
            throw RuntimeFailure("APP_UPDATE_INSTALL_PERMISSION", "尚未允许安装未知应用，请先在系统设置里授权")
        }
        if (apk == null || !apk.isFile) {
            throw RuntimeFailure("APP_UPDATE_FILE_MISSING", "更新安装包不存在，请先下载")
        }
        return apk
    }
}
