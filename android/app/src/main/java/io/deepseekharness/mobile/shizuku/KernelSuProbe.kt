package io.deepseekharness.mobile.shizuku

import java.io.File
import java.util.regex.Pattern

/**
 * KernelSU 状态检测与探测器。
 *
 * 核心目标：
 * 1. 验证内核版本是否为 GKI 2.0（Linux 5.10 及以上）；
 * 2. 探测设备是否存在 KernelSU 特征（su / ksud / 管理器包名）；
 * 3. 推导 KernelSU 当前可用性状态（READY / NEEDS_ACTION / UNSUPPORTED）。
 *
 * 【安全与权限探测原则】：
 * - 普通 Android 应用（untrusted_app）不能读写 /data/adb 及其子目录，直接使用 `File("/data/adb/...")`
 *   会触发 DAC/SELinux 拒绝产生 audit 告警并永远返回 false。
 * - KernelSU 的安全设计会对未授权应用隐藏 su 文件节点，因此必须通过真实命令探测或包管理器探测。
 * - 纯逻辑与硬件/系统调用分离，便于纯 JVM 单元测试覆盖。
 */
object KernelSuProbe {

    const val STATUS_READY = "ready"
    const val STATUS_NEEDS_ACTION = "needs_action"
    const val STATUS_UNSUPPORTED = "unsupported"

    const val REASON_READY = "READY"
    const val REASON_KERNEL_UNSUPPORTED = "KERNELSU_KERNEL_UNSUPPORTED"
    const val REASON_NOT_INSTALLED = "KERNELSU_NOT_INSTALLED"
    const val REASON_PERMISSION_REQUIRED = "KERNELSU_PERMISSION_REQUIRED"

    val KERNELSU_MANAGER_PACKAGES = listOf(
        "me.tiann.kernelsu",
        "com.rifsxd.ksunext",
    )

    private val KERNEL_VERSION_PATTERN = Pattern.compile(
        """Linux\s+version\s+(\d+)\.(\d+)\.(\d+)""",
        Pattern.CASE_INSENSITIVE
    )

    data class ProbeResult(
        val status: String,
        val reason: String,
        val kernelVersion: String?,
        val isGkiSupported: Boolean,
        val isSuAvailable: Boolean,
        val isKernelSuDetected: Boolean,
    )

    /**
     * 解析 `/proc/version` 输出，判断是否为支持 KernelSU 的 GKI 2.0+ (Linux 5.10+) 内核。
     */
    fun parseKernelVersion(procVersionText: String): Pair<Int, Int>? {
        val matcher = KERNEL_VERSION_PATTERN.matcher(procVersionText)
        if (matcher.find()) {
            val major = matcher.group(1)?.toIntOrNull() ?: return null
            val minor = matcher.group(2)?.toIntOrNull() ?: return null
            return major to minor
        }
        return null
    }

    /**
     * 判断内核主次版本号是否达到 GKI 2.0 最低要求（>= 5.10）。
     */
    fun isGkiCompatible(major: Int, minor: Int): Boolean {
        return (major > 5) || (major == 5 && minor >= 10)
    }

    /**
     * 纯事实推导函数：依据探测到的环境事实判定 KernelSU 状态。
     */
    fun evaluate(
        isGkiSupported: Boolean,
        hasKernelSuManager: Boolean,
        hasSuBinary: Boolean,
        isRootGranted: Boolean,
        rawVersion: String? = null,
    ): ProbeResult {
        if (!isGkiSupported) {
            return ProbeResult(
                status = STATUS_UNSUPPORTED,
                reason = REASON_KERNEL_UNSUPPORTED,
                kernelVersion = rawVersion,
                isGkiSupported = false,
                isSuAvailable = hasSuBinary,
                isKernelSuDetected = false,
            )
        }

        if (!hasKernelSuManager && !hasSuBinary) {
            return ProbeResult(
                status = STATUS_UNSUPPORTED,
                reason = REASON_NOT_INSTALLED,
                kernelVersion = rawVersion,
                isGkiSupported = true,
                isSuAvailable = false,
                isKernelSuDetected = false,
            )
        }

        if (!isRootGranted) {
            return ProbeResult(
                status = STATUS_NEEDS_ACTION,
                reason = REASON_PERMISSION_REQUIRED,
                kernelVersion = rawVersion,
                isGkiSupported = true,
                isSuAvailable = hasSuBinary,
                isKernelSuDetected = true,
            )
        }

        return ProbeResult(
            status = STATUS_READY,
            reason = REASON_READY,
            kernelVersion = rawVersion,
            isGkiSupported = true,
            isSuAvailable = true,
            isKernelSuDetected = true,
        )
    }

    /**
     * 读取本机 `/proc/version` 并执行环境探测。
     * 可选传入 [packageChecker] 委托给宿主 `PackageManager` 判定 KernelSU 管理器是否存在。
     */
    fun probeLocal(packageChecker: ((String) -> Boolean)? = null): ProbeResult {
        val procVersion = try {
            File("/proc/version").readText().trim()
        } catch (_: Exception) {
            ""
        }

        val versionPair = parseKernelVersion(procVersion)
        val isGki = versionPair?.let { isGkiCompatible(it.first, it.second) } ?: false

        // 探测管理器应用是否存在（若传入宿主 packageChecker）
        val hasManager = packageChecker?.let { checker ->
            KERNELSU_MANAGER_PACKAGES.any { checker(it) }
        } ?: false

        // 尝试通过调用 su 执行探测（增加超时防挂起）
        // 在 KernelSU 体系下，即使 stat 隐藏了 su 实体节点，内核也会接管 execve("su")
        val (hasSu, isGranted) = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-v"))
            val completed = process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (completed) {
                val output = process.inputStream.bufferedReader().readText()
                val granted = (process.exitValue() == 0 || output.contains("KernelSU", ignoreCase = true))
                true to granted
            } else {
                // 超时通常意味着 KernelSU 正在弹窗等待用户交互授权，su 必然存在
                process.destroyForcibly()
                true to false
            }
        } catch (_: java.io.IOException) {
            // "Cannot run program su": 未找到可执行文件，回落检测标准 bin 路径
            val fallbackPaths = listOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/system/bin/ksud",
            )
            fallbackPaths.any { File(it).exists() } to false
        } catch (_: Exception) {
            false to false
        }

        val versionStr = versionPair?.let { "${it.first}.${it.second}" }
        return evaluate(
            isGkiSupported = isGki,
            hasKernelSuManager = hasManager,
            hasSuBinary = hasSu,
            isRootGranted = isGranted,
            rawVersion = versionStr,
        )
    }
}
