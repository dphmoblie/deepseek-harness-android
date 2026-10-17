package io.deepseekharness.mobile

/*
 * 桥载荷映射：把原生状态对象铺成前端校验认识的 JSON。
 *
 * 从 `MobileRuntimePlugin.kt` 搬出来：这些扩展函数只做字段搬运，不读插件实例状态，
 * 却占了插件文件尾部近 200 行。前端 `src/platform/validation.ts` 按固定字段校验，
 * 少一个键整份就被判「格式无效」——所以这里的注释与写法一律保持原样。
 */
import io.deepseekharness.mobile.accessibility.AutomationControlPolicy
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticState
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticText
import io.deepseekharness.mobile.runtime.MailboxState
import io.deepseekharness.mobile.runtime.MailboxDirectoryState
import io.deepseekharness.mobile.runtime.MailboxDirectoryEntry
import io.deepseekharness.mobile.runtime.StorageDirectoryState
import io.deepseekharness.mobile.runtime.StorageDirsState
import io.deepseekharness.mobile.runtime.StorageDirStatus
import io.deepseekharness.mobile.runtime.MailboxImportOutcome
import io.deepseekharness.mobile.runtime.MailboxExportOutcome
import io.deepseekharness.mobile.capability.DeviceCapability
import io.deepseekharness.mobile.capability.DeviceCapabilityFacts
import io.deepseekharness.mobile.capability.DeviceCapabilityPolicy
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import org.json.JSONArray
import org.json.JSONObject


/**
 * 能力清单过桥。
 *
 * `atMs` 是这份清单的取样时刻：能力随连接状态变，拿旧清单下判断就是按错误前提做事，
 * 所以时间戳必须跟数据一起给。
 * `action` / `alternative` 没值时给 JSON null，**不要让键消失**——前端校验按固定字段走，
 * 少一个键整份就被判「格式无效」，界面会把「没有替代方式」显示成「读不到」。
 */
internal fun DeviceCapabilityFacts.toJs(): JSObject = JSObject()
    .put("atMs", System.currentTimeMillis())
    .put("sdkInt", sdkInt)
    .put(
        "capabilities",
        JSArray().also { array -> DeviceCapabilityPolicy.report(this).forEach { array.put(it.toJs()) } },
    )

internal fun DeviceCapability.toJs(): JSObject = JSObject()
    .put("id", id)
    .put("state", state)
    .put("reason", reason)
    .put("action", action ?: JSONObject.NULL)
    .put("alternative", alternative ?: JSONObject.NULL)

/**
 * 无障碍状态过桥。
 *
 * 状态字段完整返回：`src/platform/validation.ts` 会校验它们的格式，
 * 缺一个整份状态就被判成「格式无效」，界面会把「读不到」显示成「没有自动项 / 没设密码」。
 * `alwaysAllowedPackages`（恒为 `[SELF_PACKAGE]`）与 `passwordConfigured` 都是布尔/包名，
 * **不含任何密码内容**。
 */
internal fun accessibilityStateToJs(state: JSONObject): JSObject = JSObject()
    .put("enabled", state.optBoolean("enabled", false))
    .put("whitelistEnabled", state.optBoolean("whitelistEnabled", true))
    .put("allowedPackages", state.optJSONArray("allowedPackages") ?: org.json.JSONArray())
    .put("alwaysAllowedPackages", state.optJSONArray("alwaysAllowedPackages") ?: org.json.JSONArray())
    .put("passwordConfigured", state.optBoolean("passwordConfigured", false))
    // 总开关与「服务登记过 / 服务实例还在」必须分开表达：一个布尔既说不清用户按没按过「开始」，
    // 也说不清「系统开着但服务被回收了」。缺省值取最保守的一侧（关、未连上）。
    .put("automationRuntime", state.optString("automationRuntime", AutomationControlPolicy.NAME_OFF))
    .put("serviceStatus", state.optString("serviceStatus", "off"))

/**
 * 根通道状态过桥。
 *
 * 键固定给全（没有 `su -v` 输出时给 JSON null，**不要让键消失**——前端校验按固定键走，
 * 少一个键整份就被判「格式无效」）。`usable` 是「现在真能执行根命令」的合成结论：
 * 开关开着 **且** KernelSU 侧就绪；界面用它决定说明文案与按钮可用性。
 * `degraded` 单独给：能用但少了一层隔离，需要照实显示，不能被 `usable` 掩盖。
 */
internal fun io.deepseekharness.mobile.shizuku.RootEngineAccess.Status.toJs(): JSObject = JSObject()
    .put("enabled", enabled)
    .put("usable", usable)
    .put("kernelSuStatus", kernelSuStatus)
    .put("reason", reason)
    .put("degraded", degraded)
    .put("degradations", JSArray().also { array -> degradations.forEach { array.put(it) } })
    .put("suVersion", suVersion ?: JSONObject.NULL)

/** 诊断日志状态：只有布尔值、计数与时间戳，不含任何日志内容。 */
internal fun DiagnosticState.toJs(): JSObject = JSObject()
    .put("enabled", enabled)
    .put("retentionDays", retentionDays)
    .put("fileCount", fileCount)
    .put("totalBytes", totalBytes)
    .put("lastEntryAtMillis", lastEntryAtMillis)

/**
 * 投递区状态。
 *
 * `available` 与 `availability` 是同一事实的两种表达：前者给按钮的禁用条件，
 * 后者给文案分档（可用 / 需要授权 / 不支持 / 已授权但不可写）。
 * 路径是**用户可见路径**与访客挂载点，二者都在文档里公开，不属于私有信息。
 */
internal fun MailboxState.toJs(): JSObject = JSObject()
    .put("availability", availability.wireValue)
    .put("level", availability.level)
    .put("available", available)
    .put("supported", supported)
    .put("granted", granted)
    .put("inboxPath", inboxPath)
    .put("outboxPath", outboxPath)
    .put("guestInboxPath", guestInboxPath)
    .put("guestOutboxPath", guestOutboxPath)
    .put("inboxFileCount", inboxFileCount)
    .put(
        "inboxTars",
        org.json.JSONArray().also { array ->
            inboxTars.forEach { candidate ->
                array.put(JSObject().put("name", candidate.name).put("bytes", candidate.bytes))
            }
        },
    )
    .put("exportTarName", exportTarName)
    .put("exportManifestName", exportManifestName)
    .put("importDirectory", importDirectory)

internal fun MailboxDirectoryState.toJs(): JSObject = JSObject()
    .put("root", root)
    .put("path", path ?: JSONObject.NULL)
    .put(
        "entries",
        org.json.JSONArray().also { array ->
            entries.forEach { entry -> array.put(entry.toJs()) }
        },
    )
    .put("truncated", truncated)

internal fun MailboxDirectoryEntry.toJs(): JSObject = JSObject()
    .put("name", name)
    .put("kind", kind)
    .put("bytes", bytes)

/**
 * 共享目录里某一条目录的浏览载荷。
 *
 * 与 [MailboxDirectoryState.toJs] **只差根字段的名字**（`root` → `guestPath`），条目用的是同一个
 * [MailboxDirectoryEntry.toJs]：前端用同一个文件浏览组件，两边的线形状必须逐字相同，
 * 任何一边多一个字段都会让「同一个组件」退化成两套渲染分支。
 */
internal fun StorageDirectoryState.toJs(): JSObject = JSObject()
    .put("guestPath", guestPath)
    .put("path", path ?: JSONObject.NULL)
    .put(
        "entries",
        org.json.JSONArray().also { array ->
            entries.forEach { entry -> array.put(entry.toJs()) }
        },
    )
    .put("truncated", truncated)

/**
 * 目录白名单状态。
 *
 * `level` 给界面展示口径（T2 / T0），逐条 `availability` + `level` 给「这一条现在能不能用」，
 * `reasonCode` 只在不可用时出现（受控错误码，界面据此给不同提示）。
 * 路径是**用户自己选过的用户可见路径**与访客挂载点，与投递区的 inboxPath 同类，不属于私有信息。
 */
internal fun StorageDirsState.toJs(): JSObject = JSObject()
    .put("supported", supported)
    .put("granted", granted)
    .put("level", level)
    .put("maxDirectories", maxDirectories)
    .put("count", entries.size)
    .put("active", active)
    .put(
        "entries",
        org.json.JSONArray().also { array -> entries.forEach { status -> array.put(status.toJs()) } },
    )

internal fun StorageDirStatus.toJs(): JSObject = JSObject()
    .put("index", index)
    .put("path", entry.path)
    .put("displayName", entry.displayName)
    .put("guestPath", guestPath)
    .put("availability", availability.wireValue)
    .put("level", availability.level)
    .put("available", available)
    .also { json -> reasonCode?.let { json.put("reasonCode", it) } }

/** 导入结果：只有计数、字节数、文件名与落点，不含内容。 */
internal fun MailboxImportOutcome.toJs(): JSObject = JSObject()        .put("entryCount", entryCount)
    .put("fileCount", fileCount)
    .put("directoryCount", directoryCount)
    .put("symlinkCount", symlinkCount)
    .put("hardlinkCount", hardlinkCount)
    .put("bytes", bytes)
    .put("tarName", tarName)
    .put("tarBytes", tarBytes)
    .put("verified", verified)
    .put("ignoredFiles", ignoredFiles)
    .put("target", target)
    .also { json -> manifestName?.let { json.put("manifestName", it) } }

/** 导出结果：产物文件名、字节数与摘要，以及被跳过的条目数。 */
internal fun MailboxExportOutcome.toJs(): JSObject = JSObject()
    .put("entryCount", entryCount)
    .put("bytes", bytes)
    .put("tarName", tarName)
    .put("tarBytes", tarBytes)
    .put("tarSha256", tarSha256)
    .put("manifestName", manifestName)
    .put("skippedLinks", skippedLinks)
    .put("skippedSpecial", skippedSpecial)
    .also { json -> subdirectory?.let { json.put("subdirectory", it) } }
    .also { json -> destinationDirectory?.let { json.put("destinationDirectory", it) } }

/** 应用内查看结果：受控字段文本 + 窗口与截断状态，不含路径或文件名。 */
internal fun DiagnosticText.toJs(): JSObject = JSObject()
    .put("text", text)
    .put("maxBytes", maxBytes)
    .put("totalBytes", totalBytes)
    .put("truncated", truncated)