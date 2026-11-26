import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from summary import summarize

class TestSummaryTest(unittest.TestCase):
    def test_counts_actual_cases_and_preserves_failure_error_and_skip(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, 'TEST-example.xml').write_text('''<testsuite name="expanded" tests="99">
              <testcase name="parameter[1]"/><testcase name="parameter[2]"><failure/></testcase>
              <testcase name="parameter[3]"><skipped/></testcase><testcase name="parameter[4]"><error/></testcase>
            </testsuite>''')
            totals, lines = summarize(root)
            self.assertEqual(dict(suites=1, tests=4, failures=1, errors=1, skipped=1), totals)
            self.assertEqual(['- expanded: 4 cases'], lines)
    def test_empty_directory_reports_zero(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(0, summarize(root)[0]['tests'])

    def test_required_case_passes_when_class_and_method_match(self):
        result = self.run_summary(
            '<testcase classname="fixture.UpdaterTest" name="downloads"/>',
            '--strict', '--min-tests', '1',
            '--require-test', 'fixture.UpdaterTest#downloads',
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_required_case_is_missing_when_class_or_method_differs(self):
        for classname, method in (('other.UpdaterTest', 'downloads'), ('fixture.UpdaterTest', 'opens')):
            with self.subTest(classname=classname, method=method):
                result = self.run_summary(
                    f'<testcase classname="{classname}" name="{method}"/>',
                    '--require-test', 'fixture.UpdaterTest#downloads',
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Missing required test: fixture.UpdaterTest#downloads', result.stderr)

    def test_required_case_must_pass_even_when_its_skip_is_allowed(self):
        for outcome in ('skipped', 'failure', 'error'):
            with self.subTest(outcome=outcome):
                result = self.run_summary(
                    f'<testcase classname="fixture.UpdaterTest" name="downloads"><{outcome}/></testcase>',
                    '--require-test', 'fixture.UpdaterTest#downloads',
                    '--allow-skipped-test', 'fixture.UpdaterTest#downloads',
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Required test did not pass: fixture.UpdaterTest#downloads', result.stderr)

    def test_device_strict_counts_cases_and_accepts_an_unrequired_skip(self):
        result = self.run_summary(
            '<testcase classname="fixture.DeviceTest" name="runs"/>'
            '<testcase classname="fixture.DeviceTest" name="optional"><skipped/></testcase>',
            '--strict', '--min-tests', '2', '--require-test', 'fixture.DeviceTest#runs',
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("'skipped': 1", result.stdout)

    def test_strict_accepts_only_explicitly_allowed_skips_when_listed(self):
        skipped = '<testcase classname="fixture.UpdaterTest" name="downloads"><skipped/></testcase>'
        cases = skipped + '<testcase classname="fixture.UpdaterTest" name="opens"/>'
        allowed = self.run_summary(cases, '--strict', '--allow-skipped-test', 'fixture.UpdaterTest#downloads')
        self.assertEqual(0, allowed.returncode, allowed.stderr)
        self.assertIn("'skipped': 1", allowed.stdout)

        unexpected = self.run_summary(cases, '--strict', '--allow-skipped-test', 'fixture.UpdaterTest#other')
        self.assertNotEqual(0, unexpected.returncode)
        self.assertIn('Unexpected skipped test: fixture.UpdaterTest#downloads', unexpected.stderr)

    def run_summary(self, cases, *arguments):
        with tempfile.TemporaryDirectory() as root:
            Path(root, 'TEST-example.xml').write_text(f'<testsuite>{cases}</testsuite>', encoding='utf-8')
            environment = os.environ.copy()
            environment.pop('GITHUB_STEP_SUMMARY', None)
            return subprocess.run(
                [sys.executable, '-B', str(Path(__file__).with_name('summary.py')), root, *arguments],
                capture_output=True,
                text=True,
                check=False,
                env=environment,
            )
