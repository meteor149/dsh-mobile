# Runtime build

`runtime/dist` is the only hand-off point between the Linux build pipeline and
the Android build. It is intentionally ignored by Git.

## Expected artifacts

After a complete build the directory contains:

```text
runtime/dist/
├── dsh-ubuntu-arm64.tar.zst
├── libdsh_proot.so
├── libdsh_proot_loader.so
├── libandroid-shmem.so
├── libdsh_talloc.so
└── runtime-manifest.json
```

`libdsh_proot.so` is the PRoot PIE executable with an APK-compatible filename,
not a JNI library. Likewise, `libdsh_proot_loader.so` is the unbundled PRoot
loader. Android extracts both from `jniLibs` onto its executable native-library
filesystem, which avoids executing files from writable app storage on modern
target SDK levels.

The PRoot build is sourced from
[`termux/proot`](https://github.com/termux/proot) through the official
[`termux-packages`](https://github.com/termux/termux-packages) recipe. The build
changes the Termux package name to `ai.meteor.dshmobile`, compiles PRoot and
its dependencies for AArch64, packages the loader separately, and rewrites the
versioned `libtalloc` dependency to an APK-compatible library name.

## Rootfs contents

The Ubuntu image contains fixed versions of:

- Ubuntu 24.04 ARM64;
- Node.js from the pinned official ARM64 distribution archive;
- the official `@deepseek-ai/dsh` npm release installed with `npm ci` from a
  committed lockfile;
- Linux ARM64 builds of native Node dependencies such as `node-pty`;
- Git, OpenSSH client, Python 3, ripgrep, curl, and CA certificates;
- `dsh-mobile-gateway`, the authenticated loopback reverse proxy.

Build tools exist only in the image builder stage. Users can install additional
tools with `apt` at runtime, although such modifications belong to the installed
rootfs version and are not migrated automatically to a newer runtime.

## Independent build steps

Rootfs only:

```bash
bash runtime/rootfs/build-rootfs.sh
```

PRoot only:

```bash
bash runtime/proot/build-proot.sh
```

Under WSL2, the temporary `termux-packages` checkout and compiler output use
`/var/tmp/dsh-mobile-runtime/proot` on the native Linux filesystem. This avoids
the severe small-file overhead of compiling on `/mnt/c`; final artifacts are
still copied to `runtime/dist`.

Generate the manifest after both steps:

```bash
node tools/generate-runtime-manifest.mjs runtime/dist
./gradlew :app:prepareRuntimeAssets
```

On Windows, run the Linux entry points inside WSL2 using the Docker Engine
installed in that distribution. `runtime/rootfs/build-rootfs.ps1` remains
available for environments that expose a compatible Docker CLI to PowerShell.

## Version updates

Never change an artifact in place while retaining the same `RUNTIME_VERSION`.
For an Ubuntu, Node, DSH, PRoot, or image recipe update:

1. update the pinned values and checksums in `versions.env`; for DSH, also
   update `rootfs/dsh-package/package.json` and regenerate its lockfile;
2. increment `RUNTIME_VERSION`;
3. rebuild all artifacts;
4. run the Android and gateway tests;
5. test installation and DSH startup on an ARM64 device.

The Android installer keeps user data under `files/linux-data` and installs
replaceable rootfs versions under `files/runtime/versions`.
