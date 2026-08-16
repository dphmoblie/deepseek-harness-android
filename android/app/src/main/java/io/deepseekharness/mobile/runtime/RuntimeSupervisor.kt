package io.deepseekharness.mobile.runtime

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

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
    private val lock = Any()
    private var harnessProcess: Process? = null
    private var harnessAccess: HarnessAccess? = null

    fun startHarness(): RuntimeStateSnapshot = synchronized(lock) {
        val existing = harnessProcess
        if (existing?.isAlive == true && harnessAccess != null) return@synchronized status.snapshot()
        harnessProcess = null
        harnessAccess = null

        val manifest = store.installedManifest()
            ?: throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
        val access = HarnessAccess(
            url = manifest.harnessUri.toASCIIString(),
            username = HarnessAccess.USERNAME,
            password = generateToken(),
        )
        val command = RuntimeCommand.prootArgv(store, manifest.harnessArgv, access.password)
        val process = try {
            ProcessBuilder(command)
                .directory(store.currentRoot)
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(RuntimeCommand.hostEnvironment(appContext, store))
                }
                .start()
        } catch (error: Exception) {
            status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = "HARNESS_START_FAILED")
            throw RuntimeFailure("HARNESS_START_FAILED", "无法启动 Harness", error)
        }
        harnessProcess = process
        drainOutput(process)

        try {
            waitForLoopback(process, manifest.harnessPort)
        } catch (error: RuntimeFailure) {
            terminate(process)
            harnessProcess = null
            harnessAccess = null
            status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = error.code)
            throw error
        }
        harnessAccess = access
        status.update(
            RuntimePhase.RUNNING,
            downloaded = manifest.rootfs.compressedBytes,
            total = manifest.rootfs.compressedBytes,
            nextHarnessUrl = manifest.harnessUri.toASCIIString(),
        )
    }

    fun stop(): RuntimeStateSnapshot = synchronized(lock) {
        val process = harnessProcess
        if (process == null || !process.isAlive) {
            harnessProcess = null
            harnessAccess = null
            return@synchronized status.refreshIdle()
        }
        status.update(RuntimePhase.STOPPING, nextHarnessUrl = null)
        terminate(process)
        harnessProcess = null
        harnessAccess = null
        status.refreshIdle()
    }

    fun isRunning(): Boolean = synchronized(lock) { harnessProcess?.isAlive == true }

    fun access(): HarnessAccess = synchronized(lock) {
        if (harnessProcess?.isAlive != true) {
            harnessProcess = null
            harnessAccess = null
            throw RuntimeFailure("HARNESS_NOT_RUNNING", "Harness 尚未运行")
        }
        harnessAccess ?: throw RuntimeFailure("HARNESS_AUTH_UNAVAILABLE", "Harness 临时凭据不可用")
    }

    private fun waitForLoopback(process: Process, port: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throw RuntimeFailure("HARNESS_EXITED", "Harness 在监听端口前已退出")
            }
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), SOCKET_TIMEOUT_MS)
                    return
                }
            } catch (_: Exception) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw RuntimeFailure("HARNESS_START_INTERRUPTED", "Harness 启动等待被中断", error)
                }
            }
        }
        throw RuntimeFailure("HARNESS_START_TIMEOUT", "Harness 未在限定时间内启动")
    }

    private fun drainOutput(process: Process) {
        Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (input.read(buffer) >= 0) {
                        // Output can contain credentials or prompts and is intentionally discarded.
                    }
                }
            } catch (_: Exception) {
                // Process termination normally closes this stream.
            }
        }, "dsh-harness-output").apply {
            isDaemon = true
            start()
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
        private const val START_TIMEOUT_SECONDS = 20L
        private const val STOP_TIMEOUT_SECONDS = 3L
        private const val POLL_INTERVAL_MS = 200L
        private const val SOCKET_TIMEOUT_MS = 200
        private const val TOKEN_BYTES = 32
        private val secureRandom = SecureRandom()
    }
}
