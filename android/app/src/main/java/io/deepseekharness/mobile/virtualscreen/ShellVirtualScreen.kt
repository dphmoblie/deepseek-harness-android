package io.deepseekharness.mobile.virtualscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** 仅在 Shizuku 用户服务中运行。副屏、采集器和输入共享同一个会话生命周期。 */
class ShellVirtualScreen {
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var owner: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var session = ""
    private var targetPackage = ""
    private var width = 0
    private var height = 0
    private var independentFocus = false
    private val frameLock = Any()
    private var frame: Bitmap? = null
    private var frameAt = 0L
    private var encodedFrame: ByteArray? = null
    private var pixels: java.nio.ByteBuffer? = null
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val writers = Executors.newFixedThreadPool(2)
    private val transfers = Semaphore(2)

    @Synchronized fun start(component: String, w: Int, h: Int, dpi: Int, client: IBinder): String {
        check(Build.VERSION.SDK_INT >= 29) { "副屏操作需要 Android 10 或更高版本" }
        VirtualScreenPolicy.component(component)
        VirtualScreenPolicy.dimensions(w, h, dpi)
        check(display == null) { "请先结束当前副屏会话" }
        try {
            // 清除应用 Binder 身份，系统服务应按 Shizuku 进程的真实权限判断。
            width = w; height = h; targetPackage = component.substringBefore('/')
            session = UUID.randomUUID().toString()
            val thisSession = session
            owner = client
            val recipient = IBinder.DeathRecipient { synchronized(this) { if (session == thisSession) close() } }
            death = recipient
            client.linkToDeath(recipient, 0)
            worker = HandlerThread("dsh-virtual-frames").also { it.start() }
            val capture = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = capture
            val handler = Handler(worker!!.looper)
            var scheduled = false
            capture.setOnImageAvailableListener({ source ->
                // 延后读取最新缓冲区，既限帧又不丢弃界面静止前的最后一次更新。
                if (!scheduled) {
                    scheduled = true
                    handler.postDelayed({ scheduled = false; collectFrame(source) }, 180)
                }
            }, handler)
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
                ?: threadClass.getMethod("systemMain").invoke(null)
            val system = threadClass.getMethod("getSystemContext").invoke(thread) as Context
            val shell = system.createPackageContext("com.android.shell", 0)
            val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            val manager = ctor.newInstance(shell)
            // 自有内容禁止主屏镜像；移除副屏时销毁内容，避免任务迁回主屏。
            var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 6) or (1 shl 8)
            val trusted = Build.VERSION.SDK_INT >= 33 && shell.checkPermission(
                "android.permission.ADD_TRUSTED_DISPLAY", android.os.Process.myPid(), android.os.Process.myUid(),
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (trusted) flags = flags or (1 shl 10)
            independentFocus = trusted && Build.VERSION.SDK_INT >= 34
            if (independentFocus) flags = flags or (1 shl 14) or (1 shl 16)
            // 下列标志属于 AOSP 的隐藏副屏能力，公开 SDK 的 IntDef 未列出；仅特权进程按系统版本使用。
            @android.annotation.SuppressLint("WrongConstant")
            val created = manager.createVirtualDisplay("DSH 目标应用", w, h, dpi, capture.surface, flags)
            display = created
            check(id() > 0) { "系统未创建独立副屏" }
            // 参数数组直传，不经 Shell 字符串解释；不会退回主屏启动。
            command(listOf("/system/bin/am", "start", "-W", "--display", id().toString(), "-n", component))
            check(targetVisible()) { "未能确认目标应用位于副屏，此设备或应用暂不兼容" }
            check(client.isBinderAlive) { "副屏所有者已断开" }
            return state().toString()
        } catch (e: Exception) {
            close()
            throw IllegalStateException("无法启动目标应用副屏：${e.javaClass.simpleName}", e)
        }
    }

    private fun collectFrame(source: ImageReader) {
        // 回调与关闭可能并发；迟到帧不得重新写回已销毁会话。
        runCatching {
            source.acquireLatestImage()?.use { image ->
                synchronized(frameLock) {
                    if (reader !== source) return@use
                    val plane = image.planes[0]
                    check(plane.pixelStride == 4 && plane.rowStride >= image.width * 4)
                    check(image.width == width && image.height == height)
                    val rowBytes = image.width * 4
                    val packed = pixels ?: java.nio.ByteBuffer.allocateDirect(rowBytes * image.height).also { pixels = it }
                    packed.clear()
                    val sourceBytes = plane.buffer.duplicate()
                    val available = sourceBytes.limit()
                    // 最后一行可能没有尾部填充；逐行收紧缓冲区，避免按整块 stride 读取越界。
                    for (row in 0 until image.height) {
                        val offset = row * plane.rowStride
                        check(offset.toLong() + rowBytes <= available)
                        sourceBytes.limit(offset + rowBytes).position(offset)
                        packed.put(sourceBytes)
                    }
                    packed.flip()
                    val bitmap = frame ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { frame = it }
                    bitmap.copyPixelsFromBuffer(packed)
                    encodedFrame = null
                    frameAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    @Synchronized fun state(): JSONObject = JSONObject().put("active", display != null)
        .put("sessionId", session).put("displayId", id()).put("packageName", targetPackage)
        .put("width", width).put("height", height).put("frameAtElapsedMs", synchronized(frameLock) { frameAt })
        .put("previewMode", "limited-fps").put("uiTreeSupported", false)
        .put("independentFocusRequested", independentFocus)

    @Synchronized fun action(raw: String): String {
        require(raw.length <= 4096)
        val p = JSONObject(raw)
        requireSession(p.getString("sessionId"))
        when (p.getString("action")) {
            "stop" -> close()
            "tap", "swipe", "back" -> {
                check(targetVisible()) { "目标应用已离开副屏或系统无法确认其状态" }
                command(VirtualScreenPolicy.inputArguments(p, id(), width, height))
            }
            else -> error("不支持的副屏操作")
        }
        return state().toString()
    }

    @Synchronized fun snapshot(sessionId: String): ParcelFileDescriptor {
        requireSession(sessionId)
        check(targetVisible()) { "目标应用已离开副屏或无法确认其状态" }
        val bytes = synchronized(frameLock) {
            check(frameAt > 0) { "副屏尚未产生画面" }
            // 静止页面复用最后一帧，不能把“画面未变化”误判成连接失效。
            encodedFrame ?: ByteArrayOutputStream().use { out ->
                checkNotNull(frame).compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray().also { check(it.size <= 6 * 1024 * 1024) { "副屏截图过大" }; encodedFrame = it }
            }
        }
        check(transfers.tryAcquire()) { "副屏截图传输繁忙" }
        val pipe = try { ParcelFileDescriptor.createReliablePipe() } catch (e: Exception) { transfers.release(); throw e }
        // 管道绕开 Binder 事务大小上限；未读取的客户端最多占用五秒资源。
        val timeout = timers.schedule({ runCatching { pipe[1].closeWithError("副屏截图传输超时") } }, 5, TimeUnit.SECONDS)
        writers.execute {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
            catch (_: Exception) { runCatching { pipe[1].close() } }
            finally { timeout.cancel(false); transfers.release() }
        }
        return pipe[0]
    }

    private fun id(): Int = display?.display?.displayId ?: -1
    private fun requireSession(value: String) { VirtualScreenPolicy.session(value); check(id() > 0 && value == session) { "副屏会话已失效" } }
    private fun targetVisible() = VirtualScreenPolicy.targetResumed(command(listOf("/system/bin/dumpsys", "activity", "activities")), id(), targetPackage)

    private fun command(args: List<String>): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val timeout = timers.schedule({ process.destroyForcibly() }, 8, TimeUnit.SECONDS)
        try {
            val bytes = process.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= 3 * 1024 * 1024) { "系统返回内容过大" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            check(process.waitFor() == 0) { "系统命令未成功执行" }
            val output = bytes.toString(Charsets.UTF_8)
            check(!Regex("(?m)^\\s*(?:Error:|Exception:|SecurityException)").containsMatchIn(output)) { "系统拒绝副屏命令" }
            return output
        } finally { timeout.cancel(false); process.destroy() }
    }

    @Synchronized fun close() {
        death?.let { recipient -> owner?.let { runCatching { it.unlinkToDeath(recipient, 0) } } }; owner = null; death = null
        runCatching { display?.release() }; display = null
        synchronized(frameLock) {
            val old = reader; reader = null
            runCatching { old?.close() }
            frame?.recycle(); frame = null; frameAt = 0; encodedFrame = null; pixels = null
        }
        worker?.quitSafely(); worker = null
        session = ""; targetPackage = ""; width = 0; height = 0; independentFocus = false
    }
}
