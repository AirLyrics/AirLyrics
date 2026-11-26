#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
input="${1:?Usage: apple_lyrics_audit.sh <lyrics.ttml|native.json|directory|-> [output-directory]}"
output="${2:-$root/build/apple-lyrics-audit}"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
export AIRLYRICS_AUDIT_OUTPUT="$output"
unset AIRLYRICS_AUDIT_DIR AIRLYRICS_AUDIT_INPUT AIRLYRICS_AUDIT_TRACE
if [[ -d "$input" ]]; then
    export AIRLYRICS_AUDIT_DIR="$(realpath "$input")"
    method=auditDirectory
    report=summary.md
else
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
    export AIRLYRICS_AUDIT_INPUT="$input"
    if [[ -f "$trace" ]]; then export AIRLYRICS_AUDIT_TRACE="$trace"; fi
    method=audit
    report=report.md
fi
cd "$root"
audit_class=com.andsi.airlyrics.lyrics.providers.AppleLyricsAuditTest
./gradlew --offline :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true \
    -Pairlyrics.enableAppleLyricsAudit=true --tests "$audit_class.$method" --rerun
python3 -B scripts/test_reports/summary.py app/build/test-results/testDebugUnitTest-apple-audit \
    --strict --require-test "$audit_class#$method"
printf '%s\n' "$output/$report"
