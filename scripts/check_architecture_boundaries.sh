#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

case "${1:-}" in
  --source-only)
    exec python3 -B scripts/architecture/check.py
    ;;
  --update-docs)
    exec python3 -B scripts/architecture/check.py --update-docs
    ;;
  "")
    python3 -B scripts/architecture/check.py
    exec ./gradlew :app:checkArchitecture -Pairlyrics.skipRustBuild=true --stacktrace
    ;;
  *)
    echo "Usage: $0 [--source-only|--update-docs]" >&2
    exit 2
    ;;
esac
