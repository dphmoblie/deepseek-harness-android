package io.deepseekharness.mobile.runtime

/**
 * 连接测试与模型目录请求共用的草稿端点选择规则。
 *
 * API Key 可以留空以复用已保存凭据，但 Base URL 与协议只要当前表单传了值（包括
 * 用户正在清空字段时的空字符串），就必须使用当前草稿；否则用户修改地址后点击测试
 * 仍会静默请求旧地址。
 */
internal fun draftOrSavedBaseUrl(draftBaseUrl: String?, savedProvider: CustomModelProvider?): String? =
    draftBaseUrl ?: savedProvider?.baseUrl

internal fun draftOrSavedApi(draftApi: String?, savedProvider: CustomModelProvider?): String? =
    draftApi ?: savedProvider?.api?.wireValue
