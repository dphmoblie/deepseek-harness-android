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
    /**
     * 构造分享/打开用的选择器 Intent。
     *
     * 复制动作会读取整个文件，调用方需在后台线程执行；返回的 Intent 可在主线程启动。
     * 失败时抛出 [RuntimeFailure]，由调用方转换成用户可见的错误，不在这里吞掉。
     */
    fun chooser(context: Context, store: RuntimeStore, cacheDir: File, relative: String, open: Boolean): Intent {
        val manager = RuntimeWorkspaceFiles(store, cacheDir)
        val target = manager.copyForSharing(relative)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", target)
        val intent = Intent(if (open) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
            val mime = manager.mimeType(relative)
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
}
