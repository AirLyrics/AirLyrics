#!/usr/bin/env python3
"""Build a host JNI probe and test the existing lyrics providers with an input file."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
from datetime import datetime, timezone
from collections import Counter

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PROVIDERS = ('netease', 'musixmatch', 'lrclib')


def load_cases(path):
    data = json.loads(path.read_text(encoding='utf-8'))
    source = data['cases'] if isinstance(data, dict) else data
    if not isinstance(source, list) or not source:
        raise ValueError('Input must contain a non-empty cases list')
    if isinstance(data, dict) and 'case_count' in data and data['case_count'] != len(source):
        raise ValueError('case_count does not match cases length')

    cases = []
    seen = set()
    for index, item in enumerate(source, 1):
        if not isinstance(item, dict):
            raise ValueError(f'Case {index} must be an object')
        case_id = item.get('case_id', f'CASE{index:04d}')
        title = item.get('query_title', item.get('title'))
        artist = item.get('query_artist', item.get('artist', ''))
        duration = item.get('duration_sec', 0)
        group = item.get('test_group', 'primary')
        if not isinstance(case_id, str) or not case_id or case_id in seen:
            raise ValueError(f'Case {index} has a missing or duplicate case_id')
        if not isinstance(title, str) or not title.strip():
            raise ValueError(f'Case {index} needs a title')
        if not isinstance(artist, str) or not isinstance(item.get('album', ''), str):
            raise ValueError(f'Case {index} has invalid artist or album')
        if isinstance(duration, bool) or not isinstance(duration, (int, float)) or not math.isfinite(duration) or duration < 0:
            raise ValueError(f'Case {index} has invalid duration_sec')
        if not isinstance(group, str) or not group.strip() or group == 'all':
            raise ValueError(f'Case {index} has invalid test_group')
        seen.add(case_id)
        cases.append(dict(case_id=case_id, query_title=title.strip(), query_artist=artist.strip(),
                          album=item.get('album', '').strip(), duration_sec=duration, test_group=group))
    return cases


def load_rows(output, providers=PROVIDERS):
    rows = []
    for provider in providers:
        for line in (output/f'{provider}.jsonl').read_text().splitlines():
            rows.append(json.loads(line))
    return rows


def summarize(output, cases, providers=PROVIDERS):
    rows = load_rows(output, providers)
    summary = {}
    for group in ('all', *dict.fromkeys(c['test_group'] for c in cases)):
        ids = {c['case_id'] for c in cases if group == 'all' or c['test_group'] == group}
        if not ids:
            continue
        stats = {}
        for provider in providers:
            subset = [r for r in rows if r['provider'] == provider and r['case_id'] in ids]
            counts = Counter(r['status'] for r in subset)
            found = counts['found']
            stats[provider] = dict(total=len(ids), tested=len(subset), found=found,
                not_found=counts['not_found'], error=counts['error'], coverage_percent=round(100*found/len(ids), 2),
                errors=dict(Counter(r.get('error_type', 'Unknown') for r in subset if r['status'] == 'error')))
        union = {r['case_id'] for r in rows if r['case_id'] in ids and r['status'] == 'found'}
        summary[group] = dict(providers=stats, union_found=len(union), total=len(ids), union_coverage_percent=round(100*len(union)/len(ids), 2))
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(summary['all'], ensure_ascii=False, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--prepare-only', action='store_true', help='Compile and validate inputs without API requests')
    parser.add_argument('--input', '--cases', dest='input', type=Path, required=True,
                        help='JSON file containing a list of songs or an object with a cases list')
    parser.add_argument('--providers', nargs='+', choices=PROVIDERS, default=PROVIDERS)
    parser.add_argument('--proxy', help='Explicit HTTP proxy for the probe processes (no credentials in URL)')
    args = parser.parse_args()
    fixture = args.input.resolve()
    try:
        cases = load_cases(fixture)
    except (OSError, ValueError, KeyError) as error:
        parser.error(f'Invalid input file: {error}')
    env = dict(os.environ, JAVA_HOME='/usr/lib/jvm/java-17-openjdk')
    env['PATH'] = env['JAVA_HOME'] + '/bin:' + env['PATH']
    if args.proxy:
        from urllib.parse import urlsplit
        proxy = urlsplit(args.proxy)
        if proxy.scheme not in ('http', 'https') or not proxy.hostname or proxy.username or proxy.password:
            parser.error('--proxy must be an HTTP(S) URL without credentials')
        for name in ('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'http_proxy', 'https_proxy', 'all_proxy'):
            env[name] = args.proxy
        env['NO_PROXY'] = env['no_proxy'] = 'localhost,127.0.0.1'
    subprocess.run(['cargo', 'build', '--manifest-path', 'lyrics-core/Cargo.toml'], cwd=ROOT, env=env, check=True)
    subprocess.run(['./gradlew', ':app:bundleDebugClassesToRuntimeJar', '-Pairlyrics.skipRustBuild=true'], cwd=ROOT, env=env, check=True)
    cache = Path(os.environ.get('GRADLE_USER_HOME', '/mnt/data/dev-cache/gradle')) / 'caches/modules-2/files-2.1'
    jars = [ROOT / 'app/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar']
    for artifact, version in [('org.json/json', '20260814'), ('org.jetbrains.kotlin/kotlin-stdlib', '2.4.10')]:
        jars.append(next(p for p in (cache / artifact / version).rglob('*.jar') if not p.name.endswith(('-sources.jar', '-javadoc.jar'))))
    with tempfile.TemporaryDirectory(prefix='airlyrics-benchmark-') as build:
        cp = os.pathsep.join(map(str, [Path(build), *jars]))
        subprocess.run([env['JAVA_HOME']+'/bin/javac', '-cp', cp, '-d', build, str(HERE/'Benchmark.java')], check=True)
        if args.prepare_only:
            print(f'Prepared {len(cases)} cases × {len(args.providers)} providers; no API requests sent.')
            return
        output = args.output.resolve()
        output.mkdir(parents=True, exist_ok=False)
        normalized_fixture = Path(build) / 'cases.json'
        normalized_fixture.write_text(json.dumps({'cases': cases}, ensure_ascii=False), encoding='utf-8')
        source_paths = sorted([*ROOT.glob('lyrics-core/src/**/*.rs'), *ROOT.glob('lyrics-core/data/**/*'), *ROOT.glob('app/src/main/java/**/*.kt'), HERE/'Benchmark.java', HERE/'run.py'])
        manifest = dict(started_at=datetime.now(timezone.utc).isoformat(), fixture_sha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
            providers=args.providers, case_count=len(cases),
            git_head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_paths if p.is_file()},
            translation_language='zh', pause_seconds=1, retries=0,
            explicit_proxy=args.proxy,
            provider_execution='sequential',
            scope='Host JNI + production parsers; no repository/local cache or UI; returned matches not manually verified')
        (output/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
        def run(provider):
            subprocess.run([env['JAVA_HOME']+'/bin/java', '-Djava.library.path='+str(ROOT/'lyrics-core/target/debug'), '-cp', cp,
                'Benchmark', provider, str(normalized_fixture), str(output/f'{provider}.jsonl')],
                check=True, stderr=subprocess.DEVNULL, env=env)
        for provider in args.providers:
            run(provider)
        incomplete = [provider for provider in args.providers if len((output/f'{provider}.jsonl').read_text().splitlines()) < len(cases)]
        if incomplete:
            raise RuntimeError(f'Upstream refusal stopped {", ".join(incomplete)}; checkpoints kept at {output}; no coverage summary generated')
        summarize(output, cases, args.providers)


if __name__ == '__main__':
    main()
