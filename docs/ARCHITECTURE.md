# Android architecture

The APK is a Capacitor control surface. It does not copy the upstream Web bundle because that bundle requires a host-injected `window.__DSH_BOOT__`, dynamic `/plugins/*` assets, same-origin HTTP RPC, and WebSocket downlinks. The installed Ubuntu environment runs `dsh web` on Android loopback behind the packaged mobile-auth Node preload; the app displays that host in an internal, navigation-restricted WebView.

## Runtime installation

1. If no remote source is configured, the app reads
   `assets/runtime/runtime-manifest.json` and
   `assets/runtime/rootfs.bundle` from the APK. The opaque bundle remains a
   gzip stream but avoids AAPT's automatic `.gz` expansion and renaming. The current image recipe is
   Ubuntu 24.04 ARM64 plus Node.js 24.19.0 and `@deepseek-ai/dsh`
   0.1.0-rc.6.
2. If both a remote manifest URL and its SHA-256 are configured, the remote
   HTTPS manifest overrides the embedded source. The manifest bytes must match
   that digest before parsing. A partial URL/digest pair is rejected.
3. The native layer validates the schema, architecture, URL scheme, host
   policy, byte limits, gzip compression value, entrypoint allowlist, and digest
   formats. Both embedded and remote rootfs bytes are checked against the
   manifest's exact length and SHA-256.
4. A remote rootfs is downloaded to an app-private staging file. The embedded
   rootfs is copied from the APK to staging. Both paths share the same bounded,
   verified extraction and promotion flow.
5. Extraction rejects path traversal, device nodes, unsafe hard links,
   excessive entry counts, and extracted-size overflow. Symbolic links are
   created only after regular entries have been written.
6. A completed environment is atomically promoted. Reset never follows
   symbolic links and is limited to the app-private runtime directory.

`scripts/build-embedded-runtime.py` produces the optional embedded gzip bundle
and manifest without checking generated artifacts into Git. A distributor can
omit those assets and publish a digest-pinned remote runtime to reduce APK
size, but such an APK cannot install until a valid remote pair is configured.
The PRoot-compatible runner and loader are executable native libraries and
must always be packaged in the APK because current Android versions do not
allow executing newly downloaded code from writable app storage. Generated
`.so` files are ignored by Git.

## Terminal and Harness

The Ubuntu terminal always starts a manifest-validated fixed entrypoint through PRoot. Terminal keystrokes are length-limited byte input to an existing process; they are never concatenated into a host shell command. Harness starts only on `127.0.0.1`. Each start receives a fresh 256-bit token through a fixed environment field; a Node preload removes the field after deriving a constant-time Basic-auth check and rejects unauthenticated HTTP and WebSocket upgrades before route dispatch. The token is held only in process memory, and the device-authenticated internal WebView supplies it through Android's HTTP-auth callback without adding it to the URL.

## Shizuku

Shizuku is optional and user-authorized. The app declares the official provider and API dependencies but does not bundle the Shizuku APK. Device sessions start a fixed `/system/bin/sh` process after an explicit permission grant. The Capacitor bridge cannot choose another executable, add process arguments, or run a background command without an open user-visible terminal session. Shizuku supplies shell-level privileges, not root or Android hardware virtualization.

## Operit2 runtime boundary

The Capacitor bridge, download verifier, extractor, PTY wrapper, and fixed
Shizuku UserService contract in this repository remain independently
implemented. The release-native PRoot artifacts are a separate boundary: the
APK packages `libdsh_proot.so` and `libdsh_proot_loader.so` obtained from the
Operit2 Android runtime toolchain at commit
`dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`, under
`tools/android-runtime/`. The runner is used with `PROOT_LOADER` pointing to
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

DeepSeek API credentials remain inside the Harness credential flow and are not handled by the Capacitor management UI. Android device authentication is performed by the system credential activity before the management WebView is revealed. The ephemeral Harness transport credential is generated with `SecureRandom`, never persisted, and never returned to JavaScript. The WebView-side reference is cleared when the internal WebView stops, while the server-side reference is cleared when the Harness process stops. Signing material, local Gradle properties, generated rootfs archives, generated manifests, native runners, `.env` files, build output, and logs are ignored by Git.

Native audit files live in `noBackupFilesDir`, use owner-only directory/file modes, rotate by UTC date, and retain the 90-day boundary plus newer files. Each line contains only an ISO timestamp, a fixed event enum, and a fixed result enum. URLs, commands, session identifiers, terminal data, credentials, and exception details are never written.
