import tempfile
from pathlib import Path
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
