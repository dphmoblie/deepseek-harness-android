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
 * 访客 profile 补丁文档（`/root/.dsh/profiles/web/cordis.patch.yml`）中 `llm-pi-ai` 条目的安全并写。
 *
 * ## 为什么把 `llm-pi-ai` 从 `--patch` 迁到这里
 *
 * dsh 的配置叠层顺序（`@deepseek-ai/dsh-app-boot` 的 `readProfilePatches`）为：
 *
 * ```text
 * bundle 补丁 < profiles/web/cordis.patch.yml < ~/.dsh/cordis.patch.yml < CLI --patch
 * ```
 *
 * 而 `dsh-config-editor`（官方 Web UI 的「模型」设置页）只读写 `profiles/web/cordis.patch.yml`，
 * 并在落盘前用整套叠层重算一次最终配置：只要 CLI `--patch` 里带了 `llm-pi-ai`，重算结果
 * 就会被命令行覆盖层整块顶掉，Web UI 直接抛出
 * `Configuration for "llm-pi-ai" is overridden by a home patch or command-line overlay` 并拒绝保存。
 *
 * 迁到 `profiles/web/cordis.patch.yml` 后：
 * - `--patch`（`launcher-providers.patch.json`）只保留 `sandbox` 运行器覆盖；
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

        val rowIndex = entries.indexOfLast { isLlmPiAiConfigRow(it) }
        val existingRow = if (rowIndex >= 0) deepCopyMap(entries[rowIndex]) else null

        val existingConfig: MutableMap<String, Any?> = when {
            existingRow == null || !existingRow.containsKey("config") || existingRow["config"] == null -> linkedMapOf()
            existingRow["config"] is Map<*, *> -> deepCopyMap(existingRow["config"] as Map<*, *>)
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

        val updatedEntries = entries.mapTo(mutableListOf()) { deepCopyMap(it) }
        if (existingProviders.isEmpty()) {
            if (rowIndex >= 0) {
                existingConfig.remove("providers")
                val row = existingRow ?: linkedMapOf()
                if (existingConfig.isEmpty()) {
                    row.remove("config")
                } else {
                    row["config"] = existingConfig
                }
                val onlyIdentity = row.keys.all { it == "id" || it == "name" }
                if (onlyIdentity) {
                    updatedEntries.removeAt(rowIndex)
                } else {
                    updatedEntries[rowIndex] = row
                }
            }
        } else {
            existingConfig["providers"] = existingProviders
            if (rowIndex >= 0) {
                val row = existingRow ?: linkedMapOf()
                row["config"] = existingConfig
                updatedEntries[rowIndex] = row
            } else {
                updatedEntries.add(
                    linkedMapOf(
                        "id" to LLM_PI_AI_ID,
                        "name" to LLM_PI_AI_NAME,
                        "config" to existingConfig,
                    ),
                )
            }
        }

        val rendered = renderEntries(updatedEntries)
        return MergeOutcome(
            yamlText = rendered,
            managedDigests = currentDigests,
            changed = true,
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
        val preservedRow = preservedEntries.lastOrNull { isLlmPiAiConfigRow(it) }
            ?: return PreservedMergeOutcome(yamlText = existingText ?: "", changed = false)

        val preservedConfig = when {
            !preservedRow.containsKey("config") || preservedRow["config"] == null ->
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
        val rowIndex = existingEntries.indexOfLast { isLlmPiAiConfigRow(it) }
        val updatedEntries = existingEntries.mapTo(mutableListOf()) { deepCopyMap(it) }

        if (rowIndex >= 0) {
            val row = updatedEntries[rowIndex]
            val existingConfig: MutableMap<String, Any?> = when {
                !row.containsKey("config") || row["config"] == null -> linkedMapOf()
                row["config"] is Map<*, *> -> deepCopyMap(row["config"] as Map<*, *>)
                else -> return PreservedMergeOutcome(yamlText = null, changed = false)
            }
            val mergedProviders: MutableMap<String, Any?> = when {
                !existingConfig.containsKey("providers") || existingConfig["providers"] == null -> linkedMapOf()
                existingConfig["providers"] is Map<*, *> -> deepCopyMap(existingConfig["providers"] as Map<*, *>)
                else -> return PreservedMergeOutcome(yamlText = null, changed = false)
            }
            for ((id, spec) in preservedProviders) {
                mergedProviders[id.toString()] = deepCopyValue(spec)
            }
            existingConfig["providers"] = mergedProviders
            row["config"] = existingConfig
        } else {
            updatedEntries += linkedMapOf(
                "id" to LLM_PI_AI_ID,
                "name" to LLM_PI_AI_NAME,
                "config" to deepCopyMap(preservedConfig),
            )
        }

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
        return name == null || name == LLM_PI_AI_NAME
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
