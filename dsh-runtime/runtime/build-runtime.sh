#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
bash "$script_dir/rootfs/build-rootfs.sh"
node "$project_root/tools/generate-runtime-manifest.mjs" "$script_dir/dist"
