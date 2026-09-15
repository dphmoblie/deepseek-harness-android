# 网络工具组件组的法律材料（`assets/legal/ubuntu-packages/`）

本目录是 APK 内 `assets/legal/ubuntu-packages/` 的来源，存放**网络工具组件组**
（`git` / `curl` / `libcurl` / `libexpat1` / `ca-certificates`，以及可选的
`openssh-client`）及其传递依赖库的发行版版权与许可证原文。

## 为什么单独放一个目录

APK 的既有法律材料走**手工登记**：`android/app/build.gradle` 的 `legalAssetSources`
把 `LICENSE`、`THIRD_PARTY_NOTICES.md`、`legal/licenses/*.txt` 以及 node_modules 中的
许可证登记成 `assets/legal/…`，任一来源缺失或为空就终止构建。这条路径只适用于
「源码在仓库里」的组件。

新增的网络工具组件是从 Ubuntu 24.04 ARM64 包里取的**二进制**，仓库里不会存在这些
文件，因此补上收集步骤：由 CI 在与镜像**同发行版同架构**的 runner 上执行

```sh
python3 scripts/legal-notices.py collect \
  --dest android/app/src/main/assets/legal/ubuntu-packages \
  --packages /tmp/network-tools-packages.txt
```

把每个包的 `/usr/share/doc/<pkg>/copyright` 复制成 `<包名>-copyright.txt`，并写出
`inventory.json`（本次收集到的包清单与许可证标识）。包清单由
`scripts/stage-network-tools.sh` 用 `dpkg -S` 反查生成，因此 `ldd` 带进来的传递依赖库
也会一并登记，不会只登记直接依赖。

`packages.json` 之类的重复清单**故意不存在**：组件表只有一处，
即 `scripts/legal-notices.py` 的 `COMPONENTS`（必需/可选、许可证标识、版权文件里
必须出现的标记）。

## 本目录里的文件

| 文件 | 来源 |
| --- | --- |
| `README.md` | 本说明（仓库内维护） |
| `<包名>-copyright.txt` | CI 从 runner 的 `/usr/share/doc/<包名>/copyright` 复制（**不入库**） |
| `inventory.json` | CI 在收集时生成的本次清单（**不入库**） |

## 校验

打包 APK 前（CI 的 `build` 作业与本地发布前）必须通过：

```sh
python3 scripts/legal-notices.py verify \
  --dest android/app/src/main/assets/legal/ubuntu-packages
```

校验口径：必需组件的版权文件必须存在、不小于 512 字节、且出现预期许可证标记
（`git` → GPL-2；`ca-certificates` → MPL-2.0；`libexpat1` → MIT；`curl`/`libcurl` → curl），
并确认 APK 内已有未删节的 GPL-2.0 全文（`legal/licenses/proot-GPL-2.0.txt`，
`git` 与 PRoot 共用同一份 GPL-2.0 全文）。缺一件即失败。

`openssh-client` 是可选项：**存在就必须合格，不存在不算失败**。

## GPL 的对应源码义务

`git` 是 GPL-2.0。提供「对应源码」的口径**不在这里自创**，见
`docs/RELEASE_CHECKLIST.md` 的既有表述：必须按许可证允许的方式提供确切二进制的
完整对应源码（含补丁、构建与安装脚本、接口文件与明确的免费获取说明），
只给上游仓库链接或 commit 不构成对应源码要约。

## 本地发布构建

本目录的 `<包名>-copyright.txt` 由 CI 收集，不在 Git 里。若在本地打发布包，
必须先在一台 Ubuntu 24.04（`noble`）机器上执行上面的 `collect`，再执行 `verify`；
否则 `verify` 会失败，且该次 APK **不应**作为发布包分发。
