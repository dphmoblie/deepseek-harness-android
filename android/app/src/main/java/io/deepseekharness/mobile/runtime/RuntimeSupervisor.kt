package io.deepseekharness.mobile.runtime

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticEvent
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLevel
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class HarnessAccess(
    val url: String,
    val username: String,
    val password: String,
) {
    override fun toString(): String = "HarnessAccess(url=<redacted>, username=$username, password=<redacted>)"

    companion object {
        const val USERNAME = "dsh-mobile"
        const val REALM = "DeepSeek Harness Mobile"
    }
}

/**
 * 应用进程被系统回收时，PRoot→node Harness 子进程不会随之退出，会残留并
 * 继续占用本机端口，导致下次启动报 HARNESS_PORT_IN_USE。启动前依据持久化
 * pid 文件及完整环境标记识别残留进程：根进程还需匹配受信任运行器路径，
 * 所有信号发送前均校验 /proc 启动时间，防止 pid 复用误杀无关进程。
 */
internal object HarnessResidual {
    /** 解析持久化的 pid 记录；非法内容与 pid 1（init）一律视为无记录。 */
    fun parsePid(content: String): Int? = content.trim().toIntOrNull()?.takeIf { it > 1 }

    /** 进程 cmdline（NUL 分隔）的首段必须与受信任运行器路径完全一致。 */
    fun isProotProcess(cmdline: String, runnerPath: String): Boolean {
        val executable = cmdline.split('\u0000').firstOrNull() ?: return false
        return executable.isNotEmpty() && executable == runnerPath
    }

    /** 环境变量按 NUL 分隔后的完整条目匹配，避免路径前缀误认其他进程。 */
    fun hasEnvironmentEntry(environment: String, name: String, value: String): Boolean {
        if (name.isEmpty() || name.any { it == '=' || it == '\u0000' } || value.indexOf('\u0000') >= 0) return false
        val expected = "$name=$value"
        return environment.split('\u0000').any { it == expected }
    }
}

private data class HarnessProcessIdentity(val pid: Int, val startedAt: String)

internal class RuntimeStartCancellation {
    private var starting = false
    private var cancellationRequested = false

    @Synchronized
    fun tryBegin(): Boolean {
        if (starting) return false
        starting = true
        cancellationRequested = false
        return true
    }

    @Synchronized
    fun request(): Boolean {
        if (!starting) return false
        cancellationRequested = true
        return true
    }

    @Synchronized
    fun isRequested(): Boolean = starting && cancellationRequested

    @Synchronized
    fun isStarting(): Boolean = starting

    @Synchronized
    fun finish() {
        starting = false
        cancellationRequested = false
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
    private val startCancellation = RuntimeStartCancellation()
    private val startCancellationEpoch = AtomicLong(0)
    private var harnessProcess: Process? = null

    /*
     * 输出尾部与其留存快照都声明为 @Volatile：写入始终发生在 lock 内，
     * 但读取（界面「运行日志」）刻意不抢 lock —— startHarness 与 stop 会在持锁期间
     * 等待最长数十秒，若读取也去排队，WebView 桥接线程会被一起拖住。
     */
    @Volatile private var harnessOutput: ProcessOutputTail? = null
    @Volatile private var lastHarnessOutput: String? = null
    private var harnessAccess: HarnessAccess? = null
    private var deviceBridgeAccess: DeviceBridgeAccess? = null

    init {
        // 桥接层（io.deepseekharness.mobile.MobileRuntimePlugin）拿不到本类的引用：
        // supervisor 由 MobileRuntimeController 私有持有。这里把只读出口登记到进程级发布点，
        // 由 RuntimeHost 在运行时释放时清空，登记项不会长期持有本实例。
        HarnessOutputTailSource.register(::harnessOutputTail)
    }

    fun configureDeviceBridge(access: DeviceBridgeAccess) = synchronized(lock) {
        if (harnessProcess?.isAlive == true) throw RuntimeFailure("RUNTIME_BUSY", "Harness 运行时不能更改设备桥")
        deviceBridgeAccess = access
    }

    fun startHarness(): RuntimeStateSnapshot {
        val startEpoch = startCancellationEpoch.get()
        if (!startCancellation.tryBegin()) return status.snapshot()
        try {
            if (startCancellationEpoch.get() != startEpoch) {
                throw RuntimeFailure(START_CANCELLED_CODE, "Harness 启动已取消")
            }
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
        throwIfStartCancelled()
        try {
            launchResolver.verifyGuest(
                NODE_PROBE_ENTRYPOINT,
                "NODE_RUNTIME_FAILED",
                "内置 Node.js 无法在当前设备运行",
                NODE_PROBE_TIMEOUT_SECONDS,
                startCancellation::isRequested,
            )
            launchResolver.verifyGuest(
                HARNESS_PROBE_ENTRYPOINT,
                "HARNESS_PREFLIGHT_FAILED",
                "Harness 命令未通过启动自检",
                HARNESS_PROBE_TIMEOUT_SECONDS,
                startCancellation::isRequested,
            )
            throwIfStartCancelled()
            ensurePortAvailable(manifest.harnessPort)
        } catch (failure: RuntimeFailure) {
            if (failure.code != START_CANCELLED_CODE) {
                status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = failure.code)
            }
            throw failure
        }

        val password = generateToken()
        val launch = try {
            val configuredProviders = store.providerApiKeys().keys
            val providerPatchPath = store.prepareProviderPatch(configuredProviders)
            val providerEntrypoint = RuntimeCommand.withProviderPatch(manifest.harnessArgv, providerPatchPath)
            val pluginPatch = File(store.currentRoot, "root/.dsh-mobile/launcher-plugins.patch.json")
            val harnessEntrypoint = if (pluginPatch.isFile) {
                providerEntrypoint.take(2) + listOf("--patch", "/root/.dsh-mobile/launcher-plugins.patch.json") + providerEntrypoint.drop(2)
            } else providerEntrypoint
            launchResolver.launch(
                harnessEntrypoint,
                password,
                deviceBridgeAccess,
                startCancellation::isRequested,
            )
        } catch (failure: RuntimeFailure) {
            if (failure.code != START_CANCELLED_CODE) {
                status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = failure.code)
            }
            throw failure
        }
        throwIfStartCancelled()
        // 记录本次启动实际注入的模型凭据**条数**，不记录变量名与取值。
        // 用途：导出诊断日志后即可区分「App 没有可注入的凭据」与「dsh 侧没有用上」。
        // 命名形态取自 ModelProvider 与 CustomModelProvider，二者均以 _API_KEY 结尾；
        // 因此 DSH_MOBILE_AUTH_TOKEN / DSH_DEVICE_BRIDGE_TOKEN 这类临时凭据不会被计入。
        val credentialCount = launch.argv.count { entry ->
            entry.substringBefore('=').matches(Regex("^[A-Z][A-Z0-9_]*_API_KEY$"))
        }
        store.diagnostics.record(
            DiagnosticLevel.INFO,
            DiagnosticEvent.CREDENTIALS,
            mapOf(
                "result" to if (credentialCount > 0) "ok" else "skipped",
                "count" to credentialCount.toString(),
            ),
        )
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
        val output = ProcessOutputTail.drain(process, "dsh-harness-output", manifest.harnessPort)
        harnessOutput = output

        val launchUrl = try {
            waitForHarness(process, manifest.harnessPort, output)
        } catch (error: RuntimeFailure) {
            terminate(process, manifest.harnessPort)
            clearHarnessState()
            if (error.code != START_CANCELLED_CODE) {
                status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = error.code)
            }
            throw error
        }
        throwIfStartCancelled()
        // Credentials remain native/in-memory; status snapshots retain the public manifest URL.
        harnessAccess = HarnessAccess(launchUrl, HarnessAccess.USERNAME, password)
        lastStartAttemptAt = System.currentTimeMillis()
        status.update(
            RuntimePhase.RUNNING,
            downloaded = manifest.rootfs.compressedBytes,
            total = manifest.rootfs.compressedBytes,
            nextHarnessUrl = manifest.harnessUri.toASCIIString(),
            )
            }
        } catch (failure: RuntimeFailure) {
            if (failure.code != START_CANCELLED_CODE) throw failure
            return synchronized(lock) {
                harnessProcess?.takeIf { it.isAlive }?.let { process ->
                    terminate(process, store.installedManifest()?.harnessPort)
                }
                clearHarnessState()
                status.refreshIdle()
            }
        } finally {
            startCancellation.finish()
        }
    }

    fun requestStartCancellation(): Boolean {
        startCancellationEpoch.incrementAndGet()
        return startCancellation.request()
    }

    fun isStarting(): Boolean = startCancellation.isStarting()

    fun stop(): RuntimeStateSnapshot = synchronized(lock) {
        lastStartAttemptAt = 0
        status.update(RuntimePhase.STOPPING, nextHarnessUrl = null)
        try {
            val process = harnessProcess
            val harnessPort = store.installedManifest()?.harnessPort
            if (process == null || !process.isAlive) {
                reapStaleHarness(harnessPort)
            } else {
                terminate(process, harnessPort)
            }
            harnessPort?.let { waitForPortRelease(it) }
            clearHarnessState()
            return@synchronized status.refreshIdle()
        } catch (failure: RuntimeFailure) {
            harnessAccess = null
            status.update(RuntimePhase.ERROR, nextHarnessUrl = null, nextErrorCode = failure.code)
            throw failure
        }
    }

    fun isRunning(): Boolean = synchronized(lock) {
        val running = harnessProcess?.isAlive == true
        running
    }

    /**
     * Harness 进程输出（stdout 与 stderr 已合并）的尾部快照，最多 [maxBytes] 个 UTF-8 字节。
     *
     * 用途：设置页的「运行日志」。工具调用失败时界面往往只有一句没有栈的报错
     * （例如 Cannot read properties of undefined），而 dsh 自己打印的完整异常就落在这段输出里。
     *
     * 取值顺序：优先读正在运行的进程缓冲区；进程已结束（自行退出、启动失败或被停止）时
     * 回退到 [clearHarnessState] 在关闭前留存的最后一次快照。两者都没有时返回 null，
     * 由调用方如实显示「没有可用的运行日志」。
     *
     * 线程模型：刻意**不抢** [lock]。`startHarness` 与 `stop` 会在持锁期间等待最长数十秒，
     * 这里若也去排队，WebView 桥接线程会被一起拖住（用户点开日志就会卡住界面）。
     * 读取只用 @Volatile 字段 + 缓冲区自身的 @Synchronized：单次拷贝上限 16 KB，
     * 与写线程互斥但不会长时间阻塞。
     *
     * 隐私边界：返回的文本可能包含会话内容。它只用于设备上的界面展示：
     * 不落盘、不写诊断日志、不随诊断日志导出。
     */
    fun harnessOutputTail(maxBytes: Int = HARNESS_OUTPUT_TAIL_BYTES): String? {
        // 竞态窗口：clearHarnessState 可能刚清空缓冲区再置空引用，此时读到的是空串；
        // 那与「进程还没有任何输出」是同一种情况，一并回退到留存快照。
        val live = harnessOutput?.snapshot()?.takeIf { it.isNotEmpty() }
        val text = live ?: lastHarnessOutput ?: return null
        return utf8TailWithin(text, maxBytes).takeIf { it.isNotEmpty() }
    }

    /**
     * 是否存在本进程未持有、但仍在运行的 Harness 残留进程。
     *
     * 应用进程被系统回收（强制停止、内存回收、厂商后台清理）时，PRoot→node 子进程
     * 不会随之退出，pid 文件也会保留；但本进程内存里的临时 Basic Auth 凭据已经消失，
     * 旧会话无法恢复。此处只做只读判定，不发送信号、不启动进程：
     * pid 文件缺失、进程已退出或身份与受信任运行器不符时一律返回 false。
     */
    fun hasResidualHarness(): Boolean = synchronized(lock) {
        if (harnessProcess?.isAlive == true) return@synchronized false
        val pid = try {
            HarnessResidual.parsePid(store.harnessPidFile.readText())
        } catch (_: Exception) {
            null
        } ?: return@synchronized false
        if (!isPidAlive(pid)) return@synchronized false
        HarnessResidual.isProotProcess(readProcCmdline(pid), store.launchRunnerFile.absolutePath)
    }

    /** 修改插件前回收旧进程，并确认监听端口已释放。 */
    fun preparePluginManagement() = synchronized(lock) {
        if (harnessProcess?.isAlive == true || isStarting()) throw RuntimeFailure("RUNTIME_BUSY", "请先停止 Harness")
        val port = store.installedManifest()?.harnessPort
        reapStaleHarness(port)
        port?.let { waitForPortRelease(it) }
    }

    fun access(): HarnessAccess = synchronized(lock) {
        if (harnessProcess?.isAlive != true) {
            throw RuntimeFailure("HARNESS_NOT_RUNNING", "Harness 尚未运行")
        }
        harnessAccess ?: throw RuntimeFailure("HARNESS_AUTH_UNAVAILABLE", "Harness 临时凭据不可用")
    }

    private fun clearHarnessState() {
        harnessProcess = null
        // 关闭前留存最后一次输出：进程自行退出或启动失败时，错误码之外的唯一线索就在这里，
        // 而 close() 会清空缓冲区，错过这一次就再也读不到了。
        // 它随本实例（即当前运行时）存在：运行时释放时登记项被清空，本实例随即不可达。
        harnessOutput?.snapshot()?.takeIf { it.isNotEmpty() }?.let { lastHarnessOutput = it }
        harnessOutput?.close()
        harnessOutput = null
        harnessAccess = null
        deleteHarnessPid()
    }

    /** 回收上次启动残留的 Harness 进程树；pid 文件缺失或进程已退出时仅清理记录。 */
    private fun reapStaleHarness(port: Int? = null) {
        val markedProcesses = (findMarkedHarnessProcesses() +
            port?.let(::findListeningProcessIdentities).orEmpty()).distinctBy { it.pid }
        if (markedProcesses.isNotEmpty()) {
            signalProcesses(markedProcesses, OsConstants.SIGKILL)
            waitForProcessExit(markedProcesses, REAP_WAIT_TIMEOUT_MS)
            // 残留回收是「进程曾被系统回收」最直接的证据，值得进诊断时间线。
            store.diagnostics.record(
                DiagnosticLevel.WARN,
                DiagnosticEvent.RECOVERY,
                mapOf("reason" to "reaped_residual", "count" to markedProcesses.size.toString()),
            )
        }
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
            store.diagnostics.record(
                DiagnosticLevel.WARN,
                DiagnosticEvent.RECOVERY,
                mapOf("reason" to "reaped_pid_file", "count" to "1"),
            )
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

    private fun readProcEnvironment(pid: Int): String = try {
        String(File("/proc/$pid/environ").readBytes(), Charsets.ISO_8859_1)
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
        return try {
            File("/proc/$pid/task").listFiles().orEmpty().flatMap { task ->
                try {
                    File(task, "children").readText().trim().split(WHITESPACE)
                        .mapNotNull { it.toIntOrNull()?.takeIf { child -> child > 1 } }
                } catch (_: Exception) {
                    emptyList()
                }
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
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

    /**
     * PRoot 退出时 guest 进程可能被重新挂到其他父进程。启动器注入的 pid 文件路径
     * 对当前 App 安装唯一，扫描完整环境条目可在失去父子关系后继续识别这些进程。
     */
    private fun findMarkedHarnessProcesses(): List<HarnessProcessIdentity> {
        return File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toIntOrNull()?.takeIf { it > 1 } ?: return@mapNotNull null
            if (!HarnessResidual.hasEnvironmentEntry(
                    readProcEnvironment(pid),
                    PID_FILE_ENV,
                    store.harnessPidFile.absolutePath,
                )
            ) {
                return@mapNotNull null
            }
            processIdentity(pid)
        }
    }

    /** 捕获当前进程树并按叶子到根排序，避免先终止 PRoot 后丢失其 tracee。 */
    private fun processTree(rootPid: Int): List<HarnessProcessIdentity> {
        val result = mutableListOf<HarnessProcessIdentity>()
        val visited = mutableSetOf<Int>()
        fun collect(pid: Int) {
            if (!visited.add(pid)) return
            readChildPids(pid).forEach(::collect)
            processIdentity(pid)?.let(result::add)
        }
        collect(rootPid)
        return result
    }

    private fun processIdentity(pid: Int): HarnessProcessIdentity? {
        val startedAt = processStartTime(pid)
        return startedAt.takeIf { it.isNotEmpty() }?.let { HarnessProcessIdentity(pid, it) }
    }

    private fun isSameProcess(identity: HarnessProcessIdentity): Boolean =
        identity.startedAt.isNotEmpty() && processStartTime(identity.pid) == identity.startedAt

    private fun signalProcesses(processes: List<HarnessProcessIdentity>, signal: Int) {
        processes.distinctBy { it.pid }.forEach { identity ->
            if (!isSameProcess(identity)) return@forEach
            try {
                Os.kill(identity.pid, signal)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ESRCH) {
                    throw RuntimeFailure("HARNESS_STOP_FAILED", "无法停止 Harness 进程", error)
                }
            }
        }
    }

    private fun waitForProcessExit(processes: List<HarnessProcessIdentity>, timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (processes.any(::isSameProcess) && System.nanoTime() < deadline) {
            pauseWhileStopping(REAP_POLL_INTERVAL_MS)
        }
    }

    private fun waitForHarness(process: Process, port: Int, output: ProcessOutputTail): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS)
        var authServiceReady = false
        while (System.nanoTime() < deadline) {
            throwIfStartCancelled()
            if (!process.isAlive) {
                throwHarnessExit(output)
            }

            if (hasExpectedAuthChallenge(port)) {
                authServiceReady = true
                pauseWhileStarting(HARNESS_STABILITY_MS)
                if (!process.isAlive) throwHarnessExit(output)
                val knownFailure = RuntimeDiagnostics.harnessFailure(output.snapshot())
                if (knownFailure.code != "HARNESS_EXITED") {
                    throw RuntimeFailure(knownFailure.code, knownFailure.message)
                }
                val launchUrl = output.harnessLaunchUrl()
                if (launchUrl != null && hasExpectedAuthChallenge(port)) return launchUrl
            }
            pauseWhileStarting(POLL_INTERVAL_MS)
        }
        if (authServiceReady && output.harnessLaunchUrl() == null) {
            throw RuntimeFailure("HARNESS_AUTH_UNAVAILABLE", "Harness 未提供有效的网页认证入口")
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
        // The bounded tail may begin inside a credential, so log only the classified failure.
        android.util.Log.w("dsh-runtime", "harness exited code=${failure.code}")
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

    private fun terminate(process: Process, port: Int?) {
        val rootPid = try { HarnessResidual.parsePid(store.harnessPidFile.readText()) } catch (_: Exception) { null }
        val trustedRoot = rootPid?.takeIf {
            HarnessResidual.isProotProcess(readProcCmdline(it), store.launchRunnerFile.absolutePath)
        }?.let(::processIdentity)
        val observed = linkedMapOf<Int, HarnessProcessIdentity>()

        fun discover(): List<HarnessProcessIdentity> {
            val tree = trustedRoot?.takeIf(::isSameProcess)?.let { processTree(it.pid) }.orEmpty()
            val treePids = tree.mapTo(mutableSetOf()) { it.pid }
            val marked = findMarkedHarnessProcesses().filterNot { it.pid in treePids }
            val listeners = port?.let(::findListeningProcessIdentities).orEmpty()
                .filterNot { it.pid in treePids || marked.any { markedProcess -> markedProcess.pid == it.pid } }
            return (tree + marked + listeners).also { current ->
                current.forEach { identity -> observed[identity.pid] = identity }
            }
        }

        // PRoot may wait for its tracees. Signal leaves before the tracer/root.
        signalProcesses(discover(), OsConstants.SIGTERM)
        process.destroy()
        val gracefulDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACEFUL_STOP_TIMEOUT_MS)
        while (System.nanoTime() < gracefulDeadline) {
            discover()
            if (!process.isAlive && observed.values.none(::isSameProcess)) return
            pauseWhileStopping(REAP_POLL_INTERVAL_MS)
        }

        val forceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FORCE_STOP_TIMEOUT_MS)
        do {
            signalProcesses(discover(), OsConstants.SIGKILL)
            if (process.isAlive) process.destroyForcibly()
            if (!process.isAlive && observed.values.none(::isSameProcess) && findMarkedHarnessProcesses().isEmpty()) return
            pauseWhileStopping(REAP_POLL_INTERVAL_MS)
        } while (System.nanoTime() < forceDeadline)

        if (observed.values.any(::isSameProcess) || findMarkedHarnessProcesses().isNotEmpty() || process.isAlive) {
            throw RuntimeFailure("HARNESS_STOP_TIMEOUT", "Harness 进程未在限定时间内结束")
        }
    }

    private fun throwIfStartCancelled() {
        if (startCancellation.isRequested()) {
            throw RuntimeFailure(START_CANCELLED_CODE, "Harness 启动已取消")
        }
    }

    private fun processStartTime(pid: Int): String = try {
        val stat = File("/proc/$pid/stat").readText()
        val fields = stat.substringAfterLast(") ").split(' ')
        if (fields.getOrNull(0) == "Z") "" else fields.getOrNull(19).orEmpty()
    } catch (_: Exception) { "" }

    private fun waitForPortRelease(port: Int) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PORT_RELEASE_TIMEOUT_MS)
        while (true) {
            try { ensurePortAvailable(port); return } catch (error: RuntimeFailure) {
                if (System.nanoTime() >= deadline) throw RuntimeFailure("HARNESS_STOP_TIMEOUT", "Harness 端口尚未释放，请稍后重试停止", error)
                // PRoot 根退出后仍可能出现刚被重挂父进程的 guest，按唯一环境标记再次回收。
                signalProcesses(findMarkedHarnessProcesses(), OsConstants.SIGKILL)
            }
            pauseWhileStopping(REAP_POLL_INTERVAL_MS)
        }
    }

    /**
     * PRoot tracees can outlive the tracer and lose their parent relationship. Locate
     * listeners for the fixed loopback Harness port through /proc and return only
     * processes owned by this app, avoiding interference with other applications.
     */
    private fun findListeningProcessIdentities(port: Int): List<HarnessProcessIdentity> {
        if (port !in 1024..65535) return emptyList()
        val targetPort = port.toString(16).uppercase()
        val inodes = buildSet {
            listOf("/proc/net/tcp", "/proc/net/tcp6").forEach { path ->
                try {
                    File(path).useLines { lines ->
                        lines.drop(1).forEach { line ->
                            val fields = line.trim().split(WHITESPACE)
                            if (fields.size > 11 && fields[3] == "0A" &&
                                fields[1].substringAfter(':', "") == targetPort
                            ) add(fields[10])
                        }
                    }
                } catch (_: Exception) {
                    // /proc entries may disappear while processes exit.
                }
            }
        }
        if (inodes.isEmpty()) return emptyList()
        return File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toIntOrNull()?.takeIf { it > 1 } ?: return@mapNotNull null
            if (!isOwnedByApp(pid)) return@mapNotNull null
            val fdDir = File(entry, "fd")
            val ownsSocket = try {
                fdDir.listFiles().orEmpty().any { fd ->
                    val target = try { Os.readlink(fd.absolutePath) } catch (_: Exception) { "" }
                    target.startsWith("socket:[") && target.removePrefix("socket:[").removeSuffix("]") in inodes
                }
            } catch (_: Exception) {
                false
            }
            if (ownsSocket) processIdentity(pid) else null
        }
    }

    private fun isOwnedByApp(pid: Int): Boolean = try {
        val uidLine = File("/proc/$pid/status").useLines { lines -> lines.firstOrNull { it.startsWith("Uid:") } }
        uidLine?.trim()?.split(WHITESPACE)?.getOrNull(1)?.toIntOrNull() == android.os.Process.myUid()
    } catch (_: Exception) {
        false
    }

    private fun pauseWhileStopping(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeFailure("HARNESS_STOP_INTERRUPTED", "Harness 停止等待被中断", error)
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
        @Volatile private var lastStartAttemptAt = 0L
        internal const val START_CANCELLED_CODE = "HARNESS_START_CANCELLED"
        private const val START_TIMEOUT_SECONDS = 120L
        private const val GRACEFUL_STOP_TIMEOUT_MS = 2_000L
        private const val FORCE_STOP_TIMEOUT_MS = 5_000L
        private const val PORT_RELEASE_TIMEOUT_MS = 5_000L
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
