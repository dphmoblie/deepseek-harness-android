# Release checklist

The repository is a buildable source scaffold, not a preconfigured binary distribution. Complete every item below before publishing an APK.

## Runtime artifacts

- Publish an ARM64 Ubuntu rootfs containing Node.js in the upstream-supported range and a fixed `/usr/local/bin/dsh` entrypoint.
- Retain Ubuntu package copyright metadata and the DeepSeek Harness MIT license in the rootfs.
- Publish the rootfs manifest over HTTPS and pin the manifest and archive with exact SHA-256 values and byte lengths.
- Set `DSH_RUNTIME_MANIFEST_URL` and `DSH_RUNTIME_MANIFEST_SHA256` in the release build environment. Do not place credentials in either value.
- Import an ARM64 PRoot-compatible runner with `pnpm run prepare:runner`; record its upstream revision, license, byte length, and SHA-256.
- Include the runner license and satisfy its corresponding-source or written-offer requirements in the distributed APK and release channel.

## Security gates

- Provide application-level client authentication for the Harness HTTP and WebSocket transport before production distribution. Loopback binding alone is not an authentication boundary on Android.
- Verify that runtime configuration files containing credentials are created with owner-only permissions inside the guest.
- Confirm Shizuku remains optional, explicitly authorized, and limited to a user-visible fixed device Shell session.
- Run Android lint, JVM tests, and an instrumented test on every supported Android API level and an ARM64 physical device.
- Review the final APK network security configuration, exported components, backup rules, and WebView debugging state.
- Verify device-credential locking and the owner-only 90-day audit rotation on every supported Android API level.

## Reproducibility and licensing

- Generate the Gradle wrapper from the pinned Gradle version on a trusted build machine; dependency binaries are intentionally not stored in this repository.
- Add and independently verify the official `distributionSha256Sum` for the exact Gradle distribution before publishing a build.
- Resolve release gate `DEP-001` for the unsupported development-only archive dependency and record a clean package-manager audit.
- Generate and review a full JavaScript and Android dependency inventory from the lockfile and resolved Gradle graph.
- Inspect the generated APK `assets/legal/` bundle for `LICENSE`, `THIRD_PARTY_NOTICES.md`, and every direct JavaScript runtime dependency license; the Gradle build fails if one of its required source files is absent.
- Add the reviewed transitive Android license texts and all Ubuntu, DeepSeek Harness, and PRoot runtime notices required by the exact release artifacts.
- Run the repository checks and inspect `git diff` before the local release commit. Do not commit `.env`, signing material, rootfs archives, runners, build output, logs, or files over 100 MB.
