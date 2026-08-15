package io.deepseekharness.mobile.runtime

object BestEffortCleanup {
    fun runAll(vararg operations: () -> Unit) {
        var firstFailure: RuntimeFailure? = null
        for (operation in operations) {
            try {
                operation()
            } catch (failure: RuntimeFailure) {
                if (firstFailure == null) firstFailure = failure
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = RuntimeFailure("CLEANUP_FAILED", "本机运行时清理失败", error)
                }
            }
        }
        firstFailure?.let { throw it }
    }
}
