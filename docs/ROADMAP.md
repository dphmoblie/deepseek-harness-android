# DSH 移动版长期任务路线图（Roadmap）

> 依据：docs/mobile-runtime-evaluation.md（真机实测评估）。按优先级排序，标注验收标准与依赖。

## T1 用户态 Landlock 沙箱（对应 P0-3）

- **背景**：容器内无 bubblewrap/namespace 可用，沙箱后端不可用，文件工具只能 danger-full-access。
- **方案**：~~Landlock 适配~~（真机判定：landlock 444/445/446→ENOSYS、userns→ENOSYS，容器内沙箱后端全部不可用）→ 转显式降级：会话显著标注"无沙箱"权限模式，消除报错噪音。
- **验收**：write/edit 工具在受限模式可用且不报 "no sandbox backend is usable"；越界路径被拒。
- **依赖**：rootfs 内 dsh-sandbox-local 打补丁或新增后端；荣耀 SELinux 兼容性实测。
- **工作量**：大（架构级）。
- **状态**：📋 已立项为长期任务（含调研结论与里程碑）→ docs/long-term-tasks.md

## T2 Shizuku 命令桥接（对应 P1-1）

- **背景**：App 已获 Shizuku 授权，但容器内无 binder 工具链、无 adbd 通道，agent 无法利用。
- **方案**：App 侧新增 Shizuku 命令 RPC（把容器内命令经宿主 Shizuku 执行并回传），或预装 arm64 adb（待稳定静态源）。
- **验收**：agent 能通过 Shizuku 执行设备级命令（截图/输入/文件读取）。
- **依赖**：dsh 工具协议扩展 + App 原生桥接。
- **工作量**：中。
- **状态**：✅ 已实现（代码层，Kotlin 编译通过），待真机验证。
  - App 侧 DeviceBridgeServer：127.0.0.1:3082 HTTP 桥（ServerSocket 实现，Android 无 com.sun.net.httpserver），POST /device-command + Bearer 认证（DSH_DEVICE_BRIDGE_TOKEN，RuntimeStore 持久生成）。
  - 白名单命令：screenshot / uiDump / tap / inputText（DeviceCommand.fromName），一次性会话执行后关闭。
  - 容器侧 dsh-device CLI（构建时注入 /usr/local/bin）封装调用。
  - 真机验证：装测试包后，容器内执行 dsh-device screenshot /sdcard/x.png 等，对照验收清单第 8 节。

## T3 apt/dpkg 可用性（对应 P0-2）

- **背景**：SELinux untrusted_app 域拒绝 rename system_file，apt 安装失败。
- **方案**：根因已确认：dpkg backup 用 link()，而 untrusted_app 域禁 link()（rename/写均 OK，标签统一 app_data_file）。路线：① 重测全新包在线安装（无旧文件即无 backup link）；② 构建期预装为主；③ /opt 白名单兜底。
- **验收**：容器内 apt-get install 成功安装常见包。
- **依赖**：构建链容器布局重构；荣耀 SELinux 实测。
- **工作量**：中-大。
- **状态**：📋 已立项为长期任务（含根因假设与诊断步骤）→ docs/long-term-tasks.md

## T4 /sdcard 可写访问（对应 P1-2 深化）

- **现状**：已实现可选 bind（代码层），真机效果待验证。
- **方案**：若 SELinux 拒绝读取，改走 MediaStore/SAF 桥接（App 侧文件代理）。
- **验收**：agent 能读写用户选定目录（下载/相册）。
- **工作量**：中（视真机结果）。

## T5 工具链完整性（对应 P0-1 深化）

- **现状**：busybox/jq/unzip/python3 已注入（CI 已验证通过）。
- **方案**：git 构建期 .deb 解包（建议先行）；gcc 优先走 T3 在线安装（build-essential 全新包），备选构建期解包 / zig cc。
- **验收**：常见 agent 任务（编译/脚本/依赖管理）开箱可跑。
- **工作量**：小-中。
- **状态**：📋 已立项为长期任务（T5a git 建议先行，T5b gcc 待 T3/zig 试点）→ docs/long-term-tasks.md

---
优先级建议：T1（沙箱）> T2（Shizuku）> T4（sdcard 真机结果）> T5（工具链补全）> T3（apt）。
