# dsh-mobile-compat

移动端 Harness Web UI 客户端插件：<640px 切换为抽屉侧栏 + bottom-sheet 详情 + 顶栏的
移动 AppFrame，并注入桌面插件（aionui-panel / task-board / market）的移动 CSS 覆盖。
桌面宽度下保留三栏契约（CENTER_MIN 640 求解器），同一插件在浏览器 profile 亦安全。

设计依据：docs/mobile-plugin-compat.md（本仓库）。
参考实现：@dsh-android/dsh-client-ui-responsive（kelai141 生态）。
