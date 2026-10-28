#!/usr/bin/env bash
# Retry SDK downloads only. Never use this wrapper to retry builds or tests.
set -euo pipefail
if (( $# == 0 )); then
  echo "Usage: $0 package [package ...]" >&2
  exit 2
fi

for attempt in 1 2 3; do
  if sdkmanager --install "$@"; then
    exit 0
  else
    result=$?
  fi
  if (( attempt == 3 )); then
    echo "Android SDK installation failed after $attempt attempts." >&2
    exit "$result"
  fi
  echo "Android SDK installation failed; retrying ($attempt/3)." >&2
  sleep "$((attempt * 5))"
done
