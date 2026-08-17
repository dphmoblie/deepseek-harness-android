# 移动端真机验收清单（feature/dsh-plugin-compat）

> 适用范围：荣耀 Android 16（arm64/16KB）及任一 arm64 真机；验收对象为本分支
> 交付的 dsh-mobile-compat 插件、Onboarding 向导与 --mobile-profile rootfs 配方。

## 0. 前置

- [ ] APK 构建成功（CI artifact 或本地 assembleDebug）；含 16KB 对齐检查（readelf p_align==0x4000，见 docs/review-2026-08-16.md §6）
- [ ] 运行时可用：内嵌 bundle（embedRootfs=true）或远程 manifest 对已配置（两者必须成对）
- [ ] Shizuku（可选）与设备 Shell 基线可用（含 BIND_USER_SERVICE 声明修复的验证）

## 1. 布局矩阵（dsh-mobile-compat）

在 360px / 480px / 640px / 1024px 四种视口（开发者工具或旋转/分屏）逐一验证：

| # | 检查项 | 预期 |
|---|---|---|
| L1 | <640px 顶栏可见（菜单/详情按钮，safe-area 不遮挡） | 顶栏完整可点 |
| L2 | 菜单按钮开/关抽屉侧栏；scrim 点击关闭 | 侧栏 ≤84vw，动画无跳动 |
| L3 | 详情按钮开/关 bottom-sheet；把手可见 | sheet ≤70vh，内容可滚动 |
| L4 | ≥640px 恢复三栏（sidebar/center/details） | 拖动把手宽幅在契约区间 |
| L5 | 640-1024px 侧栏自动折叠为 rail | 窄屏 rail 可展开覆盖 |
| L6 | 旋转/分屏实时重排，无宽度残留 | 回宽自动恢复 |

## 2. 插件逐项（安装移动 profile 白名单后）

| 插件 | 验收点 |
|---|---|
| dsh-mobile-compat（内置） | §1 全过；桌面宽度三栏不回归 |
| aionui-panel | 文件/预览/变更在窄屏全宽可用；拖拽把手在移动形态隐藏 |
| task-board | 单列全宽 + 横滑列（scroll-snap）不溢出 |
| dshmarket | 卡片单列流；搜索/安装可用 |
| modlens / describe-image | 图片全宽预览，操作条可达 |
| 禁用的悬浮类（pet/live-stats） | 确认默认关闭，设置中明示 |

## 3. 新手引导（Onboarding）

- [ ] 首次启动弹出五步向导；「跳过」后不再出现（localStorage 持久化）
- [ ] 步骤 2 未配置 manifest 对时显示警告；配置后安装按钮可用且安装成功
- [ ] 步骤 3 Shizuku 未装/未授权/已授权三种状态文案与按钮正确
- [ ] 步骤 5 「打开 Harness」在 phase=running 时可点、跳转成功
- [ ] 完成后重启应用，向导不再弹出

## 4. 轻量化指标

| 指标 | 记录值 | 目标 |
|---|---|---|
| APK 大小（无内嵌 rootfs） | ___ MB | <30MB（对比全量内嵌版） |
| 运行时安装体积 | ___ MB | 与 manifest compressedBytes 一致 |
| 空闲内存（Harness 运行中） | ___ MB | 记录基线 |
| 首启到 Harness 可用时长 | ___ 秒 | 记录基线 |
| idleStopMinutes 生效 | 空闲 N 分钟后 runtime 自动停止 | 与 profile 一致 |

## 5. 记录模板

```
设备/系统：荣耀 XX / Android 16（getconf PAGE_SIZE=____）
构建：feature/dsh-plugin-compat @ <commit>
结果：L1-L6 / 插件表 / 向导 / 轻量指标，逐项 ✅/❌ + 截图
```

## 6. 与本分支无关但需同步验证的遗留项

- [ ] docs/review-2026-08-16.md §6 的 16KB/PRoot 检查
- [ ] DeviceShellUserService manifest 声明修复后的设备 Shell 绑定实测
