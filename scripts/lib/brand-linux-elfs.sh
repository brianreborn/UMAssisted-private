#!/usr/bin/env bash
# brand-linux-elfs.sh — mark Linux ELF objects with the Linux ABI brand
# so FreeBSD's Linuxulator (linux64.ko) will execute them.
#
# Usage:
#   brand-linux-elfs.sh <dir-or-file> [dir-or-file ...]
#   brand-linux-elfs.sh --quiet <dir>
#
# Safe to re-run. Skips FreeBSD-native ELFs and non-ELFs.
set -euo pipefail

QUIET=0
FORCE=0
QUICK=0
TARGETS=()

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

for arg in "$@"; do
  case "$arg" in
    -q|--quiet) QUIET=1 ;;
    -f|--force) FORCE=1 ;;
    --quick) QUICK=1 ;;
    -h|--help) usage ;;
    -*)
      echo "Unknown option: $arg" >&2
      usage
      ;;
    *) TARGETS+=("$arg") ;;
  esac
done

if [[ ${#TARGETS[@]} -eq 0 ]]; then
  usage
fi

if ! command -v brandelf >/dev/null 2>&1; then
  echo "brandelf not found (FreeBSD base system tool required)" >&2
  exit 1
fi

if ! command -v file >/dev/null 2>&1; then
  echo "file(1) not found" >&2
  exit 1
fi

log() {
  if [[ "$QUIET" -eq 0 ]]; then
    printf '%s\n' "$*"
  fi
}

needs_brand() {
  local path=$1
  local desc brand

  desc=$(file -b "$path" 2>/dev/null || true)
  case "$desc" in
    *ELF*) ;;
    *) return 1 ;;
  esac
  case "$desc" in
    *FreeBSD*) return 1 ;;
  esac

  brand=$(brandelf -l "$path" 2>/dev/null | awk '{print $NF}' || true)
  case "$brand" in
    Linux|linux)
      if [[ "$FORCE" -eq 1 ]]; then
        return 0
      fi
      return 1
      ;;
  esac
  return 0
}

brand_one() {
  local path=$1
  if [[ ! -f "$path" ]]; then
    return 0
  fi
  if needs_brand "$path"; then
    if brandelf -t Linux "$path" 2>/dev/null; then
      log "  brandelf Linux: $path"
    else
      log "  brandelf failed (ignored): $path"
    fi
  fi
  return 0
}

brand_tree() {
  local root=$1
  if [[ -f "$root" ]]; then
    brand_one "$root"
    return 0
  fi
  if [[ ! -d "$root" ]]; then
    log "skip missing: $root"
    return 0
  fi

  log "Branding Linux ELFs under: $root"

  # Well-known Android SDK / NDK / build-tools binaries and shared objects.
  find "$root" -type f \( \
      -name 'aapt' -o -name 'aapt2' -o -name 'aidl' -o -name 'zipalign' \
      -o -name 'dexdump' -o -name 'split-select' -o -name 'llvm-rs-cc' \
      -o -name 'bcc_compat' \
      -o -name 'adb' -o -name 'fastboot' -o -name 'sqlite3' \
      -o -name 'etc1tool' -o -name 'hprof-conv' -o -name 'make_f2fs' \
      -o -name 'mke2fs' -o -name 'sload_f2fs' \
      -o -name 'libaapt2_jni.so' -o -name '*.so' \
      -o -name 'crashpad_handler' \
      -o -path '*/cmake/*/bin/*' \
      -o -path '*/bin/clang' -o -path '*/bin/clang++' \
      -o -path '*/bin/lld' -o -path '*/bin/ld.lld' \
    \) 2>/dev/null | while IFS= read -r p; do
      brand_one "$p"
    done

  # Broader ELF pass (skipped with --quick for large Gradle caches).
  if [[ "$QUICK" -eq 1 ]]; then
    return 0
  fi
  count=0
  max=${JAPANGLIFY_BRANDELF_MAX:-4000}
  while IFS= read -r p; do
    case "$p" in
      *.jar|*.dex|*.apk|*.aab|*.aar|*.zip|*.png|*.xml|*.txt|*.prop|*.properties|*.java|*.kt|*.class|*.o|*.a|*.pyc)
        continue
        ;;
    esac
    brand_one "$p"
    count=$((count + 1))
    if [[ "$count" -ge "$max" ]]; then
      log "  (stopped after $max files under $root — set JAPANGLIFY_BRANDELF_MAX to raise)"
      break
    fi
  done < <(
    find "$root" -type f \
      ! -name '*.jar' ! -name '*.dex' ! -name '*.apk' ! -name '*.aab' \
      ! -name '*.aar' ! -name '*.zip' ! -name '*.png' ! -name '*.xml' \
      ! -name '*.txt' ! -name '*.prop' ! -name '*.properties' \
      ! -name '*.java' ! -name '*.kt' ! -name '*.class' \
      ! -name '*.o' ! -name '*.a' ! -name '*.pyc' \
      ! -path '*/.git/*' \
      2>/dev/null || true
  )
}

for t in "${TARGETS[@]}"; do
  brand_tree "$t"
done
