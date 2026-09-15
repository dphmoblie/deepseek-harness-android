# 运行时补丁与 dsh 写入回退（访客硬链接被拒，登记册 P0-1 / P0-2）

本文说明：访客侧 dsh 两处写入路径为什么在真机上必然失败、仓库如何用**既有的 pnpm 补丁通道**
（`scripts/runtime-profile/pnpm-workspace.yaml` 的 `patchedDependencies`）给它们加回退、
回退在语义上放弃了什么、本机能验证到哪一步、以及 dsh 升级时怎么重做补丁。

登记册条目：`docs/真机缺陷与改进清单.md` 的 P0-1（`write` 工具无法新建文件）与
P0-2（附件落盘全废）。触发方是 PRoot / 主机策略（访客内 `link(2)` 一律 `EACCES`），
**不在我们手里**；我们能修的是「dsh 侧没有回退路径」这一半。

## 一、问题

### 1.1 真机现象（PRoot 访客，Android 16 / HONOR）

- 访客内 `link(2)` **一律被拒绝**：同目录、跨目录都是 `Permission denied`；`rename` / `mv` 正常。
- 于是：
  - `write` 工具**新建**文件失败，**覆盖**已存在的文件正常；
  - `read_image` / `mobile_device_screenshot` 报 `Unable to persist attachment.`。

### 1.2 上游代码位置（`@deepseek-ai/dsh 0.1.5-rc.2`，两个互不共享的包）

| 编号 | 包 | 文件与函数 | 硬链接调用点 | 为什么「新建失败、覆盖正常」 |
|---|---|---|---|---|
| P0-1 | `@deepseek-ai/dsh-fs-local` | `lib/index.js` 的 `writeFileAtomic()`（第 494 行起） | `linkFile = internals.linkFile ?? link`（第 504 行），在 `createIfAbsent !== void 0` 分支调用（第 532–536 行） | 传了 `createIfAbsent` 就走 `link`（新建）；没传就走 `else await rename(...)`（第 543 行，覆盖） |
| P0-2（图片 / 截图） | `@deepseek-ai/dsh-attachment-local` | `lib/index.js` 的 `publishStagedObject()`（第 528 行起） | `await link(staged.path, target)`（第 533 行） | 内容寻址对象只有这一条发布路径，没有 rename 分支 |
| P0-2（通用文件附件） | 同上 | 同文件的 `publishImmutableAlias()`（第 467 行起） | `await link(source, target)`（第 472 行） | 对象先由 `publishImmutableObject()` 发布成功，再为它挂只读别名时失败；调用方是 `saveFileVerbatim()` / `saveFileStreamVerbatim()` |

两个包**不共用 helper**：`writeFileAtomic` 是 `dsh-fs-local` 的模块内私有函数，
`publishStagedObject` 是 `dsh-attachment-local` 的模块内私有函数，各自直接
`import { link } from "node:fs/promises"`。所以必须两个包各打一个补丁，
不存在「只改一处」的选项。（`@deepseek-ai/dsh-atomic-write` 与本路径无关。）

### 1.3 与登记册描述不一致的地方（以代码为准）

1. **报错文本不是裸 `EACCES: link()`**，而是被 dsh 的错误分类包了一层：
   - P0-1：`throwGuardedCreateFailure()`（第 465 行）把 errno 归类成
     `FsError(code = "FS_IO_ERROR")`，消息形如
     `cannot write "<相对路径>": EACCES: permission denied, link '<temp>' -> '<target>'`，
     原始 errno 在 `error.cause.code`；
   - P0-2：`AttachmentError("Unable to persist attachment.", "ATTACHMENT_WRITE_FAILED", { cause })`。
2. **P0-2 分两条发布路径**（重要）：`publishStagedObject()` 只覆盖
   **图片 / 截图**路径（`saveImageFile` → `commitPreparedImageFile` → `publishImmutableObject`
   → `publishStagedObject`）。**通用文件附件**走的是
   `saveFileVerbatim()` / `saveFileStreamVerbatim()`（第 708 / 728 行），它们除了发布对象，
   还要用 `publishImmutableAlias()`（第 467 行，内部 `link` 在第 472 行）为同一对象再挂一个
   **只读名字**（`<root>/storefiles/<摘要>/<摘要>/<名字>`）。这两条路径的回退方式**必须不同**
   （原因见 §3.3），因此各有一个分支与一组测试。

## 二、补丁通道怎么生效（沿用既有机制，没有另创机制）

1. `scripts/runtime-profile/pnpm-workspace.yaml` 的 `patchedDependencies` 登记
   「包名@精确版本 → 补丁文件相对路径」；补丁文件放在 `scripts/runtime-profile/patches/`。
2. `pnpm install`（`scripts/runtime-profile` 是一个独立 workspace）把补丁**应用到安装出来的
   `node_modules` 副本**上：被补丁的包在 `pnpm-lock.yaml` 里生成
   `patchedDependencies` 哈希表与带 `patch_hash=<补丁文件 sha256>` 的快照键，
   虚拟目录名也随之变化。
3. CI 的 rootfs 步骤把 `package.json` / `pnpm-lock.yaml` / `pnpm-workspace.yaml` /
   `patches/` / `plugins/` 复制到 `/tmp/dsh-root` 后 `pnpm install --frozen-lockfile`，
   再把整棵 `node_modules` 打进 `opt/dsh`（`scripts/build-embedded-runtime.py`）。
   **因此补丁代码随 rootfs 一起进设备**，设备侧不需要任何后处理。
4. 本仓库既有的同类先例是 `patches/@earendil-works__pi-ai@0.85.1.patch`（同一个通道）。

### 2.1 键名写法与 peer 关系

```yaml
patchedDependencies:
  '@earendil-works/pi-ai@0.85.1': patches/@earendil-works__pi-ai@0.85.1.patch
  '@deepseek-ai/dsh-fs-local@0.1.5-rc.2': patches/@deepseek-ai__dsh-fs-local@0.1.5-rc.2.patch
  '@deepseek-ai/dsh-attachment-local@0.1.5-rc.2': patches/@deepseek-ai__dsh-attachment-local@0.1.5-rc.2.patch
```

- 键用 **精确版本**：`pnpm-workspace.yaml` 的 `overrides` 已把 `@deepseek-ai/dsh-*` 钉死在
  一个版本上，精确键因此是稳定的；而 pnpm 的 `allowUnusedPatches` 默认为 `false`，
  一旦 dsh 升到别的版本，这些键就变成「未被使用的补丁」，`pnpm install` **直接失败**
  （pnpm 11 里补丁应用失败一律抛错）。这正是我们要的失败模式：**升级必须重做补丁，
  而不是补丁静默失效**。
- **peer 关系不进键名**。peer 只体现在两处：`pnpm-lock.yaml` 的 snapshot 键
  （`@deepseek-ai/dsh-fs-local@0.1.5-rc.2(patch_hash=…)(@deepseek-ai/cordis@4.0.2)(…)`）与
  `node_modules/.pnpm/` 的虚拟目录名。补丁文件的命名沿用既有约定：
  `@scope/name@version.patch` 里的 `/` 换成 `__`。
- 键所指向的补丁文件内容变了，就必须重新 `pnpm install`：lockfile 里记录的是
  补丁文件字节的 **sha256**，与文件不一致时 CI 的 `--frozen-lockfile` 会报
  `OUTDATED_LOCKFILE`（本地先由回归测试拦住，见 §五）。

## 三、补丁内容（最小化：只改 `lib/index.js`，只加回退必需的行）

### 3.1 `patches/@deepseek-ai__dsh-fs-local@0.1.5-rc.2.patch`

- 新增模块内私有判定 `isLinkUnavailableError(error)`：`EACCES` / `EPERM` / `EXDEV`。
- `writeFileAtomic()` 的 `createIfAbsent` 分支的 `catch` 里：

```js
} catch (error) {
    if (!isLinkUnavailableError(error)) await throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
    let existing;
    try {
        existing = await inspectPublicationTarget(absolutePath);
    } catch (metadataError) {
        if (!isENOENT(metadataError) && !isENOTDIR(metadataError)) await throwGuardedCreateFailure(metadataError, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
    }
    if (existing !== void 0) await throwGuardedCreateFailure(error, absolutePath, createIfAbsent.displayPath, inspectPublicationTarget);
    await rename(tempPath, absolutePath);
}
```

未改动：其它分支、错误消息、导出签名、依赖、`internals` 测试钩子（`linkFile` 仍然是
`internals.linkFile ?? link`，回退只在 `link` 真的抛错后发生）。

### 3.2 `patches/@deepseek-ai__dsh-attachment-local@0.1.5-rc.2.patch`

- `node:fs/promises` 的导入增加 `lstat`（复核目标存在性用）。
- 新增 `isLinkUnavailableError(error)` 与 `renameStagedObject(staged, target)`：
  目标不存在 → `rename(staged.path, target)`；目标已存在 → **不覆盖**，按原有 EEXIST 分支的
  口径做摘要复核（不一致即 `ATTACHMENT_CORRUPT`）。
- `publishStagedObject()` 的 `catch` 里加一个分支：

```js
} catch (error) {
    if (isLinkUnavailableError(error)) {
        await renameStagedObject(staged, target);
    } else {
        if (!(error instanceof Error && "code" in error && error.code === "EEXIST")) throw error;
        if (await digestFile(target) !== staged.sha256) throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
    }
}
```

- **必需的一处附带改动**：把发布后的 `await unlink(staged.path)` 换成
  `await removeTemporary(staged.path)`。原因：`rename` 回退已经把暂存名**搬成**目标名，
  此时 `unlink` 必然 `ENOENT`，会被外层 `catch` 包成
  `ATTACHMENT_WRITE_FAILED`——**对象其实已经发布成功，调用方却仍然看到失败**
  （实测：把这一行改回 `unlink` 后，目标文件存在但调用抛
  `ATTACHMENT_WRITE_FAILED` / `cause=ENOENT`）。`removeTemporary` 只容忍 `ENOENT`，
  其它清理失败照旧上抛，因此没有放松失败可见性。

### 3.3 `publishImmutableAlias()`：只能 copy，不能 rename

同一个补丁里还有第二处回退，它**刻意不照抄** `renameStagedObject`：

- **为什么不能 rename**：`renameStagedObject` 搬的是**一次性暂存名**，搬走正好；
  而别名发布的 `source` 是**内容寻址对象**（`fileObjects/<前两位>/<sha256>`），
  它还要被同一份 store 的其它引用复用。用 rename 等于**把对象库里那条记录搬走**，
  别的引用与后续读取会找不到对象。
- **因此回退是复制**：新增 `copyImmutableAlias(source, target)`——`O_EXCL` 独占创建
  （目标已存在即不覆盖，交回调用方按原有摘要复核语义处理）→ 流式读源写入 →
  `handle.sync()`。不可变性由**写后摘要复核**保证（目标字节必须等于声明的 `sha256`），
  而不是由「共享同一个 inode」保证。
- **失败清理**：半途失败（磁盘满、源读失败）必须删掉残片——否则下一次重试会看到
  「已存在」的目标，复核不过而报 `ATTACHMENT_CORRUPT`，把「写失败」伪装成「存储损坏」。
- **复核位置的一处调整**：原文只在 `EEXIST` 分支做摘要复核，现在两个分支都做
  （copy 分支需要它来证明不可变性）。`EEXIST` 分支的语义与原来逐字相同。
- **代价（如实写明）**：`link` 可用时同一份字节只占一份盘，回退路径下占两份。
  这与该设备上 pnpm 的 store 与 `node_modules` 各存一份真实副本是同一类取舍；
  `link(2)` 被整体拒绝的环境里，「省盘」与「可写」只能二选一，这里选可写。

## 四、回退语义与「原子性 vs 语义」的取舍

**选择保留语义，放弃这一步原子性。**

- `link(temp, target)` 在 POSIX 上同时提供两件事：**创建**与**「目标已存在就拒绝」**，
  两步是同一个系统调用，天然没有窗口。所以 `createIfAbsent` / 内容寻址发布的
  no-replace 保证来自内核，而不是来自调用方的检查。
- `rename` 是原子的，但**会覆盖已存在的目标**：直接用 rename 回退，
  「只在目标不存在时创建」就不成立了——并发创建者写进去的文件会被静默覆盖。
- 因此回退前**必须重新确认目标**：
  - `dsh-fs-local`：`lstat` 到目标 → 沿用原有分类报错
    （普通文件 `FS_NOT_OBSERVED`、非普通文件 `FS_NOT_REGULAR_FILE`），不覆盖；
  - `dsh-attachment-local`：`lstat` 到目标 → 不覆盖，按内容寻址的摘要复核口径处理
    （字节一致视为去重成功，不一致报 `ATTACHMENT_CORRUPT`）。
- **放弃的是什么**：复核与 `rename` 之间存在窗口。在这个窗口里被并发创建的目标，
  可能被本次 `rename` 覆盖。也就是说 `createIfAbsent` 从「内核级 no-replace」
  降级为「存在性复核 + rename」，在单写入者的常规使用（`write` 工具、附件落盘）里等价，
  在并发写入同一路径时不再有内核保证。`link(2)` 被整体拒绝的环境里，
  **可写性与这一步原子性只能二选一**；这里选择可写性 + 保住语义。
- **不做什么**：不写 catch-all。只有 `EACCES` / `EPERM` / `EXDEV` 进回退；
  `ENOENT` / `EEXIST` / 其它任何 errno 一律走原有失败路径（回归测试逐条钉住）。

**别名发布（§3.3）多一层取舍**：copy 回退保住了「对象库不被搬走」与「字节不可变」，
但放弃了 `link` 的共享存储。**这不是等价替换，是有意识的功能优先**：
在 `link(2)` 被拒的设备上，通用文件附件要么多占一份盘，要么完全不可用。

## 五、回归防线（本机可跑）

测试文件：`scripts/runtime-link-fallback.test.mjs`（CI 的 `node --test scripts/*.test.mjs`
会带上它）。四组断言：

1. **通道与最小化**（不需要安装 `node_modules` 也能跑）：
   - `patchedDependencies` 里有这两个键，补丁文件存在；
   - 键里的版本 == `pnpm-workspace.yaml` 的 `overrides['@deepseek-ai/dsh-*']`
     == `package.json` 的 dsh 版本（升 dsh 忘记重做补丁时先在这里炸）；
   - `pnpm-lock.yaml` 里两个键的哈希 == 补丁文件字节的 sha256，且快照键带同一个
     `patch_hash=`；
   - 每个补丁**只改 `lib/index.js` 一个文件**，且新增行里必须有三个 errno 判定、
     `isLinkUnavailableError`、rename 落地动作等必需行。
2. **`dsh-fs-local` 行为**：从 `scripts/runtime-profile/node_modules` 里取出
   **运行时实际加载的那一份**（沿 `@deepseek-ai/dsh-base` 的真实目录解析 peer 根，
   与 Node 的解析路径一致），确认它带补丁标记，然后把它的 `link` 换成按 errno 抛错的桩，
   逐条驱动：`EACCES`/`EPERM`/`EXDEV` 回退后文件真的被新建、内容正确、暂存目录被清理；
   目标已存在时报 `FS_NOT_OBSERVED` 且**内容不被覆盖**；目标是目录时报
   `FS_NOT_REGULAR_FILE`；`ENOENT` 仍以 `FS_IO_ERROR` + `cause.code = "ENOENT"` 失败
   （不被吞成成功写入）；`EEXIST` 语义不变；不带 `createIfAbsent` 的覆盖分支不受影响；
   `link` 正常时仍走原来的硬链接路径。
3. **`dsh-attachment-local` 对象发布行为**：同样取自运行时实际加载的那一份。
   `EACCES` 回退后对象字节正确、暂存名被清理、权限 `0400`（非 Windows）；
   目标已存在且字节相同 → 去重复用；字节不同 → `ATTACHMENT_CORRUPT` 且不覆盖；
   `ENOENT` → `ATTACHMENT_WRITE_FAILED` + `cause.code = "ENOENT"`；`EEXIST` 语义不变；
   `link` 正常时仍走硬链接发布。
4. **`dsh-attachment-local` 别名发布行为**（`publishImmutableAlias`）：
   `EACCES` 回退后别名内容正确、权限 `0400`（非 Windows）；
   **源对象必须仍然存在**（这条断言专门钉住「不能照抄 rename 回退」），且源 `nlink` 为 1
   （证明复制得到的是独立 inode）；别名已存在且字节相同 → 不覆盖不报错；
   字节不同 → `ATTACHMENT_CORRUPT` 且源与目标都不被改动；
   `ENOENT` → `ATTACHMENT_WRITE_FAILED` + `cause.code = "ENOENT"`，且不留半截目标；
   `link` 正常时仍是硬链接（源 `nlink` 为 2）。

### 5.1 桩的做法与夹具边界

- `writeFileAtomic` / `publishStagedObject` 都**不是公开导出**（包只导出 cordis 插件），
  所以夹具把**补丁后的整份模块源码**复制到临时目录，只改两处导入：
  - `node:fs/promises` → 本地桩模块（`export *` + 显式 `link`，显式导出优先，
    因此被测代码里的 `link` 就是桩；桩通过 `linkControl.mode` 抛 `EACCES` 等）；
  - `sharp` → 空实现（原生模块；工作区 `supportedArchitectures` 只装 linux/arm64，
    本机 Windows 与 CI 的 x64 都没有平台二进制；被测的两条路径不经过 sharp）。
  其余依赖按补丁后模块里的**真实解析结果**改写成绝对 `file:` URL，
  所以夹具跑的是补丁后的真实函数体，不是重写的副本。
- 若 `scripts/runtime-profile/node_modules` 完全没装，行为测试会 **skip** 并给出说明
  （CI 的步骤顺序是「先 `pnpm install --frozen-lockfile`，再 `node --test`」，所以 CI 上必然执行）；
  通道与补丁内容断言在任何情况下都执行。
- **本机验证不到的部分**：真实 rootfs 里的生效情况。本机无法构建 rootfs
  （需要 CI 的 arm64 runner 下载 Ubuntu / Node 归档），
  因此「补丁在设备上生效」只能由 CI / 真机确认（见 §七）。

## 六、dsh 升级时如何重建补丁

沿用 pi-ai 那套做法（`.patch-source/` 是本地草稿目录，已被 `.gitignore` 忽略）：

```powershell
cd scripts/runtime-profile

# 1) 让 pnpm 装上目标版本（先改 package.json / overrides 的版本钉），然后取上游原文
#    作为「未打补丁」的一侧：
#    node_modules/.pnpm/@deepseek-ai+dsh-fs-local@*/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js

# 2) 在 .patch-source/gen/<包名>/ 下建 a/ 与 b/ 两棵同构目录：a 放上游原文、b 放改好的版本
#    （目录名就叫 a / b，diff 出来的路径才是规范的 a/lib/index.js、b/lib/index.js）

# 3) 生成补丁（--no-prefix 让 git 直接把 a/ b/ 当路径前缀，--full-index 出完整哈希）
git diff --no-index --no-prefix --full-index --no-color -- a b > ..\.patch-source\gen\<包名>.patch

# 4) 覆盖到 patches/ 下（沿用 @scope__name@version.patch 命名），更新 pnpm-workspace.yaml 的键
# 5) 重新安装，让 pnpm 应用补丁并刷新 pnpm-lock.yaml 里的 patchedDependencies 哈希
pnpm install --ignore-scripts

# 6) 核对：lockfile 的哈希 == 补丁文件 sha256；再跑回归测试
cd ../..; node --test scripts/*.test.mjs
```

升级时的注意点：

- **必须**重新生成补丁（不能只把键改成新版本号）：`isLinkUnavailableError` 的插入位置
  与 `@@` 上下文都按旧版本的行号，直接改键会在安装时因上下文不匹配而失败。
- 若上游已自带 rename 回退（`isLinkUnavailableError` 或等价分支），**删掉对应补丁与键**，
  并把回归测试里对应的期望一起删掉——不要留一个永远「打不上」的补丁。
- 补丁必须同步更新 `pnpm-lock.yaml`（`pnpm install` 会做），否则 CI 的
  `--frozen-lockfile` 直接失败。
- 补丁文件本身要保持在仓库里是 **LF**（`pnpm` 应用补丁时按行比对；CI 在 Linux 上检出即为 LF）。

## 七、只能在 CI / 真机验证的部分

本机（Windows / x64）**无法**验证的：

1. **rootfs 构建与打包**：补丁代码随 `node_modules` 进 `opt/dsh` 的过程由
   `.github/workflows/android-build.yml` 的 arm64 任务完成。
2. **补丁在设备访客内生效**：建议的验收步骤（真机）：
   - `write` 工具**新建**一个文件（此前必失败）→ 应成功；再**覆盖**同一文件 → 仍成功；
   - `read_image` 与 `mobile_device_screenshot` 各一次 → 应不再出现
     `Unable to persist attachment.`；
   - 运行时自检的 `hardlink` 项在该设备上仍然会报 `fail` + `HARDLINK_DENIED`
     —— 这是**如实报告**「这台设备上硬链接不可用」，与本次回退并不矛盾，
     不要把它读成「补丁没生效」；反过来，`hardlink=ok` 的设备上回退路径不会触发。
3. `pnpm` 自身的硬链接行为（store ↔ `node_modules` 各存一份真实副本）不在本次范围内。

## 八、已知边界与不确定之处

1. **`publishImmutableAlias()` 的回退是 copy，不是 rename**（`dsh-attachment-local`，
   详见 §3.3）。它服务于**通用文件附件**（`saveFileVerbatim` / `saveFileStreamVerbatim`），
   语义是「给同一份内容再挂一个只读名字」。曾经的候选是「让引用路径也走 rename」，
   **已否决**：那会把内容寻址对象本身从对象库搬走，破坏其它引用。
   **代价是明摆着的**：回退路径下同一份字节占两份盘（`link` 可用时占一份）。
   这不是等价替换，是「可写优先」的有意识取舍。
   **仍未真机确认**：真实 FUSE / f2fs 上的复制行为、以及大文件（上限见 dsh 的附件限额）
   复制时的耗时都没在设备上量过；本机只验证了代码路径与 errno 分支。
2. **回退窗口内的并发覆盖**：见 §四。本次选择保语义、放弃 link 的内核级 no-replace。
3. **`createIfAbsent` 之外的写入路径未受影响**：覆盖分支本来就是 `rename`
   （Windows 上是 `replaceFile` + `rename` 兜底），本机与真机都不受影响。
4. **补丁与上游版本强耦合**：`pnpm-lock.yaml` 的 `patch_hash`、回归测试里的锚点
   （函数名、模块内私有标识符）都按 `0.1.5-rc.2` 写死。上游改这些名字时测试会失败——
   这是刻意的：它提示「补丁需要重新对齐」。
5. **真机实测数据来自父 agent 的登记册**：本文的「真机现象」一节引用登记册，
   我没有独立复现 PRoot 环境；本机复现的是**同一 errno 下的代码行为**
   （用 `link` 抛 `EACCES` 的桩驱动上游原码：`writeFileAtomic` →
   `FS_IO_ERROR: EACCES`；`publishStagedObject` → `ATTACHMENT_WRITE_FAILED: EACCES`），
   打补丁后两条路径都成功。
