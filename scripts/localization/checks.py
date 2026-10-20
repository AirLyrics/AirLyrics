"""Android formatting semantics; naming/changelog rules remain repository policy."""
import re
from collections import Counter

FORMAT = re.compile(r'%(?:(\d+)\$)?([-#+ 0,(<]*)(?:\d+)?(?:\.\d+)?([tT][a-zA-Z]|[a-zA-Z%])')
CJK = re.compile(r'[\u4e00-\u9fff]')


def placeholders(value):
    arguments = []
    ordinary = 0
    previous = None
    for match in FORMAT.finditer(value):
        indexed, flags, conversion = match.groups()
        if conversion in ('%', 'n'):
            continue
        if indexed:
            index = int(indexed)
        elif '<' in flags:
            index = previous if previous is not None else -1
        else:
            ordinary += 1
            index = ordinary
        previous = index
        arguments.append((index, conversion if conversion.startswith(("t", "T")) else conversion.lower()))
    return Counter(arguments)


def stray_percent_positions(value):
    invalid = []
    offset = 0
    while offset < len(value):
        if value[offset] == '%':
            match = FORMAT.match(value, offset)
            if match and match.group(3)[0] in 'bBhHsScCdoxXeEfgGaAtT%n':
                offset = match.end()
                continue
            invalid.append(offset)
        offset += 1
    return invalid


def kotlin_cjk_literals(text):
    """Read literal fragments, skipping nested comments even inside templates."""
    found = []
    offset = 0

    def string(quote):
        nonlocal offset
        start = offset
        line = text.count('\n', 0, start) + 1
        fragments = []
        offset += len(quote)
        while offset < len(text):
            if text.startswith(quote, offset):
                offset += len(quote)
                break
            if quote != "'" and text.startswith('${', offset):
                offset += 2
                code(template=True)
            elif quote != '"""' and text[offset] == '\\':
                escaped = re.match(r'\\u([0-9a-fA-F]{4})', text[offset:])
                if escaped:
                    fragments.append(chr(int(escaped[1], 16)))
                    offset += 6
                else:
                    fragments.append(text[offset:offset + 2])
                    offset += 2
            else:
                fragments.append(text[offset])
                offset += 1
        literal = ''.join(fragments)
        if CJK.search(literal):
            found.append((line, literal))

    def code(template=False):
        nonlocal offset
        depth = 0
        while offset < len(text):
            if text.startswith('//', offset):
                end = text.find('\n', offset)
                offset = len(text) if end < 0 else end
            elif text.startswith('/*', offset):
                nesting = 1
                offset += 2
                while offset < len(text) and nesting:
                    if text.startswith('/*', offset):
                        nesting += 1
                        offset += 2
                    elif text.startswith('*/', offset):
                        nesting -= 1
                        offset += 2
                    else:
                        offset += 1
            elif text.startswith('"""', offset):
                string('"""')
            elif text[offset] in ('"', "'"):
                string(text[offset])
            else:
                char = text[offset]
                offset += 1
                if template and char == '}' and depth == 0:
                    return
                if char == '{':
                    depth += 1
                elif char == '}':
                    depth -= 1

    code()
    return found
