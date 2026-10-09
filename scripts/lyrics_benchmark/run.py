#!/usr/bin/env python3
"""Build a host JNI probe and test the existing lyrics providers with an input file."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from collections import Counter

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PROVIDERS = ('netease', 'musixmatch', 'lrclib', 'spicy')
DEFAULT_PROVIDERS = ('netease', 'lrclib', 'spicy')
MATCHING_SOURCE_PREFIXES = ('lyrics-core/src/', 'lyrics-core/data/', 'app/src/main/java/')


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
        path = output/f'{provider}.jsonl'
        for line in path.read_text().splitlines() if path.exists() else ():
            rows.append(json.loads(line))
    return rows


def resume_positions(output, cases, providers):
    expected_ids = [case['case_id'] for case in cases]
    positions = {}
    for provider in providers:
        path = output/f'{provider}.jsonl'
        rows = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        if len(rows) > len(cases) or any(row.get('provider') != provider or row.get('case_id') != expected_ids[i]
                                              for i, row in enumerate(rows)):
            raise ValueError(f'{provider} results are not a prefix of the input file')
        positions[provider] = len(rows)
    return positions


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
            completed = len(subset) == len(ids)
            word_found = sum(r['status'] == 'found' and r.get('word_found', False) for r in subset)
            stats[provider] = dict(total=len(ids), tested=len(subset), complete=completed,
                found=found, not_found=counts['not_found'], error=counts['error'],
                coverage_percent=round(100*found/len(ids), 2) if completed else None,
                observed_hit_rate_percent=round(100*found/len(subset), 2) if subset else None,
                word_found=word_found,
                word_coverage_percent=round(100*word_found/len(ids), 2) if provider == 'spicy' and completed else None,
                errors=dict(Counter(r.get('error_type', 'Unknown') for r in subset if r['status'] == 'error')))
        union = {r['case_id'] for r in rows if r['case_id'] in ids and r['status'] == 'found'}
        complete = all(stat['complete'] for stat in stats.values())
        summary[group] = dict(providers=stats, union_found=len(union), total=len(ids), complete=complete,
            union_coverage_percent=round(100*len(union)/len(ids), 2) if complete else None)
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(summary['all'], ensure_ascii=False, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--prepare-only', action='store_true', help='Compile and validate inputs without API requests')
    parser.add_argument('--resume', action='store_true', help='Continue an interrupted run with the same input and matching code')
    parser.add_argument('--input', '--cases', dest='input', type=Path, required=True,
                        help='JSON file containing a list of songs or an object with a cases list')
    parser.add_argument('--providers', nargs='+', choices=PROVIDERS, default=DEFAULT_PROVIDERS,
                        help='Sources to query; default excludes frequently rate-limited Musixmatch')
    parser.add_argument('--proxy', help='Explicit HTTP proxy for the probe processes (no credentials in URL)')
    args = parser.parse_args()
    args.providers = tuple(dict.fromkeys(args.providers))
    if args.resume and args.prepare_only:
        parser.error('--resume cannot be combined with --prepare-only')
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
        if 'spicy' in args.providers:
            subprocess.run([env['JAVA_HOME']+'/bin/java', '-cp', cp, 'Benchmark', '--check-spicy-key'],
                           check=True, env=env)
        if args.prepare_only:
            print(f'Prepared {len(cases)} cases × {len(args.providers)} providers; no API requests sent.')
            return 0
        output = args.output.resolve()
        normalized_fixture = Path(build) / 'cases.json'
        normalized_fixture.write_text(json.dumps({'cases': cases}, ensure_ascii=False), encoding='utf-8')
        source_paths = sorted([*ROOT.glob('lyrics-core/src/**/*.rs'), *ROOT.glob('lyrics-core/data/**/*'), *ROOT.glob('app/src/main/java/**/*.kt'),
                               HERE/'Benchmark.java', HERE/'run.py', HERE/'report.py', HERE/'compare.py'])
        source_sha256 = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_paths if p.is_file()}
        fixture_sha256 = hashlib.sha256(fixture.read_bytes()).hexdigest()
        if args.resume:
            if not (output/'manifest.json').is_file():
                parser.error('--resume requires an existing benchmark output directory')
            manifest = json.loads((output/'manifest.json').read_text())
            if (manifest['fixture_sha256'] != fixture_sha256 or tuple(manifest['providers']) != args.providers
                    or manifest.get('explicit_proxy') != args.proxy):
                parser.error('Input file, providers and proxy must match the original run')
            old_matching_sources = {k: v for k, v in manifest['source_sha256'].items()
                                    if k.startswith(MATCHING_SOURCE_PREFIXES)}
            current_matching_sources = {k: v for k, v in source_sha256.items()
                                        if k.startswith(MATCHING_SOURCE_PREFIXES)}
            if old_matching_sources != current_matching_sources:
                parser.error('Matching code changed; start a new run for a valid comparison')
            try:
                positions = resume_positions(output, cases, args.providers)
            except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
                parser.error(f'Cannot resume results: {error}')
            manifest.setdefault('resumptions', []).append(dict(started_at=datetime.now(timezone.utc).isoformat(),
                harness_source_sha256={k: v for k, v in source_sha256.items()
                                       if not k.startswith(MATCHING_SOURCE_PREFIXES)}))
        else:
            output.mkdir(parents=True, exist_ok=False)
            positions = {provider: 0 for provider in args.providers}
            manifest = dict(started_at=datetime.now(timezone.utc).isoformat(), fixture_sha256=fixture_sha256,
                providers=args.providers, case_count=len(cases),
                git_head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                source_sha256=source_sha256, translation_language='zh', pause_seconds=1, benchmark_retries=0,
                explicit_proxy=args.proxy, provider_execution='sequential',
                scope='Host JNI + production parsers; no repository/local cache or UI; returned matches not manually verified')
        manifest.pop('process_failures', None)
        (output/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
        def run(provider, start_index):
            subprocess.run([env['JAVA_HOME']+'/bin/java', '-Djava.library.path='+str(ROOT/'lyrics-core/target/debug'), '-cp', cp,
                'Benchmark', provider, str(normalized_fixture), str(output/f'{provider}.jsonl'), str(start_index)],
                check=True, stderr=subprocess.DEVNULL, env=env)
        process_failures = {}
        for provider in args.providers:
            start_index = positions[provider]
            if start_index == len(cases):
                continue
            path = output/f'{provider}.jsonl'
            if start_index == 0 and path.exists():
                path.unlink()
            try:
                run(provider, start_index)
            except subprocess.CalledProcessError as error:
                process_failures[provider] = error.returncode
                print(f'{provider} process failed with exit code {error.returncode}; continuing with remaining sources', file=sys.stderr)
        if process_failures:
            manifest['process_failures'] = process_failures
            (output/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
        from report import render_report
        render_report(output, cases, manifest)
        incomplete = [provider for provider in args.providers
                      if not (output/f'{provider}.jsonl').exists() or len((output/f'{provider}.jsonl').read_text().splitlines()) < len(cases)]
        if incomplete:
            print(f'Incomplete sources: {", ".join(incomplete)}; partial results and report saved at {output}', file=sys.stderr)
            return 2
        return 0


if __name__ == '__main__':
    raise SystemExit(main())
