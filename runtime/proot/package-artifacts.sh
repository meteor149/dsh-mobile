#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update >/dev/null
apt-get install -y --no-install-recommends binutils dpkg-dev patchelf >/dev/null

extract_root=/tmp/packages
mkdir -p "$extract_root"
extract_package() {
  local package="$1"
  mapfile -t candidates < <(find /packages -maxdepth 1 -type f -name "${package}_*aarch64.deb" | sort)
  [[ ${#candidates[@]} -gt 0 ]] || { echo "Missing package output: $package" >&2; exit 1; }
  dpkg-deb --extract "${candidates[-1]}" "$extract_root"
}
extract_package proot
extract_package libandroid-shmem
extract_package libtalloc

prefix="$(find "$extract_root" -type f -path '*/files/usr/bin/proot' -printf '%h\n' | sed 's|/bin$||' | head -n 1)"
[[ -n "$prefix" ]] || { echo 'Unable to locate the custom Termux prefix' >&2; exit 1; }
proot="$prefix/bin/proot"
loader="$prefix/libexec/proot/loader"
shmem="$(find "$prefix/lib" -type f -name 'libandroid-shmem.so' | head -n 1)"
talloc="$(find "$prefix/lib" -type f -name 'libtalloc.so.*' | sort | tail -n 1)"

for required in "$proot" "$loader" "$shmem" "$talloc"; do
  [[ -f "$required" ]] || { echo "Missing built artifact: $required" >&2; exit 1; }
done

install -m 0755 "$proot" /out/libdsh_proot.so
install -m 0755 "$loader" /out/libdsh_proot_loader.so
install -m 0755 "$shmem" /out/libandroid-shmem.so
install -m 0755 "$talloc" /out/libdsh_talloc.so

while IFS= read -r dependency; do
  case "$dependency" in
    libtalloc.so*) patchelf --replace-needed "$dependency" libdsh_talloc.so /out/libdsh_proot.so ;;
  esac
done < <(patchelf --print-needed /out/libdsh_proot.so)

readelf -h /out/libdsh_proot.so | grep -q 'AArch64' || { echo 'PRoot is not AArch64' >&2; exit 1; }
readelf -h /out/libdsh_proot_loader.so | grep -q 'AArch64' || { echo 'PRoot loader is not AArch64' >&2; exit 1; }
echo 'PRoot dependencies:'
patchelf --print-needed /out/libdsh_proot.so
