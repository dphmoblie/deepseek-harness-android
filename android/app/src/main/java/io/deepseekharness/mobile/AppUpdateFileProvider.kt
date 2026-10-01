package io.deepseekharness.mobile

import androidx.core.content.FileProvider

/**
 * 更新安装包专用的 FileProvider。
 *
 * 必须与诊断导出用的 `androidx.core.content.FileProvider` 是**不同的类**：同一个应用里两个
 * `<provider>` 用同一个 `android:name` 时，PackageManager 会按 ComponentName 去重，
 * 另一个 authority 在真机上就解析不到。所以这里用一个空子类占住 `${applicationId}.appupdates`。
 *
 * 它只暴露应用私有 cacheDir 下的 `app-updates/`（见 `res/xml/app_update_paths.xml`），
 * 且只按次授权：系统安装器读完那一次就够了，不授予持久权限。
 */
class AppUpdateFileProvider : FileProvider()
