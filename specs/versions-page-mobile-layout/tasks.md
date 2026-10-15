# 版本管理页窄屏排版故障 —— 任务清单

> 依赖：`specs/versions-page-mobile-layout/spec.md`
> 未确认项：方案 B/C 需用户批准后才执行（见 spec §6）。

## [x] T1 [必做] 修复 `.release-row` 换行（`src/styles.css`）

- 文件：`src/styles.css`
- 位置：`.release-row`（2809）、`.release-copy`（2823）、`.release-actions`（2843）
- 改动：
  - `.release-row` 增加 `flex-wrap: wrap`
  - `.release-copy` 增加 `flex: 1 1 160px`
  - `.release-actions` 把 `flex: none` 改为 `flex: 0 1 auto`，并增加 `flex-wrap: wrap`
- 保持：`justify-content: space-between`、`gap`、`min-width:0`、边框与字号全部不动
- 同步注释：补一句说明"空间不足时动作整列换行，避免文本列被压到 0"
- 验证：`npx tsc --noEmit`；`npm run build`

## [x] T2 [必做] 窄屏视口回归（真实 `styles.css` 浏览器实测）

- 360×800 与 412×915：版本管理 → 本地版本库 / 在线可用版本 / 会话备份，行内版本号独占一行
- ≥900px：单行布局与修复前一致
- 产物：截图留档（可选，放 `output/debug/`）

## [x] T3 [必做] 前端单测

- `npx jest src/App.versions.test.tsx`（或仓库既有单测入口）
- 全套前端单测与 TS 类型检查通过

## [x] T4 收窄 `#root *{min-width:0}`（`harness-web/android.css`）

- 删除 `harness-web/android.css:127-130` 的 `#root *{ min-width:0 }`，保留 `#root{ min-width:0 }`
- 在 219 / 223 / 227 三条外壳列规则上各补 `min-width: 0`
- 保留 `[role='dialog'] > *{ min-width:0 }` 与 `[role='dialog']{ min-width:0 }`
- 若涉及锚点变化，同一次提交更新 `harness-web/scripts/anchor-contract.mjs`
- 验证：`harness-web/` 下 `pnpm test`、`pnpm build`

## [x] T5 离线运行时包

- `harness-web` 子模块内提交 `28d92f0`（单独可复核的提交）
- `harness-web.pin` 已更新为该提交
- **落地方式修正**：`rootfs.bundle` / `runtime-manifest.json` 位于
  `android/app/src/main/assets/runtime/`，被 `.gitignore` 忽略、**不纳入版本管理**；
  它们由 CI（`build-embedded-runtime.py` → `rebuild-rootfs-frontend.py`，见
  `.github/workflows/android-build.yml:404-419`）用 `harness-web/dist` 现场生成。
  故本地不重写这两份产物；子模块换版后，下一次 CI 构建出的离线包即含本次样式。
- 验证：`pnpm run test:scripts`（含 `mobile-compat-contract.test.mjs`，pin = 索引 gitlink = HEAD gitlink 三处一致）

## [ ] T6 [必做] 提交并推送 main

- 提交信息沿用仓库风格：`fix(版本管理): 窄屏行内动作换行，避免版本号被压成逐字换行`
- `git push origin main`（历史多次网络超时失败，失败则提示检查网络/代理后重试）
