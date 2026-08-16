#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/../.." && pwd)"
source "$project_root/runtime/versions.env"
mkdir -p "$project_root/runtime/dist"

work_root="$project_root/runtime/.work/rootfs"
node_dist_dir="$work_root/node-dist"
node_archive="$node_dist_dir/node.tar.gz"
node_archive_url="https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-arm64.tar.gz"
mkdir -p "$node_dist_dir"
if ! printf '%s  %s\n' "$NODE_LINUX_ARM64_GZIP_SHA256" "$node_archive" | sha256sum --check --status; then
  node_archive_part="$node_archive.part"
  rm -f -- "$node_archive_part"
  curl --fail --location --show-error \
    --output "$node_archive_part" "$node_archive_url"
  printf '%s  %s\n' "$NODE_LINUX_ARM64_GZIP_SHA256" "$node_archive_part" | sha256sum --check --strict
  mv -- "$node_archive_part" "$node_archive"
fi

docker buildx build \
  --platform linux/arm64 \
  --file "$script_dir/Containerfile" \
  --build-context "node-dist=$node_dist_dir" \
  --build-arg "UBUNTU_IMAGE=$UBUNTU_IMAGE" \
  --build-arg "NODE_VERSION=$NODE_VERSION" \
  --build-arg "NODE_LINUX_ARM64_GZIP_SHA256=$NODE_LINUX_ARM64_GZIP_SHA256" \
  --build-arg "DSH_VERSION=$DSH_VERSION" \
  --build-arg "DSH_PACKAGE_INTEGRITY=$DSH_PACKAGE_INTEGRITY" \
  --target artifact \
  --output "type=local,dest=$project_root/runtime/dist" \
  "$script_dir"

echo "rootfs artifact: $project_root/runtime/dist/dsh-ubuntu-arm64.tar.zst"
