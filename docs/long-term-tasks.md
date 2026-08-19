# 长期任务立项：T1 沙箱 / T3 apt / T5 工具链

> 立项日期：2026-08-18 ｜ 依据：docs/mobile-runtime-evaluation.md + 真机探测报告（2026-08-18）
> 关联：docs/ROADMAP.md ｜ 状态机：已立项 → 里程碑推进 → 验收关闭
> 执行纪律：真机验证优先；代码只推 feature 分支；不发布软件包与内容物品（用户指令）。

三个任务均来自评估报告 P0 级问题。本文档记录目标、约束、**真机判定结论**、
里程碑拆解（每步可独立验证）与回退方案。

---

## T1 用户态沙箱（对应 P0-3，优先级：高）

### 目标与验收
- 目标：恢复 dsh 文件工具的受限沙箱能力，消除 "no sandbox backend is usable" 噪音。
- 验收（降级方案）：会话初始上下文显著标注真实权限模式（无沙箱/危险全权），
  沙箱缺失从"每次报错"改为"一次性声明 + 中文转译"。

### 真机判定结论（2026-08-18，容器内探测）
- **Landlock 不可用**：syscalls 444/445/446（landlock_create_ruleset / add_rule / restrict_self）全部 ENOSYS。
  对照实验：seccomp filter 活跃（mode 2，2 filters，NoNewPrivs=1）；允许的 syscall 正常
  （getpid OK、openat2→EFAULT、pidfd_open→EINVAL、clone3→EINVAL），显式禁止的→EPERM（bpf），
  未列出的→ENOSYS（fsopen）。Landlock 的 ENOSYS 无法区分"内核未编译"与"seccomp 丢弃"，
  但两种情况下结果相同：**本进程不可调用**。
- **userns 不可用**：unshare(CLONE_NEWUSER)→ENOSYS（Android 对 app 禁 user namespace），
  bubblewrap 后端同样不可用（且 bwrap 未安装）。
- **内核侧不可验证**：/proc/config.gz 与 kallsyms 权限拒绝、securityfs 未挂载
  （无 /sys/kernel/security/lsm）、/sys 被掩蔽。web 证据（GKI landlock Kconfig 存在于
  android kernel/common 源码）不能证明 defconfig 启用；Android 安全模型本身不依赖 Landlock。
- **结论：容器内沙箱后端（Landlock / userns / bwrap）全部不可用**，且 App 进程自身
  同样受 seccomp 限制，宿主侧包装亦不可行。→ **关闭"容器内实现沙箱"路线**。

### 里程碑（已转向降级方案）
- M1 ✅ 真机探测（完成）：判定结论入库；完整证据见 docs/device-probe-report-2026-08-18.md（双通道 xattr 确认、seccomp 对照实验、rename 测试）。
- M2 降级实现：dsh web 会话初始上下文注入"无沙箱模式"声明（真实权限边界：容器 root 受限、
  文件工具全权但受 SELinux/路径约束）；沙箱后端缺失错误改为一次性提示 + 中文转译
  （errors.ts 已有部分 EACCES/EPERM 转译，需补"沙箱后端"分支）。
- M3 验收：新建会话时显著展示权限模式；write/edit 不再伴随沙箱报错噪音；
  越界写入（/system、/data 等宿主路径）仍被 SELinux 拒绝并转译。

### 风险
- 降级声明掩盖真实风险 → 声明必须包含"容器内 root 为伪 root、SELinux untrusted_app"完整语义；
- 未来环境（root 设备 / 普通 Linux 主机 / 内核启用 Landlock）可重新评估沙箱后端。

---

## T3 apt/dpkg 可用性（对应 P0-2，优先级：高）

### 目标与验收
- 目标：容器内可安装常用包。
- 验收：apt-get update 成功；apt-get install 全新包（tree、ripgrep 等）成功且命令可执行。

### 根因（已确认，2026-08-18 真机探测）
- **原"system_file 标签"假设被否定**：rootfs 全部文件统一标签
  u:object_r:app_data_file（ls -Z 与 stat -c %C 双读确认，含 dpkg/apt/bash/sh/passwd）。
- **rename 不被拦截**：/usr/bin/dpkg → dpkg.renametest → 还原，全部允许（SELinux 不限制
  app-data 内操作）。
- **真正根因 = link() 被禁**：dpkg 的 "unable to make backup link ... Permission denied"
  即 link(2) 硬链接备份失败——untrusted_app 域禁 link()（本项目早已实测并做了
  link→copyFile 降级，但 dpkg 二进制本身仍调 link()）。
- 推论：**全新包安装（目标文件不存在）不触发 backup link**，理论上可直接成功；
  覆盖已有文件的升级会失败。

### 里程碑
- M1 ✅ 诊断（完成）：结论入库（标签统一 app_data_file、rename OK、link() 被禁）；完整证据见 docs/device-probe-report-2026-08-18.md。
- M2 重测全新包安装（真机，下一步）：
  - apt-get update && apt-get install -y tree（全新包，无备份需求）；
  - 成功 → 验证矩阵：ripgrep、git、build-essential（gcc）等全新包；
    输出"运行期安装约束"：仅全新安装，避免覆盖升级；
  - 失败 → 记录具体错误点（backup link 是否在 postinst/其他阶段触发）。
- M3 兜底：构建期预装（现成路线，python3 等已落地）；/opt 白名单
  （dpkg --instdir=/opt/... 全新安装到空目录，同样无 backup link 需求）+ PATH/ldconfig 注入。
- M4 若必须支持覆盖升级：LD_PRELOAD link()→copy 垫片（影响面大，仅评估，不首选）。

### 风险
- postinst 脚本可能写系统路径或调用 link() → 包级兼容性差异，按包记录；
- apt 自身更新（升级 apt/dpkg 包本体）属覆盖场景，不适用在线升级。

---

## T5 工具链补全（对应 P0-1 深化，优先级：中）

### 现状
- 已注入并 CI 验证：busybox / jq / unzip / python3.13.5（standalone）+ 移动 profile。
- 缺口：git、gcc（及 curl/wget 视需要）。

### T5a git（建议立即启动，独立可做）
- 方案：构建期在 arm64 runner 上 apt download git（Ubuntu 24.04 arm64 官方包）+ 依赖闭包
  （libcurl4t64、libpcre2-8-0、libexpat1、zlib1g 等），dpkg-deb -x 解包进 rootfs——
  与 rootfs 的 glibc 2.39 完全兼容；增量体积约 60–80MB。
- 备选：musl 静态 git（社区 static-git 构建）体积小，但证书/兼容风险高，仅作回退。
- 验收：git --version；容器内 git init/commit/clone 本地仓库成功。

### T5b gcc（路线已随 T3 根因更新）
- 首选：T3-M2 重测成功后，容器内 apt-get install build-essential（全新包，无 backup link 需求）
  ——无需体积权衡。
- 备选（在线不可行时）：构建期 .deb 解包（+400–500MB，rootfs.bundle 体积翻倍）或
  zig cc 单文件静态二进制（约 90MB，clang 前端）；tcc 仅应急。
- 验收：cc 编译 hello.c 成功执行；gcc --version。

---

## 状态跟踪

| 任务 | 优先级 | 状态 | 下一步 |
|---|---|---|---|
| T1 沙箱 | 高（P0-3） | 容器内判定不可用 → 转显式降级方案 | M2 降级实现（web 端声明 + 错误转译） |
| T3 apt/dpkg | 高（P0-2） | 根因已确认（link() 被禁） | M2 重测全新包安装（真机） |
| T5a git | 中（P0-1） | 已立项，建议先行 | 构建链集成（arm64 runner 解包 .deb） |
| T5b gcc | 中 | 依赖 T3-M2 结果 | 在线装 build-essential / 备选 zig cc |

## 里程碑推进记录

| 日期 | 任务 | 进展 |
|---|---|---|
| 2026-08-18 | 立项 | 本文档建立；调研结论入库；ROADMAP 关联 |
| 2026-08-18 | T1/T3 真机探测 | 用户真机报告：landlock 444/445/446→ENOSYS；userns→ENOSYS；bwrap 未装 → 沙箱后端全不可用（T1 转降级）。rootfs 统一 app_data_file、rename 全 OK、dpkg 失败根因=link() 被禁（T3 原 xattr 假设否定） |
