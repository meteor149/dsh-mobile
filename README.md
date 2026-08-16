# DSH Mobile

[![Android](https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml/badge.svg)](https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml)

DSH Mobile is an unofficial Android host for
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness). It runs the
official DSH Web UI inside an app-private Ubuntu ARM64 environment and opens it
in a restricted local WebView.

> Beta software. The current release supports `arm64-v8a` devices running
> Android 9 or newer.

## Features

- explicit install, start, and open steps—nothing is installed on first launch;
- versioned Ubuntu 24.04 rootfs with checksum verification;
- Termux-patched PRoot, supervised by an Android foreground service;
- authenticated loopback gateway for HTTP, SSE, and WebSocket traffic;
- WebView navigation restricted to the local DSH origin;
- runtime and workspace data stored in the app-private directory.

## Install

Download the APK from [GitHub Actions](https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml)
or a tagged [release](https://github.com/meteor149/dsh-mobile/releases).

On first launch:

1. tap **安装运行时**;
2. tap **启动 DeepSeek Harness**;
3. open the Web UI and finish model setup there.

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

## Project identity

- Package: `ai.meteor.dshmobile`
- Version: `0.0.1-BETA`
- DeepSeek Harness and Termux remain the property of their respective projects.

This project is not affiliated with or endorsed by DeepSeek.
