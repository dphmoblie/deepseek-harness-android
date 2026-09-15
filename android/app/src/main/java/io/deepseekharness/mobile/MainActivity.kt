package io.deepseekharness.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.getcapacitor.BridgeActivity
import io.deepseekharness.mobile.runtime.RuntimeStore

/**
 * Capacitor 外壳 Activity，承载管理界面（`src/` 的 React 页面）。
 *
 * 返回键语义与外壳的浏览器历史严格配合：
 *  - WebView 还有可回退的历史 → 先回退历史。外壳每次切换视图都会 `pushState`，
 *    回退后由页面的 `popstate` 把视图恢复成上一级（设置二级页 → 设置一级 → 主视图）；
 *  - 历史已经见底（外壳主视图）→ 把任务退到后台，而不是 `finish()` 掉 Activity：
 *    用户按返回只是要离开当前界面，不希望应用被结束掉，更不希望正在跑的本机运行时被拆掉。
 *
 * 关于「双重处理」：Capacitor 7.4.3 的 `BridgeActivity` 没有实现 `onBackPressed`，
 * 也不注册任何 `OnBackPressedCallback`（已核对上游源码），因此这里注册的回调是返回键的
 * 唯一处理路径。回调被消费后不会再落到 AppCompat 默认的 `finish()`；本类也刻意不重写
 * `onBackPressed()`，避免「既 goBack 又 finish」的两条路径同时生效。
 */
class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(MobileRuntimePlugin::class.java)
        super.onCreate(savedInstanceState)
        handleExternalFileIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = bridge?.webView
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                // 历史见底：整个任务退到后台。不调用 finish()，也不回调 super（那会走默认的 finish），
                // 这样 Activity、WebView 历史与本机运行时原样保留，用户从最近任务回来还是原来的界面。
                moveTaskToBack(true)
            }
        })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleExternalFileIntent(intent)
    }

    /** Accept a user-selected content URI from another app and copy it into private inbox storage. */
    private fun handleExternalFileIntent(intent: Intent) {
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri,
            )
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: arrayListOf()).filterIsInstance<Uri>()
                    .ifEmpty { (0 until (intent.clipData?.itemCount ?: 0)).map { intent.clipData!!.getItemAt(it).uri } }
            }
            else -> emptyList()
        }
        val acceptedUris = uris.filter { it.scheme == "content" }.take(MAX_IMPORT_FILES)
        if (acceptedUris.isEmpty()) {
            Toast.makeText(this, "仅支持通过系统文件提供方导入文件", Toast.LENGTH_SHORT).show()
            return
        }
        val runtimeInbox = java.io.File(RuntimeStore(this).currentRoot, "root/1/inbox")
        val inbox = if (RuntimeStore(this).currentRoot.isDirectory) runtimeInbox else java.io.File(filesDir, "inbox")
        if (!inbox.exists() && !inbox.mkdirs()) return
        try {
            acceptedUris.forEachIndexed { index, uri ->
                val name = try { queryDisplayName(uri) } catch (_: Throwable) { null } ?: "shared-file-$index"
                val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "shared-file-$index" }
                val target = java.io.File(inbox, "${System.currentTimeMillis()}-$index-$safeName")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_IMPORT_BYTES) throw IllegalArgumentException("file too large")
                                output.write(buffer, 0, read)
                            }
                        }
                    } ?: throw IllegalArgumentException("unreadable")
                } catch (error: Throwable) {
                    target.delete()
                    throw error
                    }
            }
            Toast.makeText(this, "文件已导入应用收件箱", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(this, "文件导入失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
        private const val MAX_IMPORT_FILES = 16
    }
}
