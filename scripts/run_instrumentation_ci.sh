#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/test-diagnostics
collect_diagnostics() {
  local result=$?
  adb logcat -d > app/build/test-diagnostics/logcat.txt 2>&1 || true
  return "$result"
}
trap collect_diagnostics EXIT
{
  echo "SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo 'Provider configuration is checked offline; no online credentials are required.'
} | tee app/build/test-diagnostics/environment.txt
./gradlew :app:connectedDebugAndroidTest -Pairlyrics.skipRustBuild=true -Pairlyrics.buildX86_64=true --stacktrace
