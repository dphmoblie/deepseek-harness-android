# 主程序「版本管理」页窄屏排版故障修复

## 1. 现象与用户口径

- 用户原话：「本地版本库离线保存的 dsh 运行时包 部分页面存在故障 一行只有两个字 格式错误 修复 合并 推送main」
- 澄清答复：故障页 = **主程序的「版本管理」页**；修复力度 = **连同全局规则一起收窄**（方案 B）。
- 现象：版本管理页中列举「本地版本库（离线保存的 dsh 运行时包）」的条目，版本号那一行被压到只有约两个汉字宽，逐字换行。

## 2. 证据链（已核实）

1. `harness-web/android.css` **不注入主程序前端**。全仓检索 `dsh-android` / `android.css` 引用，仅命中
   [build.gradle](file:///d:/data/project/deepseekharness/app/android/app/build.gradle#L55) 的 license 打包行与
   [package.json](file:///d:/data/project/deepseekharness/app/harness-web/package.json#L7)。
   `android.css` 只随 `harness-web/dist` 进入 rootfs 里的 dsh 前端文档。故版本管理页与本层的
   `#root *{min-width:0}` 无关，只吃 [styles.css](file:///d:/data/project/deepseekharness/app/src/styles.css)。
2. 版本管理页共 **三处** `.release-row` 列表，结构一致（`文本列 + 右侧动作列`）：
   - 本地版本库：[App.tsx:2384-2450](file:///d:/data/project/deepseekharness/app/src/App.tsx#L2384-L2450)（锁定 / 切换至此版本 / 删除此版本）
   - 在线可用版本：[App.tsx:2507-2560](file:///d:/data/project/deepseekharness/app/src/App.tsx#L2507-L2560)
   - 会话备份：[App.tsx:2705-2760](file:///d:/data/project/deepseekharness/app/src/App.tsx#L2705-L2760)
3. 布局契约（[styles.css:2803-2848](file:///d:/data/project/deepseekharness/app/src/styles.css#L2803-L2848)）：
   - `.release-row { display:flex; justify-content: space-between; gap:12px }` —— **无 `flex-wrap`**
   - `.release-copy { min-width:0 }` —— 允许收缩到 0
   - `.release-actions { flex: none }` —— **`flex: 0 0 auto`，拒绝收缩**，宽度恒等于按钮的 max-content
4. 按钮度量（[styles.css:629-643](file:///d:/data/project/deepseekharness/app/src/styles.css#L629-L643)）：
   `min-height:2.75rem; padding:10px 15px; font-size:.875rem; gap:8px`，配 18px 图标。
   实测估算：`锁定`≈84px、`切换至此版本`≈140px、`删除`≈84px，加 8px 间距合计 **≈316px**。
   360px 手机减去屏幕与卡片内边距后行宽 ≈ **296px**。
5. 结论：动作列（`flex:none`，≈316px）大于行宽（≈296px）且不可换行，`justify-content: space-between`
   下 `.release-copy` 被压到 ≈0 → 版本号逐字换行，即「一行只有两个字」。
   该风险在 [styles.css 注释](file:///d:/data/project/deepseekharness/app/src/styles.css#L2796-L2802) 里已被预判，
   但当时的对策只给了 `.release-copy` 的 `min-width:0`，没有解决**动作列不可换行**这一半。

## 3. 修复方案

### A. 必做：`src/styles.css` 让行在空间不足时把动作换到下一行

改 `.release-row` / `.release-copy` / `.release-actions` 三个声明块，改为**容器驱动的换行**，
不引入固定断点魔法数字，桌面宽屏行为保持不变：

```css
.release-row {
  display: flex;
  flex-wrap: wrap;              /* 新增：空间不足时允许动作列落到下一行 */
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  ...
}

.release-copy {
  ...
  flex: 1 1 160px;              /* 新增：给出可读下限，避免被压到 0 */
  min-width: 0;                 /* 保留 */
}

.release-actions {
  ...
  flex: 0 1 auto;               /* 由 none 改为可收缩 */
  flex-wrap: wrap;              /* 新增：按钮自身也允许换行 */
}
```

效果：宽屏（行宽 > 文本下限 + 动作列 max-content）时与现状**逐像素一致**（仍是一行、space-between）；
窄屏时动作列整列落到第二行，文本列独占第一行全宽 —— 版本号不再逐字换行。

### B. 用户要求追加：`harness-web/android.css` 收窄 `#root *{min-width:0}`

[android.css:127-130](file:///d:/data/project/deepseekharness/app/harness-web/android.css#L127-L130) 的
`#root, #root *{ min-width:0 }` 是 **ID 级特异性（1,0,0）**，会压过官方一切类选择器的 `min-width`
（例如 [android.css:144-146](file:///d:/data/project/deepseekharness/app/harness-web/android.css#L144-L146)
自己声明的 `.md-table-wide > table{min-width:max-content}` 就被它反压）。改为**只保留
外壳三列**的 `min-width:0`，把官方后代的 `min-width` 语义还回去：

- 删除 `#root *{ min-width:0 }`（保留 `html, body, #root` 的顶层声明与 `#root` 单独声明）
- 在已有的三条外壳列规则（`grid-area: 1/1`、`grid-area: 1/2`、`grid-area: 1/3`，android.css:219-229）
  上分别补 `min-width: 0`
- 保留 `[role='dialog'] > *{ min-width:0 }`（弹窗直系子列仍需可收缩）

风险：后代恢复 `min-width:auto` 后，官方依赖它的宽内容（表格、长 URL）在极端窄屏可能横向溢出。
缓解：`md-table-wide` 有独立横向滚动、`#root` 仍 `min-width:0`；落地后必须在 360px 视口回归
三列外壳、设置弹窗、含宽表格的会话三类页面。

### C. 若 B 落地，离线运行时包必须重建

`android/app/src/main/assets/runtime/rootfs.bundle` 内嵌的是 `harness-web/dist`（含 android.css）。
B 改了样式就**必须**走既有流水线，否则手机上仍是旧样式：

1. `harness-web` 子模块内提交（样式 + 必要的 `scripts/anchor-contract.mjs` 同步、单测）
2. 更新 `harness-web.pin`（契约测试强制 pin = 索引 gitlink = HEAD gitlink，且子模块换版必须是单独可复核的提交）
3. `python scripts/rebuild-rootfs-frontend.py` 重建 `rootfs.bundle` + `runtime-manifest.json`
   （streaming 重写 tar，含 .bak 事务回滚与逐条校验）
4. `python scripts/verify-bundle.py` 复核

## 4. 范围外（明确不做）

- 不改 dsh 官方产物（`harness-web` 铁律）
- 不动 `[data-width-handle] / [data-side]` 隐藏、抽屉、设置弹窗单列化等现有适配规则
- 不调版本号
- 不动 `.settings-status-row`（运行时版本列表：无按钮，实测标签 + 值两栏在 296px 下仍可读，无此故障）

## 5. 验收标准

1. 360×800（及 412×915）视口打开 版本管理 → 本地版本库 / 在线可用版本 / 会话备份：
   版本号行**独占一行且可读**，动作按钮在下一行。
2. ≥900px 视口：`.release-row` 仍是单行、文本左、动作右（与修复前截图逐像素一致）。
3. `npx tsc --noEmit`、前端单测（含 `App.versions.test.tsx`）、`npm run build` 全绿。
4. 若落地 B：`harness-web` 的 `pnpm test` 全绿、`pnpm build` 通过、`scripts/mobile-compat-contract.test.mjs` 全绿、
   `verify-bundle.py` 通过，且 360px 视口 dsh 外壳/设置弹窗/宽表格三类页面无横向溢出。
5. 变更提交并 `git push origin main` 成功。

## 6. 待用户确认

- **B、C 是否执行**：故障根因在 A；B 是用户点选的"更彻底"项，代价是必须重建 238MB 离线运行时包 + 子模块换版流程。
- 若只想先解决现象，可只做 A，B/C 另起一次改动。
