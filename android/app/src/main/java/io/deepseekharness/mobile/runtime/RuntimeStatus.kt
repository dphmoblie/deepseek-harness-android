package io.deepseekharness.mobile.runtime

data class RuntimeStateSnapshot(
    val phase: RuntimePhase,
    val architecture: String,
    val installedVersion: String?,
    val updateAvailable: Boolean,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val runnerAvailable: Boolean,
    val harnessUrl: String?,
    val errorCode: String?,
)

class RuntimeStatus(private val store: RuntimeStore) {
    private val installedAtStartup = store.installedManifest()
    /** 上次运行留下的恢复记录；进程重启后只读它来提示重新连接，不据此恢复阶段。 */
    private val startupRecord = store.runtimeIntentRecord()

    @Volatile
    private var phase: RuntimePhase = if (installedAtStartup == null) {
        RuntimePhase.NOT_INSTALLED
    } else {
        RuntimePhase.READY
    }

    /**
     * 当前运行意图，初值来自持久化记录。
     * 进程重启后不会据此把状态伪装成已恢复，只用于提示用户重新连接。
     */
    @Volatile
    private var intent: RuntimeIntent = startupRecord.intent

    /** 最近一次已落盘的阶段；下载与解压的进度回调不会重复写盘。 */
    @Volatile
    private var persistedPhase: RuntimePhase? = startupRecord.phase

    @Volatile private var downloadedBytes = 0L
    @Volatile private var totalBytes = installedAtStartup?.rootfs?.compressedBytes ?: 0L
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
        recordIntent(nextPhase)
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
        recordIntent(phase, forceStop = true)
        return snapshot()
    }

    @Synchronized
    fun snapshot(): RuntimeStateSnapshot {
        val installed = store.installedManifest()
        val bundled = store.bundledManifestOrNull()
        return RuntimeStateSnapshot(
            phase = phase,
            architecture = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            installedVersion = installed?.version,
            updateAvailable = RuntimeUpdatePolicy.isAvailable(
                installed?.runtimeId,
                installed?.version,
                installed?.rootfs?.sha256,
                bundled?.runtimeId,
                bundled?.version,
                bundled?.rootfs?.sha256,
            ),
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            runnerAvailable = store.runnerAvailable(),
            harnessUrl = harnessUrl,
            errorCode = errorCode,
        )
    }

    /**
     * 持久化运行意图、最近阶段与时间。
     *
     * 过渡阶段（preparing/downloading/verifying/extracting/stopping/error）保留上一次
     * 意图，避免启动失败重试时把「运行中」误判成「已停止」；只有确认运行或确认停止
     * 才改写意图。记录中不含地址、凭据、进程号或终端内容。
     *
     * 只有阶段真正变化时才写盘：下载与解压的进度回调可能触发上千次 [update]，
     * 逐次写 SharedPreferences 既无意义也会拖慢安装。
     */
    private fun recordIntent(currentPhase: RuntimePhase, forceStop: Boolean = false) {
        if (!forceStop && currentPhase == persistedPhase) return
        val next = when {
            forceStop -> RuntimeIntent.STOPPED
            currentPhase == RuntimePhase.RUNNING -> RuntimeIntent.RUNNING
            currentPhase == RuntimePhase.NOT_INSTALLED -> RuntimeIntent.STOPPED
            else -> intent
        }
        intent = next
        persistedPhase = currentPhase
        store.recordRuntimeIntent(next, currentPhase, System.currentTimeMillis())
    }

    companion object {
        private const val NOTIFY_BYTE_STEP = 512 * 1024L
        private const val NOTIFY_TIME_STEP_NANOS = 200_000_000L
    }
}
