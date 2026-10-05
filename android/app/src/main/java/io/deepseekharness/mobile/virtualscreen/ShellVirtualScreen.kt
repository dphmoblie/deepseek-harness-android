package io.deepseekharness.mobile.virtualscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.*
import android.view.KeyCharacterMap
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
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
    // 最近一次采集失败的异常类名；空串表示没有失败。只作诊断用，不含命令输出或画面内容。
    private var frameError = ""
    private var encodedFrame: ByteArray? = null
    /** 最近一帧的图形缓冲区；预览通过 Binder 传递句柄，避免 PNG 编解码。 */
    private var hardwareFrame: HardwareBuffer? = null
    private var hardwareImage: android.media.Image? = null
    // 连续预览只保留硬件缓冲区；完整 CPU 位图仅在 AI 请求截图时生成。
    @Volatile private var cpuCaptureRequested = false
    private var cpuFrameAt = 0L
    private var pixels: java.nio.ByteBuffer? = null
    // 空白判定只取采样行，复用这一小块缓冲，不每帧分配整帧数组。
    private var samplePixels = IntArray(0)
    // 默认 60fps 保证人眼预览流畅；需要省电时可切回 limited。
    @Volatile private var frameMode = "60fps"
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
            val capture = if (Build.VERSION.SDK_INT >= 29) {
                ImageReader.newInstance(
                    w, h, PixelFormat.RGBA_8888, 3,
                    HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
                )
            } else ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
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
            // VirtualDisplayConfig 与 createVirtualDisplay(VirtualDisplayConfig) 都是 API 34 才有的，
            // 低版本必须走下面的旧重载，否则会在 API 31–33 真机上抛 NoClassDefFoundError。
            val created = if (Build.VERSION.SDK_INT >= 34) {
                val refresh = 185f
                val config = android.hardware.display.VirtualDisplayConfig.Builder("DSH 目标应用", w, h, dpi)
                    .setSurface(capture.surface).setFlags(flags).setRequestedRefreshRate(refresh).build()
                manager.createVirtualDisplay(config)
            } else manager.createVirtualDisplay("DSH 目标应用", w, h, dpi, capture.surface, flags)
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
            source.acquireLatestImage()?.let { image ->
                var retained = false
                synchronized(frameLock) {
                    if (reader !== source) { image.close(); return@let }
                    val plane = image.planes[0]
                    if (Build.VERSION.SDK_INT >= 30) {
                        runCatching { image.hardwareBuffer }.getOrNull()?.let { next ->
                            hardwareImage?.close()
                            hardwareImage = image
                            retained = true
                            hardwareFrame?.close()
                            hardwareFrame = next
                        }
                    }
                    check(plane.pixelStride == 4 && plane.rowStride >= image.width * 4)
                    check(image.width == width && image.height == height)
                    // 预览不再每帧把整张 RGBA 搬到 CPU；硬件缓冲区直接交给前台 GPU。
                    if (cpuCaptureRequested) {
                        val rowBytes = image.width * 4
                        val packed = pixels ?: java.nio.ByteBuffer.allocateDirect(rowBytes * image.height).also { pixels = it }
                        packed.clear()
                        val sourceBytes = plane.buffer.duplicate()
                        val available = sourceBytes.limit()
                        for (row in 0 until image.height) {
                            val offset = row * plane.rowStride
                            check(offset.toLong() + rowBytes <= available)
                            sourceBytes.limit(offset + rowBytes).position(offset)
                            packed.put(sourceBytes)
                        }
                        packed.flip()
                        val bitmap = frame ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { frame = it }
                        bitmap.copyPixelsFromBuffer(packed)
                        cpuFrameAt = SystemClock.elapsedRealtime()
                        cpuCaptureRequested = false
                    }
                    // 空白判断只在需要 CPU 位图时执行，避免高刷新率下重复遍历采样行。
                    frameBlank = if (frame != null) {
                        val rows = VirtualScreenPolicy.frameSampleRows(image.height)
                        val buffer = samplePixels.takeIf { it.size >= rows.size * image.width }
                            ?: IntArray(rows.size * image.width).also { samplePixels = it }
                        var offset = 0
                        val bitmap = checkNotNull(frame)
                        for (row in rows) { bitmap.getPixels(buffer, offset, image.width, 0, row, image.width, 1); offset += image.width }
                        rows.isNotEmpty() && VirtualScreenPolicy.blankFrame(buffer, image.width, rows.size)
                    } else false
                    frameSampledAt = SystemClock.elapsedRealtime()
                    // 记录本次采集时刻并按最近若干帧重算实测帧率，不额外起采样线程。
                    frameStamps[frameStampNext] = frameSampledAt
                    frameStampNext = (frameStampNext + 1) % frameStamps.size
                    if (frameStampCount < frameStamps.size) frameStampCount++
                    frameFps = measuredFps()
                    // 新帧未编码：空白帧同样不留下缓存，后续截图必须重新判断当前画面。
                    encodedFrame = null
                    frameAt = frameSampledAt
                    frameError = ""
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (frameLock as java.lang.Object).notifyAll()
                }
                if (!retained) image.close()
            }
        }.onFailure { error ->
            // 采集失败以前被静默吞掉，预览只能看到「未读到新帧」；这里留下异常类名，
            // 便于区分「系统没有给帧」与「取帧代码自己出错」（不含命令输出与画面内容）。
            synchronized(frameLock) { frameError = error.javaClass.simpleName }
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
        val frames = synchronized(frameLock) { FrameState(frameAt, frameBlank, frameSampledAt, frameFps, frameError) }
        return JSONObject().put("active", display != null)
            .put("sessionId", session).put("displayId", id()).put("packageName", targetPackage)
            .put("width", width).put("height", height).put("frameAtElapsedMs", frames.at)
            .put("displayRefreshRate", display?.display?.refreshRate ?: 0f)
            // frameBlank=true 表示最近一帧被判定为空白或未渲染（尚未采集到画面时也为 true）。
            .put("frameBlank", frames.blank)
            // 空白判定与新帧采集同一次完成，因此它与 frameAtElapsedMs 取同一时刻。
            .put("frameSampledAtElapsedMs", frames.sampledAt)
            // frameError 是最近一次采集失败的异常类名，空串表示没有失败；只作诊断用。
            .put("frameError", frames.error)
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
            // 文本输入：纯 ASCII 走设备 Shell 的 input 命令，含中文/emoji 的文本必须回到主进程的无障碍通道。
            // 必须在 INPUT_ACTIONS 之前匹配：策略层的分发集合里也有 "text"，否则这里的分支永远进不来。
            "text" -> return textAction(p)
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
                val requested = component.substringBefore('/')
                command(listOf("/system/bin/am", "start", "-W", "--display", id().toString(), "-n", component))
                // `am start -W` 返回只代表命令派发完成，不等于目标已经在新屏 resume：在一个有界预算内反复确认。
                // 超时抛带明确 code 的 VIRTUAL_SCREEN_TARGET_TIMEOUT；不能再用 check 抛 IllegalStateException，
                // 那会顺着 VirtualScreenPolicy.errorCode 的 else 兜底变成「副屏不可用」。
                val switched = VirtualScreenPolicy.waitUntilVisible(
                    elapsedMillis = { SystemClock.elapsedRealtime() },
                    sleepMillis = { SystemClock.sleep(it) },
                    // 确认的是刚请求的包名：这一步 targetPackage 还是旧值，拿它去查等于没查。
                    visible = { targetVisible(requested) },
                )
                if (!switched) throw VirtualScreenPolicy.targetSwitchFailure()
                targetPackage = requested
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
            // AI 手势：整段路径交给注入器一次直传（down → 约 16 毫秒步进 move → up），
            // 插值与节拍都在进程内完成，不逐点起 shell 进程；失败在返回里标 aborted，这里翻译成错误码。
            "gesture" -> {
                check(targetVisible()) { "目标应用已离开副屏或系统无法确认其状态" }
                val points = VirtualScreenPolicy.gestureRequest(p, width, height)
                val duration = VirtualScreenPolicy.gestureDuration(p)
                val gesture = checkNotNull(gesture) { "副屏会话尚未就绪" }
                // 注入失败不抛异常（与 touch 同一约定），这里翻译成专用的注入错误码：
                // 副屏会话与目标应用都还正常，别让调用方看到 VIRTUAL_SCREEN_UNAVAILABLE 就以为设备不支持副屏。
                val result = runCatching { gesture.stroke(points, duration) }.getOrElse { error ->
                    (error as? RuntimeFailure)?.let { throw it }
                    throw RuntimeFailure(
                        VirtualScreenPolicy.INJECTION_FAILED_CODE,
                        "副屏手势注入未完成：${error.message ?: "触摸通道拒绝了这次手势"}（副屏会话本身正常，可稍后重试或改用 tap/swipe）",
                    )
                }
                if (result.optBoolean("aborted")) {
                    throw RuntimeFailure(
                        VirtualScreenPolicy.INJECTION_FAILED_CODE,
                        "副屏手势注入未完成：手势被中途取消（副屏会话本身正常，可稍后重试或改用 tap/swipe）",
                    )
                }
                // 回显校验通过的坐标：调用方据此确认设备端实际执行的是哪条路径（校验只拒绝、不夹取坐标）。
                val echoed = JSONArray()
                points.forEach { echoed.put(JSONObject().put("x", it.x).put("y", it.y)) }
                return JSONObject()
                    .put("sessionId", session).put("displayId", id())
                    .put("touchChannel", VirtualScreenInjector.channel())
                    .put("points", echoed).put("durationMs", result.optInt("durationMs", duration))
                    .put("gesture", result)
                    .toString()
            }
            // 跳转应用：在副屏上启动组件 / 包名 / 白名单链接，确认上屏后才把 targetPackage 换成新目标。
            "launch" -> {
                val request = VirtualScreenPolicy.launchRequest(p)
                // 三个分支各自的 `am start` 参数形态：组件与解析出的组件都用 -n，链接用 ACTION_VIEW + -d。
                // 参数永远走 list 交给 ProcessBuilder，绝不拼成 shell 字符串。
                var arguments: List<String> = emptyList()
                var label = ""
                var expected: String? = null
                when (request) {
                    is VirtualScreenPolicy.LaunchRequest.Component -> {
                        arguments = listOf("-n", request.component)
                        label = request.component
                        expected = request.component.substringBefore('/')
                    }
                    is VirtualScreenPolicy.LaunchRequest.Package -> {
                        val component = resolveComponent(request.packageName)
                            ?: throw VirtualScreenPolicy.launchUnresolvedFailure(request.packageName)
                        arguments = listOf("-n", component)
                        label = request.packageName
                        expected = request.packageName
                    }
                    is VirtualScreenPolicy.LaunchRequest.Uri -> {
                        arguments = listOf("-a", "android.intent.action.VIEW", "-d", request.uri)
                        label = request.uri
                        expected = null
                    }
                }
                val launched = launchOnDisplay(arguments, label, expected)
                // 只有确认上屏才更新目标包名：失败路径不写，避免状态里出现一个其实没在副屏上的目标。
                if (launched != null) targetPackage = launched
                return JSONObject()
                    .put("sessionId", session).put("displayId", id())
                    .put("launched", launched ?: "").put("targetPackage", targetPackage)
                    .toString()
            }
            // 跟随：目标应用内部跳转会把新 Activity 落到主屏（display 0），把主屏最前台的可跟随包拉回副屏。
            "follow" -> {
                val dump = command(listOf("/system/bin/dumpsys", "activity", "activities"))
                val candidate = VirtualScreenPolicy.resumedComponent(dump, 0)
                val selfPackage = p.optString(VirtualScreenPolicy.SELF_PACKAGE_FIELD)
                val inputMethods = inputMethods()
                // 桌面排除项共三级来源（2026-10-05 真机实测：`dumpsys activity activities` 里**没有** `mHomeProcess`，
                // 它在不带 `activities` 的 `dumpsys activity` 里）：①顺手从当前 dump 取 ②按需跑一次 `dumpsys activity`
                // ③HOME 意图解析兜底。三级都拿不到时只靠其余排除项，少一层「别把桌面拉进副屏」的保护。
                val home = VirtualScreenPolicy.homePackage(dump) ?: homeProcessFromActivity() ?: homeComponent()
                if (candidate == null || !VirtualScreenPolicy.followable(candidate, selfPackage, inputMethods, home)) {
                    // 没有可跟随对象是一次正常结论（桌面 / 输入法 / 本应用自己 / 系统界面）：返回说明，不抛「副屏不可用」。
                    return JSONObject()
                        .put("sessionId", session).put("displayId", id())
                        .put("followed", false).put("code", VirtualScreenPolicy.FOLLOW_NONE_CODE)
                        .put("message", VirtualScreenPolicy.followNoneReason(candidate, selfPackage, inputMethods, home))
                        .toString()
                }
                if (targetVisible(candidate)) {
                    // 已经在副屏前台：重发 am start 会把应用拉回首页，反而是破坏，如实回报即可。
                    return JSONObject()
                        .put("sessionId", session).put("displayId", id())
                        .put("followed", false).put("alreadyOnDisplay", true).put("packageName", candidate)
                        .put("message", "$candidate 已经在副屏前台，无需跟随")
                        .toString()
                }
                val component = resolveComponent(candidate)
                if (component == null) {
                    // 解析不到启动入口（该应用没有任何可用 `-n` 启动的 Activity）时也**不**抛「副屏不可用」：
                    // 这是一次正常的「跟随不了」结论，把包名、错误码与原因如实回报，由调用方决定下一步。
                    return JSONObject()
                        .put("sessionId", session).put("displayId", id())
                        .put("followed", false).put("packageName", candidate)
                        .put("code", VirtualScreenPolicy.LAUNCH_UNRESOLVED_CODE)
                        .put("message", VirtualScreenPolicy.launchUnresolvedFailure(candidate).message)
                        .toString()
                }
                launchOnDisplay(listOf("-n", component), candidate, candidate)
                targetPackage = candidate
                return JSONObject()
                    .put("sessionId", session).put("displayId", id())
                    .put("followed", true).put("packageName", candidate).put("component", component)
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
            val before = cpuFrameAt
            cpuCaptureRequested = true
            val deadline = SystemClock.elapsedRealtime() + 800L
            while (frame == null || cpuFrameAt <= before) {
                val remain = deadline - SystemClock.elapsedRealtime()
                if (remain <= 0) break
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (frameLock as java.lang.Object).wait(minOf(40L, remain))
            }
            check(frameAt > 0 && frame != null) { "副屏尚未产生画面" }
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

    /** 返回最近一帧的硬件缓冲区，供页面预览直接交给 GPU；调用方完成使用后必须关闭句柄。 */
    @Synchronized fun frame(sessionId: String): HardwareBuffer {
        requireSession(sessionId)
        check(Build.VERSION.SDK_INT >= 30) { "硬件帧预览需要 Android 11 或更高版本" }
        check(frameAt > 0 && hardwareFrame != null) { "副屏尚未产生硬件画面" }
        return checkNotNull(hardwareFrame)
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

    /** 确认某个包名已在本次会话的副屏上 resume；热切换过程中必须传刚请求的包名，默认值只用于当前目标。 */
    private fun targetVisible(packageName: String = targetPackage) =
        VirtualScreenPolicy.targetResumed(command(listOf("/system/bin/dumpsys", "activity", "activities")), id(), packageName)

    /** 指定显示器上最前台的组件名（0 为主屏，会话副屏用 [id]）；解析不到返回 null。 */
    private fun foregroundComponent(displayId: Int): String? =
        VirtualScreenPolicy.resumedComponent(command(listOf("/system/bin/dumpsys", "activity", "activities")), displayId)

    /**
     * 用设备侧 `cmd package resolve-activity --brief <包名>` 把包名解析成可启动组件；解析不到返回 null。
     * 命令失败（未安装 / 没有启动 Activity 会非零退出）与格式不符都收敛成 null，
     * 由调用方按 [VirtualScreenPolicy.launchUnresolvedFailure] 报「没有启动入口」，不误报成副屏不可用。
     */
    private fun resolveComponent(packageName: String): String? = runCatching {
        VirtualScreenPolicy.resolvedComponent(
            command(listOf("/system/bin/cmd", "package", "resolve-activity", "--brief", packageName)),
        )
    }.getOrNull()

    /**
     * 桌面包名兜底：`dumpsys activity activities` 在部分 ROM 上不带 `mHomeProcess`，
     * 此时用设备侧解析 HOME 意图拿默认桌面（解析不到返回 null，调用方只靠其余排除项判断）。
     * 跟随动作**绝不能把桌面拉到副屏**——那等于把用户桌面整体搬进虚拟显示器。
     */
    private fun homeComponent(): String? = runCatching {
        VirtualScreenPolicy.resolvedComponent(
            command(
                listOf(
                    "/system/bin/cmd", "package", "resolve-activity", "--brief",
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
                ),
            ),
        )
    }.getOrNull()?.substringBefore('/')

    /**
     * 桌面进程的第二个来源（2026-10-05 真机实测：`mHomeProcess` 只在不带 `activities` 的 `dumpsys activity` 里，
     * `dumpsys activity activities` 零命中，所以上面那条「顺手从当前 dump 取」在真机上拿不到值）。
     * 这个 dump 体积大（超过 [command] 的 3 MB 上限或 8 秒超时会抛异常），失败一律收敛成 null，
     * 由调用方继续走 HOME 意图解析那一级兜底，不影响跟随动作本身能否执行。
     */
    private fun homeProcessFromActivity(): String? = runCatching {
        VirtualScreenPolicy.homePackage(command(listOf("/system/bin/dumpsys", "activity")))
    }.getOrNull()

    /**
     * 已启用输入法的包名集合（`ime list -s` 每行形如 `包名/服务类名`）：跟随不能把输入法拉到副屏。
     */
    private fun inputMethods(): Set<String> = runCatching {
        command(listOf("/system/bin/ime", "list", "-s"))
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('/') }
            .toSet()
    }.getOrElse { emptySet() }

    /**
     * 在副屏上执行 `am start -W --display <编号> <arguments>` 并确认目标真的上了副屏。
     * - 命令被系统拒绝（非零退出 / 输出含 `Error:`）→ [VirtualScreenPolicy.launchFailedFailure]；
     * - 确认超时 → 复用 [VirtualScreenPolicy.targetSwitchFailure]（同样是有界轮询，不新增「副屏不可用」误报）；
     * - 返回确认到的包名：优先用调用方给的目标包名；链接情形退化为 `am start` 输出里的组件；
     *   两者都没有时只能确认「副屏前台换成了另一个组件」，此时返回 null。
     */
    private fun launchOnDisplay(arguments: List<String>, label: String, expected: String?): String? {
        // 链接没有已知包名：先记下副屏当前前台，用于「换了一个前台」这条退路判定。
        val before = if (expected == null) foregroundComponent(id()) else null
        val output = try {
            command(listOf("/system/bin/am", "start", "-W", "--display", id().toString()) + arguments)
        } catch (error: Exception) {
            // 失败原因（系统命令未成功执行 / 系统拒绝副屏命令）对排查有用，截断后并进文案。
            val reason = error.message.orEmpty().take(120).ifEmpty { "启动命令未成功执行" }
            throw VirtualScreenPolicy.launchFailedFailure("$label（$reason）")
        }
        val launched = VirtualScreenPolicy.resolvedComponent(output)?.substringBefore('/')
        val confirmed = expected ?: launched
        val visible: () -> Boolean = if (confirmed != null) {
            { targetVisible(confirmed) }
        } else {
            { foregroundComponent(id())?.let { it != before } == true }
        }
        val switched = VirtualScreenPolicy.waitUntilVisible(
            elapsedMillis = { SystemClock.elapsedRealtime() },
            sleepMillis = { SystemClock.sleep(it) },
            visible = visible,
        )
        if (!switched) throw VirtualScreenPolicy.targetSwitchFailure()
        return confirmed
    }

    /**
     * `text` 动作在**设备 Shell 侧**的实现。这一侧只负责「不依赖无障碍也能送进去的那一段文字」：
     *
     * 1. 纯 ASCII：`input text` 直接写（它内部就是合成键事件，比逐字符更稳）；
     * 2. `input text` 没成：用 [KeyCharacterMap] 把字符映射成键码，逐字符 `input keyevent` 兜底——
     *    **绝不自己拼 shell 字符串**，映射不出来（需要 Shift 组合的大写字母、部分符号）就如实失败；
     * 3. 含非 ASCII：这条路根本送不进去（`input text` 只吃可打印 ASCII）。这里是 Shizuku 用户服务进程，
     *    拿不到无障碍服务实例（`DeepSeekAccessibilityService.current()` 是进程内静态引用），所以只如实回报
     *    「这次注入必须回到主进程的无障碍通道」——**不是**「副屏不可用」，副屏本身一切正常。
     * 4. `submit=true`：文字**确实写进去之后**才按一次回车（键码复用策略层那唯一一份映射），失败不按。
     */
    private fun textAction(p: JSONObject): String {
        val text = p.optString("text", "")
        val submit = p.optBoolean("submit", false)
        // 参数与前置条件（长度、控制字符、零宽字符）与无障碍路径共用同一份判定：
        // 不合法时抛 IllegalArgumentException，上层映射成 VIRTUAL_SCREEN_INVALID。
        VirtualScreenTextPolicy.requireInjectable(text)
        if (VirtualScreenTextPolicy.classify(text).hasNonAscii) {
            throw RuntimeFailure(
                VirtualScreenTree.TEXT_INJECTION_REQUIRED,
                "这段文本含非 ASCII 字符，只能由主进程通过无障碍定向注入通道写入（设备 Shell 进程拿不到无障碍服务实例）。副屏会话本身正常。",
            )
        }
        check(targetVisible()) { "目标应用已离开副屏或系统无法确认其状态" }
        val attempts = ArrayList<VirtualScreenTextPolicy.TextAttempt>(3)
        val written = runCatching { command(VirtualScreenPolicy.inputArguments(p, id(), width, height)) }
        val wholeTextWritten: Boolean
        if (written.isSuccess) {
            wholeTextWritten = true
            attempts += VirtualScreenTextPolicy.TextAttempt(
                VirtualScreenTextPolicy.TextMethod.KEY_EVENTS,
                true,
                null,
                "设备 Shell input text",
            )
        } else {
            attempts += VirtualScreenTextPolicy.TextAttempt(
                VirtualScreenTextPolicy.TextMethod.KEY_EVENTS,
                false,
                VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
                "input text 未成功：${written.exceptionOrNull()?.message ?: "未知原因"}",
            )
            // 兜底：逐字符按键。映射不出来就如实报失败，绝不送半个字符。
            val codes = runCatching { keyEventCodes(text) }.getOrNull()
            if (codes == null) {
                attempts += VirtualScreenTextPolicy.TextAttempt(
                    VirtualScreenTextPolicy.TextMethod.KEY_EVENTS,
                    false,
                    VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
                    "这段文本里有 input keyevent 单独送不出的字符（例如需要 Shift 组合的大写字母或符号），兜底通道放弃，未写入任何字符",
                )
                return envelope(attempts, 0, succeeded = false, submitRequested = submit).toString()
            }
            var injected = 0
            var stepFailure: String? = null
            for (code in codes) {
                val stepped = runCatching {
                    command(listOf("/system/bin/input", "-d", id().toString(), "keyevent", code.toString()))
                }
                if (stepped.isFailure) {
                    stepFailure = stepped.exceptionOrNull()?.message ?: "未知原因"
                    break
                }
                injected++
            }
            if (stepFailure != null) {
                attempts += VirtualScreenTextPolicy.TextAttempt(
                    VirtualScreenTextPolicy.TextMethod.KEY_EVENTS,
                    false,
                    VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
                    "逐字符按键在第 ${injected + 1} 个字符失败：$stepFailure；输入框里可能只写进了前 $injected 个字符（$injected/${codes.size}）",
                )
                return envelope(attempts, injected, succeeded = false, submitRequested = submit).toString()
            }
            wholeTextWritten = true
            attempts += VirtualScreenTextPolicy.TextAttempt(
                VirtualScreenTextPolicy.TextMethod.KEY_EVENTS,
                true,
                null,
                "设备 Shell input keyevent 逐字符输入",
            )
        }
        // 只有真的写进去之后才提交；回车送不出去时把 submit 留成 false，不宣称「已发送」。
        val submitted = wholeTextWritten && submit && runCatching { command(submitArguments()) }.isSuccess
        return envelope(
            attempts,
            text.length,
            succeeded = wholeTextWritten,
            submitted = submitted,
            submitRequested = submit,
        ).toString()
    }

    /** 回退链结果信封：字段形状与主进程无障碍路径一致（`method`/`chars`/`submit`/`steps`/`code`）。 */
    private fun envelope(
        attempts: List<VirtualScreenTextPolicy.TextAttempt>,
        chars: Int,
        succeeded: Boolean,
        submitted: Boolean = false,
        submitRequested: Boolean = false,
    ): JSONObject = VirtualScreenTree.encodeKeyEvents(attempts, chars, succeeded, submitted, submitRequested)
        .put("sessionId", session)
        .put("displayId", id())

    /**
     * 逐字符按键兜底用的键码：字符 → `KeyCharacterMap.VIRTUAL_KEYBOARD` 映射，绝不自己拼 shell 字符串。
     *
     * 只接受「单个按键就能打出来」的字符：`input keyevent` 送不出 Shift 组合，因此需要组合键的大写字母
     * 与部分符号在这里如实返回 null（放弃兜底），而不是送出一个错的字符。真机映射待真机验证。
     */
    private fun keyEventCodes(text: String): List<Int>? {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val codes = ArrayList<Int>(text.length)
        for (char in text) {
            val events = map.getEvents(charArrayOf(char)) ?: return null
            val event = events.singleOrNull() ?: return null
            if (event.metaState != 0 || !event.isPrintingKey) return null
            codes += event.keyCode
        }
        return codes
    }

    /**
     * 提交用的回车参数：键码走 [VirtualScreenPolicy.inputArguments] 里唯一一份映射（ENTER → 66），
     * 副屏编号校验也一并复用，绝不会误发到主屏。
     */
    private fun submitArguments(): List<String> = VirtualScreenPolicy.inputArguments(
        JSONObject().put("action", "keyevent").put("key", "ENTER"),
        id(),
        width,
        height,
    )

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
            hardwareImage?.close(); hardwareImage = null
            frame?.recycle(); frame = null; frameAt = 0; cpuFrameAt = 0; cpuCaptureRequested = false; encodedFrame = null; pixels = null
            hardwareFrame?.close(); hardwareFrame = null
            // 会话结束后没有可信画面：恢复为「未渲染」，避免下一次截图沿用旧判定。
            frameBlank = true; frameSampledAt = 0; frameError = ""
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
    private class FrameState(val at: Long, val blank: Boolean, val sampledAt: Long, val fps: Double, val error: String)
}
