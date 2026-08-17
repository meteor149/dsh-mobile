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
- **Android-native supervision** — Termux-patched PRoot managed by a foreground service.
- **Local-only access** — authenticated loopback gateway for HTTP, SSE, and WebSocket traffic.
- **Restricted WebView** — navigation is limited to the local DSH origin.
- **Private storage** — runtime and workspace data stay in the app-private directory.

## Requirements

- an `arm64-v8a` Android device;
- Android 9 or newer.

## Install

For the latest development build, open a successful [GitHub Actions run](https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml)
and download the APK from its **Artifacts** section. Tagged builds are also
published on the [Releases](https://github.com/meteor149/dsh-mobile/releases)
page.

On first launch:

1. install the runtime;
2. start DeepSeek Harness;
3. open the Web UI and finish the model setup there.

Pressing Back in the Web UI sends the app to the background; the local runtime
keeps running until it is stopped from the app or notification.

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

## Architecture

```text
Android / Compose
      │
foreground service
      │
PRoot ── Ubuntu ARM64 ── dsh web
      │
authenticated 127.0.0.1 gateway
      │
restricted WebView
```

PRoot does not grant root privileges and is not a security boundary. DSH runs
with the Android application UID. See [`runtime/README.md`](runtime/README.md)
for the runtime layout, artifact contract, and update process.

## License

Licensed under the [Apache License 2.0](LICENSE).
