# DeepSeek Harness Android

[English](README.md) | [中文](README.zh.md)

`app/` is an independent Capacitor Android application for running DeepSeek Harness in a local Ubuntu userspace. Once the runtime is ready, opening the app starts Harness and enters the in-app conversation directly; no external browser is required. Harness service controls, Ubuntu installation and reset, terminals, runtime source details, and optional Shizuku-backed device shell access live under Settings.

## Build requirements

- Node.js `^22.19.0 || >=24.0.0`, matching the current DeepSeek Harness engine range. Node.js 11.9 cannot build supported Capacitor releases or the current DeepSeek Harness upstream.
- JDK 23.0.1, with `JAVA_HOME` set explicitly when the system default still points to Java 8.
- Android SDK 35 and a compatible Android NDK.
- The pinned ARM64 PRoot runner and loader used by the release. The current
  release artifacts come from the Operit2 Android runtime toolchain at commit
  `dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`.

The Android WebView does not run Node.js. The installed Ubuntu environment must contain Node.js in the exact supported range `^22.19.0 || >=24.0.0`; Node.js 23 is not supported by the current Harness.

## Local workflow

```powershell
pnpm install --frozen-lockfile
pnpm run build
pnpm run android:sync
```

The `0.1.8` CI release is a self-contained ARM64 APK. The workflow builds the
mobile Harness conversation frontend, injects it into an Ubuntu 24.04 ARM64
image containing Node.js 24.19.0 and `@deepseek-ai/dsh` 0.1.0-rc.6, then embeds
the verified `rootfs.bundle` and `runtime-manifest.json` in the matching APK.
The same runtime files are also published as separate Release assets for
inspection and explicitly configured remote installation. Installing the
official APK therefore works offline and does not require entering a manifest
URL or digest.

The bundled manifest pins the rootfs byte length, architecture, compression,
and SHA-256. Embedded installation verifies those values before extraction.
When a remote source is explicitly configured, its manifest digest and HTTPS
destination are validated as well; archives use an app-private
`rootfs-<sha256>.part` file so interrupted transfers can resume across app or
process restarts. A resumed response must be HTTP 206 with the exact expected
`Content-Range`; HTTP 200 or 416 causes a clean restart from byte zero. Network,
TLS, timeout, and incomplete-transfer failures enter an explicit error state
while retaining a valid bounded partial file. The UI does not report
extraction until acquisition and archive verification have completed.

`scripts/build-embedded-runtime.py` remains the deterministic image builder
used by CI and also supports an explicitly constructed embedded development
build. Embedded and remote acquisition share the same size, digest,
extraction, and atomic-promotion checks. Do not put API keys, passwords,
database credentials, signing passwords, or tokens in `.env`, Gradle files,
source code, manifests, URLs, or logs.

The packaged `/` route is the mobile conversation UI, with sessions, tasks,
files, model and reasoning controls, agent presets, public Harness settings,
and plugin lifecycle management. The former desktop landing page is not
packaged as another entry point. Third-party Cordis plugin pages can be opened
from Settings through an on-demand compatibility workbench whose assets are
not loaded during normal conversation use.

The native runner files are generated or imported separately and never
committed. A release APK packages both
`lib/arm64-v8a/libdsh_proot.so` and
`lib/arm64-v8a/libdsh_proot_loader.so`; both are required. The existing
`prepare:runner` flow remains available for a separately pinned runner source,
but it does not replace the provenance and license review for the exact two
binaries shipped in an APK.

The runtime manifest fields, CI pinning flow, and security boundaries are
documented in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). See
[docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) before distributing an
APK or runtime asset.

## Security checkpoints

- Native bridge inputs have explicit type, length, format, and state validation.
- The app has no user-facing login or Android device-credential gate. The
  conversation opens directly, while management operations remain in the
  app's Settings surface. This does not weaken the separate loopback transport
  credential described below.
- Embedded and downloaded artifacts require exact digests and byte limits;
  downloads additionally require HTTPS, resumable digest-named staging files,
  strict Range-response validation, and atomic promotion.
- Archive extraction prevents traversal and does not create device nodes. The
  exact compressed stream consumed by the extractor is counted and hashed
  again before promotion, independently enforcing the manifest's compressed
  size and SHA-256 during decompression.
- Before launching Ubuntu, the app probes the packaged PRoot runner and its
  seccomp compatibility, then requires individually validated bind mounts for
  the generated resolver file, `/dev`, and `/proc`. Startup fails closed if a
  required source, guest target, or compatibility probe is unavailable.
- Harness binds only to Android loopback; no business service is exposed on `0.0.0.0`.
- Every Harness start generates a non-persistent 256-bit credential. A rootfs
  preload authenticates both HTTP and WebSocket upgrades before upstream
  handlers run, and the non-exported internal WebView answers the Basic-auth
  challenge transparently. An open TCP port alone is not considered ready: two
  loopback probes separated by a stability interval must return HTTP 401 with
  the exact expected Basic realm and UTF-8 challenge. The credential is never
  placed in the URL or audit log.
- Shizuku access requires the declared `ShizukuProvider`, a visible permission
  grant, an explicit successful UserService connection, and a user-opened
  terminal session. Settings exposes a Connect Shizuku action whenever
  permission exists but the service is disconnected. Binder or UserService
  loss clears active sessions, and `connected` is true only while a live
  authorized UserService binder is available.
- Reset is confined to the app-private runtime root and does not follow symbolic links.
- Owner-only audit files rotate daily and retain at least 90 days of fixed event/result codes.
- No credential, URL, command, session identifier, terminal content, or sensitive user data is written to application audit files.

## Licensing

The original application source is MIT licensed. That does not replace or
weaken the licenses of packaged runtime components. Direct dependency and
runtime redistribution obligations are summarized in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). In particular, the PRoot
runner is GPL-2.0-or-later and the referenced Operit2 source/build material is
AGPL-3.0. A distributor must provide the applicable license texts, complete
corresponding source for the exact shipped artifacts (including modifications
and the scripts needed to build them), and clear source-acquisition
instructions for as long as the applicable licenses require. The current
provenance record identifies a source revision and binary digests; it does not
claim a bit-for-bit reproducible rebuild.
