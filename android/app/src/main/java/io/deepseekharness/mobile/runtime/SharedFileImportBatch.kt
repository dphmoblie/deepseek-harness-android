package io.deepseekharness.mobile.runtime

/** 每个完整文件立刻投递；后续来源失败时仍保留已经接收的数量与投递结果。 */
internal object SharedFileImportBatch {
    enum class Status {
        SUCCESS,
        STAGED,
        TOO_LARGE,
        PERMISSION_DENIED,
        SOURCE_UNREADABLE,
        DESTINATION_UNAVAILABLE,
    }

    data class Result(val status: Status, val acceptedCount: Int = 0, val deliveryComplete: Boolean = false)

    fun <T> run(items: List<T>, importOne: (Int, T) -> Status, deliver: () -> Boolean): Result {
        var accepted = 0
        var complete = true
        items.forEachIndexed { index, item ->
            if (Thread.currentThread().isInterrupted) return Result(Status.SOURCE_UNREADABLE, accepted, complete)
            val status = try {
                importOne(index, item)
            } catch (_: Exception) {
                Status.SOURCE_UNREADABLE
            }
            if (status != Status.SUCCESS) return Result(status, accepted, complete)
            accepted++
            complete = try { deliver() } catch (_: Exception) { false }
        }
        return Result(if (complete) Status.SUCCESS else Status.STAGED, accepted, complete)
    }
}
