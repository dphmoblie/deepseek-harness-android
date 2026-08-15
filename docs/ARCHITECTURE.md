# Android architecture

The APK is a Capacitor control surface. It does not copy the upstream Web bundle because that bundle requires a host-injected `window.__DSH_BOOT__`, dynamic `/plugins/*` assets, same-origin HTTP RPC, and WebSocket downlinks. The installed Ubuntu environment runs the unmodified `dsh web` host on Android loopback; the app displays that host in an internal, navigation-restricted WebView.

## Runtime installation

1. The app obtains a manifest from a configured HTTPS URL and verifies the manifest bytes against a build-time or user-entered SHA-256 digest.
2. The native layer validates the schema, architecture, URL scheme, host policy, byte limits, entrypoint allowlist, and digest formats.
3. The rootfs is downloaded to an app-private staging file with a compressed-size limit and SHA-256 verification.
4. Extraction rejects path traversal, device nodes, unsafe hard links, excessive entry counts, and extracted-size overflow. Symbolic links are created only after regular entries have been written.
5. A completed environment is atomically promoted. Reset never follows symbolic links and is limited to the app-private runtime directory.

The rootfs is remote to keep the APK small. The PRoot-compatible runner is an executable native library and must be packaged in the APK because current Android versions do not allow executing newly downloaded code from writable app storage. `scripts/prepare-runner.mjs` imports a release-pinned runner only when its URL and SHA-256 are supplied through environment variables; generated `.so` files are ignored by Git.

## Terminal and Harness

The Ubuntu terminal always starts a manifest-validated fixed entrypoint through PRoot. Terminal keystrokes are length-limited byte input to an existing process; they are never concatenated into a host shell command. Harness starts only on `127.0.0.1`, and the app accepts only loopback HTTP/WebSocket navigation for its embedded page.

## Shizuku

Shizuku is optional and user-authorized. The app declares the official provider and API dependencies but does not bundle the Shizuku APK. Device sessions start a fixed `/system/bin/sh` process after an explicit permission grant. The Capacitor bridge cannot choose another executable, add process arguments, or run a background command without an open user-visible terminal session. Shizuku supplies shell-level privileges, not root or Android hardware virtualization.

## Operit reference boundary

Operit revision `35a8c8aa51039fa551f57d92ce2858e77f061fcc` and OperitTerminalCore revision `e4442bc6a047b6165bf59103721ad143149c620d` were reviewed for their high-level PRoot, terminal, and Shizuku integration patterns. This project uses an independent Capacitor bridge, download verifier, extractor, PTY implementation, and fixed Shizuku UserService contract. No Operit source code, assets, or binaries are included.

## Secrets and logs

DeepSeek API credentials remain inside the Harness credential flow and are not handled by the Capacitor management UI. Android device authentication is performed by the system credential activity before the management WebView is revealed. Signing material, local Gradle properties, rootfs archives, native runners, `.env` files, build output, and logs are ignored by Git.

Native audit files live in `noBackupFilesDir`, use owner-only directory/file modes, rotate by UTC date, and retain the 90-day boundary plus newer files. Each line contains only an ISO timestamp, a fixed event enum, and a fixed result enum. URLs, commands, session identifiers, terminal data, credentials, and exception details are never written.
