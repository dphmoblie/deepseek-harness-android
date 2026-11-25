package io.deepseekharness.mobile.runtime

/** 每个完整文件立刻投递；后续来源失败时仍保留已经接收的数量与投递结果。 */
internal object SharedFileImportBatch {
    enum class Status {
        SUCCESS,
        STAGED,
        PERMISSION_DENIED,
        SOURCE_UNREADABLE,
        DESTINATION_UNAVAILABLE,
        NO_SPACE,
    }

    data class Result(
        val status: Status,
        val acceptedCount: Int = 0,
        val delivery: RuntimeSharedInbox.Delivery = RuntimeSharedInbox.Delivery.STAGED,
    ) {
        val deliveryComplete get() = delivery == RuntimeSharedInbox.Delivery.DELIVERED
    }

    fun <T> run(items: List<T>, importOne: (Int, T) -> Status, deliver: () -> RuntimeSharedInbox.Delivery): Result {
        var accepted = 0
        var delivery = RuntimeSharedInbox.Delivery.DELIVERED
        items.forEachIndexed { index, item ->
            if (Thread.currentThread().isInterrupted) return Result(Status.SOURCE_UNREADABLE, accepted, delivery)
            // 连 Error 也在此截住：逃出循环会丢掉已成功计数，用户会误以为全部失败而重复导入。
            val status = try {
                importOne(index, item)
            } catch (_: Throwable) {
                Status.SOURCE_UNREADABLE
            }
            if (status != Status.SUCCESS) return Result(status, accepted, delivery)
            accepted++
            delivery = try { deliver() } catch (_: Throwable) { RuntimeSharedInbox.Delivery.UNAVAILABLE }
        }
        return Result(if (delivery == RuntimeSharedInbox.Delivery.DELIVERED) Status.SUCCESS else Status.STAGED, accepted, delivery)
    }
}
