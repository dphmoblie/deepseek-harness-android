# Third-party notices

This file records direct dependencies and the provenance of generated runtime
artifacts. Release builds must also generate and review a complete transitive
dependency inventory for the exact lockfile, rootfs, native binaries, and
Android artifact being distributed.

The Android build copies this notice, the application `LICENSE`, the configured
JavaScript dependency license texts, the full GPL-2.0 and AGPL-3.0 texts
applicable to the imported PRoot/Operit artifacts, and archived GPL-3.0 and
LGPL-3.0 reference texts into `assets/legal/` in the APK. This generated bundle does not, by itself, satisfy
the release-time review and corresponding-source requirements for transitive
Android dependencies or the Ubuntu, Node.js, DeepSeek Harness, PRoot, and
Operit runtime materials.

| Component | Purpose | License |
| --- | --- | --- |
| Capacitor | Android application shell and native bridge | MIT |
| React | Management UI | MIT |
| react-markdown | Safe Markdown rendering for conversation content | MIT |
| remark-gfm | GitHub Flavored Markdown support | MIT |
| xterm.js | Terminal emulator | MIT |
| Lucide | Interface icons | ISC |
| Vite | Web build tooling | MIT |
| TypeScript | Type checking and compilation | Apache-2.0 |
| ESLint | Source linting | MIT |
| Kotlin | Android implementation language and runtime | Apache-2.0 |
| AndroidX | Android application support libraries | Apache-2.0 |
| Apache Commons Compress / IO | Rootfs archive handling | Apache-2.0 |
| OkHttp | Digest-pinned HTTPS downloads | Apache-2.0 |
| Shizuku API / provider | Optional Android shell bridge | Apache-2.0 |
| Ubuntu 24.04 ARM64 packages | Embedded or remotely installed userspace runtime | Package-specific licenses |
| Node.js 24.19.0 and bundled dependencies | JavaScript runtime inside the rootfs | MIT and bundled dependency-specific licenses |
| `@deepseek-ai/dsh` 0.1.0-rc.6 | Agent runtime inside the rootfs | MIT |
| PRoot v5.1.107.78 runner and loader | Userspace rootfs execution | GPL-2.0-or-later |
| Operit2 Android runtime tooling and patch | Source/build provenance for the packaged PRoot artifacts | AGPL-3.0 |
| Operit Terminal Core | Reference implementation consulted for the terminal integration | LGPL-3.0 |

## Native runtime provenance

Release APKs package these renamed ARM64 files:

| APK file | Upstream output | SHA-256 of the current release input |
| --- | --- | --- |
| `lib/arm64-v8a/libdsh_proot.so` | `liboperit_proot.so` | `cb40a1ced11cee76569b4008a9e478c87883ce831152be4eb9570763b82e580d` |
| `lib/arm64-v8a/libdsh_proot_loader.so` | `liboperit_loader.so` | `f149774236db1e69b36cc1e4ed3866c7094db2eee52da0d4956aa63a9bb26929` |

Their source/build provenance is the Operit2 repository at
`https://github.com/AAswordman/Operit2`, commit
`dc4c3a9405dc7ed3ef69b2ac9a6ace65374d77cf`, especially
`tools/android-runtime/`. At that revision the tooling pins Termux PRoot
v5.1.107.78 and carries the Operit patch at
`tools/android-runtime/patches/termux-proot-operit-android.patch`. Operit2's
top-level license is GNU AGPL version 3. PRoot source headers permit GNU GPL
version 2 or, at the recipient's option, any later version.

The upstream Operit Terminal Core `LICENSE` file is preserved verbatim as
`operit-terminal-core-LGPL-3.0.txt`. Because that upstream notice abbreviates
the incorporated GNU GPL version 3 text, the APK also carries unabridged FSF
copies as `gnu-LGPL-3.0.txt` and `gnu-GPL-3.0.txt`. Terminal Core was consulted
as a reference; no Terminal Core source or binary is copied into this app, so
these three files are retained for traceability rather than asserted as the
license of the independently implemented terminal bridge.

The native ELF files and generated rootfs are deliberately absent from Git.
Redistributors must preserve copyright and license notices and provide the
complete corresponding source required for the exact binaries they convey,
including the pinned upstream source, Operit patch and any later changes, plus
the scripts and instructions needed to generate, install, and run the object
code. A link to an upstream repository or this commit identifier alone is not
a corresponding-source offer. This provenance statement records the imported
binary hashes; it does not claim that this repository currently performs a
bit-for-bit rebuild of them.

The prepared rootfs must retain Ubuntu package copyright data, the Node.js
license and bundled notices, the DeepSeek Harness license, and the licenses of
all copied npm packages. A remote rootfs is subject to the same obligations as
one embedded in the APK.
