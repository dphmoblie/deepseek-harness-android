# Agent 须知：这些取舍是刻意的，不要当缺陷改回去

本文件记录**看起来像漏洞、其实是有意为之**的实现决策。后续 agent 在 review、重构或"修 bug"时，
请先读完本文再动手：下面每一条都曾经是真实缺陷的修法，改回去会重新引入问题。

Git 提交/发布规范见 [`.agents/rules/git-workflow.md`](.agents/rules/git-workflow.md)（中文、约定式提交、
发布说明写在 `docs/releases/发布说明-<版本>.md`）。文档放置规范见 [`.agents/rules/docs-layout.md`](.agents/rules/docs-layout.md)：唯一文档根是 `docs/`，根目录只留多语言 README、`AGENTS.md`、`LICENSE` 与 `THIRD_PARTY_NOTICES.md`。

## 1. 不要恢复「打开应用自动进入 Harness」

- `RuntimeSettings` 里的 `autoLaunch` 字段**已整体移除**（前端 types/validation/browser、原生
  RuntimeModels/RuntimeStore/MobileRuntimePlugin 都没有它了）。入口只认用户点击。
- `openHarness` 里的运行期守卫不要删：只有非空字符串才当 `sessionId`，其余一律 `undefined`。
  按钮曾经写成 `onClick={onLaunch}`，React 合成事件被当成 `sessionId` 传给 Capacitor，
  序列化抛 `Converting circular structure to JSON`，promise 永不 settle，界面一直转圈到看门狗超时。
- 首页接线必须是箭头函数（`onLaunch={() => launchHarness()}`），不要改回 `onLaunch={launchHarness}`。

## 2. 启动器首页空闲态**不要**写「正在打开 Harness 对话」

自动启动移除后，开机停在首页是常态。文案按状态区分，只有真的在打开/停止时才用进行时：

| 状态 | 标题 | 卡片标题 | 说明 |
|---|---|---|---|
| 空闲·已就绪 | 对话已就绪 | 点击「打开对话」开始 | 打开对话时才会启动本机服务。 |
| 空闲·服务已在运行 | 对话已就绪 | 点击「打开对话」进入对话 | 本机服务正在运行，打开后即可继续对话。 |
| 打开中（`busy === 'launch'`） | 正在进入对话 | 正在打开 Harness 对话 | 正在启动本机服务并进入对话… |
| 停止中（`busy === 'stop'`） | 正在停止运行环境 | 正在停止本机服务 | 停止完成后即可重新打开对话。 |

用户报过的原话就是"启动器首页正在打开 harness 的表述不恰当"——空闲态写进行时会让人以为卡在启动流程。
`src/App.test.tsx` 里有四条用例钉住这四种状态，别为了让文案"简洁"而合回一套。

## 3. 不要给每次启动加回 PRoot `dsh --version` 自检

`RuntimeLaunchResolver.verifyGuest` 在「启动档来自缓存 + 已证明完整启动成功」时会**跳过探测**，
这是启动提速最大的一块（一次 PRoot+node 冷启真机约 1~3 秒）。

- 缓存只在 `RuntimeSupervisor.startHarness` 走到 RUNNING 之后写入（`markGuestVerified`）；
  **探测通过不算**——探测只跑一条命令，证明不了 node/dsh/端口/鉴权入口都能起来。
- 失效口径不要删：任何启动失败（含非受控异常）、安装/更新/同版本重装/版本切换/清空/中断恢复
  （`RuntimeStore.updateInstalledManifest` / `invalidateInstalledManifest`）都会失效；令牌缺失、
  类型损坏、旧前缀一律按未验证处理，照旧探测。
- **失效标记必须是进程级的**（伴生对象上的 `@Volatile`），不能退回实例字段：调用方会按需现造实例
  （`RuntimeStore.invalidateInstalledManifest` 里 `from(context).invalidate()`），实例字段的失效
  会随那个临时对象一起丢掉，而真正决定跳不跳过自检的是 `RuntimeLaunchResolver` 持有的另一个实例。
  磁盘删除失败时，靠这个进程级标记兜住「继续信任旧令牌」的最坏情况。
- 跳过时仍会每次校验 `RootfsIntegrity.verifyLinks`（链接类损坏）与 `prepareLaunchFiles`
  的 loader 实测（运行器类损坏）。只有"仅影响 `dsh --version`"的损坏会推迟到拉起阶段报错，
  且失败会立即失效缓存，用户重试一次就拿到精确错误码。

## 4. 不要回滚启动等待参数

- `HarnessStartupTimingPolicy.STABILITY_MS = 200`（原 600）：那 600ms 是拿到 401 之后的纯等待，
  真正决定耗时的是 dsh 打印鉴权入口的时刻。更晚的退出由 `watchHarnessProcess` 收尾
  （阶段回落 + 通知 + 诊断），不是静默。
- 轮询自适应：启动前 2.5 秒 100ms，之后 200ms。按"启动已过时长"切换而不是按次数。
- `RuntimePreparationReuse`：同一次启动里 `verifyGuest` 与 `launch` 各准备一遍运行时是无意义重复
  （DNS/hosts 写入带 fsync、沙箱脚本、loader 实测）。去重只在启动事务内、按运行时指纹；
  事务外（终端/插件管理/自检）仍然每次重做。

## 5. 预启动开关 `prewarmHarness` 的"多余逻辑"都是必要的

默认关闭。打开后**只调 `startHarness`，绝不调 `openHarness`**（不替用户跳页面）。以下逻辑不要简化：

1. **触发口径是「设置由关变开」，而且要区分相位**（`prewarmSettingSeen`）：
   - `phase === 'running'` 时**当场消费**这次变更。这是「用户手动停止后不被重新拉起」的守卫：
     开机那一次判定看到 running，若不消费，用户点「停止」把相位带回 ready 时，这次由关变开会被当成
     "条件尚未成熟、留着待触发"，刚被用户关掉的服务立刻又被拉起。
   - 其余未就绪条件（下载解压 / 停止中 / 有待更新 / 还没配模型凭据）**不要提前消费**，留到条件
     成熟的那次渲染再补做；提前消费会让用户"开了开关却要等下次冷启动"，并且全程没有任何提示。
   - 关掉开关要**当场复位** `seen`，否则「关掉再打开」会被当成已经触发过而静默跳过。
   - 总之：不要把它简化成「只看相位」或「只看设置」——两个维度都要。
2. **`prewarmInFlight` 落定即清空**。留着已完成的 promise，之后"先停止、再打开"会拿到过期的
   running 快照，于是跳过 `startHarness` 直接打开，用户收到 `NOT_RUNNING` 错误。
3. **用户中途点「打开对话」时等待在飞的预启动**（带 `HARNESS_START_TIMEOUT_MS` 上限），
   不要并发再发一次 `startHarness`：原生对重复启动会立刻回报"还没运行"，紧接着的打开就失败。
4. 只在 `phase === 'ready'` 时真正发起预启动（不是 `installed`）：避免与运行中/停止中/下载解压竞态。
5. 保存语义照 `overlayBallEnabled`：**省略 = 不改**。否则一次只带部分字段的保存会把用户已开的预启动悄悄关掉。
   缺键读取按 `false`，老用户升级后行为不变。

## 6. 不要删打包脚本里的目录排序

`scripts/build-embedded-runtime.py` 的 `add_windows_tree` 里对 `directory_names` / `file_names`
排序是有意的：`scandir` 顺序由文件系统决定（ext4 是哈希序），同一 inode 的多个硬链接里
"谁先被看到"决定谁落成实体条目。CI 上因此出现过 `git-add` 抢到实体、`git-core/git` 变硬链接，
`Runtime packaging self-check` 随机失败（同一提交两次运行结论不同）。
排序同时让同一份输入的 bundle 字节序稳定（便于比对 sha256）。

## 7. 不要放宽 `mobile-compat-contract` 里的 stopRuntime 断言

`scripts/mobile-compat-contract.test.mjs` 要求 `stopRuntime` 在 `harnessStartGeneration.incrementAndGet()`
之前先 `harnessOpenRequests.invalidate()`。这不是格式洁癖：不失效在途的打开请求时，用户点"停止"后
排队的打开请求仍会开一层 HarnessActivity，出现"停掉了却冒出对话页"。别把它改成可选匹配。

## 8. 诊断字段 `elapsed_ms` 是启动耗时的唯一客观口径

`MobileRuntimePlugin.startHarness` 在 `HARNESS_START` 事件里记 `elapsed_ms`（成功与失败都记）。
没有它，"启动变快了"只能靠体感，无法与"这次网络好"区分。字段在白名单内（纯数字，无路径/凭据），
不要因为"多一个字段"删掉。导出日志里形如：

```
2026-10-20T01:00:00Z|INFO|HARNESS_START|result=ok|phase=running|elapsed_ms=1834
```

## 9. 桥接线程不要阻塞

`openHarness`、`startHarness` 必须走 `execute(call)`（后台线程池）而不是 `resolveWhileActive`
（在 Capacitor 串行桥线程上同步执行）。获取运行时凭据要等生命周期锁，阻塞桥线程会让**所有**桥调用排队。

## 10. 发布版不要打开 R8 混淆

`android/app/build.gradle` 的 `release` 是 `minifyEnabled false` + `shrinkResources false`，
原因写在 `docs/android.md` 的「构建策略：发布版不混淆代码」里，别只看着两行就当成遗留配置：

- 体积 98% 是 rootfs，R8 省下的几百 KB 没有实际意义；
- Shizuku 的 UserService（按类名起服务）、Capacitor 插件注册、`FileProvider` 的 authority
  解析都按名字工作，混淆后只会表现为「连接超时」「文件操作失败」这类误导性报错；
- `shrinkResources false` **不等于**资源名没被改：AGP 仍会把 `res/xml/network_security_config.xml`
  改成 `res/8G.xml`，那个开关是 `android.enableResourceOptimizations`，别拿前者推断后者。

要打开混淆必须同时补保留规则、做真机验证（启动/Shizuku/分享打开文件/插件管理），并回写本条。

## 11. 长操作进行中，启动/打开必须快速失败，不能排队

安装、修复、Harness 版本的 npm 安装、插件安装、版本切换、运行库下载与重置都会长时间持有
`MobileRuntimeController.lifecycleLock`（Harness npm 安装上限 20 分钟）。启动请求若排在锁后，
界面会停在「正在进入对话」，安装结束还会替用户把 Harness 拉起来——比直接报错更糟。

- `RuntimeOperationGate` 与两把包装是刻意设计：`withLongOperationLock` **先**
  `operations.begin()` 再拿锁；`withStartOperationLock` 通过 `operations.withStartLock`
  在拿锁前后检查长操作，按 100ms 一片等锁、每片之间重查闸门（有长操作登记即快速失败）。不能退回「先检查再无限等锁」，否则两步之间
  安装抢到锁时，启动仍会排队。也不能退回「100ms 拿不到锁就报忙」：开机时状态读取、设置保存等
  普通短持锁者会被误报成「Harness 版本操作正在进行」（用户报过的真实 bug）；无长操作时等待以 15 秒封顶。
- 新增长操作必须走 `withLongOperationLock`。纯读取列表/检查传 `enabled=false`；Harness
  版本列表会探测访客环境、刷新脚本和恢复未完成的切换，因此也必须登记长操作。
- 拒绝用独立受控码 `RUNTIME_OPERATION_BUSY`，不要复用 `RUNTIME_BUSY`：后者描述的是
  「Harness/终端还在跑，先停掉」，这里描述的是「安装类操作还在跑，等它完成」。
- 前端 `harnessVersionBusy` 只是提前禁用与文案；原生闸门才是兜底，两者都要保留。
- `harnessVersionBusy` 期间首页禁用启动、环境页隐藏「保留数据修复」：npm 安装时
  `runtime.phase` 仍是 `ready`，但它不是可操作的空闲态。
- 回归入口：
  `android/app/src/test/.../RuntimeOperationGateTest.kt`、
  `RuntimeOperationGateContractTest.kt` 与 `src/App.versions.test.tsx` 的
  「Harness 版本安装期间不能排队启动」用例。

## 12. 根通道分级放宽、dsh 官方审批与 App 警告确认页不要退回硬拦截或单布尔

2026-11-22 起（承接 2026-11-08 的 `off`/`read_write` + `strict`/`maximum` 双层结构），根通道静态围栏由「直接硬拦截」演进为**四级风险分类（`RootCommandGuard.classify` 唯一真源）× 双层用户授权**：

- **Tier 0（`LOW`）**：常规只读诊断、`/data/local/tmp`、`/data/adb/modules/<id>` 与 `/dev/null` 重定向直接放行。
- **Tier 1（`ELEVATED`）**：原 `STRICT_RULES`（`mount`/`dd`/`insmod`/`setprop`/写 `/proc/sys` 与非空 `/dev/*`）及动态脚本（`$(...)`/反引号/`sh -c`/`eval`/`source`）。
  - `maximum` 档直接放行；
  - `strict` 档走 **dsh 官方审批**（`ctx.approval.request()`，`displayReason` 展示命令预览，`reason` 仅记脱敏分级以保护会话日志）；
  - 当 dsh 会话处于 `danger-full-access`（`approval.effectivePolicy === 'never'`）时**尊重 dsh 授权**直接凭 `challengeId` 放行，切勿盲目调用 `ctx.approval.request()`（dsh 对 `never` 会直接静默返回 `rejected`）。
- **Tier 2（`HIGH`）**：电源（`reboot`/`poweroff`/`svc power`）、关 SELinux、停关键系统服务、核心分区写入、嵌套 `su -c`/`adb shell`。不再硬拦截：dsh 处于 `ask` 时先走官方审批，最终执行**强制经 Android 宿主 `RootApprovalCoordinator` 打开 `RootCommandApprovalActivity`（应用警告确认页）**。
- **Tier 3（`CRITICAL`）**：bootloader/recovery/fastboot、`/dev/block` 读写、`rm -rf /data` 等数据销毁、`/dev/mem`、`/proc/kcore`。**不再硬拦截**，但**必须打开应用极危警告页**展示完整脚本、命中规则、强制勾选确认框与系统身份确认（生物识别/锁屏凭据；无锁屏设备降级二次弹窗）。
- **不可被访客绕过**：PRoot 单 UID 访客不可自报批准绕过 Tier 2/3；`RootApprovalRegistry.consumeDshApproval` 仅允许兑换 Tier 1，且 `RootExecutionGrant` 构造器为 `internal`，只有 `RootApprovalCoordinator` 在原生确认页结单后才能为 Tier 2/3 签发凭证。
- 危险命令仍按**命令位置 + 参数路径**匹配（`echo "run mount now"`、`echo reboot`、`2>/dev/null` 放行，`normalize()` 把换行变 `;` 防止多行漏判）。
- 设置页仍保留快捷手动电源按钮（`MobileRuntimePlugin.requestDevicePowerAction` 经系统身份确认执行编译期常量 `reboot` / `reboot -p`）；AI 发起的电源脚本一律走 `shell` → `HOST_CONFIRM` → `RootCommandApprovalActivity`。
- 实测设备上沙箱降级（`PROPAGATION_FAILED` + `BLOCK_MASK_SKIPPED` + `HARDENING_UNAVAILABLE`）必须如实显示，不能把 `maximum` 说成「已隔离」。
- 不要把 0.3.9 报告里的 `ls /data/data` / `pm list packages` 等隐私词表加回护栏。
- 回归入口：`RootSecurityTest.kt` 的四级分类、`RootApprovalRegistry`、`RootApprovalCoordinator`、`RootCommandRunner.runApproved` 与 `DeviceBridgeServer` 端到端用例；`scripts/mobile-device-tools.test.mjs` 的 dsh 审批与 `danger-full-access` 用例；`RootEngineCard.test.tsx`。

## 13. 认证预载必须随 APK 刷新到已安装运行时

`scripts/mobile-auth-preload.cjs` 是**唯一源文件**；构建时由
`generateAuthPreloadAsset` 生成 `assets/support/mobile-auth-preload.cjs`，每次启动事务里再由
`MobileAuthPreload.install` 原子写入当前运行时的 `/usr/local/lib/dsh-mobile-auth.cjs`。

这不是多余的重复：运行时可以独立于 APK 安装、切换和回退，只更新 APK 里的预载不会影响设备上
已经解压的旧 rootfs。而旧预载把 dsh 令牌的发现押在 stdout 截获上，插件日志先打印一个带
`token=` 的地址就会猜错，外置浏览器随即落到 dsh 的 401 页。不要在 assets 里手抄第二份预载，
也不要删掉启动事务里的刷新；二者任一缺失，都会让旧运行时继续跑旧鉴权逻辑。

回归入口：`MobileAuthPreloadTest.kt`、`scripts/mobile-compat-contract.test.mjs` 的
「installed runtimes receive the APK auth preload before every launch」。

## 14. 语言白名单与词典键的三处同步（新增语言时）

界面语言现在有五个：`zh-CN`（源语言）/ `zh-TW` / `en` / `ja` / `ko`。新增或调整时，
以下三处 + 两处资源必须同时改，少一处就是「切了语言但某些屏幕还是中文」：

1. `src/i18n.ts` 的 `LANGUAGES`（唯一白名单，`isLanguage` 由它派生）；
2. `Android AppLanguage.SUPPORTED`（原生偏好、通知、悬浮球都走它）；
3. `res/values-<语言>/strings.xml`（`scripts/check-android-resources.py` 会挡缺键）；
4. `src/locales/<语言>.ts` —— **键必须是 src 里的简体原文**（`t()` 用它查表），
   不是翻译后的文字；`src/i18n.keys.test.ts` 校验「键都来自 en.ts」与九成覆盖率；
5. `src/downloadTelemetry.ts` 的 `DURATION_UNITS` 之类按语言分支的格式化表。

不允许把这五个值散落在新的 `=== 'en'` 判断里：`Language` 是联合类型，
`LANGUAGES` 是运行时白名单，两处任一漏改都会退化成「非法值当没选过」。
Harness 运行时本身只认 `zh`/`en`，`RuntimeStore.syncHarnessLocale` 集中做映射
（繁→zh，日/韩→en），不要在别处再写一份。

## 15. 硬链接自检项**不要**加回来

访客脚本的 `checkHardlink`、结论码 `HARDLINK_DENIED`、标签「硬链接（原子写入的前提）」已于
2026-11-22 **整体移除**。自检载荷现在是 **17 项 = 访客脚本 13 项（10 项链路 + 3 项编译能力）+ 宿主 4 项**，
契约顺序里 `attachments` 之后直接是 `rg`（`rg` 仍在索引 9）。

- **为什么移除**：P0-1（`write` 新建文件）与 P0-2（附件落盘）两条路径都已经在 pnpm 补丁里回退到
  `rename`（见 [`docs/design/运行时补丁与dsh写入回退.md`](docs/design/运行时补丁与dsh写入回退.md)），
  访客内 `link(2)` 被拒**不再等于写入失败**。探针于是不再指示任何真实故障，却会在每台 PRoot 访客上
  每次都报一条 `fail`，把自检面板的四条红项里固定占掉一条。
- 用户已明确决定**不为硬链接重做 root（运行时根）适配**，所以「补硬链接支持让探针变绿」不是待办；
  把探针加回来等于把一条已知无动作项的告警重新塞给所有用户。
- 这是**契约变更**，四处必须同时改：访客脚本 `runtime-self-check.cjs`、
  `RuntimeSelfCheckPolicy`（id 白名单 + 码表）、前端 `src/runtimeSelfCheck.ts`（id/码/标签/文案）、
  `scripts/runtime-self-check.test.mjs` 的逐字 ID 列表；测试里另有 `take(13)` 与 17 项断言钉着。
- 兼容口径：旧载荷里若仍带 `hardlink`，Kotlin `sanitize` 丢弃该条（不编造结论），
  前端 `validateSelfCheckReport` 对不认识的 id 直接拒收整份载荷（报「自检项标识无效」）——
  因此**不要**只把 id 加回白名单、脚本却不产出：两边必须一致。
- 代价要说清：设备上 `write` 新建文件或图片附件落盘失败时，自检不再单独指认「可能是硬链接被拒」。
  此时看 dsh 报错原文（`EACCES: permission denied, link ...`）与上面那份回退文档。

## 16. 应用内配色只看外壳的 surface，不要交回页面的 `prefers-color-scheme`

同步过主题的 DSH 页面，文字与底色必须由**同一个开关**决定。2026-11-24 的修法（用户报
「应用白字 / 通知黑字」与「黑字压深底」）就是这条，别把判据改回媒体查询：

- 判据是 `HarnessThemeBridge.pagePalette(surface)`：surface 的 WCAG 相对亮度 `> 0.179`
  用浅色调色板（黑字），否则深色调色板（白字），与 `src/theme.ts` 的 `--on-blue` 同一口径。
  写 `data-dsh-theme-resolved` + `--dsh-theme-label-*`；`android.css` 用
  `html[data-dsh-theme-source='shell'][data-dsh-theme-resolved] body`（0,2,2）把官方
  `--dsw-alias-label-*` 接到那三个变量上，压过 `body[data-ds-dark-theme]`（0,1,1）。
  **不要**改官方两套根值（外置浏览器没有外壳同步，会跟着错乱）。
- `system` 模式在桥里也**不查系统**：同步过来的 surface 是外壳在它自己解析出的明暗下算好的；
  WebView 的 `prefers-color-scheme` 跟的是宿主 Activity 的 uiMode（本应用允许外观与系统不同），
  拿它判深浅必然与底色分家。
- 注入脚本会把 `body[data-ds-dark-theme]` 与 html 的内联 `color-scheme` **对齐**到那个结论。
  这是仲裁，不是第二个写入者：值一致就不回写，不会与页面的 `apply` 形成环。删掉它，页面
  运行期 apply 一次就翻回旧排版（边框、代码块、提示气泡全挂在同一个属性上）。
- `android.css` 顶层**不许**出现 `!important`（子模块测试逐条扫）：它对付不了内联样式，
  真正的解法是上面的内联对齐；强制声明只属于窄屏/粗指针查询。
- 注入时机分两层：Activity 里 `installDocumentStart`（document-start，首帧就对）+
  `onPageFinished` 的 `apply()` 兜底；脚本自己等 body（短轮询 + DOMContentLoaded/load +
  documentElement 的 childList/样式 observer），body 被重建也会补上。脚本里带的是**当前**
  主题常量，所以值变了必须用 `replaceDocumentStart` 换注册；取消同步要撤注册并跑
  `RESET_SCRIPT`（从没同步过的页面上它必须零副作用）。注册只归 `attached` 那块页面，
  浮层小窗的 `apply()` 不能把注册抢走。
- 通知的**文字**颜色由 SystemUI 按自己的深色模式绘制，只对齐强调色（`setColor`），
  不要给通知写死 `#FFF`/`#000`。
- 回归入口：`android/.../HarnessThemeBridgeTest.kt`、
  `scripts/android-theme-contract.test.mjs`（两端令牌/色值契约 + jsdom 里跑真实注入脚本）。

## 改动前先跑这些

本机 pnpm 不可用时用 node 直调（PATH 前置 `node` 目录）：

```
node node_modules/typescript/bin/tsc -b
node node_modules/eslint/bin/eslint.js . --max-warnings 0
node node_modules/vitest/vitest.mjs run --reporter=basic
node --test scripts/*.test.mjs
node scripts/gate-check.mjs
cd android && ./gradlew :app:compileDebugUnitTestKotlin :app:testDebugUnitTest
```

若改动涉及启动链路，还应在真机上看 `elapsed_ms` 的实际数值，而不是只看测试绿。
