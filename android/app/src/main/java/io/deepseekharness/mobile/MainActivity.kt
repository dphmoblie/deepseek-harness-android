package io.deepseekharness.mobile

import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.getcapacitor.BridgeActivity
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors

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
    private val importExecutor = Executors.newSingleThreadExecutor()
    private var fallbackMimeTypes = arrayOf(ANY_MIME_TYPE)
    private val fallbackFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) enqueueFileImport(uris, allowPermissionFallback = false)
    }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExternalFileIntent(intent)
    }

    override fun onDestroy() {
        importExecutor.shutdownNow()
        super.onDestroy()
    }

    /** Accept a user-selected content URI from another app and copy it into private inbox storage. */
    private fun handleExternalFileIntent(intent: Intent) {
        // 普通桌面启动使用 ACTION_MAIN（或没有 action），不属于外部文件导入。
        // 只有系统明确发起的查看/分享 Intent 才进入 URI 校验，否则每次打开应用都会
        // 因为没有 URI 错误地弹出“仅支持通过系统文件提供方导入文件”。
        val action = intent.action ?: return
        if (action != Intent.ACTION_VIEW &&
            action != Intent.ACTION_SEND &&
            action != Intent.ACTION_SEND_MULTIPLE
        ) return

        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri,
            )
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf<Uri>()).filterIsInstance<Uri>()
                    .ifEmpty { (0 until (intent.clipData?.itemCount ?: 0)).map { intent.clipData!!.getItemAt(it).uri } }
            }
            else -> emptyList()
        }
        val acceptedUris = uris.filter { it.scheme == "content" }.distinct().take(MAX_IMPORT_FILES)
        if (acceptedUris.isEmpty()) {
            Toast.makeText(this, "仅支持通过系统文件提供方导入文件", Toast.LENGTH_SHORT).show()
            return
        }
        fallbackMimeTypes = acceptedMimeTypes(intent)
        enqueueFileImport(acceptedUris, allowPermissionFallback = true)
    }

    private fun enqueueFileImport(uris: List<Uri>, allowPermissionFallback: Boolean) {
        try {
            importExecutor.execute {
                val result = try {
                    importFiles(uris)
                } catch (_: Exception) {
                    ImportResult.SOURCE_UNREADABLE
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (result) {
                        ImportResult.SUCCESS -> showImportToast("文件已导入应用收件箱")
                        ImportResult.TOO_LARGE -> showImportToast("文件超过 64 MB，无法导入")
                        ImportResult.PERMISSION_DENIED -> {
                            if (allowPermissionFallback) launchPermissionFallback()
                            else showImportToast("无法读取所选文件，请更换文件提供方")
                        }
                        ImportResult.SOURCE_UNREADABLE -> showImportToast("文件来源不可读，请重新选择")
                        ImportResult.DESTINATION_UNAVAILABLE -> showImportToast("应用收件箱不可写，导入失败")
                    }
                }
            }
        } catch (_: RuntimeException) {
            showImportToast("导入任务无法启动，请重试")
        }
    }

    private fun importFiles(uris: List<Uri>): ImportResult {
        val store = RuntimeStore(applicationContext)
        val runtimeInbox = File(store.currentRoot, "root/1/inbox")
        val inbox = if (store.currentRoot.isDirectory) runtimeInbox else File(filesDir, "inbox")
        if ((!inbox.exists() && !inbox.mkdirs()) || !inbox.isDirectory) {
            return ImportResult.DESTINATION_UNAVAILABLE
        }

        uris.forEachIndexed { index, uri ->
            if (Thread.currentThread().isInterrupted) return ImportResult.SOURCE_UNREADABLE
            val name = queryDisplayName(uri) ?: "shared-file-$index"
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(MAX_FILE_NAME_LENGTH)
                .ifEmpty { "shared-file-$index" }
            val target = File(inbox, "${System.currentTimeMillis()}-$index-$safeName")
            val result = copySharedFile(uri, target)
            if (result != ImportResult.SUCCESS) return result
        }
        return ImportResult.SUCCESS
    }

    private fun copySharedFile(uri: Uri, target: File): ImportResult {
        val input = try {
            openSharedInputStream(uri)
        } catch (_: SecurityException) {
            return ImportResult.PERMISSION_DENIED
        } catch (_: IOException) {
            return ImportResult.SOURCE_UNREADABLE
        }

        var completed = false
        return try {
            input.use { source ->
                try {
                    target.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val read = try {
                                source.read(buffer)
                            } catch (_: SecurityException) {
                                return ImportResult.PERMISSION_DENIED
                            } catch (_: IOException) {
                                return ImportResult.SOURCE_UNREADABLE
                            }
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMPORT_BYTES) return ImportResult.TOO_LARGE
                            try {
                                output.write(buffer, 0, read)
                            } catch (_: IOException) {
                                return ImportResult.DESTINATION_UNAVAILABLE
                            }
                        }
                    }
                } catch (_: FileNotFoundException) {
                    return ImportResult.DESTINATION_UNAVAILABLE
                } catch (_: SecurityException) {
                    return ImportResult.DESTINATION_UNAVAILABLE
                } catch (_: IOException) {
                    return ImportResult.DESTINATION_UNAVAILABLE
                }
            }
            completed = true
            ImportResult.SUCCESS
        } catch (_: SecurityException) {
            ImportResult.PERMISSION_DENIED
        } catch (_: IOException) {
            ImportResult.SOURCE_UNREADABLE
        } finally {
            if (!completed) target.delete()
        }
    }

    /** Some cloud providers expose virtual documents only through typed asset access. */
    @Throws(IOException::class, SecurityException::class)
    private fun openSharedInputStream(uri: Uri): InputStream {
        try {
            contentResolver.openInputStream(uri)?.let { return it }
        } catch (_: FileNotFoundException) {
            // Fall through to the typed API used by virtual documents.
        }
        val mimeType = try {
            contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
            ?.takeIf(::isValidMimeType)
            ?: ANY_MIME_TYPE
        val descriptor: AssetFileDescriptor = contentResolver.openTypedAssetFileDescriptor(
            uri,
            mimeType,
            null,
        ) ?: throw FileNotFoundException("content provider returned no file descriptor")
        return try {
            descriptor.createInputStream()
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun acceptedMimeTypes(intent: Intent): Array<String> {
        val types = buildList {
            intent.type?.takeIf(::isValidMimeType)?.let(::add)
            val description = intent.clipData?.description
            if (description != null) {
                for (index in 0 until description.mimeTypeCount) {
                    description.getMimeType(index)?.takeIf(::isValidMimeType)?.let(::add)
                }
            }
        }.distinct().take(MAX_MIME_TYPES)
        return types.takeIf { it.isNotEmpty() }?.toTypedArray() ?: arrayOf(ANY_MIME_TYPE)
    }

    private fun isValidMimeType(value: String): Boolean =
        value.length in 3..MAX_MIME_TYPE_LENGTH &&
            value.contains('/') &&
            value.none { it.isISOControl() }

    private fun launchPermissionFallback() {
        showImportToast("文件来源未授予读取权限，请在系统选择器中重新选择")
        try {
            fallbackFilePicker.launch(fallbackMimeTypes)
        } catch (_: RuntimeException) {
            showImportToast("无法打开系统文件选择器")
        }
    }

    private fun showImportToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ANY_MIME_TYPE = "*/*"
        private const val COPY_BUFFER_BYTES = 16 * 1024
        private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
        private const val MAX_IMPORT_FILES = 16
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val MAX_MIME_TYPES = 16
        private const val MAX_MIME_TYPE_LENGTH = 127
    }

    private enum class ImportResult {
        SUCCESS,
        TOO_LARGE,
        PERMISSION_DENIED,
        SOURCE_UNREADABLE,
        DESTINATION_UNAVAILABLE,
    }
}
