#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
source_dir="${UBUNTU_SOURCE_DIR:-$(dirname "$project_root")}"
if [[ "${BUILD_UBUNTU_LIBRARIES:-false}" == "true" ]]; then
  bash "$source_dir/android-ubuntu-runtime/runtime/build-runtime.sh"
  bash "$source_dir/android-ubuntu-image/runtime/build-runtime.sh"
fi
bash "$project_root/dsh-runtime/runtime/build-runtime.sh"
bash "$script_dir/proroot/fetch-proroot.sh"
node "$project_root/tools/generate-runtime-manifest.mjs" "$script_dir/dist" --component proroot
"$project_root/gradlew" :app:prepareRuntimeAssets
