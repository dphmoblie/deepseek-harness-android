# 移动端 0.1.8 真机验收清单

> 适用范围：荣耀 Android 16（arm64/16KB）及至少一台其他 arm64 真机。验收对象为
> 同一个 `v0.1.8-mobile-<run_number>` Release 中的内嵌 APK、
> `runtime-manifest.json` 与 `rootfs.bundle`。

## 0. 发布资产前置

- [ ] APK 的 `versionCode=8`、`versionName=0.1.8`，并通过团队签名校验。
- [ ] Release 同时包含 APK、`runtime-manifest.json`、`rootfs.bundle`，三者来自同一次 CI run。
- [ ] APK 内恰有一份 `assets/runtime/rootfs.bundle` 和运行时 manifest，没有 `.bak` 或其他 rootfs 副本。
- [ ] 内嵌 manifest 的 rootfs SHA-256 与 APK 内 bundle 一致；全新安装无需手工填写运行时来源。
- [ ] APK 包含 ARM64 PRoot runner/loader，二者通过 16KB 对齐检查。
- [ ] Shizuku 为可选项；未安装 Shizuku 不影响 Ubuntu 安装和 Harness 对话。

## 1. 启动与导航

| # | 操作 | 预期 |
|---|---|---|
| S1 | 已安装运行时后冷启动应用 | 自动启动本机 Harness，并直接打开应用内对话页；不拉起外部浏览器 |
| S2 | Harness 已运行时再次打开应用 | 直接打开现有 Harness，不重复启动服务 |
| S3 | 在 Harness 原生工具栏点设置 | 返回 Capacitor 设置页，不立即循环重开 Harness |
| S4 | 在 Harness 原生工具栏点返回 | 有 WebView 历史时后退；无历史时返回设置页 |
| S5 | 首次安装、运行时不存在 | 仅显示“安装并进入对话”的单步入口，无额外引导、跳过按钮或登录认证页 |
| S6 | 设置页点“返回对话” | 启动或复用 Harness 后回到对话页 |

## 2. Harness 对话 GUI

在 360x800、390x844、480x960 及桌面宽度逐项验证：

- [ ] 启动时恢复最近未归档会话；没有会话时自动创建会话并进入聊天。
- [ ] 会话抽屉可打开、切换、新建、归档和关闭，标题优先显示会话 projection。
- [ ] 消息支持 Markdown、GFM 表格、代码块、工具调用与可展开工具结果；外部 HTML 不执行。
- [ ] Composer 可发送普通消息；运行中可选择排队或引导，队列数量同步显示。
- [ ] 模型和推理强度可选，选择结果会用于后续发送。
- [ ] 任务、文件、模型/Harness 设置作为二级页面可进入并可返回聊天。
- [ ] Android 软键盘弹出时输入框、发送按钮和最后一条消息均可见，无横向溢出或文字重叠。
- [ ] 应用切后台再恢复后事件流继续更新，不丢失增量，也不被迟到历史覆盖。

## 3. Ubuntu 下载、续传与状态

- [ ] 全新安装点“安装并进入对话”后先显示真实下载进度，未下载完成前不显示校验或安装完成。
- [ ] 下载中断后记录当前字节数；关闭并重开应用，再次安装从同一
  `rootfs-<sha256>.part` 偏移发出 Range 请求。
- [ ] 合法 HTTP 206 与精确 `Content-Range` 继续写入，不重新下载已有字节。
- [ ] 服务端返回 HTTP 200 或 416 时清理旧 partial 并从 0 开始；错误 206/Range 响应失效即关。
- [ ] 断网、DNS、TLS、超时及截断下载进入明确 `error`，保留合法 partial，不显示“正在安装”。
- [ ] 下载完成后校验 manifest 声明的大小和 SHA-256；摘要不符不得解压或启用。
- [ ] 解压完成后自动启动 Harness 并进入对话；重新启动应用仍识别已安装版本。
- [ ] 设置中的重置需要输入 `RESET_RUNTIME`，只清除应用私有 Ubuntu 环境并关闭相关会话。

## 4. 设置与终端

- [ ] Harness 服务启动/停止、Ubuntu 版本与状态、运行时来源、重置均位于设置或其二级页。
- [ ] Ubuntu 终端可创建、输入、调整尺寸、重新连接和关闭；字体设置立即用于新会话。
- [ ] 保持屏幕常亮开关生效，退出相关页面后不泄漏其他敏感状态。
- [ ] 设置页没有用户账号、密码、验证码或 Android 设备凭据认证入口。
- [ ] Harness HTTP 与 WebSocket 未认证请求均被拒绝；内部 WebView 无可见认证提示且可正常连接。

## 5. Shizuku 与设备 Shell

- [ ] 未安装：显示“打开 Shizuku”，Ubuntu 与 Harness 功能仍可用。
- [ ] 已安装但服务未运行：显示启动提示，打开 Shizuku 后可返回刷新状态。
- [ ] 服务运行但未授权：点击“请求授权”只触发系统 Shizuku 授权流程。
- [ ] 已授权但未连接：设备终端保持不可用，并显示“连接 Shizuku”按钮。
- [ ] 点击“连接 Shizuku”后仅在 UserService binder 存活时显示“已连接”并允许设备 Shell。
- [ ] 杀死 binder/UserService 后现有设备会话退出，状态回到未连接；再次连接可恢复。
- [ ] 设备 Shell 的 uid 为 Android shell 权限而非 root；固定 `/system/bin/sh`，无隐藏后台通用命令入口。
- [ ] screencap、uiautomator、tap、inputText 仅通过已有可见设备会话和固定白名单执行。

## 6. 体积与兼容性记录

| 指标 | 记录值 | 验收要求 |
|---|---|---|
| 内嵌 APK 大小 | ___ MB | 包含一份 rootfs；异常增长必须定位 |
| rootfs compressedBytes | ___ MB | 与 manifest 及实际资产完全一致 |
| 安装后运行时体积 | ___ MB | 不超过清单和提取上限 |
| 首次安装到 Harness 可用 | ___ 秒 | 记录设备条件 |
| Harness 空闲内存 | ___ MB | 记录稳定基线 |
| 页面大小 | ___ bytes | 荣耀 / Android 16 为 16384 |

## 7. 验收记录

```text
设备/系统：
Android 页面大小：
Release tag：v0.1.8-mobile-____
Git commit：
APK SHA-256：
manifest SHA-256：
rootfs SHA-256：
结果：S1-S6 / 对话 GUI / 下载续传 / 设置终端 / Shizuku，逐项通过或失败并附截图与日志编号
```


## 8. 工具链与 /sdcard 验证（对应 docs/ROADMAP.md T4/T5）

- [ ] 工具链：容器内 `python3 --version`、`jq --version`、`busybox | head -1`、`unzip -v | head -1` 均可用（rootfs 注入 /usr/local/bin 与 /opt/python）。
- [ ] python 软链：`python3` 与 `python` 都解析到 /opt/python/bin/python3（荣耀降级复制后仍可执行）。
- [ ] /sdcard：容器内 `ls /sdcard` 可见 Android 公共存储（有内容或至少目录存在）；若为空/拒绝，记录是否因 SELinux（预期失败跳过，不影响 Harness 启动）。
- [ ] 容器内 `cat /sdcard/<下载文件>` 可读取（可选：写测试）。
- [ ] 对话页发消息正常（工具链/ bind 改动不回归核心对话）。

## 9. 验收记录（补充）

| 项 | 结果 |
|---|---|
| 工具链（python3/jq/busybox/unzip） | ___ |
| /sdcard 可见性 | ___ |
| /sdcard 读取 | ___ |

- [ ] 同步完成 `docs/review-2026-08-16.md` 中的 16KB/PRoot 检查。
- [ ] 执行 Android lint、JVM 测试、前端测试和至少一次 ARM64 真机完整安装。
