package io.deepseekharness.mobile

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 国产 ROM 的「读取应用列表」权限名。
 *
 * 它不是 AOSP 权限，而是《移动终端应用软件列表权限实施指南》（T/TAF 108-2022）里的
 * 权限。HyperOS（MIUI 13+）、MagicOS、ColorOS、OriginOS 等在
 * `android.permission.QUERY_ALL_PACKAGES` 之外**又加了一道**运行时权限：
 * 只声明 `QUERY_ALL_PACKAGES` 时，`PackageManager#getInstalledApplications` 只返回
 * 本应用、系统应用与清单里声明过的包，选择器里就只剩自己。
 *
 * 单独抽成常量是因为它同时要出现在清单、Capacitor 的 permission 别名与 UI 判定里，
 * 三处必须拼同一个字符串。
 */
const val APP_LIST_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

/**
 * 「读取应用列表」的可用性判定。
 *
 * AOSP / Pixel / 三星没有这条权限，`getPermissionInfo` 会抛 `NameNotFoundException`：
 * 此时 [granted] 必须按已授予处理——拿一个不存在的权限去 `checkSelfPermission` 恒为拒绝，
 * 界面会永远显示「未授予」并让用户去一个不存在的开关里找。
 */
object AppListAccess {

    /** 系统是否定义了这条 OEM 权限；只有定义了的 ROM 才谈得上申请。 */
    fun supported(context: Context): Boolean = runCatching {
        context.packageManager.getPermissionInfo(APP_LIST_PERMISSION, 0)
    }.isSuccess

    /** 是否能读到完整应用清单。权限不存在（AOSP 等）时视为已授予。 */
    fun granted(context: Context): Boolean =
        !supported(context) ||
            ContextCompat.checkSelfPermission(context, APP_LIST_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 需要向用户展示授权入口：系统定义了这条权限，但当前还没授予。 */
    fun needsGrant(context: Context): Boolean = requiresGrant(supported(context), granted(context))

    /**
     * 纯逻辑口径：只有「系统定义了这条权限且尚未授予」才需要引导。
     *
     * 抽成不依赖 Context 的函数，是为了让单测能直接钉住这条判定（本模块没有 Robolectric）。
     */
    internal fun requiresGrant(supported: Boolean, granted: Boolean): Boolean = supported && !granted
}
