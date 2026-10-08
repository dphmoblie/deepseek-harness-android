package io.deepseekharness.mobile.shizuku

/**
 * Shizuku 状态读取闸门：同一时刻只允许一次读取在途。
 *
 * 背景是真机反馈「Shizuku 更新后连不上、点打开会卡」：[ShizukuRuntime.state] 里的每一步
 * （`resolveContentProvider`、`pingBinder`、`checkSelfPermission`、
 * `shouldShowRequestPermissionRationale`、`getVersion`）最终都会落到 Shizuku 服务端的
 * binder 调用上，而 binder 调用既不返回也不超时——服务端卡住时它会一直挂着。
 * 前端每 5 秒轮询一次状态，每次读取都占住一个执行线程，于是卡住的读取会把桥的线程池
 * 逐个占满，整个运行时跟着僵住。
 *
 * 这里的取舍：
 *  - 已有读取在途时**不再发起新的 binder 调用**：直接返回上一次成功读取的结果；
 *  - 连一次成功读取都还没有时返回 [fallback]（只查「Shizuku 是否安装」，完全不碰 binder），
 *    界面此时按「未就绪」显示，用户仍能点「打开 Shizuku」去重启服务；
 *  - 读取抛异常时照旧抛出，由调用方决定怎么降级；
 *  - 服务端换了一茬（binder 到达或断开）时由调用方 [invalidate]：卡死的读取永远不会返回，
 *    不作废的话闸门会一直以为「有人在读」，用户重启 Shizuku 服务后界面也永远停在兜底状态。
 *
 * 线程安全：`reading`、`generation` 与 `cached` 由 [lock] 保护；[read] 与 [fallback] 都在锁外
 * 执行，避免把「可能卡住的调用」和锁绑在一起（否则第二个调用会连锁一起排队）。
 */
internal class ShizukuStateCache(
    private val read: () -> ShizukuState,
    private val fallback: () -> ShizukuState,
) {
    private val lock = Any()
    private var reading = false

    /**
     * 每作废一次就自增。在途读取记下启动时的代号，回来时先对代号：代号变了说明它读的是
     * 上一茬服务端，既不许写缓存、也不许动新一代的在途标记。
     */
    private var generation = 0L
    private var cached: ShizukuState? = null

    /** 桥线程与轮询走的路径：永远不排队在一个可能卡住的读取后面。 */
    fun current(): ShizukuState {
        var previous: ShizukuState? = null
        var startRead = false
        var startedAt = 0L
        synchronized(lock) {
            if (reading) {
                previous = cached
            } else {
                reading = true
                startedAt = generation
                startRead = true
            }
        }
        // 上一次结果与兜底都在锁外算：兜底虽然只查「是否安装」，也仍是一次 PackageManager 调用，
        // 把它放进锁里就等于让后面所有调用一起排队（正是这个类要避免的事）。
        if (!startRead) return previous ?: fallback()
        return try {
            val fresh = read()
            synchronized(lock) {
                if (generation == startedAt) {
                    cached = fresh
                    reading = false
                }
            }
            fresh
        } catch (error: Throwable) {
            // 失败也必须放开闸门：否则一次异常会永久堵死后续所有状态读取。
            // 但同样只在自己这一代里放开——若期间已被 [invalidate] 作废，闸门已由它放开，
            // 这里再动就会清掉新一代正在进行的读取标记。
            synchronized(lock) {
                if (generation == startedAt) reading = false
            }
            throw error
        }
    }

    /**
     * 作废在途读取与缓存读数。
     *
     * binder 到达或断开说明 Shizuku 服务端换了（或重启了）：此前那一次读取读的是上一茬服务端，
     * 它甚至可能永远不返回（服务端卡死），所以必须在这里放开闸门，让下一次 [current] 重新去读
     * 真实状态，而不是一直回兜底。调用点在 [ShizukuRuntime] 的两个 binder 监听器里。
     */
    fun invalidate() {
        synchronized(lock) {
            generation += 1
            reading = false
            cached = null
        }
    }
}
