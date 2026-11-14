# 官方 dsh 移动端支持核对（0.2.1-rc.1）

核对时间：2026-10-17。目标：确认「官方最新版已经支持移动端」是否成立，并据此决定
`harness-web` 移动适配层是保留还是归档。

## 1. 版本事实

| 来源 | 版本 | 说明 |
| --- | --- | --- |
| npm `@deepseek-ai/dsh` `next` | `0.2.1-rc.1` | 2026-10-17 03:17 UTC 发布，是目前最新的官方内核 |
| npm `latest` | `0.2.0-rc.2` | 仍是旧稳定 tag，未追平 `next` |
| GitHub Releases | `dsh-v0.2.1-alpha.5` | `rc.1` 的 npm 包已发，GitHub Release 尚未补 |
| 上游仓库 `master` | `1517a4db` | 2026-10-16，0.2.1 线 |

本仓因此把运行时与官方前端统一钉到 **`0.2.1-rc.1`**，而不是停在 `latest` 的
`0.2.0-rc.2`。

## 2. 移动端能力核对（官方前端产物）

直接检查 `@deepseek-ai/dsh-web-frontend@0.2.1-rc.1` 的 `dist`：

- `index.html` 有 `viewport`（`width=device-width, initial-scale=1`）与
  `manifest.webmanifest`，可以「加到主屏幕」，但这只是 PWA 外壳。
- 两份 CSS 的 `@media` 只有 `prefers-reduced-motion: reduce`：
  - 没有 `max-width` / `min-width` 窄屏断点；
  - 没有 `safe-area-inset-*`；
  - 没有 `hover: hover` / `pointer: coarse` 的整页分支；
  - 没有手机端侧栏抽屉或全屏设置页。
- 部分客户端插件源码里能看到 `@media (hover: hover)`、`pointer: coarse` 与
  `100dvh`（例如 `ui-chat`、`ui-attachment`），说明官方在做触屏细节，但**没有**
  提供手机布局。

上游仓库里另有一份 `.agents/skills/dsh-responsive-ui/SKILL.md`，写的是
390 CSS px 验收下限、移动端 hover/tooltip/键盘规范。它是**给贡献者的设计标准**，
不是已经落地的移动端外壳。

结论：**「官方最新版已经支持移动端」不成立**（至少 `0.2.1-rc.1` 不成立）。官方
只做到「触屏细节 + PWA 可安装」，没有窄屏布局。npm 上的 `dsh-mobile-*`、
`@tecfancy/dsh-mobile` 等是社区插件，不是官方产物。

## 3. 对本仓的决定

1. 保留 `harness-web` 适配层，不归档。它提供的窄屏抽屉、44px 触控目标、安全区、
   输入框 16px 防缩放、设置页单列等，官方 0.2.1 仍未提供。
2. 适配层跟随官方前端升到 `0.2.1-rc.1`，锚点契约（`scripts/anchor-contract.mjs`）
   继续作为升级闸门：官方删锚点就构建失败，而不是静默失效。
3. 运行时补丁按 `0.2.1-rc.1` 重建；`pi-ai` 补丁跟随上游 lockfile 的 `1.1.0`。
4. 等官方真正落地窄屏布局后，再按 `harness-web/README.zh-CN.md` 的约定归档：
   删除子模块与 `android.css`，不做规则迁移。

## 4. 复核命令

```sh
# 官方前端产物里是否出现窄屏断点/安全区
rg -o '@media[^{]+' harness-web/node_modules/@deepseek-ai/dsh-web-frontend/dist/assets/*.css

# 本仓适配层锚点是否仍全部存在（构建期会自动跑）
pnpm --dir harness-web build

# 版本钉一致性
node --test scripts/mobile-compat-contract.test.mjs
```
