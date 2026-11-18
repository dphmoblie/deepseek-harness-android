package io.deepseekharness.mobile.runtime

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore 密钥缺失或已失效时抛出的独立异常，用于向用户明确说明历史加密备份不可恢复。 */
internal class SnapshotKeyUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 生产环境快照文件的加密边界；旧版（layout 1）明文快照仍可只读恢复。 */
internal class RuntimeSnapshotCipher(
    private val keyProvider: (createIfMissing: Boolean) -> SecretKey = ::androidKeyStoreSecretKey,
) : SnapshotCipher {

    override fun encrypt(source: File, destination: File) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
        val iv = cipher.iv
        destination.parentFile?.mkdirs()
        BufferedOutputStream(Files.newOutputStream(destination.toPath())).use { raw ->
            raw.write(MAGIC)
            raw.write(iv.size)
            raw.write(iv)
            CipherOutputStream(raw, cipher).use { encrypted ->
                Files.newInputStream(source.toPath()).use { input -> input.copyTo(encrypted) }
            }
        }
    }

    override fun decrypt(source: File, destination: File, requireEncrypted: Boolean) {
        destination.parentFile?.mkdirs()
        BufferedInputStream(Files.newInputStream(source.toPath())).use { raw ->
            val header = readExactOrNull(raw, MAGIC.size)
            if (header == null || !header.contentEquals(MAGIC)) {
                if (requireEncrypted) {
                    throw IOException("快照文件缺少有效加密头，可能已损坏")
                }
                Files.newInputStream(source.toPath()).use { input ->
                    Files.newOutputStream(destination.toPath()).use { input.copyTo(it) }
                }
                return
            }
            val ivLength = raw.read()
            if (ivLength !in MIN_IV_BYTES..MAX_IV_BYTES) throw IOException("快照加密头无效")
            val iv = readExactOrNull(raw, ivLength) ?: throw IOException("快照加密头不完整")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(GCM_TAG_BITS, iv))
            CipherInputStream(raw, cipher).use { decrypted ->
                Files.newOutputStream(destination.toPath()).use { decrypted.copyTo(it) }
            }
        }
    }

    override fun plaintextBytes(file: File, requireEncrypted: Boolean): Long {
        val size = Files.size(file.toPath())
        if (size < HEADER_BYTES + GCM_TAG_BYTES) {
            return if (requireEncrypted) -1L else size
        }
        Files.newInputStream(file.toPath()).use { input ->
            val header = readExactOrNull(input, MAGIC.size)
            if (header == null || !header.contentEquals(MAGIC)) {
                return if (requireEncrypted) -1L else size
            }
            val ivLength = input.read()
            if (ivLength !in MIN_IV_BYTES..MAX_IV_BYTES) {
                return if (requireEncrypted) -1L else size
            }
            if (!skipExact(input, ivLength.toLong())) {
                return if (requireEncrypted) -1L else size
            }
            val minEncryptedSize = MAGIC.size + 1L + ivLength + GCM_TAG_BYTES
            if (size < minEncryptedSize) {
                return if (requireEncrypted) -1L else size
            }
            return size - minEncryptedSize
        }
    }

    private fun readExactOrNull(input: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read <= 0) return null
            offset += read
        }
        return buffer
    }

    private fun skipExact(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) return false
                remaining -= 1L
            }
        }
        return true
    }

    private companion object {
        val MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), '2'.code.toByte())
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dsh-mobile-runtime-snapshots-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = 16
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 32
        const val HEADER_BYTES = 4 + 1 + MIN_IV_BYTES
        val KEY_LOCK = Any()

        fun androidKeyStoreSecretKey(createIfMissing: Boolean): SecretKey = synchronized(KEY_LOCK) {
            val keyStore = try {
                KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            } catch (error: Exception) {
                throw SnapshotKeyUnavailableException("快照加密密钥库不可用，加密备份无法恢复", error)
            }
            val existing = try {
                keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            } catch (error: Exception) {
                throw SnapshotKeyUnavailableException("快照加密密钥已失效，已加密备份不可恢复", error)
            }
            if (existing != null) return@synchronized existing
            if (!createIfMissing) {
                throw SnapshotKeyUnavailableException("快照加密密钥已丢失，已加密备份不可恢复")
            }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
                init(KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
        }
    }
}
