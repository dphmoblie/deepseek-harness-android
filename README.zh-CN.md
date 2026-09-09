# DeepSeek Harness 安卓版

[English](README.md) · [简体中文](README.zh-CN.md)

**DeepSeek Harness 安卓版**可在安卓手机上直接运行完整的 [DeepSeek Harness](https://github.com/deepseek-ai/dsh) 智能体环境——包括 Ubuntu 用户空间、Node.js 以及官方 Harness 网页控制台。设备**无需 Root**：整套 Linux 环境通过 [PRoot](https://github.com/proot-me/proot) 在用户空间内执行，Harness 服务仅监听安卓回环地址，并在禁止外部导航的内置 WebView 中展示。

| | |
| --- | --- |
| 应用包名 | `io.deepseekharness.mobile` |
| 当前版本 | `0.1.9` |
| 最低系统 | Android 8.0（API 26）及以上 |
| 支持架构 | 仅 `arm64-v8a` |
| 内置运行时 | Ubuntu 24.04 ARM64 · Node.js 24.19 · `@deepseek-ai/dsh` 0.1.5-alpha.1 |
| 应用许可证 | MIT（运行时组件沿用各自许可证，见[许可证](#许可证)一节） |

## 功能特性

- **手机上的完整 Linux 智能体环境。** Ubuntu 24.04 完全在设备本地通过 PRoot 运行，不依赖云服务器、远程桌面，也无需注册账号：智能体运行时与网页控制台均在本地执行。
- **官方 Harness 网页控制台。** 应用内置官方 `dsh web` 前端，仅针对移动端视口尺寸与安全区域做了适配。桌面端 DSH 网页插件可通过标准 Harness 插件加载器使用，并获得适配移动端的布局形态。
- **免 Root 运行。** 在普通原厂设备上即可通过 PRoot 实现用户空间容器化。可选的 [Shizuku](https://shizuku.rikka.app/) 集成可在用户主动授权后额外提供 Shell 级别的设备终端（`/system/bin/sh`）。Shizuku 提供的是安卓 Shell 权限，而非 Root 权限。
- **开箱即用、支持离线安装。** 正式版 APK 内置经过校验的 `rootfs.bundle` 与清单文件，无网络环境也可完成运行时安装；同时也支持经摘要固定（digest-pinned）的远程运行时来源。
- **防篡改的运行时分发。** 每份清单与根文件系统镜像在使用前均按精确长度与 SHA-256 校验；下载仅接受 HTTPS 目标地址、拒绝指向私有地址的 DNS 解析结果，支持 HTTP 范围请求断点续传，解压时具备路径穿越与设备节点防护。环境就绪后以原子方式切换生效。
- **内置与自定义模型供应商。** DeepSeek、OpenAI、Anthropic、Google Gemini、OpenRouter、Groq、xAI、Mistral 以及自建 OpenAI 兼容端点的凭据均通过 Android Keystore 加密保存，且只会注入运行时进程，绝不回传至 WebView。
- **默认仅本地通信。** Harness 只绑定 `127.0.0.1`。每次启动都会生成全新的 256 位传输令牌，同时保护 HTTP 与 WebSocket 请求；令牌仅保存在进程内存中，不会持久化，也不会写入 URL。
- **集成终端。** 可在同一界面中使用 PRoot 环境内的 Ubuntu 终端，以及（可选）由 Shizuku 支持的安卓设备终端。

## 工作原理

应用分为三层：

1. **管理界面（Capacitor + React）。** 原生安卓外壳，负责运行时安装、服务控制、模型供应商设置、终端、运行时来源与环境重置。
2. **原生运行时层（Kotlin）。** 负责根文件系统的校验与解压、以原生库形式随包提供的 PRoot 运行器与加载器管理、Harness 进程与 PTY 会话监管，以及在用户授权后连接 Shizuku UserService。
3. **Ubuntu 运行时（PRoot）。** 通过固定的白名单入口在 Ubuntu 24.04 内启动 `dsh web` 并仅监听回环地址。Node.js 预加载模块会在任何请求到达 Harness 之前校验当次启动令牌，内置 WebView 也被限制在同一回环源内。

完整架构与安全边界见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 安装

1. 从 [Releases](https://github.com/dphmoblie/deepseek-harness-android/releases) 页面下载最新版 APK。
2. 安装 APK（按系统提示允许来自可信来源的安装）。
3. 打开应用，等待内置运行时完成读取、校验与安装——官方自包含版本无需联网。
4. 在**设置 → 模型供应商**中添加模型供应商与 API 密钥，随后启动 Harness。

运行时就绪后，应用会直接打开 Harness 控制台并恢复最近一次会话。

### 环境要求

- Android 8.0 及以上、**arm64-v8a**（64 位 ARM）处理器的设备。
- 数 GB 左右的可用存储空间，用于存放解压后的 Ubuntu 环境。
- 至少一个受支持模型供应商的 API 密钥，或一个兼容的自定义端点。

## 模型供应商

内置供应商：**DeepSeek、OpenAI、Anthropic、Google Gemini、OpenRouter、Groq、xAI、Mistral**。

也可以将任意 OpenAI 兼容端点配置为自定义供应商（基础地址、API 密钥与模型列表）。凭据通过 Android Keystore 静态加密，仅以进程环境变量形式注入 Harness 运行时；保存配置时若 Harness 正在运行会自动重启，确保运行时状态始终与界面显示一致。

## 可选的 Shizuku 集成

Shizuku 完全可选，且不会随应用捆绑安装：

1. 自行安装并启动 [Shizuku](https://shizuku.rikka.app/)（通过无线调试或 Shizuku 官方指引的方式）。
2. 在应用内授予权限，再点击显式的**连接 Shizuku** 操作按钮。
3. 该项功能正在测试，可能存在问题。

当 Shizuku 不可用、未授权或连接断开时，设备终端请求会明确报错；Ubuntu 运行时与 Harness 不受影响。

## 从源码构建

### 构建依赖

- Node.js `^22.19.0` 或 `>=24.0.0`，以及 [pnpm](https://pnpm.io/) 11
- Android SDK 35、NDK、CMake 3.22.1、JDK 23、Gradle 8.11.1
- 来自 Operit2 安卓运行时工具链、与发布版本固定对应的 ARM64 PRoot 运行器与加载器（`libdsh_proot.so`、`libdsh_proot_loader.so`）——准确的上游版本号与哈希见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- 自包含构建还需从同一源码版本生成的 `runtime-manifest.json` 与 `rootfs.bundle`

### Web 与安卓构建

```bash
pnpm install --frozen-lockfile
pnpm run build          # TypeScript 检查 + Vite 生产构建
pnpm run android:sync   # 构建并同步到安卓工程
pnpm run android:open   # 在 Android Studio 中打开，或直接使用 Gradle 构建
```

开发版构建可以不内置运行时，改为同时设置 `DSH_RUNTIME_MANIFEST_URL` 与
`DSH_RUNTIME_MANIFEST_SHA256` 以固定远程清单。完整构建说明与签名策略见
[android/README.md](android/README.md)。

### 检查与测试

```bash
pnpm test          # Vitest 单元测试
pnpm run test:scripts
pnpm lint          # ESLint，零警告通过
```

## 安全与隐私

- **仅监听回环地址。** Harness 不会绑定任何非回环网络接口；内置 WebView 阻止访问回环源之外的导航与 HTTP 资源。
- **临时传输凭据。** 每次启动 Harness 都会通过 `SecureRandom` 生成全新的 256 位令牌。令牌不会持久化、不会写入日志或 URL，也不会返回给 JavaScript。
- **凭据存储。** 供应商 API 密钥通过 Android Keystore 加密，离开管理界面时仅作为 PRoot 运行时的进程环境变量存在。
- **可验证的运行时供应链。** 清单与根文件系统镜像均经过 schema 校验与摘要固定，解压时执行严格的归档边界检查；断点续传遇到非法范围或异常响应时按失败即关闭（fail-closed）处理。
- **审计记录。** 原生审计日志存放在应用私有的禁备份目录中，文件仅所有者可读写，按 UTC 日期轮转并保留 90 天。记录只包含固定的事件/结果枚举，绝不包含 URL、命令、令牌或终端数据。
- **无登录、无追踪。** 应用不设账号、不含广告、不采集遥测数据。

## 参与贡献

欢迎在 <https://github.com/dphmoblie/deepseek-harness-android> 提交 Issue 与 Pull Request。

提交前请保持改动聚焦、为新行为补充测试，并运行 `pnpm lint` 与 `pnpm test`。
涉及安全的改动必须维护 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 所述边界，
尤其不得削弱回环访问控制、摘要校验、入口白名单或 Shizuku UserService 契约。

### 贡献者

感谢以下贡献者对本项目的付出：

- [@standtrain](https://github.com/standtrain)
- [@11hyy](https://github.com/11hyy)

### 交流社区

- **QQ 交流群：1108895375**——欢迎入群提问、反馈建议、获取版本发布通知。

## 许可证

本仓库中的应用代码基于 [MIT 许可证](LICENSE)发布。

正式版 APK 还以各自许可证再分发了第三方运行时组件，包括 PRoot
（GPL-2.0-or-later）、Operit2 运行时工具链（AGPL-3.0）、Ubuntu 24.04
软件包、Node.js，以及采用 MIT 许可证的 DeepSeek Harness 运行时与前端。
组件来源、确切上游版本、制品哈希及相应许可证文本记录于
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，并随 APK 内
`assets/legal/` 目录一并提供。

## 相关文档

- [架构与安全边界](docs/ARCHITECTURE.md)
- [移动端插件兼容设计](docs/mobile-plugin-compat.md)
- [发布检查清单](docs/RELEASE_CHECKLIST.md)
- [安卓平台构建说明](android/README.md)
- [第三方声明](THIRD_PARTY_NOTICES.md)
