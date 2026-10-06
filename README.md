# DeepSeek Harness 安卓版

项目概览 · [使用与构建说明](README.zh-CN.md) · [English](README.en.md)

[![最新版本](https://img.shields.io/github/v/release/dphmoblie/deepseek-harness-android?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC&logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![下载总量](https://img.shields.io/github/downloads/dphmoblie/deepseek-harness-android/total?label=%E4%B8%8B%E8%BD%BD%E6%80%BB%E9%87%8F&logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![许可证](https://img.shields.io/github/license/dphmoblie/deepseek-harness-android?label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](LICENSE)
[![最近提交](https://img.shields.io/github/last-commit/dphmoblie/deepseek-harness-android?label=%E6%9C%80%E8%BF%91%E6%8F%90%E4%BA%A4)](https://github.com/dphmoblie/deepseek-harness-android/commits)
[![欢迎 PR](https://img.shields.io/badge/PR-%E6%AC%A2%E8%BF%8E%E8%B4%A1%E7%8C%AE-brightgreen.svg)](https://github.com/dphmoblie/deepseek-harness-android/pulls)

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-3DDC84?logo=arm&logoColor=white)](https://developer.android.com/ndk/guides/abis)
[![Ubuntu 24.04](https://img.shields.io/badge/Ubuntu%2024.04-E95420?logo=ubuntu&logoColor=white)](https://ubuntu.com/)
[![Node.js 24](https://img.shields.io/badge/Node.js%2024-5FA04E?logo=node.js&logoColor=white)](https://nodejs.org/)
[![PRoot](https://img.shields.io/badge/PRoot-%E7%94%A8%E6%88%B7%E7%A9%BA%E9%97%B4%E5%AE%B9%E5%99%A8-4EAA25)](https://github.com/proot-me/proot)
[![Capacitor 7](https://img.shields.io/badge/Capacitor%207-119EFC?logo=capacitor&logoColor=white)](https://capacitorjs.com/)
[![React 18](https://img.shields.io/badge/React%2018-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![QQ群](https://img.shields.io/badge/QQ群-1108895375-12B7F5?style=for-the-badge)](#qq-group)

<p align="center"><img src="docs/images/app-icon-512.png" width="256" alt="DeepSeek Harness 安卓版应用图标"></p>

**面向 Android 的本地 AI 工作台。** 将 [DeepSeek Harness](https://github.com/deepseek-ai/dsh)、Ubuntu 运行环境、会话、插件与文件管理整合到手机中；开启相应授权后，AI 还可通过 Shizuku 执行设备 Shell（命令行）任务，或通过无障碍服务操作指定应用。

无需 Root（超级用户权限）。运行环境和控制台在手机本地执行，模型推理使用你配置的服务；对话、工具返回的文件内容和截图可能发送至该服务。

<a id="qq-group"></a><a id="community-qq-group"></a>**项目 QQ 群：** `1108895375`，欢迎交流使用体验、反馈问题和参与开发。

> 本页介绍当前源码。源码声明的应用版本为 `0.2.4`；目标应用副屏以实验功能提供。下载 APK 时，请以对应版本的发布说明为准，测试版不代表已完成真机兼容验收。

## 可以做什么

| 能力 | 当前实现 |
| --- | --- |
| 手机 AI 工作台 | 在本机 Ubuntu 用户空间运行 Harness，使用内置网页控制台，管理会话与模型供应商。 |
| 移动端界面 | 首页、插件、设置三个底部入口；会话管理合入首页，并为窄屏、横屏与系统安全区提供适配。 |
| 插件管理 | 按插件包和子插件管理启停；从 npm 包名、HTTPS 压缩包或 `git+https` 地址导入，按支持的目录规则保留更新数据，并可回滚至上一版。 |
| 文件与终端 | 按文件夹整理投递区文件，使用 Ubuntu 终端；授权 Shizuku 后可使用安卓设备终端。 |
| 设备自动化 | AI Shell 支持实际权限范围内的文件读写、上传下载与后台任务查询；无障碍通道支持观察界面及操作用户白名单中的应用。 |
| 主题与小窗 | 可选图片或视频背景，调整卡片颜色、通透度、模糊程度及强调色；悬浮球提供 AI 对话小窗入口。 |
| 运行管理 | 提供运行时安装与自检、日志查看、后台保持设置；后台存活仍受 Android 和厂商策略影响。 |

插件兼容性取决于具体版本和依赖；并非所有桌面插件都能直接用于手机。插件导入来源、数据迁移范围及回滚规则见[插件管理说明](docs/插件管理.md)。

## 目标应用副屏：让 AI 观察并操作另一块屏幕

**实验功能，当前源码已接入。** 用户可选择目标应用，尝试将其运行到独立虚拟显示，再在原生页面或可拖动小窗中查看和操作。这里展示的是目标应用的画面，与 AI 对话小窗分开。

- **入口：** 设置 → AI Shell → 目标应用副屏（实验功能）。
- **条件：** Android 10 及以上，Shizuku 已授权并连接，用户已开启 AI Shell。
- **AI 工具：** 查询副屏状态、获取 PNG 截图、点击、滑动、返回和结束会话。
- **当前边界：** 锁屏或熄屏时暂停该通道的读取与输入；尚无副屏专用无障碍节点树、中文文本输入或高帧率视频流。

截至 **2026-10-02**，已在 MuMu Android 15 中以普通 Shell 权限验证测试应用的横竖屏截图、定向点击、会话回收，以及 `363×800 dp` 和 `800×363 dp` 的原生选择页布局。**完整 Shizuku 授权与绑定链路、真实第三方应用、小窗互切、长期后台及锁屏恢复仍待验证。**

已有运行环境需要更新或重新打包 `dsh-mobile-shizuku` 插件才会出现新增 AI 工具；单独更新 APK 不会改写用户现有插件数据。详见[副屏使用说明与验证记录](docs/目标应用副屏.md)。

## 开始使用

1. 在[版本发布页](https://github.com/dphmoblie/deepseek-harness-android/releases)选择 APK，并阅读该版本的功能说明与已知问题。
2. 安装并打开应用，完成运行环境安装。自包含安装包内置经过校验的运行时，首次安装运行时可以离线完成。
3. 在**设置 → 模型与密钥**中配置服务，然后启动 Harness。
4. 按需启用设备能力：Shizuku、AI Shell、无障碍和悬浮窗分别需要相应授权；普通对话不要求这些权限。

| 项目 | 要求或说明 |
| --- | --- |
| 应用包名 | `io.deepseekharness.mobile` |
| 源码声明版本 | `0.2.4`，不代表下载包已包含全部源码改动 |
| 主应用最低系统 | Android 8.0（API 26） |
| 正式运行时架构 | `arm64-v8a`（64 位 ARM） |
| 目标应用副屏 | Android 10（API 29）及以上，兼容性需逐设备验证 |
| 运行环境 | Ubuntu 24.04 ARM64、Node.js 与 DeepSeek Harness |
| 存储与模型 | 预留数 GB 可用空间，并配置受支持的模型服务 |

[完整安装、模型配置与源码构建说明](README.zh-CN.md)

## 授权与数据去向

- **设备能力由用户开启。** AI Shell 一次开启后持续生效，直到用户关闭，无需逐条确认命令；执行能力取决于 Shizuku 实际权限。无障碍服务需在系统中单独开启，目标应用由用户从已安装应用列表加入白名单，数量不设上限。
- **本机服务与模型请求分开。** Harness 控制台仅监听本机回环地址，并有临时访问凭据；模型请求、插件下载及用户发起的上传下载可能访问网络。
- **截图和文件可能包含隐私。** 本地副屏预览不上传图像；AI 截图及工具结果可能进入会话并发送至所配置的模型服务，只授予任务所需的数据访问。
- **系统限制仍然有效。** 无障碍与副屏不能绕过锁屏；安全窗口、应用多屏限制与厂商后台策略可能影响使用。项目不承诺任意应用兼容或锁屏后永久保活。

供应商密钥通过 Android Keystore（密钥库）加密保存；原生审计记录保留至少 90 天，不记录命令正文、终端输出或截图。详情见[安全与隐私](README.zh-CN.md#安全与隐私)。

## 文档与开发

- [使用、模型配置、源码构建](README.zh-CN.md)
- [0.2.4 测试版变更与升级说明](docs/发布说明-0.2.4.md)
- [目标应用副屏与验证范围](docs/目标应用副屏.md)
- [应用读取与无障碍自动化](docs/应用自动化能力.md)
- [插件导入、数据保留与回滚](docs/插件管理.md)
- [投递区与存储权限](docs/存储权限与导入落点.md)
- [移动端插件兼容设计](docs/mobile-plugin-compat.md)
- [架构与安全边界](docs/ARCHITECTURE.md)
- [安卓构建说明](android/README.md)
- [发布检查清单](docs/RELEASE_CHECKLIST.md)

欢迎提交问题反馈和合并请求。报告兼容性问题时，请说明设备与系统版本、应用版本、目标应用或插件版本、复现步骤，并先移除截图和日志中的个人信息。

[![贡献者](https://contrib.rocks/image?repo=dphmoblie/deepseek-harness-android)](https://github.com/dphmoblie/deepseek-harness-android/graphs/contributors)

欢迎更多开发者参与，你的名字也可以出现在这里。

## 许可证

本仓库应用代码使用 [MIT 许可证](LICENSE)。随运行时分发的 PRoot（GPL-2.0-or-later）、Operit2 工具链（AGPL-3.0）、Ubuntu 软件包、Node.js 与 DeepSeek Harness 等组件沿用各自许可证。组件来源、版本、制品摘要与许可证见[第三方声明](THIRD_PARTY_NOTICES.md)及 APK 中的 `assets/legal/`。
