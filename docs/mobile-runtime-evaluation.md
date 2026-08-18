# DSH 移动版运行环境评估报告

> 评估基线：Android 16（6.12.38-android16-aarch64）+ Ubuntu 24.04 容器（rootfs 位于应用私有目录，proot 用户态模拟 root）。
> 实测身份：容器内 uid=0（伪装），真实 uid=10368（io.deepseekharness.mobile），SELinux 域 untrusted_app。
> 日期：2026-08-17 ｜ 素材：真机实测 + 构建链实测 ｜ 关联文档：ARCHITECTURE.md / mobile-acceptance-checklist.md

---

## 1. 评估范围

- 运行时形态：APK 内置 PRoot 运行器 + Ubuntu 24.04 rootfs（离线嵌入版），dsh web 在容器内监听 127.0.0.1:3080
- 评估维度：① 系统能力与权限边界；② 工具链可用性；③ 插件/服务可达性（Shizuku 等）；④ 用户体验
- 已确认可用的能力：网络（resolv.conf 宿主注入）、node v24、perl、tar、15GB 内存 / 458G 存储

---

## 2. 严重问题（P0）

### P0-1 基础工具链缺失
- **现象**：容器内无 python3、gcc、curl、wget、npm；只有 node v24、perl、tar。
- **影响**：绝大多数 agent 任务（编译、脚本、依赖管理）第一步即卡住；用户被迫手写 Node 脚本下载静态二进制。
- **改进建议**：
  - rootfs 构建期**预装最小工具链**：python3 + pip、curl、wget、git、gcc/g++、build-essential、unzip（ubuntu-base 之上叠加 deb 层）；
  - 或内置一键安装引导：`dsh tool install python|gcc|git`（下载静态二进制/预编译包）。

### P0-2 包管理不可用（apt/dpkg 被 SELinux 拒绝）
- **现象**：`apt-get install` 在 dpkg 阶段失败：`unable to make backup link ... Permission denied`——untrusted_app 域无法 rename 已有 system_file。
- **影响**：无法在运行期安装任何系统包，工具链只能靠容器外渠道补充。
- **改进建议**：以"预装替代在线安装"为原则；若必须在线安装，容器根改用应用可写的 overlay 层，或安装到 `/opt` 等未受保护路径后调整 PATH。

### P0-3 沙箱后端不可用，安全模型名存实亡
- **现象**：每次命令执行报 `no sandbox backend is usable`，被迫 danger-full-access；write/edit 文件工具被 EACCES 拒绝（link 原子写被拒——**本项目已在 rootfs 构建期补 link→copyFile 降级，会话/附件写入已修复**，但文件工具的沙箱层仍无可用后端）。
- **影响**：① 权限提示噪音大；② 用户无法通过正常文件工具工作，只能 bash 绕过；③ 移动版"沙箱"承诺不成立。
- **改进建议**：
  - 利用内核 6.12 的 Landlock 实现**用户态沙箱**（无需 root/namespace），恢复 write/edit 工具的受限写入；
  - 或移动版显式降级为"无沙箱模式"，在会话初始上下文显著提示真实权限模式。

### P0-4 假 root 误导
- **现象**：`id` 显示 uid=0，但 /proc/self/status 真实 uid=10368、SELinux untrusted_app；写 /system、/data 均被拒。
- **影响**：用户与 agent 误判能力边界，排错方向错误。
- **改进建议**：容器初始化时向 agent 注入明确的权限边界描述（`whoami` 语义、可写路径白名单），UI 显示"容器内 root（受限）"。

---

## 3. 中等问题（P1）

| # | 问题 | 现象 | 影响 | 建议 |
|---|---|---|---|---|
| P1-1 | Shizuku 授权不可用 | /dev/binder、/dev/hwbinder 可见，但容器内无 binder 工具链；/dev/socket/adbd 无权限、无 5555/5037 监听；/dev/__properties__ 权限拒绝 | 已授权却无法做系统级操作，只能用户手动 | 预装 adb（`adb connect 127.0.0.1` 走无线调试）；内置 binder CLI 客户端；文档化容器内 Shizuku 调用链 |
| P1-2 | 手机文件系统不可达 | 容器内看不到 /data、/sdcard | 用户无法让 agent 处理手机文件（下载/相册/脚本） | 将 /sdcard 或用户选定目录 bind 进容器（只读/可写可选）——移动版差异化能力 |
| P1-3 | 工具链错误信息冗长 | dpkg 的 SELinux 底层报错直接抛给用户 | 用户看不懂 | 捕获常见 EACCES/EPERM 并转译为中文提示（"系统限制：无法修改系统文件"） |
| P1-4 | 文件系统视角割裂 | 根 erofs ro（挂载表视角），实际容器根在 f2fs /data 下 | 排查路径问题时困惑 | 文档化容器根布局；提供 `df -h` 视角说明 |

---

## 4. 体验问题（P2）

| # | 问题 | 建议 |
|---|---|---|
| P2-1 | 空消息无引导（发"。"直接空会话） | 检测空/纯标点输入，弹出引导菜单（"你可以：…"） |
| P2-2 | 沙箱降级提示不透明（只在报错时出现） | 会话初始化展示实际权限模式 |
| P2-3 | 工具链下载无内置通道 | 内置 `dsh tool install` 一键命令 |
| P2-4 | 宠物系统（whale-girl）与核心功能割裂、占空间 | 可折叠/设置内隐藏 |
| P2-5 | 自动启动 Harness 打扰 | 已提供设置开关并默认关闭（v0.1.7 起） |

---

## 5. 已解决项（本轮修复）

- **link EACCES 全链路**：会话持久化 + 附件存储的 link 原子写已降级 copyFile（荣耀等 ROM 实测通过）；
- **profiles 扁平链接悬空**（Cannot find package）：scoped 包相对路径少一层 .. 已修复 + verify 全量校验（含防伪补丁校验）；
- **API Key 配置**：设置页直接填写并注入 DEEPSEEK_API_KEY（不再依赖终端）；
- **对话错误详情**：turn/end 错误详情直接展示（替代笼统"本轮因错误终止"）。

---

## 6. 改进优先级路线图

| 优先级 | 项 | 工作量 | 收益 |
|---|---|---|---|
| P0-1 | rootfs 预装工具链（python3/curl/git/gcc） | 中（构建脚本） | 消除 80% "装不了"问题 |
| P0-2 | 包管理可用性（预装/overlay） | 中 | 在线装包成为可能 |
| P0-3 | Landlock 沙箱或显式降级 | 大 | 恢复文件工具 + 权限透明 |
| P1-1 | Shizuku 工具链（adb/binder CLI） | 中 | 已授权能力真正可用 |
| P1-2 | bind /sdcard 进容器 | 中 | 移动版杀手级能力 |
| P2 | 空消息引导、错误转译、一键工具安装 | 小 | 体验显著提升 |

**建议下一步**：优先 **P0-1（工具链预装）**——对话已跑通，接下来"agent 能不能干活"取决于工具链；其次是 **P1-2（/sdcard 可达）**，这是移动版区别于桌面的核心场景。

---

*本报告素材来自真机实测；改进项需结合实际构建链（build-embedded-runtime.py）逐一落地。*
