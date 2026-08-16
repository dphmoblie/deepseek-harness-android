package io.deepseekharness.mobile.runtime

import android.content.Context
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class HarnessAccess(
    val url: String,
    val username: String,
    val password: String,
) {
    companion object {
        const val USERNAME = "dsh-mobile"
        const val REALM = "DeepSeek Harness Mobile"
    }
}

class RuntimeSupervisor(
    context: Context,
    private val store: RuntimeStore,
    private val status: RuntimeStatus,
) {
    private val appContext = context.applicationContext
    private val launchResolver = RuntimeLaunchResolver(appContext, store)
    private val lock = Any()
    private var harnessProcess: Process? = null
    private var harnessOutput: ProcessOutputTail? = null
    private var harnessAccess: HarnessAccess? = null

    fun startHarness(): RuntimeStateSnapshot {
        // CAS 防重入：启动进行中时直接返回当前状态快照，避免并发启动排队
        if (!starting.compareAndSet(false, true)) {
            return synchronized(lock) { status.snapshot() }
        }
        try {
            return synchronized(lock) {
        val existing = harnessProcess
        if (existing?.isAlive == true && harnessAccess != null) return@synchronized status.snapshot()
        // 冷却期：仅在成功启动后生效，失败可立即重试
        if (System.currentTimeMillis() - lastStartAttemptAt < START_COOLDOWN_MS) {
            return@synchronized status.snapshot()
        }
        clearHarnessState()

        val manifest = store.installedManifest()
            ?: throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
        try {
            launchResolver.verifyGuest(
                NODE_PROBE_ENTRYPOINT,
                "NODE_RUNTIME_FAILED",
                "内置 Node.js 无法在当前设备运行",
                NODE_PROBE_TIMEOUT_SECONDS,
            )
            launchResolver.verifyGuest(
                HARNESS_PROBE_ENTRYPOINT,
                "HARNESS_PREFLIGHT_FAILED",
                "Harness 命令未通过启动自检",
                HARNESS_PROBE_TIMEOUT_SECONDS,
            )
            ensurePortAvailable(manifest.harnessPort)
        } catch (failure: RuntimeFailure) {
            status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = failure.code)
            throw failure
        }

        val access = HarnessAccess(
            url = manifest.harnessUri.toASCIIString(),
            username = HarnessAccess.USERNAME,
            password = generateToken(),
        )
        val launch = try {
            launchResolver.launch(manifest.harnessArgv, access.password)
        } catch (failure: RuntimeFailure) {
            status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = failure.code)
            throw failure
        }
        val process = try {
            ProcessBuilder(launch.argv)
                .directory(store.currentRoot)
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(launch.environment)
                }
                .start()
        } catch (error: Exception) {
            status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = "HARNESS_START_FAILED")
            throw RuntimeFailure("HARNESS_START_FAILED", "无法启动 Harness", error)
        }
        harnessProcess = process
        val output = ProcessOutputTail.drain(process, "dsh-harness-output")
        harnessOutput = output

        try {
            waitForHarness(process, manifest.harnessPort, output)
        } catch (error: RuntimeFailure) {
            terminate(process)
            clearHarnessState()
            status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = error.code)
            throw error
        }
        harnessAccess = access
        lastStartAttemptAt = System.currentTimeMillis()
        status.update(
            RuntimePhase.RUNNING,
            downloaded = manifest.rootfs.compressedBytes,
            total = manifest.rootfs.compressedBytes,
            nextHarnessUrl = manifest.harnessUri.toASCIIString(),
        )
            }
        } finally {
            starting.set(false)
        }
    }

    fun stop(): RuntimeStateSnapshot = synchronized(lock) {
        lastStartAttemptAt = 0
        val process = harnessProcess
        if (process == null || !process.isAlive) {
            clearHarnessState()
            return@synchronized status.refreshIdle()
        }
        status.update(RuntimePhase.STOPPING, nextHarnessUrl = null)
        terminate(process)
        clearHarnessState()
        status.refreshIdle()
    }

    fun isRunning(): Boolean = synchronized(lock) {
        val running = harnessProcess?.isAlive == true
        if (!running) clearHarnessState()
        running
    }

    fun access(): HarnessAccess = synchronized(lock) {
        if (harnessProcess?.isAlive != true) {
            clearHarnessState()
            throw RuntimeFailure("HARNESS_NOT_RUNNING", "Harness 尚未运行")
        }
        harnessAccess ?: throw RuntimeFailure("HARNESS_AUTH_UNAVAILABLE", "Harness 临时凭据不可用")
    }

    private fun clearHarnessState() {
        harnessProcess = null
        harnessOutput?.close()
        harnessOutput = null
        harnessAccess = null
    }

    private fun waitForHarness(process: Process, port: Int, output: ProcessOutputTail) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throwHarnessExit(output)
            }

            if (hasExpectedAuthChallenge(port)) {
                pauseWhileStarting(HARNESS_STABILITY_MS)
                if (!process.isAlive) throwHarnessExit(output)
                val knownFailure = RuntimeDiagnostics.harnessFailure(output.snapshot())
                if (knownFailure.code != "HARNESS_EXITED") {
                    throw RuntimeFailure(knownFailure.code, knownFailure.message)
                }
                if (hasExpectedAuthChallenge(port)) return
            }
            pauseWhileStarting(POLL_INTERVAL_MS)
        }
        throw RuntimeFailure("HARNESS_START_TIMEOUT", "Harness 未在限定时间内启动")
    }

    private fun hasExpectedAuthChallenge(port: Int): Boolean {
        val connection = try {
            URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return false
        }
        return try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = HTTP_PROBE_TIMEOUT_MS
            connection.readTimeout = HTTP_PROBE_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("Connection", "close")
            connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED &&
                connection.getHeaderField("WWW-Authenticate") == EXPECTED_AUTH_CHALLENGE
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun throwHarnessExit(output: ProcessOutputTail): Nothing {
        output.awaitClosed(OUTPUT_DRAIN_TIMEOUT_MS)
        val failure = RuntimeDiagnostics.harnessFailure(output.snapshot())
        throw RuntimeFailure(failure.code, failure.message)
    }

    private fun pauseWhileStarting(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeFailure("HARNESS_START_INTERRUPTED", "Harness 启动等待被中断", error)
        }
    }

    private fun ensurePortAvailable(port: Int) {
        try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("127.0.0.1", port), 1)
            }
        } catch (error: Exception) {
            throw RuntimeFailure("HARNESS_PORT_IN_USE", "Harness 本机端口已被占用", error)
        }
    }

    private fun terminate(process: Process) {
        process.destroy()
        try {
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private val NODE_PROBE_ENTRYPOINT = listOf("/opt/node/bin/node", "--version")
        private val HARNESS_PROBE_ENTRYPOINT = listOf("/usr/local/bin/dsh", "--version")
        private const val START_COOLDOWN_MS = 90_000L
        private val starting = AtomicBoolean(false)
        @Volatile private var lastStartAttemptAt = 0L
        private const val START_TIMEOUT_SECONDS = 120L
        private const val STOP_TIMEOUT_SECONDS = 3L
        private const val NODE_PROBE_TIMEOUT_SECONDS = 15L
        private const val HARNESS_PROBE_TIMEOUT_SECONDS = 30L
        private const val POLL_INTERVAL_MS = 200L
        private const val HARNESS_STABILITY_MS = 600L
        private const val HTTP_PROBE_TIMEOUT_MS = 300
        private const val OUTPUT_DRAIN_TIMEOUT_MS = 750L
        private const val TOKEN_BYTES = 32
        private const val EXPECTED_AUTH_CHALLENGE = "Basic realm=\"${HarnessAccess.REALM}\", charset=\"UTF-8\""
        private val secureRandom = SecureRandom()
    }
}
