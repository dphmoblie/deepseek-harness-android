# DeepSeek Harness Android

`app/` is an independent Capacitor Android application for managing a local DeepSeek Harness Ubuntu userspace. It provides runtime installation and reset, an Ubuntu terminal, optional Shizuku-backed device shell access, settings, and an embedded loopback-only Harness Web UI.

## Build requirements

- Node.js `^22.19.0 || >=24.0.0`, matching the current DeepSeek Harness engine range. Node.js 11.9 cannot build supported Capacitor releases or the current DeepSeek Harness upstream.
- JDK 23.0.1, with `JAVA_HOME` set explicitly when the system default still points to Java 8.
- Android SDK 35 and a compatible Android NDK.
- An arm64 PRoot-compatible runner prepared with `pnpm run prepare:runner`.

The Android WebView does not run Node.js. The downloaded Ubuntu environment must contain Node.js in the exact supported range `^22.19.0 || >=24.0.0`; Node.js 23 is not supported by the current Harness.

## Local workflow

```powershell
pnpm install --frozen-lockfile
pnpm run build
pnpm run android:sync
```

Set `DSH_RUNTIME_MANIFEST_URL` and `DSH_RUNTIME_MANIFEST_SHA256` at Android build time, or configure a custom HTTPS source and digest in the app. Do not put API keys, passwords, database credentials, signing passwords, or tokens in `.env`, Gradle files, source code, example manifests, or logs.

The native PRoot runner is imported separately and never committed. Before `pnpm run prepare:runner`, provide `DSH_PROOT_ARM64_URL`, `DSH_PROOT_ARM64_SHA256`, `DSH_PROOT_ARM64_BYTES`, and a comma-separated `DSH_RUNNER_ALLOWED_HOSTS`. The script rejects URL credentials, unapproved redirect hosts, byte mismatches, digest mismatches, oversized artifacts, and non-ARM64 ELF files. Distributions must also include the selected runner's license and corresponding-source offer where its license requires them.

The runtime manifest fields and security flow are documented in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The example manifest intentionally uses the reserved `.invalid` domain and a nonfunctional digest. A release build must supply its own hosted, digest-pinned runtime and runner; see [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).

## Security checkpoints

- Native bridge inputs have explicit type, length, format, and state validation.
- The management WebView is hidden until Android device-credential authentication succeeds and is locked again when the activity leaves the foreground.
- Downloads require HTTPS, exact digests, byte limits, staging files, and atomic promotion.
- Archive extraction prevents traversal and does not create device nodes.
- Harness binds only to Android loopback; no business service is exposed on `0.0.0.0`.
- Loopback binding is a reachability restriction, not client authentication. Production distribution is gated on an authenticated Harness transport or equivalent upstream support.
- Shizuku access requires a visible permission grant and a user-opened terminal session.
- Reset is confined to the app-private runtime root and does not follow symbolic links.
- Owner-only audit files rotate daily and retain at least 90 days of fixed event/result codes.
- No credential, URL, command, session identifier, terminal content, or sensitive user data is written to application audit files.

## Licensing

The application source is MIT licensed. Direct dependency and runtime redistribution obligations are summarized in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The PRoot runner and prepared Ubuntu rootfs are imported separately and must carry their own license texts and corresponding-source obligations.
