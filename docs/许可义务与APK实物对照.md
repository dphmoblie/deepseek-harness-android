# 许可义务 ↔ APK 实物对照

本文回答一个问题：**我们在 `THIRD_PARTY_NOTICES.md` 里承诺的每一份许可文本，是否真的进了 APK。**
结论是逐项对得上；本篇记录取证方法与原始读数，便于任何人复现。

## 1. 取证对象与方法

- 被检对象：GitHub Actions 成功运行 `37262623530` 的产物 `dsh-harness-android-release`
  （`dsh-harness-android-release/app-release.apk`，**314,385,216 B**，SHA-256
  `3FC044142C1EE3B54BA42CE4B07EE940568846DAC20F9DE256A6C497320DAB12`）。
- 该产物对应提交 **`60447ac`**（2026-10-05 04:14Z 运行的 `headSha`），**早于**本轮「无障碍参数改名 + 清除第三方致谢」的改动；
  因此下文的 `THIRD_PARTY_NOTICES.md` 是**改动前**的版本，其余文本与当前仓库一致。
- 方法：`gh run download 37262623530 -n dsh-harness-android-release` ⇒ `tar -tf app-release.apk | grep assets/legal`
  ⇒ `tar -xf app-release.apk assets/legal` ⇒ 与仓库文件（或 `git cat-file blob 60447ac:<path>`）逐字符比对。
- 复现命令（PowerShell）：

  ```powershell
  gh run download 37262623530 -n dsh-harness-android-release -D <dir>
  tar -tf <dir>\app-release.apk | Select-String 'assets/legal/'
  tar -xf <dir>\app-release.apk -C <dir>\out 'assets/legal'
  ```

## 2. APK 内 `assets/legal/` 全量清单（26 条）

| 路径 | 条数 | 作用 |
| --- | --- | --- |
| `assets/legal/LICENSE` | 1 | 应用自身 MIT 全文 |
| `assets/legal/THIRD_PARTY_NOTICES.md` | 1 | 溯源与义务声明（本次为 `60447ac` 的旧版） |
| `assets/legal/licenses/` | 13 | GPL/AGPL/LGPL 全文 + npm 依赖许可 |
| `assets/legal/ubuntu-packages/` | 11 | Ubuntu 构件版权文件 + `inventory.json` + 说明 |

`licenses/` 13 条：`gnu-GPL-3.0.txt`、`gnu-LGPL-3.0.txt`、`operit2-AGPL-3.0.txt`、
`operit-terminal-core-LGPL-3.0.txt`、`proot-GPL-2.0.txt`、`capacitor-android-LICENSE.txt`、
`capacitor-core-LICENSE.txt`、`dsh-web-frontend-LICENSE.txt`、`lucide-react-LICENSE.txt`、
`react-LICENSE.txt`、`react-dom-LICENSE.txt`、`xterm-LICENSE.txt`、`xterm-addon-fit-LICENSE.txt`。

`ubuntu-packages/` 11 条：`README.md`、`inventory.json`、`ca-certificates-copyright.txt`、
`curl-copyright.txt`、`git-copyright.txt`、`libcurl4t64-copyright.txt`、`libexpat1-copyright.txt`、
`openssh-client-copyright.txt`、`openssh-server-copyright.txt`、`openssh-sftp-server-copyright.txt`、
`openssl-copyright.txt`。

## 3. 义务 ↔ 实物逐项核对

| 义务（`THIRD_PARTY_NOTICES.md` / `legal/licenses/`） | APK 实物 | 一致性 |
| --- | --- | --- |
| 应用自身 MIT 全文 | `assets/legal/LICENSE` | **一致**（仅行尾 CRLF/LF 差异） |
| PRoot（GPL-2.0-or-later，已按 Operit 工具链打补丁）全文 | `licenses/proot-GPL-2.0.txt` | **一致**（行尾差异） |
| Operit2 工具链（AGPL-3.0）全文 | `licenses/operit2-AGPL-3.0.txt` | **一致**（行尾差异） |
| Operit Terminal Core 上游 `LICENSE`（LGPL-3.0，留存以备溯源） | `licenses/operit-terminal-core-LGPL-3.0.txt` | **一致**（行尾差异） |
| LGPL-3.0 未删节全文（上游文本被缩写，故补 FSF 全文） | `licenses/gnu-LGPL-3.0.txt` | **一致**（行尾差异） |
| GPL-3.0 未删节全文（同上，且为 `git` 的 GPL-2.0 提供参考） | `licenses/gnu-GPL-3.0.txt` | **一致**（行尾差异） |
| 网络工具构件（`git`/`curl`/`libcurl4t64`/`libexpat1`/`ca-certificates`/openssh）版权文件 | `ubuntu-packages/*-copyright.txt` | **在场**（`git`/`curl`/`libcurl`/`libexpat1`/`ca-certificates` 五件齐备，另有 openssh 三件与 openssl） |
| 参与打包的构件清单（含 `ldd` 传递依赖） | `ubuntu-packages/inventory.json` | **在场** |
| npm 依赖许可文本 | `licenses/*-LICENSE.txt`（7 份） | **在场** |
| 对应源码可得的承诺 | `THIRD_PARTY_NOTICES.md:72-80`、`:100-108` 与 `docs/RELEASE_CHECKLIST.md` | 文本义务已写明；**源码本身不在 APK 内**（在仓库 `scripts/runtime-profile/patches/**` 与上游） |

「行尾差异」的取证：仓库工作区是 CRLF（`core.autocrlf`），APK 内是 LF，**去掉行尾后逐字符相同**
（例：`operit-terminal-core-LGPL-3.0.txt` 两边都是 210 行、首尾逐字相同，唯一差异是仓库侧 210 个 `CR`）。

## 4. CI 门禁（每次发布都会跑）

`.github/workflows/android-build.yml`：

- `:146`「Install runtime legal notices into the APK assets」把 `runtime-legal` 产物拷进
  `android/app/src/main/assets/legal/ubuntu-packages`；
- `:161`「Verify APK legal material for the network tool components」执行
  `python3 scripts/legal-notices.py verify --dest …`，要求 `git`/`curl`/`libcurl4t64`/`libexpat1`/`ca-certificates`
  的版权文件与未删节 GPL-2.0 全文齐全，**缺一件即构建失败**；
- `:189-193` 上传 `dsh-harness-android-release` 产物。

## 5. 诚实边界

- 本表核对的是**许可文本是否随包**，**没有**核对「每个被分发的二进制都能由其声明的源码逐位重建」；
  `THIRD_PARTY_NOTICES.md:72-80` 已如实写明这一点（「本文不声称本仓库当前会对它们做逐位重建」）。
- 被检 APK 早于本轮改名与致谢清理，其 `THIRD_PARTY_NOTICES.md` 含现已删除的第三方致谢段；
  **下一次发布后应重跑本表**，把该文件替换为新版读数。
- 本文是工程口径的记录，不构成法律意见。
