# Android architecture

The APK combines a Capacitor management surface with a native Harness activity. The installed Ubuntu environment runs `dsh web` on Android loopback behind the packaged mobile-auth Node preload. The packaged frontend uses the official Harness distribution and its plugin loader, with a small Android stylesheet for safe areas and input sizing. There is one root frontend and no separate mobile conversation implementation. Once the runtime is ready, app startup opens `/` in the internal navigation-restricted WebView. Returning from the native Harness toolbar lands on Settings, where service, model provider credentials, runtime, terminal, reset, source, and Shizuku controls are grouped.

## Runtime installation

1. The official `0.1.9` workflow builds `rootfs.bundle` and
   `runtime-manifest.json`, packages the Android-adapted official frontend,
   and embeds both verified assets in the matching APK. The same files are
   published under the corresponding Release tag for inspection and explicit
   remote installation. The manifest records the archive length, SHA-256,
   architecture, compression, and runtime version.
2. Before Gradle builds the APK, CI verifies the finished manifest and copies
   it and the bundle into `app/src/main/assets/runtime/`. `.bak`, `.part`, and
   unrelated generated runtime files are rejected. `RuntimeStore` treats the
   embedded manifest as the default source, so an official APK needs no
   post-install source entry and can install while offline.
3. The manifest bytes must match the APK-pinned digest before parsing. The
   native layer validates schema, architecture, HTTPS/public-destination
   policy on every redirect, byte limits, gzip compression, entrypoint
   allowlists, and digest formats. Rootfs bytes are checked against the exact
   manifest length and SHA-256.
4. The UI advertises the embedded runtime as an update only when the installed
   and bundled manifests use the same runtime ID, the bundled version is a
   strictly newer bounded release, and the rootfs digest differs. A digest
   mismatch alone never replaces an explicitly configured newer remote runtime.
5. The rootfs download uses an app-private
   `rootfs-<manifest-rootfs-sha256>.part` file. The digest-derived name lets the
   same pinned artifact resume across process or app restarts. A resumed
   request must receive HTTP 206 with the exact start offset and total in
   `Content-Range`; a malformed range fails closed, while HTTP 200 (Range
   ignored) or HTTP 416 (stale range rejected) restarts the transfer from byte
   zero. Network, TLS, and timeout failures use fixed error codes and retain the
   bounded app-private partial for a later retry.
6. Only after download completion and digest verification does state advance
   through verification and extraction. Extraction rejects path traversal,
   device nodes, unsafe hard links,
   excessive entry counts, and extracted-size overflow. Symbolic links are
   created only after regular entries have been written.
7. A completed environment is atomically promoted. Reset never follows
   symbolic links and is limited to the app-private runtime directory.

`scripts/build-embedded-runtime.py` produces the gzip bundle and manifest
without checking generated artifacts into Git. `scripts/rebuild-rootfs-frontend.py`
replaces the Harness frontend transactionally and deletes its temporary `.bak`
files after verification so Android cannot package two rootfs copies. The
official `0.1.9` build is embedded and uses the same archive verification and
extraction boundaries as an explicitly configured remote build.
The PRoot-compatible runner and loader are executable native libraries and
must always be packaged in the APK because current Android versions do not
allow executing newly downloaded code from writable app storage. Generated
`.so` files are ignored by Git.

## Terminal and Harness

The Ubuntu terminal always starts a manifest-validated fixed entrypoint through PRoot. Terminal keystrokes are length-limited byte input to an existing process; they are never concatenated into a host shell command. Harness starts only on `127.0.0.1`. Each start receives a fresh 256-bit token through a fixed environment field; a Node preload removes the field after deriving a constant-time Basic-auth check and rejects unauthenticated HTTP and WebSocket upgrades before route dispatch. The token is held only in process memory. The non-exported internal WebView answers the HTTP Basic challenge transparently and also installs a JS-inaccessible, origin-scoped cookie before the first page load because WebView does not surface a Basic challenge for WebSocket upgrades. Neither credential is added to the URL. Neither direct conversation startup nor Settings invokes Android device-credential authentication.

## Background keep-alive and recovery

「后台保持 Harness」是一个显式开关（`keepRuntimeInBackground`，默认 `false`，旧配置缺键时同样按
`false` 处理）。开启且 Harness 由本进程成功启动后，应用启动一个 `specialUse` 前台服务
（`HarnessKeepAliveService`）并显示常驻通知，把本应用进程标记为前台服务；通知文案来自固定资源
字符串，不含 URL、端口、凭据、终端内容或会话标识。服务本身不执行 Shell 命令、不连接 Shizuku、
不持有任何凭据，也不承诺进程不会被系统或厂商策略结束。Android 13 及以上会在开关打开时申请
`POST_NOTIFICATIONS`；被拒绝时服务照常运行，只是不显示常驻通知。

`MobileRuntimeController` 的实际持有者是进程级 `RuntimeHost`，而不是 Capacitor 插件：插件的
`handleOnDestroy()`（划掉最近任务也会触发）只回收插件自有资源并注销事件订阅者，前台服务仍在
负责运行时时不调用 `shutdown`，因此 PRoot→node 的 Harness 子进程与内存中的临时会话凭据得以
保留；插件或服务都不再持有时才释放运行时，语义与旧实现一致。事件出口是可替换的
`RuntimeEventSink`，没有订阅者时事件被丢弃而不缓存。

恢复语义：`RuntimeStore` 只持久化运行意图（`running`/`stopped`/`unknown`）、最近阶段与时间，
不含凭据。应用进程被系统回收后，`RuntimeStatus` 不会把状态恢复成 `running`；
`RuntimeSupervisor.hasResidualHarness()` 以只读方式（pid 文件 + `/proc/<pid>/cmdline` 必须匹配
受信任运行器路径）判断是否存在无法复用的残留进程。只要本进程未持有正在运行的 Harness，且
检测到残留进程或上次意图为运行中，界面就显示「需要重新连接」，由用户显式重启一个新的会话。
前台服务在没有可管理运行时时立即结束，避免留下无法解释的通知。逐项行为与限制见
`docs/后台保持与恢复.md`，真机验收步骤见 `docs/mobile-acceptance-checklist.md` 的 5.1 小节。

## Shizuku

Shizuku is optional and user-authorized. The app declares the official
`rikka.shizuku.ShizukuProvider` and API dependencies but does not bundle the
Shizuku APK. The runtime listens for binder availability and death, binds a
non-daemon UserService after permission is granted, and invalidates terminal
sessions when either binder layer dies. Authorization and connection are
separate visible states: after permission, Settings and the device-terminal
empty state expose an explicit Connect Shizuku action until the UserService is
live. Automatic reconnect is best effort; a failed attempt never marks the
terminal ready. The public `connected` state is true only when Shizuku is
running, permission is granted, and that UserService binder is alive. Device
sessions then start a fixed `/system/bin/sh`. The Capacitor bridge cannot
choose another executable, add process arguments, or run a background command
without an open user-visible terminal session. Shizuku supplies shell-level
privileges, not root or Android hardware virtualization. `healthCheck()` is a
read-only snapshot used for degraded-mode decisions and the background
keep-alive status line: it never throws, never runs a command, and never logs,
so it cannot leak credentials or command arguments. Shizuku never influences
keep-alive decisions — the foreground service does not depend on it.

容器无法直接访问 Android Binder。`dsh-device` 使用 Harness 启动时注入的随机回环端口和进程级临时令牌请求宿主桥；宿主桥只接受有界的固定命令类型，并通过已授权的 Shizuku UserService PTY 执行。令牌不持久化，不写入 URL 或日志。Shizuku 不可用、未授权或 UserService 断开时请求明确失败。

## Operit2 runtime boundary

The Capacitor bridge, download verifier, extractor, PTY wrapper, and fixed
Shizuku UserService contract in this repository remain independently
implemented. The release-native PRoot artifacts are a separate boundary: the
APK packages `libdsh_proot.so` and `libdsh_proot_loader.so` obtained from the
Operit2 Android runtime toolchain at commit
`dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`, under
`tools/android-runtime/`. The runner is used through app-private links to the
APK native libraries, with `PROOT_LOADER` pointing to the corresponding trusted
loader link. The app probes the runner and guest before use, retries with the
no-seccomp profile when required, and enables only validated bind mounts that
the device accepts. Runtime execution still originates from
the loader in Android's native library directory; it is not copied to and
executed from writable storage.

PRoot is GPL-2.0-or-later. Operit2 is AGPL-3.0. Release provenance must retain
the exact upstream revision, the hashes of both shipped ELF files, all local
patches, and usable build/source instructions. Distribution must include the
applicable license texts and make complete corresponding source available by a
method allowed by those licenses. Recording the commit and hashes is necessary
but is not, by itself, corresponding source. The current import record does
not assert that the shipped binaries can be rebuilt bit-for-bit.

## Secrets and logs

Built-in and custom provider credentials entered in the management UI are encrypted with Android Keystore and only injected into the PRoot process environment. Credential values never return to the WebView. Saving model configuration restarts a running Harness before reporting success so the generated Cordis overlay and environment agree with the displayed state. The management surface opens directly and does not use Android device-credential authentication. The ephemeral Harness transport credential is generated with `SecureRandom`, never persisted, never returned to JavaScript, and supplied to the internal WebView without a user-facing prompt. The WebView-side reference is cleared when the internal WebView stops, while the server-side reference is cleared when the Harness process stops. Signing material, local Gradle properties, generated rootfs archives, generated manifests, native runners, `.env` files, build output, and logs are ignored by Git.

Native audit files live in `noBackupFilesDir`, use owner-only directory/file modes, rotate by UTC date, and retain the 90-day boundary plus newer files. Each line contains only an ISO timestamp, a fixed event enum, and a fixed result enum. URLs, commands, session identifiers, terminal data, credentials, and exception details are never written.
