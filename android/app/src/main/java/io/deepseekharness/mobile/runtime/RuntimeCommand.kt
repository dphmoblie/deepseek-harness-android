package io.deepseekharness.mobile.runtime

import android.content.Context
import java.io.File

object RuntimeCommand {
    fun prootArgv(store: RuntimeStore, entrypoint: List<String>): List<String> {
        if (!store.runnerAvailable()) {
            throw RuntimeFailure("RUNNER_UNAVAILABLE", "APK 未包含当前架构的受信任运行器")
        }
        if (!store.currentRoot.isDirectory) {
            throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
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
            add("/usr/bin/env")
            add("-i")
            add("HOME=/root")
            add("USER=root")
            add("LOGNAME=root")
            add("LANG=C.UTF-8")
            add("TERM=xterm-256color")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            addAll(entrypoint)
        }
    }

    fun hostEnvironment(context: Context): Map<String, String> {
        val temporary = File(context.cacheDir, "proot-tmp")
        if (!temporary.exists() && !temporary.mkdirs()) {
            throw RuntimeFailure("FILESYSTEM_ERROR", "无法创建运行器临时目录")
        }
        return mapOf(
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "HOME" to context.filesDir.absolutePath,
            "LANG" to "C.UTF-8",
            "PROOT_TMP_DIR" to temporary.absolutePath,
            "TMPDIR" to temporary.absolutePath,
        )
    }
}
