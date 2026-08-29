#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 - <<'PY'
from pathlib import Path
import re
import sys

SOURCE_ROOT = Path("app/src/main/java/com/andsi/airlyrics")

RULES = {
    "core": {
        "path": SOURCE_ROOT / "core",
        "forbidden": ["app", "design", "feedback", "floating", "i18n", "lyrics", "media", "settings", "ui"],
    },
    "design": {
        "path": SOURCE_ROOT / "design",
        "forbidden": ["app", "feedback", "floating", "i18n", "lyrics", "media", "settings", "ui"],
    },
    "feedback": {
        "path": SOURCE_ROOT / "feedback",
        "forbidden": ["app", "core", "design", "floating", "i18n", "lyrics", "media", "settings", "ui"],
    },
    "settings": {
        "path": SOURCE_ROOT / "settings",
        "forbidden": ["app", "feedback", "floating", "i18n", "lyrics", "media", "ui"],
    },
    "lyrics": {
        "path": SOURCE_ROOT / "lyrics",
        "forbidden": ["app", "feedback", "floating", "media", "settings", "ui"],
    },
    "i18n": {
        "path": SOURCE_ROOT / "i18n",
        "forbidden": ["app", "feedback", "floating", "media", "settings", "ui"],
    },
    "media": {
        "path": SOURCE_ROOT / "media",
        "forbidden": ["app", "feedback", "floating", "lyrics", "settings", "ui"],
    },
    "floating": {
        "path": SOURCE_ROOT / "floating",
        "forbidden": ["app", "ui"],
    },
    "ui": {
        "path": SOURCE_ROOT / "ui",
        "forbidden": ["app", "floating", "lyrics", "media", "settings"],
    },
}

IMPORT_RE = re.compile(r"^\s*import\s+com\.andsi\.airlyrics\.([A-Za-z_][A-Za-z0-9_]*)(?:\.|\b)")

errors: list[str] = []

for package_name, rule in RULES.items():
    package_path = rule["path"]
    if not package_path.exists():
        continue

    forbidden = set(rule["forbidden"])
    for path in sorted(package_path.rglob("*.kt")):
        for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = IMPORT_RE.match(line)
            if not match:
                continue

            imported_package = match.group(1)
            if imported_package in forbidden:
                errors.append(
                    f"{path}:{line_no}: {package_name} must not import "
                    f"com.andsi.airlyrics.{imported_package}: {line.strip()}"
                )

if errors:
    print("Architecture boundary check failed:")
    for error in errors:
        print("  - " + error)
    sys.exit(1)

print("Architecture boundary check passed.")
PY
