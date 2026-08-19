# Release checklist

Generated rootfs and native ELF files are intentionally excluded from Git.
The official `0.1.8` workflow publishes a self-contained APK plus matching
runtime assets for inspection and explicit remote installation. Complete every
applicable item below for the exact APK, manifest, and rootfs before publishing
or otherwise distributing them.

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
- Confirm the workflow tag is exactly `v0.1.8-mobile-<run_number>` (or
  `v0.1.8` for a stable release) and that the rootfs URL recorded in
  `runtime-manifest.json` points to `rootfs.bundle` under that same tag.
- Independently verify the finished manifest SHA-256, rootfs SHA-256, byte
  lengths, `arm64-v8a` architecture, `gzip` compression value, and runtime
  version after the mobile Harness frontend has been injected.
- Confirm CI embeds that exact manifest and rootfs digest in the matching APK.
  Install the official APK with no prior app data and verify the install action
  works offline without entering or changing a source URL or digest.
- Inspect the APK and fail the release if it contains more than one
  `assets/runtime/rootfs.bundle`, more than one `runtime-manifest.json`, either
  `.bak` file, another rootfs archive, or another generated manifest. Record
  the embedded APK byte size and investigate any unexpected increase.
- Confirm the published Release contains exactly the intended APK,
  `rootfs.bundle`, and `runtime-manifest.json`, and that its notes identify the
  embedded offline install and optional remote resume behavior without asking
  users to configure a manifest manually.
- Interrupt a remote rootfs transfer, restart the app, and verify the matching
  `rootfs-<sha256>.part` resumes with Range. Reject an incorrect HTTP 206 or
  `Content-Range`, and verify HTTP 200 and 416 responses to a resume request
  discard the partial and download again from byte zero. Confirm offline, TLS,
  timeout, and truncated responses enter `error` without deleting a valid
  bounded partial or advancing the UI to extraction.
- Inspect the APK for both `lib/arm64-v8a/libdsh_proot.so` and
  `lib/arm64-v8a/libdsh_proot_loader.so`, verify their ARM64 ELF type and
  release-recorded SHA-256 values, and test that the loader is resolved from
  Android's native library directory.

## Security gates

- Verify an unauthenticated Harness HTTP request and WebSocket upgrade are both
  rejected, the current non-exported internal WebView succeeds without
  a visible Basic-auth prompt, its HttpOnly origin cookie is installed before
  the first page load, and a credential from an earlier Harness process no
  longer works. Loopback binding alone is not an authentication boundary on
  Android.
- Verify that runtime configuration files containing credentials are created with owner-only permissions inside the guest.
- Confirm the manifest contains the official `ShizukuProvider`; Shizuku remains optional, explicitly authorized, explicitly connected, and limited to a user-visible fixed device Shell session.
- Kill and restart the Shizuku binder and UserService. Verify active sessions close, the UI returns to an authorized-but-disconnected state, Connect Shizuku performs a fresh bind, and `connected` is true only while the authorized UserService binder is live.
- Run Android lint, JVM tests, and an instrumented test on every supported Android API level and an ARM64 physical device.
- Review the final APK network security configuration, exported components, backup rules, and WebView debugging state.
- Verify app startup enters the Harness conversation directly when the runtime is ready, the native toolbar returns to Settings, and neither startup nor management operations open an Android device-credential prompt. Verify the owner-only 90-day audit rotation on every supported Android API level.

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

- Verify every packaged ELF (.so) passes the 16KB alignment check
  (`readelf -l <lib> | grep LOAD` → p_align == 0x4000) before release, and record the
  Honor / Android 16 on-device page-size test (`adb shell getconf PAGE_SIZE` == 16384)
  in the release notes. See docs/review-2026-08-16.md §6.
