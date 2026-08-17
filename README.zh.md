# DeepSeek Harness Android

`app/` 是一个独立的 Capacitor Android 应用，在本机 Ubuntu 用户空间中运行 DeepSeek Harness。运行时就绪后，打开应用会直接启动并进入应用内 Harness 对话，无需外部浏览器；服务控制、Ubuntu 安装与重置、终端、运行时来源和可选的 Shizuku 设备 Shell 均位于设置中。

## 构建要求

- Node.js `^22.19.0 || >=24.0.0`，与当前 DeepSeek Harness 引擎的支持范围一致。Node.js 11.9 无法构建受支持的 Capacitor 版本，也无法运行当前的 DeepSeek Harness 上游。
- JDK 23.0.1；当系统默认仍指向 Java 8 时，必须显式设置 `JAVA_HOME`。
- Android SDK 35 与兼容的 Android NDK。
- 发布所用的、钉死版本的 ARM64 PRoot 运行器与加载器。当前发布产物来自 Operit2 Android 运行时工具链，commit 为 `dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`。

Android WebView 不运行 Node.js。安装的 Ubuntu 环境必须包含支持范围内的 Node.js（`^22.19.0 || >=24.0.0`）；当前 Harness 不支持 Node.js 23。

## 本地工作流

```powershell
pnpm install --frozen-lockfile
pnpm run build
pnpm run android:sync
```

官方 `0.1.7` CI 发布的是瘦 APK。工作流构建移动端 Harness 对话前端，将其注入 Ubuntu 24.04 ARM64、Node.js 24.19.0 与 `@deepseek-ai/dsh` 0.1.0-rc.6 运行时，并在同一个 `v0.1.7-mobile-<run_number>` Release 中发布 APK、`runtime-manifest.json` 与 `rootfs.bundle`。完成后的 manifest URL 与 SHA-256 会被编译进对应 APK，因此全新安装无需手工填写运行时来源；生成的 rootfs、manifest 与事务 `.bak` 不会被打进官方 APK。

应用会先校验 manifest 的固定摘要，再校验其声明的 HTTPS rootfs URL、长度、架构、压缩方式与 SHA-256。下载使用应用私有的 `rootfs-<sha256>.part`，中断后可跨应用重启续传；续传响应必须是精确匹配的 HTTP 206/`Content-Range`，HTTP 200 或 416 会从零重新下载。断网、TLS、超时或截断会进入明确的错误状态并保留合法断点，不会提前显示正在解压或安装完成。`scripts/build-embedded-runtime.py` 仍可用于明确构造的内嵌开发包。不要把 API 密钥、密码、数据库凭据、签名口令或 token 放入 `.env`、Gradle 文件、源码、manifest、URL 或日志。

原生运行器文件单独生成或导入，永不提交入库。发布 APK 必须同时打包 `lib/arm64-v8a/libdsh_proot.so` 与 `lib/arm64-v8a/libdsh_proot_loader.so` 两个文件。现有的 `prepare:runner` 流程仍可用于单独钉死版本的运行器来源，但它不能替代对随 APK 发布的这两个确切二进制的出处与许可审查。

运行时 manifest 字段与安全流程见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。内嵌 manifest 中保留的 `.invalid` 归档 URL 仅在从 APK 读取归档时作为元数据存在，绝不应被访问。远程发布 manifest 必须使用真实 HTTPS URL 与精确的发布摘要。参见 [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)。

## 安全要点

- 原生桥输入具有显式的类型、长度、格式与状态校验。
- 应用没有用户可见的登录或 Android 设备凭据门槛；启动直达对话，管理功能位于设置中。Harness 回环传输仍使用独立的一次性认证凭据。
- 内嵌与下载产物均要求精确摘要与字节上限；下载还要求 HTTPS、暂存文件与原子晋升。
- 归档解包防止路径穿越，且不创建设备节点。
- Harness 仅绑定 Android 回环地址；任何业务服务都不暴露在 `0.0.0.0` 上。
- 每次 Harness 启动都生成不落盘的 256 位凭据。rootfs preload 在上游处理器运行前对 HTTP 与 WebSocket 升级统一认证，只有设备认证过的内部 WebView 应答 Basic 认证质询。凭据绝不放入 URL 或审计日志。
- Shizuku 访问要求可见的授权授予与用户已打开的终端会话。
- 重置仅限应用私有的运行时根目录，且不跟随符号链接。
- 仅属主可读的审计文件按日轮转，保留至少 90 天的固定事件/结果码。
- 应用审计文件中不写入任何凭据、URL、命令、会话标识、终端内容或敏感用户数据。

## 许可

应用源码本身采用 MIT 许可。这不会替代或削弱所打包运行时组件的许可。直接依赖与运行时再分发义务汇总于 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。特别地：PRoot 运行器为 GPL-2.0-or-later，所引用的 Operit2 源码/构建材料为 AGPL-3.0。分发者必须按相应许可要求提供适用许可文本、所发布确切产物（含修改与构建脚本）的完整对应源码，以及在许可要求期限内清晰可得的源码获取说明。当前出处记录标明了源码修订与二进制摘要，但并未声称可逐位复现的重新构建。
