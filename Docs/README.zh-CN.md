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

DSH Mobile 是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的非官方 Android 应用，在应用私有的 Ubuntu ARM64 环境中运行 DSH，并通过内置 WebView 显示界面。

本开发分支通过 Central snapshot 仓库接入 `io.github.meteor149:ubuntu-runtime:0.3.0-SNAPSHOT`，临时修复 PRoot 的兼容问题。Ubuntu 镜像仍使用 `24.04-1`，proroot 的接入由本应用负责。

## 应用效果

<p align="center">
  <img src="assets/dsh-setup.png" alt="Ubuntu 引导界面、运行方式选择和自动启动选项" width="280" />
</p>

<p align="center">Ubuntu 引导界面：运行方式选择与自动启动选项。</p>

<p align="center">
  <img src="assets/dsh-web-ui.png" alt="首页" width="280" />
  <img src="assets/dsh-sidebar.png" alt="侧边栏" width="280" />
</p>

<p align="center">
  <img src="assets/dsh-settings.png" alt="通用设置" width="280" />
  <img src="assets/dsh-models.png" alt="模型设置" width="280" />
</p>

<p align="center">ARM64 Android 真机上的英文界面：首页、侧边栏、通用设置和模型设置。</p>

## 功能

- 在 Ubuntu 24.04 ARM64 中运行 DSH，并校验运行时文件的完整性。
- 支持 PRoot、proroot 和 chroot，由前台服务管理本地运行时。
- 适配手机屏幕，提供侧边栏导航、可横向滚动的设置分类和不被软键盘遮挡的输入区。
- Ubuntu 和工作区数据保存在应用私有目录。
- 通过带身份验证的本地网关访问 Web UI。

## 安装与使用

需要运行 Android 9 或更高版本的 ARM64 设备。只有 chroot 需要 Root 权限。

从 [Releases](https://github.com/meteor149/dsh-mobile/releases) 下载 APK。
开发版可在成功的 [GitHub Actions 构建](https://github.com/meteor149/dsh-mobile/actions/workflows/android.yml) 的 **Artifacts** 中下载。

1. 安装 APK，然后在应用内安装运行时。
2. 选择运行方式，建议先使用 PRoot；chroot 需要在 Root 管理器中授权。
3. 启动 DeepSeek Harness，打开 Web UI。
4. 在设置中配置模型服务商和 API 密钥。

首次使用时，DSH 会在 `/workspace/deepseek-harness/default-workspace` 创建默认工作区，文件保存在应用私有数据中，并在各运行方式之间共享。

在 Web UI 中按返回键，有浏览历史时返回上一页，否则将应用切换到后台。
运行时会继续运行，直到通过应用或通知停止。

点击 Web UI 中的下载按钮后，在 Android 系统“保存到”界面选择位置，即可把文件导出到手机的下载目录或其他文档存储位置。

## 运行方式

三种方式共用 Ubuntu 环境和工作区数据，切换时无需重新安装 Ubuntu。

| 方式 | 需要 Root | 说明 |
|---|---|---|
| PRoot | 否 | 默认方式，通过 ptrace 转换系统调用。 |
| proroot | 否 | 实验性方案，通过 LD_PRELOAD 转换避开 ptrace 开销，未开源。 |
| chroot | 是 | 使用内核 chroot 机制，兼容性取决于内核和 Root 配置。 |

proroot 使用 [coderredlab/proroot](https://github.com/coderredlab/proroot) 的预编译二进制，遵循其[上游许可证](../app/src/main/assets/licenses/proroot-LICENSE.txt)。

PRoot 和 proroot 使用 Android 应用 UID 运行，不能作为安全边界。
chroot 以 Root 身份运行 Ubuntu，请仅在可信设备上使用。

## 构建

需要 JDK 21 和 Android SDK 36。构建运行时还需要 Node.js、启用 BuildKit 的 Docker 和 ARM64 模拟支持（QEMU），详见[运行时构建说明](../runtime/README.md)。
Ubuntu 库默认从 Maven Central 下载。

```bash
./gradlew buildRuntime
./gradlew :app:assembleDebug
```

APK 输出至 `app/build/outputs/apk/debug/app-debug.apk`。
缺少运行时制品时，应用以诊断模式构建，无法启动 DSH。

运行检查：

```bash
./gradlew :dsh-runtime:testDebugUnitTest :shared:testDebugUnitTest :app:testDebugUnitTest
```

Node.js、DSH 和网关由内部模块 `:dsh-runtime` 维护。可复用的 Ubuntu 库分别位于 [android-ubuntu-runtime](https://github.com/meteor149/android-ubuntu-runtime) 和 [android-ubuntu-image](https://github.com/meteor149/android-ubuntu-image)。

发布时修改 [`gradle.properties`](../gradle.properties) 中的 `APP_VERSION_NAME`，递增 `APP_VERSION_CODE`，再推送对应的 `v<版本号>` 标签。GitHub Actions 会构建并发布签名 APK。

## 许可证

DSH Mobile 源代码使用 [Apache License 2.0](../LICENSE)。
打包的 proroot 二进制未开源，使用[独立的上游许可证](../app/src/main/assets/licenses/proroot-LICENSE.txt)。
