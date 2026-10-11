package io.deepseekharness.mobile.runtime

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 「应用升级迁移」的记账文件：`root/.dsh-mobile/plugin-manager/installed-migration.json`。
 *
 * 它只回答一个问题：**上次成功迁移到哪一步了**。因此只记四个受控事实：
 * 架构版本、上次迁移成功时的 `BuildConfig.VERSION_CODE`、当时的运行时代次指纹
 * （`runtimeId|版本|rootfs 摘要`，与 `PluginRepairPolicy` 用的是同一个指纹）、迁移时刻。
 *
 * 为什么放在插件管理区（而且是**跨运行时升级保留**的那一个目录）：应用升级和运行时升级都会
 * 重建运行时的其余部分，这份记录必须活得比它们久，否则每次升级都退化成"从未迁移过"。
 *
 * 与 `installed-lock.json`（访客脚本维护的**版本事实**）的分工：这份文件是**原生侧自己的进度**，
 * 访客脚本不读也不写；反过来原生侧不改版本账本。
 *
 * 读侧口径与账本一致：缺失、超限、JSON 损坏或任何一条字段不合法都返回 `null`（按"从未迁移过"
 * 处理，最多让迁移多跑一次 —— 迁移是幂等的）；**写失败必须让调用方知道**，因此 [write] 会抛
 * [IOException]，由调用方决定怎么记进状态（绝不静默）。
 */
object PluginMigrationState {
    const val FILE_NAME = "installed-migration.json"
    const val SCHEMA_VERSION = 1

    /** 记账文件很小；超过这个大小说明不是我们写的内容。 */
    const val MAX_BYTES = 8192L

    /** 受控的迁移原因取值，与 [PluginMigrationPolicy] 的常量一一对应。 */
    val REASONS = setOf(
        PluginMigrationPolicy.REASON_FIRST_RUN,
        PluginMigrationPolicy.REASON_APP_UPGRADED,
        PluginMigrationPolicy.REASON_APP_DOWNGRADED,
        PluginMigrationPolicy.REASON_RUNTIME_CHANGED,
    )

    private val GENERATION = Regex("^[A-Za-z0-9._|:+-]{1,256}$")

    /**
     * 一条迁移记录。
     *
     * [appVersionCode] 必须是**成功迁移**时的版本号：失败的迁移不写记录（下次启动照旧重试）。
     * [runtimeGeneration] 为 `null` 表示迁移时运行时还没装好（`installedManifest()` 为 null）。
     */
    data class Record(
        val appVersionCode: Int,
        val runtimeGeneration: String?,
        val migratedAtMillis: Long,
        val reason: String,
    )

    /** 读取并校验记录；不可用时返回 `null`（调用方按"从未迁移过"处理）。 */
    fun read(file: File): Record? = try {
        if (!file.isFile || file.length() > MAX_BYTES) null
        else {
            val value = JSONObject(file.readText())
            if (value.optInt("schemaVersion", -1) != SCHEMA_VERSION) null
            else {
                val versionCode = value.optInt("appVersionCode", -1)
                val generation = if (value.isNull("runtimeGeneration")) null else value.optString("runtimeGeneration", "")
                val at = value.optLong("migratedAtMillis", -1L)
                val reason = value.optString("reason", "")
                val valid = versionCode > 0 &&
                    at >= 0L &&
                    reason in REASONS &&
                    (generation == null || GENERATION.matches(generation))
                if (valid) Record(versionCode, generation, at, reason) else null
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 原子写：先写同目录临时文件，再改名覆盖。
     *
     * 迁移每次启动最多跑一次、且都在同一个进程里串行调用，因此不需要额外的锁；写入的是一份
     * 小文件，"临时文件 + 改名"足以避免半截内容被当成有效记录（改名失败时抛 [IOException]）。
     */
    fun write(file: File, record: Record) {
        val parent = file.parentFile ?: throw IOException("缺少目标目录")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建目录")
        val value = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("appVersionCode", record.appVersionCode)
            .put("runtimeGeneration", record.runtimeGeneration ?: JSONObject.NULL)
            .put("migratedAtMillis", record.migratedAtMillis)
            .put("reason", record.reason)
        val pending = File(parent, file.name + ".tmp")
        try {
            pending.writeText(value.toString())
            // Windows 上 renameTo 不会覆盖已存在的目标：先删旧的再改名（旧记录只是上一版进度）。
            if (file.exists() && !file.delete()) throw IOException("无法替换旧记录")
            if (!pending.renameTo(file)) throw IOException("无法写入记录")
        } finally {
            if (pending.exists()) pending.delete()
        }
    }
}
