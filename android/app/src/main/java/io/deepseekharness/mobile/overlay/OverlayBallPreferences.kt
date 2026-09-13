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

    /**
     * 写入球位置。
     *
     * 坐标为负时静默丢弃、不覆盖旧值：此时存储里仍是被拒前的旧位置，而不是「没存过」，
     * 调用方也拿不到任何错误信号。坐标的合法范围（例如不超出屏幕尺寸）由调用方负责 clamp。
     */
    fun writePosition(x: Int, y: Int) {
        if (x < 0 || y < 0) return
        storage.writeInt(KEY_X, x)
        storage.writeInt(KEY_Y, y)
    }

    fun clearPosition() {
        // Storage 抽象只暴露了 readInt/writeInt，没有删除方法，约定写入哨兵表示未设置。
        storage.writeInt(KEY_X, NOT_SET)
        storage.writeInt(KEY_Y, NOT_SET)
    }

    companion object {
        const val KEY_X = "ball_x"
        const val KEY_Y = "ball_y"

        /**
         * 未设置的哨兵值：[clearPosition] 主动写入它表示已清除；
         * 读取路径遇到任何负值（含本值）都视为「没存过」，因此它同时承担
         * 「已清除」与「非法值」两种含义。
         */
        private const val NOT_SET = -1

        /** 生产实现：独立偏好文件，仅本应用可读。 */
        fun from(context: Context): OverlayBallPreferences {
            val preferences = context.applicationContext
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            return OverlayBallPreferences(object : Storage {
                override fun readInt(key: String): Int? =
                    if (preferences.contains(key)) preferences.getInt(key, NOT_SET) else null

                override fun writeInt(key: String, value: Int) {
                    preferences.edit().putInt(key, value).apply()
                }
            })
        }

        private const val FILE_NAME = "dsh-overlay-ball"
    }
}
