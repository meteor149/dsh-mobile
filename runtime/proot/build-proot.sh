#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/../.." && pwd)"
source "$project_root/runtime/versions.env"
if grep -qi microsoft /proc/sys/kernel/osrelease 2>/dev/null; then
  work_root="${DSH_PROOT_WORK_ROOT:-/var/tmp/dsh-mobile-runtime/proot}"
else
  work_root="${DSH_PROOT_WORK_ROOT:-$project_root/runtime/.work/proot}"
fi
source_root="$work_root/termux-packages"
dist="$project_root/runtime/dist"

case "$work_root" in
  "$project_root/runtime/.work/"*|/var/tmp/dsh-mobile-runtime/proot) ;;
  *) echo "unsafe work path: $work_root" >&2; exit 1 ;;
esac
mkdir -p "$work_root" "$dist"

if [[ -e "$source_root" && ! -d "$source_root/.git" ]]; then
  rm -rf -- "$source_root"
fi
if [[ ! -d "$source_root/.git" ]]; then
  git clone --filter=blob:none --no-checkout \
    https://github.com/termux/termux-packages.git "$source_root"
fi
git -c "safe.directory=$source_root" -C "$source_root" \
  fetch --filter=blob:none origin "$TERMUX_PACKAGES_COMMIT"
git -c "safe.directory=$source_root" -C "$source_root" \
  checkout --force --detach "$TERMUX_PACKAGES_COMMIT"

if [[ "$work_root" == /var/tmp/dsh-mobile-runtime/proot ]]; then
  chown -R "${TERMUX_BUILDER_UID:-1001}:${TERMUX_BUILDER_GID:-1001}" "$source_root"
fi

properties="$source_root/scripts/properties.sh"
sed -i 's/^TERMUX_APP__PACKAGE_NAME="com\.termux"$/TERMUX_APP__PACKAGE_NAME="ai.meteor.dshmobile"/' "$properties"
grep -q '^TERMUX_APP__PACKAGE_NAME="ai.meteor.dshmobile"$' "$properties" || {
  echo "Unable to configure the Termux package name" >&2
  exit 1
}

container_name="dsh-mobile-termux-builder-$$"
cleanup() {
  docker rm --force "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT
(
  cd "$source_root"
  CONTAINER_NAME="$container_name" CI=true ./scripts/run-docker.sh ./build-package.sh -a aarch64 -F proot
)

docker run --rm \
  --volume "$source_root/output:/packages:ro" \
  --volume "$dist:/out" \
  --volume "$script_dir/package-artifacts.sh:/package-artifacts.sh:ro" \
  ubuntu:24.04 bash /package-artifacts.sh

echo "PRoot artifacts written to $dist"
