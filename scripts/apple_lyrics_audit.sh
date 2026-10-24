#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
input="${1:?Usage: apple_lyrics_audit.sh <lyrics.ttml|native.json|-> [output-directory]}"
output="${2:-$root/build/apple-lyrics-audit}"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
if [[ "$input" == *.json ]]; then
    input="$(realpath "$input")"
    trace="$(dirname "$input")/trace.json"
else
    if [[ "$input" != "-" ]]; then input="$(realpath "$input")"; fi
    cd "$root/lyrics-core"
    cargo +1.88.0 run --offline --bin apple_probe -- parse --ttml "$input" --output "$output/native.json" --trace "$output/trace.json"
    input="$output/native.json"
    trace="$output/trace.json"
fi
export AIRLYRICS_AUDIT_INPUT="$input" AIRLYRICS_AUDIT_OUTPUT="$output"
unset AIRLYRICS_AUDIT_TRACE
if [[ -f "$trace" ]]; then export AIRLYRICS_AUDIT_TRACE="$trace"; fi
cd "$root"
./gradlew --offline :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true --tests '*AppleLyricsAuditTest' --rerun-tasks
printf '%s\n' "$output/report.md"
