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

## 应用效果

<p align="center">
  <img src="assets/dsh-web-ui.png" alt="首页" width="280" />
  <img src="assets/dsh-sidebar.png" alt="侧边栏" width="280" />
</p>

<p align="center">
  <img src="assets/dsh-settings.png" alt="通用设置" width="280" />
  <img src="assets/dsh-models.png" alt="模型设置" width="280" />
</p>

<p align="center">ARM64 Android 真机上的英文界面：首页、侧边栏、通用设置和模型设置。</p>

## 主要特性

- **明确的运行周期** — 安装、启动和打开均由用户主动操作，首次启动时不会自动安装任何内容。
- **经过验证的运行时** — 版本化 Ubuntu 24.04 根文件系统，并进行校验和验证。
- **三种运行方式** — 默认使用免 Root 的 PRoot，也可尝试低开销的免 Root proroot（未开源），或在已 Root 设备上选择内核 chroot。
- **Android 原生管理** — PRoot、proroot 与 chroot 会话均由 Android 前台服务管理。
- **仅限本地访问** — 为 HTTP、SSE 和 WebSocket 流量提供经过身份验证的环回网关。
- **受限 WebView** — 仅允许导航至本地 DSH 来源。
- **手机优先的 Web UI** — 会话跟随可视视口显示，并提供抽屉导航、横滑设置分类、适合触控的单列设置项和不被软键盘遮挡的输入区。
- **私有存储** — 运行时和工作区数据均保存在应用私有目录中。

Node.js、DSH 和身份验证网关由仓库内部的 `:dsh-runtime` 模块维护。两个独立 Ubuntu 库只包含通用运行能力和纯 Ubuntu 镜像，接入说明见 [库集成文档](ubuntu-libraries.md)。

## 系统要求

- `arm64-v8a` Android 设备；
- Android 9 或更高版本。
- 兼容 `su` 的 Root 权限管理为可选项，仅 chroot 方式需要。

## 安装

如需最新开发版本，请打开一次成功的 [GitHub Actions 运行](https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml)，在页面底部的 **Artifacts** 区域下载 APK。带标签的构建也会发布至 [Releases](https://github.com/meteor149/dsh-mobile/releases) 页面。

首次启动时：

1. 安装运行时；
2. 选择 PRoot、proroot；或者选择 chroot，并在 Root 管理器弹窗中授权；
3. 启动 DeepSeek Harness；
4. 打开 Web UI，并在其中完成模型设置。

在 Web UI 中按返回键会将应用切换到后台；本地运行时会继续运行，直到用户通过应用或通知将其停止。

## 选择 PRoot、proroot 还是 chroot？

三种方式使用同一套经过校验的 Ubuntu 根文件系统和应用私有持久化数据，但取舍不同：

| | PRoot | proroot | chroot |
|---|---|---|---|
| Root 权限 | 不需要 | 不需要 | 需要通过设备的 `su` 管理器授权 |
| 兼容性 | 推荐默认选项，在普通 Android 设备上行为较成熟 | 实验性；上游已在较新的 ARM64 Android 设备上验证 | 取决于 Root 方案、内核和 SELinux 策略 |
| 性能 | 通过 ptrace 在用户态转换系统调用 | 基于 LD_PRELOAD 运行，避开 ptrace 开销 | 直接使用内核，通常最快 |
| Linux 行为 | 模拟 Root 和部分文件系统行为，少数底层工具可能无法使用 | 模拟 Root 和文件系统行为，边缘场景可能与 PRoot 不同 | 提供真实 chroot 和挂载行为，但仍不是完整容器 |
| 安全影响 | 使用应用 UID 运行，并非安全边界 | 使用应用 UID 运行，并非安全边界 | Ubuntu 以真实 Root 运行，遭入侵时对设备的影响显著更大 |
| 适用场景 | 大多数用户，以及需要最大可移植性的环境 | 希望测试更低运行开销的免 Root 用户 | 可信的已 Root 设备，且确实需要内核兼容行为时 |

建议优先使用 PRoot。proroot 是基于 [coderredlab/proroot](https://github.com/coderredlab/proroot) 二进制发行版的独立实验后端。proroot 方案未开源；本应用依据其独立许可证使用上游预编译二进制。切换方式不会重新安装 Ubuntu；chroot 退出后会把文件所有权恢复为应用 UID，因此两种免 Root 方式都可以继续使用相同数据。

## 构建

Android 宿主应用需要 JDK 21 和 Android SDK 36。源码开发时，请将 `android-ubuntu-runtime` 和 `android-ubuntu-image` 两个仓库放在本项目同级目录；Maven 接入方式见[库接入文档](ubuntu-libraries.md)。

```bash
./gradlew :dsh-runtime:testDebugUnitTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

缺少运行时制品时，该命令会生成仅包含宿主应用、用于诊断的 APK。构建完整 APK 还需要 Node.js、启用 BuildKit 的 Docker，以及 ARM64 模拟支持（QEMU）：

```bash
./gradlew buildRuntime
./gradlew :app:assembleDebug
```

APK 输出至 `app/build/outputs/apk/debug/app-debug.apk`。各 Ubuntu 库自己维护输入版本和生成制品；本项目的 [`runtime/versions.env`](../runtime/versions.env) 继续管理 proroot 版本。生成的 `runtime/dist` 制品不会提交到版本库。

## 可复用的 Android 库

Ubuntu 运行能力已拆分为 `ubuntu-runtime`，镜像拆分为 `ubuntu-image`。
两个库分别位于独立仓库 `android-ubuntu-runtime` 和 `android-ubuntu-image`，可独立版本管理并发布到 Maven。
详见 [接入与发布文档](ubuntu-libraries.md)。

## 版本管理

[`gradle.properties`](../gradle.properties) 中的 `APP_VERSION_NAME` 和 `APP_VERSION_CODE` 是 Gradle 与 GitHub Actions 共用的唯一版本来源。版本名称遵循语义化版本；预发布版本使用 `-beta.1` 等后缀。每一个对外分发的 APK（包括连续预发布版本）都必须递增整数 `APP_VERSION_CODE`。发布标签使用匹配的 `v<版本号>`，例如 `v0.0.1` 或 `v0.0.2-beta.1`。

## 架构

```text
Android / Compose
      │
前台服务
      │
PRoot（应用 UID）─┐
proroot（应用 UID）├── Ubuntu ARM64 ── dsh web
chroot（Root）─────┘
      │
经过身份验证的 127.0.0.1 网关
      │
受限的 WebView
```

PRoot 与 proroot 不会授予 Root 权限，也不能作为安全边界；这两种方式下 DSH 使用 Android 应用的 UID 运行。chroot 方式会明确申请并校验 UID 0，请仅在可信的设备与 Root 管理器上启用。有关运行时布局、制品约定、运行方式和更新流程，请参阅 [`runtime/README.md`](../runtime/README.md)。

## 开源协议

DSH Mobile 源代码基于 [Apache License 2.0](../LICENSE) 开源。完整运行时构建所打包的未修改 proroot 二进制使用[独立的上游许可证](../app/src/main/assets/licenses/proroot-LICENSE.txt)，并归属 [proroot](https://github.com/coderredlab/proroot)。
