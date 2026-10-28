package io.deepseekharness.mobile.runtime

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把工作区文件交给系统分享面板或外部应用打开。
 *
 * 复制、路径校验仍由 [RuntimeWorkspaceFiles] 完成：只允许工作区 `root/1` 下的普通文件，
 * 逐层 NoFollow，避免符号链接把工作区外的内容带进分享缓存。这里只负责把复制出的
 * 临时文件转成 `content://` URI，并授予一次性读权限。
 *
 * 抽出成独立对象是因为内置预览面板（[io.deepseekharness.mobile.HarnessActivity]）与
 * 外壳工作区列表（插件）都要走同一条分享链路，不能各写一份权限与 MIME 处理。
 */
internal object WorkspaceFileSharing {
    /** 地址解析成功，但工作区里没有对应文件（候选路径都不存在）。 */
    const val NOT_IN_WORKSPACE = "WORKSPACE_SHARE_FILE_MISSING"

    /** [RuntimeWorkspaceFiles] 报告「文件不存在 / 不是普通文件」时的受控码。 */
    private const val FILE_UNAVAILABLE = "WORKSPACE_FILE_UNAVAILABLE"

    /**
     * 构造分享/打开用的选择器 Intent。
     *
     * 复制动作会读取整个文件，调用方需在后台线程执行；返回的 Intent 可在主线程启动。
     * 失败时抛出 [RuntimeFailure]，由调用方转换成用户可见的错误，不在这里吞掉。
     */
    fun chooser(context: Context, store: RuntimeStore, cacheDir: File, relative: String, open: Boolean): Intent =
        chooser(context, store, cacheDir, listOf(relative), open)

    /**
     * 依次尝试多个工作区相对路径（同一个地址在不同会话工作目录口径下的解释）。
     *
     * 只有「工作区里没有这个文件」才换下一个候选；路径被拒绝、安全目录流不可用、
     * 缓存目录写不进去等其他失败立刻抛出，避免把真实故障误报成「文件不在工作区」。
     * 全部候选都不可用时抛 [NOT_IN_WORKSPACE]。
     */
    fun chooser(context: Context, store: RuntimeStore, cacheDir: File, candidates: List<String>, open: Boolean): Intent {
        val manager = RuntimeWorkspaceFiles(store, cacheDir)
        val target = copyFirstAvailable(manager, candidates)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", target)
        val intent = Intent(if (open) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
            val mime = manager.mimeType(candidates.first())
            if (open) {
                setDataAndType(uri, mime)
            } else {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            clipData = ClipData.newRawUri(target.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, if (open) "打开 DSH 文件" else "分享 DSH 文件")
    }

    /**
     * 复制第一个可用的候选文件，返回复制到分享缓存里的临时文件。
     *
     * 候选全部来自 [io.deepseekharness.mobile.WorkspaceShareAddress]，都只是 `root/1` 下的
     * 相对路径；这里不拼绝对路径，也不越过工作区边界。
     */
    internal fun copyFirstAvailable(manager: RuntimeWorkspaceFiles, candidates: List<String>): File =
        firstAvailable(candidates) { manager.copyForSharing(it) }

    /**
     * 候选取舍逻辑本身，与文件系统解耦。
     *
     * 拆出来是为了能在不支持 `SecureDirectoryStream` 的开发机（Windows）上单测「换候选」这件事：
     * 真机/CI 上的复制行为仍由 [copyForSharing] 覆盖。
     */
    internal fun firstAvailable(candidates: List<String>, copy: (String) -> File): File {
        var unavailable: RuntimeFailure? = null
        for (candidate in candidates) {
            try {
                return copy(candidate)
            } catch (failure: RuntimeFailure) {
                if (failure.code != FILE_UNAVAILABLE) throw failure
                unavailable = failure
            }
        }
        throw RuntimeFailure(NOT_IN_WORKSPACE, "文件不在 DSH 工作区内，无法分享", unavailable)
    }
}
