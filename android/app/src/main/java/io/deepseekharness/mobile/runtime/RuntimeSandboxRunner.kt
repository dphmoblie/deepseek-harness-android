package io.deepseekharness.mobile.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * 移动端沙箱运行器的宿主侧接线。
 *
 * **为什么需要它**：访客里的沙箱由上游 `dsh-sandbox-local` 提供，它的 landlock 档只授权
 * “访客根 `/`”，而被 PRoot 真正 `execve` 的是 **APK `nativeLibraryDir` 里的
 * `libdsh_proot_loader.so`**（`PROOT_LOADER`）——真实路径在访客根之外，于是内核拒绝这次执行，
 * 报错却落在用户要运行的程序名下，界面上表现为 `PRoot 无法加载 Ubuntu 程序`。
 * 真机复现与 A/B 取证见 `docs/真机缺陷与改进清单.md` 的 P0-3 段。
 *
 * **通道**：上游没有“追加授权”的入口（`landlock-run` 只读 argv，源码里没有任何 getenv）。
 * 可用的接缝是 `LocalSandboxProvider` 的 `runnerCommand`：一旦配置，它会把 bwrap 形态的档参数
 * 交给这个运行器。于是 App 做三件事：
 *  1. 每次启动把 [nativeLibraryDirectory] 绑定到访客固定路径 [GUEST_NATIVE_LIB_PATH]；
 *  2. 把 `assets/support/sandbox-runner.sh` 写进访客 [GUEST_SCRIPT_PATH]（0755），
 *     由它把 bwrap 形态参数翻译成 `landlock-run` 的授权参数并追加 loader 目录授权；
 *  3. 在 Cordis 覆盖里给 `sandbox` 这个插件条目设 `runnerCommand` 与失败签名（[overlayEntry]）。
 *
 * 覆盖条目与供应商条目同属一个补丁文件（`RuntimeStore.prepareProviderPatch` 写出），
 * 但**与用户是否配置模型供应商无关**：一个供应商都没配时它照样要写。
 */
internal object RuntimeSandboxRunner {
    /** 资产名，同时也是访客里的文件名。 */
    const val SCRIPT_NAME = "sandbox-runner.sh"

    /** 访客里的固定位置，与 `RuntimeCommand.PROVIDER_PATCH_GUEST_PATH` 同目录。 */
    const val GUEST_SCRIPT_PATH = "/root/.dsh-mobile/$SCRIPT_NAME"

    /**
     * APK `nativeLibraryDir` 在访客里的绑定点。
     *
     * 必须与 `assets/support/sandbox-runner.sh` 里的 `DSH_MOBILE_NATIVE_LIB` 默认值逐字一致：
     * 脚本按这个**访客路径**给 `landlock-run` 授权，`landlock-run` 打开它拿到的是宿主真实 inode。
     */
    const val GUEST_NATIVE_LIB_PATH = "/.dsh-native"

    /**
     * Cordis 插件条目 id：**`sandbox`**，不是按包名缩写出来的 `sandbox-local`。
     *
     * 真机取证（`@deepseek-ai/dsh-base@0.2.0-rc.2` 的 `cordis.patch.yml`）：
     * ```
     * - id: sandbox
     *   name: '@deepseek-ai/dsh-sandbox-local'
     * ```
     * id 与包名不同名，而覆盖条目**只按 id 定位**；对不上的条目上游只 warn 一句就跳过
     * （`dsh-app-boot@0.2.0-rc.2` 的 `patch: entry %C not found`），于是写错 id 时
     * 运行器永远不会被调用，而沙箱内执行照旧失败——静默失效，最难受的一种错法。
     * 以后升级运行时若发现沙箱又坏了，第一件事是核对这个 id 是否仍然存在。
     */
    const val PLUGIN_ID = "sandbox"

    /**
     * 运行器自身的失败签名（脚本 `fail()` 打印的前缀）。
     *
     * 交给上游做 `runnerFailureSignatures`：命中时它把这次失败判成“运行器没跑起来”，
     * 而不是“用户的命令被权限拒绝”——两者在用户那里是不同的结论。
     */
    const val FAILURE_SIGNATURE = "dsh-sandbox-runner: "

    /** 运行器启动形态：显式 `sh`，不依赖脚本的执行位（执行位仍然会设）。 */
    val RUNNER_COMMAND: List<String> = listOf("/bin/sh", GUEST_SCRIPT_PATH)

    /** 访客目录：`root/.dsh-mobile`（宿主路径）。 */
    fun guestDirectory(store: RuntimeStore): File = File(store.currentRoot, "root/.dsh-mobile")

    /** 访客脚本（宿主路径）。 */
    fun guestScript(store: RuntimeStore): File = File(guestDirectory(store), SCRIPT_NAME)

    /**
     * `sandbox` 条目的覆盖配置。
     *
     * 只写这两个键：`runnerCommand` 让上游把档参数交给我们的运行器；
     * `runnerFailureSignatures` 让运行器的失败有一条可识别的签名。
     * **不写** `probeTimeoutMs` 之类的默认值：默认值由上游决定，覆盖它等于把上游的改动吃掉。
     *
     * 注意上游是「config 整体替换」而不是深合并：这里的 `config` 会把条目的原有 config 整段换掉。
     * 真机核对过 dsh-base 里这个条目**本来没有 config**，所以整体替换不会吃掉别的东西。
     */
    fun overlayEntry(): JSONObject = JSONObject()
        .put("id", PLUGIN_ID)
        .put(
            "config",
            JSONObject()
                .put("runnerCommand", JSONArray().apply { RUNNER_COMMAND.forEach { put(it) } })
                .put("runnerFailureSignatures", JSONArray().put(FAILURE_SIGNATURE)),
        )

    /**
     * 组装补丁文件的条目列表：[overlayEntry] 在前（它必须无条件存在），供应商条目在后。
     *
     * 顺序稳定是有意的：补丁文件每次启动都会重写，条目顺序变化会让“这次启动和上次
     * 有什么不同”变得不可读。
     */
    fun overlay(providersEntry: JSONObject?): JSONArray = JSONArray().apply {
        put(overlayEntry())
        providersEntry?.let { put(it) }
    }

    /** PRoot 绑定：宿主 `nativeLibraryDir` → 访客 [GUEST_NATIVE_LIB_PATH]。 */
    fun bindMount(store: RuntimeStore): ProotBindMount =
        ProotBindMount(store.nativeLibraryDirectory.absolutePath, GUEST_NATIVE_LIB_PATH)

    /**
     * 把脚本放进访客目录（每次启动覆盖：脚本必须与当前 APK 里的 Kotlin 侧常量同版本）。
     *
     * 做法与 `RuntimeSelfCheck.prepareScript` 一致：固定目录逐层拒绝符号链接、
     * 临时文件独占创建、`Os.rename` 原子替换，最后设 0755。
     */
    fun prepare(context: Context, store: RuntimeStore) {
        val appContext = context.applicationContext
        val directory = guestDirectory(store)
        try {
            for (folder in listOf(File(store.currentRoot, "root"), directory)) {
                if (!Files.exists(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(folder.toPath())
                }
                if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) invalid()
            }
            Os.chmod(directory.absolutePath, DIRECTORY_MODE)
            val target = File(directory, SCRIPT_NAME)
            if (
                Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                invalid()
            }
            val pending = Files.createTempFile(directory.toPath(), ".sandbox-runner-", ".tmp")
            try {
                Os.chmod(pending.toString(), OWNER_READ_WRITE)
                appContext.assets.open("support/$SCRIPT_NAME").use { input ->
                    Files.newOutputStream(
                        pending,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { output -> input.copyTo(output) }
                }
                Os.chmod(pending.toString(), EXECUTABLE_FILE_MODE)
                Os.rename(pending.toString(), target.absolutePath)
            } finally {
                Files.deleteIfExists(pending)
            }
        } catch (failure: RuntimeFailure) {
            throw failure
        } catch (error: Exception) {
            throw RuntimeFailure(CODE, "无法准备移动端沙箱运行器", error)
        }
    }

    private fun invalid(): Nothing = throw RuntimeFailure(CODE, "无法准备移动端沙箱运行器")

    /** 失败码：受控枚举形态（诊断日志的 `code` 只接受大写字母/数字/下划线）。 */
    const val CODE = "SANDBOX_RUNNER_PREPARE_FAILED"

    private const val DIRECTORY_MODE = 0x1c0
    private const val OWNER_READ_WRITE = 0x180
    private const val EXECUTABLE_FILE_MODE = 0x1ed
}
