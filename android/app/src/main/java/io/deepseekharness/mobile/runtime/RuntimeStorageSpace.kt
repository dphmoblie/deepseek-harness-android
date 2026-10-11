package io.deepseekharness.mobile.runtime

import android.os.StatFs
import java.io.File

/**
 * 运行时目录的可用空间读数，**只**用于诊断日志。
 *
 * 与 `RuntimeSelfCheck.availableBytes()` 的契约不同，注意不要互相改动：那一处读不到时回 `0`
 * （自检载荷里 `0` 就是「读不到」），这里回 `null`，让诊断字段**省略**而不是写一个假数字。
 *
 * 安装流程**不**据此提前拒绝安装：先如实失败并留下数字，比先拒绝更容易定位；
 * 空间够不够由用户对照 `free_bytes` 与归档大小自行判断。
 */
internal object RuntimeStorageSpace {
    fun availableBytes(path: File): Long? = try {
        StatFs(path.absolutePath).availableBytes
    } catch (_: Throwable) {
        null
    }
}
