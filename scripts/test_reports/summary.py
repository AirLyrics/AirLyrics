"""Summarize JUnit XML without concealing failures or skipped cases."""
import argparse
from collections import defaultdict
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def collect_results(root):
    totals = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
    lines = []
    outcomes = defaultdict(set)
    fields = {'failure': 'failures', 'error': 'errors', 'skipped': 'skipped'}
    for path in sorted(Path(root).rglob('TEST-*.xml')):
        document = ET.parse(path).getroot()
        suites = [document] if document.tag == 'testsuite' else document.findall('.//testsuite')
        for suite in suites:
            cases = suite.findall('testcase')
            totals['suites'] += 1
            totals['tests'] += len(cases)
            for case in cases:
                case_id = f"{case.get('classname', '')}#{case.get('name', '')}"
                statuses = {kind for kind in fields if case.find(kind) is not None}
                outcomes[case_id].update(statuses)
                for kind in statuses:
                    totals[fields[kind]] += 1
            lines.append(f"- {suite.get('name', path.stem)}: {len(cases)} cases")
    return totals, lines, outcomes


def summarize(root):
    totals, lines, _ = collect_results(root)
    return totals, lines


def test_id(value):
    classname, separator, method = value.partition('#')
    if not (classname and separator and method) or '#' in method:
        raise argparse.ArgumentTypeError('Expected CLASS#METHOD')
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('root')
    parser.add_argument('--strict', action='store_true', help='Require tests and reject failures, errors, and all-skipped runs')
    parser.add_argument('--min-tests', type=int, default=0, help='Require at least this many test cases')
    parser.add_argument('--require-test', action='append', type=test_id, default=[], metavar='CLASS#METHOD',
                        help='Require a test to appear and pass')
    parser.add_argument('--allow-skipped-test', action='append', type=test_id, default=[], metavar='CLASS#METHOD',
                        help='In strict mode, reject skips outside the listed test IDs')
    args = parser.parse_args()
    totals, lines, outcomes = collect_results(args.root)
    output = f"Test results ({args.root}): {totals}\n" + '\n'.join(lines) + '\n'
    print(output)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as report:
            report.write(output)
    problems = []
    for required in args.require_test:
        if required not in outcomes:
            problems.append(f'Missing required test: {required}')
        elif outcomes[required]:
            statuses = ', '.join(sorted(outcomes[required]))
            problems.append(f'Required test did not pass: {required} ({statuses})')
    if args.strict:
        if totals['tests'] == totals['skipped'] or totals['failures'] or totals['errors']:
            problems.append('Tests did not complete cleanly; see XML and diagnostics')
        if args.allow_skipped_test:
            allowed_skips = set(args.allow_skipped_test)
            for case_id, statuses in outcomes.items():
                if 'skipped' in statuses and case_id not in allowed_skips:
                    problems.append(f'Unexpected skipped test: {case_id}')
    if totals['tests'] < args.min_tests:
        problems.append(f"Only {totals['tests']} tests ran; expected at least {args.min_tests}")
    if problems:
        raise SystemExit('\n'.join(problems))


if __name__ == '__main__':
    main()
