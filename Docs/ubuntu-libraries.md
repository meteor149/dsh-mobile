# Ubuntu Android libraries

```text
source/
  android-ubuntu-runtime/   # installation, process control and directory mounts
  android-ubuntu-image/     # pure Ubuntu ARM64 image
  dsh-mobile/
    dsh-runtime/           # internal module: Node, DSH, gateway and DSH lifecycle
    app/                   # foreground service and WebView
```

Only the Ubuntu libraries are independent repositories and Maven artifacts.
DSH is maintained and built inside DSH Mobile; there is no separate DSH project
or Maven publication. Its checksum-verified payload is installed separately in
`files/dsh-packages/versions`, then mounted into Ubuntu when DSH starts.
Ubuntu upgrades retain user data in `files/linux-data`; DSH upgrades do not
require replacing the Ubuntu image.

## Development and publication

Maven Central is the default source for both Ubuntu libraries. Local source
substitution requires `-PUSE_LOCAL_UBUNTU_LIBRARIES=true`; set
`UBUNTU_SOURCE_DIR` for another source directory. Local Maven outputs require
`-PUSE_LOCAL_UBUNTU_MAVEN=true`. Versions are
configured in `gradle.properties`.

```bash
./gradlew buildRuntime
./gradlew :dsh-runtime:testDebugUnitTest :app:assembleRelease
node --test dsh-runtime/runtime/rootfs/gateway.test.mjs dsh-runtime/tools/generate-runtime-manifest.test.mjs
```

`buildRuntime` builds the internal DSH payload and app-only proroot. Set
`BUILD_UBUNTU_LIBRARIES=true` to also build sibling library artifacts. Node/DSH pins belong to `dsh-runtime/runtime/versions.env`;
the image owns Ubuntu packages, while the generic runtime owns PRoot.

From each Ubuntu library directory, `./gradlew publish` verifies complete
artifacts and creates `build/maven-repository`. CI can upload signed packages to
Maven Central using configured Action secrets; versions must be released before
other apps can resolve them there. This split uses runtime `0.2.0` and image
`24.04-1`; runtime 0.2.0 removes the old DSH-specific API.

The Maven-only sample belongs to `android-ubuntu-runtime/samples/ubuntu-client`.
Publish both libraries locally, then run `./gradlew -p samples/ubuntu-client
assembleDebug` from the runtime project. It has no dependency on DSH Mobile.

proroot stays in the complete app due to its binary distribution license.
The reusable runtime supplies PRoot and supports root-managed chroot.

## 其它 app 接入

在项目 repositories 中加入实际发布地址，本地验证可使用本仓库的 `build/maven-repository`。
然后在 Android app 模块中配置：

```kotlin
dependencies {
    implementation("io.github.meteor149:ubuntu-runtime:0.2.0")
    implementation("io.github.meteor149:ubuntu-image:24.04-1")
}

android {
    defaultConfig { minSdk = 28 }
    packaging.jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += setOf(
            "**/libdsh_proot.so", "**/libdsh_proot_loader.so",
            "**/libandroid-shmem.so", "**/libdsh_talloc.so",
        )
    }
    androidResources.noCompress += "zst"
}
```

最终 APK 必须解出原生启动器到 `nativeLibraryDir`，而且保留校验过的原始字节。
库 manifest 提供 INTERNET、ACCESS_NETWORK_STATE 权限及 `extractNativeLibs=true`。
宿主需保留上述 APK 打包设置；AAR 本身无法替宿主设置 `useLegacyPackaging`。

```kotlin
import ai.meteor.ubuntu.runtime.UbuntuCommand
import ai.meteor.ubuntu.runtime.UbuntuEnvironment

val ubuntu = UbuntuEnvironment(applicationContext) // 每个 app 复用一个实例

// 在 coroutine 中调用；安装和执行内部会切换到 IO dispatcher。
ubuntu.install { progress, message -> /* 更新界面 */ }
val result = ubuntu.execute(
    UbuntuCommand(
        arguments = listOf("/bin/bash", "-lc", "cat /etc/os-release; python3 --version"),
        environment = mapOf("MY_APP_SETTING" to "example"),
    ),
)
println("exit=${result.exitCode}\n${result.output}")
```

`execute` 等待命令退出，返回退出码和合并的 stdout/stderr；参数按字面量传递，
需要 shell 语法时显式使用 `/bin/bash -lc`。输出保存在内存中，适合有界输出的命令。
取消调用 coroutine 会停止命令。可以传入 `RuntimeMode.Chroot`，由设备的 `su`
管理器请求 root 授权。不要在同一 app 中同时用多个管理器操作同一 rootfs。

数据位于宿主 app 的私有目录：`files/runtime/versions` 保存安装的镜像，
`files/linux-data/home`、`workspaces` 保存持久数据。安装前至少需要镜像描述文件
指定的空间（当前 2 GiB）；安装新镜像会替换旧 rootfs，保留持久数据目录。

通用目录挂载和长运行命令示例：

```kotlin
val tools = applicationContext.filesDir.toPath().resolve("my-tools")
java.nio.file.Files.createDirectories(tools)
ubuntu.start(
    UbuntuCommand(
        arguments = listOf("/bin/bash", "-lc", "exec python3 -m http.server 8080"),
        bindings = mapOf("/my-tools" to tools),
        workingDirectory = "/my-tools",
    ),
    onLog = { line -> /* 收集日志，宿主自行判断服务就绪 */ },
    onExit = { code -> /* 更新状态 */ },
)
// 停止完整进程组（包括子进程）。
ubuntu.stop()
```

挂载源需为已存在的绝对目录，目标为规范的 Ubuntu 绝对路径；环境变量和参数按字面量传递。
长时间运行任务由宿主管理前台服务及相应权限。两库均不提供 Node、DSH、网关或 WebView。
DSH 专用的 `RuntimeManager`、`RuntimeStateStore`、就绪协议及资源包在
`dsh-mobile` 的内部 `:dsh-runtime` 模块中，包名为 `ai.meteor.dsh.runtime`。

0.2.0 将 DSH API 移出了 Ubuntu 运行库；使用旧 API 的宿主应迁移到自己的业务层。

## Full app device smoke test

Connect and unlock an arm64 Android device, then run in PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest '-PSMOKE_TEST_APPLICATION_ID_SUFFIX=.smoke'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
foreach ($mode in @('Proot', 'Proroot')) {
    adb shell am start -n ai.meteor.dshmobile.smoke/ai.meteor.dshmobile.MainActivity
    adb shell am instrument -w -r -e runtimeMode $mode -e class ai.meteor.dshmobile.RuntimeSmokeTest ai.meteor.dshmobile.smoke.test/androidx.test.runner.AndroidJUnitRunner
}
```

The test installs Ubuntu, starts both PRoot and proroot through the foreground
service, checks gateway authentication and the real WebView, and verifies
background operation and shutdown. The `.smoke` app identity isolates all data
from the user's DSH Mobile installation. It refuses to run against the normal
app identity. Cleanup with `adb uninstall ai.meteor.dshmobile.smoke.test` and
`adb uninstall ai.meteor.dshmobile.smoke`; rebuild without the suffix property
to produce the normal APK again.
