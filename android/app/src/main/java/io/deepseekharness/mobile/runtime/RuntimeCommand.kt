package io.deepseekharness.mobile.runtime

import android.content.Context
import java.io.File

data class RuntimeLaunchSpec(
    val argv: List<String>,
    val environment: Map<String, String>,
    /**
     * 本次启动投递的模型凭据**条数**（只记数量，不含变量名与取值）。
     *
     * 修复前这个数字是从 argv 里数 `<PROVIDER>_API_KEY=` 形态得出的；
     * 现在 argv 里不再出现任何凭据赋值，因此改由投递计划给出（见 [RuntimeSecretDelivery]）。
     */
    val modelCredentialCount: Int = 0,
)

data class ProotBindMount(
    val source: String,
    val target: String = source,
)

data class DeviceBridgeAccess(val port: Int, val token: String)

/**
 * 组装一次 PRoot argv 所需的**全部**输入。
 *
 * 这个数据类是「argv 里不出现密钥取值」的**结构性保证**：
 * 除 [secrets] 之外没有第二个字段能携带秘密，而 [secrets] 自身只把取值留在
 * [RuntimeSecretDelivery.environmentValues] 里 —— argv 只允许出现
 * [RuntimeSecretDelivery.guestEnvironmentFile] 这个**路径**。
 *
 * 它是纯数据、不依赖 Android API，因此可以在 JVM 单测里用哨兵取值直接断言 argv 干净
 * （见 `RuntimeCommandSecretTest`）。测试之所以不是空转，是因为取值确实进入了本对象。
 */
internal data class ProotArgvInput(
    val runnerPath: String,
    val rootPath: String,
    /** `DSH_PERMISSION_MODE` 的受控取值（[HarnessPermissionMode.wireValue]）。 */
    val permissionMode: String,
    val entrypoint: List<String>,
    val bindMounts: List<ProotBindMount> = emptyList(),
    /** 非空的 Harness 会话会额外注入 pid 文件路径与 Node 预载，用于识别 PRoot 退出后的残留子进程。 */
    val harnessPidFilePath: String? = null,
    /** 设备桥端口。**端口不是秘密**，取值本身由访客环境文件承载。 */
    val deviceBridgePort: Int? = null,
    val secrets: RuntimeSecretDelivery = RuntimeSecretDelivery.NONE,
)

object RuntimeCommand {
    const val PROVIDER_PATCH_GUEST_PATH = "/root/.dsh-mobile/launcher-providers.patch.json"

    /**
     * 访客内运行时秘密环境文件的固定路径。
     *
     * 这是 argv 里**唯一**允许与秘密相关的字符串：它是路径，不含任何取值。
     * 宿主侧对应文件见 `RuntimeStore.runtimeSecretFile`。
     */
    const val GUEST_SECRET_ENV_PATH = "/root/.dsh-mobile/runtime-secrets.env"

    /** Node 预载脚本的访客路径（认证门由它在 dsh 的路由挂载前安装）。 */
    private const val MOBILE_AUTH_PRELOAD_GUEST_PATH = "/usr/local/lib/dsh-mobile-auth.cjs"

    /**
     * 访客入口包装脚本：先 `source` 那个 0600 环境文件，再 `exec` 真正的入口。
     *
     * **常量、不含任何取值、也不含路径** —— 取值只存在于文件里，路径作为独立的 argv 元素
     * 通过 `"$1"` 传进来（`$1` 被 `shift` 掉之后 `"$@"` 正好是真正的入口）。
     * 这样做的两个好处：argv 里与秘密相关的字符串只有一个可读的路径；
     * 这段脚本里连一个 `=` 都不出现（验收脚本会按赋值形态扫描 `ps` 输出）。
     *
     * `exec "$@"` 会原地替换本进程，因此不会多出一层常驻进程，也不会改变 dsh 看到的 argv[0]。
     * 文件不可读时**直接失败**而不是继续：继续会让 dsh 在缺临时令牌的情况下起来，
     * 用户看到的是「认证不可用」这类下游报错，把投递故障伪装成运行故障。
     */
    internal val GUEST_SECRET_SOURCE_SCRIPT = listOf(
        "if [ -r \"\$1\" ]; then set -a; . \"\$1\"; set +a",
        "else echo dsh-runtime-secret-file-unavailable >&2; exit 1; fi",
        "shift",
        "exec \"\$@\"",
    ).joinToString("; ")

    /** `sh -c '脚本' <$0> <argv...>` 里的 `$0`：只用于占位，dsh 看到的是真正的入口。 */
    private const val GUEST_ENTRYPOINT_ARGV0 = "dsh-mobile"

    fun withProviderPatch(entrypoint: List<String>, patchPath: String?): List<String> {
        if (patchPath == null) return entrypoint.toList()
        if (
            patchPath != PROVIDER_PATCH_GUEST_PATH || entrypoint.size != 6 ||
            entrypoint[0] != "/usr/local/bin/dsh" || entrypoint[1] != "web"
        ) {
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "Harness 启动配置无效")
        }
        return buildList(entrypoint.size + 2) {
            add(entrypoint[0])
            add(entrypoint[1])
            add("--patch")
            add(PROVIDER_PATCH_GUEST_PATH)
            addAll(entrypoint.drop(2))
        }
    }

    /**
     * 从 [RuntimeStore] 取出组装 argv 所需的非敏感输入。
     *
     * 保留这一层是因为启动前置校验（运行器可用、根目录存在）必须只有一道门：
     * 所有启动路径（Harness、终端、探测、自检）都经过这里。
     *
     * @param secrets 秘密的**载体描述**。由 `RuntimeStore.prepareRuntimeSecrets` 在调用本方法
     *   **之前**写好文件并返回；本方法只把其中的路径放进 argv。
     */
    internal fun prootArgv(
        store: RuntimeStore,
        entrypoint: List<String>,
        bindMounts: List<ProotBindMount> = emptyList(),
        secrets: RuntimeSecretDelivery = RuntimeSecretDelivery.NONE,
        harnessSession: Boolean = false,
        deviceBridgePort: Int? = null,
    ): List<String> {
        if (!store.runnerAvailable()) {
            throw RuntimeFailure("RUNNER_UNAVAILABLE", "APK 未包含当前架构的受信任运行器")
        }
        if (!store.currentRoot.isDirectory) {
            throw RuntimeFailure("RUNTIME_NOT_INSTALLED", "Ubuntu 运行时尚未安装")
        }
        return prootArgv(
            ProotArgvInput(
                runnerPath = store.launchRunnerFile.absolutePath,
                rootPath = store.currentRoot.absolutePath,
                permissionMode = store.harnessPermissionMode().wireValue,
                entrypoint = entrypoint,
                bindMounts = bindMounts,
                harnessPidFilePath = store.harnessPidFile.absolutePath.takeIf { harnessSession },
                deviceBridgePort = deviceBridgePort,
                secrets = secrets,
            ),
        )
    }

    /**
     * 组装 argv 的**纯函数**本体。
     *
     * 安全校验点：`env -i` 之后注入的每一项要么是固定常量，要么是**路径**或受控枚举
     * （端口、权限模式）。秘密取值一律经由 [RuntimeSecretDelivery] 描述的访客文件传入，
     * 本函数只输出该文件的路径；回归单测用哨兵取值固定这一点。
     *
     * 包装脚本只在**确实有秘密要投递**时加入：没有秘密的启动（探测、自检、
     * 无凭据终端）保持与修复前完全相同的 argv 形态，不引入任何新变量。
     */
    internal fun prootArgv(input: ProotArgvInput): List<String> = buildList {
        val secretsPath = input.secrets.guestEnvironmentFile
        add(input.runnerPath)
        add("-r")
        add(input.rootPath)
        add("-w")
        add("/root")
        input.bindMounts.forEach { mount ->
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
        // 安全校验点：来源是持久化枚举；env -i 后显式注入，父进程环境不能改变此选择。
        add("DSH_PERMISSION_MODE=${input.permissionMode}")
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        // 端口不是秘密：同 uid 的访客本来就能从 /proc/net/tcp 看到它。
        input.deviceBridgePort?.let { add("DSH_DEVICE_BRIDGE_PORT=$it") }
        input.harnessPidFilePath?.let { pidFilePath ->
            // pid 文件路径同样是路径而非取值；RuntimeSupervisor 用完整环境条目
            // 识别 PRoot 退出后被重新挂父进程的 Harness 子进程。
            add("DSH_PIDFILE=$pidFilePath")
            add("NODE_OPTIONS=--require=$MOBILE_AUTH_PRELOAD_GUEST_PATH")
        }
        if (input.secrets.isEmpty || secretsPath == null) {
            addAll(input.entrypoint)
        } else {
            // argv 里与秘密相关的全部内容就是这一个路径；取值在别处、在这个列表之外。
            add("/bin/sh")
            add("-c")
            add(GUEST_SECRET_SOURCE_SCRIPT)
            add(GUEST_ENTRYPOINT_ARGV0)
            add(secretsPath)
            addAll(input.entrypoint)
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

    private val SAFE_ABSOLUTE_PATH = Regex("^/[A-Za-z0-9._/-]+$")
    private const val MAX_ABSOLUTE_PATH_LENGTH = 4096
}
