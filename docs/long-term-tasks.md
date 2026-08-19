# 长期任务立项：T1 沙箱 / T3 apt / T5 工具链

> 立项日期：2026-08-18 ｜ 依据：docs/mobile-runtime-evaluation.md（真机实测，Android 16 / 6.12.38-aarch64 / untrusted_app）
> 关联：docs/ROADMAP.md（路线图条目）｜ 状态机：已立项 → 里程碑推进 → 验收关闭
> 执行纪律：真机验证优先；代码只推 feature 分支；不发布软件包与内容物品（用户指令）。

三个任务均来自评估报告 P0 级问题，属架构级/工程级长期工作。本文档给出每个任务的目标、
约束、调研结论、里程碑拆解（每步可独立验证）与回退方案。

---

## T1 Landlock 用户态沙箱（对应 P0-3，优先级：高）

### 目标与验收
- 目标：恢复 dsh 文件工具的受限沙箱能力，消除 "no sandbox backend is usable" 与 danger-full-access 兜底。
- 验收：
  1. write/edit 工具在受限模式可用且不再报沙箱后端缺失；
  2. 越界路径（沙箱根之外）被拒绝并给出中文错误；
  3. 普通 bash 命令不受影响（非文件工具仍按原权限执行）。

### 现状与约束
- 真机：Android 16，内核 6.12.38-android16-aarch64；容器内 proot 用户态 root，真实 uid 10368，SELinux untrusted_app。
- 已落地：link→copyFile 降级（会话/附件写入已修复），但沙箱后端仍缺失。
- Landlock 是进程级、无需 root/namespace 的 LSM（内核 5.13+），与 proot 场景天然契合。

### 调研结论
- Android GKI 内核包含 Landlock：AOSP kernel/common 自带 Documentation/userspace-api/landlock.rst，
  GKI 5.15 已有"无特权 Landlock"解析资料；本机 6.12 内核从版本上完全支持。
- 未知项（真机确认）：荣耀 ROM 的 LSM 启用列表（/sys/kernel/security/lsm）是否含 landlock；
  Android 默认 LSM 顺序（selinux,bpf,...）可能未启用 landlock，需内核 cmdline 或厂商配置。

### 里程碑
- M1 真机探测（立即可做，随下一个测试包）：
  - cat /sys/kernel/security/lsm 确认是否含 landlock；
  - 容器内用已注入的 python3.13 写 ctypes 脚本调用 landlock_restrict_self（先 add_rule 空规则 + ABI 探测），
    确认无特权调用不被 seccomp/SELinux 拦截。
- M2 dsh-sandbox-local 新增 Landlock 后端（rootfs 内打补丁）：
  - 实现受限读写规则（allowed_fs 写目录 + 可执行位控制）；
  - 关键验证点：proot 路径翻译下 landlock_add_rule 的路径语义（容器路径 → 宿主真实路径）兼容性。
- M3 文件工具受限模式验证：沙箱根内读写 OK、越界被拒、错误信息转译。
- M4 默认策略切换：会话初始上下文由 danger-full-access 改为受限沙箱 + 明确提示。

### 风险与回退
- 荣耀内核 LSM 列表不含 landlock → 回退：显式"无沙箱模式"（评估 P0-3 备选），UI 显著标注真实权限模式；
- proot 路径翻译不兼容 → 评估在宿主侧（App 进程）做 Landlock 包装进程（容器命令经宿主代理执行）。

---

## T3 apt/dpkg 可用性（对应 P0-2，优先级：高）

### 目标与验收
- 目标：容器内 apt-get install 可安装常见包。
- 验收：apt-get update 成功；apt-get install 安装 tree、ripgrep 等小型包成功且命令可执行。

### 现状与约束
- 真机现象：dpkg 阶段失败 "unable to make backup link ... Permission denied"——untrusted_app 域
  rename 已有 system_file 被拒。
- 已实测：link() 被禁（已降级 copyFile）；rename/mkdtemp/普通写 OK（在普通路径）。
- rootfs 位于应用私有目录 /data/user/0/io.deepseekharness.mobile/files/ 下。

### 根因假设（首选）
- rootfs.bundle 解压时保留了 security.selinux xattr（system_file），使私有目录内的文件仍携带
  system_file 标签 → 被 untrusted_app 域策略拒绝 rename。
- 修复方向：构建期剥离 security.selinux xattr（tar/cpio 不保留 xattr 或显式清除），
  使文件落盘后按路径规则获得 app_data_file 标签。

### 里程碑
- M1 诊断（随下一个测试包）：
  - 容器内 ls -Z /usr/bin/dpkg、ls -Z /var/lib/dpkg/status 确认实际标签；
  - 同一路径 touch 新文件 + rename 测试（区分"标签问题"与"文件系统问题"）；
  - 若可见 getfattr，读 security.selinux xattr 验证根因假设。
- M2 构建期修复：rootfs 打包剥离 xattr + 重新出测试包 → 复测 apt-get install 小型包。
- M3 若 M2 仍被拒：/opt 白名单方案——apt 下载 .deb 后 dpkg --instdir=/opt/... 解包 +
  自定义 PATH/ldconfig 注入（不动 /usr），验证常见包可运行。
- M4 规模化：验证 20+ 常见包（python3-pip、git、gcc 等）安装矩阵，输出兼容性清单。

### 风险与回退
- 荣耀额外策略拦截其他路径 → 维持"构建期预装 + 运行期 /opt 白名单"双轨；
- apt 依赖脚本（postinst）写 /usr → 白名单模式不覆盖，仅覆盖纯二进制/库类包。

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

### T5b gcc（体积权衡，建议依赖 T3 或走 zig 试点）
- 约束：gcc 依赖闭包（gcc-13+cpp+binutils+libgcc-13-dev+linux-libc-dev+头文件）约 400–500MB，
  直接构建期解包会让 rootfs.bundle（现约 242MB）翻倍，不划算。
- 路线 1（推荐）：T3 解决后运行期 apt install gcc/build-essential。
- 路线 2（中间态）：zig cc 单文件 aarch64-linux 静态二进制（约 90MB，自带 clang 前端），
  可编译 C/C++，体积可控、无依赖。
- 路线 3（应急）：tcc（极小但现代 C 支持有限）。
- 验收：cc 编译 hello.c 成功执行；gcc --version（zig 路线为 zig cc --version）。

---

## 状态跟踪

| 任务 | 优先级 | 状态 | 下一步 |
|---|---|---|---|
| T1 Landlock 沙箱 | 高（P0-3） | 已立项 | M1 真机探测（python3 ctypes + lsm 列表） |
| T3 apt/dpkg | 高（P0-2） | 已立项 | M1 诊断（ls -Z 标签 + xattr 验证） |
| T5a git | 中（P0-1） | 已立项，建议先行 | 构建链集成（arm64 runner 解包 .deb） |
| T5b gcc | 低（体积权衡） | 已立项 | 等 T3；或 zig cc 试点 |

## 里程碑推进记录

| 日期 | 任务 | 进展 |
|---|---|---|
| 2026-08-18 | 立项 | 本文档建立；调研结论入库；ROADMAP 关联 |
