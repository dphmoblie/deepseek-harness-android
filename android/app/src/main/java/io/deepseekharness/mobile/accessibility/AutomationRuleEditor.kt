package io.deepseekharness.mobile.accessibility

import org.json.JSONObject

/**
 * 规则的新建 / 编辑 / 删除（读改写）。
 *
 * 存储层只有「整包替换」一个写入口（[AutomationRuleStore.savePackage]），所以这里负责
 * 「读出来 → 改一条 → 原子写回去」，并把「为什么没写成」翻译成受控码：桥接层只做参数提取、
 * 白名单与审计，界面只按码显示文案。
 *
 * 三条口径：
 * - **按 id 精确定位**：新建撞 id 与编辑/删除找不到 id 都拒，不做隐式覆盖。界面上 id 是规则的
 *   身份证（编辑时只读），改名等于换一条规则，不做跨条目的隐式搬迁。
 * - **包名必须一致**：草稿里的 `packageName` 与请求的包名不同即拒——「你以为写的是 A，其实写到 B」
 *   是这条路径上最不该发生的错（与桥接层「缺包名拒绝、不回落前台包」同一口径）。
 * - **校验只走一条**：规则内容交给 [AutomationRule.fromJson] 的严格解析（未知字段、类型、越界、
 *   选择器可空条件都在那里拒），这里不另写一套宽松校验，否则同一条规则会有两种「合法」。
 */
internal object AutomationRuleEditor {

    const val MODE_CREATE = "create"
    const val MODE_UPDATE = "update"
    val MODES = setOf(MODE_CREATE, MODE_UPDATE)

    /** 编辑侧的受控码；桥接层按它挑 `ACCESSIBILITY_CONFIG_*` 错误码。 */
    internal object Codes {
        const val MODE_INVALID = "AUTOMATION_RULE_MODE_INVALID"
        const val ID_CONFLICT = "AUTOMATION_RULE_ID_CONFLICT"
        const val NOT_FOUND = "AUTOMATION_RULE_NOT_FOUND"
        const val PACKAGE_MISMATCH = "AUTOMATION_RULE_PACKAGE_MISMATCH"
        const val LIMIT_REACHED = "AUTOMATION_RULE_LIMIT_REACHED"
        const val CORRUPT = "AUTOMATION_RULE_PACKAGE_CORRUPT"
        const val STORAGE_FAILED = "AUTOMATION_RULE_STORAGE_FAILED"
    }

    /**
     * 编辑结果。
     *
     * 成功时回**写完之后**的整包规则（已按 id 排序，与界面读数同一顺序）：界面据此刷新列表，
     * 不必再发一次读请求，也不会出现「写成功了但列表还是旧的」这种时间差。
     */
    internal sealed interface Outcome {
        data class Applied(val rules: List<AutomationRule>) : Outcome
        data class Rejected(val code: String, val message: String) : Outcome
    }

    /**
     * 单条规则的读取结果（编辑表单预填用）。
     *
     * 与 [Outcome] 分开：读没有「写成功」的语义，免得被误当成写路径的返回值。
     */
    internal sealed interface ReadOutcome {
        data class Found(val rule: AutomationRule) : ReadOutcome
        data class Rejected(val code: String, val message: String) : ReadOutcome
    }

    /**
     * 新建或整条替换一条规则。
     *
     * @param draft 完整规则 JSON（与 `AutomationRule.fromJson` 同一套字段）
     * @param mode [MODE_CREATE] 要求 id 不存在，[MODE_UPDATE] 要求 id 已存在
     */
    fun save(
        store: AutomationRulePackageStorage,
        packageName: String,
        draft: JSONObject,
        mode: String,
    ): Outcome {
        if (mode !in MODES) {
            return reject(Codes.MODE_INVALID, "规则的写入方式必须是 ${MODES.joinToString("/")}")
        }
        val rule = try {
            AutomationRule.fromJson(draft)
        } catch (failure: AutomationRuleException) {
            // 严格解析的码原样透出：界面能直接说清是哪个字段、为什么不行。
            return reject(failure.code, failure.message ?: "规则内容不合法")
        }
        if (rule.packageName != packageName) {
            return reject(Codes.PACKAGE_MISMATCH, "规则里的包名与请求的包名不一致")
        }
        val current = when (val read = AutomationRuleStore.readPackage(store, packageName)) {
            is AutomationRuleStore.PackageRead.Corrupt -> return reject(Codes.CORRUPT, read.message)
            is AutomationRuleStore.PackageRead.Ok -> read.rules
        }
        val existing = current.firstOrNull { it.id == rule.id }
        when (mode) {
            MODE_CREATE -> if (existing != null) {
                return reject(Codes.ID_CONFLICT, "已有同名规则：${rule.id}")
            }

            else -> if (existing == null) {
                return reject(Codes.NOT_FOUND, "找不到这条规则：${rule.id}")
            }
        }
        val next = if (existing == null) current + rule else current.map { if (it.id == rule.id) rule else it }
        return write(store, packageName, next)
    }

    /** 删除一条规则；删到空列表时存储层等价于把这个包整个删掉（既有口径）。 */
    fun delete(
        store: AutomationRulePackageStorage,
        packageName: String,
        ruleId: String,
    ): Outcome {
        val current = when (val read = AutomationRuleStore.readPackage(store, packageName)) {
            is AutomationRuleStore.PackageRead.Corrupt -> return reject(Codes.CORRUPT, read.message)
            is AutomationRuleStore.PackageRead.Ok -> read.rules
        }
        if (current.none { it.id == ruleId }) {
            return reject(Codes.NOT_FOUND, "找不到这条规则：$ruleId")
        }
        return write(store, packageName, current.filterNot { it.id == ruleId })
    }

    /**
     * 读一条**完整**规则（含选择器正文与动作参数）。
     *
     * 为什么编辑必须先读到全文：存储层的写入口是整包替换、[MODE_UPDATE] 又是**整条替换**，
     * 表单如果预填不出原来的选择器，用户点一次「保存」就等于把它们整片抹掉。列表读数
     * （`MobileRuntimePlugin.accessibilityRulesToJs`）刻意只带摘要，所以编辑走这一条。
     */
    fun find(
        store: AutomationRulePackageStorage,
        packageName: String,
        ruleId: String,
    ): ReadOutcome {
        val current = when (val read = AutomationRuleStore.readPackage(store, packageName)) {
            is AutomationRuleStore.PackageRead.Corrupt ->
                return ReadOutcome.Rejected(Codes.CORRUPT, read.message)

            is AutomationRuleStore.PackageRead.Ok -> read.rules
        }
        val rule = current.firstOrNull { it.id == ruleId }
            ?: return ReadOutcome.Rejected(Codes.NOT_FOUND, "找不到这条规则：$ruleId")
        return ReadOutcome.Found(rule)
    }

    /**
     * 整包原子写。
     *
     * 条数上限（[AutomationRuleLimits.MAX_RULES_PER_PACKAGE]）与两类字节上限由存储层在写入时判定，
     * 这里不预先算一遍：限额是**拒绝**而不是截断，谁先算谁说了算，两处各算一次只会有一天不一致。
     * 也因此，写失败时把限额异常单独说成「超出上限」，其余异常只说「写入失败」——不猜原因。
     */
    private fun write(
        store: AutomationRulePackageStorage,
        packageName: String,
        rules: List<AutomationRule>,
    ): Outcome = try {
        AutomationRuleStore.savePackage(store, packageName, rules)
        Outcome.Applied(rules.sortedBy { it.id })
    } catch (failure: AutomationRuleException) {
        reject(Codes.LIMIT_REACHED, failure.message ?: "规则超出存储上限")
    } catch (_: Throwable) {
        reject(Codes.STORAGE_FAILED, "规则写入失败")
    }

    private fun reject(code: String, message: String): Outcome.Rejected = Outcome.Rejected(code, message)
}
