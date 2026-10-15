package io.deepseekharness.mobile

import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import android.webkit.WebViewDatabase
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.getcapacitor.BridgeActivity
import io.deepseekharness.mobile.runtime.RuntimeSharedInbox
import io.deepseekharness.mobile.runtime.RuntimeStore
import io.deepseekharness.mobile.runtime.SharedFileImportBatch
import io.deepseekharness.mobile.runtime.SharedFileImportBatch.Status as ImportResult
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
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
        // 尽早在后台线程预热 WebView 提供方，与下面的桥接初始化、布局 inflate 并行。
        warmUpWebView()
        registerPlugin(MobileRuntimePlugin::class.java)
        super.onCreate(savedInstanceState)
        AppWindowInsets.apply(this)
        handleExternalFileIntent(intent)
        applySystemFontScale()

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

    override fun onStart() {
        super.onStart()
        AppForeground.onActivityStarted()
    }

    override fun onStop() {
        AppForeground.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 主题可能刚在 Web 侧被改过，系统深色模式也可能在后台切换过；每次回到前台重算一次状态栏，
        // 比在每条改动路径上分别通知可靠。
        AppThemePreference.applySafely(this)
        // 用户已经回到应用，界面本身就会显示运行时状态：任务通知在这里没有存在价值（登记册 §5.5）。
        io.deepseekharness.mobile.runtime.TaskNotification.clear(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // `uiMode` 在 configChanges 列表里，系统切换深色**不会重建本 Activity**，
        // 所以这里必须自己重算：否则「跟随系统」时状态栏会一直停在旧配色。
        AppThemePreference.applySafely(this)
    }

    override fun onDestroy() {
        importExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * 管理界面的字号跟随系统字号（登记册 5.6-I）。
     *
     * 外壳的 CSS 已经把字号全部 rem 化、根字号也不写死，但 **WebView 不会因为系统字号变大
     * 就改变 rem 基准**——只有 `WebSettings.textZoom` 能带上这件事。不接这一步，
     * 用户在系统设置里把字体调到最大，应用内文字仍然纹丝不动。
     *
     * 时机：`fontScale` **不在** `configChanges` 列表里（见 AndroidManifest 的 MainActivity），
     * 所以系统改字号会重建 Activity，在 `onCreate` 里读一次就够；不需要额外监听。
     * `bridge` 在 `super.onCreate` 之后才可用，因此调用点放在那之后。
     */
    private fun applySystemFontScale() {
        val settings = bridge?.webView?.settings ?: return
        settings.textZoom = AppTextScale.percentOf(resources.configuration.fontScale)
    }

    /**
     * 在后台线程预热 WebView 提供方。
     *
     * 冷启动时第一个 WebView 实例化会触发系统 WebView APK 加载与 native 库初始化，
     * 这是主线程上的固定大头。[WebViewDatabase.getInstance] 内部同样会加载 WebView
     * provider，而该 API 官方允许在后台线程调用；在 onCreate 最早期发起后，它与
     * Capacitor 的桥接初始化并行，provider 初始化全局只发生一次，随后管理界面与
     * Harness 会话页创建 WebView 时直接复用。异常静默回退到系统默认的懒加载路径。
     */
    private fun warmUpWebView() {
        webViewWarmupExecutor.execute {
            runCatching { WebViewDatabase.getInstance(applicationContext) }
        }
    }

    /** 从外部 Intent 拿到用户选中的 content URI，复制进私有 inbox 目录。 */
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
                (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf<Uri>())
                    .asSequence()
                    .filterIsInstance<Uri>()
                    .take(MAX_IMPORT_FILES)
                    .toList()
                    .ifEmpty {
                        (0 until minOf(intent.clipData?.itemCount ?: 0, MAX_IMPORT_FILES))
                            .map { intent.clipData!!.getItemAt(it).uri }
                    }
            }
            else -> emptyList()
        }
        val acceptedUris = uris.asSequence()
            .filter { it.scheme == "content" }
            .distinct()
            .take(MAX_IMPORT_FILES)
            .toList()
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
                } catch (_: Throwable) {
                    SharedFileImportBatch.Result(ImportResult.SOURCE_UNREADABLE)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val partial = result.acceptedCount > 0 && result.status != ImportResult.SUCCESS && result.status != ImportResult.STAGED
                    if (partial) {
                        val received = if (result.deliveryComplete) "已导入收件箱" else "已接收，未投递的文件已安全暂存"
                        val failure = when (result.status) {
                            ImportResult.TOO_LARGE -> "后续文件超过 64 MB"
                            ImportResult.PERMISSION_DENIED -> "后续文件没有读取权限"
                            ImportResult.DESTINATION_UNAVAILABLE -> "后续文件无法保存"
                            else -> "后续文件来源不可读"
                        }
                        // 已接收的文件不再用选择器整批重试，避免用户误以为全部失败而重复导入。
                        showImportToast("${result.acceptedCount} 个文件$received；$failure，未导入")
                        return@runOnUiThread
                    }
                    when (result.status) {
                        ImportResult.SUCCESS -> showImportToast("文件已导入应用收件箱")
                        ImportResult.STAGED -> showImportToast("文件已安全暂存，运行时准备完成后可在工作区 inbox 中使用")
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
        } catch (_: Throwable) {
            showImportToast("导入任务无法启动，请重试")
        }
    }

    private fun importFiles(uris: List<Uri>): SharedFileImportBatch.Result {
        val store = RuntimeStore(applicationContext)
        val sharedInbox = RuntimeSharedInbox(store)
        val inbox = try {
            sharedInbox.stagingDirectory()
        } catch (_: Exception) {
            return SharedFileImportBatch.Result(ImportResult.DESTINATION_UNAVAILABLE)
        }

        return SharedFileImportBatch.run(uris.take(MAX_IMPORT_FILES), importOne = { index, uri ->
            val name = queryDisplayName(uri) ?: "shared-file-$index"
            val safeName = name.take(MAX_SOURCE_NAME_LENGTH)
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(MAX_FILE_NAME_LENGTH)
                .ifEmpty { "shared-file-$index" }
            val target = File(inbox, "${UUID.randomUUID()}-$safeName")
            try {
                sharedInbox.withPendingFile { pending ->
                    val result = copySharedFile(uri, pending)
                    if (result != ImportResult.SUCCESS) result
                    else {
                        RuntimeSharedInbox.withLock {
                            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        }
                        ImportResult.SUCCESS
                    }
                }
            } catch (_: Exception) {
                ImportResult.DESTINATION_UNAVAILABLE
            }
        }, deliver = sharedInbox::deliver)
    }

    private fun copySharedFile(uri: Uri, target: File): ImportResult {
        val input = try {
            openSharedInputStream(uri)
        } catch (_: SecurityException) {
            return ImportResult.PERMISSION_DENIED
        } catch (_: IOException) {
            return ImportResult.SOURCE_UNREADABLE
        }

        return try {
            input.use { source ->
                try {
                    Files.newOutputStream(
                        target.toPath(),
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) return ImportResult.SOURCE_UNREADABLE
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
            ImportResult.SUCCESS
        } catch (_: SecurityException) {
            ImportResult.PERMISSION_DENIED
        } catch (_: IOException) {
            ImportResult.SOURCE_UNREADABLE
        }
    }

    /** 部分云文档服务只通过 typed asset 暴露虚拟文件，openInputStream 拿不到。 */
    @Throws(IOException::class, SecurityException::class)
    private fun openSharedInputStream(uri: Uri): InputStream {
        try {
            contentResolver.openInputStream(uri)?.let { return it }
        } catch (_: FileNotFoundException) {
            // 普通 openInputStream 失败后回退到 openTypedAssetFileDescriptor（云文档虚拟文件走这里）。
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
        /**
         * WebView 提供方预热专用线程：进程级单线程、守护线程，不随 Activity 销毁关闭。
         */
        private val webViewWarmupExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dsh-webview-warmup").apply { isDaemon = true }
        }

        private const val ANY_MIME_TYPE = "*/*"
        private const val COPY_BUFFER_BYTES = 16 * 1024
        private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
        private const val MAX_IMPORT_FILES = 16
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val MAX_SOURCE_NAME_LENGTH = 512
        private const val MAX_MIME_TYPES = 16
        private const val MAX_MIME_TYPE_LENGTH = 127
    }

}
