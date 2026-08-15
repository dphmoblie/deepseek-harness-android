package io.deepseekharness.mobile.runtime

data class RuntimeStateSnapshot(
    val phase: RuntimePhase,
    val architecture: String,
    val installedVersion: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val runnerAvailable: Boolean,
    val harnessUrl: String?,
    val errorCode: String?,
)

class RuntimeStatus(private val store: RuntimeStore) {
    @Volatile
    private var phase: RuntimePhase = if (store.installedManifest() == null) {
        RuntimePhase.NOT_INSTALLED
    } else {
        RuntimePhase.READY
    }

    @Volatile private var downloadedBytes = 0L
    @Volatile private var totalBytes = 0L
    @Volatile private var harnessUrl: String? = null
    @Volatile private var errorCode: String? = null
    private var lastNotifiedPhase: RuntimePhase? = null
    private var lastNotifiedBytes = -1L
    private var lastNotificationNanos = 0L

    var progressListener: ((RuntimeStateSnapshot) -> Unit)? = null

    @Synchronized
    fun update(
        nextPhase: RuntimePhase,
        downloaded: Long = downloadedBytes,
        total: Long = totalBytes,
        nextHarnessUrl: String? = harnessUrl,
        nextErrorCode: String? = null,
    ): RuntimeStateSnapshot {
        phase = nextPhase
        downloadedBytes = downloaded.coerceAtLeast(0)
        totalBytes = total.coerceAtLeast(0)
        harnessUrl = nextHarnessUrl
        errorCode = nextErrorCode
        return snapshot().also { current ->
            val now = System.nanoTime()
            val shouldNotify = nextPhase != lastNotifiedPhase || downloadedBytes == totalBytes ||
                downloadedBytes - lastNotifiedBytes >= NOTIFY_BYTE_STEP ||
                now - lastNotificationNanos >= NOTIFY_TIME_STEP_NANOS
            if (shouldNotify) {
                lastNotifiedPhase = nextPhase
                lastNotifiedBytes = downloadedBytes
                lastNotificationNanos = now
                progressListener?.invoke(current)
            }
        }
    }

    @Synchronized
    fun refreshIdle(): RuntimeStateSnapshot {
        val installed = store.installedManifest()
        phase = if (installed == null) RuntimePhase.NOT_INSTALLED else RuntimePhase.READY
        downloadedBytes = 0
        totalBytes = installed?.rootfs?.compressedBytes ?: 0
        harnessUrl = null
        errorCode = null
        return snapshot()
    }

    @Synchronized
    fun snapshot(): RuntimeStateSnapshot {
        val installed = store.installedManifest()
        return RuntimeStateSnapshot(
            phase = phase,
            architecture = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            installedVersion = installed?.version,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            runnerAvailable = store.runnerAvailable(),
            harnessUrl = harnessUrl,
            errorCode = errorCode,
        )
    }

    companion object {
        private const val NOTIFY_BYTE_STEP = 512 * 1024L
        private const val NOTIFY_TIME_STEP_NANOS = 200_000_000L
    }
}
