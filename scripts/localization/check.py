from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from checks import placeholders, stray_percent_positions, kotlin_cjk_literals

errors = []
MAX_STRING_KEY_LENGTH = 40
STRING_NAME_RE = re.compile(r'^[a-z][a-z0-9_]*$')
CJK_RE = re.compile(r'[\u4e00-\u9fff]')

# Keys that naturally contain small numbers or units and are not generated from prose.
NUMERIC_KEY_ALLOWLIST = {
    'ui_advance_0_1s',
    'ui_advance_1s',
    'ui_delay_0_1s',
    'ui_delay_1s',
}

# Language names may intentionally be shown in their own writing system even
# when the app is currently using the default English resource bundle. The
# floating preview samples also intentionally use Japanese as the original
# lyric so the translated line remains visually distinct.
DEFAULT_RESOURCE_CJK_ALLOWLIST = {
    'ui_chinese_simplified',
    'ui_chinese_traditional',
    'ui_next_lyric_preview',
    'ui_previous_lyric_preview',
    'ui_this_is_a_lyric_preview',
}

# Input aliases and parser vocabulary are external-data contracts, not UI copy.
CJK_LITERAL_ALLOWLIST = {
    'app/src/main/java/com/andsi/airlyrics/i18n/lyrics/LyricsText.kt': {
        '网易云歌词', '网易云音乐',
    },
    'app/src/main/java/com/andsi/airlyrics/lyrics/parser/LrcParser.kt': {
        '作词|作詞|填词|作曲|',
        '编曲|編曲|译词|翻译|翻訳|',
        '作词人|作詞家|作曲人|制作人|',
        '监制|監製|작사|작곡|편곡|번역|',
    },
}

def fail(message: str) -> None:
    errors.append(message)

def read_text(path: Path) -> str:
    return path.read_text(encoding='utf-8')

print('Checking hardcoded CJK characters in Kotlin source...')
for path in Path('app/src/main/java').rglob('*.kt'):
    for line_no, literal in kotlin_cjk_literals(read_text(path)):
        if literal not in CJK_LITERAL_ALLOWLIST.get(path.as_posix(), set()):
            fail(f'{path}:{line_no}: CJK string literal: {literal}')

print('Checking Android string resources...')
resource_paths = [
    Path('app/src/main/res/values/strings.xml'),
    Path('app/src/main/res/values-es/strings.xml'),
    Path('app/src/main/res/values-zh-rCN/strings.xml'),
    Path('app/src/main/res/values-zh-rTW/strings.xml'),
]
resources: dict[str, dict[str, str]] = {}
for path in resource_paths:
    try:
        root = ET.parse(path).getroot()
    except Exception as exc:
        fail(f'{path}: XML parse failed: {exc}')
        continue

    strings: dict[str, str] = {}
    names: list[str] = []
    for el in root.findall('string'):
        name = el.attrib.get('name', '')
        value = ''.join(el.itertext())
        names.append(name)
        strings[name] = value

        if not STRING_NAME_RE.match(name):
            fail(f'{path}: invalid string key name: {name}')
        if len(name) > MAX_STRING_KEY_LENGTH:
            fail(f'{path}: string key is too long ({len(name)}>{MAX_STRING_KEY_LENGTH}): {name}')
        if re.search(r'_[0-9a-f]{6}$', name):
            fail(f'{path}: generated hash-like string key is not allowed: {name}')
        if name not in NUMERIC_KEY_ALLOWLIST and re.search(r'_(?:\d+_){2,}\d+|_\d+s?$', name):
            fail(f'{path}: sentence-derived numeric/time fragment in string key: {name}')
        if name.endswith('_') or '__' in name:
            fail(f'{path}: malformed string key: {name}')

        percent_errors = stray_percent_positions(value)
        if percent_errors:
            fail(f'{path}: unescaped or invalid percent placeholder in {name}')

    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        fail(f'{path}: duplicate string names: {duplicates}')
    resources[str(path)] = strings

if len(resources) == len(resource_paths):
    default_path = str(resource_paths[0])
    default_strings = resources[default_path]
    default_names = set(default_strings)

    if any(
        CJK_RE.search(value)
        for name, value in default_strings.items()
        if name not in DEFAULT_RESOURCE_CJK_ALLOWLIST
    ):
        bad = sorted(
            name
            for name, value in default_strings.items()
            if name not in DEFAULT_RESOURCE_CJK_ALLOWLIST and CJK_RE.search(value)
        )
        fail(f'{resource_paths[0]}: default English resources contain CJK text: {bad}')

    for path in resource_paths[1:]:
        localized_strings = resources[str(path)]
        names = set(localized_strings)
        missing = sorted(default_names - names)
        extra = sorted(names - default_names)
        if missing:
            fail(f'{path}: missing keys from default resources: {missing}')
        if extra:
            fail(f'{path}: extra keys not in default resources: {extra}')

        common = sorted(default_names & names)
        for name in common:
            default_placeholders = placeholders(default_strings[name])
            localized_placeholders = placeholders(localized_strings[name])
            if default_placeholders != localized_placeholders:
                fail(
                    f'{path}: placeholder mismatch for {name}: '
                    f'default={default_placeholders}, localized={localized_placeholders}'
                )

    print('Checking R.string references...')
    refs = set()
    for path in Path('app/src/main/java').rglob('*.kt'):
        text = read_text(path)
        refs.update(re.findall(r'R\.string\.([A-Za-z0-9_]+)', text))
    missing_refs = sorted(refs - default_names)
    if missing_refs:
        fail(f'Kotlin references missing string resources: {missing_refs}')

print('Checking changelog policy...')
allowed_changelog_variants = {
    Path('app/src/main/assets/changelog_current.txt'),
}
localized_changelogs = sorted(
    path for path in Path('app/src/main/assets').glob('changelog_*.txt')
    if path not in allowed_changelog_variants
)
if localized_changelogs:
    fail(
        'Localized changelog files are not allowed. '
        'Use app/src/main/assets/changelog.txt and app/src/main/assets/changelog_current.txt only: '
        + ', '.join(map(str, localized_changelogs))
    )

if errors:
    print('Localization check failed:')
    for error in errors:
        print('  - ' + error)
    sys.exit(1)

print('Localization check passed.')
