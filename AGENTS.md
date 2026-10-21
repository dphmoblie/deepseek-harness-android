# Agent 须知：这些取舍是刻意的，不要当缺陷改回去

本文件记录**看起来像漏洞、其实是有意为之**的实现决策。后续 agent 在 review、重构或"修 bug"时，
请先读完本文再动手：下面每一条都曾经是真实缺陷的修法，改回去会重新引入问题。

Git 提交/发布规范见 [`.agents/rules/git-workflow.md`](.agents/rules/git-workflow.md)（中文、约定式提交、
发布说明写在 `docs/发布说明-<版本>.md`）。

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
