package io.deepseekharness.mobile.runtime

import android.content.Context

/**
 * 「访客自检已通过」的持久化缓存。
 *
 * 为什么需要它：每次点「打开对话」都会起一个 PRoot 进程执行 `dsh --version`，只用来确认
 * Ubuntu 用户空间还能不能跑。一次 PRoot + node 的冷启动在真机上要 1~3 秒，而它验证的东西
 * 在两次启动之间通常不会变 —— 运行时清单、rootfs 摘要、设备指纹、可选绑定任何一项变了，
 * 启动档缓存键 `profileKey` 都会跟着变。于是把结论缓存下来：键没变、且**上一次完整启动
 * 成功过**时，跳过这次探测。
 *
 * 为什么缓存的是「完整启动成功」而不是「探测通过」：探测只跑 `bash -c exit 0`，完整启动还
 * 验证了 node、dsh 入口、端口与网页鉴权入口。只有后者能证明「这套环境真的能起来」；
 * 把探测通过当成可缓存的结论，会出现「探测说好、实际起不来，却因为缓存被跳过」。
 *
 * 失效口径（由调用方 `RuntimeLaunchResolver` 保证）：
 *  - 任何一次完整启动失败 → [invalidate]；
 *  - 运行时安装/更新、启动档变化 → `profileKey` 变，令牌逐字失配；
 *  - 令牌缺失、类型损坏、旧版本前缀 → 一律按「未验证」处理，照旧探测。
 *
 * 存储抽象可注入（生产实现包 SharedPreferences，JVM 单测用内存替身），与
 * [RuntimeStorageDirPreferences] 同一个模式：业务代码里不出现 `getSharedPreferences`，
 * 否则这条缓存逻辑就只能靠桩件去测，等于测不到。
 *
 * 线程模型：调用方始终在 `RuntimeLaunchResolver` 的锁内使用本类，这里不再加锁。
 */
internal class RuntimeGuestVerification(private val storage: Storage) {
    /** 存储抽象：生产实现包 SharedPreferences，测试用内存替身。 */
    interface Storage {
        fun readToken(): String?

        /** 传 null 表示删除键。 */
        fun writeToken(value: String?)
    }

    /**
     * 本进程内「已明确失效」的标记：一旦置位，[allowsSkip] 不再信任磁盘上的令牌。
     *
     * 用来兜住「失效写入失败」这一条：`SharedPreferences.edit().apply()` 极少失败，但失败时
     * 磁盘上可能还留着上一次成功的令牌；如果只靠磁盘状态判断，本次启动失败之后的下一次启动
     * 仍会跳过自检 —— 那正是「真实损坏被缓存掩盖」的最坏情况。
     */
    private var invalidated = false

    /**
     * 当前 `profileKey` 是否被上一次完整启动证明过。
     *
     * 任何读取故障都按「未证明」处理：缓存是纯优化，读不出来最多多付一次探测，绝不能
     * 反过来让启动失败或者放行一个没验证过的环境。
     */
    fun allowsSkip(profileKey: String): Boolean {
        if (invalidated) return false
        val token = try {
            storage.readToken()
        } catch (_: Exception) {
            return false
        }
        return matches(token, profileKey)
    }

    /** 完整启动成功后调用；写失败只意味着下次多付一次探测，不能让本次启动失败。 */
    fun markVerified(profileKey: String) {
        invalidated = false
        try {
            storage.writeToken(tokenFor(profileKey))
        } catch (_: Exception) {
            // 缓存写不进去就退回「每次都探测」。
        }
    }

    /** 任何一次启动失败后调用：下一次启动必须重新探测。 */
    fun invalidate() {
        invalidated = true
        try {
            storage.writeToken(null)
        } catch (_: Exception) {
            // 磁盘删除失败由 [invalidated] 在本次进程内兜住；跨进程只能等下次失败再次清。
        }
    }

    companion object {
        /**
         * 令牌前缀带版本。
         *
         * 以后如果缓存语义要改（例如从「完整启动成功」放宽成「探测通过」），改这个前缀即可让
         * 所有设备上的旧令牌自动失配，不需要用户清数据，也不需要迁移代码。
         */
        private const val TOKEN_VERSION = "verified-v1"

        /** 独立偏好文件：只存一个令牌，和启动档缓存分开，互不影响。 */
        const val PREFERENCES = "runtime_guest_verification"

        const val KEY_TOKEN = "verified_profile"

        internal fun tokenFor(profileKey: String): String = "$TOKEN_VERSION:$profileKey"

        /**
         * 只认逐字相等。
         *
         * `profileKey` 本身就是「设备 + 运行时清单 + rootfs 摘要 + 可选绑定」拼成的键串，
         * 任何一段变化都会让它失配，因此这里不需要再解析令牌内容，也不存在「部分字段过期」
         * 这种中间态。
         */
        internal fun matches(token: String?, profileKey: String): Boolean =
            token != null && token == tokenFor(profileKey)

        fun from(context: Context): RuntimeGuestVerification {
            val preferences = context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            return RuntimeGuestVerification(object : Storage {
                override fun readToken(): String? = try {
                    if (preferences.contains(KEY_TOKEN)) preferences.getString(KEY_TOKEN, null) else null
                } catch (_: ClassCastException) {
                    // 值被别的类型覆盖（或旧版本写坏了）：当没有缓存，照旧探测。
                    null
                }

                override fun writeToken(value: String?) {
                    val editor = preferences.edit()
                    if (value == null) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, value)
                    editor.apply()
                }
            })
        }
    }
}
