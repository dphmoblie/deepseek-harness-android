package io.deepseekharness.mobile.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ≤8 目录白名单的持久化。
 *
 * 存储抽象可注入（生产实现包 SharedPreferences，JVM 单测用内存替身），与
 * `OverlayBallPreferences` 同一个模式：业务代码里不出现 `getSharedPreferences`，
 * 否则白名单的读取/失效判定就只能靠 `android.system.Os` 桩去测，等于测不到。
 *
 * **整份白名单只存一个键**（一个 JSON 数组）：路径、展示名与顺序都在这一个值里，
 * 因此不存在「顺序键与条目键各自更新一半」的中间态。写入是整体替换，读取是整体解析。
 *
 * 读取的容错口径（对应规格第三节第 3 条）：
 *  - 单个条目形态非法（缺 `path`、类型不对、重复、超出上限）→ **只丢这一条**并计数；
 *  - 整份内容不是合法 JSON 数组 → 记 `corrupt`，此时确实没有可保留的东西。
 * 两种都不抛错：偏好坏掉不该让运行时启动失败，也不该让用户其余的选择一起消失。
 */
internal class RuntimeStorageDirPreferences(private val storage: Storage) {
    /** 存储抽象：生产实现包 SharedPreferences，测试用内存替身。 */
    interface Storage {
        fun readString(key: String): String?
        fun writeString(key: String, value: String)
    }

    fun read(): StoredStorageDirs {
        val text = storage.readString(KEY_ENTRIES) ?: return StoredStorageDirs(emptyList())
        val array = try {
            JSONArray(text)
        } catch (_: Exception) {
            return StoredStorageDirs(emptyList(), corrupt = true)
        }
        val entries = mutableListOf<StorageDirEntry>()
        val seen = mutableSetOf<String>()
        var dropped = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            val path = item?.optString(KEY_PATH, "").orEmpty()
            val name = item?.optString(KEY_NAME, "").orEmpty()
            val accepted = path.isNotEmpty() && name.isNotEmpty() && seen.add(path) &&
                entries.size < RuntimeStorageDirsLimits.MAX_DIRECTORIES &&
                path.length <= MAX_STORED_PATH_CHARS
            if (!accepted) {
                dropped += 1
                continue
            }
            entries += StorageDirEntry(path, name.take(RuntimeStorageDirsLimits.MAX_DISPLAY_NAME_CHARS))
        }
        return StoredStorageDirs(entries, dropped)
    }

    /**
     * 整体替换写入。
     *
     * 上限在这里再拦一次：白名单是「不超过 8 条」的不变量，越界的调用是代码缺陷，
     * 应当在写入点就暴露，而不是先落盘再靠读取端丢弃。
     */
    fun write(entries: List<StorageDirEntry>) {
        if (entries.size > RuntimeStorageDirsLimits.MAX_DIRECTORIES) {
            throw RuntimeFailure(
                StorageDirCodes.LIMIT_REACHED,
                "目录白名单最多 ${RuntimeStorageDirsLimits.MAX_DIRECTORIES} 条",
            )
        }
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put(KEY_PATH, entry.path)
                    .put(KEY_NAME, entry.displayName),
            )
        }
        storage.writeString(KEY_ENTRIES, array.toString())
    }

    companion object {
        const val KEY_ENTRIES = "entries"
        private const val KEY_PATH = "path"
        private const val KEY_NAME = "name"

        /** 存储内容自证：比共享存储根还长的路径不可能合法，读到就当坏条目丢掉。 */
        private const val MAX_STORED_PATH_CHARS = RuntimeStorageDirsLimits.MAX_RELATIVE_PATH_CHARS + 64

        private const val FILE_NAME = "dsh-storage-dirs"

        /** 生产实现：独立偏好文件，仅本应用可读（只存用户自己选过的目录路径）。 */
        fun from(context: Context): RuntimeStorageDirPreferences {
            val preferences = context.applicationContext
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            return RuntimeStorageDirPreferences(object : Storage {
                override fun readString(key: String): String? =
                    if (preferences.contains(key)) preferences.getString(key, null) else null

                override fun writeString(key: String, value: String) {
                    preferences.edit().putString(key, value).apply()
                }
            })
        }
    }
}
