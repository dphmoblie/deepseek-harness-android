package io.deepseekharness.mobile

import io.deepseekharness.mobile.runtime.RuntimeResidueInventory
import io.deepseekharness.mobile.runtime.RuntimeResidueSweepResult

/**
 * 「运行时占用」卡片的两份载荷：还有多少可回收残留（查询）与这一次清理的结果（清理）。
 *
 * 为什么不写在插件里：`MobileRuntimePlugin` 是 `@CapacitorPlugin`，构造要 Android Context，
 * 拿不到纯 JVM 单测；这里只做**整形**，依赖是两个注入的 λ，所以份数、字节数、截断标记、
 * 幂等与失败计数都能用假实现直接断言，不碰磁盘。把 `Map` 搬到 Capacitor 的 `JSObject` 上是
 * 桥层的活（与 `MobileRuntimePlugin` 顶部 `JSONObject.toJsObject()` 同一分工：runtime 层只依赖
 * org.json，桥层负责类型搬家）。
 *
 * 三条硬规则：
 *  - **载荷里只有份数、字节数与一句中文说明**，绝不含路径、目录名或文件名。残留目录名是
 *    `<版本槽>-stale-<时间戳>-<uuid>` 这类东西：它一进载荷就可能被界面渲染、被日志导出带走，
 *    而诊断白名单里根本没有路径字段的位置。
 *  - 两个方法都**绝不抛出**：查询读不到就回 0 份并把 `truncated` 置真（「这个数字只是下界」），
 *    清理没能执行也在说明里如实写清楚——绝不把「读不到」编造成「确认没有」。
 *  - 清理载荷里的 `cleaned` / `failed` / `reclaimedBytes` 是**这一次尝试**的结果，不是清理之后的
 *    当前状态；界面想知道「还剩几份」必须重新查一次 [status]，不许拿这两个数自己相减。
 */
internal class RuntimeResiduePayload(
    private val report: () -> RuntimeResidueInventory.Report,
    private val sweep: () -> RuntimeResidueSweepResult,
) {
    /**
     * 查询载荷：`count` / `bytes` 只统计可回收的 `stale-*`（与「清理残留」按钮的判定同一份逻辑），
     * `truncated` 取自整次盘点——任一子项因条目预算提前停止时为真。
     */
    fun status(): Map<String, Any> {
        val counted = try {
            report()
        } catch (_: Throwable) {
            // 盘点本身不该抛（RuntimeResidueInventory 自己兜住 I/O 失败）；真抛了也不能回一份
            // 「确认没有残留」——份数照实回 0，只用 truncated 说明它是下界。
            return mapOf<String, Any>("count" to 0, "bytes" to 0L, "truncated" to true)
        }
        val residue = counted.categories.firstOrNull { it.token == RuntimeResidueInventory.STALE_TOKEN }
        return mapOf<String, Any>(
            "count" to (residue?.entries ?: 0),
            "bytes" to (residue?.bytes ?: 0L),
            "truncated" to counted.truncated,
        )
    }

    /**
     * 清理载荷：`cleaned`（含走兜底删除器回收的份数）/ `failed` / `reclaimedBytes` + 一句如实说明。
     *
     * 单份删不掉不算失败：返回体照常给结果，`failed` 与 `message` 如实体现，每一份的失败原因由
     * 清扫器自己写进诊断日志（`phase=cleanup`、`reason=ok|fallback|failed`）。
     */
    fun clean(): Map<String, Any> {
        val result = try {
            sweep()
        } catch (_: Throwable) {
            // sweep() 的契约是绝不抛；真抛了也必须让卡片拿到一句话，而不是一个被拒绝的 Promise。
            // cleaned/failed 回 0 是「没有任何一份被确认回收」的如实说法，不写成「已清理干净」。
            return mapOf<String, Any>(
                "cleaned" to 0,
                "failed" to 0,
                "reclaimedBytes" to 0L,
                "message" to "清理没能执行，详见诊断与日志",
            )
        }
        return mapOf<String, Any>(
            "cleaned" to result.cleaned,
            "failed" to result.failed,
            "reclaimedBytes" to result.reclaimedBytes,
            "message" to message(result),
        )
    }

    private fun message(result: RuntimeResidueSweepResult): String {
        if (result.cleaned == 0 && result.failed == 0) {
            return "没有可回收的残留，无需清理"
        }
        val parts = mutableListOf<String>()
        if (result.cleaned > 0) {
            val fallback = if (result.fallbackCleaned > 0) "（其中 ${result.fallbackCleaned} 份走了兜底删除器）" else ""
            parts += "已回收 ${result.cleaned} 份$fallback"
        }
        if (result.failed > 0) {
            parts += "${result.failed} 份未能删除"
        }
        parts += "详见诊断与日志"
        return parts.joinToString("，")
    }
}
