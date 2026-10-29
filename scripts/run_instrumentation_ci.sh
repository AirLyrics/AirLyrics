#!/usr/bin/env bash
set -euo pipefail
live_test=com.andsi.airlyrics.app.search.ManualSearchLiveInstrumentedTest
suite=${1:---fast}
extended_annotation=com.andsi.airlyrics.test.ExtendedInstrumentationTest
stress_annotation=com.andsi.airlyrics.test.SystemStressTest
hardware_annotation=com.andsi.airlyrics.test.HardwareFrameTest
test_args=("-Pandroid.testInstrumentationRunnerArguments.notClass=$live_test")
case "$suite" in
  --fast)
    test_args+=("-Pandroid.testInstrumentationRunnerArguments.notAnnotation=$extended_annotation")
    ;;
  --full)
    ;;
  --stress)
    test_args+=("-Pandroid.testInstrumentationRunnerArguments.annotation=$stress_annotation")
    ;;
  --hardware)
    test_args+=("-Pandroid.testInstrumentationRunnerArguments.annotation=$hardware_annotation")
    ;;
  --live)
    test_args=("-Pandroid.testInstrumentationRunnerArguments.class=$live_test"
      "-Pandroid.testInstrumentationRunnerArguments.liveLyricsSearch=true")
    ;;
  *)
    echo "Usage: $0 [--fast|--full|--stress|--hardware|--live]" >&2
    exit 2
    ;;
esac
mkdir -p app/build/test-diagnostics
collect_diagnostics() {
  local result=$?
  adb logcat -d > app/build/test-diagnostics/logcat.txt 2>&1 || true
  adb shell dumpsys input_method > app/build/test-diagnostics/input-method.txt 2>&1 || true
  adb shell dumpsys window > app/build/test-diagnostics/windows.txt 2>&1 || true
  adb shell dumpsys activity activities > app/build/test-diagnostics/activities.txt 2>&1 || true
  adb shell wm size > app/build/test-diagnostics/display-size.txt 2>&1 || true
  adb shell wm density > app/build/test-diagnostics/display-density.txt 2>&1 || true
  adb shell settings get system font_scale > app/build/test-diagnostics/font-scale.txt 2>&1 || true
  adb shell settings get system user_rotation > app/build/test-diagnostics/user-rotation.txt 2>&1 || true
  adb shell settings get system accelerometer_rotation > app/build/test-diagnostics/auto-rotation.txt 2>&1 || true
  adb shell cmd window user-rotation > app/build/test-diagnostics/rotation-mode.txt 2>&1 || true
  return "$result"
}
trap collect_diagnostics EXIT
device_abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
case "$device_abi" in
  x86_64) abi_args=(-Pairlyrics.buildX86_64=true) ;;
  arm64-v8a) abi_args=() ;;
  *) echo "Unsupported instrumentation device ABI: $device_abi" >&2; exit 2 ;;
esac
jni_library="app/src/main/jniLibs/$device_abi/libairlyrics_lyrics.so"
rust_args=()
rust_status='build required'
if [[ -f "$jni_library" ]]; then
  rust_args=(-Pairlyrics.skipRustBuild=true)
  rust_status='already built'
fi
{
  echo "SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "ABI=$device_abi"
  echo "Test selection: $suite"
  echo "Rust JNI: $rust_status"
} | tee app/build/test-diagnostics/environment.txt
./gradlew :app:connectedDebugAndroidTest "${rust_args[@]}" "${abi_args[@]}" "${test_args[@]}" --stacktrace
