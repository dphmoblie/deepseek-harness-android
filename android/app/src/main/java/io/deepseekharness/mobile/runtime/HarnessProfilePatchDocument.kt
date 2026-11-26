package io.deepseekharness.mobile.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.ConstructNode
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.RepresentToNode
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.representer.StandardRepresenter
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * 访客 profile 补丁文档（`/root/.dsh/profiles/web/cordis.patch.yml`）中 `llm-pi-ai` 条目的安全并写，
 * 以及把全局 home 补丁（`/root/.dsh/cordis.patch.yml`）里的 `llm-pi-ai.config` 迁入 profile 补丁。
 *
 * ## 为什么把 `llm-pi-ai` 从 `--patch` 与 home 补丁迁到这里
 *
 * dsh 的配置叠层顺序（`@deepseek-ai/dsh-app-boot` 的 `readProfilePatches`）为：
 *
 * ```text
 * bundle 补丁 < profiles/web/cordis.patch.yml < ~/.dsh/cordis.patch.yml < CLI --patch
 * ```
 *
 * 而 `dsh-config-editor`（官方 Web UI 的「模型」设置页）只读写 `profiles/web/cordis.patch.yml`，
 * 并在落盘前用整套叠层重算一次最终配置：只要 `~/.dsh/cordis.patch.yml`（home patch）或 CLI `--patch`
 * 里带了 `llm-pi-ai` 的 `config` 覆盖，重算结果就会把 profile 层的修改整块顶掉，Web UI 既会把供应商字段
 * 仅当作占位符显示，又会在保存时直接抛出
 * `Configuration for "llm-pi-ai" is overridden by a home patch or command-line overlay` 并拒绝保存。
 *
 * 迁到 `profiles/web/cordis.patch.yml` 后：
 * - `--patch`（`launcher-providers.patch.json`）只保留 `sandbox` 运行器覆盖；
 * - `~/.dsh/cordis.patch.yml` 若遗留了 `llm-pi-ai.config`（手动编写、AI 代写或桌面迁移），启动前会先
 *   无损迁入 `profiles/web/cordis.patch.yml`，再从 home 补丁仅剥离 `llm-pi-ai` 的 `config` 键（保留其它条目、
 *   `disabled` 开关与 `!!js` 标签）；
 * - App 配置的内置/自定义供应商与官方 Web UI 共用同一层文档，Web UI 可以自由新建、修改供应商
 *   与模型输入模态（图片识别）、推理等级、compat 等配置；
 * - 通过逐供应商 SHA-256 摘要追踪 App 管理的条目：App 侧未改动时**绝不覆盖**用户在 Web UI 里的编辑，
 *   App 侧改动时只合并 App 管理的字段并保留 Web UI 额外字段，App 侧删除时只移除 App 托管的供应商、
 *   不碰用户在 Web UI 自建的供应商。
 *
 * 本对象是纯逻辑（不触碰文件系统、不依赖 Android API），便于在 JVM 单测中覆盖所有合并边界。
 */
internal object HarnessProfilePatchDocument {
    const val MAX_DOCUMENT_BYTES = 512 * 1024
    const val LLM_PI_AI_ID = "llm-pi-ai"
    const val LLM_PI_AI_NAME = "@deepseek-ai/dsh-llm-pi-ai"
    const val PROFILE_PATCH_RELATIVE_PATH = "root/.dsh/profiles/web/cordis.patch.yml"
    const val HOME_PATCH_RELATIVE_PATH = "root/.dsh/cordis.patch.yml"

    private val JS_TAG = Tag("tag:yaml.org,2002:js")

    private data class JsExpression(val source: String)

    private object ConstructJsExpression : ConstructNode {
        override fun construct(node: Node): Any {
            val scalar = node as? ScalarNode
                ?: throw IllegalArgumentException("!!js tag must be a scalar")
            return JsExpression(scalar.value)
        }
    }

    private class JsAwareRepresenter(settings: DumpSettings) : StandardRepresenter(settings) {
        init {
            representers[JsExpression::class.java] = RepresentToNode { data ->
                val expr = data as JsExpression
                ScalarNode(JS_TAG, expr.source, ScalarStyle.DOUBLE_QUOTED)
            }
        }
    }

    private val LOAD_SETTINGS: LoadSettings = LoadSettings.builder()
        .setLabel("Harness profile patch")
        .setAllowDuplicateKeys(false)
        .setMaxAliasesForCollections(0)
        .setCodePointLimit(MAX_DOCUMENT_BYTES)
        .setTagConstructors(mapOf(JS_TAG to ConstructJsExpression))
        .build()

    private val DUMP_SETTINGS: DumpSettings = DumpSettings.builder()
        .setDefaultFlowStyle(FlowStyle.BLOCK)
        .setIndent(2)
        .setIndicatorIndent(0)
        .setWidth(120)
        .build()

    /**
     * 合并结果。
     *
     * @property yamlText 合并后的完整 YAML 文本；**原文损坏或不是合法补丁数组时为 `null`**（失败关闭，不覆盖磁盘）。
     * @property managedDigests 本次同步后 App 托管的供应商摘要表（`providerId -> sha256`）。
     * @property changed 磁盘内容是否确实发生变化；为 `false` 时调用方无需重写文件。
     */
    data class MergeOutcome(
        val yamlText: String?,
        val managedDigests: Map<String, String>,
        val changed: Boolean,
    )

    /**
     * 运行时升级迁移旧 profile 中 `llm-pi-ai` 模型配置的合并结果。
     *
     * @property yamlText 合并后的完整 YAML 文本；**任一文档损坏或结构非法时为 `null`**（失败关闭）。
     * @property changed 目标文档是否发生变化；为 `false` 时调用方无需写入文件。
     */
    data class PreservedMergeOutcome(
        val yamlText: String?,
        val changed: Boolean,
    )

    /**
     * 将 `$DSH_HOME/cordis.patch.yml`（home patch）中的 `llm-pi-ai.config` 迁入
     * `profiles/web/cordis.patch.yml`（profile patch）的合并结果。
     *
     * @property profileYamlText 迁入后的 profile 补丁全文；**任一文档损坏或结构非法时为 `null`**（失败关闭）。
     * @property homeYamlText 剥离 `llm-pi-ai.config` 后的 home 补丁全文；失败关闭时为 `null`。
     * @property profileChanged profile 补丁是否需要落盘。
     * @property homeChanged home 补丁是否需要落盘。
     */
    data class HomeMigrationOutcome(
        val profileYamlText: String?,
        val homeYamlText: String?,
        val profileChanged: Boolean,
        val homeChanged: Boolean,
    )

    /**
     * 按 App 设置构建待同步的 `llm-pi-ai.config.providers` 映射。
     */
    fun buildAppProviders(
        enabledBuiltIn: List<ModelProvider>,
        customProviders: List<CustomModelProvider>,
    ): Map<String, Map<String, Any>> {
        val providers = linkedMapOf<String, Map<String, Any>>()
        enabledBuiltIn.forEach { provider ->
            providers[provider.wireValue] = linkedMapOf("apiKeyEnv" to provider.environmentVariable)
        }
        customProviders.forEach { provider ->
            val models = provider.models.map { model ->
                linkedMapOf<String, Any>(
                    "id" to model.id,
                    "name" to model.name,
                    "contextWindow" to model.contextWindow,
                    "maxTokens" to model.maxTokens,
                ).apply {
                    model.input?.let { put("input", it.toList()) }
                }
            }
            providers[provider.id] = linkedMapOf(
                "apiKeyEnv" to provider.environmentVariable,
                "displayName" to provider.name,
                "api" to provider.api.wireValue,
                "baseURL" to provider.baseUrl,
                "models" to models,
            )
        }
        return providers
    }

    /**
     * 把 App 配置的 `llm-pi-ai` 供应商非破坏性地并入 `profiles/web/cordis.patch.yml`。
     */
    fun mergeLlmPiAi(
        existingText: String?,
        appProviders: Map<String, Map<String, Any>>,
        previousDigests: Map<String, String>,
    ): MergeOutcome {
        val currentDigests = linkedMapOf<String, String>()
        for ((id, spec) in appProviders) {
            currentDigests[id] = digestOf(spec)
        }

        if (existingText == null && appProviders.isEmpty() && previousDigests.isEmpty()) {
            return MergeOutcome(yamlText = "", managedDigests = emptyMap(), changed = false)
        }

        val entries = parseEntries(existingText)
            ?: return MergeOutcome(yamlText = null, managedDigests = previousDigests, changed = false)

        val configRowIndex = entries.indexOfLast { isLlmPiAiConfigRow(it) && it.containsKey("config") }
        val existingConfigRow = if (configRowIndex >= 0) deepCopyMap(entries[configRowIndex]) else null

        val existingConfig: MutableMap<String, Any?> = when {
            existingConfigRow == null || existingConfigRow["config"] == null -> linkedMapOf()
            existingConfigRow["config"] is Map<*, *> -> deepCopyMap(existingConfigRow["config"] as Map<*, *>)
            else -> return MergeOutcome(yamlText = null, managedDigests = previousDigests, changed = false)
        }

        val existingProviders: MutableMap<String, Any?> = when {
            !existingConfig.containsKey("providers") || existingConfig["providers"] == null -> linkedMapOf()
            existingConfig["providers"] is Map<*, *> -> deepCopyMap(existingConfig["providers"] as Map<*, *>)
            else -> return MergeOutcome(yamlText = null, managedDigests = previousDigests, changed = false)
        }

        var mutated = false

        // 1. 移除此前由 App 托管、现在已在 App 中删除或清密钥的供应商；不碰 Web UI 自建供应商。
        for (oldId in previousDigests.keys) {
            if (oldId !in appProviders && existingProviders.remove(oldId) != null) {
                mutated = true
            }
        }

        // 2. 写入新增或 App 侧发生变更的供应商；App 未改动且已存在时保留用户在 Web UI 内的修改。
        for ((id, appSpec) in appProviders) {
            val digest = currentDigests.getValue(id)
            val existingSpec = existingProviders[id]
            if (existingSpec != null && previousDigests[id] == digest) {
                continue
            }
            val merged = mergeProviderSpec(existingSpec as? Map<*, *>, appSpec)
            if (existingSpec != merged) {
                existingProviders[id] = merged
                mutated = true
            }
        }

        if (!mutated) {
            return MergeOutcome(
                yamlText = existingText ?: "",
                managedDigests = currentDigests,
                changed = false,
            )
        }

        val updatedEntries = if (existingProviders.isEmpty()) {
            existingConfig.remove("providers")
            if (existingConfig.isEmpty()) {
                stripAllLlmPiAiConfigs(entries)
            } else {
                upsertLlmPiAiConfig(entries, existingConfig)
            }
        } else {
            existingConfig["providers"] = existingProviders
            upsertLlmPiAiConfig(entries, existingConfig)
        }

        val rendered = renderEntries(updatedEntries)
        return MergeOutcome(
            yamlText = rendered,
            managedDigests = currentDigests,
            changed = true,
        )
    }

    /**
     * 快速判定给定补丁文本是否包含会覆盖 `llm-pi-ai` 配置的条目（含 `config: null` / `config: {}`）。
     * 文档损坏或非数组时返回 `false`（失败关闭）。
     */
    fun hasLlmPiAiConfig(text: String?): Boolean {
        val entries = parseEntries(text) ?: return false
        return entries.any { isLlmPiAiConfigRow(it) && it.containsKey("config") }
    }

    /**
     * 将 `$DSH_HOME/cordis.patch.yml`（home patch）中的 `llm-pi-ai.config` 无损迁入
     * `profiles/web/cordis.patch.yml`（profile patch），并从 home 补丁剥离所有 `llm-pi-ai` 的 `config` 键。
     *
     * 在 dsh 叠层语义里 home patch 优先级高于 profile patch，因此同名供应商以 home patch 为准，
     * 同时保留 profile patch 里不冲突的供应商与兄弟条目，以及 home patch 里的非 `llm-pi-ai` 条目、
     * `disabled` 开关和 `!!js` 标签。即便 home patch 只写了 `config: null` 或 `config: {}`，
     * 在 dsh 合并语义里仍会整块顶掉 profile 配置并触发覆盖锁定，因此也必须剥离。
     */
    fun migrateHomeLlmPiAi(
        profileText: String?,
        homeText: String?,
    ): HomeMigrationOutcome {
        if (homeText.isNullOrBlank()) {
            return HomeMigrationOutcome(
                profileYamlText = profileText ?: "",
                homeYamlText = homeText ?: "",
                profileChanged = false,
                homeChanged = false,
            )
        }
        val homeEntries = parseEntries(homeText)
            ?: return HomeMigrationOutcome(null, null, profileChanged = false, homeChanged = false)
        val effectiveHomeRow = homeEntries.lastOrNull { isLlmPiAiConfigRow(it) && it.containsKey("config") }
            ?: return HomeMigrationOutcome(
                profileYamlText = profileText ?: "",
                homeYamlText = homeText,
                profileChanged = false,
                homeChanged = false,
            )

        val homeConfigRaw = effectiveHomeRow["config"]
        val homeConfig: Map<*, *>? = when (homeConfigRaw) {
            null -> null
            is Map<*, *> -> homeConfigRaw
            else -> return HomeMigrationOutcome(null, null, profileChanged = false, homeChanged = false)
        }
        val homeProvidersRaw = homeConfig?.get("providers")
        val homeProviders: Map<*, *>? = when (homeProvidersRaw) {
            null -> null
            is Map<*, *> -> homeProvidersRaw
            else -> return HomeMigrationOutcome(null, null, profileChanged = false, homeChanged = false)
        }

        val hasNonProviderHomeKeys = homeConfig?.keys?.any { it != "providers" } == true
        val hasHomePayload = hasNonProviderHomeKeys || (homeProviders != null && homeProviders.isNotEmpty())

        var renderedProfile = profileText ?: ""
        var profileChanged = false
        if (hasHomePayload) {
            val profileEntries = parseEntries(profileText)
                ?: return HomeMigrationOutcome(null, null, profileChanged = false, homeChanged = false)
            val profileConfigIndex = profileEntries.indexOfLast { isLlmPiAiConfigRow(it) && it.containsKey("config") }
            val mergedConfig: MutableMap<String, Any?> = when {
                profileConfigIndex < 0 || profileEntries[profileConfigIndex]["config"] == null -> linkedMapOf()
                profileEntries[profileConfigIndex]["config"] is Map<*, *> ->
                    deepCopyMap(profileEntries[profileConfigIndex]["config"] as Map<*, *>)
                else -> return HomeMigrationOutcome(null, null, profileChanged = false, homeChanged = false)
            }

            for ((key, value) in homeConfig!!) {
                val keyName = key.toString()
                if (keyName == "providers") continue
                mergedConfig[keyName] = deepCopyValue(value)
            }
            if (homeProviders != null && homeProviders.isNotEmpty()) {
                val mergedProviders: MutableMap<String, Any?> = when {
                    !mergedConfig.containsKey("providers") || mergedConfig["providers"] == null -> linkedMapOf()
                    mergedConfig["providers"] is Map<*, *> -> deepCopyMap(mergedConfig["providers"] as Map<*, *>)
                    else -> return HomeMigrationOutcome(null, null, profileChanged = false, homeChanged = false)
                }
                for ((id, spec) in homeProviders) {
                    mergedProviders[id.toString()] = deepCopyValue(spec)
                }
                mergedConfig["providers"] = mergedProviders
            }

            val updatedProfileEntries = upsertLlmPiAiConfig(profileEntries, mergedConfig)
            renderedProfile = renderEntries(updatedProfileEntries)
            profileChanged = renderedProfile != (profileText ?: "")
        }

        val updatedHomeEntries = stripAllLlmPiAiConfigs(homeEntries)
        val renderedHome = renderEntries(updatedHomeEntries)
        return HomeMigrationOutcome(
            profileYamlText = renderedProfile,
            homeYamlText = renderedHome,
            profileChanged = profileChanged,
            homeChanged = true,
        )
    }

    /**
     * 把旧运行时里由 Harness Web / App 保存的 `llm-pi-ai` 供应商配置迁移到新 profile。
     *
     * 运行时升级会重建 `profiles/web`（避免携带指向旧 rootfs 的符号链接），因此只迁移
     * `llm-pi-ai.config.providers`，保留新版本的其它 profile 条目与默认供应商。
     */
    fun mergePreservedLlmPiAi(
        existingText: String?,
        preservedText: String?,
    ): PreservedMergeOutcome {
        val preservedEntries = parseEntries(preservedText)
            ?: return PreservedMergeOutcome(yamlText = null, changed = false)
        val preservedRow = preservedEntries.lastOrNull { isLlmPiAiConfigRow(it) && it.containsKey("config") }
            ?: return PreservedMergeOutcome(yamlText = existingText ?: "", changed = false)

        val preservedConfig = when {
            preservedRow["config"] == null ->
                return PreservedMergeOutcome(yamlText = existingText ?: "", changed = false)
            preservedRow["config"] is Map<*, *> -> preservedRow["config"] as Map<*, *>
            else -> return PreservedMergeOutcome(yamlText = null, changed = false)
        }
        val preservedProviders = when {
            !preservedConfig.containsKey("providers") || preservedConfig["providers"] == null ->
                return PreservedMergeOutcome(yamlText = existingText ?: "", changed = false)
            preservedConfig["providers"] is Map<*, *> -> preservedConfig["providers"] as Map<*, *>
            else -> return PreservedMergeOutcome(yamlText = null, changed = false)
        }
        if (preservedProviders.isEmpty()) {
            return PreservedMergeOutcome(yamlText = existingText ?: "", changed = false)
        }

        val existingEntries = parseEntries(existingText)
            ?: return PreservedMergeOutcome(yamlText = null, changed = false)
        val existingConfigIndex = existingEntries.indexOfLast { isLlmPiAiConfigRow(it) && it.containsKey("config") }

        val mergedConfig: MutableMap<String, Any?> = if (existingConfigIndex >= 0) {
            val row = existingEntries[existingConfigIndex]
            val baseConfig: MutableMap<String, Any?> = when {
                row["config"] == null -> linkedMapOf()
                row["config"] is Map<*, *> -> deepCopyMap(row["config"] as Map<*, *>)
                else -> return PreservedMergeOutcome(yamlText = null, changed = false)
            }
            val mergedProviders: MutableMap<String, Any?> = when {
                !baseConfig.containsKey("providers") || baseConfig["providers"] == null -> linkedMapOf()
                baseConfig["providers"] is Map<*, *> -> deepCopyMap(baseConfig["providers"] as Map<*, *>)
                else -> return PreservedMergeOutcome(yamlText = null, changed = false)
            }
            for ((id, spec) in preservedProviders) {
                mergedProviders[id.toString()] = deepCopyValue(spec)
            }
            baseConfig["providers"] = mergedProviders
            baseConfig
        } else {
            deepCopyMap(preservedConfig)
        }

        val updatedEntries = upsertLlmPiAiConfig(existingEntries, mergedConfig)
        val rendered = renderEntries(updatedEntries)
        return PreservedMergeOutcome(yamlText = rendered, changed = rendered != (existingText ?: ""))
    }

    private fun parseEntries(text: String?): List<Map<*, *>>? {
        if (text.isNullOrBlank()) return emptyList()
        val parsed = try {
            Load(LOAD_SETTINGS).loadFromInputStream(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            return null
        } ?: return emptyList()
        val list = parsed as? List<*> ?: return null
        val rows = ArrayList<Map<*, *>>(list.size)
        for (item in list) {
            val map = item as? Map<*, *> ?: return null
            if (map.keys.any { it !is String }) return null
            rows.add(map)
        }
        return rows
    }

    private fun isLlmPiAiConfigRow(row: Map<*, *>): Boolean {
        if (row["id"] != LLM_PI_AI_ID) return false
        if (row.containsKey("insert")) return false
        val name = row["name"]
        return name == null || name == "" || name == LLM_PI_AI_NAME
    }

    /**
     * 将 `config` 写入最后一个匹配的 `llm-pi-ai` 行（不存在则追加），同时清理更早匹配行上的
     * 冗余 `config`，避免 `dsh-config-editor` 编辑或重置时被隐藏旧行回流干扰。
     */
    private fun upsertLlmPiAiConfig(
        entries: List<Map<*, *>>,
        config: MutableMap<String, Any?>,
    ): MutableList<Map<String, Any?>> {
        val targetIndex = entries.indexOfLast { isLlmPiAiConfigRow(it) }
        val updated = mutableListOf<Map<String, Any?>>()
        if (targetIndex < 0) {
            for (row in entries) updated.add(deepCopyMap(row))
            updated.add(
                linkedMapOf(
                    "id" to LLM_PI_AI_ID,
                    "name" to LLM_PI_AI_NAME,
                    "config" to config,
                ),
            )
            return updated
        }
        for (index in entries.indices) {
            val row = deepCopyMap(entries[index])
            if (index == targetIndex) {
                if (row["name"] == "") {
                    row["name"] = LLM_PI_AI_NAME
                }
                row["config"] = config
                updated.add(row)
            } else if (isLlmPiAiConfigRow(row) && row.containsKey("config")) {
                row.remove("config")
                val onlyIdentity = row.keys.all { it == "id" || it == "name" }
                if (!onlyIdentity) {
                    updated.add(row)
                }
            } else {
                updated.add(row)
            }
        }
        return updated
    }

    /**
     * 剥离所有匹配 `llm-pi-ai` 行上的 `config` 键；若该行只剩 `id`/`name` 标识键则整行移除，
     * 保留 `disabled` 等其它属性与所有非 `llm-pi-ai` 行。
     */
    private fun stripAllLlmPiAiConfigs(entries: List<Map<*, *>>): MutableList<Map<String, Any?>> {
        val updated = mutableListOf<Map<String, Any?>>()
        for (source in entries) {
            if (isLlmPiAiConfigRow(source) && source.containsKey("config")) {
                val row = deepCopyMap(source)
                row.remove("config")
                val onlyIdentity = row.keys.all { it == "id" || it == "name" }
                if (!onlyIdentity) {
                    updated.add(row)
                }
            } else {
                updated.add(deepCopyMap(source))
            }
        }
        return updated
    }

    private fun mergeProviderSpec(
        existing: Map<*, *>?,
        appSpec: Map<String, Any>,
    ): MutableMap<String, Any?> {
        val merged = if (existing != null) deepCopyMap(existing) else linkedMapOf()
        val existingModelsById = linkedMapOf<String, Map<*, *>>()
        val existingModels = existing?.get("models") as? List<*>
        existingModels?.forEach { item ->
            val modelMap = item as? Map<*, *> ?: return@forEach
            val id = modelMap["id"] as? String ?: return@forEach
            existingModelsById[id] = modelMap
        }

        for ((key, value) in appSpec) {
            if (key == "models" && value is List<*>) {
                val mergedModels = value.map { rawModel ->
                    @Suppress("UNCHECKED_CAST")
                    val appModel = rawModel as Map<String, Any>
                    val modelId = appModel["id"] as String
                    val prev = existingModelsById[modelId]
                    val target = if (prev != null) deepCopyMap(prev) else linkedMapOf()
                    for ((modelKey, modelVal) in appModel) {
                        target[modelKey] = deepCopyValue(modelVal)
                    }
                    target
                }
                merged["models"] = mergedModels
            } else {
                merged[key] = deepCopyValue(value)
            }
        }
        return merged
    }

    private fun renderEntries(entries: List<Map<String, Any?>>): String {
        if (entries.isEmpty()) return "[]\n"
        val dump = Dump(DUMP_SETTINGS, JsAwareRepresenter(DUMP_SETTINGS))
        val text = dump.dumpToString(entries)
        return if (text.endsWith('\n')) text else "$text\n"
    }

    private fun deepCopyMap(source: Map<*, *>): MutableMap<String, Any?> {
        val copy = linkedMapOf<String, Any?>()
        for ((k, v) in source) {
            copy[k.toString()] = deepCopyValue(v)
        }
        return copy
    }

    private fun deepCopyValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> deepCopyMap(value)
        is List<*> -> value.mapTo(mutableListOf()) { deepCopyValue(it) }
        else -> value
    }

    private fun digestOf(spec: Map<String, Any>): String {
        val canonical = toJsonValue(spec).toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
        return buildString(digest.size * 2) {
            for (byte in digest) {
                append(String.format("%02x", byte.toInt() and 0xff))
            }
        }
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj ->
            value.entries
                .sortedBy { it.key.toString() }
                .forEach { (k, v) -> obj.put(k.toString(), toJsonValue(v)) }
        }
        is List<*> -> JSONArray().also { arr ->
            value.forEach { item -> arr.put(toJsonValue(item)) }
        }
        else -> value
    }
}
