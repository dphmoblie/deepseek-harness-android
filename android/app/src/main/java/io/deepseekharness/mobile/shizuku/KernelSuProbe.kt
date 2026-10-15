package io.deepseekharness.mobile.shizuku

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * KernelSU 状态探测。
 *
 * 与上一版的差异（上一版会把可用设备误判为不可用，或把非 KernelSU 的 root 误判为就绪）：
 * 1. **GKI 不再是硬门槛**：`>= 5.10` 只代表内核版本落进 GKI 2.0 区间，
 *    而 KernelSU 另有一条 LKM（可加载内核模块）路线，4.9/5.4 设备同样能装上；
 *    因此内核版本只作为诊断信息上报，判定可用性靠「管理器存在」或「`su -v` 自报 KernelSU 变体」；
 * 2. **管理器包名补上官方 `me.weishu.kernelsu`**——原列表只有两个第三方变体，官方版反而不认；
 * 3. **变体识别**：`su -v` 输出里找 `kernelsu`/`sukisu`/`kowsu`/`mksu` 等特征，
 *    识别出 Magisk 之类的其他 root 实现时明确报 `ROOT_IMPL_NOT_KERNELSU`，不再含糊地报「未授权」；
 * 4. **`needs_action` 只留给权限未授予**：探测到特征却拿不到 root，说明等待用户在管理器里放行；
 *    拿不到 root 而是因为别的实现，属于 [STATUS_UNSUPPORTED]；
 * 5. 探测结果 **带 60 秒缓存**（`su -v` + `su -c id -u` 会拉起进程，不适合每帧调用），
 *    并在同一次探测里完成 `su -c id -u`，不再只看 `su -v` 的退出码就断言已授权。
 *
 * 【探测原则】普通应用不能读写 `/data/adb`（会触发 DAC/SELinux 拒绝并留 audit 告警），
 * KernelSU 也会对未授权应用隐藏 `su` 节点，所以一切判定都靠真实进程探测与包管理器查询。
 */
object KernelSuProbe {

    const val STATUS_READY = "ready"
    const val STATUS_NEEDS_ACTION = "needs_action"
    const val STATUS_UNSUPPORTED = "unsupported"

    const val REASON_READY = "READY"
    const val REASON_KERNEL_UNSUPPORTED = "KERNELSU_KERNEL_UNSUPPORTED"
    const val REASON_NOT_INSTALLED = "KERNELSU_NOT_INSTALLED"
    const val REASON_PERMISSION_REQUIRED = "KERNELSU_PERMISSION_REQUIRED"
    const val REASON_ROOT_IMPL_NOT_KERNELSU = "ROOT_IMPL_NOT_KERNELSU"

    /** 管理器包名：官方放第一位（历史实现只认两个第三方变体）。 */
    val KERNELSU_MANAGER_PACKAGES = listOf(
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "me.tiann.kernelsu",
        "com.sukisu.ultra",
    )

    /** `su -v` 自报变体里的 KernelSU 家族特征（小写匹配）。 */
    val KERNELSU_VARIANT_MARKERS = listOf(
        "kernelsu",
        "kernelsu next",
        "sukisu",
        "kowsu",
        "mksu",
    )

    /** `su -v` 自报变体里的其他 root 实现特征，用于给出「不是 KernelSU」的明确结论。 */
    private val OTHER_ROOT_MARKERS = listOf("magisk", "apatch", "superuser", "supersu")

    const val CACHE_TTL_MS = 60_000L

    private const val SU_VERSION_TIMEOUT_MS = 1_500L
    private const val SU_GRANT_TIMEOUT_MS = 3_000L
    private const val MAX_PROBE_OUTPUT = 4_096

    private val KERNEL_VERSION_PATTERN = Pattern.compile(
        """Linux\s+version\s+(\d+)\.(\d+)\.(\d+)""",
        Pattern.CASE_INSENSITIVE,
    )

    data class ProbeResult(
        val status: String,
        val reason: String,
        val kernelVersion: String?,
        val isGkiSupported: Boolean,
        val isSuAvailable: Boolean,
        val isKernelSuDetected: Boolean,
        /** `su -v` 的原始输出（已裁剪），用于诊断面板与问题上报。 */
        val suVersion: String? = null,
    ) {
        /** 可用但有保留意见时的一句话说明，供界面提示；没有保留意见时为 null。 */
        fun warning(): String? = when {
            status == STATUS_READY && !isGkiSupported ->
                "内核不在 GKI 2.0（>= 5.10）区间，走的是 LKM 路线；若通道异常请优先怀疑内核模块版本"
            else -> null
        }
    }

    /** 解析 `/proc/version`，取主次版本号。 */
    fun parseKernelVersion(procVersionText: String): Pair<Int, Int>? {
        val matcher = KERNEL_VERSION_PATTERN.matcher(procVersionText)
        if (!matcher.find()) return null
        val major = matcher.group(1)?.toIntOrNull() ?: return null
        val minor = matcher.group(2)?.toIntOrNull() ?: return null
        return major to minor
    }

    /** 内核主次版本号是否落进 GKI 2.0 区间（>= 5.10）；仅作诊断，不作可用性门槛。 */
    fun isGkiCompatible(major: Int, minor: Int): Boolean = major > 5 || (major == 5 && minor >= 10)

    /**
     * 纯事实推导：依据探测到的事实判定 KernelSU 可用性。
     *
     * @param suIsKernelSu `su -v` 是否自报 KernelSU 家族；`null` 表示没拿到 `su` 输出。
     */
    fun evaluate(
        isGkiSupported: Boolean,
        hasKernelSuManager: Boolean,
        hasSuBinary: Boolean,
        isRootGranted: Boolean,
        rawVersion: String? = null,
        suIsKernelSu: Boolean? = null,
        suVersion: String? = null,
    ): ProbeResult {
        if (!hasSuBinary && !hasKernelSuManager) {
            return ProbeResult(
                status = STATUS_UNSUPPORTED,
                reason = REASON_NOT_INSTALLED,
                kernelVersion = rawVersion,
                isGkiSupported = isGkiSupported,
                isSuAvailable = false,
                isKernelSuDetected = false,
            )
        }

        // 明确识别为其他实现时不能被残留管理器覆盖；仅缺少版本读数时以管理器兜底。
        val kernelSuSignature = suIsKernelSu == true || (suIsKernelSu == null && hasKernelSuManager)
        if (!kernelSuSignature) {
            return ProbeResult(
                status = STATUS_UNSUPPORTED,
                reason = REASON_ROOT_IMPL_NOT_KERNELSU,
                kernelVersion = rawVersion,
                isGkiSupported = isGkiSupported,
                isSuAvailable = hasSuBinary,
                isKernelSuDetected = false,
                suVersion = suVersion,
            )
        }

        if (!isRootGranted) {
            return ProbeResult(
                status = STATUS_NEEDS_ACTION,
                reason = REASON_PERMISSION_REQUIRED,
                kernelVersion = rawVersion,
                isGkiSupported = isGkiSupported,
                isSuAvailable = hasSuBinary,
                isKernelSuDetected = true,
                suVersion = suVersion,
            )
        }

        return ProbeResult(
            status = STATUS_READY,
            reason = REASON_READY,
            kernelVersion = rawVersion,
            isGkiSupported = isGkiSupported,
            isSuAvailable = true,
            isKernelSuDetected = true,
            suVersion = suVersion,
        )
    }

    @Volatile
    private var cached: Pair<Long, ProbeResult>? = null

    /** 丢弃缓存；用户在管理器里改了授权之后应主动调用。 */
    fun clearCache() {
        cached = null
    }

    /**
     * 读取本机 `/proc/version` 并探测环境；结果带 [CACHE_TTL_MS] 缓存。
     *
     * @param packageChecker 交给宿主 `PackageManager` 判断管理器是否存在。
     */
    fun probeLocal(
        packageChecker: ((String) -> Boolean)? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ProbeResult {
        cached?.let { (at, result) -> if (nowMs - at in 0 until CACHE_TTL_MS) return result }
        return detect(packageChecker).also { cached = nowMs to it }
    }

    private fun detect(packageChecker: ((String) -> Boolean)?): ProbeResult {
        val procVersion = try {
            File("/proc/version").readText().trim()
        } catch (_: Exception) {
            ""
        }
        val versionPair = parseKernelVersion(procVersion)
        val isGki = versionPair?.let { isGkiCompatible(it.first, it.second) } ?: false

        val hasManager = packageChecker?.let { checker ->
            KERNELSU_MANAGER_PACKAGES.any { checker(it) }
        } ?: false

        val versionRun = runSu(arrayOf("su", "-v"), SU_VERSION_TIMEOUT_MS)
        val suVersion = versionRun?.output?.takeIf { it.isNotBlank() }
        val hasSuBinary = versionRun != null || SU_FALLBACK_PATHS.any { File(it).exists() }
        val suIsKernelSu = suVersion?.let { output ->
            val lowered = output.lowercase(Locale.ROOT)
            KERNELSU_VARIANT_MARKERS.any { lowered.contains(it) } ||
                OTHER_ROOT_MARKERS.none { lowered.contains(it) } && hasManager
        }

        // 只有 su 真能跑起来（不是超时等用户点弹窗）时才算授权成功。
        val isGranted = if (versionRun == null) {
            false
        } else {
            val grantRun = runSu(arrayOf("su", "-c", "id -u"), SU_GRANT_TIMEOUT_MS)
            grantRun != null && !grantRun.timedOut && grantRun.exitCode == 0 &&
                grantRun.output.lineSequence().any { it.trim() == "0" }
        }

        return evaluate(
            isGkiSupported = isGki,
            hasKernelSuManager = hasManager,
            hasSuBinary = hasSuBinary,
            isRootGranted = isGranted,
            rawVersion = versionPair?.let { "${it.first}.${it.second}" },
            suIsKernelSu = suIsKernelSu,
            suVersion = suVersion,
        )
    }

    private val SU_FALLBACK_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/bin/ksud",
    )

    private data class SuRun(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * 拉起一次 `su` 调用并读取输出。
     *
     * - 读流在独立线程里做：`waitFor` 之前就开始读，避免输出撑满管道把子进程卡死；
     * - 超时不代表失败：KernelSU 正在弹窗等用户点授权也会超时，返回 [SuRun.timedOut] 交调用方判断；
     * - 返回 `null` 表示 `su` 根本拉不起来（未安装或内核拒绝 `execve`）。
     */
    private fun runSu(argv: Array<String>, timeoutMs: Long): SuRun? {
        val process = try {
            ProcessBuilder(*argv).redirectErrorStream(true).start()
        } catch (_: Exception) {
            return null
        }

        val buffer = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { stream ->
                    val chunk = CharArray(512)
                    while (buffer.length < MAX_PROBE_OUTPUT) {
                        val read = stream.read(chunk)
                        if (read <= 0) break
                        buffer.appendRange(chunk, 0, minOf(read, MAX_PROBE_OUTPUT - buffer.length))
                    }
                }
            } catch (_: Exception) {
                // 读流失败只影响诊断文本，不影响判定。
            }
        }
        reader.isDaemon = true
        reader.start()

        val completed = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }
        if (!completed) {
            process.destroyForcibly()
            return SuRun(exitCode = -1, output = "", timedOut = true)
        }
        reader.join(200)
        return SuRun(exitCode = process.exitValue(), output = buffer.toString().trim(), timedOut = false)
    }
}
