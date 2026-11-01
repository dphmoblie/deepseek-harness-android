package io.deepseekharness.mobile.runtime.diagnostics

import android.os.Build
import io.deepseekharness.mobile.runtime.runtimeDeviceFingerprintToken

/**
 * 设备与 ROM 的只读快照，只用于诊断日志**文件头**的那两行。
 *
 * 为什么需要：同一个报错在不同 ROM 上成因不同（厂商的存储压缩/应用管理会改写设备上的文件、
 * 系统版本决定可用的 API），而导出文件此前只有应用版本、SDK 与 ABI——收到日志也不知道
 * 是哪类设备报的。导出时机型与 ROM 构建串会改写为稳定摘要，仍可用于判断「这台设备属于哪一类 ROM」。
 *
 * 记录范围刻意收窄，只取 `Build` 里的公开常量：
 * 厂商、子品牌、型号、设备代号、ROM 构建串（`Build.DISPLAY`）、Android 版本、安全补丁级别；
 * 原始值只在应用私有内存中参与生成文件头，导出路径通过 [headerLines] 的 `redact` 参数改写摘要。
 * **不含**序列号、IMEI/MEID、Android ID、账号、广告标识或任何需要权限才能读到的标识，
 * 也不做任何隐藏 API 调用。这些常量对普通应用始终可读，不引入新的权限与失败路径。
 *
 * 取值会先经 [clean] 处理：控制字符与连续空白被压平、超长截断——ROM 自定义的构建串
 * 一旦带换行就会把文件头撕成两行，宁可少几个字符也不能让格式失控。
 */
internal class DiagnosticDevice private constructor(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val rom: String,
    val androidRelease: String,
    val securityPatch: String,
) {
    /**
     * 文件头里的「机型」「ROM」两行；某个字段读不到时整段略去，
     * 两个字段全空时返回空列表（宁可没有这两行，也不写「未知」）。
     */
    fun headerLines(redact: Boolean = false): List<String> {
        val safeManufacturer = if (redact) token(manufacturer) else manufacturer
        val safeBrand = if (redact) token(brand) else brand
        val safeModel = if (redact) token(model) else model
        val safeDevice = if (redact) token(device) else device
        val safeRom = if (redact) token(rom) else rom
        val maker = if (safeBrand.isEmpty() || safeBrand.equals(safeManufacturer, ignoreCase = true)) {
            safeManufacturer
        } else {
            listOf(safeManufacturer, safeBrand).filter { it.isNotEmpty() }.joinToString(" ")
        }
        val hardware = listOf(maker, safeModel).filter { it.isNotEmpty() }.joinToString(" ")
        val hardwareText = when {
            hardware.isEmpty() && safeDevice.isEmpty() -> null
            safeDevice.isEmpty() -> hardware
            hardware.isEmpty() -> "设备代号 $safeDevice"
            else -> "$hardware（设备代号 $safeDevice）"
        }
        val system = listOfNotNull(
            androidRelease.takeIf { it.isNotEmpty() }?.let { "Android $it" },
            securityPatch.takeIf { it.isNotEmpty() }?.let { "安全补丁 $it" },
        ).joinToString("，")
        val romText = when {
            safeRom.isEmpty() && system.isEmpty() -> null
            system.isEmpty() -> safeRom
            safeRom.isEmpty() -> system
            else -> "$safeRom（$system）"
        }
        return listOfNotNull(
            hardwareText?.let { "# 机型: $it" },
            romText?.let { "# ROM: $it" },
        )
    }

    companion object {
        /** 单个字段的最大保留字符数；ROM 构建串通常不到 30 个字符。 */
        const val MAX_FIELD_CHARS = 64

        private fun token(value: String): String {
            if (value.isEmpty()) return ""
            // Reuse the per-install Keystore HMAC used for local fingerprints. On
            // devices where Keystore is unavailable the helper falls back to SHA-256
            // so diagnostics remain available without exposing the raw value.
            return "匿名-" + runtimeDeviceFingerprintToken(value)
                .removePrefix("device-fp-v1:")
                .take(16)
        }

        fun of(
            manufacturer: String?,
            brand: String?,
            model: String?,
            device: String?,
            rom: String?,
            androidRelease: String?,
            securityPatch: String?,
        ): DiagnosticDevice = DiagnosticDevice(
            manufacturer = clean(manufacturer),
            brand = clean(brand),
            model = clean(model),
            device = clean(device),
            rom = clean(rom),
            androidRelease = clean(androidRelease),
            securityPatch = clean(securityPatch),
        )

        fun current(): DiagnosticDevice = of(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            rom = Build.DISPLAY,
            androidRelease = Build.VERSION.RELEASE,
            securityPatch = Build.VERSION.SECURITY_PATCH,
        )

        /** 压平控制字符与连续空白并截断；读不到或全是空白时返回空串。 */
        internal fun clean(raw: String?): String {
            val text = raw ?: return ""
            val builder = StringBuilder(text.length.coerceAtMost(MAX_FIELD_CHARS))
            var pendingSpace = false
            for (character in text) {
                if (character.isWhitespace() || character.isISOControl()) {
                    pendingSpace = builder.isNotEmpty()
                    continue
                }
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(character)
                if (builder.length >= MAX_FIELD_CHARS) break
            }
            return builder.toString()
        }
    }
}
