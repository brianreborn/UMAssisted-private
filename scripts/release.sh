#!/usr/bin/env bash
# release.sh — roll an actual UMAssisted release.
#
# Builds the release variant (per-ABI split APKs + a universal APK), collects
# them into a timestamped staging dir, and optionally publishes them as GitHub
# Release assets.
#
# APKs are deliberately NOT committed to git (see .gitignore) — committing a
# ~50MB binary on every build is what bloated the public repo with ~547MB of
# artifacts. Release builds live as GitHub Release assets instead.
#
# Usage:
#   ./scripts/release.sh                      # build + stage only
#   ./scripts/release.sh --publish v1.0-alpha # build + stage + create GH release
#
# Signing: if keystore.properties exists at the repo root, the release APKs are
# signed. If not, the build still succeeds but emits *-unsigned.apk, and this
# script refuses to --publish them (unsigned APKs can't be installed normally).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PUBLISH=0
TAG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --publish)
      PUBLISH=1
      TAG="${2:-}"
      if [[ -z "$TAG" ]]; then
        echo "ERROR: --publish requires a tag, e.g. --publish v1.0-alpha" >&2
        exit 1
      fi
      shift 2
      ;;
    -h|--help)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

echo "==> Building release APKs"
./gradlew :app:assembleRelease --console=plain

APK_DIR="app/build/outputs/apk/release"
if [[ ! -d "$APK_DIR" ]]; then
  echo "ERROR: no release output dir at $APK_DIR" >&2
  exit 1
fi

mapfile -t APKS < <(find "$APK_DIR" -maxdepth 1 -name '*.apk' | sort)
if [[ ${#APKS[@]} -eq 0 ]]; then
  echo "ERROR: release build produced no APKs" >&2
  exit 1
fi

UNSIGNED=0
for apk in "${APKS[@]}"; do
  [[ "$apk" == *unsigned* ]] && UNSIGNED=1
done

VERSION_NAME=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle | head -1)
TS=$(date -u +"%Y%m%d_%H%M%SZ")
STAGE="dist/${VERSION_NAME}_${TS}"
mkdir -p "$STAGE"

echo "==> Staging into $STAGE"
for apk in "${APKS[@]}"; do
  base=$(basename "$apk")
  cp "$apk" "$STAGE/$base"
  size=$(ls -lh "$STAGE/$base" | awk '{print $5}')
  echo "    $base  ($size)"
done

( cd "$STAGE" && sha256sum ./*.apk > SHA256SUMS.txt )
echo "    SHA256SUMS.txt"

if [[ "$UNSIGNED" -eq 1 ]]; then
  echo
  echo "WARNING: these APKs are UNSIGNED (no keystore.properties at repo root)."
  echo "         They cannot be installed on a device as-is."
  echo "         Create keystore.properties with storeFile/storePassword/keyAlias/keyPassword"
  echo "         to produce signed release builds."
fi

if [[ "$PUBLISH" -eq 1 ]]; then
  if [[ "$UNSIGNED" -eq 1 ]]; then
    echo "ERROR: refusing to publish unsigned APKs. Set up signing first." >&2
    exit 1
  fi
  echo "==> Publishing GitHub Release $TAG"
  gh release create "$TAG" \
    --repo brianreborn/UMAssisted-private \
    --title "UMAssisted $TAG" \
    --notes "Release build $VERSION_NAME ($TS). Per-ABI split APKs plus a universal APK." \
    "$STAGE"/*.apk "$STAGE/SHA256SUMS.txt"
  echo "==> Published."
else
  echo
  echo "Staged only. To publish:  ./scripts/release.sh --publish <tag>"
fi
