package io.deepseekharness.mobile.runtime

import android.content.Context
import java.io.File

object RuntimeCommand {
    fun prootArgv(
        store: RuntimeStore,
        entrypoint: List<String>,
        harnessAuthToken: String? = null,
    ): List<String> {
        if (!store.runnerAvailable()) {
            throw RuntimeFailure("RUNNER_UNAVAILABLE", "APK 未包含当前架构的受信任运行器")
        }
        if (!store.currentRoot.isDirectory) {
            throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
        }
        if (harnessAuthToken != null && !HARNESS_TOKEN_PATTERN.matches(harnessAuthToken)) {
            throw RuntimeFailure("HARNESS_AUTH_INVALID", "Harness 临时凭据无效")
        }
        return buildList {
            add(store.runnerFile.absolutePath)
            add("-r")
            add(store.currentRoot.absolutePath)
            add("-0")
            add("-w")
            add("/root")
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("${store.resolverFile.absolutePath}:/etc/resolv.conf")
            add("/usr/bin/env")
            add("-i")
            add("HOME=/root")
            add("USER=root")
            add("LOGNAME=root")
            add("LANG=C.UTF-8")
            add("TERM=xterm-256color")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            if (harnessAuthToken != null) {
                add("NODE_OPTIONS=--require=/usr/local/lib/dsh-mobile-auth.cjs")
                add("DSH_MOBILE_AUTH_TOKEN=$harnessAuthToken")
            }
            addAll(entrypoint)
        }
    }

    fun hostEnvironment(context: Context, store: RuntimeStore): Map<String, String> {
        if (!store.runnerAvailable()) {
            throw RuntimeFailure("RUNNER_UNAVAILABLE", "APK 未包含当前架构的受信任运行器")
        }
        val temporary = File(context.cacheDir, "proot-tmp")
        if (!temporary.exists() && !temporary.mkdirs()) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法创建运行器临时目录")
        }
        RuntimeDns.refresh(context, store.resolverFile)
        return mapOf(
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "HOME" to context.filesDir.absolutePath,
            "LANG" to "C.UTF-8",
            "PROOT_LOADER" to store.loaderFile.absolutePath,
            "PROOT_TMP_DIR" to temporary.absolutePath,
            "TMPDIR" to temporary.absolutePath,
        )
    }

    private val HARNESS_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
}
