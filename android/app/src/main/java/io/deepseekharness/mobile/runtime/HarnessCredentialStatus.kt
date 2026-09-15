package io.deepseekharness.mobile.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
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

    internal fun parse(input: InputStream, customProviders: List<CustomModelProvider>): HarnessCredentialStatus {
        val document = Load(LOAD_SETTINGS).loadFromInputStream(input) as? Map<*, *>
            ?: return HarnessCredentialStatus()
        if (document.keys.any { it !is String || it !in TOP_LEVEL_KEYS } || document["version"] != 1) {
            return HarnessCredentialStatus()
        }
        val refs = document["refs"] ?: return HarnessCredentialStatus()
        if (refs !is Map<*, *> || refs.size > MAX_CREDENTIAL_REFS) return HarnessCredentialStatus()
        val configuredRefs = linkedSetOf<String>()
        for ((rawName, rawValue) in refs) {
            val name = rawName as? String ?: return HarnessCredentialStatus()
            val value = rawValue as? String ?: return HarnessCredentialStatus()
            if (!CREDENTIAL_REF_PATTERN.matches(name) || value.isEmpty()) return HarnessCredentialStatus()
            configuredRefs += name
        }
        val modelProviders = ModelProvider.entries.filterTo(linkedSetOf()) {
            it.environmentVariable in configuredRefs
        }
        val customProviderIds = customProviders.mapNotNullTo(linkedSetOf()) { provider ->
            provider.id.takeIf { provider.environmentVariable in configuredRefs }
        }
        return HarnessCredentialStatus(modelProviders, customProviderIds)
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

    private val LOAD_SETTINGS = LoadSettings.builder()
        .setLabel("Harness credential status")
        .setAllowDuplicateKeys(false)
        .setMaxAliasesForCollections(0)
        .setCodePointLimit(MAX_CREDENTIAL_DOCUMENT_BYTES)
        .build()
    private val TOP_LEVEL_KEYS = setOf("version", "refs", "records")
    private val CREDENTIAL_REF_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private const val MAX_CREDENTIAL_DOCUMENT_BYTES = 256 * 1024
    private const val MAX_CREDENTIAL_REFS = 256
    private const val GROUP_OR_OTHER_MODE_BITS = 0x3f
    // `.credentials.yaml` -> `.dsh` -> `root` -> `current`; every fixed parent must be a real directory.
    private const val TRUSTED_PARENT_DEPTH = 3
}
