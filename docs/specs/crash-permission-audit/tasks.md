# Tasks: 同类故障修复

> Round 1 的 T1–T9 已随 `e5cbad5e` / `f9a604cb` / `2f6196d5` 落地（此处保留为历史记录）。

- [ ] T1 FIX-1 BootRecoveryReceiver.onReceive 抽出 restoreAfterBoot + runCatching 兜底
- [ ] T2 FIX-2 VirtualScreenActivity.refresh Runnable 加 try/catch，异常 toast + 停循环
- [ ] T3 FIX-5 VirtualScreenService 两处 catch(Exception) → catch(Throwable)
- [ ] T4 FIX-3 App.tsx 复核回调消费 foregroundStartError
- [ ] T5 FIX-4a PermissionGuide 增 shizuku-permission + Dialog 文案/按钮/prop
- [ ] T6 FIX-4b run() 增可选 guide 参数并在 catch 中 setPermissionGuide
- [ ] T7 FIX-4c requestShizukuPermission 传 guide；渲染处传 onOpenShizuku
- [ ] T8 验证：npx tsc --noEmit 通过
- [ ] T9 验证：gradlew :app:compileDebugKotlin 通过

## Round 2（已批准并完成）

- [x] R2T1 R2-1 `schedulePostStartModuleGraph` 任务体加 `if (closed.get() || !supervisor.isRunning()) return`（改法 c）
- [x] R2T2 R2-2 `postStartDiagnosticsExecutor.execute` 前置 `isShutdown` 或 `runCatching` 兜底
- [x] R2T3 R2-3 `src/App.tsx` L6468-6472 用 `foregroundStartError` 参与 keepAlive 分流
- [x] R2T4 R2-4 `HarnessActivity.kt` LOAD 分支守卫改为主线程重投递（`Handler(Looper.getMainLooper()).post`）
- [x] R2T5 验证：`./gradlew :app:compileDebugKotlin` 通过（exit 0）
- [x] R2T6 验证：`npx tsc --noEmit` 无错误 + `vitest run src/App* src/__tests__` 71 通过
- [x] R2T7 不 commit、不 push（已遵守）
