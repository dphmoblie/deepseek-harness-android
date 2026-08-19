# dsh-mobile-compat

这是一个实验性的移动端 Harness Web UI 客户端插件，提供抽屉侧栏、bottom-sheet 详情和
插件 CSS 覆盖。它没有加入默认 Android profile：官方
`@deepseek-ai/dsh-client-ui-layout` 已经提供经过验证的响应式布局，并且还提供官方
`ctx.layout` 服务；同时加载两套 root 布局会造成单槽冲突并使插件加载失败。

如需继续开发此插件，必须先实现与官方 layout service 完整兼容的替代层，再通过独立 profile
进行验证，不能直接把它插入默认运行时。

设计依据：docs/mobile-plugin-compat.md（本仓库）。
参考实现：@dsh-android/dsh-client-ui-responsive（kelai141 生态）。
