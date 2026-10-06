"""Inspect actual class dependencies with the JDK tool, not a custom class-file parser."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def jdk_tool(name):
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / name if home else None
    if candidate and candidate.is_file():
        return str(candidate)
    found = shutil.which(name)
    if not found:
        raise ValueError(f"{name} not found; configure a JDK 17 JAVA_HOME")
    return found


def check_bytecode(paths, policy):
    classes = set()
    for path in paths:
        path = Path(path)
        if path.is_dir():
            classes.update(p.relative_to(path).as_posix()[:-6].replace("/", ".") for p in path.rglob("*.class"))
        elif path.suffix == ".jar" and path.is_file():
            with zipfile.ZipFile(path) as archive:
                classes.update(n[:-6].replace("/", ".") for n in archive.namelist() if n.endswith(".class"))
        else:
            raise ValueError(f"Expected project class directory or jar, got: {path}")
    project = {c for c in classes if c.startswith(policy.namespace + ".")}
    if not project:
        raise ValueError("No project classes found; refusing to pass an empty bytecode check")
    errors, references, groups = [], [], set()
    for name in sorted(project):
        relative = policy.relative(name)
        if policy.is_generated(relative):
            continue
        group = policy.owner(relative)
        if group is None:
            errors.append(f"bytecode {name}: unregistered source class")
        else:
            groups.add(group)
    command = [jdk_tool("jdeps"), "-verbose:class", "-filter:none", "--ignore-missing-deps", *map(str, paths)]
    result = subprocess.run(command, text=True, capture_output=True, check=True)
    edges = 0
    for line in result.stdout.splitlines():
        match = re.match(r"\s*(\S+)\s+->\s+(\S+)\s+", line)
        if not match or match[1] not in project:
            continue
        edges += 1
        source, target = match.groups()
        relative = policy.relative(source)
        if policy.is_generated(relative):
            continue
        group = policy.owner(relative)
        if not group:
            continue
        references.append((group, target, f"bytecode {source}"))
        target_relative = policy.relative(target)
        if target_relative is not None and not policy.is_generated(target_relative) and target not in project:
            errors.append(f"bytecode {source}: missing project dependency {target}")
    if not edges:
        raise ValueError("jdeps produced no class dependency records")
    return errors, references, groups
