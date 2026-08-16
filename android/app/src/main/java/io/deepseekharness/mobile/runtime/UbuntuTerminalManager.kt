package io.deepseekharness.mobile.runtime

import android.content.Context
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UbuntuTerminalManager(
    context: Context,
    private val store: RuntimeStore,
    private val onOutput: (sessionId: String, dataBase64: String) -> Unit,
    private val onExit: (sessionId: String, exitCode: Int) -> Unit,
) {
    private data class Session(
        val id: String,
        val processId: Int,
        val fileDescriptor: Int,
        val closing: AtomicBoolean = AtomicBoolean(false),
        val writeLock: Any = Any(),
    )

    private val appContext = context.applicationContext
    private val sessions = ConcurrentHashMap<String, Session>()
    private val ioExecutor = Executors.newCachedThreadPool()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    fun create(columns: Int, rows: Int): String {
        validateSize(columns, rows)
        val manifest = store.installedManifest()
            ?: throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
        val argv = RuntimeCommand.prootArgv(store, manifest.shellArgv).toTypedArray()
        val environment = RuntimeCommand.hostEnvironment(appContext, store)
            .map { (key, value) -> "$key=$value" }
            .toTypedArray()
        val handles = try {
            NativePty.spawn(argv, environment, columns, rows)
        } catch (error: Throwable) {
            throw RuntimeFailure("PTY_START_FAILED", "无法启动 Ubuntu 终端", error)
        }
        if (handles.size != 2 || handles[0] !in 2..Int.MAX_VALUE.toLong() || handles[1] !in 0..Int.MAX_VALUE.toLong()) {
            throw RuntimeFailure("PTY_START_FAILED", "终端返回了无效进程句柄")
        }
        val session = Session(UUID.randomUUID().toString(), handles[0].toInt(), handles[1].toInt())
        sessions[session.id] = session
        ioExecutor.execute { readLoop(session) }
        return session.id
    }

    fun contains(sessionId: String): Boolean = sessions.containsKey(sessionId)

    fun hasSessions(): Boolean = sessions.isNotEmpty()

    fun write(sessionId: String, data: ByteArray) {
        if (data.isEmpty() || data.size > RuntimeLimits.MAX_TERMINAL_INPUT_BYTES) {
            throw RuntimeFailure("TERMINAL_INPUT_INVALID", "终端输入长度无效")
        }
        val session = requireSession(sessionId)
        synchronized(session.writeLock) {
            var offset = 0
            while (offset < data.size) {
                val chunk = if (offset == 0) data else data.copyOfRange(offset, data.size)
                val written = NativePty.write(session.fileDescriptor, chunk)
                if (written <= 0 || written > chunk.size) {
                    throw RuntimeFailure("TERMINAL_WRITE_FAILED", "无法写入终端会话")
                }
                offset += written
            }
        }
    }

    fun resize(sessionId: String, columns: Int, rows: Int) {
        validateSize(columns, rows)
        val session = requireSession(sessionId)
        NativePty.resize(session.fileDescriptor, columns, rows)
    }

    fun close(sessionId: String) {
        val session = requireSession(sessionId)
        closeSession(session)
    }

    fun closeAll() {
        sessions.values.toList().forEach(::closeSession)
    }

    fun closeAllAndWait(timeoutMillis: Long = 4_000) {
        closeAll()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (sessions.isNotEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeFailure("TERMINAL_STOP_INTERRUPTED", "等待终端关闭时被中断", error)
            }
        }
        if (sessions.isNotEmpty()) {
            throw RuntimeFailure("TERMINAL_STOP_TIMEOUT", "终端进程未在限定时间内结束")
        }
    }

    fun shutdown() {
        try {
            closeAllAndWait()
        } finally {
            ioExecutor.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    private fun readLoop(session: Session) {
        var exitCode = 255
        var waited = false
        try {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = NativePty.read(session.fileDescriptor, buffer)
                if (read <= 0) break
                val encoded = Base64.getEncoder().encodeToString(buffer.copyOf(read))
                onOutput(session.id, encoded)
            }
            exitCode = NativePty.waitFor(session.processId)
            waited = true
        } catch (_: Throwable) {
            try {
                NativePty.signal(session.processId, SIGNAL_KILL)
                exitCode = NativePty.waitFor(session.processId)
                waited = true
            } catch (_: Throwable) {
                exitCode = 255
            }
        } finally {
            if (!waited) {
                try {
                    NativePty.signal(session.processId, SIGNAL_KILL)
                    exitCode = NativePty.waitFor(session.processId)
                } catch (_: Throwable) {
                    exitCode = 255
                }
            }
            sessions.remove(session.id, session)
            try {
                NativePty.close(session.fileDescriptor)
            } catch (_: Throwable) {
                // Descriptor ownership ends with this session.
            }
            onExit(session.id, exitCode)
        }
    }

    private fun closeSession(session: Session) {
        if (!session.closing.compareAndSet(false, true)) return
        try {
            NativePty.signal(session.processId, SIGNAL_TERM)
        } catch (_: Throwable) {
            return
        }
        scheduler.schedule({
            if (sessions.containsKey(session.id)) {
                try {
                    NativePty.signal(session.processId, SIGNAL_KILL)
                } catch (_: Throwable) {
                    // The process may already have exited.
                }
            }
        }, FORCE_KILL_DELAY_SECONDS, TimeUnit.SECONDS)
    }

    private fun requireSession(sessionId: String): Session {
        if (!SESSION_ID_PATTERN.matches(sessionId)) {
            throw RuntimeFailure("SESSION_ID_INVALID", "终端会话标识无效")
        }
        return sessions[sessionId] ?: throw RuntimeFailure("SESSION_NOT_FOUND", "终端会话不存在或已结束")
    }

    companion object {
        private val SESSION_ID_PATTERN = Regex("^[a-f0-9-]{36}$")
        private const val SIGNAL_TERM = 15
        private const val SIGNAL_KILL = 9
        private const val FORCE_KILL_DELAY_SECONDS = 2L

        fun validateSize(columns: Int, rows: Int) {
            if (columns !in 20..300 || rows !in 4..150) {
                throw RuntimeFailure("TERMINAL_SIZE_INVALID", "终端窗口尺寸无效")
            }
        }
    }
}
