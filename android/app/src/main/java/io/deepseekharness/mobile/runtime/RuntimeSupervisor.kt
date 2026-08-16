package io.deepseekharness.mobile.runtime

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
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

/**
 * 应用进程被系统回收时，PRoot→node Harness 子进程不会随之退出，会残留并
 * 继续占用本机端口，导致下次启动报 HARNESS_PORT_IN_USE。启动前依据持久化
 * pid 文件识别残留进程：仅当进程 cmdline 与受信任运行器路径完全一致时才
 * 回收（防止 pid 复用误杀无关进程）。
 */
internal object HarnessResidual {
    /** 解析持久化的 pid 记录；非法内容与 pid 1（init）一律视为无记录。 */
    fun parsePid(content: String): Int? = content.trim().toIntOrNull()?.takeIf { it > 1 }

    /** 进程 cmdline（NUL 分隔）的首段必须与受信任运行器路径完全一致。 */
    fun isProotProcess(cmdline: String, runnerPath: String): Boolean {
        val executable = cmdline.split('\u0000').firstOrNull() ?: return false
        return executable.isNotEmpty() && executable == runnerPath
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
        // 先回收上次启动残留的 Harness 进程树（应用被回收时子进程不会随退），
        // 再清理状态，否则 clearHarnessState 会先删掉用于识别残留的 pid 文件
        reapStaleHarness()
        clearHarnessState()

        val manifest = store.installedManifest()
            ?: throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
        RootfsIntegrity.verifyLinks(store.currentRoot, "RUNTIME_CORRUPTED")
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
            ProcessBuilder(harnessLaunchArgv(launch.argv))
                .directory(store.currentRoot)
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(launch.environment)
                    builder.environment().put(PID_FILE_ENV, store.harnessPidFile.absolutePath)
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
        deleteHarnessPid()
    }

    /** 回收上次启动残留的 Harness 进程树；pid 文件缺失或进程已退出时仅清理记录。 */
    private fun reapStaleHarness() {
        val pidFile = store.harnessPidFile
        if (!pidFile.isFile) return
        val content = try {
            pidFile.readText()
        } catch (_: Exception) {
            deleteHarnessPid()
            return
        }
        val pid = HarnessResidual.parsePid(content)
        if (pid == null || !isPidAlive(pid)) {
            deleteHarnessPid()
            return
        }
        // 仅当残留进程身份与受信任运行器一致时才回收，防止 pid 复用误杀无关进程
        if (HarnessResidual.isProotProcess(readProcCmdline(pid), store.launchRunnerFile.absolutePath)) {
            killProcessTree(pid)
            waitForPidExit(pid)
        }
        deleteHarnessPid()
    }

    /**
     * 用 /system/bin/sh 包一层启动：shell 先把自身 pid 写入 DSH_PIDFILE 再 exec
     * 原命令（pid 不变，argv[0] 保留原值）。即使应用在启动瞬间崩溃，残留进程
     * 的 pid 也已落盘，下次启动可以回收。
     */
    private fun harnessLaunchArgv(original: List<String>): List<String> {
        if (original.isEmpty()) {
            throw RuntimeFailure("HARNESS_LAUNCH_ARGV_INVALID", "Harness 启动参数无效")
        }
        return listOf(
            "/system/bin/sh",
            "-c",
            "echo \$\$ > \"\$DSH_PIDFILE\"; exec \"\$0\" \"\$@\"",
            original.first(),
        ) + original.drop(1)
    }

    private fun deleteHarnessPid() {
        try {
            store.harnessPidFile.delete()
        } catch (_: Exception) {
            // 删除失败留给下次启动的残留回收兜底
        }
    }

    private fun isPidAlive(pid: Int): Boolean = try {
        Os.kill(pid, 0)
        true
    } catch (error: ErrnoException) {
        // EPERM 表示进程存在但无权发信号，同样视为存活
        error.errno != OsConstants.ESRCH
    }

    private fun readProcCmdline(pid: Int): String = try {
        File("/proc/$pid/cmdline").readText()
    } catch (_: Exception) {
        ""
    }

    /** 自底向上 SIGKILL 整棵进程树，覆盖 PRoot 之外的残留 guest 进程。 */
    private fun killProcessTree(pid: Int) {
        readChildPids(pid).forEach { child -> killProcessTree(child) }
        try {
            Os.kill(pid, OsConstants.SIGKILL)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ESRCH) return
            // 回收失败不阻断启动：后续 ensurePortAvailable 仍会给出明确的端口占用错误
        }
    }

    private fun readChildPids(pid: Int): List<Int> {
        val content = try {
            File("/proc/$pid/task/$pid/children").readText()
        } catch (_: Exception) {
            return emptyList()
        }
        return content.trim().split(WHITESPACE).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
    }

    private fun waitForPidExit(pid: Int) {
        val deadline = System.currentTimeMillis() + REAP_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!isPidAlive(pid)) return
            try {
                Thread.sleep(REAP_POLL_INTERVAL_MS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
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
                // 必须与真实 Harness 的监听行为对齐：node 的监听 socket 默认启用
                // SO_REUSEADDR，可越过仅剩 TIME_WAIT 连接的端口（虚拟机暂停会冻结
                // TIME_WAIT 计时器，残留条目能存活很久）。探测若不设 REUSEADDR，
                // 会在端口实际空闲时误报 EADDRINUSE；真正有进程在监听时 bind
                // 依然失败，不会漏报。
                socket.reuseAddress = true
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
        private const val REAP_WAIT_TIMEOUT_MS = 5_000L
        private const val REAP_POLL_INTERVAL_MS = 100L
        private val WHITESPACE = Regex("\\s+")
        private const val PID_FILE_ENV = "DSH_PIDFILE"
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
