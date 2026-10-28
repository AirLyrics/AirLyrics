#!/usr/bin/env bash
set -euo pipefail
live_test=com.andsi.airlyrics.app.search.ManualSearchLiveInstrumentedTest
case "${1:-}" in
  "")
    test_args=("-Pandroid.testInstrumentationRunnerArguments.notClass=$live_test")
    ;;
  --live)
    test_args=("-Pandroid.testInstrumentationRunnerArguments.class=$live_test"
      "-Pandroid.testInstrumentationRunnerArguments.liveLyricsSearch=true")
    ;;
  *)
    echo "Usage: $0 [--live]" >&2
    exit 2
    ;;
esac
mkdir -p app/build/test-diagnostics
collect_diagnostics() {
  local result=$?
  adb logcat -d > app/build/test-diagnostics/logcat.txt 2>&1 || true
  adb shell dumpsys input_method > app/build/test-diagnostics/input-method.txt 2>&1 || true
  adb shell dumpsys window > app/build/test-diagnostics/windows.txt 2>&1 || true
  return "$result"
}
trap collect_diagnostics EXIT
{
  echo "SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "ABI=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "Test selection: ${1:-offline}"
} | tee app/build/test-diagnostics/environment.txt
./gradlew :app:connectedDebugAndroidTest -Pairlyrics.skipRustBuild=true -Pairlyrics.buildX86_64=true "${test_args[@]}" --stacktrace
