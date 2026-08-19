package io.deepseekharness.mobile.runtime

internal object RuntimeUpdatePolicy {
    // Installed manifests use a canonicalized local URL, so the signed rootfs digest is the
    // stable identity shared with the immutable APK manifest.
    fun isAvailable(installedRootfsSha256: String?, bundledRootfsSha256: String?): Boolean =
        installedRootfsSha256 != null &&
            bundledRootfsSha256 != null &&
            installedRootfsSha256 != bundledRootfsSha256
}
