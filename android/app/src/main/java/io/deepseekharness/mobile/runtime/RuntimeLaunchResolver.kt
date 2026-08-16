package io.deepseekharness.mobile.runtime

import android.content.Context
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

class RuntimeLaunchResolver(
    context: Context,
    private val store: RuntimeStore,
) {
    private data class CachedProfile(val key: String, val profile: ProotLaunchProfile)

    private val appContext = context.applicationContext
    private val lock = Any()
    private var cachedProfile: CachedProfile? = null

    fun launch(
        entrypoint: List<String>,
        harnessAuthToken: String? = null,
    ): RuntimeLaunchSpec = synchronized(lock) {
        val resolved = resolveProfile()
        buildLaunch(resolved.profile, entrypoint, harnessAuthToken)
    }

    fun verifyGuest(
        entrypoint: List<String>,
        errorCode: String,
        message: String,
        timeoutSeconds: Long,
    ) = synchronized(lock) {
        val resolved = resolveProfile()
        val firstResult = ProcessProbe.run(
            buildLaunch(resolved.profile, entrypoint),
            store.currentRoot,
            timeoutSeconds,
        )
        if (firstResult.succeeded) return@synchronized

        var failureResults = listOf(firstResult)
        if (!resolved.profile.disableSeccomp && RuntimeDiagnostics.shouldRetryWithoutSeccomp(firstResult)) {
            val fallback = resolved.profile.copy(disableSeccomp = true)
            val fallbackResult = ProcessProbe.run(
                buildLaunch(fallback, entrypoint),
                store.currentRoot,
                timeoutSeconds,
            )
            if (fallbackResult.succeeded) {
                cachedProfile = CachedProfile(resolved.key, fallback)
                return@synchronized
            }
            // Prefer the fallback's concrete failure, while retaining the explicit
            // seccomp diagnosis if the second attempt produced no useful output.
            failureResults = listOf(fallbackResult, firstResult)
        }

        val failure = RuntimeDiagnostics.guestFailure(
            failureResults,
            errorCode,
            message,
        )
        val cause = failureResults.firstNotNullOfOrNull { it.startError }
        throw RuntimeFailure(failure.code, failure.message, cause)
    }

    private fun resolveProfile(): CachedProfile {
        val manifest = prepareRuntime()
        val key = "${manifest.runtimeId}:${manifest.version}:${manifest.rootfs.sha256}"
        return cachedProfile?.takeIf { it.key == key } ?: CachedProfile(key, detectProfile()).also {
            cachedProfile = it
        }
    }

    private fun prepareRuntime(): RuntimeManifest {
        val manifest = store.installedManifest()
            ?: throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
        if (!store.currentRoot.isDirectory) {
            throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
        }
        store.prepareLaunchFiles()
        RuntimeDns.refresh(appContext, store.resolverFile)
        return manifest
    }

    private fun detectProfile(): ProotLaunchProfile {
        val runnerResult = ProcessProbe.run(
            RuntimeLaunchSpec(
                argv = listOf(store.launchRunnerFile.absolutePath, "--version"),
                environment = RuntimeCommand.hostEnvironment(appContext, store),
            ),
            store.currentRoot,
            RUNNER_PROBE_TIMEOUT_SECONDS,
        )
        if (!runnerResult.succeeded) {
            val failure = RuntimeDiagnostics.runnerFailure(runnerResult)
            throw RuntimeFailure(failure.code, failure.message, runnerResult.startError)
        }

        val defaultProfile = ProotLaunchProfile(disableSeccomp = false, bindMounts = emptyList())
        val defaultResult = probeGuest(defaultProfile)
        var profile = if (defaultResult.succeeded) {
            defaultProfile
        } else {
            if (!RuntimeDiagnostics.shouldRetryWithoutSeccomp(defaultResult)) {
                throw guestStartFailure(listOf(defaultResult))
            }
            val fallbackProfile = defaultProfile.copy(disableSeccomp = true)
            val fallbackResult = probeGuest(fallbackProfile)
            if (!fallbackResult.succeeded) {
                throw guestStartFailure(listOf(fallbackResult, defaultResult))
            }
            fallbackProfile
        }

        val bindCandidates = listOf(
            ProotBindMount(store.resolverFile.absolutePath, "/etc/resolv.conf"),
            *SYSTEM_BIND_MOUNTS.toTypedArray(),
        )
        for (mount in bindCandidates) {
            val guestTarget = File(store.currentRoot, mount.target.removePrefix("/"))
            if (!File(mount.source).exists() || !guestTarget.exists()) {
                throw requiredBindFailure()
            }
            val candidate = profile.copy(bindMounts = profile.bindMounts + mount)
            val firstResult = probeGuest(candidate)
            if (firstResult.succeeded) {
                profile = candidate
                continue
            }
            if (!candidate.disableSeccomp && RuntimeDiagnostics.shouldRetryWithoutSeccomp(firstResult)) {
                val fallback = candidate.copy(disableSeccomp = true)
                val fallbackResult = probeGuest(fallback)
                if (fallbackResult.succeeded) {
                    profile = fallback
                    continue
                }
                throw requiredBindFailure(fallbackResult.startError ?: firstResult.startError)
            }
            throw requiredBindFailure(firstResult.startError)
        }
        return profile
    }

    private fun probeGuest(profile: ProotLaunchProfile): ProcessProbeResult = ProcessProbe.run(
        buildLaunch(profile, GUEST_PROBE_ENTRYPOINT),
        store.currentRoot,
        GUEST_PROBE_TIMEOUT_SECONDS,
    )

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

    private fun buildLaunch(
        profile: ProotLaunchProfile,
        entrypoint: List<String>,
        harnessAuthToken: String? = null,
    ): RuntimeLaunchSpec = RuntimeLaunchSpec(
        argv = RuntimeCommand.prootArgv(store, entrypoint, profile.bindMounts, harnessAuthToken),
        environment = RuntimeCommand.hostEnvironment(appContext, store, profile.disableSeccomp),
    )

    private companion object {
        val GUEST_PROBE_ENTRYPOINT = listOf("/bin/bash", "--noprofile", "--norc", "-c", "exit 0")
        val SYSTEM_BIND_MOUNTS = listOf(ProotBindMount("/dev"), ProotBindMount("/proc"))
        const val RUNNER_PROBE_TIMEOUT_SECONDS = 5L
        const val GUEST_PROBE_TIMEOUT_SECONDS = 12L
    }
}

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
    fun run(spec: RuntimeLaunchSpec, workingDirectory: File, timeoutSeconds: Long): ProcessProbeResult {
        val process = try {
            ProcessBuilder(spec.argv)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(spec.environment)
                }
                .start()
        } catch (error: Throwable) {
            return ProcessProbeResult(null, false, "", error)
        }
        val output = ProcessOutputTail.drain(process, "dsh-runtime-probe")
        val completed = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            terminate(process)
            output.close()
            throw RuntimeFailure("RUNTIME_START_INTERRUPTED", "运行时启动自检被中断", error)
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
    private const val OUTPUT_DRAIN_TIMEOUT_MS = 750L
}

internal class ProcessOutputTail private constructor(
    process: Process,
    threadName: String,
) {
    private val buffer = TailBuffer(MAX_OUTPUT_BYTES)
    private val input: InputStream = process.inputStream
    private val reader = Thread({
        try {
            input.use {
                val chunk = ByteArray(4096)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count > 0) buffer.append(chunk, count)
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
    }

    companion object {
        fun drain(process: Process, threadName: String): ProcessOutputTail = ProcessOutputTail(process, threadName)
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
