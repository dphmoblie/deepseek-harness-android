package io.deepseekharness.mobile.shizuku

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import io.deepseekharness.mobile.runtime.NativePty
import io.deepseekharness.mobile.runtime.RuntimeLimits
import io.deepseekharness.mobile.runtime.UbuntuTerminalManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DeviceShellUserService() : IDeviceShellService.Stub() {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private class Session(
        val id: String,
        val processId: Int,
        val fileDescriptor: Int,
        val callback: IDeviceShellCallback,
        val closing: AtomicBoolean = AtomicBoolean(false),
        val writeLock: Any = Any(),
    ) {
        lateinit var deathRecipient: IBinder.DeathRecipient
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val ioExecutor = Executors.newCachedThreadPool()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    override fun createSession(columns: Int, rows: Int, callback: IDeviceShellCallback?): String {
        UbuntuTerminalManager.validateSize(columns, rows)
        requireNotNull(callback) { "Terminal callback is required" }
        val handles = NativePty.spawn(
            arrayOf(DEVICE_SHELL),
            arrayOf(
                "HOME=/data/local/tmp",
                "LANG=C.UTF-8",
                "PATH=/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "TMPDIR=/data/local/tmp",
            ),
            columns,
            rows,
        )
        check(handles.size == 2 && handles[0] > 1 && handles[1] >= 0) { "Invalid terminal handles" }
        val session = Session(UUID.randomUUID().toString(), handles[0].toInt(), handles[1].toInt(), callback)
        session.deathRecipient = IBinder.DeathRecipient { close(session) }
        try {
            callback.asBinder().linkToDeath(session.deathRecipient, 0)
        } catch (error: RemoteException) {
            NativePty.signal(session.processId, SIGNAL_KILL)
            NativePty.waitFor(session.processId)
            NativePty.close(session.fileDescriptor)
            throw error
        }
        sessions[session.id] = session
        ioExecutor.execute { readLoop(session) }
        return session.id
    }

    override fun write(sessionId: String?, data: ByteArray?) {
        val session = requireSession(sessionId)
        requireNotNull(data) { "Terminal data is required" }
        require(data.isNotEmpty() && data.size <= RuntimeLimits.MAX_TERMINAL_INPUT_BYTES) { "Terminal data length is invalid" }
        synchronized(session.writeLock) {
            var offset = 0
            while (offset < data.size) {
                val chunk = if (offset == 0) data else data.copyOfRange(offset, data.size)
                val written = NativePty.write(session.fileDescriptor, chunk)
                check(written in 1..chunk.size) { "Unable to write terminal" }
                offset += written
            }
        }
    }

    override fun resize(sessionId: String?, columns: Int, rows: Int) {
        UbuntuTerminalManager.validateSize(columns, rows)
        val session = requireSession(sessionId)
        NativePty.resize(session.fileDescriptor, columns, rows)
    }

    override fun closeSession(sessionId: String?) {
        close(requireSession(sessionId))
    }

    override fun closeAll() {
        sessions.values.toList().forEach(::close)
    }

    override fun destroy() {
        closeAll()
        scheduler.schedule({ Runtime.getRuntime().exit(0) }, 3, TimeUnit.SECONDS)
    }

    private fun readLoop(session: Session) {
        var exitCode = 255
        var waited = false
        try {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = NativePty.read(session.fileDescriptor, buffer)
                if (read <= 0) break
                session.callback.onOutput(session.id, buffer.copyOf(read))
            }
            exitCode = NativePty.waitFor(session.processId)
            waited = true
        } catch (_: RemoteException) {
            close(session)
            try {
                exitCode = NativePty.waitFor(session.processId)
                waited = true
            } catch (_: Throwable) {
                exitCode = 255
            }
        } catch (_: Throwable) {
            close(session)
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
                session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
            } catch (_: Throwable) {
                // The callback binder may already be dead.
            }
            try {
                NativePty.close(session.fileDescriptor)
            } catch (_: Throwable) {
                // Descriptor ownership ends with this session.
            }
            try {
                session.callback.onExit(session.id, exitCode)
            } catch (_: RemoteException) {
                // The client may have closed with the terminal.
            }
        }
    }

    private fun close(session: Session) {
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
                    // Process may already be gone.
                }
            }
        }, 2, TimeUnit.SECONDS)
    }

    private fun requireSession(sessionId: String?): Session {
        require(sessionId != null && SESSION_PATTERN.matches(sessionId)) { "Invalid terminal session" }
        return sessions[sessionId] ?: throw IllegalStateException("Terminal session is not active")
    }

    companion object {
        private const val DEVICE_SHELL = "/system/bin/sh"
        private const val SIGNAL_TERM = 15
        private const val SIGNAL_KILL = 9
        private val SESSION_PATTERN = Regex("^[a-f0-9-]{36}$")
    }
}
