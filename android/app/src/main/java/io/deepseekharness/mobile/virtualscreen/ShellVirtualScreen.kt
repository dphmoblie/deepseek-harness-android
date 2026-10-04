package io.deepseekharness.mobile.virtualscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.*
import io.deepseekharness.mobile.runtime.RuntimeFailure
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
    // 最近一帧的空白判定；尚未采集到画面时为 true（未渲染），避免被当成有效画面。
    private var frameBlank = true
    private var frameSampledAt = 0L
    private var encodedFrame: ByteArray? = null
    private var pixels: java.nio.ByteBuffer? = null
    // 空白判定只取采样行，复用这一小块缓冲，不每帧分配整帧数组。
    private var samplePixels = IntArray(0)
    // 预览限帧模式：默认 limited 保持改动前的 180 毫秒限帧行为；间隔一律由 VirtualScreenPolicy.frameInterval 求值，不再硬编码。
    @Volatile private var frameMode = "limited"
    // 最近若干帧的采集时刻环形缓冲（毫秒，elapsedRealtime），在 frameLock 内读写，实测帧率据此换算。
    private val frameStamps = LongArray(FRAME_SAMPLE_COUNT)
    private var frameStampCount = 0
    private var frameStampNext = 0
    private var frameFps = 0.0
    // 会话级触摸手势；随副屏会话创建与释放，停止会话时必须取消未结束的手势。
    private var gesture: VirtualScreenInjector.Gesture? = null
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
                // 延后读取最新缓冲区，既限帧又不丢弃界面静止前的最后一次更新；延迟取当前预览模式的采集间隔。
                if (!scheduled) {
                    scheduled = true
                    handler.postDelayed({ scheduled = false; collectFrame(source) }, VirtualScreenPolicy.frameInterval(frameMode).toLong())
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
            // 触摸直传：进程内通道可用则逐事件注入，否则整段手势在抬起时合成 tap/swipe 交给 input 命令。
            // 显示器要等下面才创建，所以手势只持有取编号的函数；真正注入时编号仍为 0 或 -1 就拒绝，绝不落到主屏。
            gesture = VirtualScreenInjector.Gesture({ id() }, w, h) { args ->
                command(listOf("/system/bin/input", "-d", id().toString()) + args)
            }
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
                    // 只按固定步长取采样行做空白判定，采样缓冲按需扩容后复用，不做整帧遍历。
                    val rows = VirtualScreenPolicy.frameSampleLines(image.height)
                    val buffer = samplePixels.takeIf { it.size >= rows.size * image.width }
                        ?: IntArray(rows.size * image.width).also { samplePixels = it }
                    var offset = 0
                    for (row in rows) {
                        bitmap.getPixels(buffer, offset, image.width, 0, row, image.width, 1)
                        offset += image.width
                    }
                    frameBlank = rows.isNotEmpty() && VirtualScreenPolicy.blankFrame(buffer, image.width, rows.size)
                    frameSampledAt = SystemClock.elapsedRealtime()
                    // 记录本次采集时刻并按最近若干帧重算实测帧率，不额外起采样线程。
                    frameStamps[frameStampNext] = frameSampledAt
                    frameStampNext = (frameStampNext + 1) % frameStamps.size
                    if (frameStampCount < frameStamps.size) frameStampCount++
                    frameFps = measuredFps()
                    // 新帧未编码：空白帧同样不留下缓存，后续截图必须重新判断当前画面。
                    encodedFrame = null
                    frameAt = frameSampledAt
                }
            }
        }
    }

    /** 用最近 [FRAME_SAMPLE_COUNT] 帧采集时刻的相邻间隔均值换算每秒帧数；样本少于 2 个时为 0.0。 */
    private fun measuredFps(): Double {
        if (frameStampCount < 2) return 0.0
        val oldest = (frameStampNext - frameStampCount + frameStamps.size) % frameStamps.size
        val span = frameStamps[(frameStampNext - 1 + frameStamps.size) % frameStamps.size] - frameStamps[oldest]
        // 同一毫秒内连续到达的帧会算出无穷大，按至少 1 毫秒的采样间隔计入。
        return (frameStampCount - 1) * 1000.0 / maxOf(span, 1L)
    }

    @Synchronized fun state(): JSONObject {
        // 画面相关字段在同一个锁内一次读出，避免状态里混用不同帧的快照。
        val frames = synchronized(frameLock) { FrameState(frameAt, frameBlank, frameSampledAt, frameFps) }
        return JSONObject().put("active", display != null)
            .put("sessionId", session).put("displayId", id()).put("packageName", targetPackage)
            .put("width", width).put("height", height).put("frameAtElapsedMs", frames.at)
            // frameBlank=true 表示最近一帧被判定为空白或未渲染（尚未采集到画面时也为 true）。
            .put("frameBlank", frames.blank)
            // 空白判定与新帧采集同一次完成，因此它与 frameAtElapsedMs 取同一时刻。
            .put("frameSampledAtElapsedMs", frames.sampledAt)
            // previewMode 是当前预览模式的稳定标签，frameIntervalMs 是它对应的采集间隔，frameFps 是实测帧率。
            .put("previewMode", VirtualScreenPolicy.frameModeLabel(frameMode))
            .put("frameIntervalMs", VirtualScreenPolicy.frameInterval(frameMode))
            // 一位小数即可，调用方不需要更高精度。
            .put("frameFps", Math.round(frames.fps * 10.0) / 10.0)
            // 触摸通道：stream 为逐事件直传，discrete 为抬起时合成 tap/swipe。
            .put("touchChannel", VirtualScreenInjector.channel())
            .put("uiTreeSupported", false)
            .put("independentFocusRequested", independentFocus)
    }

    @Synchronized fun action(raw: String): String {
        require(raw.length <= 4096)
        val p = JSONObject(raw)
        requireSession(p.getString("sessionId"))
        when (p.getString("action")) {
            // 会话控制。
            "stop" -> close()
            // 分发集合与策略层共用：新增动作必须同时出现在 VirtualScreenPolicy.INPUT_ACTIONS 里。
            in VirtualScreenPolicy.INPUT_ACTIONS -> {
                check(targetVisible()) { "目标应用已离开副屏或系统无法确认其状态" }
                command(VirtualScreenPolicy.inputArguments(p, id(), width, height))
            }
            // 预览限帧模式：校验通过后立即生效，采集间隔按新模式求值。
            "config" -> {
                val mode = p.getString(VirtualScreenPolicy.PREVIEW_FIELD)
                VirtualScreenPolicy.frameInterval(mode)
                frameMode = mode
            }
            // 目标应用热切换：沿用同一个虚拟显示器与采集器，不释放也不重建。
            "target" -> {
                val component = VirtualScreenPolicy.targetRequest(p)
                command(listOf("/system/bin/am", "start", "-W", "--display", id().toString(), "-n", component))
                check(targetVisible()) { "目标应用未能切换到副屏，此设备或应用暂不兼容" }
                targetPackage = component.substringBefore('/')
            }
            // 触摸直传：逐事件注入，不逐事件跑 targetVisible()（它要起 dumpsys 进程，会毁掉直传帧率）。
            "touch" -> {
                val touch = VirtualScreenInjector.request(p, width, height)
                if (touch.phase == "down") check(targetVisible()) { "目标应用已离开副屏或系统无法确认其状态" }
                val gesture = checkNotNull(gesture) { "副屏会话尚未就绪" }
                val result = gesture.handle(touch)
                return JSONObject()
                    .put("sessionId", session).put("displayId", id())
                    .put("touchChannel", VirtualScreenInjector.channel()).put("touch", result)
                    .toString()
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
            val current = checkNotNull(frame)
            // 静止页面复用最后一帧，不能把“画面未变化”误判成连接失效；
            // 但空白帧绝不写入缓存：每次按当前 Bitmap 重新编码，让调用方依据 frameBlank 决定是否重试，
            // 而不是拿一张空白画面的缓存冒充新画面。
            if (frameBlank) encodeFrame(current) else encodedFrame ?: encodeFrame(current).also { encodedFrame = it }
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

    /**
     * 会话校验：会话已切换（用户重新开始副屏）或显示已释放都是可重试的瞬时情形，
     * 必须抛 `VIRTUAL_SCREEN_STOPPED`，不能被上层统一映射成「副屏不可用」。
     */
    private fun requireSession(value: String) {
        VirtualScreenPolicy.session(value)
        if (id() <= 0 || value != session) throw RuntimeFailure("VIRTUAL_SCREEN_STOPPED", "副屏会话已切换，请重新读取状态")
    }

    /** PNG 编码并限制体积；调用方必须已持有 [frameLock]。 */
    private fun encodeFrame(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray().also { check(it.size <= 6 * 1024 * 1024) { "副屏截图过大" } }
    }

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
        // 会话结束前取消未完成的触摸手势（进程内通道补一个取消事件，离散通道丢弃半截手势）；只在此处取消一次。
        gesture?.let { runCatching { it.abort() } }; gesture = null
        runCatching { display?.release() }; display = null
        synchronized(frameLock) {
            val old = reader; reader = null
            runCatching { old?.close() }
            frame?.recycle(); frame = null; frameAt = 0; encodedFrame = null; pixels = null
            // 会话结束后没有可信画面：恢复为「未渲染」，避免下一次截图沿用旧判定。
            frameBlank = true; frameSampledAt = 0
            // 采集时刻与实测帧率同属本次会话，一并清空，下一次会话重新采样。
            frameStampCount = 0; frameStampNext = 0; frameFps = 0.0
        }
        worker?.quitSafely(); worker = null
        session = ""; targetPackage = ""; width = 0; height = 0; independentFocus = false
    }

    private companion object {
        /** 实测帧率使用的采样帧数：只统计最近这么多帧，避免很久以前的间隔拉扁当前帧率。 */
        const val FRAME_SAMPLE_COUNT = 24
    }

    /** 状态里画面相关字段的一次性快照，保证同一份状态描述同一帧。 */
    private class FrameState(val at: Long, val blank: Boolean, val sampledAt: Long, val fps: Double)
}
