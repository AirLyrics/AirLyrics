"""Fast lexical checks. Resolved JVM dependencies are checked separately with jdeps."""
from dataclasses import dataclass
import re


@dataclass(frozen=True)
class Token:
    text: str
    line: int
    escaped: bool = False


def tokens(text):
    """Skip comments/literal text, but scan Kotlin ${...} template expressions as code."""
    result = []
    i, line = 0, 1

    def advance(count=1):
        nonlocal i, line
        line += text[i:i + count].count("\n")
        i += count

    def string(quote):
        advance(len(quote))
        while i < len(text):
            if text.startswith(quote, i):
                advance(len(quote))
                return
            if quote != '"""' and text[i] == "\\":
                advance(min(2, len(text) - i))
            elif quote != "'" and text.startswith("${", i):
                advance(2)
                code(template=True)
            else:
                advance()
        raise ValueError(f"Unterminated string at line {line}")

    def code(template=False):
        depth = 0
        while i < len(text):
            start_line = line
            if text.startswith("//", i):
                while i < len(text) and text[i] != "\n":
                    advance()
            elif text.startswith("/*", i):
                advance(2)
                nesting = 1
                while i < len(text) and nesting:
                    if text.startswith("/*", i):
                        nesting += 1
                        advance(2)
                    elif text.startswith("*/", i):
                        nesting -= 1
                        advance(2)
                    else:
                        advance()
                if nesting:
                    raise ValueError(f"Unterminated comment at line {start_line}")
            elif text.startswith('"""', i):
                string('"""')
            elif text[i] in ('"', "'"):
                string(text[i])
            elif text[i] == "`":
                advance()
                start = i
                while i < len(text) and text[i] != "`":
                    advance()
                if i == len(text):
                    raise ValueError(f"Unterminated identifier at line {start_line}")
                result.append(Token(text[start:i], start_line, escaped=True))
                advance()
            elif text[i].isalpha() or text[i] in "_$":
                start = i
                while i < len(text) and (text[i].isalnum() or text[i] in "_$"):
                    advance()
                result.append(Token(text[start:i], start_line))
            else:
                char = text[i]
                advance()
                if char == "}" and template and depth == 0:
                    return
                if char == "{":
                    depth += 1
                elif char == "}":
                    depth -= 1
                if not char.isspace():
                    result.append(Token(char, start_line))
        if template:
            raise ValueError("Unterminated template expression")

    code()
    return result


def qualified(items, start):
    parts = [items[start].text]
    end = start + 1
    while end + 1 < len(items) and items[end].text == "." and re.fullmatch(r"[\w$]+|\*", items[end + 1].text):
        parts.append(items[end + 1].text)
        end += 2
    return ".".join(parts).removesuffix(".*"), end


def analyze_file(path, source_root, policy):
    text = path.read_text(encoding="utf-8")
    items = tokens(text)
    declarations = [(n, t) for n, t in enumerate(items) if t.text == "package" and not t.escaped]
    if len(declarations) != 1:
        return [f"{path}: expected exactly one package declaration"], [], None
    index, _ = declarations[0]
    package, package_end = qualified(items, index + 1)
    relative = policy.relative(package)
    group = policy.owner(relative) if relative else None
    if group is None:
        return [f"{path}: unregistered source package {package}"], [], None
    errors = []
    if path.parent.relative_to(source_root).as_posix() != package.replace(".", "/"):
        errors.append(f"{path}: directory does not match declared package {package}")
    references = []
    n = 0
    while n < len(items):
        name, end = qualified(items, n)
        if not (index <= n < package_end) and name.startswith(policy.namespace + "."):
            references.append((group, name, f"{path}:{items[n].line}"))
        n = end
    return errors, references, group


def source_files(root, policy):
    found = []
    for name in policy.data["source_roots"]:
        directory = root / name
        if directory.is_dir():
            found.extend((p, directory) for p in directory.rglob("*") if p.suffix in (".kt", ".java"))
    return sorted(found)


def check_sources(root, policy):
    errors, references, groups = [], [], set()
    files = source_files(root, policy)
    covered = {path for path, _ in files}
    source_tree = root / "app/src"
    for path in sorted(source_tree.rglob("*")):
        if path.suffix not in (".kt", ".java"):
            continue
        relative = path.relative_to(source_tree)
        if relative.parts[0] in policy.data["excluded_source_sets"]:
            continue
        if path not in covered:
            errors.append(f"{path}: unregistered production source root; update source_roots in policy.json")
    if not files:
        errors.append("No production source files found")
    for path, directory in files:
        file_errors, refs, group = analyze_file(path, directory, policy)
        errors.extend(file_errors)
        references.extend(refs)
        if group:
            groups.add(group)
    return errors, references, groups
