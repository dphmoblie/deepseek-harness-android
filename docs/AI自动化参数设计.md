# AI 自动化参数设计

> **状态**：设计稿（本文只描述参数模型、工具面、执行模型与边界，**不含实现代码**）。
> **范围**：让 AI 通过工具把「自动化参数」写进应用，应用按参数自动操作**前台应用**；参数模型、抑制机制与匹配口径均由本项目自定（见 §2、§3）。
> **约定（全文一致）**：
> - 【码】= 该结论已落在代码里，并给出 `文件:行号`。
> - 【推】= 本文的设计建议，**尚未实现**。
> - 【验】= 需要真机或测试才能确认，本文给不出结论。
> - 【未读】= 本文写作时**未能读取**的资料，不做任何推测。
> - 所有行号以本文写作时的 checkout 为准（同一 checkout 有并行改动，行号可能偏移）。

---

## 0. 一句话设计

**AI 只写参数，不执行动作；参数由原生侧严格校验后落盘；能不能动、动什么、动几次，全部由原生无障碍服务在动作发生的那一刻重新判定。**

这条线的三个支点是既有的：
1. 白名单（`AccessibilityAutomationStore`）：只有白名单内、且当前在前台的应用才可能被操作。
2. 验证密码（`AccessibilityPasswordStore`/`AccessibilityPasswordPolicy`）：改白名单是「人能改、AI 不能绕过」的操作。
3. 服务侧动作前复核（`DeepSeekAccessibilityService.performConfirmedAction`）：动作执行前重新取窗口、重新跑一遍锁定/频率/白名单/敏感窗口判定。

> **与 `docs/应用自动化能力.md` 的分工**：那份文档写的是**能力与口径**（有哪些设备工具、白名单与密码的语义、推荐自动化循环、验收边界，见 `docs/应用自动化能力.md:12-23`/`:30-33`/`:35-51`/`:53-60`/`:76-85`）。**本文不重复这些结论**，本文新增的是：① 每应用**规则**的字段级参数模型；② AI 工具的入参/返回/错误码；③ 无障碍服务「按规则自动评估并执行」的执行模型；④ 免责条款草稿；⑤ 分期计划。

---

## 1. 现状侦察

### 1.1 目录与文件（`android/app/src/main/java/io/deepseekharness/mobile/accessibility/`）

| 文件 | 职责 | 状态 |
| --- | --- | --- |
| `AccessibilityAutomationPolicy.kt` | 白名单/动作参数的纯校验规则 | 已有 |
| `AccessibilityAutomationStore.kt` | 白名单持久化 + 状态查询 | 已有 |
| `AccessibilityPasswordPolicy.kt` | 密码策略（纯逻辑，不 import `android.*`） | 已有 |
| `AccessibilityPasswordStore.kt` | 密码记录与失败计数的持久化 + Guard 门面 | 已有 |
| `DeepSeekAccessibilityService.kt` | 无障碍服务：读节点树、单步动作、确认浮层 | 已有 |
| `InstalledApplications.kt` | 已安装应用列表（设置页选择器用） | 已有（本文未逐行阅读） |
| **`AutomationRule.kt`** | **每应用规则的参数模型 + 严格 JSON 编解码** | **已有（本次侦察新发现，10/5 落盘）** |
| **`AutomationRuleStore.kt`** | **按包名的规则持久化（原子写、坏数据自愈）** | **已有（同上）** |
| **`AutomationRuleMatcher.kt`** | **纯匹配器（零 Android 依赖、注入时钟、可注入抑制状态）** | **已有（10/5 落盘；只判定不执行，执行器接线与 AI 工具面仍是后续任务）** |

> **重要**：§3 的参数模型**不是本文自创**，而是对齐 `AutomationRule.kt` 已经落地的权威模型；本文只补充「缺口」与「服务侧怎么用」。

### 1.2 白名单与密码（已实现，本文不重复其语义）

- 白名单校验入口：`AccessibilityAutomationPolicy.validPackage()`（长度 ≤160、包名正则、排除 `reservedPackages`）【码】`AccessibilityAutomationPolicy.kt:44-46`；系统保留包列表 `:23-27`。
- 白名单写入必须先过密码：`AccessibilityWhitelistWritePolicy.authorize()` = **先 `verifyOrThrow(password)`，再校验格式**【码】`AccessibilityAutomationStore.kt:21-30`（未过验证的人拿不到任何格式反馈）。
- 读取时**任一非法条目 → 整份白名单失效为空**【码】`AccessibilityAutomationStore.kt:45-57`（这条已在 `docs/前台自动化时延与可行性评估.md:185`/`:264` 分析过，本文不展开）。
- 密码策略：PBKDF2WithHmacSHA256、120 000 次迭代、错 5 次锁 30 秒【码】`AccessibilityPasswordPolicy.kt:27`/`:29`/`:32`；明文不落盘、`spec.clearPassword()`【码】`:52-59`；常量时间比较【码】`:62-63`；时钟回拨按「刚失败」处理【码】`:73-78`。
- 状态过桥只有 4 个字段（`enabled`/`allowedPackages`/`alwaysAllowedPackages`/`passwordConfigured`），**不含任何密码内容**【码】`MobileRuntimePlugin.kt:2464-2476`，前端对其做形状校验【码】`src/platform/validation.ts:813-823`。

### 1.3 规则模型与存储（已实现，本次新发现）

- `AutomationRule`：`id` / `packageName` / `enabled` / `allowActivities` / `denyActivities` / `matchDelayMs` / `maxActions` / `actionCoolDownMs` / `resetOn` / `selectors` / `action`【码】`AutomationRule.kt:353-366`。
- 严格解析：**任何未知字段、类型不符、越界取值一律整体拒绝**，不截断、不取默认值【码】`AutomationRule.kt:577-581`（未知字段）、`:382-423`（逐字段校验）。
- 受控错误码族：`AUTOMATION_RULE_MALFORMED` / `_FIELD_INVALID` / `_PATTERN_INVALID` / `_PACKAGE_INVALID` / `_UNKNOWN_FIELD` / `_DIGEST_INVALID`【码】`AutomationRule.kt:54-61`。
- 限额：每应用 ≤40 条、单包 JSON ≤64 KiB、全局 ≤256 KiB、选择器 ≤16、字符串 ≤200 字符等【码】`AutomationRule.kt:70-89`；超限是**拒绝**不是截断。
- 存储：独立偏好文件 `accessibility_automation_rules`（与白名单、密码各自隔离）【码】`AutomationRuleStore.kt:31`；**一次落盘原子替换整份索引**（`KEY_INDEX`，分隔符 `\t`/`\n`）【码】`:34`/`:43`/`:46`/`:49`/`:138`；`commit()` 同步落盘【码】`:335-339`。
- 坏数据自愈：某包解析失败 → **只删该包**并返回 `PackageRead.Corrupt`（不抛异常，因为读路径挂在窗口变化上）【码】`AutomationRuleStore.kt:58-78`、`:293-298`。
- **注意**：`AutomationRuleCodes.DIGEST_INVALID`【码】`AutomationRule.kt:60` 在整个仓库里**没有第二处命中**（全仓 grep `AutomationRule` 只命中 `AutomationRule.kt` 与 `AutomationRuleStore.kt`）——即这个码已定义但**尚无产生点**；本文 §4.2 把它设计成整包替换的乐观并发校验失败码。

### 1.4 无障碍服务现在能做什么、不能做什么

能读：
- 节点树 `tree(root, packageName)`：每节点 `className`(截 120) / `viewId` / `text` / `hint` / `contentDescription` / `clickable` / `editable` / `scrollable` / `bounds[l,t,r,b]`【码】`DeepSeekAccessibilityService.kt:140-170`；**`isEditable` 节点的 text/hint/desc 一律置空串**【码】`:148-170`；节点数上限触发 `truncated`【码】`:140-146`。
- 订阅的事件类型：`TYPE_WINDOW_STATE_CHANGED | TYPE_WINDOW_CONTENT_CHANGED | TYPE_VIEW_SCROLLED`，`notificationTimeout = 100L`，`FLAG_REPORT_VIEW_IDS | FLAG_RETRIEVE_INTERACTIVE_WINDOWS`【码】`:51-63`。

安全判定（单次动作路径，顺序固定）：
1. 锁定/熄屏 → `ACCESSIBILITY_DEVICE_LOCKED`【码】`:88-108`、`:387-391`
2. 频率上限（`ACTION_INTERVAL_MS = 350L`，仅动作类命令）→ `ACCESSIBILITY_RATE_LIMITED`【码】`:91`、`:781-798`
3. 取 `rootInActiveWindow` 失败 → `ACCESSIBILITY_WINDOW_UNAVAILABLE`【码】`:88-108`
4. 前台包 `validPackage() && in allowedPackages()` → `ACCESSIBILITY_PACKAGE_DENIED`【码】`:96`、`:321`
5. 敏感窗口 DFS（深度 >24 或扫描 >320 节点时 **fail-closed 按敏感处理**）→ `ACCESSIBILITY_SENSITIVE_WINDOW`【码】`:359-385`
6. 节点唯一（`findAccessibilityNodeInfosByViewId(...).singleOrNull()`）→ `ACCESSIBILITY_NODE_NOT_FOUND`【码】`:348-351`
7. 节点 `isPassword` 或 viewId 含敏感词 → `ACCESSIBILITY_ACTION_REJECTED`【码】`:172-184`

**关键缺口：`override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit`【码】`DeepSeekAccessibilityService.kt:65`——服务目前完全不响应事件。**事件类型虽已订阅【码】`:51-63`，但没有任何「按参数自动评估并执行」的路径。当前所有动作都是**外部请求驱动**的（`execute(command, param)`【码】`:75-86`），且动作前还有一次原生确认浮层【码】`:187-208`、`:210-271`（超时 30 秒【码】`:781-798`）。

### 1.5 桥接与 AI 工具面现状

- 桥接命令分发在 `DeviceBridgeServer.kt`：`automationPolicy`（能力查询，**硬编码**返回 `schemaVersion:3` 与 `allowlistedAutomation/directDeviceOperations` 等布尔）【码】`:201-210`；无障碍命令 `accessibilityTree`/`accessibilityAction`/`tap`/`inputText` 走 `DeepSeekAccessibilityService.current()?.execute(...)`，服务未开则回 `ACCESSIBILITY_SERVICE_DISABLED`【码】`:211-224`；命令名 ≤32 字符、参数 ≤`DeviceFilePolicy.MAX_PARAM_CHARS`【码】`:197-199`。
- 工具定义在容器内 Node 插件 `scripts/runtime-profile/plugins/dsh-mobile-shizuku/lib/index.js`：`mobile_accessibility_tree`【码】`:643-649`、`mobile_accessibility_action`【码】`:651-664`；参数校验 `accessibilityActionParam()`【码】`:262-288`（包名/viewId 前缀一致性/动作白名单/文本 ≤512 且无控制字符）；**权限门**：`mobile_device_tap`/`mobile_device_input_text`/`mobile_accessibility_action` 先查 `automationPolicy` 的能力位 `allowlistedAutomation === true` 才放行【码】`:546-552`。
- **现状没有任何自动化规则相关的桥接命令、工具或设置界面**（全仓 grep `AutomationRule` 只命中两个 Kotlin 文件）。

### 1.6 审计

- 事件枚举含 `ACCESSIBILITY_CONFIG`【码】`runtime/audit/AuditPolicy.kt:50`、`ACCESSIBILITY_READ`【码】`:53`、`ACCESSIBILITY_ACTION`【码】`:56`；结果集 `STARTED/SUCCEEDED/FAILED/DENIED/CANCELLED`【码】`:96-102`。
- **详情字段只允许受控错误码**：`detailPattern = Regex("^[A-Z][A-Z0-9_]{0,63}$")`【码】`:111`，不匹配就丢弃【码】`:113-117`；行格式 `ISO_INSTANT|EVENT|RESULT[|DETAIL]\n`；保留 90 天【码】`:105`。
- 既有注释明确：`ACCESSIBILITY_READ` **不记录窗口内容、包名或资源 ID**；`ACCESSIBILITY_ACTION` **不记录输入内容、包名或资源 ID**【码】`:53`/`:56`。**新增规则执行必须沿用这条口径。**

### 1.7 既有分析（本文引用而不重复）

- 单次无障碍动作的固定开销：2 次窗口获取 + 2 次敏感窗口 DFS（各 ≤320 节点）+ 2 次白名单读取 + 1–2 次按 viewId 查找 + 1 次主线程切换 + 1 次 fsync【码+推】`docs/前台自动化时延与可行性评估.md:34`。
- 「前台包在白名单」是每次动作都重读 SharedPreferences 并整份校验【码】`docs/前台自动化时延与可行性评估.md:116`/`:185`。
- 「改白名单要密码」**不在单次动作路径上**【码】`docs/前台自动化时延与可行性评估.md:121`——本文的 §4 沿用这一分层：**授权（人能改的）与执行（服务每次都查的）必须是两条路径**。

### 1.8 未读清单（本文不做推测）

- 【未读】`github.com` 系站点（仓库 README / 源码一类资料）：本机 DNS 把 `github.com` 解析到 `127.0.0.1`（`Resolve-DnsName github.com` 返回 `127.0.0.1`，而 `C:\Windows\System32\drivers\etc\hosts` 里没有 github 条目），`web_fetch` 直接拒绝：`Error: URL hostname "github.com" resolves to a non-public IP address`；`raw.githubusercontent.com` 与 `r.jina.ai` 代理均返回 `TypeError: fetch failed`。**因此本文的设计结论不引用任何外部仓库的源码**：参数模型、抑制机制与匹配口径全部由本项目自行确定（§2、§3）。
- 【未读】`AccessibilityPasswordStore.kt` 全文（只按符号清单与既有文档引用，未逐行阅读）、`InstalledApplications.kt` 全文。
- 【验】`automationPolicy` 的 `schemaVersion` 在前端的解析位置（本文未逐行核对 `src/platform/` 里的读取处）；若要新增能力位，需同步该处形状校验。

---

## 2. 设计取向与关键取舍（本项目自定）

本节只写我们自己的判断：为什么这样设计、放弃了什么。字段级细节见 §3，执行口径见 §5。

### 2.1 为什么用结构化字段，而不是选择器表达式 DSL

- 我们的 `Selector` 是一组**结构化字段**（`AutomationRule.kt:92-108`），不做表达式解析、不做关系选择器、不做运算符优先级。
- 理由：自造一门 DSL 会同时带来**解析器、转义、优化**三份复杂度；而我们的参数是 **AI 生成**的——结构化 JSON 更容易被严格校验、更容易给出「哪个字段错了」的反馈，也更容易在存储边界整体拒绝。
- 代价（如实记录）：表达能力弱于表达式语言——父子/兄弟关系、嵌套的逻辑组合目前无法表达。第一版接受这个代价：宁可「表达不了」，也不要「AI 写出一个我们没有预料到的匹配范围」。

### 2.2 参数只在本机写入，不引入远程规则输入面

- 我们的规则**只由本机 AI 或用户在本机写入**，不引入「订阅源 / 远程规则 URL」这类输入面。
- 理由：**远程规则 + 自动点击是最危险的组合**——规则内容可能在用户不知情时变化，而动作会真实落在用户设备上。只要远程输入面存在，「用户确认过的就是当前生效的规则」这个前提就不成立。

### 2.3 节点指纹只用真机可得的属性

- 节点指纹（§5.4 第 4 层）只用 `viewId` / `className` / 文本长度 / `bounds` 这类真机 `AccessibilityNodeInfo` 拿得到的属性。
- 理由：只有调试/快照工具才有的编号类属性在真机上不可用，不能作为运行态依据。
- 另外：指纹必须**截断成哈希**（不是明文），避免把界面内容写进内存与日志。

### 2.4 抑制参数的取舍

- 保留四类抑制手段：**冷却**（`actionCoolDownMs`）、**次数上限**（`maxActions`）、**重置时机**（`resetOn`）、**节点指纹去重**（§5.4）。
- **第一版不做顺序依赖**：没有「A 刚点完才允许点 B」这类约束。误触风险最高的场景正是**两个规则在同一界面同时为真**（§3.5 缺口 3）。
- **缺一条休眠语义**：有「每次执行之间的间隔」与「次数上限」，但没有「这条规则在这个界面最多参与多久」（§3.5 缺口 4）。
- 重置时机只保留两个取值（`activity` / `screen`，语义见 §5.4）。取值刻意保持少而明确：AI 只有两种选择，不会写出我们无法解释的重置口径；两个取值的区别就是「重置越频繁，重复动作越多；重置越少，越容易点一次就再也不点」这条取舍。
- **已知盲区**：flutter/webview 这类界面可能不触发 `onAccessibilityEvent`，纯事件驱动会漏掉它们，需要主动轮询兜底【推，第一版不做，如实记录】。

---

## 3. 参数模型（我们的）

**权威口径 = `AutomationRule.kt` 的既有实现。**下面三张表是对它的字段级整理（含「非法时怎么拒」的原文口径），第 §3.4 节列缺口。

### 3.1 `AutomationRule`（一条规则）

| 字段名 | 类型 | 取值范围 | 缺省 | 非法时怎么拒 |
| --- | --- | --- | --- | --- |
| `id` | string | `^[A-Za-z0-9._-]{1,64}$` | 必填 | `AUTOMATION_RULE_FIELD_INVALID`「规则标识必须是 1~64 个字母、数字、点、下划线或连字符」【码】`AutomationRule.kt:26`/`:383-385` |
| `packageName` | string | 走 `AccessibilityAutomationPolicy.validPackage`（≤160、包名正则、**非系统保留包**） | 必填 | `AUTOMATION_RULE_PACKAGE_INVALID`「规则包名不在可自动化范围」【码】`AutomationRule.kt:386-388`、`AccessibilityAutomationPolicy.kt:44-46` |
| `enabled` | boolean | true/false | `true` | 非布尔 → `FIELD_INVALID`【码】`AutomationRule.kt:453`/`:554-559` |
| `allowActivities` | string[] | 每项 ≤200 字符；**逐项按正则编译** | `[]` | 非数组/项非字符串/超长 → `FIELD_INVALID`；正则语法错 → `AUTOMATION_RULE_PATTERN_INVALID`【码】`AutomationRule.kt:465-479`/`:419`/`:507-511` |
| `denyActivities` | string[] | 同上 | `[]` | 同上【码】`:420` |
| `matchDelayMs` | int | 0..5000 | `0` | `FIELD_INVALID`「匹配延迟必须在 0~5000 毫秒之间」【码】`:359`/`:389-394` |
| `maxActions` | int | 1..99 | `1` | `FIELD_INVALID`「执行次数上限必须在 1~99 之间」【码】`:360`/`:395-400` |
| `actionCoolDownMs` | int | 0..600000 | `3000` | `FIELD_INVALID`「冷却时间必须在 0~600000 毫秒之间」【码】`:361`/`:401-406` |
| `resetOn` | string | `"activity"` \| `"screen"` | `"activity"` | `FIELD_INVALID`「重置方式必须是 activity 或 screen」【码】`:362-363`/`:407-409` |
| `selectors` | Selector[] | 1..16 个，每个至少一个非空字段 | 必填 | 空数组/超 16 → `FIELD_INVALID`【码】`:410-415`；单个空选择器 → `FIELD_INVALID`「选择器至少要有一个条件」【码】`:416`/`:156-158` |
| `action` | 对象 | 见 §3.3 | 必填 | 缺字段 → `FIELD_INVALID`「缺少字段 action（动作）」【码】`:461`/`:520-524` |

**未知字段一律拒绝**（拼错字段名必须当场报错）：`AUTOMATION_RULE_UNKNOWN_FIELD`「规则含未知字段：…」【码】`AutomationRule.kt:449`/`:577-581`。

> **旧键不再接受（2026-10-05 改名）**：这四个参数的最终键名就是上表的 `allowActivities` / `denyActivities` / `maxActions` / `resetOn`。改名前的旧写法会命中上面这条「未知字段」而被**整体拒绝**（存储层对未知字段是严格拒绝，坏数据只自愈单包）。该功能尚未发布、设备上不存在需要迁移的真实规则，因此**不做键名迁移**：旧键规则需要重写。

### 3.2 `Selector`（选择器；字段间是 **AND**）

| 字段名 | 类型 | 取值范围 | 缺省 | 非法时怎么拒 |
| --- | --- | --- | --- | --- |
| `text` | string | ≤200 字符；**精确相等**（不是包含） | 无（缺省即不参与） | 非字符串 → `FIELD_INVALID`；显式空串按「未提供」处理【码】`AutomationRule.kt:93-94`/`:142`/`:543-552` |
| `textContains` | string | 同上（包含） | 无 | 同上【码】`:95`/`:143` |
| `textStartsWith` | string | 同上（前缀） | 无 | 同上【码】`:96`/`:144` |
| `textEndsWith` | string | 同上（后缀） | 无 | 同上【码】`:97`/`:145` |
| `id` | string | **两种写法二选一**：全写 `包名:id/名称`（与 `AccessibilityAutomationPolicy.viewIdPattern` 同口径）或短写 `:id/名称` | 无 | `FIELD_INVALID`「选择器 id 必须写成 包名:id/名称 或 :id/名称」；**不做宽松包含**（避免 `id:"login"` 悄悄匹配任意含 login 的资源名）【码】`:98-99`/`:162-171`/`:34-38` |
| `desc` | string | ≤200（contentDescription，精确） | 无 | 同上族【码】`:100`/`:147` |
| `descContains` | string | ≤200（包含） | 无 | 【码】`:101`/`:148` |
| `className` | string | ≤200 | 无 | 【码】`:102`/`:149` |
| `clickable` | boolean | true/false | 无 | 非布尔 → `FIELD_INVALID`【码】`:103`/`:150` |
| `enabled` | boolean | true/false | 无 | 【码】`:104`/`:151` |
| `editable` | boolean | true/false | 无 | 【码】`:105`/`:152` |
| `minWidth` | int | ≥0 | 无 | 负数 → `FIELD_INVALID`「选择器 minWidth 不能为负数」【码】`:106`/`:173-177` |
| `minHeight` | int | ≥0 | 无 | 【码】`:107`/`:173-177` |

**全空选择器必须拒**（否则它永远为真，等于「见谁点谁」）【码】`AutomationRule.kt:109-113`/`:156-158`。

> **注意**：`text`/`desc` 是**精确相等**、`textContains`/`descContains` 才是包含——这一条必须在 §4 的工具描述里逐字写给 AI 看，否则 AI 会用 `text` 填「跳过」去匹配「跳过广告」。

### 3.3 `AutomationAction`（动作；字段与 `type` 严格配套）

`type` 枚举：`click` / `clickCenter` / `longClick` / `back` / `swipe` / `key` / `wait` / `launch`【码】`AutomationRule.kt:208`。

| `type` | 允许出现的字段 | 约束 | 非法时怎么拒 |
| --- | --- | --- | --- |
| `click` | 仅 `type` | — | 带其它字段 → `UNKNOWN_FIELD`「动作 click 含未知字段」【码】`:226-233` |
| `clickCenter` | 仅 `type` | — | 同上 |
| `longClick` | 仅 `type` | — | 同上 |
| `back` | 仅 `type` | — | 同上 |
| `swipe` | `type`,`durationMs`,`direction` | `durationMs` 必填 100..2000；`direction` ∈ `up/down/left/right` 必填 | `FIELD_INVALID`（缺 durationMs / 超范围 / 方向不合法）【码】`:253-269` |
| `key` | `type`,`keyCode` | `keyCode` 必填，**必须落在允许表** | `FIELD_INVALID`「按键不在允许列表（可用：BACK/DPAD_UP/DPAD_DOWN/DPAD_LEFT/DPAD_RIGHT/DPAD_CENTER/TAB/SPACE/ENTER/DEL/ESC）」【码】`:272-281` |
| `wait` | `type`,`delayMs` | `delayMs` 必填 1..10000 | `FIELD_INVALID`「等待时间必须在 1~10000 毫秒之间」【码】`:283-297` |
| `launch` | `type`,`component`,`uri` | 至少一个；`component` 必须 `包名/类名`（可相对）且**包名必须过 `validPackage`**；`uri` 只允许 `http://`/`https://`/`market://` | `FIELD_INVALID`（都缺/组件格式/协议）或 `AUTOMATION_RULE_PACKAGE_INVALID`（组件里的包名不可自动化）【码】`:299-317`/`:41`/`:212` |

允许的按键表（与副屏 `keyevent` 同一张表；**刻意不放开整段 `KeyEvent`**，因为 `KEYCODE_*` 里有电源/恢复出厂/拨号等破坏性按键）【码】`AutomationRule.kt:329-350`：

| 名称 | 值 | 名称 | 值 |
| --- | --- | --- | --- |
| BACK | 4 | TAB | 61 |
| DPAD_UP | 19 | SPACE | 62 |
| DPAD_DOWN | 20 | ENTER | 66 |
| DPAD_LEFT | 21 | DEL | 67 |
| DPAD_RIGHT | 22 | ESC | 111 |
| DPAD_CENTER | 23 | | |

### 3.4 容器、限额与存储口径

| 项 | 值 | 出处 |
| --- | --- | --- |
| 每应用规则数 | ≤40 | 【码】`AutomationRule.kt:71` |
| 单包 JSON | ≤64 KiB（**UTF-8 字节**，中文规则名按 3 字节算） | 【码】`:72`/`:504` |
| 全局 JSON | ≤256 KiB | 【码】`:73` |
| 槽位 | 128 个（`p0`..`p127`） | 【码】`AutomationRuleStore.kt:37`/`:40` |
| 序列化版本 | `SCHEMA = 1` | 【码】`AutomationRule.kt:88` |
| 偏好文件名 | `accessibility_automation_rules`（与白名单 `accessibility_automation`、密码各自隔离） | 【码】`AutomationRuleStore.kt:31` |
| 写语义 | **整包替换**；空列表 = 删除该包（不留空条目） | 【码】`:87-93` |
| 原子性 | 唯一一次 `write(KEY_INDEX, …)`，无「先删后写」中间态 | 【码】`:16-18`/`:137-139` |
| 重复 `id` | 写入边界拒绝（会让计数串台） | 【码】`:110-116` |
| 全局上限判定 | 用「写入后的总量」（先排除本包旧条目） | 【码】`:127-136` |
| 坏包 | 只删该包并返回 `Corrupt`，不影响其它包 | 【码】`:58-78` |

### 3.5 缺口（本文明确指出，供拍板）

1. **没有全局总开关**：现有 `enabled` 字段是**规则级**的；全局「自动化开/关」目前只能从「无障碍服务是否开启」间接推断（`AccessibilityAutomationStore.kt:72-85` 的 `enabled` = `DeepSeekAccessibilityService.current() != null`）。§4.3 建议新增独立的 `automationEnabled`（默认关）。
2. **没有匹配器**：`AutomationRuleMatcher` 不存在（`AutomationRule.kt:11` 提到了它）。`selectors` 列表的语义（OR 还是 AND）、命中后取哪个节点、字段与节点属性的逐项映射，都还没有实现口径——**这是 P0 必须收口的第一件事**（§5.2/§8）。
3. **没有顺序依赖**：没有「A 刚点完才允许点 B」这类顺序约束，等价物不存在。误触风险最高的正是「两个规则在同一界面同时为真」。
4. **没有休眠语义**：无法表达「一条规则参与匹配一段时间后自动休眠」。我们有 `actionCoolDownMs`（每次执行之间的间隔）与 `maxActions`（次数上限），但缺「这条规则在这个界面最多参与多久」——`maxActions` 管的是「点几次」，管不了「参与多久」。
5. **没有 `versionCode`/`versionName` 约束**：应用一升级，viewId 可能整体变化；我们只能在匹配不上时表现为「不生效」。
6. **敏感页面跳过不是参数**：这是**刻意**的——见 §6 第 2 条。规则里**不能**出现「允许在支付页动作」这类开关。
7. **`DIGEST_INVALID` 无产生点**：见 §1.3，设计上留给 §4.2 的乐观并发校验。

---

## 4. AI 工具面

### 4.0 分层与命名

沿用既有分层（`docs/前台自动化时延与可行性评估.md:57`：工具由容器内 Node 插件定义、前端只管设置界面）：

```
AI（模型）
  └─ 工具：mobile_automation_status / rules_set / enable / trial   ← 新增（Node 插件）
       └─ 桥接命令：automationRules（op 区分子操作）                ← 新增（DeviceBridgeServer）
            └─ 原生：AutomationRuleStore + DeepSeekAccessibilityService
                 └─ 白名单/密码/敏感窗口/锁定/频率 判定（既有）
```

**命名理由**：`mobile_automation_*` 与既有 `mobile_accessibility_*` 区分——后者是「单步动作」（人在环里，每次都可能弹确认浮层），前者是「写参数 + 让服务自己按参数跑」。这个区分必须体现在工具描述里，否则模型会把两者混用。

### 4.1 `mobile_automation_status`（读）

**用途**：一次拿到「能不能自动化、能不能动这个包、当前前台是什么、已有规则什么形状」。AI 生成选择器前的**唯一**合法入口。

入参（全部可选）：

```json
{ "packageName": "com.example.app", "includeRules": true }
```

| 字段 | 类型 | 约束 | 缺省 |
| --- | --- | --- | --- |
| `packageName` | string | ≤160、匹配包名正则、不在 `reservedPackages` | 省略 = 不查某包规则 |
| `includeRules` | boolean | — | `true` |

返回：

```json
{
  "automationEnabled": false,
  "serviceEnabled": true,
  "passwordConfigured": true,
  "allowedPackages": ["com.example.app", "io.deepseekharness.mobile"],
  "alwaysAllowedPackages": ["io.deepseekharness.mobile"],
  "foreground": { "packageName": "com.example.app", "activityId": "com.example.app/.MainActivity" },
  "limits": { "maxRulesPerPackage": 40, "maxSelectors": 16, "maxPackageBytes": 65536, "maxTotalBytes": 262144, "schema": 1 },
  "stats": { "packageCount": 2, "ruleCount": 5, "storedBytes": 2048, "corruptPackages": 0 },
  "package": {
    "packageName": "com.example.app",
    "digest": "9f2c1ab4",
    "storedBytes": 812,
    "rules": [ /* 与 AutomationRule 一一对应 */ ]
  }
}
```

- `digest`【推】：对**规范化后的规则 JSON**（`AutomationRuleStore.renderRules` 的输出，`AutomationRuleStore.kt:278-279`）做 SHA-256，取前 8–16 个十六进制字符；用于 §4.2 的乐观并发校验。**实现注意**：`renderRules` 的输出必须稳定（键序、数组序），否则 digest 会抖动。
- `foreground.activityId`【推】：取 `rootInActiveWindow` 对应窗口的 Activity 名（现有代码已有窗口/包名路径 `DeepSeekAccessibilityService.kt:88-108`，Activity 名的取法需在实现时确认【验】）。
- **不返回**：任何节点文本、输入内容、密码内容、规则以外的界面信息。

错误码：`AUTOMATION_PACKAGE_DENIED`（包不在白名单）、`AUTOMATION_SERVICE_DISABLED`（复用既有 `ACCESSIBILITY_SERVICE_DISABLED`，`DeviceBridgeServer.kt:220`）、`AUTOMATION_RULE_PACKAGE_INVALID`（包名非法，直接复用 `AutomationRuleCodes`）。

### 4.2 `mobile_automation_rules_set`（写；整包替换）

**用途**：写某个包的全部规则。**整包替换**语义与 `AutomationRuleStore.savePackage` 一致（`AutomationRuleStore.kt:87-93`），不做「增量补丁」——增量会让「AI 以为的状态」和「盘面状态」长期分叉。

入参：

```json
{
  "packageName": "com.example.app",
  "rules": [
    {
      "id": "skip-splash",
      "packageName": "com.example.app",
      "enabled": true,
      "allowActivities": ["com\\.example\\.app\\..*Splash.*"],
      "denyActivities": [],
      "matchDelayMs": 300,
      "maxActions": 1,
      "actionCoolDownMs": 3000,
      "resetOn": "activity",
      "selectors": [
        { "text": "跳过", "clickable": true },
        { "id": "com.example.app:id/btn_skip" }
      ],
      "action": { "type": "click" }
    }
  ],
  "expectedDigest": "9f2c1ab4",
  "dryRun": false
}
```

| 字段 | 类型 | 约束 | 缺省 |
| --- | --- | --- | --- |
| `packageName` | string | 同 §4.1 | 必填 |
| `rules` | `AutomationRule[]` | ≤40；每条 `packageName` 必须等于顶层 `packageName`（否则 `AUTOMATION_RULE_PACKAGE_INVALID`，`AutomationRuleStore.kt:100-106`）；`id` 不重复（`:110-116`）；`packageName` 必须已在白名单内【推】 | 必填（空数组 = 删除该包规则） |
| `expectedDigest` | string | 上一次 `status` 返回的 `digest` | 省略 = 不校验【推，见下】 |
| `dryRun` | boolean | `true` 时**只校验不落盘** | `false` |

返回：

```json
{ "packageName": "com.example.app", "savedRules": 1, "digest": "1c77ab90",
  "storedBytes": 812, "packageBytes": 812, "totalBytes": 2860, "dryRun": false }
```

错误码（**原样回传** `AutomationRuleCodes` 的受控码，便于 AI 自我修正）：

| 码 | 触发 | 出处 |
| --- | --- | --- |
| `AUTOMATION_RULE_MALFORMED` | 不是合法 JSON 对象 / 规则数据不是 JSON 数组 | 【码】`AutomationRule.kt:443`/`AutomationRuleStore.kt:265` |
| `AUTOMATION_RULE_FIELD_INVALID` | 缺字段、类型不符、越界、条数/字节超限 | 【码】`AutomationRule.kt:522`+`AutomationRuleStore.kt:94-135` |
| `AUTOMATION_RULE_UNKNOWN_FIELD` | 未知字段（**含动作里带错字段**） | 【码】`AutomationRule.kt:577-581` |
| `AUTOMATION_RULE_PATTERN_INVALID` | `allowActivities`/`denyActivities` 正则语法错 | 【码】`AutomationRule.kt:507-511` |
| `AUTOMATION_RULE_PACKAGE_INVALID` | 包名非法 / 不在白名单 / 规则包名与目标包不一致 | 【码】`AutomationRule.kt:386`/`AutomationRuleStore.kt:100-106` |
| `AUTOMATION_RULE_DIGEST_INVALID` | `expectedDigest` 与盘面不一致 | 【推】复用已定义但**尚无产生点**的码（`AutomationRule.kt:60`） |
| `AUTOMATION_DISABLED` | 总开关关闭时**允许写规则**（写参数不是危险动作），但返回里带 `enabled:false` 提示 | 【推】 |

**为什么写规则不需要密码**【推】：密码的作用是「谁能改**白名单**」（`AccessibilityAutomationStore.kt:21-30`），而白名单才是「能不能动这个应用」的唯一授权。规则只是「白名单内的应用里，看到什么点什么」，它**不能**把动作范围扩到白名单之外（服务侧在动作前还会重新校验前台包与白名单，`DeepSeekAccessibilityService.kt:321`）。若用户希望更严，见 §8 的 P3 选项：给写规则也加密码。

**`expectedDigest` 的默认建议**：**默认要求**（即 `dryRun=false` 时若无 `expectedDigest` 则报 `AUTOMATION_RULE_DIGEST_INVALID`），理由是自动化规则会长期自动执行，两个人（或人和 AI）同时写很容易出现「你以为你写的是 A，实际盘面是 B」【推，待拍板】。

### 4.3 `mobile_automation_enable`（开关）

入参：

```json
{ "enabled": true, "reason": "用户在本轮对话中要求开启" }
```

返回：

```json
{ "enabled": true, "changedAt": "2026-10-05T12:34:56Z", "serviceEnabled": true, "affectedPackages": ["com.example.app"] }
```

| 字段 | 类型 | 约束 | 缺省 |
| --- | --- | --- | --- |
| `enabled` | boolean | 必填 | — |
| `reason` | string | ≤200，**只进审计不落盘**（文案不含敏感信息） | 省略 |

错误码：`AUTOMATION_SERVICE_DISABLED`（服务没开时 `enabled:true` 无法生效，此时**保持关闭**并如实回报）、`AUTOMATION_ENABLE_FORBIDDEN`（若采用「AI 只能关不能开」的策略，见拍板项）、`AUTOMATION_NO_PACKAGES`（白名单为空，允许开启但回报 `affectedPackages: []`）。

**总开关的存储与默认值**【推】：新增独立偏好键（不复用白名单的偏好文件），**默认 false**；语义 = 「允许无障碍服务按规则自动动作」。服务开启（用户手动在系统设置里开）**不等于**自动化开启——这两个状态必须分开显示、分开记录。

### 4.4 `mobile_automation_trial`（第四个工具：试运行一次并回报）

> **为什么在任务要求的三个工具之外增加第四个**：试运行要在**真实界面**上评估一次匹配，并可能执行一次动作。把它塞进 `rules_set` 会让「写参数」和「真的点一下」变成同一个动作——一旦 AI 误调用写工具，用户手机就被点了。二者的授权级别、审计事件、失败码完全不同，**必须分开**。

入参：

```json
{ "packageName": "com.example.app", "ruleId": "skip-splash", "timeoutMs": 3000, "apply": false }
```

| 字段 | 类型 | 约束 | 缺省 |
| --- | --- | --- | --- |
| `packageName` | string | 必须在白名单内 | 必填 |
| `ruleId` | string | 必须存在于该包规则里 | 必填 |
| `timeoutMs` | int | 500..5000 | `3000` |
| `apply` | boolean | `true` = 真的执行一次（**必须复用既有原生确认浮层**） | `false` |

返回（只评估）：

```json
{ "matched": true, "ruleId": "skip-splash", "activityId": "com.example.app/.SplashActivity",
  "node": { "viewId": "com.example.app:id/btn_skip", "className": "android.widget.TextView",
            "bounds": [980, 120, 1120, 190], "clickable": true },
  "fingerprint": "a91f3c07", "elapsedMs": 41, "executed": false }
```

- `apply:false` **不执行任何动作**、不推进计数、不写 `ACCESSIBILITY_ACTION` 审计（只写 `ACCESSIBILITY_READ` 口径【码】`AuditPolicy.kt:53`）。
- `apply:true` 时走既有确认浮层「确认无障碍动作」【码】`DeepSeekAccessibilityService.kt:187-208`/`:210-271`，用户拒绝 → `ACCESSIBILITY_ACTION_CANCELLED`，超时 → `ACCESSIBILITY_ACTION_TIMEOUT`。
- 失败码：`AUTOMATION_TRIAL_NO_MATCH`（未匹配，含 `elapsedMs` 与最后一次评估的 `activityId`）、`AUTOMATION_SENSITIVE_WINDOW`（命中敏感窗口，如实回报）、`AUTOMATION_PACKAGE_DENIED`、`AUTOMATION_RATE_LIMITED`（冷却未到，回带剩余毫秒）。

### 4.5 桥接命令形状【推】

新增**一条**桥接命令（不新增三条，命令名 ≤32 字符的限制见 `DeviceBridgeServer.kt:197-199`）：

```
command = "automationRules"
param   = {"op":"status"|"set"|"enable"|"trial", ...}
```

- 接入点：`DeviceBridgeServer.kt:211-224` 那个 `commandName in setOf(...)` 分支旁边新增一条分支，走 `AutomationRuleStore` + `DeepSeekAccessibilityService.current()`。
- 能力位：`automationPolicy`（`DeviceBridgeServer.kt:201-210`）的返回里新增 `automationRules: true`；**注意它当前是硬编码的**（含 `schemaVersion:3`），改它要同步前端对该响应的解析【验，§1.8】。
- 工具侧权限门：`lib/index.js:546-552` 现在按 `allowlistedAutomation` 放行动作类工具；`mobile_automation_*` 应走**同一个能力位**（因为它同样只在白名单内动作），但 `trial(apply:true)` 还要额外经过原生确认浮层。

### 4.6 AI 生成选择器的工作流（必须按这个顺序）

```
1) 读节点树：mobile_accessibility_tree（前台必须在白名单内，否则先请用户加白名单）
   —— 只用返回的 viewId / text / desc / clickable / bounds；不要凭空造 viewId
2) 对齐字段：把节点属性映射到 Selector 字段
   —— 精确文本 → text；「包含某词」→ textContains；资源 ID → id（全写 `包名:id/名称` 或短写 `:id/名称`）
   —— 命中节点本身不可点击时，不要靠猜祖先：先加 clickable=true 试，或用 §5.3 的降级链
3) 先校验再落盘：mobile_automation_rules_set(dryRun=true)
   —— 未知字段/越界会被原样拒绝，AI 应**按错误码修正字段名**，不要改成别的合法字段绕过去
4) 写入：mobile_automation_rules_set（带 expectedDigest）
5) 试运行：mobile_automation_trial(apply=false) → 看 matched / node / fingerprint
   —— 不匹配就回到 1)，不要「多写几条总有一条中」
6) 真的执行一次：mobile_automation_trial(apply=true)（用户在屏幕前点确认）
7) 如实回报：把「写了什么、匹配到什么、执行结果、下一步需要用户做什么」写清楚
   —— 禁止把「写成功」说成「已经能用」；禁止在没有 trial 结果时声称效果
```

**硬约束（写进工具描述）**：
- **AI 不能自己开启自动化**：若采用「只能关不能开」的策略，`enable(true)` 直接回 `AUTOMATION_ENABLE_FORBIDDEN`，并引导用户去设置页（`AccessibilityAutomationStore.openSettings`，`AccessibilityAutomationStore.kt:87-89`）。
- **AI 不能绕过白名单**：白名单写入需要密码（`AccessibilityAutomationStore.kt:21-30`），工具面**不提供**任何白名单写入口。
- **AI 不能写敏感动作**：规则里的 `launch` 只能 `http/https/market`【码】`AutomationRule.kt:212`，`key` 只能用允许表【码】`:329-350`，`id` 必须是合法资源名格式【码】`:162-171`。

---

## 5. 执行模型

### 5.1 事件与评估时机【推，接在既有注册上】

- 接线点：`onAccessibilityEvent`（**当前是空实现**，`DeepSeekAccessibilityService.kt:65`）。它已订阅三类事件（`TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_VIEW_SCROLLED`）与 `notificationTimeout = 100L`【码】`:51-63`。
- 事件 → 评估的最小节流链（从外到内）：
  1. 系统级：`notificationTimeout = 100L`【码】`:51-63`（同一窗口内的连续内容变化会被合并）
  2. 服务级：动作间隔 `ACTION_INTERVAL_MS = 350L`【码】`:781-798`（**兜底，不因新功能放宽**）
  3. 规则级：`matchDelayMs`（命中后延迟再确认一次）+ `actionCoolDownMs`（两次执行之间的最小间隔）
  4. 规则级：`maxActions`（次数上限）+ `resetOn`（重置时机）
- **评估必须便宜**：事件回调在服务主线程；节点遍历要复用既有限制（深度 24 / 节点 120 / 敏感扫描 320，`DeepSeekAccessibilityService.kt:781-798`）。评估顺序建议：
  - 先用 `event.packageName` + 前台包判定**早退**（不在白名单 → 直接返回，连节点树都不取）；
  - 再取 `rootInActiveWindow` 并跑敏感窗口判定（`containsSensitiveWindow`，`:359-385`，fail-closed）；
  - 最后才做规则匹配。
- **Activity 变化的取法**：`TYPE_WINDOW_STATE_CHANGED` 的 `event.className` 是 Activity 名的常规来源；`resetOn = "activity"` 依赖它【验：需真机确认不同 ROM 上的取值】。

### 5.2 匹配语义（P0 必须收口的口径）【推】

- 单条 `Selector`：字段之间 **AND**；字段缺失 = 不参与判定。
- `selectors` 列表：**OR**（任一选择器命中即整条规则命中），理由是列表的用途是「同一个语义的多种写法」（`text` 写法 + `id` 写法），而不是「多个条件都要满足」——后者用单条选择器的多字段即可。
  - ⚠️ 这条**必须拍板**：列表语义一旦定成 OR，就不能中途改成 AND。如果将来需要「列表内 AND」，建议**新增字段**（如 `selectorsAll: boolean`，默认 false）而不是改默认语义——否则同一份 JSON 在新旧版本上行为不一致。
- 文本比较口径：`text`/`desc`/`className` = **精确相等**；`textContains`/`descContains` = 包含；`textStartsWith`/`textEndsWith` = 前缀/后缀。大小写敏感（Android 的 `getText()` 原样比较）【验：真机上是否有 ROM 做大小写折叠】。
- 节点属性映射（选择器字段 → `AccessibilityNodeInfo`）：`text`→`getText()`、`textContains`/`textStartsWith`/`textEndsWith`→`getText()` 的字符串运算、`id`→`getViewIdResourceName()`（短写 `:id/名称` 按后缀匹配）、`desc`/`descContains`→`getContentDescription()`、`className`→`getClassName()`、`clickable`/`enabled`/`editable`→同名方法、`minWidth`/`minHeight`→`getBoundsInScreen()` 的宽高【推；逐项须在实现时与 `appendNodes`（`:148-170`）现有取值口径保持一致】。
- **`id` 短写的风险**：`:id/名称` 会匹配任意包的同名资源。**建议**：AI 生成时默认写全写；短写只允许在用户手工编辑时出现【推，待拍板】。

### 5.3 命中后怎么执行【推】

执行降级链（我们的口径：**能精确点节点就不点坐标，坐标只作最后手段**）：

```
规则命中（得到目标节点 N）
  ├─ action = click      → N.isClickable ? ACTION_CLICK(N)
  │                        : 取 N 的最近可点击祖先（深度上限内）
  │                          : 仍无可点 → 点击 N 的中心坐标（bounds 中心）
  ├─ action = clickCenter → 直接点 N 的中心坐标（坐标不在屏内 → 视为不匹配，不点）
  ├─ action = longClick  → ACTION_LONG_CLICK(N)（节点不可长按 → 降级到中心坐标长按）
  ├─ action = back       → 全局返回（GLOBAL_ACTION_BACK）
  ├─ action = swipe      → 从屏幕中心按 direction 滑，时长 durationMs
  ├─ action = key        → 受控按键（仅 AutomationKeyCodes.ALLOWED）
  ├─ action = wait       → 只等待 delayMs，不执行任何动作（用于给界面时间）
  └─ action = launch     → 启动 component（白名单内包名）或 uri（http/https/market）
```

- 现有实现只有 `click`/`setText`/`scroll` 三个动作【码】`DeepSeekAccessibilityService.kt:315-346`；上表的 `clickCenter`/`longClick`/`back`/`swipe`/`key`/`wait`/`launch` **全部需要新增**。
- **`launch` 的红线**：既有的桥接层口径是「不给任意 Activity/Intent」（`docs/应用自动化能力.md:25-28`）。规则里的 `launch` 已经限制到「白名单内包名 + 显式 component」或「http/https/market」（`AutomationRule.kt:299-317`），实现时必须**沿用**这条口径，不得放宽。
- **坐标口径**：`bounds` 是原始设备坐标（`docs/应用自动化能力.md:12-23` 对 `ui_dump` 有同类说明），点击坐标不得经过任何缩放换算。

### 5.4 防连点与防死循环

四层，缺一不可：

| 层 | 机制 | 现状/出处 |
| --- | --- | --- |
| 1 | 服务级最小动作间隔 `ACTION_INTERVAL_MS = 350L`（仅动作类命令且**只有成功才推进** `lastActionAt`） | 【码】`DeepSeekAccessibilityService.kt:91`/`:104`/`:315-346`/`:781-798` |
| 2 | 规则级冷却 `actionCoolDownMs`（默认 3000 ms） | 【码】字段 `AutomationRule.kt:361`；**执行归属待实现** |
| 3 | 规则级次数上限 `maxActions`（默认 1）+ 重置时机 `resetOn` | 【码】字段 `:360`/`:362-363`；**计数状态待实现** |
| 4 | **节点指纹去重**：把目标节点的 `viewId` + `className` + 文本长度 + `bounds` 组合成一个**截断哈希**（不是明文），同一指纹在同一「重置周期」内不重复执行同类动作 | 【推】 |

`resetOn` 的语义（**这两个取值是参数契约的一部分，必须写清楚**）：

| 我们的字段 | 取值 | 语义 | 取舍（什么时候选它） |
| --- | --- | --- | --- |
| `resetOn` | `activity`（默认） | 前台 **Activity 变化**时重置计数与指纹；注意 **Activity 刷新 ≠ Activity 变化**，刷新不重置 | 选它：目标界面在 Activity 不变的情况下长期存在（开屏页、常驻弹窗）。代价是同一界面内换了目标要靠节点指纹去重兜住 |
| `resetOn` | `screen` | 每次**窗口更新**（`TYPE_WINDOW_STATE_CHANGED` / 内容变化到「新界面」）时重置 | 选它：界面内容频繁变化的页面（列表、信息流）。代价是重置更频繁、同一界面可能重复执行；「新界面」的判定阈值更粗【验：需真机确认】 |

**防「自己点自己」的死循环**（最容易被忽略的一条）【推】：
- 执行动作后的 **N 毫秒窗口内**（建议 N = 500 ms，且 ≥ `ACTION_INTERVAL_MS`）发生的窗口变化**不重新触发同一规则**——否则「点击跳过 → 界面变化 → 又匹配到跳过 → 又点」会转圈，而且每次点击都真实落在用户设备上。
- 同一规则连续失败 3 次 → **自动置 `enabled=false` 并记审计**（`DENIED`），并在 `status` 里如实回报「已因连续失败停用」；AI 不得未经用户确认就重新启用。

**状态的归属**【推】：
- 计数/指纹/时间戳**只在内存**（服务进程内），进程重启归零——这与 `maxActions`「重新准备匹配或被唤醒时重算」的语义一致。
- **不进盘面**的理由：盘面只存参数（用户/ AI 写的），运行态属于服务的私有状态；把运行态写盘会带来「规则没变但盘面变了」的困惑，也会多一次 fsync（时延代价见 `docs/前台自动化时延与可行性评估.md:34`）。

### 5.5 失败怎么降级、怎么如实回报

| 失败 | 行为 | 回报 |
| --- | --- | --- |
| 服务未开启 | 不评估、不动作 | `ACCESSIBILITY_SERVICE_DISABLED`（既有码，`DeviceBridgeServer.kt:220`） |
| 锁定/熄屏 | 不评估 | 审计 `DENIED` + detail `AUTOMATION_DEVICE_LOCKED` |
| 前台包不在白名单 | 立即早退（不取节点树） | 不回报（这是正常状态，避免刷屏）；`status` 里能看到 |
| 敏感窗口 | 立即早退（fail-closed） | 审计 `DENIED` + `AUTOMATION_SENSITIVE_WINDOW` |
| 冷却未到 | 跳过本次 | 审计 `STARTED` 后 `DENIED` 或直接跳过【推：建议只记一次 `DENIED`】 |
| 匹配不上 | 只记 `STARTED`，不记失败 | 不回报（否则每个窗口变化都会产生一条错误） |
| 节点找不到（曾经匹配、执行时消失） | 当次动作失败，**不推进 `lastActionAt`** | 审计 `FAILED` + `AUTOMATION_NODE_NOT_FOUND` |
| 动作被系统拒绝 | 当次失败 | 审计 `FAILED` + `AUTOMATION_ACTION_REJECTED` |
| 用户拒绝确认浮层 | 不执行 | `ACCESSIBILITY_ACTION_CANCELLED`（既有码） |
| 连续失败 3 次 | 自动停用该规则 | 审计 + `status` 显示 |

**审计口径（不可放松）**：只能用 `ACCESSIBILITY_CONFIG` / `ACCESSIBILITY_READ` / `ACCESSIBILITY_ACTION` 三类事件【码】`AuditPolicy.kt:50`/`:53`/`:56`；`detail` 必须是受控码（`^[A-Z][A-Z0-9_]{0,63}$`，`:111`）；**不记包名、viewId、文本内容、输入内容**（`:53`/`:56` 的既有注释）。规则执行失败的原因码天然符合这个格式（`AutomationRuleCodes` 全是大写下划线，`AutomationRule.kt:18`/`:54-61`）。

---

## 6. 安全边界（不可放松）

按「谁能改、在哪判定」列出，每一条都给出**必须落在哪个位置**：

| # | 边界 | 判定位置（现状或必须新增处） |
| --- | --- | --- |
| 1 | **只在白名单内、且当前在前台的应用上动作** | 现状：动作前重新校验 `packageName !in allowedPackages` → `ACCESSIBILITY_PACKAGE_DENIED`【码】`DeepSeekAccessibilityService.kt:321`/`:96`。规则评估**必须复用这一判定**，且必须在取节点树之前【推】 |
| 2 | **锁屏与敏感窗口一律跳过（不可由参数关闭）** | 现状：`isLockedOrScreenOff()`【码】`:387-391`、`containsSensitiveWindow()` fail-closed【码】`:359-385`。**规则模型里刻意没有「允许敏感页」字段**（§3.5 缺口 6）——AI 无法通过写参数放开 |
| 3 | **敏感页面清单不可由 AI 扩充/削减** | 现状：`sensitiveText` 正则是**代码常量**【码】`AccessibilityAutomationPolicy.kt:28-34`；规则里没有等价字段 |
| 4 | **限频** | 现状：服务级 350 ms【码】`:781-798`；新增：规则级冷却 + 次数上限（§5.4）。**三层取最严** |
| 5 | **每条动作写审计，字段必须落在 `AuditPolicy` 白名单内** | 现状：`AuditPolicy` 的 `detailPattern`【码】`AuditPolicy.kt:111`；不记路径、正文、密钥（`:53`/`:56` 注释） |
| 6 | **不保存任何密码/密钥/令牌** | 现状：密码只存 PBKDF2 派生值 + 盐【码】`AccessibilityPasswordPolicy.kt:37`/`AccessibilityPasswordStore.kt:70-88`；规则里**没有任何凭据字段**（规则内容不是凭据，落盘保持可读，理由见 `AutomationRuleStore.kt:242-248`） |
| 7 | **默认关闭，必须用户显式开启** | 现状：只有「服务是否开启」这一个隐式开关【码】`AccessibilityAutomationStore.kt:72-85`；新增独立总开关，默认 `false`（§4.3） |
| 8 | **AI 只能写参数，不能绕过以上任何判定** | 新增：工具面**不提供**白名单写入口、不提供敏感页面清单写入口、不提供频率上限的「关闭」开关 |
| 9 | **白名单写入要密码，且先验证后校验格式** | 现状：`AccessibilityWhitelistWritePolicy.authorize`【码】`AccessibilityAutomationStore.kt:21-30`。**不得**为了「让 AI 好写」而在这条路径上加任何旁路 |
| 10 | **规则写入必须在「包已白名单」的前提下** | 现状：`AutomationRuleStore.savePackage` 只校验包名**格式**（`requirePackageName`，`:284-288`），**不校验白名单**。新增：桥接层在写规则前必须同时校验白名单【推，必须补】 |
| 11 | **规则不能让动作逃出白名单** | 现状：`launch` 的包名过 `validPackage`【码】`AutomationRule.kt:308`；`key` 只允许 11 个键【码】`:329-350`；`uri` 只允许三种协议【码】`:212` |
| 12 | **子屏/副屏路径不因本功能放宽** | 现状：副屏读写路径**刻意不查包白名单**（`docs/前台自动化时延与可行性评估.md`/服务内 `displayRootFor`，`DeepSeekAccessibilityService.kt:409-427`）。新增的规则执行**只作用于主屏前台应用**，不要把它接到副屏路径上【推】 |

---

## 7. 免责条款草稿

> 以下两段是**草稿**，用于设置页「自动化」入口下方的说明与应用内《使用条款》。语气克制：只陈述事实与责任归属，不承诺任何安全保证。

### 7.1 中文

> **关于自动化（无障碍）功能**
>
> 本功能会按你（或你授权的 AI 助手）写入的规则，在你指定的应用处于前台时**自动点击、滑动、按键或等待**。规则由你确认后长期生效，因此：
>
> 1. 自动化操作**可能误点**。界面、文案、控件标识随应用更新而变化，规则可能在你没有预期的地方生效，也可能在你预期的地方失效。
> 2. 涉及**支付、验证码、密码、生物识别、权限申请**的页面一律会被跳过，且这一限制不提供开关。但「被跳过」不等于「界面绝对安全」——请勿依赖本功能替你判断风险。
> 3. 请自行确认规则的作用范围与次数上限。**因规则配置错误、来源不明或与界面不匹配所导致的后果，由使用者自行承担。**
> 4. 本功能默认关闭，需要你在设置中显式开启，并需要你在系统设置中手动授予无障碍权限；关闭后立即停止自动操作。
> 5. 本功能**不收集、不上传**你的界面内容、输入内容或规则内容；本机只记录「发生了一次动作、结果是成功还是失败」这类不含内容的审计条目。
> 6. 你不应把本功能用于违反任何应用的服务条款、绕过付费或验证、刷量、抢购等用途。

### 7.2 English

> **About Automation (Accessibility)**
>
> This feature performs **automatic taps, swipes, key presses, or waits** in a foreground app, following rules that you (or an AI assistant you authorize) have written. Rules stay in effect until you change them, so please note:
>
> 1. Automated actions **may hit the wrong target.** Layouts, labels, and view identifiers change when apps update; a rule may fire where you did not intend, and may fail where you did.
> 2. Screens involving **payment, verification codes, passwords, biometrics, or permission prompts** are always skipped, and this restriction has no off switch. Being skipped does not mean a screen is risk-free — do not rely on this feature to assess risk for you.
> 3. Please verify each rule's scope and execution limit yourself. **You are responsible for the consequences of misconfigured, untrusted, or stale rules.**
> 4. This feature is off by default; it requires you to enable it in Settings and to grant the accessibility permission manually in system settings. Turning it off stops automation immediately.
> 5. This feature **does not collect or upload** your screen content, typed content, or rule content. Locally, only content-free audit entries are recorded ("an action occurred, succeeded or failed").
> 6. Do not use this feature to violate any app's terms of service, to bypass payment or verification, or for automated bulk or scalping activity.

---

## 8. 分期计划

> **现状修正**：任务的 P0 里「参数模型 + 存储」**已经有代码落盘**（`AutomationRule.kt` / `AutomationRuleStore.kt`，10/5），但**没有匹配器、没有单测**（`android/app/src/test/java/io/deepseekharness/mobile/accessibility/` 下只有 `AccessibilityAutomationPolicyTest.kt` / `AccessibilityPasswordPolicyTest.kt` / `AccessibilityPasswordStoreTest.kt`；全仓 grep `AutomationRule` 只命中两个主文件）。因此 P0 的实际剩余工作是 **匹配器 + 单测 + 一致性测试**。

### P0：参数模型收口（纯逻辑，可完全在 JVM 单测里跑）

- 交付物：
  1. `AutomationRuleMatcher.kt`（纯函数：`Selector` × 节点快照 → 是否命中；**不碰 `Context`/服务**，与 `AutomationRule.kt:10-12` 的分层一致）。
  2. `AutomationRuleTest.kt`（字段/边界/未知字段/正则编译/按键表/文本精确 vs 包含）。
  3. `AutomationRuleStoreTest.kt`（原子写、坏包自愈、40 条上限、64 KiB/256 KiB、重复 id、整包替换=空列表删除）。
  4. `AutomationRuleMatcherTest.kt`（`selectors` OR 语义、`minWidth/minHeight`、`clickable`、`allowActivities`/`denyActivities`）。
  5. **一致性测试**：`AutomationRule.FULL_VIEW_ID_PATTERN`（`AutomationRule.kt:34-35`）与 `AccessibilityAutomationPolicy.viewIdPattern`（`AccessibilityAutomationPolicy.kt:20`）必须同口径——`AutomationRule.kt:31-33` 的注释声称「有一条单测把两者钉在一起」，但**该测试当前不存在**，P0 必须补上。
- 验证方式：`pnpm run android:check`（= `pnpm run build && cap sync android && cd android && gradlew.bat lint testDebugUnitTest`，`package.json:21`）；纯逻辑单测也可单独跑 `gradlew.bat testDebugUnitTest`【验：任务名以 `android/app/build.gradle` 实际配置为准】。
- **需要先拍板**：`selectors` 列表 = OR（§5.2）。

### P1：服务执行 + 审计 + 试运行反馈

- 交付物：
  1. `onAccessibilityEvent` 接线（`DeepSeekAccessibilityService.kt:65`）+ 早退顺序（白名单 → 锁定 → 敏感窗口 → 匹配）+ 复用 `/ 节点上限`。
  2. 动作执行器：`click`/`clickCenter`/`longClick`/`back`/`swipe`/`key`/`wait`/`launch`（§5.3 的降级链）。
  3. 抑制状态机：per-rule 计数、冷却、指纹去重、`resetOn` 重置、「自己触发不重复触发」窗口、连续失败 3 次自动停用（§5.4）。
  4. 审计：`ACCESSIBILITY_ACTION` + 受控 detail 码；**不带包名/viewId/文本**。
  5. 试运行入口 `mobile_automation_trial` 的原生侧实现（`apply:false` 只评估）。
- 验证方式：
  - JVM：抑制状态机的纯逻辑单测（把时间与事件作为入参注入）。
  - 真机【验】：MuMu 12（1272×2800 / 2800×1272，`docs/应用自动化能力.md:76-85`）上跑「写 1 条规则 → 进目标界面 → 观察是否恰好点 1 次 → 再进一次界面观察是否因 `maxActions` 不再点」。
  - 安全回归【验】：在支付/验证码页面**主动构造**一份规则，确认服务**始终拒绝**（`ACCESSIBILITY_SENSITIVE_WINDOW`），且审计里能看到 `DENIED`。

### P2：AI 工具面接线

- 交付物：
  1. 桥接命令 `automationRules`（`op: status/set/enable/trial`）+ `automationPolicy` 增加 `automationRules` 能力位。
  2. 四个工具定义（§4.1–§4.4）与**工具描述文案**（把「`text` 是精确相等」「未知字段会被拒绝」「试运行必须先 `apply:false`」写进描述）。
  3. 权限门接入 `lib/index.js:546-552` 的既有能力位。
  4. 工具级测试：`scripts/*.test.mjs`（既有 `mobile-device-tools.test.mjs` 的形状可参考【码】`scripts/mobile-device-tools.test.mjs:235`）。
- 验证方式：`pnpm test`（vitest）+ `pnpm test:scripts`（node --test）+ 真机端到端【验】：让 AI 走完 §4.6 的 7 步，把每一步的原始返回贴回来。
- **需要先拍板**：`enable` 是否允许 AI 开启（§4.3 的 `AUTOMATION_ENABLE_FORBIDDEN`）。

### P3：可视化开关 / 免责入口 / 规则列表

- 交付物：
  1. 设置页「自动化」分区：总开关（默认关）、规则列表（按包分组、显示条数/字节占用/最近一次执行结果）、规则编辑（JSON 或表单）、`dryRun` 校验反馈。
  2. 免责条款入口：首次开启总开关时展示 §7 的中文段落，需要用户显式确认（勾选 + 按钮）。**（2026-10-12 已落地：见本文档末「免责闸门」一节。）**
  3. 状态过桥扩展：在 `accessibilityStateToJs`（`MobileRuntimePlugin.kt:2472-2476`）旁边新增规则相关的状态字段，并同步 `src/platform/validation.ts:813-823` 的形状校验。
  4. 审计查看入口（既有诊断页扩展，只展示内容——注意审计本身就不含内容）。
- 验证方式：`src/**` 的单测（vitest，参考 `src/platform/validation.test.ts:917-966` 对状态形状的测法）+ 真机上完整的「开启 → 写规则 → 生效 → 关闭」四步接管。
- **可选（待拍板）**：给「写规则」也加验证密码（与白名单同一把密码或独立密码）。

### 拍板项汇总（本文档需要用户先确认的取舍）

| # | 取舍 | 建议 | 影响 |
| --- | --- | --- | --- |
| 1 | **AI 能否自己开启自动化总开关** | 建议**只能关不能开**（开启必须用户在设置页点） | 决定 `mobile_automation_enable` 的语义与错误码；也决定「AI 自动设置参数」的边界在哪 |
| 2 | **`selectors` 列表语义 = OR 还是 AND** | 建议 OR（AND 用单条选择器的多字段表达） | 决定匹配器实现与 AI 生成选择器的方式；将来若要 AND，建议新增字段而非改默认 |
| 3 | **写规则是否也需要验证密码** | 建议第一版不要（白名单才是授权门槛） | 决定 AI 自动化参数的顺畅程度与「用户在场」的要求 |
| 4 | **`expectedDigest` 是否强制** | 建议强制（默认缺省即拒绝） | 决定并发写入的一致性与 AI 的调用步骤多一步 |

### 拍板结论（2026-10-05，本方确认，后续实现按此为准）

| # | 结论 | 落地要求 |
| --- | --- | --- |
| 1 | **AI 可以开、也可以关自动化总开关** | 硬前置：白名单为空时**拒绝开启**（返回 `AUTOMATION_ENABLE_FORBIDDEN`）。AI 开启/关闭都要写审计日志；开启后设置页与悬浮球显示「自动化已开启（由 AI 设置）」并提供**一键关闭**。**白名单本身仍然只能由人在设置页改**（沿用验证密码）——「能不能动这个应用」始终是人的授权。 |
| 2 | **`selectors` 列表语义 = OR**（任一命中即算命中） | AND 语义用**单条选择器的多字段**表达；将来若需要「列表内 AND」，**新增字段**，不要改默认语义（否则同一份 JSON 前后行为不一致）。 |
| 3 | **写规则不需要验证密码** | 但 `rules_set` **必须携带 `expectedDigest`**（乐观并发，缺省即拒，错误码复用已有的 `AUTOMATION_RULE_DIGEST_INVALID`）。 |
| 4 | `expectedDigest` **强制** | 与第 3 条同一实现；首次写入用「不存在的包」的空摘要常量，读→改→写必须回传上一次的摘要。 |
| 5 | **写规则前必须校验白名单** | 补 `AutomationRuleStore.savePackage` 只校验包名格式的缺口：校验放在**桥接层**（不放松存储层职责），白名单外的包一律拒写并给明确错误码。 |
| 6 | **执行器必须接事件** | `DeepSeekAccessibilityService.kt:65` 现在是 `onAccessibilityEvent = Unit`；接管时必须：只在白名单内且在前台的应用上评估、锁屏/敏感窗口一律跳过、遵守四层节流（`notificationTimeout=100L` → `ACTION_INTERVAL_MS=350L` → 规则冷却 → `maxActions`）、节点指纹去重防自己触发自己、连续失败 3 次自动停用该规则并回报。 |

---

## 执行器（已落地 / 未验证）

> 2026-10-06 追加。上一节 P1 是**交付清单**；这一节记录**本轮真正落地的东西、边界，以及没有验证的部分**。
> 最重要的前提：**全部只在 JVM 单测与编译层面验证过，没有任何真机/MuMu 验证**，下面的"已落地"都不等于"真机可用"。

### 落地的文件

| 文件 | 内容 |
| --- | --- |
| `android/app/src/main/java/io/deepseekharness/mobile/accessibility/AutomationRuleExecutor.kt`（新增） | 纯函数闸门 `AutomationRuleGate` + 状态 `AutomationGateState` + 执行器 `AutomationRuleExecutor.execute(...)` + 节点执行后端接口 `AutomationNodeBackend`。**刻意不 import 任何 `android.*`**，所以能直接 JVM 单测；时间一律由参数注入，内部不取 `System.currentTimeMillis()` |
| `android/app/src/main/java/io/deepseekharness/mobile/accessibility/DeepSeekAccessibilityService.kt`（修改） | `onAccessibilityEvent` 从 `= Unit` 改为接入执行器；新增 `automationRules` 命令；companion 新增 8 个常量。既有 `executeChecked` 的频率限制、30 秒原生确认浮层、`accessibilityTree`/`accessibilityAction`/`tap`/`inputText` 分支**逐字未改** |
| `android/app/src/test/java/io/deepseekharness/mobile/accessibility/AutomationRuleGateTest.kt`（新增） | 闸门用例：四层节流各自拒绝、叠加取最严、时钟回退、指纹去重与上限、白名单/自身包名/锁定/敏感窗口、连续失败与自动停用、冷却重开配额 |
| `android/app/src/test/java/io/deepseekharness/mobile/accessibility/AutomationRuleExecutorTest.kt`（新增） | 执行器用例（含假后端 `FakeBackend`）：命中点击、降级链、失败计数与自动停用、试运行、`NODE_STALE`/`NODE_UNRESOLVED`、各动作的下发与被拒 |
| 未改动 | `AutomationRule.kt`、`AutomationRuleMatcher.kt`、`AutomationRuleStore.kt`、`AccessibilityAutomation*.kt`、`res/**`、`MobileRuntimePlugin.kt`、`scripts/**`、`src/**`、`build.gradle` |

### 四层节流的确切顺序（代码即口径）

事件进入 `DeepSeekAccessibilityService.handleAutomationEvent`：执行开关 → 包名合法 → 跳过 `android` / `com.android.systemui` → 白名单 → **第 1 层** `AutomationRuleGate.admitEvent`（`EVENT_THROTTLE_MS = 100L`，与 `notificationTimeout = 100L` 同值）→ 读界面树 → 锁屏直接返回 → 标记敏感窗口 → 读规则 → 逐条判定。

判定内部顺序固定，**返回第一个拒绝原因**（因此"叠加时取最严" = 顺序最前的那一层）：

1. 已自动停用 / 规则 `enabled = false` / 包名非法 / 不在白名单 / 系统保留包名 / 自身包名 / 锁屏 / 敏感窗口 / `launch` 目标包不在白名单；
2. 结算配额（`syncQuotaWindow` → `syncIdentityWindow` → 冷却到点则 `reopenQuota`）；
3. **第 2 层**绝对间隔 `ACTION_INTERVAL_MS = 350L`：**所有规则共用**一个 `lastActionAt`，不是每条规则各一份；
4. **第 3 层**规则自身冷却 `actionCoolDownMs`：冷却中报 `AUTOMATION_GATE_COOLDOWN_ACTIVE`；
5. **第 4 层** `maxActions`：用满报 `AUTOMATION_GATE_ACTION_MAX_REACHED`；
6. 指纹去重。

- **`maxActions` 是唯一的次数闸门**；**冷却只决定配额何时重开**（冷却到点或时钟回退时把该规则的计数清零）。`actionCoolDownMs = 0` 时报告文案是"冷却已到点，下次判定即重开"。
- 第 1 层**丢弃事件时不刷新基准**：连续高频事件不会把放行时刻越推越远。
- **时钟回退**（`nowMs` 小于上次时间）按放行处理并重置基准——用户改系统时间不该把规则永久卡死。
- 事件量大时**直接丢弃、不排队**：服务侧只有一个 `postDelayed` 令牌（`AutomationReevalToken` 按身份比对），新排期顶掉旧排期。

### 去重、状态提交与自动停用

- **指纹** = 窗口（`包名/Activity`）+ 规则 id + 节点身份（`pathText` / 类名 / 文本 / `viewId` / `bounds`，**故意不含 `clickable`**：按钮从"未激活"变成可点击恰恰是最值得执行的情形，算进身份会被误判 `NODE_STALE`）；同规则、同界面、同节点只执行一次，每规则最多 32 条（FIFO）。
- **状态只在动作成功之后提交**：`recordSuccess` 记配额 + 记指纹 + 连续失败清零；失败走 `recordFailure`。
- **连续失败 3 次（`FAILURE_LIMIT`）自动停用该规则**：服务侧把 `enabled = false` 写回存储（`AutomationRuleStore.savePackage`），Toast 回报，并记审计 `ACCESSIBILITY_CONFIG` + `AUTOMATION_GATE_RULE_AUTO_DISABLED`。
- **只有"动作真的发给了后端、并被拒绝"的失败才计入连续失败**（`CLICK_FAILED` / `LONG_CLICK_FAILED` / `SWIPE_FAILED` / `BACK_FAILED` / `KEY_FAILED` / `LAUNCH_FAILED`，含手势不可用导致的降级链失败）；`NODE_STALE` / `NODE_UNRESOLVED` / `BACKEND_UNAVAILABLE` / `ACTION_UNSUPPORTED` 这类**没有发出动作**的结果只如实上报，不消耗失败额度——否则界面刷新频繁时会把一条本来能用的规则误停用。
- 所有结果码都是稳定码（形如 `AUTOMATION_GATE_*`），可直接进审计的受控 detail 字段。

### 安全边界（都落在执行侧，不依赖调用方自觉）

- `automationEnabled` 默认 **false**，只由 `automationRules` 命令打开，**不落盘**（进程重启回到关闭）。命令要求包名合法且在白名单内，否则拒绝并保持关闭。
- 事件包名必须在白名单内；`android`、`com.android.systemui` 直接跳过；**默认不对应用自身包名（`io.deepseekharness.mobile`）执行动作**（防自触发，显式放行才执行）。
- 锁屏/息屏：在**进入闸门之前就返回**。
- 敏感窗口（`containsSensitiveWindow`）照常传进闸门，拒绝码 `AUTOMATION_GATE_SENSITIVE_WINDOW`；白名单与敏感词口径沿用既有 `AccessibilityAutomationPolicy`。
- 执行前最后一道防线：`resolveNode` 重新读节点并比对身份 → 不一致 `NODE_STALE`、节点已消失 `NODE_UNRESOLVED`，两种情况都不动作。
- `launch` 的目标包也必须在白名单内，否则 `PACKAGE_NOT_ALLOWED`，不启动。

### 本轮明确没做 / 没验的（不要当成已可用）

1. **没有任何真机验证**：只跑了 JVM 单测与编译。真机上的"进界面自动点一次""再进一次不再点""支付页拒动"都**未验证**。
2. **手势路径真机上大概率不可用**：`android/app/src/main/res/xml/accessibility_service_config.xml` **没有声明 `android:canPerformGestures`**，`dispatchGesture` 运行期会返回 false，于是 `clickCenter` / `swipe` / `longClick` 会一路失败到 `CLICK_FAILED` / `SWIPE_FAILED` / `LONG_CLICK_FAILED`（拒绝码 `AUTOMATION_GESTURE_UNAVAILABLE`，文案里写明"服务当前声明里没有 canPerformGestures 能力"）。该文件不在本轮可改范围内——**要真机可用就必须给它加 `canPerformGestures`**。
3. `key`：只有**返回键**与**方向键**可达（`GLOBAL_ACTION_BACK` / `GLOBAL_ACTION_DPAD_*`），方向键还需 Android 12+（API 31+）；`ENTER` / `TAB` / `SPACE` / `DEL` / `ESC` 在**发出动作之前**就返回 `ACTION_UNSUPPORTED`（不计失败）。
4. `launch` 走 `startActivity`（`FLAG_ACTIVITY_NEW_TASK`）：**不经过手势，也不弹原生确认浮层**——外部请求路径的 30 秒确认浮层完全保留、未改动，两条路径互不影响。`uri` 只接受 `http/https/market`。
5. `wait` 不碰界面，只回报 `nextEvaluationDelayMs`，由服务侧 `postDelayed` 重新读树判定；`matchDelayMs` 同理（**一次排期**，不是每个事件重新计时）。
6. 生产路径的配额窗口是"包名/Activity"：`resetOn = "screen"` 使用按应用隔离的窗口序号，只有该应用通过准入的 `TYPE_WINDOW_STATE_CHANGED` 才会推进；其他应用或系统窗口事件不会重开当前应用的配额。
7. 闸门里的 `DEVICE_LOCKED` 分支**目前只被单测覆盖**（服务侧在进闸门之前就 return 了）。
8. 桥接命令 `automationRules` 只做了"读规则 + 打开本次会话的执行开关"（`op` 概念没实现）；`automationPolicy` 的能力位、`mobile_automation_trial` 试运行入口、规则列表 UI、审计查看入口都**还没做**。
9. **口径冲突未动、需要下一轮先定**：本文档与拍板结论第 2 条说 `selectors` 列表是 **OR**，而 `AutomationRuleMatcher.firstMatch`（`AutomationRuleMatcher.kt:371` 一带）与 `AutomationRule.kt:475-476` 的注释是 **AND**（要求全部选择器命中）。匹配器不在本轮可改范围，只记录不改；**这一条直接决定 AI 生成多个选择器时的行为**。
10. 本轮修掉的两个真缺陷（写测试时发现的，已改）：降级点击的身份基准原先用了"中心点矩形"，会让每次降级点击都被判成 `NODE_STALE`；连续失败原先把"没发出动作"的结果也算进去，会误停用规则。

### 本轮验证状态（照实记录）

- **`android/app` 主源码编译：通过**。`gradle.bat --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1536m -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=96m -XX:MaxMetaspaceSize=640m -XX:+UseSerialGC" --console=plain :app:compileDebugKotlin` → `BUILD SUCCESSFUL`，只有既有文件的 `deprecation` 警告（`recycle()` / `getRealMetrics` / `SOFT_INPUT_ADJUST_RESIZE`），没有错误。
- **单元测试：尚未跑成功（被并行改动阻塞）**。`gradle.bat ... :app:testDebugUnitTest --tests io.deepseekharness.mobile.accessibility.AutomationRuleGateTest --tests io.deepseekharness.mobile.accessibility.AutomationRuleExecutorTest` 在 `:app:compileDebugUnitTestKotlin` 阶段失败，**错误不在本轮的三个文件里**：`android/app/src/test/java/io/deepseekharness/mobile/virtualscreen/VirtualScreenSizingTest.kt:388,389` → `Unresolved reference 'NOMINAL_WIDTH_DP'` / `'NOMINAL_HEIGHT_DP'`（另一位写者改了 `VirtualScreenSizing.kt` 却没同步这份既有测试）。单测任务把整个模块的测试源集一起编译，所以上面两个测试类**一个用例都还没跑过**——在它们跑通之前，本文档里所有执行器行为都只是"代码写了 + 主源码编译通过"，**不是"已验证"**。
- 另一个真实的阻塞来自环境：本机 JVM **原生内存**耗尽，Gradle 崩过一次，`android/hs_err_pid8688.log:3` 写的是 `Native memory allocation (malloc) failed to allocate 1150880 bytes. Error detail: Chunk::new` / `Out of Memory Error (arena.cpp:168)`（崩在 `C2 CompilerThread2`，当时可用虚拟内存只剩约 3.6 GB）；另一次是外部 `gradle --stop` 打断了正在编译的构建（`Gradle build daemon has been stopped: stop command received`）。上面的 `--no-daemon` + `-XX:TieredStopAtLevel=1` + `SerialGC` 参数组合就是为绕开这两件事。
- 复跑命令（`android` 目录下，`ANDROID_HOME` 指向 SDK）：
  - 编译：`gradle.bat --no-daemon "-Pkotlin.compiler.execution.strategy=in-process" --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1536m -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=96m -XX:MaxMetaspaceSize=640m -XX:+UseSerialGC" --console=plain :app:compileDebugKotlin`
  - 单测：同上参数，任务换成 `:app:testDebugUnitTest --tests io.deepseekharness.mobile.accessibility.AutomationRuleGateTest --tests io.deepseekharness.mobile.accessibility.AutomationRuleExecutorTest`（用**全限定名**，不要用通配）；结果 XML 在 `android/app/build/test-results/testDebugUnitTest/`。

---

## 总开关与运行记录（2026-10-07 追加）

> 承接上一节：执行器有了，但**没有一个「人在应用里能按的开关」，也看不到它到底做了什么**。这一节记录本轮补上的这两件事，以及一条**与拍板结论冲突、需要用户再拍一次**的口径。

### 落地的东西

| 位置 | 内容 |
| --- | --- |
| `android/app/src/main/java/io/deepseekharness/mobile/accessibility/AutomationControl.kt` | 总开关的三态 `AutomationRuntime`（`off`/`running`/`paused`）、纯函数 `AutomationControlPolicy`（`admits` / `next` / `actionFromName` / `actionName` / `NAME_OFF`）、落盘 `AutomationControlStore`（`apply` / `runtime`） |
| `android/app/src/main/java/io/deepseekharness/mobile/accessibility/AutomationRunLog.kt` | 本次会话的运行记录环形缓冲 `AutomationRunHistory`（`record` / `runs` / `blocked` / `dropped`）+ `AutomationRunLogPolicy`（`MAX_RECORDS_PER_PACKAGE`、`MAX_REASON_KINDS`） |
| `android/app/src/main/java/io/deepseekharness/mobile/accessibility/DeepSeekAccessibilityService.kt` | `handleAutomationEvent` / `reevaluateAutomation` 的首行闸门（总开关不是 `running` 直接返回）；`handleAutomationReport` **在早退之前**记录运行记录（否则「没执行」的那些原因全丢了）；`applyAutomationRuntime` / `automationRuntime()` / `currentAutomationRuntime()` / `automationRunSnapshot` / `automationBlockedSnapshot` / `automationDroppedCount` / `automationRunLimit` |
| `android/app/src/main/java/io/deepseekharness/mobile/MobileRuntimePlugin.kt` | 两个新桥方法 `setAccessibilityAutomationRuntime(call)`、`getAccessibilityAutomationRuns(call)`；`accessibilityStateToJs` 追加 `automationRuntime` / `serviceStatus` |
| `src/App.tsx` + `src/platform/{types,validation,native,browser}.ts` | 设置页「无障碍应用自动化」下的两个 `settings-subsection`：**自动化总开关**（开始/暂停/继续/紧急停止 + 服务状态三态）与**本次会话的运行记录**（最近 20 条 + 拦截原因聚合 + 丢弃计数）；浏览器预览里总开关可跑通状态机、运行记录如实回「未连接 + 空」 |

### 口径：总开关是**落盘的**，`automationEnabled` 是**会话级的**

- 总开关（`automationRuntime`）由**人在设置页**改，落盘、跨重启保留；`paused` 与 `off` 都**不放行**任何规则动作（暂停只是保留「刚才开着」这个事实，方便一键继续）。
- `automationEnabled`（上一节那个）仍是**会话级**、不落盘，只由 `automationRules` 命令打开；本轮给它加了一道前置闸门：**总开关不是 `running` 时，命令直接失败**（`AUTOMATION_ENABLE_FORBIDDEN`，文案引导用户去设置页点「开始」），且**不置 `automationEnabled = true`**。
- 服务掉线（`serviceStatus = enabled_disconnected`）时**不谎报已连接**：界面明确说「系统里已开启，但服务没连上」，并给「打开系统无障碍设置」入口——应用内无法代劳。

### ⚠️ 与拍板结论第 1 条的冲突（**需要用户再拍一次**）

拍板结论（2026-10-05，`:654`）第 1 条写的是「**AI 可以开、也可以关自动化总开关**」，落地要求是：白名单为空时拒绝开启、开/关都写审计、开启后设置页与悬浮球显示「自动化已开启（由 AI 设置）」并提供一键关闭。

**本轮代码采取的是相反的安全侧**（`docs/AI自动化参数设计.md:442` 的「AI 不能自己开启自动化」）：总开关不是 `running` 时，`automationRules` 命令一律拒绝。理由：

1. 上述落地要求里，**除「白名单为空即拒」与「写审计」外都还没实装**——没有「由 AI 设置」标签、没有对应的一键关闭入口，也还没有区分「谁开的」的持久字段；
2. 该命令**目前还不是设备桥的命令**（`DeviceBridgeServer.kt` 的内建命令只有 `automationPolicy` 与 `notify-turn-complete`），也就是说**今天没有任何 AI 路径能走到这里**，这道闸门只是把将来的语义先钉在安全侧；
3. 半成品放行比拦住更危险：AI 打开自动化却没有任何界面痕迹，用户会以为是自己开的。

**待用户确认**：是保持本轮的「AI 只能关不能开」（改文档口径），还是按 `:654` 落地完整要求（补「由 AI 设置」标签 + 一键关闭 + 白名单前置校验的用例）。在拍板之前**不静默放行**。

### 本轮没做 / 没验的

1. **没有真机验证**：总开关、运行记录、服务掉线提示都只有 JVM 单测 + 前端用例 + 编译证据。
2. **运行记录是「本次会话」的**：服务进程一重建就清空；`dropped > 0` 只说明「更早的记录被容量挤掉了」，界面照实显示这个数字，不假装记录完整。
3. **敏感页面拦截原因卡片**里显示的是受控码 + 已登记文案；**未登记的码**只显示原始码（`message` 为 `null` 时不编文案）。
4. **每应用独立配置**（按包名读运行记录的前端入口）本轮只做了桥与校验（`getAccessibilityAutomationRuns(packageName)`），设置页暂时只显示跨包汇总。
5. **规则启用/暂停/禁用**的逐条开关：执行器与存储早已支持（`enabled`），但**设置页还没有规则列表界面**——这一项在交付清单里仍然没做完。（已在下一节补齐。）

## 规则读写界面（2026-10-11 追加）

> 上一节把「开没开 / 为什么没动作 / 服务还在不在」接出来了，但**规则本身在界面上不存在**：AI 写的规则只能靠重新生成来改，用户看不到某个应用有几条规则，也没法把一条老是误触的规则单独停掉。这一节记录补上的「按应用查看、逐条启用/停用」，以及**它刻意不做的那几件事**。

### 两个新桥方法（`android/app/src/main/java/io/deepseekharness/mobile/MobileRuntimePlugin.kt`）

| 方法 | 参数 | 返回 | 拒收条件 |
| --- | --- | --- | --- |
| `getAccessibilityAutomationRules` | `packageName`（**必填**） | `{packageName, allowed, corrupt\|null, rules:[…]}` | 缺参数 / 格式非法 → `ACCESSIBILITY_CONFIG_INVALID` |
| `setAccessibilityAutomationRuleEnabled` | `packageName`、`ruleId`、`enabled` | 同上的整包读数 | 规则 id 非法 / `enabled` 不是布尔 → `ACCESSIBILITY_CONFIG_INVALID`；**包不在白名单** → `ACCESSIBILITY_CONFIG_DENIED`；规则不存在 → `ACCESSIBILITY_CONFIG_NOT_FOUND`；写盘失败 → `ACCESSIBILITY_CONFIG_SAVE_FAILED`；数据损坏 → `ACCESSIBILITY_CONFIG_CORRUPT` |

- **包名必填，不提供「省略即前台包」的兜底**：省略会让人以为查的是自己正在看的那个应用，而实际查的是别的包。
- **只改 `enabled` 一个字段**：规则内容、白名单、总开关都不动；停用一条规则**不会删掉它**。
- **写规则不需要验证密码**，但**必须过白名单**：白名单是「这台设备允许自动化哪些应用」的边界，改白名单要密码，按规则开关不需要——但白名单外的包一律拒写（`ACCESSIBILITY_CONFIG_DENIED`），这样即使校验被绕过，也不会写出一个永远不会被执行的规则。
- 审计写 `AUTOMATION_RULE_ENABLED` / `AUTOMATION_RULE_DISABLED`（`AuditEvent.ACCESSIBILITY_CONFIG`）。

### 回显刻意只带界面要判断的字段

`rules[]` 每项只有 `id`、`enabled`、`actionKind`（`action.type`）、`selectorCount`、`maxActions`、`actionCoolDownMs`——**不带选择器正文**（文本与 `viewId` 足以反推界面内容），也不带允许/拒绝的 Activity 名单。界面只需要回答「这条规则是什么动作、几个选择器、最多点几次、现在启没启用」。

### 前端（`src/platform/*` + `src/App.tsx`）

- `src/platform/types.ts` 新增 `AccessibilityAutomationRule` / `AccessibilityAutomationRulesCorrupt` / `AccessibilityAutomationRules`；`src/platform/validation.ts` 新增 `validateAccessibilityAutomationRules`（规则 id 与原生 `AUTOMATION_RULE_ID` 同一套 `^[A-Za-z0-9._-]{1,64}$`，三个计数必须是非负安全整数）、`assertAccessibilityRuleId`、`assertBooleanFlag`。
- `src/platform/native.ts` 两条实现都折进 Promise 链：**同步抛出会绕过调用方的失败分支**（界面拿不到 `failed` 状态，只看到一个没反应的按钮），这是上一批踩过的坑。
- `src/platform/browser.ts` 如实拒绝「浏览器预览没有无障碍规则存储」——**不回空列表，也不放内存规则**：「0 条规则」会被界面显示成「你还没有规则」，而真相是这里读不到；预览里能开关的规则到真机上并不存在，那是最容易误导人的假成功。
- `src/App.tsx` 的「自动化规则」区块：白名单里的应用一排按钮（`aria-pressed` 标当前查看的应用，进页面自动读第一个），每行一条规则 + 「启用」/「停用」；**不做乐观更新**（写失败时开关保持原位，只弹错误），写完重新读取整包读数。三种空态**分开说**：
  - 这个应用还没有规则；
  - 白名单外的应用：这些规则不会被任何事件用到（按钮禁用）；
  - 规则数据不可用，已被清除：`{码}`（`{说明}`），需要重新生成规则。

### 本轮没做 / 没验的

1. **没有真机验证**：写盘后规则是否立刻生效、损坏态在真机上长什么样，都只有前端用例 + 编译证据。
2. **两个新桥方法本身没有 JVM 单测**：`@PluginMethod` 的入参与拒收分支（白名单拒写、规则 id 非法、损坏）依赖 `PluginCall`，JVM 单测里构造不出来；这一层目前由 `scripts/native-bridge-parity.test.mjs` 的同名比对、`src/platform/validation.test.ts` 与 `src/App.accessibility.test.tsx` 间接覆盖，原生侧的拒收分支只能等真机/仪器化测试。
3. **没有「新建 / 编辑 / 删除规则」的界面**：这一批只做「看 + 开关」。规则内容仍由 AI 生成、人在写盘前确认；删除规则只能靠重新生成覆盖整包。**（2026-10-12 已补：见下一节。）**
4. **没有「每应用独立配置」的表单**：白名单、`maxActions`、冷却时间这些参数的编辑入口仍只有桥与存储，没有界面。

## 规则的写入面：编辑必须先读全文（2026-10-12 追加）

> 上一节留了一条明确的缺口：「新建 / 编辑 / 删除规则」的界面不存在（原生写入口与前端平台层先落了地，界面还没接）。补这个缺口时先撞上一个**会毁数据**的问题，这一节先记它，再记怎么绕开。

### 为什么不能拿列表摘要预填

原生 `update` 是**整条替换**（`AutomationRuleStore.savePackage` 整包一次原子写，`AutomationRule.fromJson` 没给的字段按原生默认值写下去），而列表回显**刻意只带摘要、不带选择器正文**（上一节：文本与 `viewId` 足以反推界面内容）。

两件事凑在一起就是一条数据毁伤路径：**表单预填不出原来的选择器，用户点一次「保存」就等于把它们整片抹掉**——界面上看不出任何异常，规则还在、只是不再命中。

三条出路里选第三条：

1. 列表多回正文 —— 直接违背上一节的脱敏口径；
2. 让原生支持「只改某几个字段」的补丁式写入 —— 等于重做写入语义，且 `update` 的原子性与「整条替换」的既有测试都要推翻；
3. **没有正文就不许编辑**：新增一个只读的「读一条规则全文」桥方法，编辑先读、读到了才让改。

### 第三个桥方法：`getAccessibilityAutomationRule`

| 方法 | 参数 | 返回 | 拒收条件 |
| --- | --- | --- | --- |
| `getAccessibilityAutomationRule` | `packageName`（必填）、`ruleId`（必填） | `{packageName, ruleId, allowed, rule}`，`rule` 是**与写入口同一套键**的完整规则（`AutomationRule.toRuleJson()`） | 缺参数 / 格式非法 / 包名不符 → `ACCESSIBILITY_CONFIG_INVALID`；规则不存在 → `ACCESSIBILITY_CONFIG_NOT_FOUND`；数据损坏 → `ACCESSIBILITY_CONFIG_CORRUPT` |

- **它是只读的**：不写盘、不打开任何开关、不需要验证密码、**白名单外的包照样读得出来**——规则是用户/AI 写下的数据，能不能改由返回里的 `allowed` 在界面上决定，而不是让读操作本身失败。
- **读回来与写进去是同一套键**：`AutomationRuleEditor.ReadOutcome` 只有 `Found(rule)` 与 `Rejected(code, message)` 两种结果，`Found` 里的规则直接来自存储解析，再走 `toRuleJson()` 原样出去。前后端因此可以**复用写入侧那套逐项校验**（`assertAccessibilityAutomationRuleInput`）来校验读回值——两边形状不一致时才需要两套，那正是「读回来的东西填不进表单」的来源。
- 前端多校验两件事：返回里的 `packageName` 与 `ruleId` 必须**与请求对得上**（对不上说明桥回错了包，拿它预填等于把 B 的内容写进 A）。

### 前端平台层（`src/platform/*`）

- `src/platform/types.ts` 新增 `AccessibilityAutomationRuleDetail`，接口 `NativeRuntimePlugin` 新增 `getAccessibilityAutomationRule`（`scripts/native-bridge-parity.test.mjs` 会与新桥方法同名比对，漏一侧即失败）。
- `src/platform/validation.ts` 新增 `validateAccessibilityAutomationRuleDetail(value, packageName, ruleId)`：包名与规则标识先对齐请求，里层规则直接复用 `assertAccessibilityAutomationRuleInput`。
- `src/platform/native.ts` 的入参自检**折进 Promise 链**（同步抛出会绕过调用方的失败分支）；校验读回值时把 `owner` / `id` 留在闭包里，不与原生返回的值混用。
- `src/platform/browser.ts` 如实拒绝「浏览器预览没有无障碍规则存储」：**不回一份空规则**——空规则预填进表单，点一次保存就等于把真机上那条规则整条清掉，这与「回空列表会被显示成『你还没有规则』」是同一类假象。

### 界面（`src/components/AutomationRuleForm.tsx` + `src/App.tsx`）

- **两道闸都在「读不回全文就不开表单」上**：`startEditRule`（`src/App.tsx:3783`）先 `setAutomationRulePending(ruleId)`，读回成功才 `setAutomationRuleDraft({mode:'update', rule: detail.rule})`；失败分支**只写提示文案**，`automationRuleDraft` 保持 `null`，而表单的挂载条件就是 `automationRuleDraft !== null`（`src/App.tsx:4506`）。组件内再加一道 `mode === 'update' && rule === null` 的守卫（`src/components/AutomationRuleForm.tsx:707-719`）：只显示原因与「关闭」，**一个输入框都不渲染**——两道闸任意一道失效，另一道仍挡得住「空表单 + 保存」。
- **字段一个都不丢是类型保证的**：草稿类型是 `StringDraft<Required<AccessibilityAutomationSelectorInput>>`（13 个选择器字段全为必填字符串），`selectorToDraft` / `draftToSelector` / `SELECTOR_FIELD_LABELS` 三处少一个键就过不了 `tsc`。折叠只影响视觉：冷门字段（`textStartsWith` / `textEndsWith` / `descContains` / `clickable` / `enabled` / `editable` / `minWidth` / `minHeight`）收在 `<details>` 里，输入框始终在 DOM 中，`<summary>` 上写「更多条件（已填 N 项）」，收起来也看得出填了几项。
- **三态与数字的坑各有一个名字**：三个布尔字段是 `不限 / 是 / 否` 三态（`''` 与 `'false'` 不是一回事，`false` 是「只匹配不可点击的节点」这个真实约束）；`launch` 的 uri 只认 `http://` / `https://` / `market://`；`key` 读回表外键值时用 `#<值>` 占位原样带回，让原生按「不在允许的按键表内」拒收，而不是界面悄悄换一个键。
- **切换动作类型是整组重建**，不是把用不上的字段藏起来（藏起来的话切回来旧值会复活，而提交时它确实会被带上）；`create` 的默认值填完标识就能直接保存。
- **保存与删除都用桥回来的整包读数刷新列表**，不本地改再回滚、也不补一次读（补读可能读到旧的，界面会短暂显示与刚写进去的内容不一致的列表）；删除有二次确认并写明「整条删除」。
- **写入失败不改列表**：错误原样显示（表单开着就显示在表单里，没开就显示在卡片里），表单里用户刚敲的内容不丢（草稿只挂载时建立一次，父组件重渲染不冲掉它；换一条规则编辑由 `key` 触发重建）。

### 本轮没做 / 没验的

1. **没有真机验证**：新桥方法在真机上能否读回一条由 AI 写入的规则、`toRuleJson()` 的键是否与写入侧逐字一致（本机只有 JVM 单测与前端用例）。
2. **`@PluginMethod` 的拒收分支仍无 JVM 单测**：依赖 `PluginCall`，JVM 里构造不出来；由编译、前端用例与桥接契约间接覆盖。
3. 读全文这条路**只给编辑用**，不改变列表口径：列表仍然是摘要，界面上不展示选择器正文。
4. **表单根元素是 `div` 而不是 `form`**：设置页本身是一个 `<form>`，表单不能套表单（套起来时「保存规则」的提交事件会冒泡到设置页那个表单上，顺手把别的设置也存了）。代价是**在输入框里按回车不会提交规则**，只能点「保存规则」；要支持回车得先在设置页那层加事件来源守卫。
5. **白名单外的应用仍可新建 / 编辑 / 删除**（只禁用「启用/停用」）：门槛在原生，写失败会把原因（`ACCESSIBILITY_CONFIG_DENIED` 的中文说明）原样显示出来。这是刻意留的——读得出规则、写不进盘，比把入口全禁掉更容易让人看懂发生了什么；若改成直接禁用，一处开关即可。
6. **预检只做「明显不合法」一档**（空标识、0 选择器、数字越界、`launch` 既无组件也无链接、uri 协议不符）：标识字符集、选择器上限、动作参数收窄仍由 `src/platform/validation.ts` 与原生判定，界面原样展示 rejected 的文案。所以「填了中文标识」这类只会在提交后由桥回来的文案提示。
7. **需真机确认**：大规则（16 选择器 × 13 字段）打开编辑表单的首屏渲染是否卡（表单是全量受控渲染，无虚拟化）、窄屏 + 软键盘弹出时底部按钮是否被遮挡、`<details>` 折叠区的展开手感、以及原生失败文案在真机上是否与界面预期一致。

## 免责闸门：第一次开启总开关前必须显式确认（2026-10-12 追加）

> P3 第 2 项要求「首次开启总开关时展示 §7 的中文段落，需要用户显式确认（勾选 + 按钮）」。这一节记它怎么落地，以及刻意没做什么。

### 拦谁、不拦谁

- 拦「**开始**」与「**继续**」：两个动作都是「让自动化跑起来」，只拦 `start` 的话，先开一次、再暂停，之后每次「继续」都会绕过说明。
- **不拦「暂停」与「紧急停止」**：它们是收敛动作。一段必须读完的文字挡住急停，等于把安全入口变成障碍——「急停要能一键生效」优先于「说明必须看全」。
- 闸门只看**本机是否确认过**，不区分动作是谁触发的。AI 侧本来就没有开启总开关的路径（安全侧 `AUTOMATION_ENABLE_FORBIDDEN`），所以这道闸门拦到的全是人点的那一下。

### 确认存在哪

- 键 `dsh-mobile-automation-disclaimer-v1`，值是字符串 `'1'`（`src/accessibilityDisclaimer.ts`）。**读失败与写失败都当成「没确认」**：无痕模式里写不进去就每次重新确认，比假装已确认安全。
- 只认 `'1'`：`'true'` / `'yes'` / `'1 '` 一律算没确认（与 `src/theme.ts`、`src/i18n.ts` 同一套「读不出合法值就回默认」的写法）。
- 确认写入失败**不阻止本次开启**（用户刚刚就在眼前确认过），只是下次还会再问一遍；这里不把「写盘成功」当成开启的前提。

### 回看入口

- 控制台里常驻一个「查看完整免责说明」（`src/App.tsx:4374`），打开的是**同一段文字**，只有「关闭」：不写确认、不动总开关。理由是确认过之后不再弹窗，得让人随时查得出自己当初同意了什么。
- 两个模式共用一个组件 `src/components/AutomationDisclaimer.tsx`（`mode: 'confirm' | 'read'`），文案逐条对应 §7.1，英文见 §7.2 与 `src/locales/en.ts:1227` 一带。

### 与设置页那段静态「免责说明」的关系

- `src/components/DeviceShellSettings.tsx:37-45` 里那段短说明（三条）**继续留在原位**：它讲的是「这个能力是什么、只对自己有权操作的应用用、界面内容按需进入当前会话」。弹窗里的是开启前的六条（§7.1 原文）：可能误点、敏感页面被跳过不等于安全、责任在谁、默认关闭、不收集不上传、不许拿来绕过验证。
- 两处都要有：一处随时可读，一处开启前必读。它们不是同一段文字的两个副本，改其中一处时要各自确认。

### 本轮没做 / 没验的

1. **没有真机验证**：六条要点在小屏 + 系统字体放大时是否需要滚动、勾选框是否够大，目前只有布局用例的间接证据。
2. **确认只记在「本机」**：换设备、清应用数据后要重新确认。这是刻意的（确认是对这台设备说的），但因此**不能**把它当「用户同意过」的证据链。
3. ~~**没有条款版本号**：这六条要点将来被改写时，已确认过的用户**不会**被重新问一遍。~~ **（2026-10-12 已补：确认记录的键名带上 `AUTOMATION_DISCLAIMER_VERSION`，弹窗里也印着这个版本号——改这六条要点就必须把版本号加一，旧确认随即失效、所有人被重新问一遍；`src/accessibilityDisclaimer.test.ts` 有专门用例守住这条约定。仍未做的是「条款正文留档」：旧版本的原话没有按版本存成不可变资源，改的是「同一段文字换了个说法」。）**
4. **弹窗根元素不是 `<form>`**，在弹窗里按回车不会提交（同上一节第 4 条：设置页本身是 `<form>`，套表单会冒泡）。
5. 与那条仍未拍板的问题无关：**AI 能否开启自动化总开关**仍未定（§4.3 的 `AUTOMATION_ENABLE_FORBIDDEN` 与 `:654` 的拍板结论相反），本批没有碰它。

## 每应用参数：按应用暂停与新建规则的默认值（2026-10-12 追加）

前面几节把「规则」这条线打通了：写入面（新建 / 编辑 / 删除）、编辑前先读全文、免责闸门都在。这一节补的是**规则之外的、按应用生效的参数**——用户按下暂停之后，这个应用的事件不再进入规则求值；以及新建规则时那几个数字的初值。

### 为什么要与规则正文分开

规则是**按包名分片**存的（`AutomationRuleStore` 每个应用占一个 slot，包内最多 40 条、包 JSON 最多 64 KiB），而参数是「这个应用的行为边界」。把默认值塞进规则 JSON 会带来三件坏事：改一个默认值要重写整包数据、受规则限额约束、还会经过规则校验（一条规则的字节问题会连带让参数也写不进去）。

所以参数单开一份偏好文件，一个应用一个键，**单键单写、整条替换**。

### 四个字段与语义

| 字段 | 语义 | 范围（与 `AutomationRuleLimits` 同源） |
| --- | --- | --- |
| `paused` | 这个应用的事件是否进入规则求值 | 布尔 |
| `defaultMaxActions` | 新建规则表单的初值 | 1..99 |
| `defaultActionCoolDownMs` | 同上 | 0..600000 |
| `defaultMatchDelayMs` | 同上 | 0..5000 |

两条必须写清楚的语义：

- 三个 `default*` **只给新建规则表单当初值**：它们不改已有规则、也不在运行时做上限强制。每条规则落地后自带自己的数字，与这里的默认值再无关系。做成「改默认值即批量改写已有规则」是另一种产品，本批刻意没做——那等于静默改写用户已经确认过的内容。
- `paused` 是**运行时开关，且只挡规则求值**：设备桥的单次动作（AI 明确要求的那一下）照旧走白名单与敏感窗口判定。暂停的含义是「别自动动」，不是「谁都不许动」。

### 落盘与 fail closed

`android/app/src/main/java/io/deepseekharness/mobile/accessibility/AccessibilityAppConfigStore.kt`：

- `FILE_NAME = "accessibility_automation_app_config"`（`:63`），键 `app:<包名>`（`:65`）。写入前三条 `require`（包名合法 + 三类范围），文案里的数字直接从 `AutomationRuleLimits` 拼出来（`:155-163`），改限额时文案跟着变。
- 存储抽象 `AccessibilityAppConfigStorage { read / write / remove }`（`:171-177`）与 `AccessibilityAppConfigPreferences.from(context)`（`:180-198`）：落盘用 `commit()` 而不是 `apply()`——与 `AutomationRuleStore.kt:460/464` 同一口径，这条开关按下之后要立刻落到盘上（lint 里因此多两条 `ApplySharedPref` 警告，与既有那几处同类，是刻意留的）。
- **读不出来就当已暂停**：坏 JSON、字段类型不对、存量数字越界，一律返回 `Corrupt`，`config.paused` 按 `true`（`:146-147`）。
- **坏数据不自动删除**：规则那边遇到坏数据会删掉自愈（丢的是规则正文），这边丢的是「用户按过暂停」，所以只报告、由用户决定要不要覆盖。
- 热路径 `isPaused` 走**同一个解析器**，不是只 `opt` 一下 `paused` 字段。这一条是被失败的测试逼出来的：最初写成只读 `paused`，于是「盘上 `paused=false` 但数字越界」时，`read` 判整份不可信、热路径却认为没暂停——两条判定分叉，「读不出来就当已暂停」在真正生效的路径上被绕过。现在 `isPaused` 复用 `read` 的结论，多解析四个字段的代价远小于留下一次静默失效。

### 服务侧两道闸

`android/app/src/main/java/io/deepseekharness/mobile/accessibility/DeepSeekAccessibilityService.kt`：

- `:934`（事件路径）：白名单判定之后、锁屏判定之前。放在这里的理由：事件包名已知、白名单已过，此时早退就不必读控件树、不必读规则、也不写执行记录。
- `:1013`（延迟重判定路径）：`packageAllowed` 之后同样一道。少了这一道，暂停之前排队的那次 `matchDelayMs` / wait 重判定会绕过暂停。

### 暂停的回执：只累计原因，不写执行记录（2026-10-12 补）

暂停是「用户自己按下的状态」，一开始的写法是**什么都不记**（理由是「不该刷屏」）。这个理由只对了一半：

- 每个事件写一行执行记录确实是错的——同一个包的暂停事件会把 `MAX_RECORDS_PER_PACKAGE`（50）的环形缓冲刷满，真正的执行历史被挤掉、`dropped` 一路涨。
- 但**一条都不记**同样是错的：用户按下暂停之后，设置页那一块看起来像什么都没发生，而「什么都没发生」正是会被读成「自动化坏了」的显示，用户接下来多半会把总开关也停掉、或者去加一条注定不会触发的规则。

所以改成**只累计一条拦截原因**：`AutomationRunHistory.noteBlocked(packageName, code, count)`（`android/app/src/main/java/io/deepseekharness/mobile/accessibility/AutomationRunLog.kt`）只更新「原因 → 次数」的聚合表，不碰执行记录的队列、不影响 `dropped`。两道闸在早退前各补一次：

- 受控码 `AUTOMATION_APP_PAUSED`（`AutomationGateCodes`，与锁屏、敏感窗口同属第 0 层「场景」类拒绝；它由服务侧发出，执行器永远不会产生它）。文案「这个应用已被暂停，事件没有进入规则求值」登记在 `AutomationSkipMessages`，`AutomationSkipMessagesTest` 用反射守着「码与文案一一对应」，漏登记会直接变红。
- 「× N 次」在卡片上的含义这里是**被丢掉的界面事件数**（一个界面变化算一次），不是动作次数。这条差异写进了码的文案注释里：同一个数字在别的码上是「评估了多少次」。
- 拦截原因卡片是按当前查看的应用过滤的，所以暂停的计数会落在那个应用的桶里（`noteBlocked` 照样建桶）——包名建不出桶时，界面上就是「这个应用什么都没发生」。
- 暂停开关的说明里加了一句指路：暂停期间被丢掉的事件会计入运行记录里的「拦截原因」（`src/components/AutomationAppConfigForm.tsx:278`）。用户得知道去哪里确认它真的在生效。

**仍然缺的**：设备桥不感知暂停（AI 收不到任何回执）。这属于还没实现的 `automationRules` 命令（§4.5，`op:"status"`），届时它该回 `pausedPackages`，而不是在这里另开一条命令。

### 桥方法

`android/app/src/main/java/io/deepseekharness/mobile/MobileRuntimePlugin.kt`：

- `getAccessibilityAutomationAppConfig`（`:2374`）：只读、不需验证密码、白名单外的应用照样读得出来。
- `setAccessibilityAutomationAppConfig`（`:2393`）：过 `requireAutomationWriteAllowed`（白名单，与写规则同一个闸），**不要求验证密码**（密码是改白名单的闸，白名单才是写规则的闸，这里沿用后者）；`paused` 必须是布尔，三个整数**全必填**——缺一个就拒收，不拿默认值补：补出来的数字会以「用户没改过」的样子落盘，而盘上原本的坏数据也永远覆盖不掉。
- 返回体由 `accessibilityAppConfigToJs`（`:2418`）拼：`{packageName, allowed, corrupt, config}`，**损坏不抛错**。参数是「按应用开关」：抛错会让用户既看不到坏数据、也无法用一次保存覆盖它。

### 前端平台层（`src/platform/*`）

- `src/platform/types.ts:839-868`：`AccessibilityAutomationAppConfig` / `AccessibilityAutomationConfigCorrupt` / `AccessibilityAutomationAppConfigDetail`，注释里写明「三个 `default*` 只做表单初值」「`corrupt` 非空时 `paused` 按真返回且原生不自动删坏数据」。
- `src/platform/validation.ts`：`validateAccessibilityAutomationAppConfigDetail` 四个字段一个不能少（缺了若补默认值，界面显示的就不是真机上跑着的那套参数）、包名必须与请求一致、`corrupt` 照收（有码没文案也算读不懂）；`assertAccessibilityAutomationAppConfigInput` 是写入侧同一套区间预检。
- `src/platform/native.ts`：两个桥方法，入参自检折进 Promise 链（同步抛出会绕过调用方的 `.rejects` 分支）。
- `src/platform/browser.ts`：两个方法都直接失败——「浏览器预览没有每应用自动化参数」/「浏览器预览不能修改每应用自动化参数」。**不回一份默认值**：回「没暂停、上限 1」看起来像真读到了，用户点一次保存就是拿预览里的假数字覆盖真机。

### 界面（`src/components/AutomationAppConfigForm.tsx` + `src/App.tsx`）

- **两份读数一起换**：`selectRulesPackage`（`src/App.tsx:3837`）同时调 `loadAutomationRules` 与 `loadAutomationAppConfig`（`src/App.tsx:3815`）。分开调会出现「规则已经是新选的包、参数还写着上一个包」的一瞬间，而那一瞬间正好是用户会照着填默认值的时候。读参数**开读前先清掉上一份读数**，免得拿上一个应用的数字给新表单当初始值。
- **换包即重建表单**：表单挂载时带 `key`（`automation-app-config-<包名>`，`src/App.tsx:4694`）。表单草稿只在挂载时按读数建立一次、之后不跟 props 走（父组件每次重渲染都会传一个新对象，跟着走会冲掉用户刚敲进去的内容）；换包靠 `key` 重建草稿，这是唯一该重建的时机。
- **读不到就不给输入框**：`detail === null && status === 'failed'` 只渲染原因 + 「重试」，一个输入框、一个开关都不出现（`src/components/AutomationAppConfigForm.tsx:153-174`）。原生写入是整条替换，界面只要在没读到参数时拿一份「没暂停、上限 1」顶上，用户点一次「保存参数」就是用界面编的数字覆盖真机数据。这条是防抹除的核心，App 层用例断言 `automation-app-config-save` 与 `role="switch"` 都不存在（`src/App.accessibility.test.tsx:836-855`）。`idle` / `loading` 同样按「还没读到」渲染。
- **损坏读数照说**：`corrupt` 非空时用 `role="alert"` 把受控码与原文一起说出来，并写明「原生按『已暂停』处理、不会自动删掉它、保存一次就会覆盖掉它」（`src/components/AutomationAppConfigForm.tsx:242-250`）；输入框照常显示（值取自读数，此时暂停为真）。
- **不在白名单**：字段仍可编辑，只禁用保存（`saveDisabled = busy || locked || !detail.allowed`，`src/components/AutomationAppConfigForm.tsx:193`），旁边说明「保存参数会被原生拒绝」——让原生那句拒写原因原样露出来，而不是界面先替它拦住。
- **整条写回**：提交时四个字段一起给（`src/components/AutomationAppConfigForm.tsx:195-222`），本地预检只挡「明显不合法」一档（空、非整数、越界，区间取自 `AUTOMATION_RULE_LIMITS` 并写成输入框的 `min`/`max`）。「只发改动的那一个字段」在这里不是优化，是把用户配好的另外三个数字顺手冲掉。
- **不做乐观更新**：`saveAutomationAppConfig`（`src/App.tsx:3962`）成功后用**桥返回的那份读数**刷新，界面不自己拼一份「应该是什么」；失败时草稿一个字都不动，只留下原因。App 层用例刻意让返回读数与提交值不同（`paused: true`、冷却 60000），断言输入框仍是提交值、而「当前生效」那行显示的是返回读数（`src/App.accessibility.test.tsx:857-891`）。
- **「当前生效」一行始终直接来自读数**（`data-testid="automation-app-config-effective"`），不经过草稿：草稿不跟 props 走，所以写入后输入框里仍是用户提交的数字，盘上真正生效的数字由这一行负责显示。
- **暂停开关只挡规则求值**，界面上写明它拦不住设备桥的单次动作——否则用户会以为拨过去就什么都点不动了。
- **「恢复默认值」只填表单、不写盘**（`src/components/AutomationAppConfigForm.tsx:230-233`）：一键写盘等于把「盘上现在读不出来」这件事顺手抹掉，要生效必须再点一次「保存参数」。
- **新建规则表单的初值**：`startCreateRule`（`src/App.tsx:3876`）把读到的三个 `default*` 传给 `AutomationRuleForm` 的新可选 `defaults`（`src/components/AutomationRuleForm.tsx:80-105`）；**编辑模式一律忽略它**（用读回的整条规则）。没读到参数时不传，表单回落到原生默认值 1 / 3000 / 0 —— 那是**表单初值**的回落，与「把一份没读到的读数当真相显示」是两回事。

### 本轮没做 / 没验的

1. **没有真机验证**：暂停开关的手感、以及厂商 ROM 下的行为都没上真机。
2. 服务侧两道闸本身**没有单测**：`AccessibilityAppConfigStoreTest` 覆盖的是存储层的 11 种情形；事件路径需要真实的 `AccessibilityNodeInfo`，本项目没有这个接缝，只能靠代码位置复核。
3. **「为什么没执行」只有聚合计数，没有逐条流水**：暂停被记进拦截原因（`AUTOMATION_APP_PAUSED`，见上一节），但**不写执行记录**（写的话会把环形缓冲刷满）。所以能回答「暂停拦掉了 N 次事件」，回答不了「哪一秒想跑哪条规则」。
4. **设备桥不感知暂停**：单次动作不受影响是刻意的，但 AI 也不会因为用户按了暂停而收到任何回执；这条要等 `automationRules` 命令的 `op:"status"`（§4.5）。
5. AI 能否开启自动化总开关仍未拍板，本批没有碰它（同上一节第 5 条）。
6. **写入返回读数与提交值不一致时，输入框仍显示提交的数字**：草稿「挂载时建立一次、之后不跟 props 走」是刻意的（跟着走会在任何一次重读时冲掉用户正在敲的内容），所以这份差异由「当前生效」那一行负责显示。要让输入框也跟返回读数走，需要一个「这份读数是本次写入的结果」的信号——现有 props 无法把它与普通重渲染区分开，误判会在保存失败时清掉用户的草稿，因此保持现状并在 App 层用例里显式钉住该行为。
7. **界面没有视觉验证**：没起本地服务、没截图，样式只按既有主题变量书写；`AutomationAppConfigForm.css` 不在 eslint 作用域内（flat config 不覆盖 CSS），只经 tsc 与用例间接验证。
8. **仓库没有「所有 `t()` 中文串必须有英文词条」的守卫测试**：本批用一次性脚本核对过改动文件里的 712 条中文串（missing 0），但没有把它留成正式用例——`src/i18n.ts:44-47` 查不到词条就静默回退中文，缺词条不会报错。
9. **真机上「原生返回损坏读数时点保存」这条链路没走过**：界面的四态渲染由夹具驱动，桥侧行为按 `src/platform/types.ts` 的注释与夹具断言。
