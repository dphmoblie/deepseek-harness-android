package io.deepseekharness.mobile.runtime

import android.content.Context
import java.io.File

data class RuntimeLaunchSpec(
    val argv: List<String>,
    val environment: Map<String, String>,
)

data class ProotBindMount(
    val source: String,
    val target: String = source,
)

object RuntimeCommand {
    fun prootArgv(
        store: RuntimeStore,
        entrypoint: List<String>,
        bindMounts: List<ProotBindMount> = emptyList(),
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
            add(store.launchRunnerFile.absolutePath)
            add("-r")
            add(store.currentRoot.absolutePath)
            add("-0")
            add("-w")
            add("/root")
            bindMounts.forEach { mount ->
                validateBindMount(mount)
                add("-b")
                add(if (mount.source == mount.target) mount.source else "${mount.source}:${mount.target}")
            }
            add("/usr/bin/env")
            add("-i")
            add("HOME=/root")
            add("USER=root")
            add("LOGNAME=root")
            add("LANG=C.UTF-8")
            add("TERM=xterm-256color")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            store.settings().apiKey.takeIf { it.isNotBlank() }?.let { add("DEEPSEEK_API_KEY=$it") }
            if (harnessAuthToken != null) {
                add("NODE_OPTIONS=--require=/usr/local/lib/dsh-mobile-auth.cjs")
                add("DSH_MOBILE_AUTH_TOKEN=$harnessAuthToken")
            }
            addAll(entrypoint)
        }
    }

    fun hostEnvironment(
        context: Context,
        store: RuntimeStore,
        disableSeccomp: Boolean = false,
    ): Map<String, String> {
        if (!store.runnerAvailable()) {
            throw RuntimeFailure("RUNNER_UNAVAILABLE", "APK 未包含当前架构的受信任运行器")
        }
        val temporary = File(context.cacheDir, "proot-tmp")
        if (!temporary.exists() && !temporary.mkdirs()) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法创建运行器临时目录")
        }
        return mutableMapOf(
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "HOME" to context.filesDir.absolutePath,
            "LANG" to "C.UTF-8",
            "LD_LIBRARY_PATH" to "",
            "PROOT_LOADER" to store.launchLoaderFile.absolutePath,
            "PROOT_TMP_DIR" to temporary.absolutePath,
            "TMPDIR" to temporary.absolutePath,
        ).apply {
            if (disableSeccomp) put("PROOT_NO_SECCOMP", "1")
        }
    }

    private fun validateBindMount(mount: ProotBindMount) {
        if (!isSafeAbsolutePath(mount.source) || !isSafeAbsolutePath(mount.target)) {
            throw RuntimeFailure("RUNNER_ARGUMENT_INVALID", "PRoot 挂载路径无效")
        }
    }

    private fun isSafeAbsolutePath(value: String): Boolean {
        if (value.length !in 2..MAX_ABSOLUTE_PATH_LENGTH || !SAFE_ABSOLUTE_PATH.matches(value)) return false
        return value.drop(1).split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".."
        }
    }

    private val HARNESS_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
    private val SAFE_ABSOLUTE_PATH = Regex("^/[A-Za-z0-9._/-]+$")
    private const val MAX_ABSOLUTE_PATH_LENGTH = 4096
}
