#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_LIBS_DIR="$ROOT_DIR/app/src/main/jniLibs"

find_strip_bin() {
  local ndk_home="${ANDROID_NDK_HOME:-}"

  if [[ -n "$ndk_home" && -x "$ndk_home/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" ]]; then
    echo "$ndk_home/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    return 0
  fi

  if [[ -n "${ANDROID_HOME:-}" ]]; then
    find "$ANDROID_HOME" -path "*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -type f 2>/dev/null | head -n 1
    return 0
  fi

  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    find "$ANDROID_SDK_ROOT" -path "*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -type f 2>/dev/null | head -n 1
    return 0
  fi

  find "$HOME/Android" /opt -path "*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -type f 2>/dev/null | head -n 1
}

STRIP_BIN="$(find_strip_bin)"

if [[ -z "$STRIP_BIN" || ! -x "$STRIP_BIN" ]]; then
  echo "Error: llvm-strip not found."
  echo "Please install Android NDK or set ANDROID_NDK_HOME."
  exit 1
fi

if [[ ! -d "$JNI_LIBS_DIR" ]]; then
  echo "Error: jniLibs directory not found: $JNI_LIBS_DIR"
  exit 1
fi

found_so=false

while IFS= read -r -d '' so_file; do
  found_so=true
  echo "Stripping: ${so_file#$ROOT_DIR/}"
  "$STRIP_BIN" --strip-unneeded "$so_file"
done < <(find "$JNI_LIBS_DIR" -name "*.so" -type f -print0)

if [[ "$found_so" == false ]]; then
  echo "No native libraries found under app/src/main/jniLibs."
  exit 0
fi

echo "Native libraries stripped successfully."
