# DeepSeek Harness for Android

[English](README.md) · [简体中文](README.zh-CN.md)

[![GitHub Release](https://img.shields.io/github/v/release/dphmoblie/deepseek-harness-android?logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![GitHub Downloads](https://img.shields.io/github/downloads/dphmoblie/deepseek-harness-android/total?logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![License](https://img.shields.io/github/license/dphmoblie/deepseek-harness-android)](LICENSE)
[![Last Commit](https://img.shields.io/github/last-commit/dphmoblie/deepseek-harness-android)](https://github.com/dphmoblie/deepseek-harness-android/commits)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/dphmoblie/deepseek-harness-android/pulls)

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![ABI arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-3DDC84?logo=arm&logoColor=white)](https://developer.android.com/ndk/guides/abis)
[![Ubuntu 24.04](https://img.shields.io/badge/Ubuntu%2024.04-E95420?logo=ubuntu&logoColor=white)](https://ubuntu.com/)
[![Node.js 24](https://img.shields.io/badge/Node.js%2024-5FA04E?logo=node.js&logoColor=white)](https://nodejs.org/)
[![PRoot](https://img.shields.io/badge/PRoot-userspace%20container-4EAA25)](https://github.com/proot-me/proot)
[![Capacitor 7](https://img.shields.io/badge/Capacitor%207-119EFC?logo=capacitor&logoColor=white)](https://capacitorjs.com/)
[![React 18](https://img.shields.io/badge/React%2018-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

**DeepSeek Harness for Android** runs the full [DeepSeek Harness](https://github.com/deepseek-ai/dsh) agent environment — an Ubuntu userspace, Node.js, and the official Harness web console — directly on an Android phone. No root is required: the complete Linux environment executes inside [PRoot](https://github.com/proot-me/proot), and Harness is served on Android loopback and displayed in a navigation-restricted internal WebView.

| | |
| --- | --- |
| Application package | `io.deepseekharness.mobile` |
| Current version | `0.1.10` |
| Minimum system | Android 8.0 (API 26) or newer |
| Architecture | `arm64-v8a` only |
| Embedded runtime | Ubuntu 24.04 ARM64 · Node.js 24.19 · `@deepseek-ai/dsh` 0.1.5-rc.2 |
| Application license | MIT (runtime components carry their own licenses — see [License](#license)) |

## Contents

- [Highlights](#highlights)
- [How it works](#how-it-works)
- [Installation](#installation)
- [Model providers](#model-providers)
- [Optional Shizuku integration](#optional-shizuku-integration)
- [Building from source](#building-from-source)
- [Security and privacy](#security-and-privacy)
- [Contributing](#contributing)
- [License](#license)
- [Related documentation](#related-documentation)

## Highlights

- **A complete Linux agent environment on your phone.** Ubuntu 24.04 runs on device through PRoot. There is no cloud server, no remote desktop, and no account sign-up: the agent runtime and its web console run locally.
- **Official Harness web console.** The app packages the official `dsh web` frontend, adapted only for mobile viewport sizing and safe areas. Desktop-oriented DSH web plugins load through the standard Harness plugin loader and receive mobile-friendly layouts.
- **Works without rooting.** PRoot provides userspace containment on stock devices. An optional [Shizuku](https://shizuku.rikka.app/) integration adds a shell-level device terminal (`/system/bin/sh`) when you choose to authorize it. Shizuku grants Android shell privileges — never root.
- **Self-contained and offline-capable.** The release APK embeds a verified `rootfs.bundle` plus a signed manifest, so the runtime can be installed with no network connection. Remote, digest-pinned runtime sources are also supported.
- **Tamper-resistant runtime delivery.** Every manifest and rootfs image is verified by exact length and SHA-256 before use; downloads only accept HTTPS destinations, reject private-address DNS answers, resume with HTTP range requests, and extract with path-traversal and device-node protections. Promotion to an active environment is atomic.
- **Built-in and custom model providers.** Credentials for DeepSeek, OpenAI, Anthropic, Google Gemini, OpenRouter, Groq, xAI, Mistral, and your own OpenAI-compatible endpoints are encrypted with the Android Keystore and injected only into the runtime process. They are never returned to the WebView.
- **Local-only by construction.** Harness binds exclusively to `127.0.0.1`. Each start generates a fresh 256-bit transport token that protects both HTTP and WebSocket requests; the token is held in process memory only and is never persisted or embedded in URLs.
- **Integrated terminals.** Use an Ubuntu terminal inside the PRoot environment and, optionally, a Shizuku-backed Android device terminal in the same interface.

## How it works

The application has three layers:

1. **Management surface (Capacitor + React).** A native Android shell for runtime installation, service control, model provider settings, terminals, runtime sources, and reset.
2. **Native runtime layer (Kotlin).** Validates and extracts the rootfs, manages the PRoot runner and loader shipped as native libraries, supervises the Harness process and PTY sessions, and optionally connects to a user-authorized Shizuku UserService.
3. **Ubuntu runtime (PRoot).** A fixed, allowlisted entrypoint starts `dsh web` on loopback inside Ubuntu 24.04. A Node.js preload enforces the per-start token before any request reaches Harness, and the internal WebView is restricted to that same loopback origin.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full architecture and security boundaries.

## Installation

1. Download the latest APK from the [Releases](https://github.com/dphmoblie/deepseek-harness-android/releases) page.
2. Install the APK (allow installation from the trusted source when prompted).
3. Open the app and wait for the embedded runtime to be read, verified, and installed — no internet connection is required for the official self-contained build.
4. Add a model provider and API key in **Settings → Model providers**, then start Harness.

When the runtime is ready, the app opens the Harness console directly and restores your most recent session.

### Requirements

- An Android 8.0+ device with an **arm64-v8a** (64-bit ARM) processor.
- Roughly several GB of free storage for the extracted Ubuntu environment.
- An API key for at least one supported model provider, or a compatible custom endpoint.

## Model providers

Built-in providers: **DeepSeek, OpenAI, Anthropic, Google Gemini, OpenRouter, Groq, xAI, Mistral**.

You can also configure any OpenAI-compatible endpoint as a custom provider (base URL, API key, and model list). Credentials are encrypted at rest with the Android Keystore and are injected into the Harness process environment only; saving a configuration restarts a running Harness so the runtime state always matches what is shown in the UI.

## Optional Shizuku integration

Shizuku is entirely optional and never bundled:

1. Install and start [Shizuku](https://shizuku.rikka.app/) (via wireless debugging or the standard Shizuku setup methods).
2. Grant the permission prompt inside the app, then use the explicit **Connect Shizuku** action.
3. This feature is currently in the testing phase and may be subject to potential defects.

If Shizuku is unavailable, unauthorized, or disconnected, device-terminal requests fail explicitly; the Ubuntu runtime and Harness are unaffected.

## Building from source

### Prerequisites

- Node.js `^22.19.0` or `>=24.0.0` with [pnpm](https://pnpm.io/) 11
- Android SDK 35, NDK, CMake 3.22.1, JDK 23, Gradle 8.11.1
- The release-pinned ARM64 PRoot runner and loader (`libdsh_proot.so`, `libdsh_proot_loader.so`) from the Operit2 Android runtime toolchain — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the exact upstream revision and hashes
- For the self-contained build: a `runtime-manifest.json` and `rootfs.bundle` generated from the matching source revision

### Web and Android build

```bash
pnpm install --frozen-lockfile
pnpm run build          # TypeScript check + Vite production build
pnpm run android:sync   # build and sync into the Android project
pnpm run android:open   # open in Android Studio, or build with Gradle
```

A development build may omit the bundled runtime and instead pin both
`DSH_RUNTIME_MANIFEST_URL` and `DSH_RUNTIME_MANIFEST_SHA256` to a remote
manifest. Full build instructions and the signing policy are documented in
[android/README.md](android/README.md).

### Checks

```bash
pnpm test          # Vitest unit tests
pnpm --dir scripts/runtime-profile install --frozen-lockfile --ignore-scripts # plugin test dependencies
pnpm run test:scripts
pnpm lint          # ESLint, zero warnings
```

## Security and privacy

- **Loopback only.** Harness never binds to a non-loopback interface; the internal WebView blocks navigation and HTTP resources outside the loopback origin.
- **Ephemeral transport credential.** A fresh 256-bit token generated with `SecureRandom` protects every Harness start. It is never persisted, logged, embedded in a URL, or returned to JavaScript.
- **Credential storage.** Provider API keys are encrypted with the Android Keystore and leave the management surface only as process environment variables for the PRoot runtime.
- **Verified runtime supply chain.** Manifests and rootfs images are schema-validated, digest-pinned, and extracted with strict archive boundaries; resumable downloads fail closed on malformed ranges or unexpected responses.
- **Audit trail.** Native audit records live in the app-private no-backup directory with owner-only file modes, rotate daily in UTC, and retain 90 days. Records contain only fixed event/result enums — never URLs, commands, tokens, or terminal data.
- **No login, no tracking.** The app has no accounts, no advertisements, and no telemetry.

## Contributing

Issues and pull requests are welcome at
<https://github.com/dphmoblie/deepseek-harness-android>.

When contributing, please keep changes scoped, add tests for new behavior, and
run `pnpm lint` and `pnpm test` before submitting. Security-sensitive changes
must preserve the boundaries described in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); in particular, never weaken
loopback enforcement, digest verification, entrypoint allowlists, or the
Shizuku UserService contract.

### Contributors

Thanks to everyone who has contributed to the project:

- [@standtrain](https://github.com/standtrain)
- [@11hyy](https://github.com/11hyy)

### Community

- **QQ group: 1108895375** — questions, feedback, and release announcements are welcome.

## License

The application code in this repository is released under the [MIT License](LICENSE).

The release APK additionally redistributes third-party runtime components under
their own licenses, including PRoot (GPL-2.0-or-later), Operit2 runtime
tooling (AGPL-3.0), Ubuntu 24.04 packages, Node.js, and the MIT-licensed
DeepSeek Harness runtime and frontend. Provenance, exact upstream revisions,
artifact hashes, and the corresponding license texts are recorded in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and inside the APK under
`assets/legal/`.

## Related documentation

- [Architecture and security boundaries](docs/ARCHITECTURE.md)
- [Mobile plugin compatibility design](docs/mobile-plugin-compat.md)
- [Release checklist](docs/RELEASE_CHECKLIST.md)
- [Android platform build notes](android/README.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
