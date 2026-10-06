package io.deepseekharness.mobile.virtualscreen

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject

/**
 * 副屏设置的完整偏好快照。
 *
 * `widthPx`/`heightPx`/`densityDpi` 为 0 表示「用户没有自定义尺寸」：此时生效规格由规格层
 * （[VirtualScreenStart.resolve]）按自适应开关与屏幕方向算出来，而不是拿 0 去建一块 0 像素的副屏。
 */
internal data class VirtualScreenSettings(
    val previewMode: String,
    val autoFollow: String,
    val orientation: String,
    val adaptive: Boolean,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
)

/** 桥的写请求：只带这次显式给出的字段，null 表示「这一项不改」。 */
internal data class VirtualScreenSettingsUpdate(
    val previewMode: String? = null,
    val autoFollow: String? = null,
    val orientation: String? = null,
    val adaptive: Boolean? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val densityDpi: Int? = null,
)

/** 桥的启动请求：目标应用 + 只对本次会话生效的覆盖项（不写回偏好）。 */
internal data class VirtualScreenStartRequest(
    val target: String,
    val adaptive: Boolean? = null,
    val orientation: String? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val densityDpi: Int? = null,
)

/** 目标字符串的形态：完整组件名（`包名/类名`）还是包名。 */
internal enum class VirtualScreenTargetKind { COMPONENT, PACKAGE, INVALID }

/**
 * 只保存显示与操作偏好，不保存目标应用名称或画面。
 *
 * 本文件同时承担**桥入参解析**与**状态合并**两件事，原因是本次改造不允许新增源文件：
 * 这两件事都只围绕「副屏设置」这一份配置展开，放在同一个文件里比拆到服务或插件里更内聚。
 * 全部取值规则都写成不依赖 Android 框架的纯函数，`Intent`/`SharedPreferences` 的读写只在最外层，
 * 这样 `:app:testDebugUnitTest`（mockable android.jar、无 Robolectric）能真正跑到每一条规则。
 */
internal object VirtualScreenPreferences {
    private const val STORE = "virtual_screen_options"
    private const val KEY_PREVIEW_MODE = "previewMode"
    private const val KEY_AUTO_FOLLOW = "autoFollow"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_ADAPTIVE = "adaptive"
    private const val KEY_WIDTH = "widthPx"
    private const val KEY_HEIGHT = "heightPx"
    private const val KEY_DPI = "densityDpi"

    /** `autoFollow` 的合法取值；与 [VirtualScreenPolicy.autoFollow] 的允许列表逐字一致。 */
    val FOLLOW_VALUES = setOf("off", "pull_back", "promote")

    /** `orientation` 的合法取值。 */
    val ORIENTATION_VALUES = setOf("auto", "portrait", "landscape")

    /**
     * 入参非法时用的错误码。与 [VirtualScreenPolicy.errorCode] 对 `IllegalArgumentException`
     * 的判定一致（`VIRTUAL_SCREEN_INVALID`），这样桥的失败码不会因为走哪条分支而变样。
     */
    const val INVALID_CODE = "VIRTUAL_SCREEN_INVALID"

    /** 会话快照里原样透传给桥的状态字段：有就带上，没有就不编造。 */
    private val SESSION_PASSTHROUGH = listOf("starting", "stopping", "error", "treeSupported")

    private fun store(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun mode(context: Context): String = store(context).getString(KEY_PREVIEW_MODE, "60fps")
        ?.takeIf { it in VirtualScreenPolicy.FRAME_MODES } ?: "60fps"

    fun follow(context: Context): String = store(context).getString(KEY_AUTO_FOLLOW, "off")
        ?.takeIf { it in FOLLOW_VALUES } ?: "off"

    fun orientation(context: Context): String = store(context).getString(KEY_ORIENTATION, "auto")
        ?.takeIf { it in ORIENTATION_VALUES } ?: "auto"

    /** 自适应开关；默认关闭，保持改造前的固定预设行为。 */
    fun adaptive(context: Context): Boolean = store(context).getBoolean(KEY_ADAPTIVE, false)

    fun saveOrientation(context: Context, value: String) {
        require(value in ORIENTATION_VALUES) { "屏幕方向无效" }
        store(context).edit().putString(KEY_ORIENTATION, value).apply()
    }

    /** 读全量设置；每一项都先过一遍取值校验，读到脏值按默认值处理。 */
    fun read(context: Context): VirtualScreenSettings {
        val store = store(context)
        return VirtualScreenSettings(
            previewMode = mode(context),
            autoFollow = follow(context),
            orientation = orientation(context),
            adaptive = store.getBoolean(KEY_ADAPTIVE, false),
            widthPx = store.getInt(KEY_WIDTH, 0),
            heightPx = store.getInt(KEY_HEIGHT, 0),
            densityDpi = store.getInt(KEY_DPI, 0),
        )
    }

    fun configuration(context: Context) = JSONObject().put(KEY_PREVIEW_MODE, mode(context)).put(KEY_AUTO_FOLLOW, follow(context))

    fun save(context: Context, request: JSONObject) {
        // 所有字段先校验后写入，避免部分配置成功、部分失败。
        if (request.has(KEY_PREVIEW_MODE)) VirtualScreenPolicy.frameInterval(request.getString(KEY_PREVIEW_MODE))
        if (request.has(KEY_AUTO_FOLLOW)) VirtualScreenPolicy.autoFollow(request.getString(KEY_AUTO_FOLLOW))
        store(context).edit().apply {
            if (request.has(KEY_PREVIEW_MODE)) putString(KEY_PREVIEW_MODE, request.getString(KEY_PREVIEW_MODE))
            if (request.has(KEY_AUTO_FOLLOW)) putString(KEY_AUTO_FOLLOW, request.getString(KEY_AUTO_FOLLOW))
        }.apply()
    }

    /**
     * 解析桥的写请求。**枚举非法整条拒绝**（一个字段都不落盘），数值越界则夹取：
     * 边长夹到 [VirtualScreenSpec.MIN_EDGE]..[VirtualScreenSpec.MAX_EDGE]、
     * dpi 夹到 [VirtualScreenSpec.MIN_DPI]..[VirtualScreenSpec.MAX_DPI]。
     *
     * 「拒绝整条」是刻意的：设置页一次会提交好几项，若预览模式非法而尺寸照写，
     * 用户看到的界面与真正生效的配置就对不上了。
     *
     * 边长/dpi 的 0 与负数**不算越界**，而是「清掉自定义尺寸」：偏好里 0 的语义就是没自定义过，
     * 页面因此能退回自适应或方向预设；若把 0 夹成 200，用户就再也回不到预设尺寸了。
     */
    fun parseUpdate(data: JSONObject): VirtualScreenSettingsUpdate {
        val previewMode = data.enumField(KEY_PREVIEW_MODE, VirtualScreenPolicy.FRAME_MODES.keys.toSet()) {
            "副屏预览模式不在允许列表：$it"
        }
        val autoFollow = data.enumField(KEY_AUTO_FOLLOW, FOLLOW_VALUES) { "自动切换策略无效：$it" }
        val orientation = data.enumField(KEY_ORIENTATION, ORIENTATION_VALUES) { "屏幕方向无效：$it" }
        return VirtualScreenSettingsUpdate(
            previewMode = previewMode,
            autoFollow = autoFollow,
            orientation = orientation,
            adaptive = data.booleanField(KEY_ADAPTIVE),
            widthPx = data.edgeField(KEY_WIDTH, VirtualScreenSpec.MIN_EDGE, VirtualScreenSpec.MAX_EDGE),
            heightPx = data.edgeField(KEY_HEIGHT, VirtualScreenSpec.MIN_EDGE, VirtualScreenSpec.MAX_EDGE),
            densityDpi = data.edgeField(KEY_DPI, VirtualScreenSpec.MIN_DPI, VirtualScreenSpec.MAX_DPI),
        )
    }

    /** 解析桥的启动请求；目标串的形态判定见 [virtualScreenTargetKind]。 */
    fun parseStart(data: JSONObject): VirtualScreenStartRequest {
        val target = data.optString("packageName").trim()
        if (target.isEmpty()) throw RuntimeFailure(INVALID_CODE, "缺少目标应用包名")
        val update = parseUpdate(data)
        return VirtualScreenStartRequest(
            target = target,
            adaptive = update.adaptive,
            orientation = update.orientation,
            widthPx = update.widthPx,
            heightPx = update.heightPx,
            densityDpi = update.densityDpi,
        )
    }

    /**
     * 把这次写请求并进设置；校验已经在 [parseUpdate] 完成，这里只负责一次性落盘。
     * 传 null 的字段保持原值，因此「只改方向」不会顺手把尺寸清掉。
     */
    fun apply(context: Context, update: VirtualScreenSettingsUpdate) {
        val editor = store(context).edit()
        update.previewMode?.let { editor.putString(KEY_PREVIEW_MODE, it) }
        update.autoFollow?.let { editor.putString(KEY_AUTO_FOLLOW, it) }
        update.orientation?.let { editor.putString(KEY_ORIENTATION, it) }
        update.adaptive?.let { editor.putBoolean(KEY_ADAPTIVE, it) }
        update.widthPx?.let { editor.putInt(KEY_WIDTH, it) }
        update.heightPx?.let { editor.putInt(KEY_HEIGHT, it) }
        update.densityDpi?.let { editor.putInt(KEY_DPI, it) }
        editor.apply()
    }

    /**
     * 会话快照 + 偏好 + 实际读数 → 桥的状态对象（字段名与前端约定逐字一致）。
     *
     * 三个来源的优先级，按「越接近真机读数越优先」排：
     * 1. `readback`：宿主进程从 `DisplayManager` 读回的副屏真实参数（宽/高/dpi/刷新率）；
     * 2. `session`：设备端 `ShellVirtualScreen.state()` 的快照（宽高、帧率、前台包名）；
     * 3. `effective`/`settings`：本次会话规格与偏好，作为读不到读数时的回退。
     *
     * `virtualForegroundPackage`/`virtualForegroundActivity` 由设备端提供（本次改造中由另一侧落地），
     * 这里只透传：取不到时写 JSON 的 null，绝不编造一个包名。
     */
    fun mergeState(
        session: JSONObject?,
        settings: VirtualScreenSettings,
        effective: VirtualScreenSpec,
        readback: JSONObject? = null,
    ): JSONObject {
        val active = session?.optBoolean("active") == true
        val sessionId = session?.optString("sessionId").orEmpty()
        val displayId = session?.optInt("displayId", 0) ?: 0
        val width = readback?.optInt("widthPx", 0)?.takeIf { it > 0 }
            ?: session?.optInt(VirtualScreenSpec.STATE_WIDTH, 0)?.takeIf { it > 0 }
            ?: session?.optInt("widthPx", 0)?.takeIf { it > 0 }
            ?: effective.widthPx
        val height = readback?.optInt("heightPx", 0)?.takeIf { it > 0 }
            ?: session?.optInt(VirtualScreenSpec.STATE_HEIGHT, 0)?.takeIf { it > 0 }
            ?: session?.optInt("heightPx", 0)?.takeIf { it > 0 }
            ?: effective.heightPx
        val densityDpi = readback?.optInt("densityDpi", 0)?.takeIf { it > 0 }
            ?: session?.optInt("densityDpi", 0)?.takeIf { it > 0 }
            ?: effective.densityDpi
        val refreshRate = readback?.finiteDouble("refreshRate")?.takeIf { it > 0.0 }
            ?: session?.finiteDouble("displayRefreshRate")?.takeIf { it > 0.0 }
            ?: 0.0
        val frameFps = session?.finiteDouble("frameFps")?.takeIf { it.isFinite() } ?: 0.0
        return JSONObject()
            .put("active", active)
            .put("sessionId", sessionId)
            .put("displayId", if (displayId > 0) displayId else -1)
            .put("previewMode", session.sessionModeLabel() ?: settings.previewMode)
            // 会话里有就以会话为准（用户可能在副屏页面里改过档位），否则回偏好。
            .put("autoFollow", session.optionalEnum("autoFollow", FOLLOW_VALUES) ?: settings.autoFollow)
            .put("orientation", session.optionalEnum("orientation", ORIENTATION_VALUES) ?: settings.orientation)
            .put("adaptive", if (session?.has("adaptive") == true) session.optBoolean("adaptive") else settings.adaptive)
            .put("widthPx", width)
            .put("heightPx", height)
            .put("densityDpi", densityDpi)
            .put("targetPackage", session.optionalPackage("packageName"))
            .put("frameFps", frameFps)
            // 刷新率读不到时回 0（未知），不去猜屏幕标称值。
            .put("displayRefreshRate", refreshRate)
            .put("virtualForegroundPackage", session.nullableText("virtualForegroundPackage"))
            .put("virtualForegroundActivity", session.nullableText("virtualForegroundActivity"))
            .also { payload ->
                if (session != null) SESSION_PASSTHROUGH.forEach { key -> if (session.has(key)) payload.put(key, session.get(key)) }
            }
    }

    /** 目标串形态：先按组件名正则（复用 [VirtualScreenPolicy.component]），再按包名。 */
    fun targetKind(value: String): VirtualScreenTargetKind {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return VirtualScreenTargetKind.INVALID
        if (runCatching { VirtualScreenPolicy.component(trimmed) }.isSuccess) return VirtualScreenTargetKind.COMPONENT
        // 包名规则与 VirtualScreenPolicy 里组件名的主干部分一致：至少两段、每段以字母开头。
        return if (PACKAGE_PATTERN.matches(trimmed)) VirtualScreenTargetKind.PACKAGE else VirtualScreenTargetKind.INVALID
    }

    private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
}

/** 只读一个枚举字段：键不存在或为 null 时返回 null，非法值抛 [VirtualScreenPreferences.INVALID_CODE]。 */
private fun JSONObject.enumField(key: String, allowed: Set<String>, message: (String) -> String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key)
    if (value !in allowed) throw RuntimeFailure(VirtualScreenPreferences.INVALID_CODE, message(value))
    return value
}

private fun JSONObject.booleanField(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    if (value !is Boolean) throw RuntimeFailure(VirtualScreenPreferences.INVALID_CODE, "$key 需要是布尔值")
    return value
}

/**
 * 只读一个数值字段：非数字类型直接拒绝（把 `"宽": "800"` 这类类型错误当成夹取会悄悄改变用户意图），
 * 数值本身越界由调用方夹取。
 */
private fun JSONObject.numberField(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    if (value !is Number) throw RuntimeFailure(VirtualScreenPreferences.INVALID_CODE, "$key 需要是数字")
    val raw = value.toDouble()
    if (!raw.isFinite()) throw RuntimeFailure(VirtualScreenPreferences.INVALID_CODE, "$key 需要是有限数字")
    return Math.round(raw).toInt()
}

/**
 * 只读一个边长/dpi 字段：`0` 与负数表示「清掉自定义尺寸」（偏好里 0 就是这个含义），
 * 正数越界则夹到 `min..max`——夹取而不是拒绝，是因为这三项由输入框或滑杆给值，越界属正常操作。
 */
private fun JSONObject.edgeField(key: String, min: Int, max: Int): Int? {
    val value = numberField(key) ?: return null
    return if (value <= 0) 0 else value.coerceIn(min, max)
}

private fun JSONObject?.finiteDouble(key: String): Double? {
    val value = this?.optDouble(key) ?: return null
    return if (value.isFinite()) value else null
}

private fun JSONObject?.optionalEnum(key: String, allowed: Set<String>): String? =
    this?.optString(key)?.takeIf { it in allowed }

/** 取一个字符串字段；键不存在、空串或 JSON null 都返回 JSON 的 null（前端看到 `null` 而不是空串）。 */
private fun JSONObject?.nullableText(key: String): Any =
    this?.optString(key)?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL

/**
 * 设备端 `state()` 里的 `previewMode` 是标签（`realtime-60fps` / `limited-fps`），
 * 而桥的字段必须是档位值，这里翻回去；翻不出已知档位就返回 null，由调用方回退到偏好。
 */
private fun JSONObject?.sessionModeLabel(): String? =
    this?.optString("previewMode")?.takeIf { it.isNotEmpty() }?.let { VirtualScreenPolicy.frameModeOf(it) }

/** 设备端可能回完整组件名；桥要的是包名，按 `/` 截断（包名里不可能出现 `/`）。 */
private fun JSONObject?.optionalPackage(key: String): Any =
    this?.optString(key)?.takeIf { it.isNotEmpty() }?.substringBefore('/') ?: JSONObject.NULL
