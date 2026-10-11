# Release checklist

Generated rootfs and native ELF files are intentionally excluded from Git.
The official `0.2.13` workflow publishes a self-contained APK plus matching
runtime assets for inspection and explicit remote installation. Complete every
applicable item below for the exact APK, manifest, and rootfs before publishing
or otherwise distributing them.

## Runtime artifacts

- Build the release image from Ubuntu 24.04 ARM64, Node.js 24.19.0, and
  `@deepseek-ai/dsh` 0.2.0-rc.2. Record and independently verify the SHA-256
  of every source archive and package used by
  `scripts/build-embedded-runtime.py`.
  （`scripts/build-embedded-runtime.py:947-948` 的 `--node-version` 默认值是 rootfs 内的
  Node 版本；它与 CI 里跑构建/测试的 action Node 24.14.0 是两个不同的东西，不要混用。）
- Verify `/usr/local/bin/dsh`, the ARM64 native Node modules, the runtime
  metadata, and the guest file permissions in the finished rootfs.
- Verify the network tool component group in the finished rootfs: `git`, `curl`,
  and `/usr/lib/git-core/git-remote-https` exist as non-empty mode `0755` ARM64
  ELF files, every regular file under `/usr/lib/git-core/` is `0755`, and
  `/etc/ssl/certs` carries at least 64 CA files (`.pem`, `<hash>.0`, or
  `ca-certificates.crt`). `scripts/verify-bundle.py` enforces these checks; do
  not pass `--without-network-tools` for a published release, and do not accept
  a build whose CA directory is empty or holds only the concatenated bundle.
  Confirm the recorded `trimmed sourcemaps: files=… bytes=…` build-log line and
  treat a large residual `SOURCEMAPS=` count as an unexplained size increase.
- Retain Ubuntu package copyright metadata, the Node.js license and bundled
  dependency notices, the DeepSeek Harness MIT license, and the licenses of
  every npm package copied into the rootfs.
- Confirm the workflow tag is exactly `v0.2.13-mobile-<run_number>` (or
  `v0.2.13` for a stable release) and that the rootfs URL recorded in
  `runtime-manifest.json` points to `rootfs.bundle` under that same tag.
- Independently verify the finished manifest SHA-256, rootfs SHA-256, byte
  lengths, `arm64-v8a` architecture, `gzip` compression value, and runtime
  version after the adapted official Harness frontend has been packaged.
- Confirm `/` loads the original Harness entrypoint with its plugin assets;
  the retired mobile entrypoint and `plugin-workbench` copies must be absent.
- Confirm CI embeds that exact manifest and rootfs digest in the matching APK.
  Install the official APK with no prior app data and verify the install action
  works offline without entering or changing a source URL or digest.
- Inspect the APK and fail the release if it contains more than one
  `assets/runtime/rootfs.bundle`, more than one `runtime-manifest.json`, either
  `.bak` file, another rootfs archive, or another generated manifest. Record
  the embedded APK byte size and investigate any unexpected increase.
- Confirm the published Release contains exactly the intended APK,
  `rootfs.bundle`, and `runtime-manifest.json`, and that its notes identify the
  embedded offline install and optional remote resume behavior without asking
  users to configure a manifest manually.
- Interrupt a remote rootfs transfer, restart the app, and verify the matching
  `rootfs-<sha256>.part` resumes with Range. Reject an incorrect HTTP 206 or
  `Content-Range`, and verify HTTP 200 and 416 responses to a resume request
  discard the partial and download again from byte zero. Confirm offline, TLS,
  timeout, and truncated responses enter `error` without deleting a valid
  bounded partial or advancing the UI to extraction.
- Inspect the APK for both `lib/arm64-v8a/libdsh_proot.so` and
  `lib/arm64-v8a/libdsh_proot_loader.so`, verify their ARM64 ELF type and
  release-recorded SHA-256 values, and test that the loader is resolved from
  Android's native library directory.
- On an ARM64 device with the embedded runtime installed, verify the network
  tool component group inside the guest terminal: `git --version` succeeds,
  `git clone` of a public HTTPS repository succeeds without disabling
  certificate verification, a commit round-trip succeeds, and the
  `dsh-client-ui-git-graph` plugin renders that repository. Set
  `core.pager=cat` or use `git --no-pager` first: the image intentionally ships
  no pager, and the resulting `less` error is not a git packaging failure. A
  certificate verification failure means the CA directory is missing from the
  image; fix the image instead of setting `GIT_SSL_NO_VERIFY`. A `cannot run
  git-remote-https` or `Permission denied` failure means the execute bit
  regressed, as in the earlier `rg`/`landlock-run` incident. Also confirm the
  image bundles no tokens, SSH keys, or `credential.helper=store` configuration:
  under a single guest uid such material is readable by any code running there,
  and credentials must be injected per session with owner-only permissions
  instead.

## Security gates

- Verify an unauthenticated Harness HTTP request and WebSocket upgrade are both
  rejected, the current non-exported internal WebView succeeds without
  a visible Basic-auth prompt, its HttpOnly origin cookie is installed before
  the first page load, and a credential from an earlier Harness process no
  longer works. Loopback binding alone is not an authentication boundary on
  Android.
- Verify that runtime configuration files containing credentials are created with owner-only permissions inside the guest.
- Confirm the manifest contains the official `ShizukuProvider`; Shizuku remains optional, explicitly authorized, explicitly connected, and limited to a user-visible fixed device Shell session.
- Kill and restart the Shizuku binder and UserService. Verify active sessions close, the UI returns to an authorized-but-disconnected state, Connect Shizuku performs a fresh bind, and `connected` is true only while the authorized UserService binder is live.
- Run Android lint, JVM tests, and an instrumented test on every supported Android API level and an ARM64 physical device.
- Review the final APK network security configuration, exported components, backup rules, and WebView debugging state.
- Verify app startup enters the Harness conversation directly when the runtime is ready, the native toolbar returns to Settings, and neither startup nor management operations open an Android device-credential prompt. Verify the owner-only 90-day audit rotation on every supported Android API level.

## Reproducibility and licensing

- Generate the Gradle wrapper from the pinned Gradle version on a trusted build machine; dependency binaries are intentionally not stored in this repository.
- The Gradle wrapper distribution digest is already pinned in
  `android/gradle/wrapper/gradle-wrapper.properties`
  (`distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`).
  Re-verify it against the official digest for the exact Gradle distribution
  whenever the Gradle version changes.
- Resolve release gate `DEP-001` for the unsupported development-only archive dependency and record a clean package-manager audit.
- Generate and review a full JavaScript and Android dependency inventory from the lockfile and resolved Gradle graph.
- Inspect the generated APK `assets/legal/` bundle for the application
  `LICENSE`, `THIRD_PARTY_NOTICES.md`, full GPL-2.0, GPL-3.0, AGPL-3.0, and
  LGPL-3.0 license texts, and every direct JavaScript runtime dependency license. Do not assume that a
  notice naming a license substitutes for its full text.
- Inspect `assets/legal/ubuntu-packages/` in the generated APK and run
  `python3 scripts/legal-notices.py verify --dest
  android/app/src/main/assets/legal/ubuntu-packages` before packaging. The
  distribution copyright files for `git`, `curl`, `libcurl`, `libexpat1`, and
  `ca-certificates` (plus `openssh-client` when staged) must be present,
  non-empty, and carry the expected license marker, and `inventory.json` must
  list every package that contributed a staged file, including the transitive
  libraries resolved by `ldd`. A local release build must run
  `scripts/legal-notices.py collect` on an Ubuntu 24.04 (noble) machine first;
  never ship an APK whose legal bundle was not collected.
- Record the shipped `git`, `curl`, `libcurl`, `libexpat1`, `ca-certificates`,
  and `openssh-client` package versions from `inventory.json` alongside the
  exact binaries conveyed.
- Record Operit2 repository
  `https://github.com/AAswordman/Operit2` at commit
  `dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`, its
  `tools/android-runtime/` build instructions, the Termux PRoot
  v5.1.107.78 source, the Operit patch, all release-local changes, and the
  hashes of the two shipped ELF files.
- Confirm the packaged PRoot `COPYING` retains its upstream copyright-holder
  and author notices in addition to the full GPL-2.0 terms.
- Publish or otherwise convey complete machine-readable corresponding source
  for the exact GPL-2.0-or-later and AGPL-3.0 covered artifacts by a method
  those licenses permit. Include the patches, build and installation scripts,
  interface files, and clear no-charge retrieval instructions; an upstream
  URL and commit alone are not sufficient.
- Apply the same corresponding-source obligation to the GPL-2.0 `git` binaries
  conveyed in the rootfs (the Ubuntu source package for the recorded version,
  including Debian packaging and patches, plus the `scripts/` and CI
  instructions needed to stage and install them by the same method). The
  unabridged GPL-2.0 text is the one already shipped as
  `legal/licenses/proot-GPL-2.0.txt`; do not add a second, possibly abridged
  copy.
- Do not describe the native artifacts as independently implemented or as
  bit-for-bit reproducible unless a clean rebuild has actually demonstrated
  that result. Preserve build logs and toolchain versions as release evidence.
- Add the reviewed transitive Android license texts and all Ubuntu, Node.js,
  DeepSeek Harness, npm, PRoot, and Operit2 notices required by the exact
  release artifacts.
- Run the repository checks and inspect `git diff` before the local release commit. Do not commit `.env`, signing material, rootfs archives, runners, build output, logs, or files over 100 MB.

- Verify every packaged ELF (.so) passes the 16KB alignment check
  (`readelf -l <lib> | grep LOAD` → p_align == 0x4000) before release, and record the
  Honor / Android 16 on-device page-size test (`adb shell getconf PAGE_SIZE` == 16384)
  in the release notes.

## 发布工程自动门禁（CI 强制执行，2026-10-11 起）

以下五条由 `scripts/mobile-compat-contract.test.mjs` 断言，跑在 CI 的
`pnpm run test:scripts`（`node --test scripts/*.test.mjs`）里，本地同样可以复跑：

- **版本号只有一个来源**：根 `package.json` 的 `version`、`android/app/build.gradle`
  的 `versionName`、`.github/workflows/android-build.yml` 的 `APP_VERSION` 必须相等，
  `versionCode` 必须是正整数。改版本号时必须三处一起改，漏一处 CI 直接失败——
  否则会产出「APK 自称旧版本、Release 标成新版本」的错版。
- **`harness-web` 冻结指针**：`harness-web.pin` 里的 40 位 sha1 必须同时等于
  索引里的 gitlink 与 HEAD 里已提交的 gitlink。子模块换版要单独提交：先把
  `harness-web` 切到目标提交，再改 `harness-web.pin`，然后一起提交。
  本地子模块工作区落后不影响 CI（CI 按 gitlink 重新检出），测试只打印提示。
- **工具链钉死、运行器不浮动**：Node / JDK / Gradle 必须写全补丁号，
  `sdkmanager` 逐个列出带版本的组件，`runs-on` 必须写具体镜像
  （`ubuntu-24.04`、`ubuntu-24.04-arm`），不允许 `ubuntu-latest` 这类浮动标签。
- **发布后复核已发布资产**：`publish-release` 上传完成后会把资产重新下载回来，
  由 `scripts/verify-release-assets.py` 按 `checksums.txt` 逐项核对 SHA-256 与文件
  集合（`--expect app-release.apk,rootfs.bundle,runtime-manifest.json,release-manifest.json`
  保证既不缺件也不多件）。上传被截断、多传一份 bundle、漏传清单都会在这里失败；该脚本自己的
  夹具测试在 `scripts/verify-release-assets.test.mjs`。
- **版本清单随包发布**：`scripts/release-manifest.py` 在 checksums 之前生成
  `/tmp/release-manifest.json`（`schemaVersion`、应用版本、发布标签与类型、提交号，
  以及 APK / rootfs.bundle / runtime-manifest.json 三者的 SHA-256 与字节数）。
  它自己也要被 `checksums.txt` 登记并一起上传，这样用户和排查者不解压 300MB 的 APK
  就能把线上包对应到源码提交。夹具测试真跑这个脚本：`scripts/release-manifest.test.mjs`。

发布作业的失败处置（2026-10-11 起）：创建 Release 或上传资产失败仍是 `exit 1`；
**复核失败**时额外执行「把本次 Release 转为草稿」（`if: failure()` 里 PATCH
`{"draft":true}`）——用户看不到这次发布，但资产与发布说明都还在。确认是误报可以
改回正式；确属坏版本则删除该 Release 与标签、升 `APP_VERSION` 重发。
**不原地覆盖已发布的 tag，也不自动删资产**：网络抖动不该造成不可恢复的删除。

## CI 门禁清单（每条检查在哪个作业、挡什么问题、本机等价命令）

2026-10-11 起 `.github/workflows/android-build.yml` 里各作业实际跑的门禁。除标「新增」的以外，
都是原本就在跑的检查，这里只是列清楚它们在哪个作业、挡什么问题，方便本机复现同一批结论。

### 新增的 `gates` 作业（4 个步骤，按轻到重排序）

`gates` 与 `publish-release` **没有依赖关系**：它并行跑，失败会让这次 run 变红，
但不会拖慢或阻断已有的发布链路（发布链路仍是 `publish-release needs: [build, build-rootfs]`）。
如果你希望发布必须以门禁通过为前提，得自己把它加进 `publish-release.needs`。

- **`Check whitespace in the pushed range`（`gates`）**：`git diff --check` 比本次推送区间
  （PR 用 `github.event.pull_request.base.sha`，push 用 `github.event.before`；取不到就退回 `HEAD^`）。
  挡的是行尾空白、`<<<<<<<` 冲突标记、文件末尾多出的空白行被带进仓库。
  **只比区间不比空树**：仓库历史文件本来就有既有的空白问题（和空树比会立刻报 60 多行），
  比空树的门禁一上线就没人看了。本机等价：`git diff --check HEAD^ HEAD`（有问题时退出码 2）。
- **`Check Android resources`（`gates`）**：`python3 scripts/check-android-resources.py`，
  不需要 Android SDK。挡的是 `values-en/strings.xml` 少一条翻译（用户看到回落文案）、
  多一条默认语言里没有的字符串（aapt2 直接报错）、同一 values 目录里资源键重复、
  字符串值是空的、以及 `AndroidManifest.xml` / 布局 / 样式里 `@string`/`@color`/`@drawable`/`@style`
  引用了不存在的资源。这些以前只有 `./gradlew lintRelease`（`build` 作业，跑到那里通常已经十几分钟）
  才会暴露。本机等价：`python3 scripts/check-android-resources.py`
  （通过时打印 `[资源检查] 通过：N 个资源文件、M 个默认语言字符串，引用全部可解析`）。
- **`Validate manifests against schemas`（`gates`）**：`node scripts/validate-manifests.mjs`，
  校验仓库里的受管清单（`docs/runtime-manifest.example.json`、
  `scripts/fixtures/runtime-manifest.v0.2.3-mobile-323.json`、`scripts/mobile-profile.example.json`，
  以及 `scripts/runtime-profile/plugins/*/package.json` 动态发现）。挡的是清单结构与 schema 脱节：
  少了必填字段、类型写错、enum 取值非法、悄悄多出未声明字段（这类改动在运行时才炸）。
  本机等价：同一条命令；单个文件可以用
  `node scripts/validate-manifests.mjs --file <清单> --schema scripts/schemas/<schema>.json`。
- **`Scan the pushed range for credentials (gitleaks)`（`gates`）**：下载 gitleaks `8.30.1`
  （`gitleaks_8.30.1_linux_x64.tar.gz`，**校验 sha256
  `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb`**，取自该 Release 的资产 digest），
  然后 `bash scripts/ci-gitleaks-range-scan.sh --gitleaks /tmp/gitleaks --base <基线> --head <提交>`。
  挡的是 API key / 令牌 / 私钥被提交进仓库。**只扫本次推送区间**：仓库早期的提交里有已经清理掉的
  诱饵值（`d1d1a935` 的 `.env.example`、`736d8896` 的旧 vendored 路径），全历史扫描会永远报红；
  目录模式则会把未跟踪的构建产物（`output/`、`android/app/build/`）算成误报。
  脚本自己还会验「扫描到的提交数不为 0」——gitleaks 在区间取错时会打印 `0 commits scanned`
  却仍然返回 0，不验就等于一道永远绿灯的门禁。本机等价（用仓库里已有的扫描器）：
  `bash scripts/ci-gitleaks-range-scan.sh --gitleaks .tools/gitleaks.exe --base HEAD^ --head HEAD`。
  `.gitleaks.toml` 只写 `[extend] useDefault = true`，**不许加 allowlist/paths 豁免**：
  `scripts/review-regressions.test.mjs` 会断言这一点。

### `build-rootfs` 作业新增的一步

- **`Validate generated runtime manifest`（`build-rootfs`）**：
  `node scripts/validate-manifests.mjs --file runtime-artifacts/runtime-manifest.json
  --schema scripts/schemas/runtime-manifest.schema.json`，插在 `Upload runtime` 之前。
  挡的是「刚构建出来的更新清单不符合 schema」：这份 JSON 会写进 APK 资产，又由原生侧
  （`RuntimeModels.kt`/`RuntimeStore.kt`）解析来决定装哪个运行时，字段改名却只改了一侧，
  以前要等真机启动失败才发现。本机等价：拿一份真实产物副本跑同一条命令，例如
  `node scripts/validate-manifests.mjs --file output/release-0.2.3/assets/runtime-manifest.json --schema scripts/schemas/runtime-manifest.schema.json`。

### `build` 作业里已有的检查（列清在哪个作业、挡什么、本机怎么复现）

这些本轮没有改动，只是把「哪条检查在哪个作业」写明白：

- `Build web bundle (tsc + vite)`：`pnpm run build` = `tsc -b && vite build`。**TS 类型检查就在这里**，
  类型错直接失败（没有单独的 `tsc` 步骤，也不需要：重复跑一遍只是浪费 CI 时间）。
  本机等价：`npx tsc -b`（退出码 0 即通过）。
- `Lint`：`pnpm run lint` = `eslint . --max-warnings 0`，warning 也算失败。本机等价：同命令。
- `Unit tests (vitest)` / `Official Harness frontend adapter tests`：`pnpm test` 与
  `pnpm --dir harness-web test`。本机等价：`npx vitest run`、`pnpm --dir harness-web test`。
- `Script tests (node --test)`：`pnpm run test:scripts` = `node --test scripts/*.test.mjs`，
  本仓库新增的校验器夹具测试（`scripts/validate-manifests.test.mjs`、
  `scripts/check-android-resources.test.mjs`）也在这一批里。本机等价：同命令。
- `Runtime packaging self-check`：`python3 scripts/build-embedded-runtime.selfcheck.py`。本机等价：同命令。
- `Build release APK + lint + unit tests`：`./gradlew assembleRelease lintRelease testDebugUnitTest --no-daemon`
  （工作目录 `android/`）。**Kotlin 编译、Android lint、Kotlin 单测都在这一步**；资源类的检查在
  `lintRelease` 里也有，但代价是十几分钟，所以能用 `gates` 里那个几秒钟的脚本拦下的问题不必等到这里。
  本机等价：`pnpm run android:check`（= `pnpm run build && cap sync android` + `gradlew.bat lint testDebugUnitTest`），
  或直接 `cd android && gradlew.bat assembleRelease lintRelease testDebugUnitTest`。
- `校验 APK 团队签名` / `Verify the final APK bundled runtime integrity` / `Verify APK legal material …`：
  分别跑 `python3 scripts/verify-apk-signature.py`、`python3 scripts/verify-bundle.py --apk …`、
  `python3 scripts/legal-notices.py verify --dest android/app/src/main/assets/legal/ubuntu-packages`，
  都只在本机有已签名的 APK 时才能复现。

### schema 覆盖的是哪一侧（谁生成、谁消费）

| schema | 覆盖的清单 | 谁生成 | 谁消费 |
| --- | --- | --- | --- |
| `scripts/schemas/runtime-manifest.schema.json` | 更新清单 `runtime-manifest.json` | 构建机上 `scripts/build-embedded-runtime.py`（`:1149-1170`），CI 里由 `build-rootfs` 产出 | 原生侧 Kotlin（`runtime/RuntimeModels.kt`、`runtime/RuntimeStore.kt`）与前端读到的版本信息 |
| `scripts/schemas/release-manifest.schema.json` | 发布清单 `release-manifest.json` | CI 的 `publish-release` 调 `scripts/release-manifest.py` | 发布复核 `scripts/verify-release-assets.py`、人工排查 |
| `scripts/schemas/plugin-package.schema.json` | 插件清单（npm `package.json` 的 `dsh.bundle.patch` 等字段） | 插件作者/仓库（`scripts/runtime-profile/plugins/*/package.json`） | 容器内 Node 的 plugin-manager（解析 `dsh.bundle.patch` 决定注入哪个补丁） |
| `scripts/schemas/mobile-profile.schema.json` | 移动端 profile（`--mobile-profile` 的输入） | 仓库里的 `scripts/mobile-profile.example.json` | `scripts/build-embedded-runtime.py` 的 `validate_mobile_profile`（schema 与它同口径） |

夹具说明：`runtime-manifest` 用的是**真实产物样本**（`scripts/fixtures/runtime-manifest.v0.2.3-mobile-323.json`
是 release `0.2.3-mobile-323` 那份清单的逐字节副本）；`release-manifest` 的夹具由测试**真跑
`scripts/release-manifest.py`** 生成后再校验，不是手写的假样本。校验器与夹具测试在
`scripts/validate-manifests.mjs` / `scripts/validate-manifests.test.mjs`（含「解析失效不会让用例恒真」的自检）。

### 没有进 CI 的检查（以及原因）

- `scripts/check-mobile-layout.py` 是浏览器布局回归，需要 Python playwright 与
  `pnpm run preview -- --port 4173` 起的预览服务器（要联网装浏览器），不适合放进 CI；
  本机需要时手动跑。
- `scripts/check-mobile-layout-cdp.mjs` 是同一组视口的**无第三方依赖**版本：直接用系统
  Edge/Chrome 的 headless 与 CDP 驱动（只需要 Node 内置模块与
  `pnpm run preview -- --port 4173 --strictPort`），本机没有 playwright、也装不上时就用它。
  两台脚本写同一个产物目录 `output/mobile-layout/`（PNG + `report.json`），契约测试
  `scripts/mobile-layout-contract.test.mjs` 保证视口档位、报告字段与产物目录两边一致。
  2026-10-11 本机实跑读数：三档（363×800 / 800×363 / 1280×900）外壳与官方设置结构夹具**全部通过**，
  末行 `LAYOUT_OK: 3 视口全过（Edg/155.0.4283.45 …）`。
  口径限制（如实记录）：验的是 **Chromium 里的 WebView 外壳与官方设置弹窗结构**，
  不等于真机 WebView（系统 WebView 版本、输入法、字体缩放、真机安全区都不在内）；
  Python 版因为缺依赖**这一轮没有跑过**，所以两套脚本的「结果一致」目前只有源码口径契约，
  没有实测比对。
- `scripts/mobile-file-publication.selfcheck.py` 需要真实 Linux 文件系统，本机 Windows 上跑不了，
  也不在 CI 里（CI 里覆盖同一逻辑的是 `scripts/mobile-session-publish.py` 相关的脚本测试）。
- gitleaks 的全历史与目录模式：如上一节所述会把已修掉的历史诱饵和未跟踪产物算成命中，
  只在需要人工普查时手动跑，不作为门禁。

### 「权限清单」说明

任务书里「插件清单 / 更新清单 / 权限清单的 JSON Schema 校验」中的权限清单，在本仓库**没有对应的
JSON**：插件清单里没有权限字段，Android 权限写在 `android/app/src/main/AndroidManifest.xml`（XML），
已由 `scripts/mobile-compat-contract.test.mjs` 用断言覆盖；插件 UI 展示的「权限」是
`src/locales/*.ts` 的文案。所以这里只为三份真实存在的 JSON 清单写了 schema，没有为权限单独造一份。
