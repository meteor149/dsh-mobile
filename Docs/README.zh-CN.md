<p align="center">
  <img src="assets/readme-hero.webp" alt="正在使用手机的 DSH Mobile 鲸鱼形象" width="100%" />
</p>

<h1 align="center">DSH Mobile</h1>

<p align="center"><strong>在 Android 上本地运行 DeepSeek Harness。</strong></p>

<p align="center">
  <a href="https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml"><img src="https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml/badge.svg" alt="Android 构建状态" /></a>
  <img src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 9 或更高版本" />
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-4C6EF5" alt="arm64-v8a" />
  <a href="../LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" alt="Apache License 2.0" /></a>
</p>

<p align="center"><a href="../README.md">English</a> · <strong>简体中文</strong></p>

DSH Mobile 是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的非官方 Android 宿主应用。它在应用私有的 Ubuntu ARM64 环境中运行官方 DSH Web UI，并通过受限的本地 WebView 打开界面。

## 主要特性

- **明确的运行周期** — 安装、启动和打开均由用户主动操作，首次启动时不会自动安装任何内容。
- **经过验证的运行时** — 版本化 Ubuntu 24.04 根文件系统，并进行校验和验证。
- **Android 原生管理** — Termux 补丁版 PRoot 由 Android 前台服务管理。
- **仅限本地访问** — 为 HTTP、SSE 和 WebSocket 流量提供经过身份验证的环回网关。
- **受限 WebView** — 仅允许导航至本地 DSH 来源。
- **私有存储** — 运行时和工作区数据均保存在应用私有目录中。

## 系统要求

- `arm64-v8a` Android 设备；
- Android 9 或更高版本。

## 安装

如需最新开发版本，请打开一次成功的 [GitHub Actions 运行](https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml)，在页面底部的 **Artifacts** 区域下载 APK。带标签的构建也会发布至 [Releases](https://github.com/meteor149/dsh-mobile/releases) 页面。

首次启动时：

1. 安装运行时；
2. 启动 DeepSeek Harness；
3. 打开 Web UI，并在其中完成模型设置。

在 Web UI 中按返回键会将应用切换到后台；本地运行时会继续运行，直到用户通过应用或通知将其停止。

## 构建

Android 宿主应用需要 JDK 21 和 Android SDK 36：

```bash
./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

缺少运行时制品时，该命令会生成仅包含宿主应用、用于诊断的 APK。构建完整 APK 还需要 Node.js、启用 BuildKit 的 Docker，以及供 Termux 软件包构建器使用的 Linux/WSL2 环境：

```bash
./gradlew buildRuntime
./gradlew :app:assembleDebug
```

APK 输出至 `app/build/outputs/apk/debug/app-debug.apk`。运行时输入的版本和哈希值固定在 [`runtime/versions.env`](../runtime/versions.env) 中；生成的 `runtime/dist` 制品不会提交到版本库。

## 架构

```text
Android / Compose
      │
前台服务
      │
PRoot ── Ubuntu ARM64 ── dsh web
      │
经过身份验证的 127.0.0.1 网关
      │
受限的 WebView
```

PRoot 不会授予 root 权限，也不能作为安全边界。DSH 使用 Android 应用的 UID 运行。有关运行时布局、制品约定和更新流程，请参阅 [`runtime/README.md`](../runtime/README.md)。

## 开源协议

本项目基于 [Apache License 2.0](../LICENSE) 开源。
