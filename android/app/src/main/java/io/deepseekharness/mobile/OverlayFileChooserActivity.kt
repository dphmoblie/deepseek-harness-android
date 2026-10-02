package io.deepseekharness.mobile

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

/** Receives the system document picker's result for the Service-owned overlay WebView. */
class OverlayFileChooserActivity : AppCompatActivity() {
    private var request: String? = null
    private var completed = false
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selected = mutableListOf<Uri>()
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(selected::add)
            result.data?.clipData?.let { clip ->
                for (index in 0 until minOf(clip.itemCount, MAX_FILES)) {
                    clip.getItemAt(index).uri?.let(selected::add)
                }
            }
        }
        complete(selected.distinct().take(if (intent.getBooleanExtra(EXTRA_MULTIPLE, false)) MAX_FILES else 1))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = intent.getStringExtra(EXTRA_REQUEST)?.takeIf { raw ->
            raw.length == 36 && runCatching { UUID.fromString(raw).toString() == raw }.getOrDefault(false)
        }
        if (request == null) {
            finish()
            return
        }
        if (savedInstanceState != null) return

        val mimeTypes = OverlayFileChooserPolicy.mimeTypes(intent.getStringArrayExtra(EXTRA_MIME_TYPES))
        val document = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypes.singleOrNull() ?: ANY_MIME_TYPE
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, intent.getBooleanExtra(EXTRA_MULTIPLE, false))
        }
        if (runCatching { picker.launch(document) }.isFailure) complete(emptyList())
    }

    override fun onDestroy() {
        if (!completed && !isChangingConfigurations) {
            request?.let { OverlayBallService.deliverFileChooserResult(it, emptyList()) }
            completed = true
        }
        super.onDestroy()
    }

    private fun complete(uris: List<Uri>) {
        if (completed) return
        completed = true
        OverlayBallService.deliverFileChooserResult(
            requireNotNull(request),
            uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.take(MAX_FILES),
        )
        finish()
    }

    companion object {
        private const val EXTRA_REQUEST = "io.deepseekharness.mobile.overlay.REQUEST"
        private const val EXTRA_MIME_TYPES = "io.deepseekharness.mobile.overlay.MIME_TYPES"
        private const val EXTRA_MULTIPLE = "io.deepseekharness.mobile.overlay.MULTIPLE"
        private const val ANY_MIME_TYPE = "*/*"
        private const val MAX_FILES = 16

        fun open(context: Context, request: String, params: WebChromeClient.FileChooserParams?): Boolean {
            val intent = Intent(context, OverlayFileChooserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_REQUEST, request)
                putExtra(EXTRA_MIME_TYPES, OverlayFileChooserPolicy.mimeTypes(params?.acceptTypes))
                putExtra(EXTRA_MULTIPLE, params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            }
            return runCatching {
                PendingIntent.getActivity(
                    context, 1, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ).send()
            }.isSuccess
        }
    }
}

/** Accept only short, standard MIME values from the page before passing them to Android. */
internal object OverlayFileChooserPolicy {
    private val MIME_TYPE = Regex("[A-Za-z0-9!#$&^_.+-]{1,64}/(?:[A-Za-z0-9!#$&^_.+-]{1,64}|\\*)")

    fun mimeTypes(raw: Array<String>?): Array<String> {
        val accepted = raw.orEmpty().asSequence()
            .filter { it.length <= 128 && (it == "*/*" || MIME_TYPE.matches(it)) }
            .distinct()
            .take(8)
            .toList()
        return (accepted.ifEmpty { listOf("*/*") }).toTypedArray()
    }
}
