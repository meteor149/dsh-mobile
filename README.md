<p align="center">
  <img src="Docs/assets/readme-hero.webp" alt="DSH Mobile whale mascot using a phone" width="100%" />
</p>

<h1 align="center">DSH Mobile</h1>

<p align="center"><strong>Run DeepSeek Harness locally on Android.</strong></p>

<p align="center">
  <a href="https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml"><img src="https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml/badge.svg" alt="Android build" /></a>
  <img src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 9 or newer" />
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-4C6EF5" alt="arm64-v8a" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" alt="Apache License 2.0" /></a>
</p>

<p align="center"><strong>English</strong> · <a href="Docs/README.zh-CN.md">简体中文</a></p>

DSH Mobile is an unofficial Android host for
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness). It runs the
official DSH Web UI inside an app-private Ubuntu ARM64 environment and opens it
in a restricted local WebView.

## Highlights

- **Explicit lifecycle** — install, start, and open only when you choose; nothing is installed on first launch.
- **Verified runtime** — versioned Ubuntu 24.04 rootfs with checksum validation.
- **Two execution modes** — use rootless PRoot by default, or choose kernel chroot on a rooted device.
- **Android-native supervision** — PRoot and chroot sessions are managed by a foreground service.
- **Local-only access** — authenticated loopback gateway for HTTP, SSE, and WebSocket traffic.
- **Restricted WebView** — navigation is limited to the local DSH origin.
- **Phone-first Web UI** — the conversation uses the visible viewport, with
  drawer navigation, horizontally scrollable settings categories, stacked
  touch-sized settings controls, and a keyboard-safe composer.
- **Private storage** — runtime and workspace data stay in the app-private directory.

## Requirements

- an `arm64-v8a` Android device;
- Android 9 or newer.
- Root access managed by a compatible `su` implementation is optional and only required for chroot mode.

## Install

For the latest development build, open a successful [GitHub Actions run](https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml)
and download the APK from its **Artifacts** section. Tagged builds are also
published on the [Releases](https://github.com/meteor149/dsh-mobile/releases)
page.

On first launch:

1. install the runtime;
2. choose PRoot, or choose chroot and approve the root-manager authorization prompt;
3. start DeepSeek Harness;
4. open the Web UI and finish the model setup there.

Pressing Back in the Web UI sends the app to the background; the local runtime
keeps running until it is stopped from the app or notification.

## PRoot or chroot?

Both modes use the same verified Ubuntu rootfs and persistent app-private data,
but they make different tradeoffs:

| | PRoot | chroot |
|---|---|---|
| Root access | Not required | Required through the device's `su` manager |
| Compatibility | Recommended default; works on standard Android devices | Depends on the root solution, kernel, and SELinux policy |
| Performance | System calls are translated in userspace, which adds overhead | Uses the kernel directly and is generally faster |
| Linux behavior | Emulates root and some filesystem behavior; a few low-level tools may not work | Provides real chroot and mount behavior, but is still not a full container |
| Security impact | Runs with the app UID; PRoot is not a security boundary | Runs Ubuntu as real root; compromise has substantially greater device impact |
| Best suited for | Most users and maximum portability | Trusted rooted devices where performance or kernel-compatible behavior matters |

Start with PRoot unless there is a concrete reason to use chroot. Switching
modes does not reinstall Ubuntu; after chroot exits, file ownership is restored
to the app UID so the same data can be used by PRoot.

## Build

The Android host requires JDK 21 and Android SDK 36:

```bash
./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Without runtime artifacts, this produces a host-only diagnostic APK. A complete
APK additionally requires Node.js, Docker with BuildKit, and the Linux/WSL2
environment used by the Termux package builder:

```bash
./gradlew buildRuntime
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Runtime input
versions and hashes are pinned in [`runtime/versions.env`](runtime/versions.env);
generated artifacts under `runtime/dist` are intentionally not committed.

## Versioning

`APP_VERSION_NAME` and `APP_VERSION_CODE` in [`gradle.properties`](gradle.properties)
are the single source of truth for both Gradle and GitHub Actions. Version names
follow Semantic Versioning prerelease syntax: Beta builds for the upcoming
`0.0.2` release are `0.0.2-beta.1`, `0.0.2-beta.2`, and so on. Every distributed
APK increments the integer `APP_VERSION_CODE`, including consecutive Beta
builds; the stable `0.0.2` release must also use a code greater than every Beta.
Release tags use the matching `v<version>` form, for example
`v0.0.2-beta.1`.

## Architecture

```text
Android / Compose
      │
foreground service
      │
PRoot (app UID) ─┐
                 ├── Ubuntu ARM64 ── dsh web
chroot (root) ───┘
      │
authenticated 127.0.0.1 gateway
      │
restricted WebView
```

PRoot does not grant root privileges and is not a security boundary; in that
mode DSH runs with the Android application UID. chroot mode explicitly requests
and verifies UID 0, so only enable it on a device and root manager you trust.
See [`runtime/README.md`](runtime/README.md) for the runtime layout, artifact
contract, execution modes, and update process.

## License

Licensed under the [Apache License 2.0](LICENSE).
