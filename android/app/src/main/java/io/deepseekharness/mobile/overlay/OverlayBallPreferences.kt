package io.deepseekharness.mobile.overlay

import android.content.Context

/**
 * 悬浮球在屏幕上的位置。
 *
 * 存在独立的 SharedPreferences 文件里，与运行时状态分开：球的像素坐标是纯粹的视图状态，
 * 不需要参与运行时的备份、清理或版本迁移。只保存两个整数坐标，不含任何用户数据。
 *
 * 读写都做完整性校验：只存了一半（写过程被杀）或值为负时一律当作「没存过」，
 * 由调用方回退到默认位置，而不是把球摆到屏幕外。
 */
class OverlayBallPreferences(private val storage: Storage) {
    /** 存储抽象：生产实现包 SharedPreferences，测试用内存替身。 */
    interface Storage {
        fun readInt(key: String): Int?
        fun writeInt(key: String, value: Int)
    }

    fun readPosition(): Pair<Int, Int>? {
        val x = storage.readInt(KEY_X) ?: return null
        val y = storage.readInt(KEY_Y) ?: return null
        if (x < 0 || y < 0) return null
        return x to y
    }

    fun writePosition(x: Int, y: Int) {
        if (x < 0 || y < 0) return
        storage.writeInt(KEY_X, x)
        storage.writeInt(KEY_Y, y)
    }

    fun clearPosition() {
        // SharedPreferences 没有删除单个键的接口，约定 -1 表示未设置。
        storage.writeInt(KEY_X, -1)
        storage.writeInt(KEY_Y, -1)
    }

    companion object {
        const val KEY_X = "ball_x"
        const val KEY_Y = "ball_y"

        /** 生产实现：独立偏好文件，仅本应用可读。 */
        fun from(context: Context): OverlayBallPreferences {
            val preferences = context.applicationContext
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            return OverlayBallPreferences(object : Storage {
                override fun readInt(key: String): Int? =
                    if (preferences.contains(key)) preferences.getInt(key, -1) else null

                override fun writeInt(key: String, value: Int) {
                    preferences.edit().putInt(key, value).apply()
                }
            })
        }

        private const val FILE_NAME = "dsh-overlay-ball"
    }
}
