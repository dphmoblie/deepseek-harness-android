# Android native runtime

This directory is the Capacitor 7 Android platform for the DeepSeek Harness mobile control surface. It targets API 35, requires Android 8.0 or newer, and packages only `arm64-v8a`.

## Build

1. Install the JavaScript dependencies from `app/` with Node.js `^22.19.0 || >=24.0.0` and `pnpm install --frozen-lockfile`.
2. Import the release-pinned ARM64 PRoot runner and loader as `app/src/main/jniLibs/arm64-v8a/libdsh_proot.so` and `libdsh_proot_loader.so`. Both generated files are intentionally ignored by Git.
3. Generate the ignored `app/src/main/assets/runtime/runtime-manifest.json` and `rootfs.bundle` assets. The bundle is gzip-compressed despite its AAPT-safe opaque suffix. Leaving the runtime source settings empty uses these assets; setting both `DSH_RUNTIME_MANIFEST_URL` and `DSH_RUNTIME_MANIFEST_SHA256` selects a digest-pinned remote replacement.
4. Run `pnpm run android:sync`, then build with Android SDK 35, NDK, CMake 3.22.1, JDK 23, and Gradle 8.11.1.

The Gradle wrapper JAR and Capacitor-generated files are not committed. Generate the wrapper with a trusted Gradle 8.11.1 installation when preparing a clean build machine. Do not store signing passwords, download credentials, API keys, database credentials, or tokens in Gradle files or `local.properties`.

## Runtime boundaries

- `MobileRuntimePlugin` is the only JavaScript bridge. Every bridge input is validated again natively.
- `MainActivity` opens the management WebView directly without Android device-credential authentication. It retains `FLAG_SECURE`; `HarnessActivity` is not exported and closes when backgrounded.
- `RuntimeInstaller` verifies the embedded archive's declared size and SHA-256. Remote replacements accept only digest-pinned HTTPS manifests and archives. Pinned DNS results reject every private, loopback, link-local, ULA, unspecified, or multicast A/AAAA answer before those same addresses are used for the connection. Remote transfers persist only the matching app-private `rootfs-<sha256>.part` file across restarts and resume with Range. A resume must return HTTP 206 and an exact `Content-Range`; HTTP 200 or 416 triggers a clean download from byte zero. Network, TLS, and timeout failures enter an explicit error state and retain the bounded app-private partial. Installation otherwise uses random app-private staging paths, no-follow/create-new file operations, fixed size limits, and atomic renames.
- Absolute guest symlinks are rewritten as relative links inside the staged root. Relative links that normalize outside it are rejected. Tar hardlinks are delayed until regular extraction completes and may target only no-follow regular files inside the staged root.
- `libdsh_proot.so` and `libdsh_proot_loader.so` must come from the pinned runtime toolchain. Either file missing returns `RUNNER_UNAVAILABLE`; startup uses app-private links whose targets remain the APK native libraries, and downloaded executable code is never launched. Before a shell or Harness starts, the app probes the runner, selects the compatible seccomp profile, and adds only validated `/dev`, `/proc`, and resolver bind mounts that the device accepts.
- Ubuntu entrypoints are allowlisted fixed argument vectors. Harness always binds `127.0.0.1`; a per-start 256-bit credential protects HTTP and WebSocket requests before dispatch, and `HarnessActivity` blocks navigation and HTTP resources outside the same loopback origin.
- Shizuku is optional. The manifest declares the official `ShizukuProvider`; after explicit permission, its UserService starts only `/system/bin/sh` and exposes that process only through a visible terminal session. Binder or UserService death invalidates sessions, and a later connection request rebinds. The bridge reports `connected=true` only when permission is granted and the UserService binder is live. This is Android shell UID access, not root and not a general-purpose ADB client.
- Reset first stops Harness and all PTY sessions, then removes only the app-private runtime tree without following symbolic links.
- Audit records are stored under `noBackupFilesDir` with directory mode `0700` and file mode `0600`. UTC daily files retain the 90-day boundary and contain only a timestamp plus fixed event and result enums.

The app does not expose an install-cancel bridge method. Destruction still cancels an active internal download. A digest-matching remote partial is retained for a later installation attempt; unrelated resume files and interrupted random staging workspaces are removed during the next install or reset. Copying an embedded APK asset uses the `preparing` phase, so the UI never describes that local copy as a completed network download. Android loopback limits reachability but is not treated as authentication; the rootfs-packaged Node preload enforces the app-generated ephemeral credential independently of upstream browser-trust checks.
