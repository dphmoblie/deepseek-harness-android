package io.deepseekharness.mobile.runtime

/**
 * 连接测试与模型目录请求共用的草稿端点选择规则。
 *
 * API Key 可以留空以复用已保存凭据，但 Base URL 与协议只要当前表单传了值（包括
 * 用户正在清空字段时的空字符串），就必须使用当前草稿；否则用户修改地址后点击测试
 * 仍会静默请求旧地址。
 *
 * 草稿值在这里**去掉首尾空白**：桥接层目前也会 trim，但这条规则不该依赖调用方记得做——
 * 少一个 trim，带空白的地址就会在 `URL` 解析或 `startsWith` 判定上出现「看起来一样却不通」
 * 的差异。全空白输入 trim 后仍是空串，交给后续校验按「用户正在清空」报错，不当成缺失回落
 * 到已保存端点。
 */
internal fun draftOrSavedBaseUrl(draftBaseUrl: String?, savedProvider: CustomModelProvider?): String? =
    draftBaseUrl?.trim() ?: savedProvider?.baseUrl

internal fun draftOrSavedApi(draftApi: String?, savedProvider: CustomModelProvider?): String? =
    draftApi?.trim() ?: savedProvider?.api?.wireValue
