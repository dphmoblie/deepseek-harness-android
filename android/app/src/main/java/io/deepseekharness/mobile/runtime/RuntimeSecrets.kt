package io.deepseekharness.mobile.runtime

/**
 * 一次启动需要投递给访客的秘密，以及它们**唯一允许的落地形态**。
 *
 * 背景（真机缺陷 P0-6）：修复前 App 把 `<PROVIDER>_API_KEY=<明文>`、
 * `DSH_DEVICE_BRIDGE_TOKEN=<明文>`、`DSH_MOBILE_AUTH_TOKEN=<明文>` 逐个作为 **argv 元素**
 * 追加到 PRoot 的命令行，于是同 uid 的任何访客进程、普通 `ps`、以及 bugreport / ANR /
 * 厂商诊断采集都能读到取值，且取值在整个进程生命周期驻留命令行。
 *
 * 现在「取值」与「命令行」被彻底分开：
 *  - [environmentValues] 只允许写进访客内的 0600 环境文件；
 *  - [guestEnvironmentFile] 是 argv 里唯一允许出现的**路径**（本身不含任何取值）。
 *
 * 这条不变量由 [RuntimeCommand.prootArgv] 的纯函数重载 + `RuntimeCommandSecretTest` 的
 * 哨兵断言共同守住：构造 argv 的代码路径**拿得到**取值（所以测试不是空转），但只输出路径。
 */
internal data class RuntimeSecretDelivery(
    /** 访客内 0600 环境文件路径；null 表示本次启动没有需要经环境投递的秘密。 */
    val guestEnvironmentFile: String? = null,
    /** 将要写进该文件的键值对（含取值）。**绝不进入 argv。** */
    val environmentValues: Map<String, String> = emptyMap(),
    /**
     * 本次启动投递的模型凭据**条数**（只记数量，不含变量名与取值）。
     * 用途与修复前一致：导出诊断日志后即可区分「App 没有可投递的凭据」与「dsh 侧没有用上」。
     */
    val modelCredentialCount: Int = 0,
) {
    init {
        // 结构性不变量：有取值就必须有落点，且落点只能是那个固定路径。
        // 少了这一条，「有取值但没有路径」的投递会被 argv 静默忽略 ——
        // 那正是「秘密悄悄没送达」的最坏形态：dsh 起来了，但认证与凭据全缺。
        if (environmentValues.isNotEmpty() && guestEnvironmentFile != RuntimeCommand.GUEST_SECRET_ENV_PATH) {
            throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "运行时凭据投递描述无效")
        }
    }

    /** 本次启动是否有需要经访客环境投递的秘密。 */
    val isEmpty: Boolean get() = environmentValues.isEmpty()

    companion object {
        /** 本次启动没有任何秘密需要投递（自检、探测、以及无凭据的终端）。 */
        val NONE = RuntimeSecretDelivery()
    }
}

/**
 * 秘密投递的**纯策略**：路由决策、取值语法校验与环境文件渲染。
 *
 * 不接触文件系统、不依赖 Android API，因此可以在 JVM 单测里穷举。
 * 真正的文件写入由 [RuntimeStore.prepareRuntimeSecrets] 负责，它把本对象的决策落成 0600 文件。
 */
internal object RuntimeSecretPolicy {
    /**
     * 环境变量名的文法，与 dsh 的 credential 引用文法一致（POSIX 标识符）。
     *
     * dsh 侧对 `refs` 键的校验是 `^[A-Za-z_][A-Za-z0-9_]*$`（见
     * `@deepseek-ai/dsh-credentials-local` 的 `credentialRef`），两侧必须一致：
     * 名字只有同时是合法环境变量名与合法引用名，才能既写进环境文件、又写进凭据文档。
     */
    private val NAME_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /**
     * 环境变量取值的文法：可打印 ASCII、不含空格与控制字符。
     *
     * 与既有校验对齐，不新增限制：
     *  - 模型凭据经 `RuntimeValidation.requireProviderApiKey`，已经是 `[\x21-\x7E]{1,200}`；
     *  - 两个临时令牌是 43 位 `[A-Za-z0-9_-]`（[TOKEN_PATTERN]）。
     * 取值越界意味着上游校验被绕过，此时**宁可让本次启动失败**，也不写出一个自己都无法安全引用的文件。
     */
    private val VALUE_PATTERN = Regex("^[\\x21-\\x7E]{1,200}$")

    /** Harness 临时 Basic 凭据与设备桥令牌的形态（43 位 URL 安全 base64 字符）。 */
    val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")

    /**
     * 是否是合法的凭据引用名（`^[A-Za-z_][A-Za-z0-9_]*$`）。
     *
     * 与 dsh 的 `credentialRef` 文法一致：同一个名字既要能当环境变量名，也要能当
     * `~/.dsh/.credentials.yaml` 里 `refs` 的键，两侧文法必须逐字相同。
     */
    fun isCredentialReferenceName(value: String): Boolean = NAME_PATTERN.matches(value)

    /**
     * 校验并原样返回取值表；任何不满足 [NAME_PATTERN] / [VALUE_PATTERN] 的条目都让启动失败。
     *
     * 为什么不是「跳过非法条目」：跳过会让 dsh 在缺凭据的情况下启动，
     * 用户看到的是模型调用失败而不是「凭据没送达」，把配置故障伪装成运行故障。
     */
    fun requireSafe(values: Map<String, String>): Map<String, String> {
        values.forEach { (name, value) ->
            if (!NAME_PATTERN.matches(name) || !VALUE_PATTERN.matches(value)) {
                throw RuntimeFailure("RUNTIME_CONFIG_FAILED", "运行时凭据形态无效")
            }
        }
        return values
    }

    /**
     * 秘密的路由决策：哪些条目必须经访客环境投递。
     *
     * @param modelCredentials 模型凭据（引用名 → 取值，即 `<PROVIDER>_API_KEY`）
     * @param ephemeralSecrets 每次启动生成、只存在于内存与访客进程内的临时令牌
     * @param modelCredentialsViaFile 模型凭据是否已由 `~/.dsh/.credentials.yaml` 承载
     *
     * 关键理由（来自 pinned dsh 的实际分层）：
     * `@deepseek-ai/dsh-credentials-local` 的解析顺序是
     * `继承的进程环境（只读，优先） > $DSH_HOME/.credentials.yaml（可写） > .env`。
     * 环境层**遮蔽**凭据文件，所以模型凭据一旦落进环境文件就永远轮不到凭据文件生效 ——
     * 因此只有凭据文件不可用时（[modelCredentialsViaFile] 为 false）才把它放进环境文件。
     */
    fun route(
        modelCredentials: Map<String, String>,
        ephemeralSecrets: Map<String, String>,
        modelCredentialsViaFile: Boolean,
    ): Map<String, String> = linkedMapOf<String, String>().apply {
        putAll(ephemeralSecrets)
        if (!modelCredentialsViaFile) putAll(modelCredentials)
    }

    /**
     * 把路由结果包装成投递描述：没有取值时**不产生文件**，因此也不需要那层 `sh` 包装。
     */
    fun delivery(environmentValues: Map<String, String>, modelCredentialCount: Int): RuntimeSecretDelivery =
        if (environmentValues.isEmpty()) {
            RuntimeSecretDelivery(modelCredentialCount = modelCredentialCount)
        } else {
            RuntimeSecretDelivery(
                guestEnvironmentFile = RuntimeCommand.GUEST_SECRET_ENV_PATH,
                environmentValues = requireSafe(environmentValues),
                modelCredentialCount = modelCredentialCount,
            )
        }

    /**
     * 渲染访客侧的 0600 环境文件。
     *
     * 形态是 POSIX shell 的 `NAME='value'` 赋值：入口包装脚本用 `set -a; . 文件` 载入。
     * 单引号里除 `'` 之外没有任何转义处理，所以取值只需把 `'` 写成 `'\''`；
     * 再叠加 [VALUE_PATTERN] 对控制字符的拒绝，渲染结果不可能跨行或注入命令。
     */
    fun renderEnvironmentFile(values: Map<String, String>): String = buildString {
        append(ENVIRONMENT_FILE_HEADER)
        append('\n')
        requireSafe(values).forEach { (name, value) ->
            append(name)
            append('=')
            append(quoteForShell(value))
            append('\n')
        }
    }

    /** `'value'`，其中 `'` 写作 `'\''`（关闭引号 + 转义引号 + 重新开引号）。 */
    private fun quoteForShell(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 头部注释不参与赋值，只说明这份文件的来历与生命周期。
     * 刻意保持 ASCII：它会被 `sh` 读入，不引入任何编码相关的不确定性。
     */
    private const val ENVIRONMENT_FILE_HEADER =
        "# dsh-mobile runtime secrets; generated per launch, mode 0600, removed when the runtime stops"
}
