# 旧 WebView 兼容：上游函数缺失怎么定位、怎么修

面向的故障形态只有一句用户描述：「**安卓 10 上打开对话，白屏 / 某个面板点开报
`xxx is not a function`**」。本文记录这条链路的判定口径、定位工具与修复分层，
对应的代码是 `HarnessPolyfill.kt`（注入 Harness 页面）、`src/legacyGlobals.ts`（应用外壳）、
`vite.config.ts` 的 `build.target`，工具是 `scripts/webview-compat-contract.mjs`（契约表）、
`scripts/webview-compat-scan.mjs`（扫描器）、`scripts/webview-compat.test.mjs`（契约测试）
与 `scripts/downlevel-harness-frontend.mjs`（语法降级）。

## 1. 先把问题切成三层，不要混着修

Harness 前端跑在**系统 WebView** 里，不是应用内置的 Node。两者版本无关：把运行时 Node 从
22 升到 24 不会给 WebView 补任何浏览器 API。旧内核上的故障因此分三层，处理方式完全不同：

| 层 | 表现 | 能不能补 | 修在哪 |
| --- | --- | --- | --- |
| 语法层 | 白屏，console 只有一条 `Uncaught SyntaxError`，页面一行 JS 都没跑 | **不能**（解析期失败，注入脚本自己都执行不到） | 构建目标 / 产物语法降级 |
| API 层 | 界面在，点某个入口报 `X is not a function` | 能 | document-start 注入 + 外壳垫片 |
| 样式层 | 界面在，只是外观退化（半透明变实色、抽屉不浮起） | 不必补 | 已有的 `@supports` 兜底 + `legacyWebView.ts` 的提示 |

判据只有一条：**看 `chrome://inspect` 里的第一条错误**。SyntaxError 属语法层；`is not a function`
/ `is not defined` 属 API 层；什么都没有、只是不好看，属样式层。

## 2. 怎么找「上游到底缺了什么」

### 2.1 三条互补的线索

1. **用户报错原文反推**（最快，但只有命中过的那几条）。已有的实例：
   - `AbortSignal.any is not a function`（Chrome < 124 的 WebView，选择工作区目录直接失败）；
   - `groups.toSorted is not a function`（ColorOS 13 自带 WebView，模型列表）；
   - `signal.throwIfAborted is not a function`（同一台机器，每次 RPC 返回后都调）；
   - `Promise.withResolvers is not a function`（荣耀浏览器 3.6.1 / Chromium 116，外置浏览器落到
     新建会话页——这条修在 `harness-web/dsh-mobile-session.js`）。
2. **静态扫描产物**（能提前发现，也能当门禁）。上游每次升级都可能引入新 API，源码里看不出来
   的东西在压缩产物里是确定的：
   ```sh
   node scripts/webview-compat-scan.mjs                          # 默认基线 chrome74
   node scripts/webview-compat-scan.mjs --baseline android10-stock --strict
   node scripts/webview-compat-scan.mjs --json
   # 也可以扫 rootfs 里的客户端插件产物（和 check-runtime-anchors.mjs 同一口径）
   node scripts/webview-compat-scan.mjs /path/to/runtime/node_modules
   ```
3. **真机 `chrome://inspect`**（唯一能证明「补丁真的生效」的手段）。补丁是字符串注入，
   JVM 单测只能钉语义，实际注入时机（document-start 是否可用、`onPageFinished` 兜底是否够早）
   只能在设备上看。

### 2.2 扫描器认什么、不认什么

- 认**调用形态**：`\.at\s*\(`、`Object\.hasOwn\s*\(`、`Promise\.allSettled\s*\(` 等，按命中次数
  汇总。它回答的是「这个产物用没用某个 API、用了几处」，**不判断是不是热点路径**。
- 不认「出现了同名字样」：SVG 的 `url(#id)`、CSS hex 颜色、正则字面量里的 `)?$` 都会被排除，
  `scripts/webview-compat.test.mjs` 用噪声样本钉住这一点。
- 语法层是**粗口径**：压缩代码里 `??`/`?.` 的片段可能落在字符串或正则里，数不干净。所以扫描器
  只输出「语法字样最高需求」，真正的「能不能跑」由产物语法降级脚本的**不动点**判定（见 3.3）。

### 2.3 实测数据（2026-11-22，官方 `@deepseek-ai/dsh-web-frontend@0.2.1-rc.1`）

`node scripts/webview-compat-scan.mjs --baseline android10-stock`：

| 产物 | 语法字样最高需求 | 基线之上的 API 缺口 |
| --- | --- | --- |
| 官方 dist（61 个 JS，4.58 MB） | **Chromium 94**（`class static {}` ×2） | hasOwn(93) ×6、at(92) ×28、replaceChildren(86) ×2、replaceAll(85) ×5、Blob.text(76) ×5、allSettled(76) ×3；WeakRef ×1 与 AggregateError ×1 补不了（见契约表 reason） |
| 应用外壳 dist（4 个 JS） | 降级前 Chromium 80（`?.`/`??`）→ 降级后 0 | hasOwn(93) ×3、replaceChildren(86) ×4、Blob.text(76) ×3、allSettled(76) ×3、randomUUID(92) ×1 |

两个结论直接决定了修法：

1. **语法层是硬边界**。官方产物要 Chromium 94 才解析得动，而 Android 10 出厂 WebView 是
   Chromium 74（`android10-stock` 别名）。这类机器上任何 polyfill 都没机会执行——必须先降级产物，
   或者明确不支持。
2. **API 层要按基线补一整套**。只补 `toSorted`/`withResolvers` 只覆盖了「语法够新的旧内核」
   （ColorOS 13、荣耀 116）；把基线压到 74 之后还要补 `Object.hasOwn`、`at`、`replaceAll`、
   `allSettled`、`Blob.text/arrayBuffer`、`replaceChildren`、`findLast/findLastIndex`、
   `AbortSignal.timeout`。

## 3. 怎么修

### 3.1 API 层：注入脚本（Harness 页面）

`HarnessPolyfill.SCRIPT` 是一段 **ES5** 立即执行函数，两条注入路径：

- WebView 支持 `DOCUMENT_START_SCRIPT`（AndroidX WebKit）→ `addDocumentStartJavaScript`，
  在主框架任何脚本之前生效；
- 不支持的 provider → `onPageFinished` 里 `evaluateJavascript` 兜底（目录选择、模型列表这类
  交互时才调的 API 仍然来得及）。

每条补丁的纪律：`typeof x !== 'function'` 守卫在前、`defineProperty` 在后；不覆盖、不代理、
不读页面状态；脚本里不出现 `window.`/`document.`（`HarnessPolyfillTest` 与
`scripts/webview-polyfill.test.mjs` 各钉一遍）。

### 3.2 API 层：外壳垫片（Capacitor 的 WebView）

`HarnessPolyfill` 只装在 Harness 页面上，对外壳无效。外壳自己走 `src/legacyGlobals.ts`，
在 `src/main.tsx` 里**第一条 import**：i18n 的 `t()` 第一条判断就是 `Object.hasOwn`，晚了就崩。
覆盖 `Object.hasOwn`、`Promise.allSettled`、`crypto.randomUUID`、`Element.replaceChildren`。

### 3.3 语法层：产物降级（派生产物，原件不动）

```sh
node scripts/downlevel-harness-frontend.mjs --check     # 只报告
node scripts/downlevel-harness-frontend.mjs             # → dist-legacy（chrome74）
node scripts/webview-compat-scan.mjs --baseline android10-stock dist-legacy
```

- 用 `vite` 的 `transformWithEsbuild` 逐文件 lowering，保持 ESM 与相对 specifier 不变，
  `index.html` 一个字节都不用改（文件名不变）。
- **判据是「不动点」**：esbuild 的 transform 幂等，反复降到某趟不再改动即认为降级到位。
  vendor 包实测要两趟（私有成员降级出的替身本身还会被下一趟改写）。
- 实测代价（0.2.1-rc.1）：61 个 JS 从 4.58 MB 到 5.01 MB（+444 KB），耗时约 1.3 s。
- `harness-web/scripts/build-official-frontend.mjs` 的「官方产物逐字节保留」契约不受影响：
  降级写的是另一份派生目录，官方 `dist` 只读。

### 3.4 语法层：外壳的构建目标

`vite.config.ts` 的 `build.target` 从 `es2022` 改成 **`chrome74`**。原来那个目标产出的 `?.`/`??`
在 Chromium 74 上是解析期 SyntaxError——外壳白屏，跟上游一点关系都没有。`cssTarget` 保持
`chrome100`：样式降级另有 `@supports` 通道，压到 74 只会让构建器去警告一堆已知项。

## 4. 决策点（还没定，需要产品口径）

1. **Chromium < 94 的机器到底支不支持**。降级脚本已把「支持」变成可执行选项，但要决定：
   是把 `dist-legacy` 接进打包（`scripts/rebuild-rootfs-frontend.py` 换成派生目录），
   还是对这类机器给一句明确的「请更新 WebView / 用外置浏览器打开」提示（当前是白屏，
   这是现状里最差的一种）。
2. **接进打包的验证面**：降级后必须在真机跑三条主链路（启动 Harness、会话切换、
   选择工作区目录），因为 esbuild 的 lowering 会重写类私有成员/静态块，行为等价但值得实测。
3. **`AGENTS.md` 的发布口径与历史口径**：`docs/releases/发布说明-0.3.9.md` 的「已知限制」写的是
   「上游前端仍用到 `Object.hasOwn`、`.at()` 等 ES2022 级 API，属于当前构建基线
   （`target: es2022`）；低于该基线的 WebView 不在支持范围」。这句话描述的是 0.3.9 当时的实现，
   **现在已不成立**：API 层已补到 Chromium 74，外壳语法目标也降到 74。真正的边界只剩
   「官方产物的语法层」（Chromium 94，需降级）。发下一个版本时要在新发布说明里改写这条，
   并把「Android 10 出厂 WebView」与 `minSdk`（26）的关系说清——两者不是一回事。
4. **`webview-android10` 作业需要首次真跑校准**。它本机跑不起来（没有 KVM / 模拟器），
   代码只验证到「仪器用例能编译 + 夹具能同步、能加载」。第一次 `workflow_dispatch` 时
   要盯三处：模拟器是否分到 KVM、`connectedDebugAndroidTest` 是否真的只跑这一个类、
   失败截图/日志是否上传。校准通过后再考虑把它提升成发版流水线的硬依赖。

## 5. 门禁与回归

### 5.1 CI 里的两层（`.github/workflows/android-build.yml`）

| 层 | 挂在哪个作业 | 跑什么 | 代价 | 能挡住什么 |
| --- | --- | --- | --- | --- |
| 静态口径 | `checks`（每次 push / PR） | `pnpm --dir harness-web run build` → 扫描器 `--strict` → 降级脚本 → 再扫降级产物 | 约 5 秒 | 上游新版本开始调用新 API、或产物语法下限抬高（新内容合进来就红，等不到发版） |
| 真机口径 | `webview-android10`（`workflow_dispatch` 且勾选 `webview_smoke`） | API 29 模拟器（镜像自带 Chromium 74）跑 `LegacyWebViewCompatTest`：把官方产物加载进系统 WebView，断言没有 `SyntaxError` / `is not a function`、`#root` 真的渲染、注入脚本承诺的 API 都在 | 约 15 分钟 | 静态扫描看不见的东西：注入时机（document-start 是否可用）、模块脚本在该内核上的实际解析、WebView 特有的实现差异 |

静态那层是**默认门禁**，真机那层是**发版前的确认**（模拟器冷启动 + NDK 太重，不该挂在每次推送里）。
两层都不是「跑通一次就完事」：上游每升一次 `@deepseek-ai/dsh-web-frontend`，静态层会重新数一遍调用点，
契约表里没有对应守卫的条目会让 `--strict` 退出 1。

冒烟只跑兼容，不产出任何发布物：`build`（APK）与 `build-rootfs`（ARM64 运行时）在
`inputs.webview_smoke == true` 时被作业级 `if` 跳过，`publish-release` 也追加了同一条守卫。
没有这层守卫时，「只想在安卓 10 上过一遍」会顺带跑 20 多分钟的产物构建并**发出一个预览版**。

```sh
# 触发一次安卓 10 冒烟（只跑 checks / gates / webview-android10）
gh workflow run android-build.yml -f webview_smoke=true
```

仪器测试的夹具由 `android/app/build.gradle` 的 `syncHarnessWebFixture` 任务同步（`harness-web/dist`
→ `build/generated/androidTestAssets/harnessWeb/harness`），用 `WebViewAssetLoader` 以
`https://appassets.androidplatform.net/assets/harness/index.html` 加载：模块脚本需要可信源，
`file://` 下 `import` 与 `crypto` 都不成立。产物没构建时用例 `assume` 跳过并打印原因，
不会把「没夹具」算成「兼容性通过」。

### 5.2 本机命令

```sh
node --test scripts/webview-compat.test.mjs     # 契约表 + 扫描器 + 降级脚本
node --test scripts/webview-polyfill.test.mjs   # 注入脚本在 vm 里的语义（含缺 API 的假内核）
node scripts/webview-compat-scan.mjs --baseline android10-stock --strict   # 未覆盖缺口 → 退出 1
node node_modules/vitest/vitest.mjs run src/legacyGlobals.test.ts
cd android && ./gradlew :app:testDebugUnitTest --tests "*HarnessPolyfillTest*"
cd android && ./gradlew :app:compileDebugAndroidTestKotlin   # 仪器用例能否编译
```

`--strict` 只对「本该补却没补」的项失败。`polyfillable: false` 的条目（WeakRef、
structuredClone、Promise.any/AggregateError、Intl.Segmenter 等）必须在契约表里写 `reason`，
它们出现在报告里但不会让门禁常红——否则真出问题那天，红的那条早被当噪声了。

## 6. 加一条新补丁的正确顺序

1. 跑扫描器，拿到「用了、需要 Chromium N、未补」的行；
2. 在 `scripts/webview-compat-contract.mjs` 的 `WEB_API_FEATURES` 里补/改条目（含 `usage`
   样例与 `guard`/`shellGuard` 守卫字符串）；
3. 在 `HarnessPolyfill.SCRIPT`（或 `src/legacyGlobals.ts`）里加守卫式补丁，**ES5**、不碰
   `window.`/`document.`；
4. `scripts/webview-polyfill.test.mjs` 里加「缺 API 的假内核」语义用例（用
   `delete Object.hasOwn` 这类做法造旧内核，不要手写假实现）；
5. `HarnessPolyfillTest.kt` 里钉「守卫在赋值之前」的邻近性；
6. 跑第 5 节全部门禁；语法层的改动另跑降级脚本 + 真机验证。
