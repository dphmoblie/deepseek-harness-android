# 移动端 DSH 插件兼容、UI 布局与轻量化部署设计（分支 feature/dsh-plugin-compat）

> 目标：让桌面 DSH 插件生态（dsh-web-ui 全家桶、dshmarket、modlens、archify 等）在手机
> WebView 内的 Harness Web UI 上可用；为各部分给出移动布局方案；设计新手引导；轻量化部署。
> 参考实现：kelai141 生态的 @dsh-android/dsh-client-ui-responsive（编译产物在本机
> .dsh-dl/dsh-android-dsh-client-ui-responsive-0.1.0.tgz，本文中的模式均经其代码核实）。

## 1. 现状基线（2026-08-16 实测）

- **三端结构**：Capacitor 管理 UI（src/，React 32KB App）→ 内嵌 Harness Web UI
  （rootfs 内 dsh web，仅 127.0.0.1 + preload Basic 认证）→ runtime profile 插件集
  （rootfs 配方：Ubuntu 24.04 ARM64 + Node 24.19 + @deepseek-ai/dsh 0.1.0-rc.6）。
- **桌面插件生态**（本机桌面 profile 实测在装）：@linxin666/dsh-web-ui-all（task-board/
  git-graph/pet/remote-web-ui/live-stats/web-ui-settings/aionui-panel/ssh/liangshen/skins/
  skin-center/describe-image）、dshmarket、modlens、archify、distill、notify、dsh-browser。
- **参考插件机制**：dsh web app 客户端插件经 `window.__ModuleLoader__.load({id, factory})`
  注册 React 模块；布局核心是 @deepseek-ai/dsh-client-ui-layout 的三栏 AppFrame
  （sidebar/center/details）；responsive 插件以 Mobile 形态替换 AppFrame
  （<640px：抽屉侧栏、bottom-sheet 详情、顶栏、safe-area），并注入
  @deepseek-ai/dsh-client-runtime + dsh-client-ui-theme。

## 2. 插件兼容矩阵（目标：全部可用 + 移动形态）

| 桌面插件 | 移动形态方案 | 优先级 |
|---|---|---|
| dsh-client-ui-layout（三栏） | 替换为移动 AppFrame（<640px 抽屉/bottom-sheet；三列求解器沿用 computeColumns 纯函数思路） | P0 |
| aionui-panel（Explorer/Preview/SCM） | 底部三 tab（文件/预览/变更），Preview 全宽，拖拽改 sheet 高度（220-500px 区间适配屏幕 40%） | P0 |
| dsh-task-board | 单列全宽 + 卡片横滑切换列（swipeable columns） | P1 |
| dsh-ssh | 表单重排单列；终端 tab 全宽 | P1 |
| dshmarket | 网格 2 列 → 单列卡片流 | P1 |
| dsh-liangshen（preset） | 设置入口并入新手引导与插件设置页 | P2 |
| modlens / describe-image | 图片预览全宽 + 底部操作条 | P2 |
| skins/skin-center | 皮肤中心 2 列网格 | P2 |
| pet / live-stats / git-graph / notify | 悬浮组件禁用或收进详情 sheet（防误触） | P2 |
| dsh-browser / browser 类 | 桌面浏览器桥不可用；手机端由内嵌 WebView 方案替代（见 §5） | 替换 |

## 3. UI 布局适配方案（移动 AppFrame，参考 responsive 编译产物）

1. **断点**：<640px 启用 Mobile 形态；640-1024px 侧栏自动折叠为 56px rail（沿用
   SIDEBAR_AUTO_COLLAPSE=1024 的成熟阈值）。
2. **三列求解器**：sidebar（56/264-420）、center（min 640 桌面；移动时全宽）、details
   （300-520 / bottom-sheet）——纯函数（viewport, preferences)→宽度，无迟滞，
   回宽自动恢复（沿用上游 computeColumns 思路，移动分支 center=viewport）。
3. **移动交互**：抽屉侧栏（左滑）、bottom-sheet 详情（上滑、拖拽把手）、顶栏
   （标题+菜单）、safe-area（env(safe-area-inset-*) 适配刘海/手势条）。
4. **主题桥**：沿用 theme-bridge（system dark 同步 + 首帧深色），移动 WebView 的
   matchMedia 卡 light 问题已在壳侧 H1 修复思路内（preload token 之外同样适用）。
5. **实现载体**：新建客户端插件包 `dsh-mobile-compat`（TS 源码 + 构建，
   以 responsive 的 dsh.client.inject 声明为模板），在 rootfs 配方的 profile 中
   替代 layout 的 AppFrame；不改桌面插件本身（零侵入，与上游策略一致）。

## 4. 新手设置（onboarding）

分两层：

1. **Capacitor 管理 UI 首启向导**（src/ 内新增 OnboardingSteps）：
   ① 设备认证（现有）→ ② 运行时安装（内嵌或远程 manifest 对）→ ③ Shizuku 引导
   （安装/授权，复用 requestShizukuPermission）→ ④ 插件启停（按 §2 矩阵预置移动集）→
   ⑤ 打开 Harness（首次进入时的使用提示 overlay）。
2. **Harness 内首会话引导**：dsh 侧以 preset（liangshen 同款机制）注入简短欢迎指令
   （说明移动布局、插件入口位置、隐私边界：数据在本机/回环）。

## 5. 轻量化部署方案

| 层 | 现状 | 轻量动作 |
|---|---|---|
| APK | debug 已可构建；release 已开 minify+shrinkResources | arm64-only（已配置）；删除调试资源；确认无 x86 快照 |
| 运行时 | 内嵌 .bundle（~100-300MB） | 默认**远程 manifest 对**下载按需安装；内嵌仅作无网兜底（可选构建） |
| 插件集 | rootfs 配方含完整 dsh | 移动 profile 白名单：默认只装 P0/P1 集（见 scripts/mobile-profile.example.json），dshmarket 按需补装 |
| 传输 | preload 认证 | 不变 |
| 内存 | Harness 常驻 | 空闲 N 分钟停 runtime 的既有 stopRuntime 流程（设置项默认开启） |

## 6. 落地顺序（本分支提交计划）

1. ✅ 本设计文档 + 移动 profile 规格（scripts/mobile-profile.example.json）
2. ✅ dsh-mobile-compat 客户端插件包（packages/dsh-mobile-compat，tsc 类型检查通过）
3. ~~dsh-mobile-compat 客户端插件包~~（已完成，见上）
4. ✅ Capacitor 侧 OnboardingSteps（向导五步，40 测试全绿）
5. ✅ rootfs 配方集成（build-embedded-runtime.py --mobile-profile 参数 + selfcheck，
   默认远程 manifest、按需内嵌）
6. ✅ 真机验收清单（docs/mobile-acceptance-checklist.md：布局矩阵/插件逐项/向导/轻量指标；真机执行待设备）

## 7. 边界与风险

- 不改桌面插件与桌面 DSH 核心（只新增客户端适配层与移动 profile 配置）；
- 插件在移动端的禁用/替换需在设置页明示（透明性）；
- 16KB/PRoot 运行器两个作者钉死件不因本分支变更（运行时安装依赖不变）；
- MIT/GPL/AGPL 义务不变（新增代码 MIT，与仓库一致）。
