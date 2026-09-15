# 上游 issue 草案：dsh 0.1.5-rc.2 把显式 `undefined` 的工具入参判为非法

**状态：草案，尚未提交。** 内容来自一次真机实测（见本仓库 `docs/真机缺陷与改进清单.md` 的 P1-1），
提交前请复核报错文案的逐字符原文与 dsh 版本号。

## 一、标题建议

* 英文（建议按此提交）：
  `[0.1.5-rc.2] Explicit undefined tool argument is rejected as "binding arguments must be lossless JSON" while omitting the key works`
* 中文备选：
  `显式传入 undefined 的工具参数被判为非法（binding arguments must be lossless JSON），省略同一键则正常`

## 二、环境

| 项 | 取值 |
|---|---|
| dsh | `@deepseek-ai/dsh` 0.1.5-rc.2 |
| 运行位置 | Android 16（HONOR）设备上的 **PRoot 访客**（不是桌面 Linux，也不是容器） |
| 访客系统 | Ubuntu 24.04 ARM64 |
| Node | 24.19 |
| 内核 | 6.12.38-android16 aarch64；访客经 PRoot 运行（`TracerPid` 非 0） |

本节只列实测到的取值。我们没有在其它 dsh 版本、其它架构或其它宿主上验证过这条现象，
因此不对那些组合下结论。

## 三、复现步骤与现象（4/4 复现）

复现率：**4/4**——两种工具各两次，全部命中；同一调用**省略该键**则正常。

1. `tools.bash({ ..., timeoutMs: undefined })` → 调用失败，报 `binding arguments must be lossless JSON`
2. `tools.read({ ..., offset: undefined })` → 同一句报错
3. 同一调用省略该键（不入参对象里放这个键）→ 正常执行

（上面只写出出问题的那个键，其余参数按各自 schema 正常给出，此处以省略号代替。）

要点：

* 调用侧是**显式设置**了该键、取值为 `undefined`，而不是省略该键；
* 报错文案**只有一句**，既不指工具，也不指参数名，因此定位只能靠逐键二分；
* 两个**不同**工具（`bash` 与 `read`）都能复现，说明问题不在某个工具的实现里，
  而在它们共用的**入参绑定校验**这一步。

## 四、期望行为

1. **显式 `undefined` 视同省略。** 入参对象里取值为 `undefined` 的键，应在 lossless 校验之前
   被丢弃（或按缺省处理），而不是判为「不可无损序列化」的值。
2. **报错文案应指明是哪个参数。** 至少给出工具名与键名，例如
   `binding arguments must be lossless JSON: <tool>.<key>`；只报一句通用文案时，
   调用方无法判断是哪一个键越界。
3. 如果确实是刻意拒绝「显式 `undefined`」这种写法，也请在 schema 层或文档里说明，
   而不是让同一个 schema 下「省略」与「显式 `undefined`」行为不一致——那会逼所有调用方
   小心翼翼地避免写 `undefined`，而这类值在 JS 里很容易因为「统一构造参数对象」而出现。

## 五、最小修法建议

我们没有可引用的 dsh 内部代码（未核实具体文件与行号），因此只描述**语义等价**的最小改动：

在真正做「lossless JSON」判定的那一步**之前**，剥离取值为 `undefined` 的键：

```js
const bound = Object.fromEntries(
  Object.entries(args).filter(([, value]) => value !== undefined),
)
// 之后照旧对 bound 做 lossless 校验
```

同时把被拒绝时的抛错改成带上工具名与键名（第 4 节第 2 条）。
剥离之后再校验，即可让「显式 `undefined`」与「省略该键」完全一致，不改变其余校验强度。

## 六、这条与「`bash` 工具在无沙箱后端设备上的报错」是两回事

同一台设备上还有一条**独立**现象：本机 Android 内核没有可用的沙箱后端（自检实测
`sandbox_probe=PROBE_UNUSABLE` / `sandbox_exec=EXEC_LAUNCHER_FAILED`），任何 `workspace-write`
命令直接失败，报 `sandbox mode "workspace-write" is requested but no sandbox backend is usable
on this host ...`（真机证据与归属判定见本仓库 `docs/真机缺陷与改进清单.md` 的 P0-3）。

两者的区别：

| | 显式 `undefined`（本 issue） | 无沙箱后端 |
|---|---|---|
| 报错文案 | `binding arguments must be lossless JSON` | `sandbox mode "workspace-write" is requested but no sandbox backend is usable on this host ...` |
| 报错含义 | 只提到参数绑定，整条报错里**没有任何沙箱字样** | 明确指向沙箱后端可用性 |
| 我们的复现证据 | 同一台设备上 `read` 也照样复现（真机报告据此把本条判为「工具入参的 JSON 绑定校验」问题） | 只在 `workspace-write` 下出现；用户切到 `danger-full-access` 后不再出现（真机报告 P0-3） |

因此**请不要把两条合并成一个 issue**：合并会掩盖「显式 `undefined` 与沙箱后端可用性无关」
这一点，而两条的修法也完全不同（前者是绑定前丢弃 `undefined`，后者是降级、前置报错或换后端）。

## 七、影响

* 任何「按 schema 把所有可选项都列出来、可选值填空」的调用方，会整体失败，
  且失败原因与工具本身无关；
* 因为报错不指名参数，用户与模型只能靠试错定位，排查成本高；
* 失败发生在调用之前，所以不会有副作用——但也没有任何可用的提示说明该改哪里。

## 八、我们这边的环境事实（供对照，非结论）

* 真机报告全文、逐条归属判定与其它上游问题：本仓库 `docs/真机缺陷与改进清单.md`
  （本条为 P1-1，邻条 P0-3 为上面提到的沙箱问题，P3-1 为 `interrupt_agent` 的存在性校验）。
* 本草案**不包含**我们未实测过的环境结论：没有验证过的 dsh 版本、架构、宿主形态一律未写。
