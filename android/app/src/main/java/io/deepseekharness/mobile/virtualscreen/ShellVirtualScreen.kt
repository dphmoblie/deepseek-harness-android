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
    // 会话规格原样留档：`restart` 要用**同一份规格**重建显示与会话（组件、分辨率、DPI），
    // 而设备 Shell 侧没有别的来源能拿到它们（宿主只在 start 时传进来一次）。
    private var targetComponent = ""
    private var virtualForeground: String? = null
    private var displayDpi = 0
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

    /**
     * 当前自动跟随策略（`off` / `pull_back` / `promote`），由 `config` 动作写入、在 [state] 里回显。
     * 宿主的健康循环正是靠回显值决定要不要周期性调用 `autoFollowTick`：不回显等于自动跟随永远不触发。
     */
    @Volatile private var autoFollow = VirtualScreenPolicy.AUTO_FOLLOW_OFF

    /**
     * 自动跟随的节流器（按「规则 + 目标」计时，默认两秒）。真机时钟传 `SystemClock.elapsedRealtime`，
     * 与单测共用同一份状态机逻辑。
     */
    private val followThrottle = VirtualScreenPolicy.FollowThrottle(
        now = { SystemClock.elapsedRealtime() },
    )
    // 最近若干帧的采集时刻环形缓冲（毫秒，elapsedRealtime），在 frameLock 内读写，实测帧率据此换算。
    private val frameStamps = LongArray(FRAME_SAMPLE_COUNT)
    private var frameStampCount = 0
    private var frameStampNext = 0
    private var frameFps = 0.0
    // 会话级触摸手势；随副屏会话创建与释放，停止会话时必须取消未结束的手势。
    private var gesture: VirtualScreenInjector.Gesture? = null
    // 最近一次触摸/手势注入的实测读数（沿用 frame* 系列的做法）：只记通道名、计数与异常类名，
    // 不含命令输出、路径或画面内容。注入耗时与事件数是判断「离散通道到底注入了多少」的唯一依据。
    private var injectionChannel = ""
    private var injectionAt = 0L
    private var injectionMillis = 0L
    private var injectionEvents = 0
    private var injectionMerged = 0
    private var injectionDropped = 0
    // 最近一次注入失败的原因标签（形如 discrete:move:IOException）；空串表示这次注入没有失败。
    private var injectionError = ""
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
            // 规格原样留档，供 restart 用同一份规格重建（见字段区注释）。
            targetComponent = component; displayDpi = dpi
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
            // 触摸直传：进程内通道可用则逐事件注入；按下事件没被接受时改走离散通道，逐事件 input motionevent。
            // 显示器要等下面才创建，所以手势只持有取编号的函数；真正注入时编号仍为 0 或 -1 就拒绝，绝不落到主屏。
            gesture = VirtualScreenInjector.Gesture({ id() }, w, h) { args ->
                command(listOf("/system/bin/input", "-d", id().toString()) + args)
            }
            // 下列标志属于 AOSP 的隐藏副屏能力，公开 SDK 的 IntDef 未列出；仅特权进程按系统版本使用。
            @android.annotation.SuppressLint("WrongConstant")
            // VirtualDisplayConfig 与 createVirtualDisplay(VirtualDisplayConfig) 都是 API 34 才有的，
            // 低版本必须走下面的旧重载，否则会在 API 31–33 真机上抛 NoClassDefFoundError。
            val created = if (Build.VERSION.SDK_INT >= 34) {
                // 请求刷新率按当前档位求值（limited → 60f，其余按档位名）。
                // 系统**可能忽略**这个请求（本机该虚拟屏的 supportedModes 只有 60.0 一档），
                // 所以请求值只是「意图」；真实值一律以创建后读回的 display.refreshRate 为准（见 state）。
                val refresh = VirtualScreenPolicy.requestedRefreshRate(frameMode)
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
                    // 本拍的失败原因：改前取硬件缓冲区失败是被静默吞掉的（预览只说「没画面」不说为什么）。
                    var failure = ""
                    if (Build.VERSION.SDK_INT >= 30) {
                        val next = try {
                            image.hardwareBuffer
                        } catch (error: Throwable) {
                            // 只记异常类名，不写命令输出、文件路径或画面内容。
                            failure = error.javaClass.simpleName
                            null
                        }
                        if (next != null) {
                            hardwareImage?.close()
                            hardwareImage = image
                            retained = true
                            hardwareFrame?.close()
                            hardwareFrame = next
                        } else if (failure.isEmpty()) {
                            failure = "HardwareBufferUnavailable"
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
                    // 本拍采集成功：清掉上一拍的异常，但本拍自己的硬件缓冲区失败原因要留着上报，
                    // 直到下一拍真正拿到缓冲区才自然清空——取硬件帧失败不再被静默吞掉。
                    frameError = failure
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
        // 副屏最前台要起一个 dumpsys 进程，只查一次，供下面两个字段共用。
        val virtualForeground = this.virtualForeground
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
            // 触摸通道：stream 为进程内逐事件直传，discrete 为逐事件走 input motionevent（合并采样点、丢弃过期移动）。
            .put("touchChannel", VirtualScreenInjector.channel())
            // 注入诊断：通道、上次注入的时刻/耗时/落地事件数/合并与丢弃数，以及失败原因标签（空串=没失败）。
            .put("injectionChannel", injectionChannel)
            .put("injectionAtElapsedMs", injectionAt)
            .put("injectionMillis", injectionMillis)
            .put("injectionEvents", injectionEvents)
            .put("injectionMerged", injectionMerged)
            .put("injectionDropped", injectionDropped)
            .put("injectionError", injectionError)
            .put("uiTreeSupported", false)
            .put("independentFocusRequested", independentFocus)
            // 自动跟随策略回显：宿主健康循环读它决定要不要周期性调用 autoFollowTick。
            .put(VirtualScreenPolicy.AUTO_FOLLOW_FIELD, autoFollow)
            // 副屏最前台（数据来源：`dumpsys activity activities` 按本会话 displayId 分段取 topResumedActivity）。
            // 会话不在或解析不到时留空串：**不能**用主屏前台顶替，否则调用方会以为目标已经在副屏上。
            .put(VirtualScreenPolicy.VIRTUAL_FOREGROUND_PACKAGE_FIELD, virtualForeground?.substringBefore('/') ?: "")
            .put(VirtualScreenPolicy.VIRTUAL_FOREGROUND_ACTIVITY_FIELD, virtualForeground ?: "")
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
            // 生命周期动作：start/restart/reconnect。语义判定在 VirtualScreenPolicy.lifecycleOutcome 里（纯函数），
            // 这里只负责把它翻译成可区分的返回字段。做不到的动作如实返回带码的失败，绝不假装成功。
            in VirtualScreenPolicy.LIFECYCLE_ACTIONS -> return lifecycleAction(p.getString("action"))
            // 自动跟随：由宿主健康循环周期性调用（不是 AI 直接调的动作），一次最多做一个动作，带节流且幂等。
            VirtualScreenPolicy.AUTO_FOLLOW_TICK_ACTION -> return autoFollowTick(p)
            // 预览限帧模式：校验通过后立即生效，采集间隔按新模式求值。
            // autoFollow 是同一条 config 消息里的第二项（宿主启动时发的就是 VirtualScreenPreferences.configuration()），
            // 缺省不动：老调用方没带这个字段时不能把用户已选的策略悄悄改掉。
            "config" -> {
                val mode = p.getString(VirtualScreenPolicy.PREVIEW_FIELD)
                VirtualScreenPolicy.frameInterval(mode)
                frameMode = mode
                if (!p.isNull(VirtualScreenPolicy.AUTO_FOLLOW_FIELD)) {
                    autoFollow = VirtualScreenPolicy.autoFollow(p.getString(VirtualScreenPolicy.AUTO_FOLLOW_FIELD))
                }
            }
            // 目标应用热切换：沿用同一个虚拟显示器与采集器，不释放也不重建。
            // 三个可选参数：confirm_budget_ms（确认窗口，默认 3 秒、夹取 500..15000）、
            // prewarm（先冷启动一次目标再确认）、rollback（失败时把原目标拉回副屏）。
            "target" -> {
                // 目标入口有两种字段形状（组件 / 包名，见 targetRequestField）：这里是设备侧唯一做解析的地方，
                // 宿主设置页与 AI 工具面因此可以各说各的写法，而不必让上层先把它翻译成组件。
                val request = VirtualScreenPolicy.targetRequestField(p)
                val previous = targetComponent.takeIf { it.isNotEmpty() }
                val budget = VirtualScreenPolicy.confirmBudgetMillis(p)
                val prewarm = p.optBoolean(VirtualScreenPolicy.PREWARM_FIELD, false)
                val rollback = p.optBoolean(VirtualScreenPolicy.ROLLBACK_FIELD, false)
                var warmUpError = ""
                var component = request
                try {
                    // 只给包名时先解析启动入口，再走既有的启动与确认。解析失败放在 try 里统一处理：
                    // 「这套入口不成立」与「am start 被拒」同属 NOT_ACCEPTED，调用方该换入口而不是重试。
                    if (!component.contains('/')) {
                        component = resolveComponent(component) ?: throw VirtualScreenPolicy.launchUnresolvedFailure(component)
                    }
                    if (prewarm) warmUpError = warmUp(component)
                    launchOnDisplay(listOf("-n", component), component, component.substringBefore('/'), budget)
                    targetPackage = component.substringBefore('/')
                    targetComponent = component
                } catch (error: Exception) {
                    // 失败时把「哪一类失败 + 为什么」分开放：错误码回答哪一类（会话失效 / 切换超时 / 启动被拒），
                    // reason 回答为什么（NOT_ACCEPTED / NOT_FOREGROUND / SESSION_DEAD），调用方据此决定重试还是换入口。
                    // reason 进 message 前缀，是因为 RuntimeFailure 只有 code 与 message 两个字段（见 runtime 包），
                    // 没有地方挂结构化字段；需要区分的话调用方读 "REASON: 详情" 的前缀即可。
                    val reason = VirtualScreenPolicy.switchFailureReason(error)
                    val code = VirtualScreenPolicy.errorCode(error)
                    val detail = error.message?.takeIf { it.isNotBlank() } ?: "目标应用未能在副屏上进入前台"
                    // 回滚：把原目标拉回副屏。回滚**自己也会失败**，两种结果都要如实说，
                    // 不能让调用方以为「请求了回滚就等于回到了原目标」。
                    var suffix = ""
                    if (rollback && previous != null) {
                        if (rollbackTarget(previous, budget)) {
                            // 回滚成功时把原目标写回 targetPackage：不写会让 state/snapshot 继续指向一个其实不在副屏上的目标。
                            targetPackage = previous.substringBefore('/')
                            targetComponent = previous
                            suffix = "；已回滚到原目标 $previous"
                        } else {
                            suffix = "；回滚到原目标 $previous 也没能确认上屏"
                        }
                    }
                    throw VirtualScreenPolicy.failure(code, "${VirtualScreenPolicy.SWITCH_REASON_FIELD}=$reason: $detail$suffix")
                }
                return JSONObject()
                    .put("sessionId", session).put("displayId", id())
                    .put("targetPackage", targetPackage).put("component", component)
                    .put("confirmBudgetMillis", budget)
                    .put("prewarm", prewarm).put("rollback", rollback)
                    // 预热失败不影响这次切换的结果，但如实回报，免得「预热过了」被当成事实。
                    .put("prewarmError", warmUpError)
                    .toString()
            }
            // 触摸直传：逐事件注入，不逐事件跑 targetVisible()（它要起 dumpsys 进程，会毁掉直传帧率）。
            "touch" -> {
                val touch = VirtualScreenInjector.request(p, width, height)
                if (touch.phase == "down") check(targetVisible()) { "目标应用已离开副屏或系统无法确认其状态" }
                val gesture = checkNotNull(gesture) { "副屏会话尚未就绪" }
                val result = gesture.handle(touch)
                // 失败只可能出在抬起阶段（离散通道那时才逐事件跑 input 命令）：照实报错并留下诊断，
                // 不能只给一个 streamed=false 让调用方以为「大概是没做吧」——改前正是这样静默过去的。
                recordInjection(result)
                val failure = result.optString("failure")
                if (failure.isNotEmpty()) {
                    throw RuntimeFailure(
                        VirtualScreenPolicy.INJECTION_FAILED_CODE,
                        "副屏触摸注入未完成：$failure（副屏会话本身正常，可稍后重试）",
                    )
                }
                return JSONObject()
                    .put("sessionId", session).put("displayId", id())
                    .put("touchChannel", VirtualScreenInjector.channel()).put("touch", result)
                    .toString()
            }
            // AI 手势：整段路径交给注入器——进程内通道按约 16 毫秒步进直传；不可用时走离散通道逐事件
            // 注入 input motionevent（按合并/丢弃规则封顶事件数）。失败在返回里标 failure/aborted，
            // 这里翻译成注入错误码：没落地就绝不报成功。
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
                recordInjection(result)
                // 离散通道的失败：`failure` + `failedAt` 说明是按下/移动/抬起哪个事件没落地、已落地多少、
                // 丢弃了多少过期移动；改前这里只发一次 input swipe 就返回成功，画面不动却报成功。
                val failure = result.optString("failure")
                if (failure.isNotEmpty()) {
                    throw RuntimeFailure(VirtualScreenPolicy.INJECTION_FAILED_CODE, "副屏手势注入未完成：$failure")
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
                if (launched != null) {
                    targetPackage = launched.substringBefore('/')
                    targetComponent = launched
                }
                return JSONObject()
                    .put("sessionId", session).put("displayId", id())
                    .put("launched", launched?.substringBefore('/') ?: "").put("targetPackage", targetPackage)
                    .toString()
            }
            // 跟随：目标应用内部跳转会把新 Activity 落到主屏（display 0），把主屏最前台的可跟随包拉回副屏。
            "follow" -> {
                val dump = command(listOf("/system/bin/dumpsys", "activity", "activities"))
                val foreground = VirtualScreenPolicy.resumedComponent(dump, 0)
                val candidate = foreground?.substringBefore('/')
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
                val component = foreground
                launchOnDisplay(listOf("-n", component), candidate, candidate)
                targetPackage = candidate
                targetComponent = component
                return JSONObject()
                    .put("sessionId", session).put("displayId", id())
                    .put("followed", true).put("packageName", candidate).put("component", component)
                    .toString()
            }
            else -> error("不支持的副屏操作")
        }
        return state().toString()
    }

    /**
     * 抓一张 PNG 截图，供 `virtualScreenSnapshot`（事务 8）回传。
     *
     * **这条路径刻意不再 `@Synchronized`**：它要等应用侧发起一次 CPU 取帧（最多 800 毫秒），而
     * `Object.wait()` 只释放 [frameLock]、不释放实例监视器。改前等待期间 `action`（输入注入）、
     * `frame`（预览取硬件帧，5–16 毫秒一次）与 `state` 全都要排在这个等待后面——应用侧按预览帧率
     * 轮询事务 9 时，输入注入就被一次次推到队列尾，用户看到的就是「通过悬浮窗操作副屏很慢」。
     * 现在只有会话校验与目标判定这一小段拿实例监视器，等待只在 [awaitFrame] 里进行；锁序仍是
     * 「实例监视器 → frameLock」，且持 [frameLock] 时绝不回头取实例监视器。
     */
    fun snapshot(sessionId: String): ParcelFileDescriptor {
        synchronized(this) {
            requireSession(sessionId)
            // 错误码细分（交付项 2）：这两条此前都是 `check(...)` → IllegalStateException，
            // 顺着 errorCode 的兜底统一变成「副屏不可用」，调用方只能盲目重试。现在分开：
            // - 目标已离开副屏 → TARGET_LEFT（要先把目标切回来，重试没有用）；
            // - 画面还没就绪/已过期 → STALE_FRAME（原地等几百毫秒重试即可，由 [awaitFrame] 抛）。
            // 仍然无法区分的只有一种：`dumpsys` 本身跑不起来（命令异常会原样向上抛，兜底码不变）。
            if (!targetVisible()) {
                throw VirtualScreenPolicy.failure(VirtualScreenPolicy.TARGET_LEFT_CODE, VirtualScreenPolicy.TARGET_LEFT_MESSAGE)
            }
        }
        // 等待与空白帧的取舍全在 [awaitFrame]：那里只持 frameLock，不占实例监视器。
        val bytes = awaitFrame()
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

    /**
     * 请求一次 CPU 取帧并等它就绪（最多 800 毫秒），返回要回传的 PNG 字节。
     *
     * 只持 [frameLock]：`wait()` 会释放它，采集线程照常写入新帧，实例监视器完全不参与，
     * 因此输入注入与预览取硬件帧不会被这段等待挡住（问题 A 的修法就在这一行边界上）。
     * 等待期间会话被关闭时 [close] 会在 [frameLock] 内清空 frame/frameAt，下面照旧按 STALE_FRAME 抛。
     */
    private fun awaitFrame(): ByteArray = synchronized(frameLock) {
        val before = cpuFrameAt
        cpuCaptureRequested = true
        val deadline = SystemClock.elapsedRealtime() + 800L
        while (frame == null || cpuFrameAt <= before) {
            val remain = deadline - SystemClock.elapsedRealtime()
            if (remain <= 0) break
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (frameLock as java.lang.Object).wait(minOf(40L, remain))
        }
        // 仍然按 STALE_FRAME 抛：这个码是「稍后重试」的语义，不该被上层兜底成「副屏不可用」。
        if (frameAt <= 0 || frame == null) {
            throw VirtualScreenPolicy.failure(VirtualScreenPolicy.STALE_FRAME_CODE, VirtualScreenPolicy.STALE_FRAME_MESSAGE)
        }
        val current = checkNotNull(frame)
        // 静止页面复用最后一帧，不能把“画面未变化”误判成连接失效；
        // 但空白帧绝不写入缓存：每次按当前 Bitmap 重新编码，让调用方依据 frameBlank 决定是否重试，
        // 而不是拿一张空白画面的缓存冒充新画面。
        if (frameBlank) encodeFrame(current) else encodedFrame ?: encodeFrame(current).also { encodedFrame = it }
    }

    /** 返回最近一帧的硬件缓冲区，供页面预览直接交给 GPU；调用方完成使用后必须关闭句柄。 */
    @Synchronized fun frame(sessionId: String): HardwareBuffer {
        requireSession(sessionId)
        check(Build.VERSION.SDK_INT >= 30) { "硬件帧预览需要 Android 11 或更高版本" }
        // 预览路径刻意**不**查 targetVisible()：那要起一个 dumpsys 进程，每帧都查会把预览帧率打下来。
        // 也刻意**不**套 frameStale() 的两秒窗口：静止页面本来就不再产生新帧，
        // 按「帧老于两秒」判会把这帧正常的静止画面误报成过期。
        // 这里只区分「还没有任何一帧」——那才是 STALE_FRAME（调用方下一帧重试即可），而不是「副屏不可用」。
        if (frameAt <= 0 || hardwareFrame == null) {
            throw VirtualScreenPolicy.failure(VirtualScreenPolicy.STALE_FRAME_CODE, VirtualScreenPolicy.STALE_FRAME_MESSAGE)
        }
        return checkNotNull(hardwareFrame)
    }

    /**
     * 记下这次注入的实测读数（通道、耗时、落地事件数、合并与丢弃数、失败原因标签），供 [state] 上报。
     *
     * 只记通道名、计数与异常类名：失败标签形如 `discrete:move:Rejected`，**不含**命令输出、文件路径或密钥。
     * 调用方是 [action]（已持实例监视器），所以这里不再加锁。
     */
    private fun recordInjection(result: JSONObject, at: Long = SystemClock.elapsedRealtime()) {
        injectionChannel = result.optString("channel", VirtualScreenInjector.channel())
        injectionAt = at
        injectionMillis = result.optLong("millis", 0L)
        injectionEvents = result.optInt("events", 0)
        injectionMerged = result.optInt("merged", 0)
        injectionDropped = result.optInt("dropped", 0)
        val failure = result.optString("failure")
        injectionError = if (failure.isEmpty()) "" else {
            val phase = result.optString("failedAt").ifEmpty { result.optString("phase", "unknown") }
            val kind = result.optString("failureKind").ifEmpty { "Failed" }
            "$injectionChannel:$phase:$kind"
        }
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
    private fun targetVisible(packageName: String = targetPackage): Boolean {
        val dump = command(listOf("/system/bin/dumpsys", "activity", "activities"))
        virtualForeground = VirtualScreenPolicy.resumedComponent(dump, id())
        return VirtualScreenPolicy.targetResumed(dump, id(), packageName)
    }

    /** 指定显示器上最前台的组件名（0 为主屏，会话副屏用 [id]）；解析不到返回 null。 */
    private fun foregroundComponent(displayId: Int): String? {
        val component = VirtualScreenPolicy.resumedComponent(
            command(listOf("/system/bin/dumpsys", "activity", "activities")), displayId,
        )
        if (displayId == id()) virtualForeground = component
        return component
    }

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
     * - 返回确认到的前台组件，确保目标包名与重启入口保持一致；
     *   URI 启动同样使用观测到的真实前台组件。
     *
     * [budgetMillis] 是**确认窗口**（`target` 动作的 `confirm_budget_ms`，默认 3 秒、夹取 500..15000）。
     * `launch`/`follow` 沿用默认值，只有显式带预算的 `target` 会把它传进来：
     * 给得越短，「目标其实慢了一点点才上屏」就越容易被判成超时，但调用方的等待也更短。
     */
    private fun launchOnDisplay(
        arguments: List<String>,
        label: String,
        expected: String?,
        budgetMillis: Long = VirtualScreenPolicy.TARGET_SWITCH_TIMEOUT_MILLIS,
    ): String? {
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
            timeoutMillis = budgetMillis,
            elapsedMillis = { SystemClock.elapsedRealtime() },
            sleepMillis = { SystemClock.sleep(it) },
            visible = visible,
        )
        if (!switched) throw VirtualScreenPolicy.targetSwitchFailure()
        return virtualForeground?.takeIf { confirmed == null || it.substringBefore('/') == confirmed }
            ?: throw VirtualScreenPolicy.targetSwitchFailure()
    }

    /**
     * `text` 动作在**设备 Shell 侧**的实现。这一侧只负责「不依赖无障碍也能送进去的那一段文字」：
     *
     * 1. 纯 ASCII：`input text` 直接写（它内部就是合成键事件，比逐字符更稳）；
     * 2. `input text` 没成：用 [KeyCharacterMap] 把字符映射成键码，逐字符 `input keyevent` 兜底——
     *    **绝不自己拼 shell 字符串**，映射不出来（需要 Shift 组合的大写字母、部分符号）就如实失败；
     * 3. 含非 ASCII：`input text` / `input keyevent` 都送不进去（前者只吃可打印 ASCII，后者要靠无 Shift 的
     *    单键映射）。改走 [nonAsciiTextAction] 的剪贴板 + 粘贴键通道（同样不依赖无障碍）；
     *    通道没打通时**如实失败**，回 `VIRTUAL_SCREEN_TEXT_UNSUPPORTED` 并按探测结论给可操作提示。
     * 4. `submit=true`：文字**确实写进去之后**才按一次回车（键码复用策略层那唯一一份映射），失败不按。
     */
    private fun textAction(p: JSONObject): String {
        val text = p.optString("text", "")
        val submit = p.optBoolean("submit", false)
        // 参数与前置条件（长度、控制字符、零宽字符）与无障碍路径共用同一份判定：
        // 不合法时抛 IllegalArgumentException，上层映射成 VIRTUAL_SCREEN_INVALID。
        VirtualScreenTextPolicy.requireInjectable(text)
        if (VirtualScreenTextPolicy.classify(text).hasNonAscii) return nonAsciiTextAction(text, submit)
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
     * 非 ASCII 文本在设备 Shell 侧的通道：**剪贴板 + 粘贴键**（这条路上不需要无障碍服务）。
     *
     * 为什么只有这一条：「input text」只接受可打印 ASCII；无障碍定向写入（`ACTION_SET_TEXT`/`ACTION_PASTE`）
     * 需要无障碍服务的实例，而那是主进程的进程内静态引用，Shizuku 用户服务进程拿不到。
     * 于是能试的是：`cmd clipboard set-primary-clip <文本>` 把文本写进系统剪贴板，
     * 再用 `input -d <副屏> keyevent 279`（KEYCODE_PASTE）让副屏上**已聚焦**的输入框自己粘贴。
     *
     * 诚实边界（都写进返回值，不让调用方猜）：
     * - 写入之后**立刻读回核对**（`cmd clipboard get-primary-clip` 的前 16 个字符），对不上就不算通道可用；
     * - 读得到 ≠ 粘得上：设备 Shell 侧读不到副屏输入框的内容，所以成功回执里带 `verified=false`
     *   与 [VirtualScreenPolicy.CLIPBOARD_VERIFY_NOTE]，粘贴到底落没落进目标字段由调用方用截图/节点树复核；
     * - 通道没打通（命令不存在/被拒/读回对不上）或粘贴键发不出去时**如实失败**，
     *   抛 `VIRTUAL_SCREEN_TEXT_UNSUPPORTED`，并按探测结论给一句可操作提示；
     * - 这个动作会改写系统剪贴板（与主进程无障碍链的粘贴方案同源），失败时**不回滚**剪贴板内容。
     */
    private fun nonAsciiTextAction(text: String, submit: Boolean): String {
        val written = runCatching {
            command(listOf("/system/bin/cmd", "clipboard", "set-primary-clip", text))
        }
        val readback = if (written.isSuccess) {
            runCatching { command(listOf("/system/bin/cmd", "clipboard", "get-primary-clip")) }
        } else {
            null
        }
        val probe = VirtualScreenPolicy.clipboardProbe(
            succeeded = readback?.isSuccess == true,
            output = readback?.getOrNull().orEmpty(),
            failure = written.exceptionOrNull()?.message.orEmpty(),
            expected = text,
        )
        if (probe != VirtualScreenPolicy.ClipboardProbe.AVAILABLE) {
            val (code, message) = VirtualScreenPolicy.nonAsciiTextFailure(probe)
            throw RuntimeFailure(code, message)
        }
        val pasted = runCatching {
            command(listOf("/system/bin/input", "-d", id().toString(), "keyevent", CLIPBOARD_PASTE_KEYCODE.toString()))
        }
        if (pasted.isFailure) {
            // 文本已经在剪贴板里了，所以这里给的是「手动粘贴」的提示，而不是「换个通道」。
            val detail = pasted.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "未知原因"
            throw RuntimeFailure(
                VirtualScreenTextPolicy.FailureCode.TEXT_UNSUPPORTED,
                "${VirtualScreenPolicy.CLIPBOARD_HINT_PASTE_FAILED}（发送粘贴键失败：$detail）",
            )
        }
        // 提交键与 ASCII 路径共用同一份映射与同一条「只有真的送出去才算 submitted」的规矩。
        val submitted = submit && runCatching { command(submitArguments()) }.isSuccess
        val attempt = VirtualScreenTextPolicy.TextAttempt(
            VirtualScreenTextPolicy.TextMethod.PASTE,
            true,
            null,
            "已把文本写入系统剪贴板并在副屏上发送 KEYCODE_PASTE（$CLIPBOARD_PASTE_KEYCODE）",
        )
        return envelope(listOf(attempt), text.length, succeeded = true, submitted = submitted, submitRequested = submit)
            // encodeKeyEvents 固定写 method=KEY_EVENTS（它原本只有按键这一种场景），这里覆写成真实通道。
            .put("method", VirtualScreenTextPolicy.TextMethod.PASTE.name)
            .put("verified", false)
            .put("note", VirtualScreenPolicy.CLIPBOARD_VERIFY_NOTE)
            .toString()
    }

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

    /**
     * 生命周期动作（`start` / `restart` / `reconnect`）在设备 Shell 侧的真实语义。
     *
     * 判定交给 [VirtualScreenPolicy.lifecycleOutcome]（纯函数、有单测），这里只把结论变成事实：
     * - `start`：**只观察**，不新建会话——规格（目标应用、分辨率、DPI、调用方 binder）只有宿主手里有，
     *   设备 Shell 侧凭空造一个会话等于替用户决定目标应用；
     * - `restart`：用**留档的同一份规格**重建显示与会话（[restartSession]），并保住原会话编号；
     * - `reconnect`：把调用方 binder 重新挂上（[rebindOwner]），显示器还在就真能绑上，绑不上就抛异常。
     *
     * 返回体固定带 `action`/`ok`/`state`/`sessionId`/`displayId`/`reason`，
     * 让调用方一眼看出「哪一类结果」，不必靠 message 猜。
     */
    private fun lifecycleAction(action: String): String {
        val outcome = VirtualScreenPolicy.lifecycleOutcome(action, id(), session, session)
        // 判定说不该继续时，异常里的码就是 policy 给的那个（SESSION_DEAD / RESTART_UNSUPPORTED /
        // RECONNECT_UNSUPPORTED），**不**换成「副屏不可用」——这三种情况的处置方式完全不同。
        if (!outcome.startable) throw checkNotNull(outcome.failure)
        val previous = session
        when (action) {
            "restart" -> restartSession()
            "reconnect" -> rebindOwner()
        }
        return JSONObject()
            .put("action", action)
            .put("ok", true)
            .put("state", outcome.state)
            .put("sessionId", session)
            .put("displayId", id())
            // restart 会把会话编号恢复成原来那一个（宿主的健康循环按编号核对会话，换号会被当成会话失效并停掉整条链路）。
            .put("previousSessionId", previous)
            .put("targetPackage", targetPackage)
            .put("reason", "")
            .toString()
    }

    /**
     * `restart`：释放当前显示与采集器，再按**同一份规格**重建。
     *
     * 规格来自 [start] 时留档的 [targetComponent] / [width] / [height] / [displayDpi]，
     * 调用方 binder 也在手上，所以「重建」是真做得到的，不需要骗调用方去设置页重开。
     *
     * 两个关键点：
     * - 重建后把会话编号**恢复成原来那一个**：宿主 `VirtualScreenService.health` 每 800 毫秒核对
     *   `state.sessionId == 自己缓存的编号`，换了编号会被判成「副屏会话失效」并把整条副屏链路停掉；
     * - 恢复编号之后必须重挂一次死亡回调：`start` 里那个闭包捕获的是新建时生成的 UUID，
     *   编号被改回去后它永远不等，调用方一死就没人收尾（[rebindOwner]）。
     */
    private fun restartSession() {
        val client = owner ?: throw VirtualScreenPolicy.failure(VirtualScreenPolicy.SESSION_DEAD_CODE, VirtualScreenPolicy.SESSION_DEAD_MESSAGE)
        if (targetComponent.isEmpty() || width <= 0 || height <= 0 || displayDpi <= 0) {
            throw VirtualScreenPolicy.failure(
                VirtualScreenPolicy.SESSION_DEAD_CODE,
                "副屏会话的规格已经不在了（目标应用 / 分辨率 / DPI），无法按原规格重建；请重新开始副屏会话",
            )
        }
        val previous = session
        val component = targetComponent
        val w = width
        val h = height
        val dpi = displayDpi
        close()
        start(component, w, h, dpi, client)
        session = previous
        rebindOwner(client)
    }

    /**
     * `reconnect`：重新绑定调用方 binder —— 解掉旧的生命周期回调，按**当前**会话编号重新注册一次。
     *
     * 这是「Shizuku 连接/会话掉线后重新绑定」在设备 Shell 侧**真能做到**的那一部分：
     * 调用方 binder 还活着就能绑上；已经死了 [IBinder.linkToDeath] 会抛 DeadObjectException，
     * 调用方看到的是真实失败，而不是一个「成功」的空转。
     */
    private fun rebindOwner() {
        val client = owner ?: throw VirtualScreenPolicy.failure(VirtualScreenPolicy.SESSION_DEAD_CODE, VirtualScreenPolicy.SESSION_DEAD_MESSAGE)
        rebindOwner(client)
    }

    private fun rebindOwner(client: IBinder) {
        death?.let { recipient -> runCatching { client.unlinkToDeath(recipient, 0) } }
        val thisSession = session
        val recipient = IBinder.DeathRecipient { synchronized(this) { if (session == thisSession) close() } }
        death = recipient
        owner = client
        client.linkToDeath(recipient, 0)
    }

    /**
     * 自动跟随的一次 tick（宿主健康循环约 800 毫秒调一次）。
     *
     * 判定全在 [VirtualScreenPolicy.autoFollowDecision]（纯函数、单测覆盖），这里只做四件事：
     * 读主屏与副屏的最前台、把判定拿去节流、动手、把**真实结果**回报给调用方（失败不吞异常）。
     * 幂等与节流都在纯函数/节流器里：判定为不动手的那一 tick 不占用节流窗口（[FollowThrottle.attempt] 的约定）。
     */
    private fun autoFollowTick(p: JSONObject): String {
        val policy = VirtualScreenPolicy.autoFollow(autoFollow)
        val selfPackage = p.optString(VirtualScreenPolicy.SELF_PACKAGE_FIELD, "")
        // 健康循环低频刷新前台缓存；逐帧 state() 只读取缓存，不启动系统进程。
        val dump = command(listOf("/system/bin/dumpsys", "activity", "activities"))
        virtualForeground = VirtualScreenPolicy.resumedComponent(dump, id())
        if (policy == VirtualScreenPolicy.AUTO_FOLLOW_OFF) {
            return tickResult(policy, "", "自动跟随已关闭", applied = false)
        }
        val decision = VirtualScreenPolicy.autoFollowDecision(
            policy = policy,
            sessionAlive = VirtualScreenPolicy.sessionAlive(id(), session, session),
            sessionTarget = targetComponent,
            virtualForeground = virtualForeground,
            mainForeground = VirtualScreenPolicy.resumedComponent(dump, 0),
            selfPackage = selfPackage,
            inputMethods = inputMethods(),
            // 桌面判定三级兜底与 `follow` 动作保持同一份来源，免得两处对「桌面是谁」给出不同答案。
            homePackage = VirtualScreenPolicy.homePackage(dump) ?: homeProcessFromActivity() ?: homeComponent(),
        )
        if (decision is VirtualScreenPolicy.AutoFollowDecision.Skipped) {
            return tickResult(policy, "", decision.reason, applied = false)
        }
        val applied = decision as VirtualScreenPolicy.AutoFollowDecision.Applied
        if (!followThrottle.attempt(applied.rule, applied.target)) {
            return tickResult(policy, applied.rule, "${applied.reason}；两秒内已经对同一个目标动作过，这次跳过", applied = false)
        }
        val component = resolveComponent(applied.target)
        if (component == null) {
            return tickResult(policy, applied.rule, "${applied.reason}；但解析不到 $applied.target 的启动入口，未动作", applied = false)
        }
        return runCatching {
            launchOnDisplay(listOf("-n", component), component, applied.target)
            // 会话目标跟着换：不换的话下一次 tick 还会把旧目标当会话目标，来回抢前台。
            targetPackage = applied.target
            targetComponent = component
            tickResult(policy, applied.rule, applied.reason, applied = true)
        }.getOrElse { error ->
            // 动作失败也要如实回报（含错误码）：调用方据此提示用户，而不是以为自动跟随在正常工作。
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            tickResult(policy, applied.rule, "${applied.reason}；执行失败（${VirtualScreenPolicy.errorCode(error)}）：$detail", applied = false)
        }
    }

    /** 自动跟随 tick 的回执：策略、是否真的动了手、用了哪条规则、为什么（不动手时就是被哪条门槛拦下）。 */
    private fun tickResult(policy: String, rule: String, reason: String, applied: Boolean): String = JSONObject()
        .put("sessionId", session)
        .put("displayId", id())
        .put(VirtualScreenPolicy.AUTO_FOLLOW_FIELD, policy)
        .put("applied", applied)
        .put("rule", rule)
        .put(VirtualScreenPolicy.SWITCH_REASON_FIELD, reason)
        .toString()

    /**
     * `prewarm`：把目标的冷启动**提前到确认窗口之前**。
     *
     * 实现就是提前做一次真正的副屏启动（`am start -W --display <副屏> -n <组件>`）：
     * `-W` 会一直等到该 Activity 真正显示出来，于是「进程冷启动」这段最长的耗时落在这一次调用里，
     * 而不是吃掉 `target` 那点确认窗口。刻意**不**在主屏上先起一次：那会让目标应用在主屏上闪一下，
     * 用户看到的「副屏目标」和实际前台会对不上。
     *
     * 返回空串＝这次预热（也就是这次提前启动）成功；否则返回一句可读原因——预热失败**不抛异常**，
     * 因为「要不要继续切」由后面的正常流程决定，调用方只需要知道预热没成（回执里的 `prewarmError`）。
     */
    private fun warmUp(component: String): String = runCatching {
        command(listOf("/system/bin/am", "start", "-W", "--display", id().toString(), "-n", component))
        ""
    }.getOrElse { error -> error.message.orEmpty().take(120).ifEmpty { "预热命令未成功执行" } }

    /**
     * `rollback`：把原目标拉回副屏。成功返回 true，失败返回 false —— **不抛异常**：
     * 调用方此刻已经在处理一次切换失败，回滚再抛会把「哪一类失败」的读数盖掉。
     * 确认窗口复用同一个预算，不额外放宽。
     */
    private fun rollbackTarget(component: String, budgetMillis: Long): Boolean = runCatching {
        launchOnDisplay(listOf("-n", component), component, component.substringBefore('/'), budgetMillis)
        true
    }.getOrElse { false }

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
        // 规格也要清：留着会让「会话已经结束」与「还能按原规格 restart」看起来是同一件事。
        targetComponent = ""; displayDpi = 0; virtualForeground = null
    }

    private companion object {
        /** 实测帧率使用的采样帧数：只统计最近这么多帧，避免很久以前的间隔拉扁当前帧率。 */
        const val FRAME_SAMPLE_COUNT = 24

        /**
         * `KeyEvent.KEYCODE_PASTE`（API 11 起就有）。Android 的 `input keyevent` 只接受数字键码，
         * 所以这里直接写数值，不走 `KeyEvent.KEYCODE_PASTE`（那是 android.view 的常量，值也是 279）。
         */
        const val CLIPBOARD_PASTE_KEYCODE = 279
    }

    /** 状态里画面相关字段的一次性快照，保证同一份状态描述同一帧。 */
    private class FrameState(val at: Long, val blank: Boolean, val sampledAt: Long, val fps: Double, val error: String)
}
