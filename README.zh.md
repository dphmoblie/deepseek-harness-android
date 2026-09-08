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

官方 `0.1.9` CI 发布的是内嵌离线运行时的 ARM64 APK。工作流固定使用与上游提交 `c389f96bf3a9b6807cb71ed6bdad5849be0df6d8` 对应的 `@deepseek-ai/dsh` 和官方前端 `0.1.3-alpha.2`，只增加 Android 安全区与触控尺寸样式，再将其注入 Ubuntu 24.04 ARM64 与 Node.js 24.19.0 运行时。随后，校验后的 `rootfs.bundle` 和 `runtime-manifest.json` 会内嵌到同一 APK。

内嵌安装会校验 manifest 声明的长度、架构、压缩方式与 rootfs SHA-256 后再解压。用户明确配置远程来源时，应用还会校验 manifest 摘要与 HTTPS 目标；下载使用应用私有的 `rootfs-<sha256>.part`，中断后可跨应用重启续传。续传响应必须精确匹配 HTTP 206/`Content-Range`，HTTP 200 或 416 会从零重新下载。断网、TLS、超时或截断会进入明确错误状态并保留合法断点，不会提前显示正在解压或安装完成。不要把 API 密钥、密码、数据库凭据、签名口令或 token 放入 `.env`、Gradle 文件、源码、manifest、URL 或日志。

打包后的根路径直接提供完整的官方 Harness 前端，包括对话、模型、推理强度、设置与插件界面。应用不再包含单独编写的对话前端或兼容工作台。Android 适配仅处理 WebView 安全区和触控目标尺寸，保留上游页面结构与风格。相邻目录中的上游源码可以独立拉取更新；APK 仍使用 `scripts/runtime-profile/package.json` 与 `harness-web/package.json` 固定的版本，只有在明确升级并完成兼容测试后才会变化。

原生运行器文件单独生成或导入，永不提交入库。发布 APK 必须同时打包 `lib/arm64-v8a/libdsh_proot.so` 与 `lib/arm64-v8a/libdsh_proot_loader.so` 两个文件。现有的 `prepare:runner` 流程仍可用于单独钉死版本的运行器来源，但它不能替代对随 APK 发布的这两个确切二进制的出处与许可审查。

运行时 manifest 字段与安全流程见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。发布 manifest 使用同一 Release 的真实 HTTPS rootfs 地址与精确摘要；内嵌安装不会访问该地址。参见 [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)。

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
