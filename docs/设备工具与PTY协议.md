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

**码不变，说明补齐。** 上表的受控码是上层用来分流的冻结契约，取值一个都不改；失败时只在
回给上层的 `text` 里追加一段简体中文说明与「下一步」（`DeviceCommandRunner.explain`），
设备原始输出仍原样保留在说明之后，成功时正文逐字节不变（截图 base64 不会被污染）。
映射与措辞由单测锁定（`DeviceCommandRunnerTest.controlledFailureCodesKeepTheirValuesAndCarryActionableGuidance`
断言「同一种失败仍返回原受控码 + 说明含可操作指引」）。说明内容与判据边界见 §6。

## 5. uiDump 为什么不伪造降级结果

真机实测：本 ROM 上 `uiautomator dump` 是**静默空壳**——退出码 0、零输出、零文件、
耗时约 0 ms（正常启动 ART 需要 1–3 秒），因此 `[ -s ]` 判定失败，上报 `UI_DUMP_EMPTY`。

之所以不做降级：`dumpsys window` 之类**不提供无障碍节点边界**，用它冒充 UI 层级只会让模型
按错误的坐标点击。如实上报空壳，比编造一份看着像 UI 层级的结果更安全。若将来要引入降级，
前提是产物确实包含可用坐标，并且绝不能把「空壳返回 0」当成成功。

## 6. 真机实测：空壳 ROM、失败文案与审批的 fail-closed

本节把真机（HONOR / Android 16，PRoot 访客）实测到的三条现状分开写清楚：哪一条是设备事实、
哪一条是我们的取舍、哪一条是刻意的安全语义，以及用户各自能做什么。

### 6.1 `ui_dump` 报 `UI_DUMP_EMPTY`：本机没有产出可用层级

实测现象：本机的 `uiautomator dump` 是**静默空壳**——退出码 0、零输出、零文件，
耗时约 0 ms（正常启动 ART 需要 1–3 秒）。

脚本侧的判定链（`DeviceCommandRunner.uiDumpScript`）：

1. `command -v uiautomator` 成功（工具在，所以不是 `UI_DUMP_NO_TOOL`）；
2. `uiautomator dump --compressed /data/local/tmp/dsh-ui-<请求标识>.xml` 返回 0，
   但 `[ -s ]` 判定产物不存在或为空；
3. 回退到旧版参数形式 `uiautomator dump /sdcard/dsh-ui-<请求标识>.xml`：同样返回 0、
   同样没有非空产物；
4. 两次都返回 0 ⇒ 脚本退出码 5 ⇒ `UI_DUMP_EMPTY`。

**判定边界（不要过度断言）**：代码能确证的只是「两次调用都返回 0，且我们检查的那两条路径上
都没有非空产物」。「把 uiautomator 做成空壳」是这类现象最常见的来源（耗时约 0 ms 支持它），
但同一个分支也可能由「产物被写到别处」或「写入被拒而命令仍返回 0」触发。因此对外文案写成
「本机 uiautomator 没有产出可读的层级文件（常见于空壳 ROM）」，而不是断言 ROM 一定如何。

### 6.2 为什么不伪造降级（沿用 §5 的口径）

`dumpsys window` 之类不提供无障碍节点边界，用它冒充层级会让模型按错误坐标点击。
本应用**不伪造降级**：没有可用层级就如实上报 `UI_DUMP_EMPTY`，并把「层级读取在本机不可用」
写进回给上层的说明里（见 6.3）。

### 6.3 失败文案：受控码不变，补「人话说明 + 下一步」

受控码本身一个都不改（见 §4 的冻结口径），改的只是回给上层的文字
（`DeviceCommandResult.text`，实现是 `DeviceCommandRunner.explain` / `guidanceFor`）：

| 受控码 | 说明要点（简） |
|---|---|
| `UI_DUMP_EMPTY` | 本机 uiautomator 两次返回 0，但两条写入路径上都没有可读的层级文件；常见于空壳 ROM；据此**层级读取在本机当前不可用**；下一步改用 `mobile_device_screenshot`，坐标须来自截图或其它可信观察 |
| `UI_DUMP_NO_TOOL` | 本机找不到可用的 uiautomator；本应用不会自行安装，也不会用其它命令伪造层级 |
| `UI_DUMP_FAILED` | dump 以非零退出码结束，设备侧报错原样保留在「设备原始输出」里 |
| `DEVICE_COMMAND_FAILED` | 通用非零退出码；结合原始输出判断原因；失败后不要盲目重复同一操作 |
| `DEVICE_COMMAND_TIMEOUT` | 超时时间内没有闭合而会话仍存活；重试一次，反复超时先回应用确认 Shizuku 与设备 Shell |
| `DEVICE_COMMAND_PROTOCOL_ERROR` | 有命令开始标记，没有配对的结束标记；同上 |
| `DEVICE_COMMAND_SESSION_LOST` | 会话已结束且没有开始标记，这次调用没有真正执行；回应用确认 Shizuku 后重试 |
| `PLUGIN_DESTROYED` | 运行时已结束，在途命令被统一收口，这次调用没有执行；重启运行时后再试 |

措辞边界：全部简体中文，不做绝对断言（不写「一定」「永远」），不承诺修好设备或 ROM 侧的
问题；设备原始输出始终原样保留在说明之后，说明不替换证据；成功时正文逐字节不变。

**今天这条文案能走到哪一步（如实标注）**：说明随设备桥响应的 `text` 回给上层，
但插件（`scripts/runtime-profile/plugins/dsh-mobile-shizuku/lib/index.js`）的失败路径
只把 `errorCode` 抛成 `Error`，失败时 `text` 不会进入模型上下文——这与 §7 已记录的
「插件端错误路径不携带设备文本」是同一条限制。要让模型也读到这段说明，需要改插件
（把受控码与说明一起抛出，或单独约定错误文本字段），**不在本次改动范围内**，
已在 §7 记为待办。

### 6.4 审批关闭时 `tap` / `input_text` 的 fail-closed 语义

插件的 `tools/pre-execute` 钩子把 `mobile_device_tap` 与 `mobile_device_input_text`
注册为**需要用户审批**（返回 `{ kind: 'ask', reason: … }`）。因此：

* 会话**禁用审批**（或用户选择拒绝）时，这两个工具在**任何命令下发之前**就被拒绝：
  设备桥不会被调用，设备上不会产生任何副作用，也不会出现设备侧受控码；
* 这是**刻意的 fail closed**：点击与文本输入属于写类操作，默认不放行。本条不会为了
  「让它能跑」而放宽成自动允许——放宽等于把「用户没同意」变成「静默执行」。

用户可采取的动作：在 **dsh 侧的权限预设 / 审批设置**里为当前会话开启审批（具体入口以当前
dsh 版本为准），并在提示出现时允许该工具，然后重试调用。

**文案缺口（本次未闭合）**：这句拒绝由 dsh 的审批层与插件的 `ask` 决策共同产生，
移动侧的 Kotlin 代码在这条路径上**不会被执行**，因此下面这句话目前无法从
`DeviceCommandRunner` 发出，本次只把它写进本文档：

> 该工具需要审批，而当前会话禁用了审批，因此拒绝执行。可在 dsh 的权限预设/审批设置里
> 为当前会话开启审批后重试。

补它的落点是插件的审批钩子（`tools/pre-execute` 的 `reason`）或运行时的权限预设文案，
两者都在 `scripts/**`，不在本次改动范围内（见 §7 待办）。

### 6.5 用户可采取的动作（按现象）

| 现象 | 能做什么 |
|---|---|
| `ui_dump` 报 `UI_DUMP_EMPTY` / `UI_DUMP_NO_TOOL` | 层级读取在本机当前不可用，属设备侧事实：改用 `mobile_device_screenshot` 观察。注意本次真机上截图**取回成功但落盘失败**（`docs/真机缺陷与改进清单.md` 的 P0-2），这条替代路径的可用性取决于该缺陷是否已修复 |
| `tap` / `input_text` 被拒绝，且没有任何设备侧报错 | 这是审批 fail-closed，不是设备失败：在 dsh 的权限预设/审批设置里为当前会话开启审批后重试（见 6.4） |
| `DEVICE_COMMAND_TIMEOUT` / `DEVICE_COMMAND_PROTOCOL_ERROR` | 重试一次；反复出现时回应用确认 Shizuku 已授权并已连接、设备 Shell 会话正常 |
| `DEVICE_COMMAND_SESSION_LOST` / `PLUGIN_DESTROYED` | 这次调用没有执行：确认 Shizuku 状态或重启运行时后重试 |

## 7. 已知限制与未解决项

* **插件端错误路径不携带设备文本。** `callBridge` 在失败时只抛出错误码，
  因此 uiDump 的 stderr（保留在应用侧 `result.text` 里）不会进入模型上下文。
  这是既有设计（错误路径不引入设备文本）；若要让模型看到 stderr，需要单独决策。
  **本次新增的中文失败说明同样受这条限制**：它随桥响应的 `text` 回给上层，但在插件失败路径上
  被丢弃（详见 §6.3）。
* **待办：审批文案的落点在 `scripts/**`（本次未做）。** 「该工具需要审批，而当前会话禁用了
  审批，因此拒绝执行」这句话要在**审批被拒之前**发出，而移动侧 Kotlin 在审批被拒时根本不会
  被执行：落点只能是插件的 `tools/pre-execute` 钩子（`reason` 文案）或运行时的权限预设文案
  （`scripts/runtime-profile/plugins/dsh-mobile-shizuku/lib/index.js`）。本次受改动范围限制
  只把建议措辞写进 §6.4，**没有**改动插件。
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

## 8. 相关代码

| 文件 | 职责 |
|---|---|
| `android/app/src/main/java/io/deepseekharness/mobile/shizuku/DeviceCommandProtocol.kt` | 双哨兵协议与纯解析函数 |
| `android/app/src/main/java/io/deepseekharness/mobile/shizuku/DeviceCommandRunner.kt` | 命令构造、注入、有界缓冲、受控码映射，以及失败时的简体中文说明（`explain` / `guidanceFor`） |
| `android/app/src/main/java/io/deepseekharness/mobile/DeviceBridgeServer.kt` | HTTP 桥、会话生命周期、退出通知 |
| `android/app/src/main/java/io/deepseekharness/mobile/shizuku/ShizukuRuntime.kt` | UserService 连接与会话回调 |
| `android/app/src/test/java/io/deepseekharness/mobile/shizuku/DeviceCommandProtocolTest.kt` | 解析规则单测（回显、折行、字面量、token 不匹配、多组哨兵、截断、退出码） |
| `android/app/src/test/java/io/deepseekharness/mobile/shizuku/DeviceCommandRunnerTest.kt` | 注入文本形态、uiDump 加固、受控码分级与**码不变**、失败说明、成功正文不被污染、污染回显端到端 |
