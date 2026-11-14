# Spec: 同类故障（进程级闪退 + 权限静默失败）修复

## Goal
在已修复三个前台服务（HarnessKeepAliveService / OverlayBallService / VirtualScreenService）与
两处通知/媒体权限请求之后，继续消除仓库内**同类**残余故障：

- **A 类**：异常逃出关键线程（广播 `onReceive`、主线程 `Handler` 回调），导致整进程崩溃。
- **B 类**：权限/服务启动失败被静默丢弃，用户只看到一闪而过或泛化的提示，没有指引入口。

本次共修 5 项（用户已确认）。参照基线 `ForegroundServices.kt`：**全程不抛异常**，失败写
全小写 token 诊断；服务侧不弹 UI，用户文案由前端 `t()` 提供。

## Scope / 非目标
- 只改下列文件；**不得**触碰 3 个在途 WIP 文件（`HarnessActivity.kt`、`MainActivity.kt`、
  `runtime/MobileRuntimeController.kt`）与 `stash@{0}: wip-foreign-edits`，不得提交或丢弃。
  （round 1 期间的 WIP 限制已由 `dbb4a154` 合入解除，round 2 起上述三个文件可正常修改。）
- 不新增前台服务类型、不改变既有 token 语义。
- 验证口径：**只跑编译**（`gradlew :app:compileDebugKotlin`、`npx tsc --noEmit`），不跑单测。

## 变更明细

### FIX-1 广播 `onReceive` 整体兜底（A 类，高危）
- 文件：`android/app/src/main/java/io/deepseekharness/mobile/BootRecoveryReceiver.kt`（L18-44）
- 现状：仅 `RuntimeStore(...)` 构造被 try/catch；`Settings.canDrawOverlays`、`store.*` 读取
  裸露在广播主线程，异常会终结进程。
- 改法：抽出 `private fun restoreAfterBoot(appContext: Context)`（原 L22-43 主体），
  `onReceive` 改 `runCatching { restoreAfterBoot(context.applicationContext) }
  .onFailure { android.util.Log.w(TAG, "boot recovery failed: ${it.javaClass.simpleName}") }`；
  新增 `companion object { private const val TAG = "dsh-runtime" }`。行为不变，仅加兜底。

### FIX-2 主线程轮询 Runnable 兜底（A 类，高危）
- 文件：`android/app/src/main/java/io/deepseekharness/mobile/virtualscreen/VirtualScreenActivity.kt`（L250-281）
- 现状：`refresh` 的 `run()` 由 `main.postDelayed` 自循环，体内无 try/catch；`service.state()` /
  构建 `VirtualScreenFrame` 抛错即崩主线程。
- 改法：`run()` 体包 `try { ...原体... } catch (_: Throwable) { toast("副屏状态刷新失败，请退出重进本页"); return }`，
  并把 `main.postDelayed(this, 700)` 移到 try 之外（异常时 `return` 不再续期）。

### FIX-3 消费 `foregroundStartError`（B 类，中危）
- 文件：`src/App.tsx`（L6402-6414 复核回调）
- 现状：`foregroundStartError` 在 `types.ts:526` / `validation.ts:860` 定义校验，但 App.tsx 全库无引用。
- 改法：复核回调内改为按 token 显式分流：
  `latest.notificationPermission === 'prompt' || latest.foregroundStartError === 'notification_permission_missing'
   ? { kind:'notification-permission' } : { kind:'background-blocked', feature:'keepAlive' }`。
  （已知 token `foreground_declined` / `start_disallowed` / `foreground_failed` 仍落 background-blocked，
  与该 fallback 等价——本次让字段变为**活字段**并为将来新 token 留出分支。）

### FIX-4 `run()` 权限类失败弹出指引（B 类，中危）
- 文件：`src/App.tsx`
- 改法：
  1. `PermissionGuide` 联合类型（L5105-5109）新增 `| { kind: 'shizuku-permission' }`。
  2. `PermissionGuideDialog`（L5121-5201）新增 prop `onOpenShizuku: () => void`；`title` 分支加
     `'shizuku-permission' → t("Shizuku 尚未授权")`；正文加一条说明；`dialog-actions` 内加按钮
     `前往授权 Shizuku`（非 overlay-internal 分支）；`overlay-internal` 的 `我知道了` 分支不受影响。
  3. `run`（L6008-6021）签名加可选第 4 参 `guide?: PermissionGuide`；`catch` 内 `notify(...)` 之后
     `if (guide) setPermissionGuide(guide)`。
  4. `requestShizukuPermission`（L6455-6460）调用改为
     `run('shizuku-permission', ..., t("Shizuku 已授权"), { kind: 'shizuku-permission' })`。
  5. 渲染处（L6845-6854）传入 `onOpenShizuku={() => { setPermissionGuide(null); openShizuku() }}`。

### FIX-5 `catch (Exception)` → `catch (Throwable)`（A 类，低危）
- 文件：`android/app/src/main/java/io/deepseekharness/mobile/virtualscreen/VirtualScreenService.kt`
  L149 `catch (error: Exception)` → `catch (error: Throwable)`；L176 `catch (_: Exception)` → `catch (_: Throwable)`。
  与基线 `runCatching`（即 Throwable）一致。

## Verification
1. `npx tsc --noEmit`（前端 FIX-3/4）
2. `./gradlew :app:compileDebugKotlin`（Android FIX-1/2/5）
3. 拒绝 `git add` 上述 WIP 文件；不 commit、不 push（除非用户另行要求）。

---

# Round 2：第二轮深度审查

审查基线：`e5cbad5e` / `20444014` / `63304ba9` / `dbb4a154` / `f9a604cb` / `2f6196d5`。
已确认非缺陷（不重复报告）：`isRunning` 乐观置位、`foreground_failed` 与 `foreground_declined`
分工、`ApplicationPicker` 800ms 组合输入兜底、`VirtualScreenActivity.refresh` 异常后停轮询、
清单 `specialUse` 类型与 `FOREGROUND_SERVICE_SPECIAL_USE` 权限、API 34+ 无类型回退。

## R2-1（中）后台模块图探测脱离生命周期锁，与 stop/install 并发操作同一 rootfs
- 文件：`android/app/src/main/java/io/deepseekharness/mobile/runtime/MobileRuntimeController.kt`
  L179（调用点，位于 `lifecycleLock.withLock` 体内）、L190-199（`schedulePostStartModuleGraph`）。
- 现状：探测从「启动关键路径、`supervisor.startHarness` 之前」移到「启动成功之后」由
  `postStartDiagnosticsExecutor` 异步执行，且**不持有 lifecycleLock**。探测内部会
  `ProcessProbe.run(resolver.launch(argv), store.currentRoot, …, timeoutSeconds)` 拉起一个
  PRoot+Node 访客进程。改动前它只在「无运行时」时执行；改动后与**正在运行的 Harness** 并发，
  且 `stop()`（只拆 supervisor 会话）与 `install()`（只检查 `supervisor.isRunning()`）都不与它串行：
  `stop()` 之后 `install()` 立刻获准，而探测最长存活 30s，会在 rootfs 被替换期间继续持有同一 rootfs。
- 复杂度：探测失败本身被容忍（记 `result=denied`），但「持有 rootfs 的访客进程与运行时替换并发」
  是本次改动新引入的时序，不再是改动前的「串行取证」。
- 改法（择一，推荐 c）：
  - c（最小）：任务体首行 `if (closed.get() || !supervisor.isRunning()) return` —— 探测只对
    「运行中的冷启动」有判读价值，停止后继续探测本就无意义。
  - b：把提交点移到 `lifecycleLock` 之外，任务体内 `lifecycleLock.tryLock()` 失败即跳过。
  - a：由 controller 持有一个探测 future，`stop()`/`install()` 显式取消。

## R2-2（低）`execute()` 在已 shutdown 的执行器上抛 `RejectedExecutionException`
- 文件：同 R2-1，L190-191（提交）与 L534-540（`shutdown()`）。
- 现状：`shutdown()` 先 `postStartDiagnosticsExecutor.shutdownNow()` 再取 `lifecycleLock`；在途
  `startHarness` 若在其后仍走到提交点，`execute()` 抛出的 `RejectedExecutionException` 会逃出
  `startHarness`（调用方 catch 后表现为一次**虚假启动失败**）。触发窗口极窄，但可零成本消除。
- 改法：`runCatching { postStartDiagnosticsExecutor.execute { … } }`，或前置
  `if (!postStartDiagnosticsExecutor.isShutdown)`。

## R2-3（中，前端）`foregroundStartError` 无人消费，后台保持失败分流仍靠启发式
- 文件：`src/platform/types.ts` L526、`src/platform/validation.ts` L860（定义/校验）、
  `src/App.tsx` L6464-6472（分流）。
- 现状：`2f6196d5` 移除了唯一读取点（不可达分支）后，字段全库只剩定义与校验；keepAlive 分流
  只看 `notificationPermission === 'prompt'`。原生侧现在**不再**因缺通知权限阻止前台服务启动，
  因此「prompt + 前台服务确实没起来」几乎必然是系统/ROM 拒绝，却把用户导向**通知设置页**。
- 改法（`foregroundStartError` 已被 validation 收敛为受控 token，可直接布尔化）：
```ts
const rejected = latest.foregroundStartError !== undefined && latest.foregroundStartError !== null
setPermissionGuide(
  rejected || latest.notificationPermission !== 'prompt'
    ? { kind: 'background-blocked', feature: 'keepAlive' }
    : { kind: 'notification-permission' },
)
```

## R2-4（低）HarnessActivity cookie 回调守卫命中时不重试，页面静默白屏
- 文件：`android/app/src/main/java/io/deepseekharness/mobile/HarnessActivity.kt` L143-167。
- 现状：cookie 写入被提前到 WebView 创建之前发起，注释断言「回调 post 到主线程，最早也要等
  onCreate 返回」。该断言不是 `CookieManager#setCookie` 的 API 契约；若某厂商 WebView 在非主线程
  回调，`!::webView.isInitialized` 分支直接 `return`，而 `pageLoadGate.onCookieStored` **已把
  `handled` 置位**，页面再也不会加载，且没有任何提示。
- 改法：守卫命中时改为**主线程重投递**而非丢弃（或恢复 gate 语义：仅在真正发起 `loadUrl` 时置 `handled`）。
```kotlin
CookieLoadDecision.LOAD -> {
    if (isFinishing || isDestroyed) return@setCookie
    if (::webView.isInitialized) { cookieManager.flush(); webView.loadUrl(entryUrl) }
    else Handler(Looper.getMainLooper()).post {
        if (!isFinishing && !isDestroyed && ::webView.isInitialized) {
            cookieManager.flush(); webView.loadUrl(entryUrl)
        }
    }
}
```

## 风险与取舍
- R2-1：改法 c 最小、风险最低（不改锁结构）；a/b 更严谨但改动面更大。
- R2-3：改法会让「prompt 且服务确实停着」从通知指引改为后台拦截指引。若确有 ROM 需要先开通知，
  应由原生侧显式下发 `notification_permission_missing` 之外的新 token 来表达，而不是前端猜测。

## Verification（Round 2）
1. `./gradlew :app:compileDebugKotlin`（R2-1/2/4）
2. `npx tsc --noEmit` + `vitest run src/App src/__tests__`（R2-3）
3. 不 commit、不 push（除非用户另行要求）。

## 待批准
以上 R2-1 ~ R2-4 为**待批准的修复计划**，逐条可单独选择是否落地。
