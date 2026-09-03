#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/../.." && pwd)"
dist="$project_root/runtime/dist"

# shellcheck disable=SC1091
source "$project_root/runtime/versions.env"

: "${PROROOT_VERSION:?PROROOT_VERSION is required}"

artifacts=(
  "libproroot.so:${PROROOT_LAUNCHER_SHA256:?PROROOT_LAUNCHER_SHA256 is required}"
  "libproroot-runtime.so:${PROROOT_RUNTIME_SHA256:?PROROOT_RUNTIME_SHA256 is required}"
  "libproroot-bridge.so:${PROROOT_BRIDGE_SHA256:?PROROOT_BRIDGE_SHA256 is required}"
  "libproroot-linker.so:${PROROOT_LINKER_SHA256:?PROROOT_LINKER_SHA256 is required}"
  "libproroot-stub-loader.so:${PROROOT_STUB_LOADER_SHA256:?PROROOT_STUB_LOADER_SHA256 is required}"
)

mkdir -p "$dist"
download_root="$(mktemp -d)"
trap 'rm -rf -- "$download_root"' EXIT

for artifact in "${artifacts[@]}"; do
  file="${artifact%%:*}"
  expected="${artifact#*:}"
  target="$dist/$file"

  if [[ -f "$target" ]] && printf '%s  %s\n' "$expected" "$target" | sha256sum --check --status; then
    echo "proroot artifact already verified: $file"
    continue
  fi

  download="$download_root/$file"
  url="https://github.com/coderredlab/proroot/releases/download/v${PROROOT_VERSION}/${file}"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    --silent --show-error --output "$download" "$url"
  printf '%s  %s\n' "$expected" "$download" | sha256sum --check --status || {
    echo "error: proroot checksum mismatch for $file" >&2
    exit 1
  }
  install -m 0755 "$download" "$target"
done

echo "proroot v${PROROOT_VERSION} artifacts written to $dist"
