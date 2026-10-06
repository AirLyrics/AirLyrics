#!/usr/bin/env python3
"""Read-only architecture checks unless --update-docs is explicitly requested."""
import argparse
from pathlib import Path
import re
import subprocess
import sys

sys.dont_write_bytecode = True
from bytecode import check_bytecode
from policy import Policy, cycles
from sources import check_sources


def documentation(root, policy, update=False):
    errors = []
    pattern = re.compile(r"<!-- architecture-policy:start -->.*?<!-- architecture-policy:end -->", re.S)
    for filename in ("docs/ARCHITECTURE.md", "docs/ARCHITECTURE.zh-CN.md"):
        path = root / filename
        text = path.read_text(encoding="utf-8")
        matches = list(pattern.finditer(text))
        match = matches[0] if matches else None
        if len(matches) != 1:
            errors.append(f"{filename}: expected exactly one generated architecture policy block")
        elif match[0] != policy.markdown():
            if update:
                path.write_text(pattern.sub(lambda _: policy.markdown(), text), encoding="utf-8")
            else:
                errors.append(f"{filename}: policy table is stale; run check_architecture_boundaries.sh --update-docs")
    return errors


def verify(references, policy):
    errors, graph = [], {}
    for group, target, location in references:
        if problem := policy.violation(group, target):
            errors.append(f"{location}: {problem}")
        policy.record(graph, group, target)
    errors.extend("Dependency cycle: " + path for path in cycles(graph))
    return sorted(set(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--classes", type=Path, nargs="+")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--update-docs", action="store_true")
    args = parser.parse_args()
    policy = Policy.load(args.root / "scripts/architecture/policy.json")
    errors = documentation(args.root, policy, args.update_docs)
    if args.self_test:
        subprocess.run([sys.executable, "-B", "-m", "unittest", "discover", "-s", str(Path(__file__).parent), "-p", "test_*.py"], check=True)
    source_errors, references, source_groups = check_sources(args.root, policy)
    errors.extend(source_errors)
    if args.classes:
        byte_errors, byte_refs, byte_groups = check_bytecode(args.classes, policy)
        errors.extend(byte_errors)
        references.extend(byte_refs)
        for group in sorted(source_groups - byte_groups):
            errors.append(f"No compiled classes for source group {group}; rebuild production classes")
    errors.extend(verify(references, policy))
    if errors:
        print("Architecture boundary check failed:")
        for error in sorted(set(errors)):
            print("  - " + error)
        return 1
    mode = "source + JVM dependencies" if args.classes else "source only; JVM dependencies not checked"
    print(f"Architecture boundary check passed ({mode}).")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Architecture checker error: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr, file=sys.stderr)
        sys.exit(1)
