#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"

bash "$script_dir/rootfs/build-rootfs.sh"
bash "$script_dir/proot/build-proot.sh"
if ! command -v node >/dev/null 2>&1; then
  echo "error: Node.js is required to generate the runtime manifest" >&2
  echo "install it inside WSL (for example: sudo apt-get install nodejs) and retry" >&2
  exit 1
fi
node "$project_root/tools/generate-runtime-manifest.mjs" "$script_dir/dist"
"$project_root/gradlew" :app:prepareRuntimeAssets
