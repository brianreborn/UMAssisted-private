#!/usr/bin/env bash
# dev-debug.sh — Live debugging & ADB workflow helper for UMAssisted
#
# Usage:
#   ./scripts/dev-debug.sh connect [IP:PORT]   # Connect ADB device over network
#   ./scripts/dev-debug.sh install              # Build debug APK & install via ADB
#   ./scripts/dev-debug.sh grant                # Auto-grant Accessibility & Overlay permissions via ADB
#   ./scripts/dev-debug.sh launch               # Launch com.umassisted.app/.MainActivity
#   ./scripts/dev-debug.sh stop                 # Force stop com.umassisted.app
#   ./scripts/dev-debug.sh logcat               # Tail filtered app logcat output
#   ./scripts/dev-debug.sh capture <label> [sub] # Take passive screenshot & save to ~/UMAssisted/screenshots
#   ./scripts/dev-debug.sh run                  # Full flow: install + grant + launch + logcat

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PUBLIC_ROOT="$(cd "$ROOT/../UMAssisted" 2>/dev/null && pwd || echo "")"

# Resolve SDK & ADB
resolve_adb() {
  if command -v adb >/dev/null 2>&1; then
    echo "adb"
    return
  fi

  local sdk_dir=""
  if [[ -f "$ROOT/local.properties" ]]; then
    sdk_dir=$(grep -E '^sdk\.dir=' "$ROOT/local.properties" | cut -d= -f2- | tr -d '\r')
  fi
  if [[ -z "$sdk_dir" ]]; then
    sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/japanglify/sdk}}"
  fi

  if [[ -x "$sdk_dir/platform-tools/adb" ]]; then
    echo "$sdk_dir/platform-tools/adb"
  else
    echo "adb"
  fi
}

ADB="$(resolve_adb)"
PKG="com.umassisted.app"
ACCESSIBILITY_SERVICE="${PKG}/${PKG}.UMAssistedAccessibilityService"

wait_device() {
  echo "==> Waiting for ADB device readiness..."
  "$ADB" wait-for-device
}

cmd_connect() {
  local target="${1:-192.168.1.123:39833}"
  echo "==> Connecting ADB to $target..."
  "$ADB" connect "$target"
  wait_device
  "$ADB" devices
}

cmd_install() {
  wait_device
  echo "==> Building assembleDebug..."
  (cd "$ROOT" && ./gradlew assembleDebug --console=plain)
  local apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$apk" ]]; then
    echo "ERROR: debug APK not found at $apk" >&2
    exit 1
  fi
  echo "==> Installing $apk via ADB..."
  "$ADB" install -r "$apk"
  echo "==> Installed successfully."
}

cmd_grant() {
  wait_device
  echo "==> Granting permissions & enabling Accessibility Service via ADB..."
  "$ADB" shell settings put secure enabled_accessibility_services "$ACCESSIBILITY_SERVICE" || true
  "$ADB" shell settings put secure accessibility_enabled 1 || true
  "$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
  echo "==> Accessibility Service enabled: $ACCESSIBILITY_SERVICE"
}

cmd_launch() {
  wait_device
  echo "==> Launching $PKG/.MainActivity..."
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
  # Fast event-driven window focus check (20ms polling, 1s max fastpath)
  local count=0
  until "$ADB" shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus.*$PKG" >/dev/null || [[ $count -ge 50 ]]; do
    sleep 0.02
    count=$((count + 1))
  done
  if [[ $count -lt 50 ]]; then
    echo "==> $PKG successfully launched and focused."
  else
    echo "==> $PKG launch completed."
  fi
}

cmd_stop() {
  wait_device
  echo "==> Force stopping $PKG..."
  "$ADB" shell am force-stop "$PKG"
}

cmd_logcat() {
  echo "==> Tailing logcat for $PKG (Ctrl+C to stop)..."
  "$ADB" logcat -c
  "$ADB" logcat -v time "$PKG:V" "UMAssisted:V" "AndroidRuntime:E" "*:S"
}

cmd_push() {
  local src="${1:-}"
  local dest="${2:-/sdcard/}"
  if [[ -z "$src" ]]; then
    echo "ERROR: Usage: ./scripts/dev-debug.sh push <local-file> [remote-path]" >&2
    exit 1
  fi
  wait_device
  echo "==> Pushing $src to $dest..."
  "$ADB" push "$src" "$dest"
}

cmd_push_apk() {
  local apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$apk" ]]; then
    echo "==> Debug APK not found. Building assembleDebug first..."
    (cd "$ROOT" && ./gradlew assembleDebug --console=plain)
  fi
  wait_device
  local target_path="/sdcard/Download/app-debug.apk"
  echo "==> Pushing $apk to $target_path..."
  "$ADB" push "$apk" "$target_path"
  echo "==> APK pushed successfully to $target_path."
}

cmd_capture() {
  local label="${1:-screen}"
  local sub="${2:-misc}"
  local brain_dest="${3:-}"
  if [[ -n "$PUBLIC_ROOT" && -x "$PUBLIC_ROOT/tools/capture_screen.sh" ]]; then
    (cd "$PUBLIC_ROOT" && ./tools/capture_screen.sh "$label" "$sub")
    local latest
    latest=$(find "$PUBLIC_ROOT/screenshots/$sub" -name "*.png" -type f -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -n 1 | cut -d' ' -f2-)
    if [[ -n "$brain_dest" && -f "$latest" ]]; then
      cp -f "$latest" "$brain_dest"
      echo "==> Synced $latest -> $brain_dest"
    fi
  else
    echo "ERROR: ~/UMAssisted repository capture script not found." >&2
    exit 1
  fi
}

cmd_mdns() {
  echo "==> Reviewing mDNS ADB Wireless Debugging services..."
  "$ADB" mdns check || true
  "$ADB" mdns services
}

cmd_scan() {
  local prefix="${1:-192.168.1}"
  if command -v nmap >/dev/null 2>&1; then
    echo "==> Scanning ${prefix}.0/24 with nmap for open ADB & Wireless Debugging ports (including 30000-50000)..."
    nmap -p 5555,5554,37561,5556,5558,6555,7555,5037,2222,30000-50000 --open "${prefix}.0/24"
  elif [[ -x "$ROOT/scripts/scan-adb.pl" ]]; then
    perl "$ROOT/scripts/scan-adb.pl" "$prefix"
  else
    echo "==> Scanning ${prefix}.1-254 for active ADB devices..."
    for i in {1..254}; do
      local ip="${prefix}.${i}"
      for port in 37561 5555 5554 5556 5558 6555 7555 5037 2222; do
        ( (echo > "/dev/tcp/$ip/$port") 2>/dev/null && echo "FOUND: $ip:$port" ) &
      done
    done
    wait
  fi
}

cmd_run() {
  cmd_install
  cmd_grant
  cmd_launch
  cmd_logcat
}

ACTION="${1:-help}"
shift || true

case "$ACTION" in
  connect) cmd_connect "$@" ;;
  install) cmd_install ;;
  grant)   cmd_grant ;;
  launch)  cmd_launch ;;
  stop)    cmd_stop ;;
  logcat)  cmd_logcat ;;
  push)    cmd_push "$@" ;;
  push-apk) cmd_push_apk ;;
  mdns)    cmd_mdns "$@" ;;
  scan)    cmd_scan "$@" ;;
  capture) cmd_capture "$@" ;;
  run)     cmd_run ;;
  -h|--help|help)
    sed -n '2,15p' "$0"
    ;;
  *)
    echo "ERROR: Unknown command '$ACTION'" >&2
    exit 1
    ;;
esac

