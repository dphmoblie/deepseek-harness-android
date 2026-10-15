# Checklist: 同类故障修复（Round 2 第二轮深度审查）

> 审查基线：`e5cbad5e` / `20444014` / `63304ba9` / `dbb4a154` / `f9a604cb` / `2f6196d5`

## 代码检查点

- [x] R2-1 `schedulePostStartModuleGraph` 任务体在探测开始前判断 `closed.get()` / `supervisor.isRunning()`，
      控制器已关闭或 Harness 未运行时跳过探测（`MobileRuntimeController.kt` L201）
- [x] R2-2 提交点前置 `postStartDiagnosticsExecutor.isShutdown` 判断并用 `runCatching` 兜底，
      `execute()` 不再向 `startHarness` 抛出 `RejectedExecutionException`（同文件 L195-196）
- [x] R2-3 `src/App.tsx` keepAlive 失败分流消费 `foregroundStartError`：令牌存在时一律走
      `background-blocked`，不再因 `notificationPermission === 'prompt'` 把用户误导到通知设置页（L6469）
- [x] R2-4 `HarnessActivity` cookie 回调 `LOAD` 分支守卫命中时改为
      `Handler(Looper.getMainLooper()).post` 主线程重投递，页面不会静默白屏（L162/L168）

## 验证检查点

- [x] `./gradlew :app:compileDebugKotlin` 通过（exit 0）
- [x] `npx tsc --noEmit` 无输出（通过）
- [x] `vitest run src/App*.test.tsx src/__tests__` 通过（Test Files 5 passed, Tests 71 passed）

## 约束检查点

- [x] 未 commit、未 push
- [x] 未触碰临时产物（`screen*.png` / `ui.xml`）与 3 个在途 WIP 文件
- [x] 未重复报告已确认的误报项（`isRunning` 乐观置位、`foreground_failed`/`foreground_declined`
      分工、`ApplicationPicker` 800ms 兜底、`VirtualScreenActivity.refresh` 停轮询、
      清单 `specialUse` 类型与权限声明、API 34+ 无类型回退）
