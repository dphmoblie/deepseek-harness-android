# Third-party notices

This file records the direct dependencies used by the source tree. Release builds must also generate and review a complete transitive dependency inventory for the exact lockfile and Android artifact being distributed.

The Android build copies this notice, the application `LICENSE`, and the available license texts for all direct JavaScript runtime dependencies into `assets/legal/` in the APK. Gradle configuration fails when any required source file is missing. This generated bundle does not replace the release-time review and packaging of transitive Android, Ubuntu, DeepSeek Harness, or PRoot notices.

| Component | Purpose | License |
| --- | --- | --- |
| Capacitor | Android application shell and native bridge | MIT |
| React | Management UI | MIT |
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
| DeepSeek Harness | Agent runtime installed inside the remote rootfs | MIT |
| Ubuntu packages | Remote userspace runtime | Package-specific licenses |
| PRoot-compatible runner | Userspace rootfs execution | Build-specific; commonly GPL-2.0-or-later |

No Operit or TerminalCore source code, assets, or binaries are included. Operit revision `35a8c8aa51039fa551f57d92ce2858e77f061fcc` and OperitTerminalCore revision `e4442bc6a047b6165bf59103721ad143149c620d` were inspected read-only as architecture references. Both repositories identify LGPL-3.0 licensing at those revisions; that license does not apply to this independently implemented code.

The PRoot runner is deliberately absent from Git. A distributor importing one must preserve its exact license, provide all required notices, and satisfy the selected build's corresponding-source terms. The prepared rootfs must retain Ubuntu package copyright data and the DeepSeek Harness license.
