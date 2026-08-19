# DeepSeek Harness Android（中文版）

`app/` 是一个独立的 Capacitor Android 应用，在本机 Ubuntu 用户空间中运行 DeepSeek Harness。运行时就绪后，打开应用会直接启动并进入应用内 Harness 对话，无需外部浏览器；服务控制、Ubuntu 安装与重置、终端、运行时来源和可选的 Shizuku 设备 Shell 均位于设置中。

> 本文档为 [README.md](README.md) 的中文版本，内容以英文原版为准。

## 构建要求

- Node.js `^22.19.0 || >=24.0.0`，与当前 DeepSeek Harness 引擎版本范围一致。Node.js 11.9 无法构建受支持的 Capacitor 版本或当前 DeepSeek Harness 上游。
- JDK 23.0.1，当系统默认仍指向 Java 8 时需显式设置 `JAVA_HOME`。
- Android SDK 35 及兼容的 Android NDK。
- 发布版使用的固定 ARM64 PRoot 运行器与加载器。当前发布产物来自 Operit2 Android 运行时工具链，提交 `dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`。

Android WebView 不运行 Node.js。已安装的 Ubuntu 环境必须包含版本范围恰为 `^22.19.0 || >=24.0.0` 的 Node.js；当前 Harness 不支持 Node.js 23。

## 本地工作流

```powershell
pnpm install --frozen-lockfile
pnpm run build
pnpm run android:sync
```

官方 `0.1.8` CI 发布的是内嵌离线运行时的 ARM64 APK。工作流构建移动端 Harness 对话前端，将其注入 Ubuntu 24.04 ARM64、Node.js 24.19.0 与 `@deepseek-ai/dsh` 0.1.0-rc.6 运行时，再把校验后的 `rootfs.bundle` 和 `runtime-manifest.json` 内嵌到同一 APK。Release 也单独发布这两项运行时资产，便于核验以及用户明确配置远程来源；安装官方 APK 无需联网或手工填写运行时地址与摘要。

内嵌安装会校验清单声明的长度、架构、压缩方式与 rootfs SHA-256 后再解压。用户明确配置远程来源时，应用还会校验清单摘要与 HTTPS 目标；下载使用应用私有的 `rootfs-<sha256>.part`，中断后可跨应用重启续传。续传响应必须精确匹配 HTTP 206/`Content-Range`，HTTP 200 或 416 会从零重新下载。断网、TLS、超时或截断会进入明确错误状态并保留合法断点，不会提前显示正在解压或安装完成。禁止将 API 密钥、密码、数据库凭据、签名密码或 Token 放入 `.env`、Gradle 文件、源代码、清单、URL 或日志。

打包后的根路径只提供新版移动对话，包含会话、任务、文件、模型与推理强度、Agent preset、Harness 公开设置和插件生命周期管理。原桌面首页不再作为另一个入口打包；第三方 Cordis 插件自带页面可从设置中的按需兼容工作台打开，正常对话时不会加载这些资源。

原生运行器文件单独生成或导入，永不提交。发布 APK 同时打包 `lib/arm64-v8a/libdsh_proot.so` 与 `lib/arm64-v8a/libdsh_proot_loader.so`，两者均必需。现有的 `prepare:runner` 流程仍可用于单独固定的运行器来源，但它不能替代对 APK 中随附的那两个确切二进制的来源与许可审查。

运行时清单字段与安全流程记录在 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。发布清单使用同一 Release 的真实 HTTPS rootfs 地址与精确摘要；内嵌安装不会访问该地址。参见 [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)。

## 安全检查点

- 原生桥接输入具备显式的类型、长度、格式与状态校验。
- 应用没有用户可见的登录或 Android 设备凭据门槛；启动直达对话，管理功能位于设置中。Harness 回环传输仍使用独立的一次性认证凭据。
- 内嵌与下载的产物要求精确摘要与字节上限；下载额外要求 HTTPS、可恢复的按摘要命名的暂存文件、严格的 Range 响应校验与原子替换。
- 归档解压防止路径穿越，且不创建设备节点。解压器消费的精确压缩流在替换前会再次计数与哈希，从而在解压过程中独立强制执行清单中的压缩大小与 SHA-256。
- 启动 Ubuntu 前，应用会探测打包的 PRoot 运行器及其 seccomp 兼容性，然后要求对生成的解析器文件、`/dev` 与 `/proc` 分别校验绑定挂载。若必需的源、客户机目标或兼容性探测不可用，启动将安全失败（fail closed）。
- Harness 仅绑定 Android 回环地址；不对 `0.0.0.0` 暴露任何业务服务。
- 每次 Harness 启动都会生成一个非持久的 256 位凭据。rootfs 预加载在上游处理器运行前对 HTTP 与 WebSocket 升级均进行认证，且仅由非导出的内部 WebView 透明应答 Basic 认证质询。仅开放的 TCP 端口不被视为就绪：两个相隔稳定性间隔的回环探测必须返回 HTTP 401，且带有精确匹配的 Basic 域与 UTF-8 质询。凭据绝不放入 URL 或审计日志。
- Shizuku 访问要求声明 `ShizukuProvider`、可见的权限授予，以及用户打开的终端会话。Binder 或 UserService 丢失会清除活动会话；之后的终端请求会重新连接，且仅当存在活跃的已授权 UserService Binder 时 `connected` 才为 true。
- 重置仅限于应用私有运行时根目录，且不跟随符号链接。
- 仅所有者可读的审计文件每日轮转，并至少保留 90 天的固定事件/结果代码。
- 任何凭据、URL、命令、会话标识符、终端内容或敏感用户数据都不会写入应用审计文件。

## 许可

原始应用源代码采用 MIT 许可。这不能替代或削弱所打包运行时组件的许可。直接依赖与运行时再分发义务汇总于 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。特别是，PRoot 运行器为 GPL-2.0-or-later，所引用的 Operit2 源代码/构建材料为 AGPL-3.0。分发者必须在适用许可要求的期限内，提供适用的许可文本、所随附确切产物的完整对应源代码（包括修改内容与构建所需的脚本）以及清晰的源代码获取说明。当前的溯源记录标明了源代码修订版本与二进制摘要；它不声明可以逐位复现的重构建。
