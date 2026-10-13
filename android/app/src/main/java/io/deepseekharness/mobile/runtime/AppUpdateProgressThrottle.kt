package io.deepseekharness.mobile.runtime

/**
 * 应用更新包下载的进度节流：只在值得上报的时候放行一次。
 *
 * 为什么需要它：下载几百 MB 的 APK 时，HTTP 读取循环会按块回调（每块几十 KB），
 * 逐笔丢给 WebView 会让桥接成为瓶颈，界面上的百分比也几乎不动。
 *
 * 规则：
 * - 第一笔一定放行（否则界面停在 0% 直到跨过阈值）；
 * - 最后一笔（下载字节数达到总长）一定放行（否则界面永远差一点）；
 * - 其余按步长放行，步长取「总长的 1%」与 256 KB 里较大的那个；
 * - 同一字节数重复上报直接丢弃（重试与断点续传会重放同一位置）。
 *
 * @param totalBytes 发布页声明的包体大小，必须为正；非正值时只放行首尾两笔。
 */
class AppUpdateProgressThrottle(private val totalBytes: Long) {
    private var lastReportedBytes = Long.MIN_VALUE

    fun shouldReport(downloadedBytes: Long): Boolean {
        if (downloadedBytes == lastReportedBytes) return false
        val first = lastReportedBytes == Long.MIN_VALUE
        val last = totalBytes > 0 && downloadedBytes >= totalBytes
        if (first || last) {
            lastReportedBytes = downloadedBytes
            return true
        }
        val step = maxOf(totalBytes / 100, MIN_STEP_BYTES)
        if (downloadedBytes - lastReportedBytes < step) return false
        lastReportedBytes = downloadedBytes
        return true
    }

    private companion object {
        /** 两次上报之间的最小跨度：包很小时「总长的 1%」会小到没意义，用它兜底。 */
        const val MIN_STEP_BYTES = 256L * 1024L
    }
}
