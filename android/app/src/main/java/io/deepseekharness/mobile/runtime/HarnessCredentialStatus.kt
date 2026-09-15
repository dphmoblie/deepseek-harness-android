package io.deepseekharness.mobile.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Public credential presence discovered in Harness' write-only credential store. */
internal data class HarnessCredentialStatus(
    val modelProviders: Set<ModelProvider> = emptySet(),
    val customProviderIds: Set<String> = emptySet(),
)

/**
 * Reads only credential-reference presence from Harness' YAML store.
 *
 * The returned status contains provider identifiers and never credential values. Invalid, oversized,
 * permissively readable, or link-backed files fail closed to an empty status so a guest-controlled path
 * cannot make the management surface claim that a credential is usable.
 */
internal object HarnessCredentialStatusReader {
    fun read(file: File, customProviders: List<CustomModelProvider>): HarnessCredentialStatus {
        if (!hasTrustedParentDirectories(file)) return HarnessCredentialStatus()
        val descriptor = try {
            Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ELOOP) return HarnessCredentialStatus()
            return HarnessCredentialStatus()
        }
        return try {
            val stat = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(stat.st_mode) ||
                stat.st_size !in 1..MAX_CREDENTIAL_DOCUMENT_BYTES.toLong() ||
                stat.st_mode and GROUP_OR_OTHER_MODE_BITS != 0
            ) {
                Os.close(descriptor)
                return HarnessCredentialStatus()
            }
            FileInputStream(descriptor).use { input -> parse(input, customProviders) }
        } catch (_: Exception) {
            try {
                Os.close(descriptor)
            } catch (_: Exception) {
                // FileInputStream owns the descriptor after successful construction.
            }
            HarnessCredentialStatus()
        }
    }

    /**
     * 只返回「哪些引用已配置」，取值在解析后立刻丢弃。
     *
     * 解析本身复用 [HarnessCredentialsDocument.refs]：状态展示与写入必须是**同一份**
     * 「什么样的文档算合法」的定义，否则会出现「界面说已配置、写入却拒绝编辑」这类自相矛盾。
     */
    internal fun parse(input: InputStream, customProviders: List<CustomModelProvider>): HarnessCredentialStatus {
        val text = readBounded(input) ?: return HarnessCredentialStatus()
        val configuredRefs = HarnessCredentialsDocument.refs(text) ?: return HarnessCredentialStatus()
        val modelProviders = ModelProvider.entries.filterTo(linkedSetOf()) {
            it.environmentVariable in configuredRefs
        }
        val customProviderIds = customProviders.mapNotNullTo(linkedSetOf()) { provider ->
            provider.id.takeIf { provider.environmentVariable in configuredRefs }
        }
        return HarnessCredentialStatus(modelProviders, customProviderIds)
    }

    /**
     * 按 [MAX_CREDENTIAL_DOCUMENT_BYTES] 有界读入；超限返回 null（失败关闭）。
     *
     * 调用方 [read] 已经用 `fstat` 卡过文件大小，这里再卡一次是因为 [parse] 也可能被直接调用
     * （单测就是这么用的），不能把「输入有界」寄托在调用方身上。
     */
    private fun readBounded(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > MAX_CREDENTIAL_DOCUMENT_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun hasTrustedParentDirectories(file: File): Boolean {
        var current = file.parentFile ?: return false
        repeat(TRUSTED_PARENT_DEPTH) {
            val stat = try {
                Os.lstat(current.absolutePath)
            } catch (_: ErrnoException) {
                return false
            }
            if (!OsConstants.S_ISDIR(stat.st_mode)) return false
            current = current.parentFile ?: return false
        }
        return true
    }

    private val MAX_CREDENTIAL_DOCUMENT_BYTES = HarnessCredentialsDocument.MAX_DOCUMENT_BYTES
    private const val GROUP_OR_OTHER_MODE_BITS = 0x3f
    // `.credentials.yaml` -> `.dsh` -> `root` -> `current`; every fixed parent must be a real directory.
    private const val TRUSTED_PARENT_DEPTH = 3
}
