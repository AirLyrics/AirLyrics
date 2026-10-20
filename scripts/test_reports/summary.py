"""Summarize JUnit XML without concealing failures or skipped cases."""
import argparse
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(root):
    totals = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
    lines = []
    for path in sorted(Path(root).rglob('TEST-*.xml')):
        document = ET.parse(path).getroot()
        suites = [document] if document.tag == 'testsuite' else document.findall('.//testsuite')
        for suite in suites:
            cases = suite.findall('testcase')
            totals['suites'] += 1
            totals['tests'] += len(cases)
            for kind in ('failure', 'error', 'skipped'):
                count = sum(case.find(kind) is not None for case in cases)
                totals[{'failure': 'failures', 'error': 'errors', 'skipped': 'skipped'}[kind]] += count
            lines.append(f"- {suite.get('name', path.stem)}: {len(cases)} cases")
    return totals, lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('root')
    parser.add_argument('--strict', action='store_true', help='Require nonzero tests and no failures/errors/skips')
    args = parser.parse_args()
    totals, lines = summarize(args.root)
    output = f"Test results ({args.root}): {totals}\n" + '\n'.join(lines) + '\n'
    print(output)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as report:
            report.write(output)
    if args.strict and (not totals['tests'] or any(totals[k] for k in ('failures', 'errors', 'skipped'))):
        raise SystemExit('Tests did not complete cleanly; see XML and diagnostics')


if __name__ == '__main__':
    main()
