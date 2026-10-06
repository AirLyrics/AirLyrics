"""Dependency policy shared by source, bytecode, tests, and generated documentation."""
import json
from pathlib import Path


def within(name, prefix):
    return name == prefix or name.startswith(prefix + ".")


def cycles(graph):
    """Return deterministic cycle paths, not merely the names of cyclic nodes."""
    active, done, found = [], set(), set()

    def visit(node):
        if node in active:
            ring = active[active.index(node):]
            rotations = [ring[i:] + ring[:i] for i in range(len(ring))]
            ring = min(rotations)
            found.add(tuple(ring + [ring[0]]))
            return
        if node in done:
            return
        active.append(node)
        for other in sorted(graph.get(node, ())):
            visit(other)
        active.pop()
        done.add(node)

    for node in sorted(graph):
        visit(node)
    return [" -> ".join(path) for path in sorted(found)]


class Policy:
    def __init__(self, data):
        self.data = data
        self.namespace = data["namespace"]
        self.groups = data["groups"]
        self.generated = data["generated_symbols"]
        if not self.groups:
            raise ValueError("No architecture groups registered")
        for group, rule in self.groups.items():
            if not group or "*" in group:
                raise ValueError(f"Invalid group: {group}")
            seen = set()
            for allowance in rule["allows"]:
                if set(allowance) - {"group", "symbols", "reason"}:
                    raise ValueError(f"Unknown allowance fields in {group}: {allowance}")
                target = allowance["group"]
                if target not in self.groups or target == group or target in seen:
                    raise ValueError(f"Invalid/duplicate allowance: {group} -> {target}")
                seen.add(target)
                if not allowance.get("reason", "").strip():
                    raise ValueError(f"Allowance requires a reason: {group} -> {target}")
                symbols = allowance.get("symbols")
                if symbols is not None and (not symbols or any(
                    "*" in s or self.owner(s) != target for s in symbols
                )):
                    raise ValueError(f"Invalid symbols: {group} -> {target}")
        graph = {g: {a["group"] for a in r["allows"]} for g, r in self.groups.items()}
        if paths := cycles(graph):
            raise ValueError("Allowed dependency cycle: " + "; ".join(paths))

    @classmethod
    def load(cls, path):
        return cls(json.loads(Path(path).read_text(encoding="utf-8")))

    def relative(self, name):
        name = name.replace("$", ".")
        return name[len(self.namespace) + 1:] if name.startswith(self.namespace + ".") else None

    def owner(self, relative):
        matches = [group for group in self.groups if within(relative, group)]
        return max(matches, key=len) if matches else None

    def is_generated(self, relative):
        return any(within(relative, symbol) for symbol in self.generated)

    def violation(self, source_group, target):
        relative = self.relative(target)
        if relative is None or self.is_generated(relative):
            return None
        owner = self.owner(relative)
        if owner is None:
            return f"unregistered target {target}"
        if owner == source_group:
            return None
        for allowance in self.groups[source_group]["allows"]:
            if allowance["group"] != owner:
                continue
            if "symbols" not in allowance or any(within(relative, s) for s in allowance["symbols"]):
                return None
        return f"{source_group} must not depend on {target} (owner: {owner})"

    def record(self, graph, source_group, target):
        relative = self.relative(target)
        if relative is not None and not self.is_generated(relative):
            owner = self.owner(relative)
            if owner and owner != source_group:
                graph.setdefault(source_group, set()).add(owner)

    def markdown(self):
        lines = ["<!-- architecture-policy:start -->", "```text"]
        for group, rule in self.groups.items():
            allowed = []
            for item in rule["allows"]:
                allowed.extend(item.get("symbols", [item["group"]]))
            lines.append(f"{group:<12} -> {', '.join(allowed) or '(none)'}")
        lines.extend(["```", "<!-- architecture-policy:end -->"])
        return "\n".join(lines)
