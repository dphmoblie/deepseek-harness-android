package io.deepseekharness.mobile.runtime

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object RuntimeCredentialEnvelope {
    private const val VERSION: Byte = 1
    private const val MIN_IV_BYTES = 12
    private const val MAX_IV_BYTES = 32
    private const val GCM_TAG_BYTES = 16
    private const val MAX_ENVELOPE_BYTES = 8 * 1024

    data class Value(val iv: ByteArray, val ciphertext: ByteArray)

    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size in MIN_IV_BYTES..MAX_IV_BYTES) { "Credential IV length is invalid" }
        require(ciphertext.size >= GCM_TAG_BYTES) { "Credential ciphertext is truncated" }
        val size = 2 + iv.size + ciphertext.size
        require(size <= MAX_ENVELOPE_BYTES) { "Credential envelope is too large" }
        val bytes = ByteBuffer.allocate(size)
            .put(VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decode(encoded: String): Value {
        require(encoded.length in 1..(MAX_ENVELOPE_BYTES * 2)) { "Credential envelope length is invalid" }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Credential envelope encoding is invalid", error)
        }
        require(bytes.size in (2 + MIN_IV_BYTES + GCM_TAG_BYTES)..MAX_ENVELOPE_BYTES) {
            "Credential envelope length is invalid"
        }
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.get() == VERSION) { "Credential envelope version is unsupported" }
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in MIN_IV_BYTES..MAX_IV_BYTES && buffer.remaining() >= ivLength + GCM_TAG_BYTES) {
            "Credential envelope structure is invalid"
        }
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        return Value(iv, ciphertext)
    }
}

/** Encrypts provider credentials with an app-private, non-exportable Android Keystore key. */
internal class RuntimeCredentialCipher {
    fun encrypt(plaintext: ByteArray): String {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Credential payload is too large" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(createIfMissing = true))
        cipher.updateAAD(AAD)
        return RuntimeCredentialEnvelope.encode(cipher.iv, cipher.doFinal(plaintext))
    }

    fun decrypt(encoded: String): ByteArray {
        val envelope = RuntimeCredentialEnvelope.decode(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(createIfMissing = false), GCMParameterSpec(GCM_TAG_BITS, envelope.iv))
        cipher.updateAAD(AAD)
        val plaintext = cipher.doFinal(envelope.ciphertext)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Credential payload is too large" }
        return plaintext
    }

    private fun secretKey(createIfMissing: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = androidKeyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        // Decryption must preserve a missing/unavailable key instead of replacing its alias.
        if (!createIfMissing) throw GeneralSecurityException("Saved credential key is unavailable")
        KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dsh-mobile-provider-credentials-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val MAX_PLAINTEXT_BYTES = 4 * 1024
        val AAD: ByteArray = "io.deepseekharness.mobile/provider-credentials/v1".toByteArray(Charsets.US_ASCII)
        val KEY_LOCK = Any()
    }
}
