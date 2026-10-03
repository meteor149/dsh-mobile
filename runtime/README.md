# DSH Mobile runtime integration

Ubuntu execution and image generation are owned by the sibling projects
`android-ubuntu-runtime` and `android-ubuntu-image`. Their `runtime/dist`
directories hold independent manifests/artifacts. See
[the library integration guide](../Docs/ubuntu-libraries.md).

This repository retains the proroot fetch scripts and license, because the
unmodified binaries may only be redistributed inside a complete APK/AAB.
`runtime/versions.env` pins that app-specific binary release.

`./gradlew buildRuntime` builds the internal `:dsh-runtime` Node/DSH payload,
fetches proroot and generates a proroot-only app manifest. Ubuntu libraries are
resolved from Maven Central; set `BUILD_UBUNTU_LIBRARIES=true` to additionally
build the sibling library artifacts. Set `UBUNTU_SOURCE_DIR` if
the library repositories are not beside DSH Mobile. Node.js, Docker and the
Linux/WSL2 Termux build environment are still required for a full source build.

```bash
bash runtime/proroot/fetch-proroot.sh
node tools/generate-runtime-manifest.mjs runtime/dist --component proroot
./gradlew :app:prepareRuntimeAssets
```

Do not include proroot binary artifacts in either library's Maven AAR.
All backends keep persistent home/workspace data in the host app's private
storage. PRoot/proroot run under the app UID; chroot explicitly requests root
from the device's su manager and restores app ownership when it stops.
