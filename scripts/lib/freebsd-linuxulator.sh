#!/usr/bin/env bash
# FreeBSD Linuxulator helpers for running Linux Android SDK tools.
# shellcheck shell=bash
set -euo pipefail

is_freebsd() {
  # Prefer the real kernel name: uname may report Linux under Linuxulator
  # process trees or when compat.linux.osname is spoofed for children.
  if [[ -n "${JAPANGLIFY_REAL_OS:-}" ]]; then
    [[ "${JAPANGLIFY_REAL_OS}" == [Ff]ree[Bb][Ss][Dd] ]]
    return
  fi
  if command -v sysctl >/dev/null 2>&1; then
    local kern
    kern=$(sysctl -n kern.ostype 2>/dev/null || true)
    if [[ "$kern" == FreeBSD ]]; then
      return 0
    fi
  fi
  case "$(uname -s 2>/dev/null || true)" in
    FreeBSD|FREEBSD) return 0 ;;
  esac
  # Java property path used by Gradle (os.name=FreeBSD)
  if [[ "${OSTYPE:-}" == freebsd* ]]; then
    return 0
  fi
  return 1
}

linuxulator_loaded() {
  if ! command -v kldstat >/dev/null 2>&1; then
    return 1
  fi
  # Module *file* names are linux64.ko / linux.ko; internal -m names are
  # often linux64elf / linuxelf (varies by FreeBSD version).
  if kldstat -q -m linux64elf 2>/dev/null \
    || kldstat -q -m linuxelf 2>/dev/null \
    || kldstat -q -m linux64 2>/dev/null \
    || kldstat -q -m linux 2>/dev/null; then
    return 0
  fi
  kldstat 2>/dev/null | grep -qE 'linux64\.ko|linux\.ko'
}

linuxulator_userland() {
  # glibc dynamic linker used by Linux x86_64 binaries
  [[ -e /compat/linux/lib64/ld-linux-x86-64.so.2 ]] \
    || [[ -e /compat/linux/lib/ld-linux.so.2 ]] \
    || [[ -e /compat/linux/lib64/ld-linux-x86-64.so.1 ]]
}

ensure_linuxulator() {
  if ! is_freebsd; then
    return 0
  fi

  if ! linuxulator_loaded; then
    echo "FreeBSD Linuxulator modules not loaded (linux64/linux)." >&2
    echo "As root:  kldload linux64   # or: service linux onestart" >&2
    echo "Permanent: add linux_enable=\"YES\" to /etc/rc.conf" >&2
    return 1
  fi

  if ! linuxulator_userland; then
    echo "Linux userland missing under /compat/linux." >&2
    echo "Install a Linux base, e.g.:" >&2
    echo "  pkg install linux_base-rl9   # or linux_base-c7" >&2
    return 1
  fi

  if ! command -v brandelf >/dev/null 2>&1; then
    echo "brandelf(1) not found in PATH" >&2
    return 1
  fi

  return 0
}

# Export env hints useful when spawning Linux tools from FreeBSD shells.
export_linuxulator_env() {
  if ! is_freebsd; then
    return 0
  fi
  export JAPANGLIFY_REAL_OS=FreeBSD
  # Some tools key off uname; leave the host as FreeBSD for brandelf, but
  # ensure Linux emul path is set for the kernel.
  if [[ -z "${LINUX_ROOT:-}" && -d /compat/linux ]]; then
    export LINUX_ROOT=/compat/linux
  fi

  # sdkmanager / AGP filter native packages by os.name. FreeBSD is not a
  # known host, so the Linux package set (aapt2, adb, …) is hidden unless
  # we spoof Linux for the JVM. brandelf still uses the real FreeBSD host.
  existing="${JAVA_TOOL_OPTIONS:-}"
  case " $existing " in
    *" -Dos.name="*) ;;
    *)
      export JAVA_TOOL_OPTIONS="${existing:+$existing }-Dos.name=Linux"
      ;;
  esac
  # Prefer Linux host classifiers in some Google download helpers
  export ANDROID_SDK_HOST_OS="${ANDROID_SDK_HOST_OS:-linux}"
}

# Run a command with Linux os.name spoofing (even if caller skipped export).
run_with_linux_os_name() {
  if is_freebsd; then
    env JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dos.name=Linux" "$@"
  else
    "$@"
  fi
}
