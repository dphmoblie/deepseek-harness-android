# Android architecture

The APK combines a Capacitor management surface with a native Harness activity. The installed Ubuntu environment runs `dsh web` on Android loopback behind the packaged mobile-auth Node preload. During packaging, the original web distribution is removed and replaced by the purpose-built mobile Harness conversation frontend, including same-origin RPC, WebSocket events, sessions, tasks, files, skills, agent presets, model controls, and reasoning effort. Once the runtime is ready, app startup starts Harness and opens that frontend at `/` in the internal navigation-restricted WebView. Returning from the native Harness toolbar lands on Settings, where service, runtime, terminal, reset, source, and Shizuku controls are grouped.

## Runtime installation

1. The official `0.1.8` workflow builds `rootfs.bundle` and
   `runtime-manifest.json`, injects the built mobile conversation frontend,
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
4. The rootfs download uses an app-private
   `rootfs-<manifest-rootfs-sha256>.part` file. The digest-derived name lets the
   same pinned artifact resume across process or app restarts. A resumed
   request must receive HTTP 206 with the exact start offset and total in
   `Content-Range`; a malformed range fails closed, while HTTP 200 (Range
   ignored) or HTTP 416 (stale range rejected) restarts the transfer from byte
   zero. Network, TLS, and timeout failures use fixed error codes and retain the
   bounded app-private partial for a later retry.
5. Only after download completion and digest verification does state advance
   through verification and extraction. Extraction rejects path traversal,
   device nodes, unsafe hard links,
   excessive entry counts, and extracted-size overflow. Symbolic links are
   created only after regular entries have been written.
6. A completed environment is atomically promoted. Reset never follows
   symbolic links and is limited to the app-private runtime directory.

`scripts/build-embedded-runtime.py` produces the gzip bundle and manifest
without checking generated artifacts into Git. `scripts/rebuild-rootfs-frontend.py`
replaces the Harness frontend transactionally and deletes its temporary `.bak`
files after verification so Android cannot package two rootfs copies. The
official `0.1.8` build is embedded and uses the same archive verification and
extraction boundaries as an explicitly configured remote build.
The PRoot-compatible runner and loader are executable native libraries and
must always be packaged in the APK because current Android versions do not
allow executing newly downloaded code from writable app storage. Generated
`.so` files are ignored by Git.

## Terminal and Harness

The Ubuntu terminal always starts a manifest-validated fixed entrypoint through PRoot. Terminal keystrokes are length-limited byte input to an existing process; they are never concatenated into a host shell command. Harness starts only on `127.0.0.1`. Each start receives a fresh 256-bit token through a fixed environment field; a Node preload removes the field after deriving a constant-time Basic-auth check and rejects unauthenticated HTTP and WebSocket upgrades before route dispatch. The token is held only in process memory. The non-exported internal WebView answers the HTTP Basic challenge transparently and also installs a JS-inaccessible, origin-scoped cookie before the first page load because WebView does not surface a Basic challenge for WebSocket upgrades. Neither credential is added to the URL. Neither direct conversation startup nor Settings invokes Android device-credential authentication.

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
privileges, not root or Android hardware virtualization.

一次性特权设备自动化（screencap/uiautomator/input 等）由 `ReflectiveShellExecutor`
经反射调用 shizuku-api 13.1.5 私有 `Shizuku.newProcess` 实现，与 UserService PTY 终端互补；
Shizuku 不可用、未授权或反射失败时一律失效即关（fail-closed）。
详见 `docs/legacy/v2-bridge.md`（历史协议留档与反射依据）。

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

DeepSeek API credentials remain inside the Harness credential flow and are not handled by the Capacitor management UI. The management surface opens directly and does not use Android device-credential authentication. The ephemeral Harness transport credential is generated with `SecureRandom`, never persisted, never returned to JavaScript, and supplied to the internal WebView without a user-facing prompt. The WebView-side reference is cleared when the internal WebView stops, while the server-side reference is cleared when the Harness process stops. Signing material, local Gradle properties, generated rootfs archives, generated manifests, native runners, `.env` files, build output, and logs are ignored by Git.

Native audit files live in `noBackupFilesDir`, use owner-only directory/file modes, rotate by UTC date, and retain the 90-day boundary plus newer files. Each line contains only an ISO timestamp, a fixed event enum, and a fixed result enum. URLs, commands, session identifiers, terminal data, credentials, and exception details are never written.
