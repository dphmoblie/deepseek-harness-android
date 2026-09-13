# 设备工具与 PTY 协议

本文说明 `mobile_device_screenshot` / `mobile_device_ui_dump` / `mobile_device_tap` /
`mobile_device_input_text` 四个工具在设备侧的执行链路、双哨兵协议，以及为什么旧方案
（按回显间隙解析）在真机上必然失败。

## 1. 链路

```
dsh 会话里的 mobile_device_* 工具
  → 插件 scripts/runtime-profile/plugins/dsh-mobile-shizuku
  → POST http://127.0.0.1:<动态端口>/device-command（Bearer token，来自 guest 环境变量）
  → 宿主 DeviceBridgeServer（白名单：screenshot / uiDump / tap / inputText）
  → 一次性设备 Shell 会话（Shizuku UserService，/system/bin/sh，80x24 PTY）
  → DeviceCommandRunner 注入命令并解析输出
```

命令类型与参数校验都在 `DeviceCommandRunner` 里完成：调用方只能选四个白名单操作，
`tap` 坐标必须是 `0..65535` 的整数，`inputText` 只接受 1..1024 个可打印 ASCII 且不含
引号、分号、反斜杠、`$`、反引号。命令内容不写入审计日志。

## 2. 旧方案为什么必然失败（已实测）

设备 Shell 是**交互式** PTY，Android 的 `/system/bin/sh` 是 **mksh**，它使用**自带行编辑器**：

* 行编辑器**自己回显**输入，并且无视终端 ECHO 标志——`stty -echo` 对它无效；
* 命令行跨过第 80 列时还会折行重绘，插入 `\r`、`<` 续行提示和退格擦除。

旧协议把哨兵 `__DSH_END_<uuid>__` 写在**注入文本**里，解析时只取**第一个**出现位置。于是：

| 场景 | 结果 |
|---|---|
| 短命令（哨兵不跨第 80 列） | 回显里的假哨兵完整保留且排在真实输出之前；其后是回显的字面量 `:$?`，正则不匹配后直接 `return`，再也不会重试 → **必然 60 秒超时**（真机实测：`tap` 必超时，`inputText` ≤5 字符必超时） |
| 长命令（哨兵跨第 80 列） | 回显里的哨兵被折行重绘打断，反而侥幸命中真哨兵 → 时灵时不灵 |
| 截图 | 回显污染落在 payload 头部（实测 690,821 字符里有 195 字符污染，含 `- \| ; _ < \b : $ ?`），插件 base64 校验失败 → `DEVICE_SCREENSHOT_INVALID`，完好的 PNG 被丢弃 |

结论：**任何依赖「回显间隙」或「关闭回显」的方案都不可靠**，因为回显不是可关闭的，
而命令长短会决定它是否被打断。

## 3. 新协议：双哨兵，标记由 shell 内部展开

注入的完整内容是一行：

```sh
/system/bin/sh -c '<内层脚本>'
```

内层脚本（示意，`<命令>` 为白名单正文）：

```sh
dsh_nonce=$$-$RANDOM
echo "__DSH_B_<请求标识>_${dsh_nonce}__"
<命令>
echo "__DSH_E_<请求标识>_${dsh_nonce}__:$?"
```

要点：

1. **标记的真值只由 shell 展开产生。** 注入文本里写的是字面量 `${dsh_nonce}`，
   回显看到的也只能是这段字面量；真实 token 形如 `4321-5678`，匹配模式要求
   `\d+-\d+`，字面量永远不可能匹配。**回显无法伪造真实标记。**
   请求标识是每次调用新生成的 UUID，payload 也无法预先猜出它。
2. **payload 区间是干净的。** 内层脚本由 `sh -c` 执行，非交互式 shell 不会回显自己的
   命令行，所以两个真实标记之间只有命令自身的输出（外加 BEGIN 那行 `echo` 自带的换行，
   需要干净文本的调用方自行去空白——截图本来就要去掉 base64 的换行）。
   外层 `mksh` 对整行的回显全部落在真实 BEGIN **之前**，天然被区间排除。
3. **参数不会逃出模板。** 拼接后的整段内层脚本再经过一层单引号转义
   （`'` → `'\''`），内层脚本本身不含单引号，因此引用关系没有歧义；`inputText` 的文本
   在双引号内，且校验阶段已排除 `"`、`$`、反引号与反斜杠。

### 解析规则（`DeviceCommandProtocol.parse`，纯函数）

* 取**最后一个** `__DSH_B_<请求标识>_<数字>-<数字>__` 作为真实 BEGIN；
* 取其后 **token 完全相同**、位置**最后**的 `__DSH_E_<请求标识>_<同一 token>__:<退出码>`
  作为真实 END（要求标记后紧跟 `\r?\n`，避免把 `:1` 提前认成 `:100`）；
* payload 严格取两者**之间**的区间；退出码取 END 尾部的十进制值；
* 多个哨兵对同时出现（重放、嵌套）时取最后一对，因此不会截错；
* payload 里出现 `__DSH_E_` 之类的文本不会被误判——它必须同时满足请求标识、token 与
  换行等全部条件。

### 有界缓冲与超时（未削弱）

| 项 | 值 | 说明 |
|---|---|---|
| 正文窗口 | 8 MiB | 超出即 `truncated=true`；payload 覆盖到窗口末尾 |
| 尾部窗口 | 512 字符 | 只用于发现真实 END（它永远是流末尾倒数第二段）；窗口里必须出现「token 已展开」的 END 形态才会触发全量扫描，回显里的字面量不会 |
| 单次超时 | 60 秒 | 与桥的上限一致，未被改动 |

### 关于 `stty -echo` 与固定 `sleep`

**已整体删除。** 原因：`stty -echo` 对 mksh 行编辑器无效（回显是编辑器自己写的），
而固定 `Thread.sleep(250ms)` + 清空缓冲既拖慢每次调用，又存在竞态——sleep 结束前到达的
输出会被误清，sleep 结束后才到达的回显又会混进 payload。新协议既不依赖「回显被关掉」，
也不依赖任何时序：回显既不能伪造标记，也不会落进 payload。

### 会话结束的快速收口

「BEGIN 已出现、END 永不到来」在过去只能等到 60 秒超时。现在 Shizuku 回调把设备命令会话的
退出事件（`ShizukuRuntime.create(..., onSessionExit = ...)`）转给
`DeviceCommandRunner.onSessionExit`，在途命令立刻按协议错误收口。会话仍存活时的超时兜底不变。

## 4. 错误码

| 错误码 | 触发条件 |
|---|---|
| `DEVICE_COMMAND_PROTOCOL_ERROR` | 有真实 BEGIN，但没有 token 相同的 END（含出现「完整但 token 不同」的 END，或会话结束时仍未闭合） |
| `DEVICE_COMMAND_SESSION_LOST` | 设备 Shell 会话结束，且连 BEGIN 都没有出现 |
| `DEVICE_COMMAND_TIMEOUT` | 60 秒内未闭合且会话仍存活（兜底，非首选路径） |
| `DEVICE_COMMAND_FAILED` | 命令本身以非零退出码结束（通用） |
| `UI_DUMP_NO_TOOL` | `command -v uiautomator` 失败（脚本退出码 3） |
| `UI_DUMP_FAILED` | `uiautomator dump` 返回非零（脚本退出码 4），其 stderr 保留在 payload 里 |
| `UI_DUMP_EMPTY` | `uiautomator` 返回 0，但目标文件不存在或为空（脚本退出码 5） |

uiDump 的脚本顺序是：**先探测工具** → 使用 `--compressed` 写入 `/data/local/tmp` →
用 `[ -s ]` 校验产物非空 → 失败时再用兼容旧版的参数形式写入 `/sdcard` → 把 stderr
一起接回（`2>&1`）。两次都返回非零才报 `UI_DUMP_FAILED`，两次都返回 0 但没有有效文件
才报 `UI_DUMP_EMPTY`。旧实现里的 `uiautomator dump X && cat X` 会让 `cat` 的 ENOENT
掩盖真实失败并统一报 `DEVICE_COMMAND_FAILED`，现在不会再发生。

## 5. uiDump 为什么不伪造降级结果

真机实测：本 ROM 上 `uiautomator dump` 是**静默空壳**——退出码 0、零输出、零文件、
耗时约 0 ms（正常启动 ART 需要 1–3 秒），因此 `[ -s ]` 判定失败，上报 `UI_DUMP_EMPTY`。

之所以不做降级：`dumpsys window` 之类**不提供无障碍节点边界**，用它冒充 UI 层级只会让模型
按错误的坐标点击。如实上报空壳，比编造一份看着像 UI 层级的结果更安全。若将来要引入降级，
前提是产物确实包含可用坐标，并且绝不能把「空壳返回 0」当成成功。

## 6. 已知限制与未解决项

* **插件端错误路径不携带设备文本。** `callBridge` 在失败时只抛出错误码，
  因此 uiDump 的 stderr（保留在应用侧 `result.text` 里）不会进入模型上下文。
  这是既有设计（错误路径不引入设备文本）；若要让模型看到 stderr，需要单独决策。
* **截图 base64 清理。** 协议修好后 payload 不再含回显，插件端 `replace(/\s/gu, '')`
  已足够（它负责去掉 base64 的换行）；回显不会再带来 `\b` 之类的控制字符。**没有**额外增加
  控制字符清理：字母表校验 `^[A-Za-z0-9+/]*={0,2}$` 会 fail-closed 拒绝任何残留污染，
  静默清理反而会掩盖协议回归。
* **设备桥审批绕过（安全，设计层面，另行处理）。** 桥的 Bearer token 通过 guest 环境变量
  `DSH_DEVICE_BRIDGE_TOKEN` 注入，同 uid 的 guest 代码可以读 `/proc/<pid>/environ` 拿到
  token，从而绕过 `mobile_device_tap` / `mobile_device_input_text` 的 `tools/pre-execute`
  审批门直接调用桥。本文档只如实标注，**不做半成品改造**。
* **真机行为无法在 JVM 复现。** 单测覆盖的是解析规则；PTY 回显、mksh 行编辑器、
  Shizuku UserService 与 `uiautomator` 是否真的产出，必须在真机上验收
  （见 `docs/mobile-acceptance-checklist.md` 第 5 节，四项均为未勾选状态）。

## 7. 相关代码

| 文件 | 职责 |
|---|---|
| `android/app/src/main/java/io/deepseekharness/mobile/shizuku/DeviceCommandProtocol.kt` | 双哨兵协议与纯解析函数 |
| `android/app/src/main/java/io/deepseekharness/mobile/shizuku/DeviceCommandRunner.kt` | 命令构造、注入、有界缓冲、错误码映射 |
| `android/app/src/main/java/io/deepseekharness/mobile/DeviceBridgeServer.kt` | HTTP 桥、会话生命周期、退出通知 |
| `android/app/src/main/java/io/deepseekharness/mobile/shizuku/ShizukuRuntime.kt` | UserService 连接与会话回调 |
| `android/app/src/test/java/io/deepseekharness/mobile/shizuku/DeviceCommandProtocolTest.kt` | 解析规则单测（回显、折行、字面量、token 不匹配、多组哨兵、截断、退出码） |
| `android/app/src/test/java/io/deepseekharness/mobile/shizuku/DeviceCommandRunnerTest.kt` | 注入文本形态、uiDump 加固、错误码分级、污染回显端到端 |
