# DSH application module

Internal Android library of DSH Mobile, included as `:dsh-runtime`. This module
stays in the DSH Mobile repository and is not published as a separate Maven library.

It owns the pinned Node.js/DSH payload, authenticated loopback gateway, mobile UI
adaptations, installation and DSH-specific lifecycle/state. `app` owns the Android
foreground service, notifications and WebView; `shared` owns the Compose UI.

Ubuntu comes from the independently published `ubuntu-runtime` and `ubuntu-image`
libraries. The verified DSH archive is installed under `files/dsh-packages/versions`,
separate from `files/runtime/versions`. Its `/opt/node`, `/opt/dsh` and
`/opt/dsh-mobile` directories are mounted for DSH sessions only. Persistent DSH
configuration remains at `files/linux-data/dsh-home`.

Both archives have independent versions and SHA-256 installation markers. Changing
DSH does not reinstall the Ubuntu image. The gateway and DSH backend use dynamically
assigned loopback ports. PRoot/proroot supervision releases the entire session when
stopped, including the gateway's children.

Build with `runtime/build-runtime.ps1` on Windows/Docker or
`runtime/build-runtime.sh` on Linux. Root `buildRuntime` builds this module's payload and the app-only proroot
binaries; Ubuntu libraries are resolved from Maven Central by default. Build outputs live in
ignored `runtime/dist`; archives are not committed.

Node/DSH provenance and integrity pins live in `runtime/versions.env` and the npm
lockfile in `runtime/rootfs/dsh-package`. The payload excludes an Ubuntu rootfs.
Linux launcher files must use LF line endings.
