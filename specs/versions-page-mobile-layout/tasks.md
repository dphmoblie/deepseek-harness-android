# 版本管理页窄屏排版故障 —— 任务清单

> 依赖：`specs/versions-page-mobile-layout/spec.md`
> 未确认项：方案 B/C 需用户批准后才执行（见 spec §6）。

## T1 [必做] 修复 `.release-row` 换行（`src/styles.css`）

- 文件：`src/styles.css`
- 位置：`.release-row`（2809）、`.release-copy`（2823）、`.release-actions`（2843）
- 改动：
  - `.release-row` 增加 `flex-wrap: wrap`
  - `.release-copy` 增加 `flex: 1 1 160px`
  - `.release-actions` 把 `flex: none` 改为 `flex: 0 1 auto`，并增加 `flex-wrap: wrap`
- 保持：`justify-content: space-between`、`gap`、`min-width:0`、边框与字号全部不动
- 同步注释：补一句说明"空间不足时动作整列换行，避免文本列被压到 0"
- 验证：`npx tsc --noEmit`；`npm run build`

## T2 [必做] 窄屏真机/视口回归

- 360×800 与 412×915：版本管理 → 本地版本库 / 在线可用版本 / 会话备份，行内版本号独占一行
- ≥900px：单行布局与修复前一致
- 产物：截图留档（可选，放 `output/debug/`）

## T3 [必做] 前端单测

- `npx jest src/App.versions.test.tsx`（或仓库既有单测入口）
- 全套前端单测与 TS 类型检查通过

## T4 [用户确认后] 收窄 `#root *{min-width:0}`（`harness-web/android.css`）

- 删除 `harness-web/android.css:127-130` 的 `#root *{ min-width:0 }`，保留 `#root{ min-width:0 }`
- 在 219 / 223 / 227 三条外壳列规则上各补 `min-width: 0`
- 保留 `[role='dialog'] > *{ min-width:0 }` 与 `[role='dialog']{ min-width:0 }`
- 若涉及锚点变化，同一次提交更新 `harness-web/scripts/anchor-contract.mjs`
- 验证：`harness-web/` 下 `pnpm test`、`pnpm build`

## T5 [用户确认后] 重建离线运行时包

- `harness-web` 子模块内提交（单独可复核的提交）
- 更新 `harness-web.pin`
- `python scripts/rebuild-rootfs-frontend.py`
- `python scripts/verify-bundle.py`
- 验证：`scripts/mobile-compat-contract.test.mjs` 全绿（pin = 索引 gitlink = HEAD gitlink 三处一致）

## T6 [必做] 提交并推送 main

- 提交信息沿用仓库风格：`fix(版本管理): 窄屏行内动作换行，避免版本号被压成逐字换行`
- `git push origin main`（历史多次网络超时失败，失败则提示检查网络/代理后重试）
