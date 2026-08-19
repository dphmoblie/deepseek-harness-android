# 真机探测报告：Landlock / SELinux / apt（2026-08-18）

> 环境：Android GKI 内核 6.12.38-android16 aarch64 ｜ Ubuntu 24.04.4 rootfs（proot，应用私有目录）
> 身份：容器内 uid=0（伪 root），真实 uid 10368，SELinux u:r:untrusted_app:s0:c141,c257,c512,c768
> 探测方式：perl syscall() 原生系统调用（python3 未安装；aarch64 syscall 号数已核实）
> 关联：docs/long-term-tasks.md（T1/T3 判定结论）｜ 依据：用户真机实测（2026-08-18）

---

## 结论概览

| 检查项 | 结果 | 结论 |
|---|---|---|
| Landlock syscalls (444/445/446) | 全部 errno 38 (ENOSYS) | 本进程无法调用 Landlock，与 DSH "no sandbox backend usable" 吻合 |
| /sys/kernel/security/lsm | 不存在（securityfs 未挂载） | 无法从该路径读 LSM 列表 |
| security.selinux xattr（/usr/bin/dpkg 等） | u:object_r:app_data_file:s0:c141,c257,c512,c768 | ls -Z 与 stat -c %C 双通道确认 |
| rename 测试 | /tmp OK；/usr/bin/dpkg 改名并还原成功 | 无 LSM 拦截 rename |
| unshare(CLONE_NEWUSER) | ENOSYS | user namespace 对应用禁用 → bwrap/userns 沙箱后端不可用 |
| bwrap | 未安装 | bubblewrap 后端不可用 |

## 1. Landlock 探测

- create_ruleset(444) NULL/0 → errno=38；add_rule(445) fd=-1 → errno=38；restrict_self(446) fd=-1 → errno=38。
- **关键陷阱**：本进程在 seccomp 过滤下（Seccomp: 2，2 个过滤器，NoNewPrivs: 1），
  ENOSYS 不一定是"内核未编译 Landlock"——Android 应用策略对未列入白名单的 syscall 默认返回 ENOSYS。
- 对照实验：

| syscall | 结果 | 解读 |
|---|---|---|
| getpid | OK | 白名单内，正常执行 |
| openat2(437) | EFAULT（NULL 参数） | 被放行，参数错误才报错 |
| pidfd_open(434) / clone3(435) | EINVAL | 被放行，参数错误 |
| bpf(280) | EPERM | 显式禁止 → EPERM |
| fsopen(430) | ENOSYS | 未列入白名单 → 默认 ENOSYS |

- 因此 landlock 的 ENOSYS = "该进程的 seccomp 策略未放行 landlock"；内核是否编译
  CONFIG_SECURITY_LANDLOCK 在容器内不可验证（/proc/config.gz、/proc/kallsyms、/proc/cmdline
  均 Permission denied；/sys 被掩蔽，/sys/module 仅 1 个条目，无 /sys/module/landlock——
  缺失不构成证据；securityfs 未挂载，无 /sys/kernel/security/lsm）。
- 网查证据：Android kernel/common 源码中存在
  [landlock Kconfig](https://android.git.googlesource.com/kernel/common/+/refs/tags/android-15-qpr2-beta-1_r0.7/security/landlock/Kconfig)
  与 [landlock.rst 文档](https://android.git.googlesource.com/kernel/common/+/391008f34e711253c5983b0bf52277cc43723127/Documentation/userspace-api/landlock.rst)，
  但无证据表明 GKI defconfig 启用；Android 安全模型基于 SELinux + seccomp，不依赖 Landlock。
- **结论：无论内核是否编译 Landlock，该进程均无法调用 → Landlock 在此环境不可用。**

## 2. SELinux / apt 诊断

- 进程上下文：u:r:untrusted_app:s0:c141,c257,c512,c768（Android 应用域 + MLS categories）。
- 文件标签：/usr/bin/dpkg、/usr/bin/apt、/bin/sh、/etc/passwd 全部
  u:object_r:app_data_file:s0:c141,c257,c512,c768 —— **整个 rootfs 统一标签**，
  落在应用 app-data 分区内，SELinux 对应用自身数据不设额外限制。
- xattr 双通道确认：ls -Z 与 stat -c %C 一致（getfattr 未安装；node v24 此构建无 getxattrSync）。
- selinuxfs：/proc/self/mounts 显示 selinuxfs 已挂载于 /sys/fs/selinux，但容器内访问为
  ENOENT（内容掩蔽）；getenforce 未安装 → enforce/permissive 不可直读；
  Android untrusted_app 默认 enforcing。
- **rename 测试全通过**（/usr/bin/dpkg → dpkg.renametest → 还原）：
  说明 dpkg 安装失败**不是** rename/标签问题。
- 结合既有事实（untrusted_app 域禁 link()，本项目已实测并做 link→copyFile 降级）：
  **apt/dpkg 失败根因 = dpkg 的 backup 硬链接调 link(2) 被拒**
  （"unable to make backup link ... Permission denied"）。
- 推论：**全新包安装（目标文件不存在）不触发 backup link**，理论上可直接成功；
  覆盖已有文件的升级会失败。

## 3. 环境事实

- 内核 6.12.38-android16 aarch64（GKI）；rootfs Ubuntu 24.04.4；
  根设备 /dev/block/dm-97（458G，Android userdata）；应用经 incremental-fs 安装
  （/data/app/~~ke4...）；/proc 以 hidepid=invisible,gid=3009 挂载。
- seccomp 2 filters + NoNewPrivs=1（所有 Android 应用进程统一策略）。

## 4. 对 DSH 的意义

- workspace-write 沙箱后端（Landlock / bubblewrap / userns）在此容器**全部不可用**，
  与 DSH 报错一致 → bash/文件工具只能走 danger-full-access（此前需审批的原因）。
- 容器内无法修复（无 userns、无 landlock、无权限挂载 securityfs）；
  恢复沙箱需在正常 Linux 环境运行 DSH，或采用"显式无沙箱模式"降级（见 long-term-tasks.md T1）。
- 遗留疑点：python3 未安装（工具链注入应含 python3.13.5）——需确认探测容器是否为
  #132 测试包；若非，工具链注入待查。
