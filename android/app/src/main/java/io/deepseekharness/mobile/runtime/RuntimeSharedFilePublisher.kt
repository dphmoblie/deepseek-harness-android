package io.deepseekharness.mobile.runtime

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path

/** 使用固定的目录句柄发布文件，避免访客换掉路径或并发创建同名文件。 */
internal object RuntimeSharedFilePublisher {
    private val available = try {
        System.loadLibrary("dsh_pty")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun publish(
        sourceDirectory: Path,
        sourceName: Path,
        runtimeAnchor: Path,
        targetDirectories: List<String>,
        targetName: Path,
    ): RuntimeSharedInbox.Delivery {
        if (!available) return RuntimeSharedInbox.Delivery.UNAVAILABLE
        return when (publishNative(
            sourceDirectory.toString().toByteArray(),
            sourceName.toString().toByteArray(),
            runtimeAnchor.toString().toByteArray(),
            targetDirectories.joinToString("/").toByteArray(),
            targetName.toString().toByteArray(),
        )) {
            0 -> RuntimeSharedInbox.Delivery.DELIVERED
            1 -> RuntimeSharedInbox.Delivery.STAGED
            2 -> RuntimeSharedInbox.Delivery.NO_SPACE
            3 -> throw FileAlreadyExistsException(targetName.toString())
            else -> RuntimeSharedInbox.Delivery.UNAVAILABLE
        }
    }

    private external fun publishNative(
        sourceDirectory: ByteArray,
        sourceName: ByteArray,
        runtimeAnchor: ByteArray,
        targetDirectories: ByteArray,
        targetName: ByteArray,
    ): Int
}
