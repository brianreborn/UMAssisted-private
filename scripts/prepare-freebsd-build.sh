#!/usr/bin/env bash
# Prepare FreeBSD as an Android build host:
#   1. Verify Linuxulator (linux64 + /compat/linux)
#   2. brandelf -t Linux on Android SDK + Gradle-cached native tools
#
# Safe to re-run before every assemble. No-ops on non-FreeBSD hosts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/freebsd-linuxulator.sh
source "$ROOT/scripts/lib/freebsd-linuxulator.sh"
# shellcheck source=lib/brand-linux-elfs.sh
# brand script is executed, not sourced (it runs as a program)

export_linuxulator_env

if ! is_freebsd; then
  echo "Not FreeBSD — nothing to prepare."
  exit 0
fi

echo "==> FreeBSD Android build host check"
if ! ensure_linuxulator; then
  exit 1
fi
echo "    Linuxulator OK (modules + /compat/linux)"

BRAND="$ROOT/scripts/lib/brand-linux-elfs.sh"
chmod +x "$BRAND" 2>/dev/null || true

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" && -f "$ROOT/local.properties" ]]; then
  # sdk.dir=/path (escape Windows-style \\ if present)
  SDK_ROOT=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1 | sed 's|\\\\|/|g')
fi
if [[ -z "$SDK_ROOT" && -d "$ROOT/sdk" ]]; then
  SDK_ROOT="$ROOT/sdk"
fi

GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

echo "==> brandelf Linux ABIs on SDK / Gradle native tools"
if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]]; then
  # Full walk of the SDK tree (build-tools, platform-tools, …)
  bash "$BRAND" --quiet "$SDK_ROOT"
  echo "    branded SDK: $SDK_ROOT"
else
  echo "    (no SDK tree yet — run scripts/bootstrap-android-sdk.sh first)"
fi

# AGP pulls aapt2 and related natives into the Gradle cache — quick mode only
# (name-matched tools) so we do not file(1) the entire cache.
if [[ -d "$GRADLE_HOME/caches" ]]; then
  for sub in \
    "$GRADLE_HOME/caches/modules-2/files-2.1/com.android.tools.build" \
    "$GRADLE_HOME/caches/transforms-3" \
    "$GRADLE_HOME/caches/transforms-4" \
    ; do
    if [[ -d "$sub" ]]; then
      bash "$BRAND" --quiet --quick "$sub" || true
    fi
  done
  n=0
  while IFS= read -r f; do
    bash "$BRAND" --quiet "$f" || true
    n=$((n + 1))
    [[ "$n" -ge 200 ]] && break
  done < <(
    find "$GRADLE_HOME/caches" -type f \( \
      -name 'aapt2' -o -name 'libaapt2_jni.so' \
    \) 2>/dev/null || true
  )
  echo "    branded Gradle cache natives under $GRADLE_HOME/caches"
fi

echo "==> FreeBSD prepare complete"
echo "    Build with:  ./gradlew :app:assembleDebug"
echo "    (os.name is spoofed to Linux for AGP; real tools run via Linuxulator)"
