# 桥接协议 v2（dsh-mobile-apk）

> **归档说明（2026-08 合并时添加）**：本文档为历史设计留档，正文中的路径与包名
> （com.dshmobile.shell、app/build.gradle.kts 等）保持原样不改。协议本身未并入新版，
> 新版对应实现：
> - 反射特权执行 → `android/app/src/main/java/io/deepseekharness/mobile/shizuku/ReflectiveShellExecutor.kt`（§4 的反射方案已移植，依赖仍 pin 13.1.5）
> - 交互终端 → `shizuku/ShizukuRuntime.kt` + `DeviceShellUserService.kt`（UserService PTY，主路径）
> - 安全模型参照 → `runtime/audit/AuditPolicy.kt`
> - §3 的 token/来源白名单/拒绝列表三重门在新版由 Harness 令牌鉴权与审计策略承接
>
> 勘误：§4 中"user service 进程运行在 app uid"的论断有误——Shizuku UserService 实际运行在
> Shizuku 服务进程（ADB 启动时为 shell uid 2000），新版 UserService PTY 具备 shell 级权限。
> 反射方案保留作为一次性特权 exec 的互补路径。
>
> 状态：实现完成，待真机验证（见 §6 测试矩阵）。v1 方法全部保留、行为不变。
> 变更文件：ShizukuSupport.kt（stage 2 特权执行）、AndroidBridge.kt（version 2.0 + v2 方法）、
> MainActivity.kt（onShellResult 回调 + 页面信任门）、app/build.gradle.kts（shizuku 13.2.0）。

## 1. 目标

把"设备特权通道"（Operit 的无障碍/系统能力调用在本项目的等价物）以最小侵入方式并入壳 APK：
页面（手机 dsh web app）经 `window.androidBridge` 以 **Shizuku 用户（shell uid 2000）** 执行设备级操作——
能力等价 `adb shell`：am/pm/settings/input/screencap/uiautomator。

## 2. 方法表

| 方法 | 签名 | 说明 |
|---|---|---|
| version | getter String | **"2.0"**（v1 为 "1.0"；feature-detect 用） |
| shizukuStatus | () → String | 同步返回 `{available, version, authorized}` |
| execShell | (token, argvJson, callbackId) | argvJson = JSON 字符串数组，argv 语义直接 exec（无 shell 元字符解释，注入安全） |
| screencap | (token, callbackId) | `screencap -p` PNG 以 base64 回传（≤8MB） |
| uiautomatorDump | (token, callbackId) | `uiautomator dump` 到 /data/local/tmp 后 cat 回传 XML 文本 |
| inputTap | (token, x, y, callbackId) | `input tap x y`（坐标来自 ui dump 的 bounds 解析） |
| inputText | (token, text, callbackId) | `input text`，**仅 ASCII**；非 ASCII 由插件侧 IME/剪贴板兜底 |

异步结果统一经 `window.__dshBridge.onShellResult(callbackId, resultJson)` 回传（主线程注入，与
onDirectoryPicked 同构）。resultJson 信封：

```json
{ "ok": true, "exitCode": 0, "stdout": "...", "stderr": "",
  "base64": null, "timedOut": false, "truncated": false }
```

`base64` 仅 screencap 使用；`ok=false` 时 `error` 携带原因（shizuku_unavailable / bad_token /
untrusted_page / denied_command / timeout / bad_argv_json / bridge_error）。

## 3. 安全模型（fail-closed）

三重门，任一失败即回 `{ok:false}`，不执行：

1. **token**：调用方必须携带进程级 pick token（EngineManager.ensurePickToken 进程内稳定，
   引擎侧经 DSH_PICK_TOKEN 环境变量持有同一值）；页面 JS 应经引擎的**已认证端点**获取 token，
   不得明文下发。
2. **页面来源**：仅当 WebView 当前 URL 前缀为 `http://127.0.0.1:3080` 时放行（MainActivity
   注入的 isPageTrusted），防注入页/iframe 劫持桥。
3. **拒绝列表**：reboot、svc power、`rm -rf /`、`dd if=`、卸载 com.dshmobile.shell 一律拒绝；
   更细的确认 UI 由引擎侧 dsh-device-tools 插件负责（不在 APK 层）。

Shizuku 缺失/未授权：全部 exec 返回 `shizuku_unavailable`，UI 状态栏提示（ShizukuSupport.status）。
并发：v2 exec 单线程串行（防 screencap/input 设备竞争）。

## 4. 后端说明：反射调用隐藏的 newProcess（已按 13.1.5 字节码核实）

- Maven Central 上 shizuku-api 最新为 **13.1.5**（13.2.0 不存在）；`Shizuku.newProcess(String[], String[], String)`
  在 13.1.5 中存在但为 **private**（javap 核实，返回 `ShizukuRemoteProcess extends java.lang.Process`，
  内部即 `IShizukuService.newProcess` 的 binder 调用）。
- 实现经**反射**调用该隐藏方法；依赖 pin 13.1.5 冻结签名；返回对象按 `java.lang.Process` 消费
  （getInputStream/getErrorStream/waitFor/exitValue/destroy）。
- 为何不用 user service：user service 进程运行在 **app uid**，其内 ProcessBuilder 没有 shell 特权；
  user service 的价值在 binder 级系统 API（本 v2 用不到）。若上游未来发布公开 newProcess 的版本，
  替换为直接调用即可，桥方法签名不变。
- 运行要求：Shizuku 应用（服务端）需支持 newProcess（≥13 均支持）；服务端过旧时 binder 抛
  RemoteException → 返回 `exec_failed`（fail-closed）。

## 5. 引擎侧契约（dsh-device-tools 插件，独立交付）

1. 插件服务端暴露 `GET /dsh-device-tools/token`（仅已认证会话，读取 DSH_PICK_TOKEN）；
2. 页面 JS：`androidBridge.execShell(token, JSON.stringify(argv), cb)` → onShellResult → POST 回服务端；
3. 工具面映射：`adb_shell`→execShell、`adb_screenshot`→screencap（base64→PNG 落盘）、
   `adb_ui_dump`→uiautomatorDump、`adb_tap/input_text`→inputTap/inputText、`adb_devices`→shizukuStatus；
4. 危险命令确认 UI + 审计日志在插件侧实现（对齐桌面 dsh-android 插件 §6.3）。

## 6. 测试矩阵（待真机/模拟器验证）

| # | 步骤 | 预期 |
|---|---|---|
| V2.1 | 无 Shizuku 环境调 execShell | `{ok:false, error:"shizuku_unavailable"}`，不崩溃 |
| V2.2 | 错 token / 伪造页面来源 | `bad_token` / `untrusted_page`，不执行 |
| V2.3 | Shizuku 已授权，execShell ["id"] | `uid=2000(shell)`，exitCode 0 |
| V2.4 | screencap | base64 解码为合法 PNG，与屏幕一致 |
| V2.5 | uiautomatorDump | 返回含当前 Activity 包名的 XML |
| V2.6 | inputTap 打开一个 App，再 dump 校验 | 界面变化符合预期 |
| V2.7 | execShell ["reboot"] | `denied_command`，设备不重启 |
| V2.8 | 全部 v1 方法（pickDirectory 等）回归 | 行为与升级前一致 |

## 7. 已知限制

- inputText 仅 ASCII（`input` 二进制限制）；中文输入待 ADBKeyboard/剪贴板方案（插件侧）。
- 锁屏/灭屏会阻断 uiautomator dump 与注入（调用前需唤醒，或提示"充电时保持唤醒"）。
- exec 串行队列：长时间 screencap 会排队后续调用（MVP 可接受）。
