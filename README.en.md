# DeepSeek Harness for Android

[简体中文](README.md) · [繁體中文](README.zh-TW.md) · [한국어](README.ko.md) · [日本語](README.ja.md)

[![Latest release](https://img.shields.io/github/v/release/dphmoblie/deepseek-harness-android?label=release&logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![Total downloads](https://img.shields.io/github/downloads/dphmoblie/deepseek-harness-android/total?label=downloads&logo=github)](https://github.com/dphmoblie/deepseek-harness-android/releases)
[![License](https://img.shields.io/github/license/dphmoblie/deepseek-harness-android?label=license)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/dphmoblie/deepseek-harness-android?label=last%20commit)](https://github.com/dphmoblie/deepseek-harness-android/commits)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/dphmoblie/deepseek-harness-android/pulls)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-3DDC84?logo=arm&logoColor=white)](https://developer.android.com/ndk/guides/abis)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

<p align="center"><img src="docs/images/app-icon-512.png" width="256" alt="DeepSeek Harness for Android app icon"></p>

**A local AI workbench for Android.** It brings [DeepSeek Harness](https://github.com/deepseek-ai/dsh), an Ubuntu runtime, sessions, plugins and file management onto your phone. Once you grant the relevant permissions, the AI can also run device shell commands through Shizuku or operate selected apps through the accessibility service.

No root required. The Linux environment runs in userspace through [PRoot](https://github.com/proot-me/proot) and the console is rendered by a bundled web view; model inference uses whatever service you configure.

<a id="qq-group"></a><a id="community-qq-group"></a>**Project QQ group:** `1108895375` — for usage questions, feedback and development discussion.

| | |
| --- | --- |
| App package name | `io.deepseekharness.mobile` |
| Minimum Android | Android 8.0 (API 26) or later |
| Target-app virtual screen | Android 10 (API 29) or later; needs Shizuku and per-device verification |
| Supported architecture | `arm64-v8a` only (64-bit ARM) |
| Bundled runtime | Ubuntu 24.04 ARM64 · Node.js 24.19 · `@deepseek-ai/dsh` 0.2.0-rc.2 |
| App license | MIT (bundled runtime components keep their own licenses — see [License](#license)) |

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Installation](#installation)
- [Model providers](#model-providers)
- [Optional Shizuku integration](#optional-shizuku-integration)
- [Target-app virtual screen (experimental)](#target-app-virtual-screen-experimental)
- [Building from source](#building-from-source)
- [Security and privacy](#security-and-privacy)
- [Contributing](#contributing)
- [License](#license)
- [Related documents](#related-documents)

## Features

- **A complete Linux agent environment on your phone.** Ubuntu 24.04 runs entirely on-device through PRoot — no cloud server, no remote desktop, no account: both the agent runtime and the web console execute locally.
- **A console adapted to mobile.** The shell organizes everything under three bottom tabs — Home, Plugins and Settings — with layout adaptation for narrow screens, landscape and safe areas; session management is merged into Home. Third-party web plugins differ in UI and dependencies, so compatibility must be verified per plugin and version.
- **Plugin import, update and rollback.** Import from an npm package name, an HTTPS archive URL or a `git+https` repository address, and enable/disable packages and sub-plugins. Updates preserve data under supported directory rules, and you can switch back to the previous version. Packages that need install scripts, native compilation or a different core SDK have limitations — see [plugin management](docs/插件管理.md).
- **File and appearance management.** The inbox supports folder categories; the shell offers image or video backgrounds with adjustable card color, transparency, blur and accent color.
- **Runs without root.** Userspace containerization through PRoot works on ordinary retail devices. The optional [Shizuku](https://shizuku.rikka.app/) integration additionally provides a shell-level device terminal (`/system/bin/sh`) after you grant it yourself. Shizuku grants Android shell privileges, not root.
- **Works out of the box, installs offline.** Official APKs bundle a verified `rootfs.bundle` and manifest, so runtime installation completes without a network; digest-pinned remote runtime sources are also supported.
- **Tamper-resistant runtime distribution.** Every manifest and root filesystem image is checked against an exact length and SHA-256 before use; downloads accept only HTTPS targets, reject DNS results pointing at private addresses, support HTTP range resumption, and extraction defends against path traversal and device nodes. A ready environment is switched into place atomically.
- **Built-in and custom model providers.** Credentials for DeepSeek, OpenAI, Anthropic, Google Gemini, OpenRouter, Groq, xAI, Mistral and self-hosted OpenAI-compatible endpoints are encrypted with the Android Keystore and injected only into the runtime process — never returned to the WebView.
- **Local console access.** Harness binds only to `127.0.0.1`. A fresh 256-bit transport token is generated on every start and protects both HTTP and WebSocket requests; the token lives only in process memory, is never persisted and never written into a URL. Model calls and user-initiated downloads or plugin installs may still reach the network.
- **Integrated terminals.** Use the Ubuntu terminal inside the PRoot environment and, optionally, a Shizuku-backed Android device terminal from the same screen.
- **In-app runtime self-check.** When the runtime misbehaves you don't need bash: the self-check probes the shell, Node.js, the sandbox launcher (including its execute bit), Landlock probing, real in-sandbox execution, two PTY smoke tests (bare PTY and in-sandbox PTY), writes to the guest data and attachment directories, the ripgrep execute bit, and reports free space. Missing execute bits or directories can be repaired in place without modifying any file contents.
- **Logs on demand, with interpretation.** Diagnostic logs containing only internal status codes and counters can be read in-app (64 / 256 KB tail windows); the runtime log window offers 8 / 64 / 256 KB with keyword filtering and level coloring. When a known failure signature matches (missing credentials, split module identity, plugin load failure, occupied port, and so on), the UI states the conclusion and the next step instead of dropping raw text on you.
- **Background keep-alive and floating ball (optional).** A foreground service raises the runtime process's priority for surviving in the background — but **cannot prevent** the system from ending it under memory, battery or vendor policies. A short tap on the floating ball expands a draggable AI chat mini window, a long press shows the menu, and the ball's position is persisted and pulled back into view after a screen rotation.
- **First-run configuration gate.** While no model credential is stored locally, Harness won't open (conversations are guaranteed to fail without a key) and the app routes you straight to "Models & keys" — while keeping an explicit "I already configured it inside Harness, open anyway" override.

## How it works

The app has three layers:

1. **Management UI (Capacitor + React).** The native Android shell, responsible for runtime installation, service control, model provider settings, terminals, runtime sources and environment reset.
2. **Native runtime layer (Kotlin).** Verifies and extracts the root filesystem, manages the PRoot runner and loader shipped as native libraries, supervises the Harness process and PTY sessions, and connects to the Shizuku UserService once you authorize it.
3. **Ubuntu runtime (PRoot).** Starts `dsh web` inside Ubuntu 24.04 through a fixed allowlisted entry point, listening on loopback only. A Node.js preload module validates the launch token before any request reaches Harness, and the bundled WebView is confined to the same loopback origin.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full architecture and security boundaries.

## Installation

1. Download the latest APK from the [Releases](https://github.com/dphmoblie/deepseek-harness-android/releases) page and read that version's release notes.
2. Install the APK (allow installation from a trusted source when prompted).
3. Open the app and wait for the bundled runtime to be read, verified and installed — official self-contained builds need no network.
4. Add a model provider and API key under **Settings → Models & keys**, then start Harness.

Once the runtime is ready, the app opens the Harness console directly and restores the most recent session.

### Requirements

- Android 8.0 or later on an **arm64-v8a** (64-bit ARM) device.
- A few GB of free storage for the extracted Ubuntu environment.
- An API key for at least one supported model provider, or a compatible custom endpoint.

## Model providers

Built-in providers: **DeepSeek, OpenAI, Anthropic, Google Gemini, OpenRouter, Groq, xAI, Mistral**.

Any OpenAI-compatible endpoint can also be configured as a custom provider (base URL, API key and model list). Credentials are encrypted at rest with the Android Keystore and injected into the Harness runtime only as process environment variables; saving settings while Harness is running restarts it automatically so the runtime state always matches what the UI shows.

## Optional Shizuku integration

Shizuku is entirely optional and is never bundled with the app:

1. Install and start [Shizuku](https://shizuku.rikka.app/) yourself (via wireless debugging or however Shizuku's own documentation directs).
2. Grant permission inside the app, then tap the explicit **Connect Shizuku** action.
3. After connecting under "Settings → Shizuku & device automation", turn on "Allow AI to call the shell". One switch stays in effect until you turn it off; there is no per-command confirmation.

Once enabled, the model can use the device shell to read, write, create directories, upload and download files, and to query the processes, services and activity summaries of a given app. The shell uses Shizuku's actual Android privileges and is not equivalent to root; command bodies and output are never written to diagnostic logs. Do not let the model read or echo secrets, SMS messages, contacts, tokens or similar private data.

Accessibility automation requires you to enable the service manually in system settings, then pick target package names from the installed-app list in the app and save a whitelist. There is no cap on whitelist size; ordinary vendor apps can be added, while system settings, permission, payment, verification-code and password screens are still refused by the native service. The service cannot bypass the lock screen and cannot guarantee continuous operation under vendor background policies.

When Shizuku is unavailable, unauthorized or disconnected, device tools report a clear error; the Ubuntu runtime and Harness are unaffected.

## Target-app virtual screen (experimental)

Under **Settings → AI Shell → Target-app virtual screen (experimental)** you can pick a target app, try running it on a separate virtual display, and view and interact with it through the native preview page or a draggable mini window. What you see there is the target app's screen; the AI chat mini window is a separate feature.

The entry point requires Android 10 or later, an authorized and connected Shizuku, and AI Shell enabled by the user. Reading and input pause while the screen is locked or off. AI tools for virtual-screen state, PNG screenshots, tap, swipe, back and ending the session are already wired in; **there is no virtual-screen-specific accessibility node tree, Chinese text input or high-frame-rate video stream yet.**

As of 2026-10-02, portrait and landscape screenshots, targeted taps and session teardown for a test app were verified under MuMu Android 15 with ordinary shell privileges, along with native picker layouts at `363×800 dp` and `800×363 dp`. **The full Shizuku authorization and binding path, real third-party apps, mini-window switching, long-lived background operation and lock-screen recovery remain unverified**; emulator probe results are not a compatibility guarantee for every phone or every app.

An existing runtime also needs its `dsh-mobile-shizuku` plugin updated or repackaged before the new AI tools appear; installing a new APK alone does not rewrite existing plugin data. See the [virtual screen guide and verification record](docs/目标应用副屏.md) for the mechanism, tool interfaces and full verification scope.

## Building from source

### Build dependencies

- Node.js `^22.19.0` or `>=24.0.0`, plus [pnpm](https://pnpm.io/) 11
- Android SDK 35, NDK, CMake 3.22.1, JDK 21, Gradle 8.11.1
- The ARM64 PRoot runner and loader pinned to the release version from the Operit2 Android runtime toolchain (`libdsh_proot.so`, `libdsh_proot_loader.so`) — exact upstream versions and hashes are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
- Self-contained builds additionally need `runtime-manifest.json` and `rootfs.bundle` generated from the same source version

### Web and Android builds

```bash
pnpm install --frozen-lockfile
pnpm run build          # TypeScript check + Vite production build
pnpm run android:sync   # build and sync into the Android project
pnpm run android:open   # open in Android Studio, or build directly with Gradle
```

Development builds can skip the bundled runtime and instead pin a remote manifest by
setting both `DSH_RUNTIME_MANIFEST_URL` and `DSH_RUNTIME_MANIFEST_SHA256`. See
[android/README.md](android/README.md) for the full build guide and signing policy.

### Checks and tests

```bash
pnpm test          # Vitest unit tests
pnpm --dir scripts/runtime-profile install --frozen-lockfile --ignore-scripts # plugin test dependencies
pnpm run test:scripts
pnpm lint          # ESLint, must pass with zero warnings
```

## Security and privacy

- **Where model data goes.** The local console is not an offline model. Conversations, file contents returned by AI tools and screenshots may enter the current session and be sent to the configured model service; local virtual-screen preview itself uploads no images. Grant only the apps and data a task actually needs.
- **Loopback only.** Harness never binds a non-loopback network interface, and the bundled WebView blocks navigation and HTTP resources outside the loopback origin.
- **Ephemeral transport credential.** Every Harness start generates a fresh 256-bit token via `SecureRandom`. The token is never persisted, never written to logs or URLs, and never handed back to JavaScript.
- **Credential storage.** Provider API keys are encrypted with the Android Keystore and exist only as process environment variables of the PRoot runtime once they leave the management UI.
- **Verifiable runtime supply chain.** Manifests and root filesystem images pass schema validation and are digest-pinned, and extraction performs strict archive boundary checks; range resumption on an illegal range or abnormal response fails closed.
- **Audit records.** Native audit logs live in a backup-excluded private app directory, readable and writable only by their owner, rotated by UTC date and retained for 90 days. Entries contain only fixed event/result enumerations — never URLs, commands, tokens or terminal data.
- **No login, no tracking.** The app has no account, no ads and collects no telemetry.

## Contributing

Issues and pull requests are welcome at <https://github.com/dphmoblie/deepseek-harness-android>.

Keep changes focused, add tests for new behavior, and run `pnpm lint` and `pnpm test` before submitting.
Changes touching security must preserve the boundaries described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) —
in particular, do not weaken loopback access control, digest verification, the entry-point allowlist or the Shizuku UserService contract.

### Contributors

[![Contributors](https://contrib.rocks/image?repo=dphmoblie/deepseek-harness-android)](https://github.com/dphmoblie/deepseek-harness-android/graphs/contributors)

More developers are welcome — your name can appear here too.

### Community

- **QQ group: 1108895375** — ask questions, send feedback and get release notifications.

## License

The application code in this repository is released under the [MIT license](LICENSE).

Official APKs additionally redistribute third-party runtime components under their own licenses, including PRoot
(GPL-2.0-or-later), the Operit2 runtime toolchain (AGPL-3.0), Ubuntu 24.04
packages, Node.js, and the MIT-licensed DeepSeek Harness runtime and frontend.
Component sources, exact upstream versions, artifact hashes and the corresponding license texts are recorded in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and shipped inside the APK's
`assets/legal/` directory.

## Related documents

- [Target-app virtual screen and verification scope](docs/目标应用副屏.md)
- [App reading and accessibility automation](docs/应用自动化能力.md)
- [Plugin import, data retention and rollback](docs/插件管理.md)
- [Inbox and storage permissions](docs/存储权限与导入落点.md)
- [Architecture and security boundaries](docs/ARCHITECTURE.md)
- [Mobile plugin compatibility design](docs/mobile-plugin-compat.md)
- [Release checklist](docs/RELEASE_CHECKLIST.md)
- [Android build guide](android/README.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
