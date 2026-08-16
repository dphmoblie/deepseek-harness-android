# Release checklist

Generated rootfs and native ELF files are intentionally excluded from Git. A
local release APK may nevertheless contain them. Complete every applicable
item below for the exact APK before publishing or otherwise distributing it.

## Runtime artifacts

- Build the release image from Ubuntu 24.04 ARM64, Node.js 24.19.0, and
  `@deepseek-ai/dsh` 0.1.0-rc.6. Record and independently verify the SHA-256
  of every source archive and package used by
  `scripts/build-embedded-runtime.py`.
- Verify `/usr/local/bin/dsh`, the ARM64 native Node modules, the runtime
  metadata, and the guest file permissions in the finished rootfs.
- Retain Ubuntu package copyright metadata, the Node.js license and bundled
  dependency notices, the DeepSeek Harness MIT license, and the licenses of
  every npm package copied into the rootfs.
- For a self-contained build, inspect the APK for
  `assets/runtime/runtime-manifest.json` and
  `assets/runtime/rootfs.bundle`. Independently verify the embedded archive's
  compressed length and SHA-256 against its manifest.
- For a smaller remote-runtime build, publish the rootfs and manifest over
  HTTPS, set both `DSH_RUNTIME_MANIFEST_URL` and
  `DSH_RUNTIME_MANIFEST_SHA256`, and independently verify the pinned manifest
  bytes, archive SHA-256, byte lengths, architecture, and `gzip` compression
  value. Do not put credentials in URLs or either setting.
- Test the selection rules: no remote pair uses the embedded assets; a full
  valid pair uses the remote override; a partial or invalid pair fails closed.
- Verify an embedded installation reports `preparing` while copying the APK
  asset and never presents that local copy as a completed network download.
- Interrupt a remote rootfs transfer, restart the app, and verify the matching
  `rootfs-<sha256>.part` resumes with Range. Reject an incorrect HTTP 206 or
  `Content-Range`, and verify HTTP 200 and 416 responses to a resume request
  discard the partial and download again from byte zero. Confirm offline, TLS,
  and timeout failures enter `error` without deleting the bounded partial.
- Inspect the APK for both `lib/arm64-v8a/libdsh_proot.so` and
  `lib/arm64-v8a/libdsh_proot_loader.so`, verify their ARM64 ELF type and
  release-recorded SHA-256 values, and test that the loader is resolved from
  Android's native library directory.

## Security gates

- Verify an unauthenticated Harness HTTP request and WebSocket upgrade are both
  rejected, the current non-exported internal WebView succeeds without
  a visible Basic-auth prompt, and a credential from an earlier Harness process
  no longer works. Loopback binding alone is not an authentication boundary on
  Android.
- Verify that runtime configuration files containing credentials are created with owner-only permissions inside the guest.
- Confirm the manifest contains the official `ShizukuProvider`; Shizuku remains optional, explicitly authorized, and limited to a user-visible fixed device Shell session.
- Kill and restart the Shizuku binder and UserService. Verify active sessions close, a subsequent terminal request reconnects, and `connected` is true only while the authorized UserService binder is live.
- Run Android lint, JVM tests, and an instrumented test on every supported Android API level and an ARM64 physical device.
- Review the final APK network security configuration, exported components, backup rules, and WebView debugging state.
- Verify app startup and management operations do not open the Android device-credential prompt, and verify the owner-only 90-day audit rotation on every supported Android API level.

## Reproducibility and licensing

- Generate the Gradle wrapper from the pinned Gradle version on a trusted build machine; dependency binaries are intentionally not stored in this repository.
- Add and independently verify the official `distributionSha256Sum` for the exact Gradle distribution before publishing a build.
- Resolve release gate `DEP-001` for the unsupported development-only archive dependency and record a clean package-manager audit.
- Generate and review a full JavaScript and Android dependency inventory from the lockfile and resolved Gradle graph.
- Inspect the generated APK `assets/legal/` bundle for the application
  `LICENSE`, `THIRD_PARTY_NOTICES.md`, full GPL-2.0, GPL-3.0, AGPL-3.0, and
  LGPL-3.0 license texts, and every direct JavaScript runtime dependency license. Do not assume that a
  notice naming a license substitutes for its full text.
- Record Operit2 repository
  `https://github.com/AAswordman/Operit2` at commit
  `dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`, its
  `tools/android-runtime/` build instructions, the Termux PRoot
  v5.1.107.78 source, the Operit patch, all release-local changes, and the
  hashes of the two shipped ELF files.
- Confirm the packaged PRoot `COPYING` retains its upstream copyright-holder
  and author notices in addition to the full GPL-2.0 terms.
- Publish or otherwise convey complete machine-readable corresponding source
  for the exact GPL-2.0-or-later and AGPL-3.0 covered artifacts by a method
  those licenses permit. Include the patches, build and installation scripts,
  interface files, and clear no-charge retrieval instructions; an upstream
  URL and commit alone are not sufficient.
- Do not describe the native artifacts as independently implemented or as
  bit-for-bit reproducible unless a clean rebuild has actually demonstrated
  that result. Preserve build logs and toolchain versions as release evidence.
- Add the reviewed transitive Android license texts and all Ubuntu, Node.js,
  DeepSeek Harness, npm, PRoot, and Operit2 notices required by the exact
  release artifacts.
- Run the repository checks and inspect `git diff` before the local release commit. Do not commit `.env`, signing material, rootfs archives, runners, build output, logs, or files over 100 MB.
