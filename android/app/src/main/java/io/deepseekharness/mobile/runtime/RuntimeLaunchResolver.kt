package io.deepseekharness.mobile.runtime

import android.content.Context
import android.os.Build
import io.deepseekharness.mobile.BuildConfig
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal data class ProotLaunchProfile(
    val disableSeccomp: Boolean,
    val bindMounts: List<ProotBindMount>,
)

internal data class ProcessProbeResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val output: String,
    val startError: Throwable? = null,
) {
    val succeeded: Boolean get() = startError == null && !timedOut && exitCode == 0
}

internal data class ClassifiedFailure(val code: String, val message: String)

internal fun prootProfileFallbacks(
    profile: ProotLaunchProfile,
    result: ProcessProbeResult,
    commandCanFail: Boolean,
): List<ProotLaunchProfile> {
    val fallbacks = mutableListOf<ProotLaunchProfile>()
    if (!profile.disableSeccomp && RuntimeDiagnostics.shouldRetryWithoutSeccomp(result)) {
        fallbacks += profile.copy(disableSeccomp = true)
    }
    // 投递区与用户目录白名单同属可选绑定：宿主目录不可访问时本来就不会追加（见 preferredProfile），
    // 而一旦 PRoot 因为其中任何一个绑定失败，必须整体撤掉它们 —— 可选能力不能拖垮会话启动。
    val includesMailbox = profile.bindMounts.any { it.target in MAILBOX_BIND_TARGETS }
    if (includesMailbox && (!commandCanFail || RuntimeDiagnostics.prootFailure(result) != null)) {
        fallbacks += profile.copy(bindMounts = profile.bindMounts.filterNot { it.target in MAILBOX_BIND_TARGETS })
    }
    val includesUserDirs = profile.bindMounts.any { RuntimeStorageDirsLayout.isGuestPath(it.target) }
    if (includesUserDirs && (!commandCanFail || RuntimeDiagnostics.prootFailure(result) != null)) {
        fallbacks += profile.copy(
            bindMounts = profile.bindMounts.filterNot { RuntimeStorageDirsLayout.isGuestPath(it.target) },
        )
    }
    return fallbacks.distinct()
}

class RuntimeLaunchResolver(
    context: Context,
    private val store: RuntimeStore,
    private val includeCredentials: Boolean = true,
) {
    private data class CachedProfile(val key: String, val profile: ProotLaunchProfile)

    private data class ProfileProbeOutcome(
        val profile: ProotLaunchProfile?,
        val failureResults: List<ProcessProbeResult>,
    )

    private val appContext = context.applicationContext
    private val profilePreferences = appContext.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()
    private var cachedProfile: CachedProfile? = null

    /**
     * ≤8 目录白名单门面。
     *
     * 与投递区并列，是**访客可见目录的唯一来源**：原先的 `/sdcard` 整体绑定已被它取代
     * （见 `docs/存储权限与导入落点.md` §3.1）。懒初始化：构造它要读偏好，而本解析器
     * 每次启动都会新建，没必要为「不涉及启动」的调用付这份代价。
     */
    private val storageDirs: RuntimeStorageDirs by lazy {
        RuntimeStorageDirs.from(appContext, store) { level, fields ->
            // 诊断日志自己吞掉全部异常，这里不再包一层；路径不进日志（只进受控码与计数）。
            store.diagnostics.record(level, DiagnosticEvent.STORAGE_DIRS, fields)
        }
    }

    fun launch(
        entrypoint: List<String>,
        harnessAuthToken: String? = null,
        deviceBridgeAccess: DeviceBridgeAccess? = null,
        externalCancellation: () -> Boolean = { false },
    ): RuntimeLaunchSpec = synchronized(lock) {
        val resolved = resolveProfile(externalCancellation)
        throwIfStartCancelled(externalCancellation)
        buildLaunch(resolved.profile, entrypoint, harnessAuthToken, deviceBridgeAccess)
    }

    fun verifyGuest(
        entrypoint: List<String>,
        errorCode: String,
        message: String,
        timeoutSeconds: Long,
        externalCancellation: () -> Boolean = { false },
    ) = synchronized(lock) {
        // The real preflight doubles as profile validation. A separate `bash -c exit 0`
        // process here adds a full PRoot startup to every cold launch without increasing safety.
        val resolved = resolveProfileForVerification(externalCancellation)
        val outcome = probeProfiles(
            resolved,
            entrypoint,
            timeoutSeconds,
            commandCanFail = true,
            externalCancellation,
        )
        if (outcome.profile != null) return@synchronized

        val failure = RuntimeDiagnostics.guestFailure(
            outcome.failureResults,
            errorCode,
            message,
        )
        // Keep a bounded, redacted probe tail for device diagnostics. Probe commands do not
        // print credentials, but redaction protects against future dependency error messages.
        android.util.Log.w(
            "dsh-runtime",
            "guest probe failed code=${failure.code} exit=${outcome.failureResults.firstOrNull()?.exitCode} " +
                "timeout=${outcome.failureResults.firstOrNull()?.timedOut} output=" +
                redactDiagnosticOutput(outcome.failureResults.firstOrNull()?.output.orEmpty()),
        )
        val cause = outcome.failureResults.firstNotNullOfOrNull { it.startError }
        throw RuntimeFailure(failure.code, failure.message, cause)
    }

    private fun resolveProfile(externalCancellation: () -> Boolean): CachedProfile {
        throwIfStartCancelled(externalCancellation)
        val manifest = prepareRuntime()
        val key = profileKey(manifest)
        existingProfile(key)?.let { return it }
        val initial = CachedProfile(key, preferredProfile())
        val outcome = probeProfiles(
            initial,
            GUEST_PROBE_ENTRYPOINT,
            GUEST_PROBE_TIMEOUT_SECONDS,
            commandCanFail = false,
            externalCancellation,
        )
        val profile = outcome.profile ?: throw guestStartFailure(outcome.failureResults)
        return CachedProfile(key, profile)
    }

    private fun resolveProfileForVerification(externalCancellation: () -> Boolean): CachedProfile {
        throwIfStartCancelled(externalCancellation)
        val manifest = prepareRuntime()
        val key = profileKey(manifest)
        return existingProfile(key) ?: CachedProfile(key, preferredProfile())
    }

    private fun prepareRuntime(): RuntimeManifest {
        val manifest = store.installedManifest()
            ?: throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
        if (!store.currentRoot.isDirectory) {
            throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
        }
        store.prepareLaunchFiles()
        // `/sdcard` 整体绑定的旧开关已被目录白名单取代：主动删掉这个键，而不是留着不读。
        // 留一个没人读的键比删掉更糟——后来者会以为还存在「一键把共享存储整体绑进访客」的开关。
        // 旧的 profile_key 缓存也会因为键串内容变化而失配，从而自动重跑一次兼容性探测。
        profilePreferences.edit().remove(LEGACY_KEY_PROFILE_INCLUDE_SDCARD).apply()
        RuntimeDns.refresh(appContext, store.resolverFile)
        RuntimeDns.refreshHosts(store.hostsFile)
        return manifest
    }

    private fun preferredProfile(): ProotLaunchProfile {
        val required = listOf(
            ProotBindMount(store.resolverFile.absolutePath, "/etc/resolv.conf"),
            ProotBindMount(store.hostsFile.absolutePath, "/etc/hosts"),
            *SYSTEM_BIND_MOUNTS.toTypedArray(),
        )
        for (mount in required) {
            val guestTarget = File(store.currentRoot, mount.target.removePrefix("/"))
            if (!File(mount.source).exists() || !guestTarget.exists()) {
                throw requiredBindFailure()
            }
        }
        val mounts = required.toMutableList()
        // 投递区：**只在宿主目录确实可访问时才追加**这两个绑定。
        // 不可访问（无「所有文件访问」、目录不可写、ROM 限制）时一个都不加：
        // 绑定一个不存在的宿主路径会让 PRoot 直接起不来，那比「投递区不可用」严重得多。
        // 这条分支与 §4.5 的分层一致——无权限时投递区落到 T0（控制台上传），不是故障。
        mounts.addAll(mailbox().bindMounts())
        // 用户目录白名单：与投递区同属可选绑定，逐条判定、逐条跳过。
        // **`/sdcard` 整体绑定已在这里被取代**：整体绑定让「App 有什么权限」直接等价于
        // 「访客能看到什么」，用户没有任何表达机会（§3.1）。旧偏好键的处置见 prepareRuntime。
        mounts.addAll(storageDirs.bindMounts())
        return ProotLaunchProfile(disableSeccomp = false, bindMounts = mounts)
    }

    /** 投递区门面；只用于追加可选绑定与判断可用性，不接触凭据路径。 */
    private fun mailbox(): RuntimeMailbox = RuntimeMailbox(store)

    private fun probeProfiles(
        initial: CachedProfile,
        entrypoint: List<String>,
        timeoutSeconds: Long,
        commandCanFail: Boolean,
        externalCancellation: () -> Boolean,
    ): ProfileProbeOutcome {
        val pending = ArrayDeque<ProotLaunchProfile>().apply { add(initial.profile) }
        val attempted = linkedSetOf<ProotLaunchProfile>()
        val failures = mutableListOf<ProcessProbeResult>()
        while (pending.isNotEmpty()) {
            throwIfStartCancelled(externalCancellation)
            val profile = pending.removeFirst()
            if (!attempted.add(profile)) continue
            val result = ProcessProbe.run(
                buildLaunch(profile, entrypoint, includeCredentials = false),
                store.currentRoot,
                timeoutSeconds,
                externalCancellation,
            )
            if (result.succeeded) {
                rememberProfile(CachedProfile(initial.key, profile))
                return ProfileProbeOutcome(profile, failures)
            }
            // Newer attempts are more compatible, so keep their classified failure first.
            failures.add(0, result)
            prootProfileFallbacks(profile, result, commandCanFail).forEach { fallback ->
                if (fallback !in attempted) pending.addLast(fallback)
            }
        }
        return ProfileProbeOutcome(null, failures)
    }

    private fun existingProfile(key: String): CachedProfile? {
        cachedProfile?.takeIf { it.key == key }?.let { return it }
        val storedKey = try {
            profilePreferences.getString(KEY_PROFILE_KEY, null)
        } catch (_: ClassCastException) {
            null
        }
        if (storedKey != key) return null
        val preferred = preferredProfile()
        val disableSeccomp = try {
            profilePreferences.getBoolean(KEY_PROFILE_DISABLE_SECCOMP, false)
        } catch (_: ClassCastException) {
            return null
        }
        // 挂载集合必须按**当下**的可选绑定可用性重建：偏好里只存「上次探测成功的 seccomp 开关」，
        // 可选绑定（投递区、用户目录白名单）每次都重新判定，避免拿一份过期的绑定表启动。
        return CachedProfile(key, ProotLaunchProfile(disableSeccomp, preferred.bindMounts))
            .also { cachedProfile = it }
    }

    private fun rememberProfile(resolved: CachedProfile) {
        cachedProfile = resolved
        profilePreferences.edit()
            .putString(KEY_PROFILE_KEY, resolved.key)
            .putBoolean(KEY_PROFILE_DISABLE_SECCOMP, resolved.profile.disableSeccomp)
            .apply()
    }

    private fun profileKey(manifest: RuntimeManifest): String = listOf(
        BuildConfig.VERSION_CODE,
        Build.VERSION.SDK_INT,
        Build.FINGERPRINT,
        manifest.runtimeId,
        manifest.version,
        manifest.rootfs.sha256,
        // 同理：投递区可用性变化（用户授予/撤销「所有文件访问」）会让绑定集合变化，缓存必须失效。
        mailbox().mountableNow(),
        // 用户目录白名单的内容（条数与路径摘要）一变，缓存的启动档就必须失效 ——
        // 与投递区同一机制，只是白名单还要看「用户选了什么」，不能只看可用性。
        storageDirs.cacheToken(),
    ).joinToString(":")

    private fun throwIfStartCancelled(externalCancellation: () -> Boolean) {
        if (externalCancellation()) {
            throw RuntimeFailure(RuntimeSupervisor.START_CANCELLED_CODE, "Harness 启动已取消")
        }
    }

    private fun guestStartFailure(results: List<ProcessProbeResult>): RuntimeFailure {
        val failure = results.firstNotNullOfOrNull(RuntimeDiagnostics::prootFailure)
            ?: ClassifiedFailure("PROOT_GUEST_START_FAILED", "PRoot 无法启动 Ubuntu 用户空间")
        val cause = results.firstNotNullOfOrNull { it.startError }
        return RuntimeFailure(failure.code, failure.message, cause)
    }

    private fun requiredBindFailure(cause: Throwable? = null): RuntimeFailure {
        val failure = RuntimeDiagnostics.requiredBindFailure()
        return RuntimeFailure(failure.code, failure.message, cause)
    }

    /**
     * 组装一次 PRoot 启动参数。
     *
     * `includeCredentials` 默认取本解析器的构造参数，但**探测与自检必须显式传 false**：
     * 那些进程只跑 `node --version`、`dsh --version` 一类的命令，不需要模型凭据，
     * 让凭据落在它们的投递路径上属于无谓的暴露面（最小权限）。
     *
     * 秘密的落地顺序不能颠倒：先由 [RuntimeStore.prepareRuntimeSecrets] 把取值写进 0600 文件，
     * 再把**只有路径**的投递描述交给 [RuntimeCommand.prootArgv]。argv 里因此不会出现任何取值。
     */
    private fun buildLaunch(
        profile: ProotLaunchProfile,
        entrypoint: List<String>,
        harnessAuthToken: String? = null,
        deviceBridgeAccess: DeviceBridgeAccess? = null,
        includeCredentials: Boolean = this.includeCredentials,
    ): RuntimeLaunchSpec {
        val secrets = if (includeCredentials) {
            store.prepareRuntimeSecrets(harnessAuthToken, deviceBridgeAccess)
        } else {
            RuntimeSecretDelivery.NONE
        }
        return RuntimeLaunchSpec(
            argv = RuntimeCommand.prootArgv(
                store = store,
                entrypoint = entrypoint,
                bindMounts = profile.bindMounts,
                secrets = secrets,
                harnessSession = includeCredentials && harnessAuthToken != null,
                deviceBridgePort = deviceBridgeAccess?.takeIf { includeCredentials }?.port,
            ),
            environment = RuntimeCommand.hostEnvironment(appContext, store, profile.disableSeccomp),
            modelCredentialCount = secrets.modelCredentialCount,
        )
    }

    private companion object {
        val GUEST_PROBE_ENTRYPOINT = listOf("/bin/bash", "--noprofile", "--norc", "-c", "exit 0")
        val SYSTEM_BIND_MOUNTS = listOf(ProotBindMount("/dev"), ProotBindMount("/proc"))
        const val GUEST_PROBE_TIMEOUT_SECONDS = 12L
        const val PROFILE_PREFERENCES = "runtime_launch_profile"
        const val KEY_PROFILE_KEY = "profile_key"
        const val KEY_PROFILE_DISABLE_SECCOMP = "disable_seccomp"

        /**
         * 旧的整体 `/sdcard` 绑定开关。**保留常量只为了把它从偏好里删掉**（见 prepareRuntime），
         * 一旦设备上都跑过至少一次新版，这个常量就可以连同删除语句一起去掉。
         */
        const val LEGACY_KEY_PROFILE_INCLUDE_SDCARD = "include_sdcard"

        private fun redactDiagnosticOutput(value: String): String = value
            .replace(Regex("(?i)(api[_-]?key|token|password|secret)=?\\s*[^\\s]+"), "$1=<redacted>")
            .takeLast(4096)
    }
}

/** 投递区在访客内的固定挂载点（宿主侧目录见 `RuntimeMailboxLayout`）。 */
private val MAILBOX_BIND_TARGETS = setOf(RuntimeMailboxLayout.GUEST_INBOX, RuntimeMailboxLayout.GUEST_OUTBOX)

internal object RuntimeDiagnostics {
    fun runnerFailure(result: ProcessProbeResult): ClassifiedFailure = when {
        result.startError != null -> ClassifiedFailure(
            "PROOT_RUNNER_START_FAILED",
            "Android 无法执行 APK 内的 PRoot 运行器",
        )
        result.timedOut -> ClassifiedFailure("PROOT_RUNNER_TIMEOUT", "PRoot 运行器自检超时")
        else -> ClassifiedFailure("PROOT_RUNNER_REJECTED", "PRoot 运行器未通过启动自检")
    }

    fun prootFailure(result: ProcessProbeResult): ClassifiedFailure? {
        if (result.succeeded) return null
        if (result.startError != null) {
            return ClassifiedFailure("PROOT_RUNNER_START_FAILED", "Android 无法执行 APK 内的 PRoot 运行器")
        }
        if (result.timedOut) return ClassifiedFailure("PROOT_PROBE_TIMEOUT", "PRoot 启动 Ubuntu 时超时")
        val output = result.output.lowercase()
        return when {
            "ptrace" in output && ("operation not permitted" in output || "permission denied" in output) ->
                ClassifiedFailure("PROOT_PTRACE_DENIED", "系统内核拒绝 PRoot 所需的 ptrace 操作")
            "seccomp" in output && ("not supported" in output || "operation not permitted" in output) ->
                ClassifiedFailure("PROOT_SECCOMP_UNAVAILABLE", "系统内核的 seccomp 策略与 PRoot 不兼容")
            "proot error" in output || "execve(" in output || "loader" in output ->
                ClassifiedFailure("PROOT_GUEST_EXEC_FAILED", "PRoot 无法加载 Ubuntu 程序")
            else -> null
        }
    }

    fun shouldRetryWithoutSeccomp(result: ProcessProbeResult): Boolean =
        prootFailure(result)?.code == "PROOT_SECCOMP_UNAVAILABLE"

    fun requiredBindFailure(): ClassifiedFailure = ClassifiedFailure(
        "PROOT_REQUIRED_BIND_FAILED",
        "PRoot 无法挂载 Ubuntu 必需的系统路径",
    )

    fun guestFailure(
        results: List<ProcessProbeResult>,
        fallbackCode: String,
        fallbackMessage: String,
    ): ClassifiedFailure {
        results.forEach { result ->
            prootFailure(result)?.let { return it }
            val output = result.output.lowercase()
            when {
                "illegal instruction" in output ->
                    return ClassifiedFailure("NODE_CPU_UNSUPPORTED", "设备 CPU 无法执行内置 Node.js")
                "err_module_not_found" in output || "cannot find module" in output ->
                    return ClassifiedFailure("HARNESS_MODULE_MISSING", "Harness 运行模块不完整")
                "err_dlopen_failed" in output || "node-pty" in output && "error" in output ->
                    return ClassifiedFailure("HARNESS_NATIVE_MODULE_FAILED", "Harness 原生模块无法在当前设备运行")
            }
        }
        return ClassifiedFailure(fallbackCode, fallbackMessage)
    }

    fun harnessFailure(output: String): ClassifiedFailure {
        val normalized = output.lowercase()
        return when {
            "eaddrinuse" in normalized || "address already in use" in normalized ->
                ClassifiedFailure("HARNESS_PORT_IN_USE", "Harness 本机端口已被占用")
            "err_module_not_found" in normalized || "cannot find module" in normalized ->
                ClassifiedFailure("HARNESS_MODULE_MISSING", "Harness 运行模块不完整")
            "err_dlopen_failed" in normalized || "node-pty" in normalized && "error" in normalized ->
                ClassifiedFailure("HARNESS_NATIVE_MODULE_FAILED", "Harness 原生模块无法在当前设备运行")
            "illegal instruction" in normalized ->
                ClassifiedFailure("NODE_CPU_UNSUPPORTED", "设备 CPU 无法执行内置 Node.js")
            "proot error" in normalized || "execve(" in normalized || "loader" in normalized ->
                ClassifiedFailure("PROOT_GUEST_EXEC_FAILED", "PRoot 无法加载 Ubuntu 程序")
            else -> ClassifiedFailure("HARNESS_EXITED", "Harness 在完成启动前已退出")
        }
    }
}

internal object ProcessProbe {
    fun run(
        spec: RuntimeLaunchSpec,
        workingDirectory: File,
        timeoutSeconds: Long,
        externalCancellation: () -> Boolean = { false },
        processStarter: (RuntimeLaunchSpec, File) -> Process = ::startProcess,
        outputLimit: Int = 16 * 1024,
    ): ProcessProbeResult {
        throwIfStartCancelled(externalCancellation)
        val process = try {
            processStarter(spec, workingDirectory)
        } catch (error: Throwable) {
            return ProcessProbeResult(null, false, "", error)
        }
        val output = ProcessOutputTail.drain(process, "dsh-runtime-probe", outputLimit = outputLimit)
        val completed = try {
            waitForExit(process, timeoutSeconds, externalCancellation)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            terminate(process)
            output.close()
            throw RuntimeFailure("RUNTIME_START_INTERRUPTED", "运行时启动自检被中断", error)
        } catch (failure: RuntimeFailure) {
            terminate(process)
            output.close()
            throw failure
        }
        if (!completed) terminate(process)
        output.awaitClosed(OUTPUT_DRAIN_TIMEOUT_MS)
        val result = ProcessProbeResult(
            exitCode = if (process.isAlive) null else process.exitValue(),
            timedOut = !completed,
            output = output.snapshot(),
        )
        output.close()
        return result
    }

    private fun startProcess(spec: RuntimeLaunchSpec, workingDirectory: File): Process =
        ProcessBuilder(spec.argv)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(spec.environment)
            }
            .start()

    internal fun waitForExit(
        process: Process,
        timeoutSeconds: Long,
        externalCancellation: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            throwIfStartCancelled(externalCancellation)
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return false
            val waitMillis = minOf(
                PROBE_CANCEL_POLL_MS,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
            )
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) return true
        }
    }

    private fun throwIfStartCancelled(externalCancellation: () -> Boolean) {
        if (externalCancellation()) {
            throw RuntimeFailure(RuntimeSupervisor.START_CANCELLED_CODE, "Harness 启动已取消")
        }
    }

    private fun terminate(process: Process) {
        process.destroy()
        try {
            if (!process.waitFor(PROBE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(PROBE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
    }

    private const val PROBE_STOP_TIMEOUT_MS = 500L
    private const val PROBE_CANCEL_POLL_MS = 100L
    private const val OUTPUT_DRAIN_TIMEOUT_MS = 750L
}

internal class ProcessOutputTail private constructor(
    process: Process,
    threadName: String,
    harnessPort: Int?,
    outputLimit: Int,
) {
    private val buffer = TailBuffer(outputLimit.coerceIn(1024, 256 * 1024))
    private val webAuth = harnessPort?.let(::HarnessWebAuthCapture)
    private val input: InputStream = process.inputStream
    private val reader = Thread({
        try {
            input.use {
                val chunk = ByteArray(4096)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count > 0) {
                        webAuth?.append(chunk, count)
                        buffer.append(chunk, count)
                    }
                }
            }
        } catch (_: Throwable) {
            // Process shutdown normally closes the pipe.
        }
    }, threadName).apply {
        isDaemon = true
        start()
    }

    fun snapshot(): String = buffer.text()

    fun harnessLaunchUrl(): String? = webAuth?.url()

    fun awaitClosed(timeoutMillis: Long) {
        try {
            reader.join(timeoutMillis)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun close() {
        try {
            input.close()
        } catch (_: Throwable) {
            // The reader may already own a closed process pipe.
        }
        awaitClosed(READER_CLOSE_TIMEOUT_MS)
        buffer.clear()
        webAuth?.clear()
    }

    companion object {
        fun drain(process: Process, threadName: String, harnessPort: Int? = null, outputLimit: Int = MAX_OUTPUT_BYTES): ProcessOutputTail =
            ProcessOutputTail(process, threadName, harnessPort, outputLimit)
        private const val MAX_OUTPUT_BYTES = 16 * 1024
        private const val READER_CLOSE_TIMEOUT_MS = 750L
    }
}

private class TailBuffer(private val capacity: Int) {
    private val bytes = ByteArray(capacity)
    private var size = 0

    @Synchronized
    fun append(source: ByteArray, count: Int) {
        if (count >= capacity) {
            source.copyInto(bytes, 0, count - capacity, count)
            size = capacity
            return
        }
        val overflow = (size + count - capacity).coerceAtLeast(0)
        if (overflow > 0) {
            bytes.copyInto(bytes, 0, overflow, size)
            size -= overflow
        }
        source.copyInto(bytes, size, 0, count)
        size += count
    }

    @Synchronized
    fun text(): String = bytes.copyOf(size).toString(Charsets.UTF_8)

    @Synchronized
    fun clear() {
        bytes.fill(0)
        size = 0
    }
}
