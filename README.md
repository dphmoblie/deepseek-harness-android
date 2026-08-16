# DeepSeek Harness Android

[English](README.md) | [中文](README.zh.md)

`app/` is an independent Capacitor Android application for managing a local DeepSeek Harness Ubuntu userspace. It provides runtime installation and reset, an Ubuntu terminal, optional Shizuku-backed device shell access, settings, and an embedded loopback-only Harness Web UI.

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

The release build can embed `assets/runtime/runtime-manifest.json` and
`assets/runtime/rootfs.bundle`. The opaque `.bundle` file is a gzip-compressed
archive whose name prevents Android's asset packager from expanding it. The
current image recipe combines Ubuntu 24.04
ARM64, Node.js 24.19.0, and `@deepseek-ai/dsh` 0.1.0-rc.6. Generate those ignored
artifacts with `scripts/build-embedded-runtime.py`; the script verifies the
fixed Ubuntu and Node.js input digests, preserves Unix metadata, and emits the
archive metadata and SHA-256 used by the embedded manifest.

When no remote source is configured, installation uses the embedded manifest
and gzip rootfs and still verifies its declared byte length and SHA-256. A build
or an authenticated user can instead configure both
`DSH_RUNTIME_MANIFEST_URL` and `DSH_RUNTIME_MANIFEST_SHA256` (or their matching
settings fields). That pair selects a remote HTTPS manifest whose exact bytes
must match the pinned digest; the manifest then pins the HTTPS rootfs URL,
length, architecture, compression, and SHA-256. Supplying only one value is an
invalid configuration, not a fallback. Do not put API keys, passwords,
database credentials, signing passwords, or tokens in `.env`, Gradle files,
source code, manifests, URLs, or logs.

The native runner files are generated or imported separately and never
committed. A release APK packages both
`lib/arm64-v8a/libdsh_proot.so` and
`lib/arm64-v8a/libdsh_proot_loader.so`; both are required. The existing
`prepare:runner` flow remains available for a separately pinned runner source,
but it does not replace the provenance and license review for the exact two
binaries shipped in an APK.

The runtime manifest fields and security flow are documented in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The embedded manifest's reserved
`.invalid` archive URL is metadata only when the archive is read from the APK;
it must never be contacted. A remote release manifest must use real HTTPS URLs
and exact release digests. See
[docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).

## Security checkpoints

- Native bridge inputs have explicit type, length, format, and state validation.
- The management WebView is hidden until Android device-credential authentication succeeds and is locked again when the activity leaves the foreground.
- Embedded and downloaded artifacts require exact digests and byte limits;
  downloads additionally require HTTPS, staging files, and atomic promotion.
- Archive extraction prevents traversal and does not create device nodes.
- Harness binds only to Android loopback; no business service is exposed on `0.0.0.0`.
- Every Harness start generates a non-persistent 256-bit credential. A rootfs
  preload authenticates both HTTP and WebSocket upgrades before upstream
  handlers run, and only the device-authenticated internal WebView answers the
  Basic-auth challenge. The credential is never placed in the URL or audit log.
- Shizuku access requires a visible permission grant and a user-opened terminal session.
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
