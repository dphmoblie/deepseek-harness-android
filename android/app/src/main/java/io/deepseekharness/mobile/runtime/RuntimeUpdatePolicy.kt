package io.deepseekharness.mobile.runtime

internal object RuntimeUpdatePolicy {
    /**
     * Only advertise an embedded update when it is a newer release of the same runtime.
     * A digest mismatch alone is insufficient: an explicitly configured remote runtime may be
     * newer than the APK, and replacing it would silently downgrade and erase the user's files.
     */
    fun isAvailable(
        installedRuntimeId: String?,
        installedVersion: String?,
        installedRootfsSha256: String?,
        bundledRuntimeId: String?,
        bundledVersion: String?,
        bundledRootfsSha256: String?,
    ): Boolean {
        if (
            installedRuntimeId == null || installedVersion == null || installedRootfsSha256 == null ||
            bundledRuntimeId == null || bundledVersion == null || bundledRootfsSha256 == null
        ) return false
        if (installedRuntimeId != bundledRuntimeId || installedRootfsSha256 == bundledRootfsSha256) return false
        return compareVersions(bundledVersion, installedVersion) > 0
    }

    /** Compare the bounded numeric release format used by runtime manifests. */
    internal fun compareVersions(left: String, right: String): Int {
        val leftParsed = parseVersion(left) ?: return 0
        val rightParsed = parseVersion(right) ?: return 0
        for (index in 0 until maxOf(leftParsed.core.size, rightParsed.core.size)) {
            val leftPart = leftParsed.core.getOrElse(index) { 0L }
            val rightPart = rightParsed.core.getOrElse(index) { 0L }
            if (leftPart != rightPart) return leftPart.compareTo(rightPart)
        }
        if (leftParsed.prerelease == null && rightParsed.prerelease != null) return 1
        if (leftParsed.prerelease != null && rightParsed.prerelease == null) return -1
        val leftSuffix = leftParsed.prerelease ?: emptyList()
        val rightSuffix = rightParsed.prerelease ?: emptyList()
        for (index in 0 until maxOf(leftSuffix.size, rightSuffix.size)) {
            val leftToken = leftSuffix.getOrNull(index) ?: return -1
            val rightToken = rightSuffix.getOrNull(index) ?: return 1
            val compared = comparePrereleaseToken(leftToken, rightToken)
            if (compared != 0) return compared
        }
        return 0
    }

    private data class ParsedVersion(val core: List<Long>, val prerelease: List<String>?)

    private fun parseVersion(value: String): ParsedVersion? {
        if (!VERSION_PATTERN.matches(value)) return null
        val split = value.split('-', limit = 2)
        val core = split[0].split('.').map { it.toLongOrNull() ?: return null }
        if (core.isEmpty() || core.size > MAX_CORE_PARTS) return null
        val prerelease = split.getOrNull(1)?.split('.')?.also { parts ->
            if (parts.any { it.isEmpty() || !PRERELEASE_TOKEN.matches(it) }) return null
        }
        return ParsedVersion(core, prerelease)
    }

    private fun comparePrereleaseToken(left: String, right: String): Int {
        val leftNumber = left.toLongOrNull()
        val rightNumber = right.toLongOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }

    private const val MAX_CORE_PARTS = 4
    private val VERSION_PATTERN = Regex("^[0-9]+(?:\\.[0-9]+){0,3}(?:-[A-Za-z0-9]+(?:\\.[A-Za-z0-9]+)*)?$")
    private val PRERELEASE_TOKEN = Regex("^[A-Za-z0-9]+$")
}
