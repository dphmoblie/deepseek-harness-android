# 移动端 DSH 插件兼容、对话 UI 与轻量化部署设计

> 目标：让桌面 DSH 插件生态（dsh-web-ui 全家桶、dshmarket、modlens、archify 等）在手机
> WebView 内的 Harness Web UI 上可用；为各部分给出移动布局方案；应用打开后直接进入
> Harness 对话，并通过固定 Release 运行时保持 APK 轻量。
> 参考实现：kelai141 生态的 @dsh-android/dsh-client-ui-responsive（编译产物在本机
> .dsh-dl/dsh-android-dsh-client-ui-responsive-0.1.0.tgz，本文中的模式均经其代码核实）。

## 1. 现状基线（0.1.7）

- **三端结构**：Capacitor 启动与管理 UI（src/）→ 原生 HarnessActivity 内的移动对话 UI
  （rootfs 内 dsh web，仅 127.0.0.1 + preload 认证）→ runtime profile 插件集
  （rootfs 配方：Ubuntu 24.04 ARM64 + Node 24.19 + @deepseek-ai/dsh 0.1.0-rc.6）。
- **入口契约**：运行时就绪时启动应用直接恢复最近会话；服务、Ubuntu、终端、重置、
  来源和 Shizuku 统一放在设置页，无额外引导或用户登录页。
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
| dsh-liangshen（preset） | 入口并入 Harness 设置页，不占用对话首屏 | P2 |
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

## 4. 应用入口与管理

1. **直接对话**：运行时为 ready 时自动启动 Harness，为 running 时直接复用；
   HarnessActivity 恢复最近未归档会话，没有会话时自动新建。
2. **首次安装**：运行时不存在时只显示“安装并进入对话”的状态入口。官方 APK 已固定
   同一 Release 的 manifest URL 与 SHA-256，用户无需填写来源或完成额外配置步骤。
3. **设置归位**：Harness 服务启停、Ubuntu 安装进度和重置、Ubuntu/设备终端、
   终端显示设置、运行时来源详情以及 Shizuku 授权/连接均在设置或二级页。
4. **导航**：Harness 原生工具栏的设置和返回动作回到管理页；恢复焦点不会自动循环重开
   HarnessActivity。

## 5. 轻量化部署方案

| 层 | 现状 | 轻量动作 |
|---|---|---|
| APK | `0.1.7` arm64-only，release 已开 minify+shrinkResources | CI 删除 rootfs/manifest/`.bak` assets，只发布瘦 APK |
| 运行时 | CI 生成 Ubuntu + Node + dsh bundle | 与 APK 同 tag 发布；manifest URL 和摘要在构建时固定，无手工配置 |
| 插件集 | rootfs 配方含完整 dsh | 移动 profile 白名单：默认只装 P0/P1 集（见 scripts/mobile-profile.example.json），dshmarket 按需补装 |
| 传输 | preload 认证 | HTTP Basic 与 WebSocket HttpOnly Cookie 共用每次启动的新 token |
| 下载 | Release rootfs 远程获取 | digest 命名 partial + HTTP Range，网络错误保留合法断点 |

## 6. 落地状态

1. 移动 profile 规格与 `dsh-mobile-compat` 客户端插件已接入 rootfs 配方。
2. `harness-web` 已改为会话抽屉、聊天主视图、任务/文件/设置二级页，并支持模型、
   推理强度、排队/引导发送与结构化消息渲染。
3. Capacitor 已采用无额外引导的直接对话入口，管理能力集中到设置，不再使用四栏主导航。
4. Shizuku 将授权与 UserService 连接分开显示；未连接时设备终端不可用且提供显式连接。
5. CI 自动构建移动前端和 rootfs，生成同 tag Release manifest，并把其 URL/摘要固定进
   `0.1.7` 瘦 APK。
6. 真机验收以 `docs/mobile-acceptance-checklist.md` 为准。

## 7. 边界与风险

- 不改桌面插件与桌面 DSH 核心（只新增客户端适配层与移动 profile 配置）；
- 插件在移动端的禁用/替换需在设置页明示（透明性）；
- 16KB/PRoot 运行器两个作者钉死件不因本分支变更（运行时安装依赖不变）；
- MIT/GPL/AGPL 义务不变（新增代码 MIT，与仓库一致）。
