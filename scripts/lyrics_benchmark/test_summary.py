"""Offline checks for input validation, coverage, errors, and deduplication."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from run import PROVIDERS, load_cases, summarize


class CoverageTest(unittest.TestCase):
    def test_local_input_is_normalized_without_a_bundled_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'songs.json'
            path.write_text(json.dumps([
                {'title': 'Example', 'artist': 'Artist', 'duration_sec': 181.25},
                {'query_title': 'Second', 'query_artist': '', 'test_group': 'control'},
            ]))
            cases = load_cases(path)
            self.assertEqual([case['case_id'] for case in cases], ['CASE0001', 'CASE0002'])
            self.assertEqual([case['test_group'] for case in cases], ['primary', 'control'])
            self.assertEqual(cases[1]['duration_sec'], 0)
            path.write_text(json.dumps({'case_count': 2, 'cases': [{'case_id': 'x', 'title': 'One'},
                                                                   {'case_id': 'x', 'title': 'Two'}]}))
            with self.assertRaisesRegex(ValueError, 'duplicate case_id'):
                load_cases(path)

    def test_report_uses_its_input_and_selected_providers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / 'input.json'
            fixture.write_text(json.dumps([{'title': 'Example', 'test_group': 'sample'}]))
            output = root / 'output'
            output.mkdir()
            (output/'manifest.json').write_text(json.dumps({
                'fixture_sha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
                'providers': ['lrclib'],
            }))
            (output/'lrclib.jsonl').write_text(json.dumps({
                'case_id': 'CASE0001', 'provider': 'lrclib', 'status': 'not_found',
                'error_type': 'NotFound',
            })+'\n')
            subprocess.run([sys.executable, str(Path(__file__).with_name('report.py')),
                            str(output), '--input', str(fixture)], check=True, capture_output=True)
            report = (output/'REPORT.zh-CN.md').read_text()
            self.assertIn('输入 1 首歌曲', report)
            self.assertIn('LRCLIB', report)
            self.assertNotIn('网易云', report)

    def test_errors_stay_separate_and_union_deduplicates(self):
        cases = [{'case_id': 'a', 'test_group': 'primary'},
                 {'case_id': 'b', 'test_group': 'primary'},
                 {'case_id': 'c', 'test_group': 'edge'}]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            for provider in PROVIDERS:
                rows = [dict(case_id='a', provider=provider, status='found'),
                        dict(case_id='b', provider=provider, status='error', error_type='NetworkError'),
                        dict(case_id='c', provider=provider, status='not_found')]
                (output/f'{provider}.jsonl').write_text('\n'.join(json.dumps(r) for r in rows))
            with contextlib.redirect_stdout(io.StringIO()):
                summarize(output, cases)
            result = json.loads((output/'summary.json').read_text())
        netease = result['all']['providers']['netease']
        self.assertEqual((netease['found'], netease['not_found'], netease['error']), (1, 1, 1))
        self.assertEqual(netease['coverage_percent'], 33.33)
        self.assertEqual(netease['errors'], {'NetworkError': 1})
        self.assertEqual(result['all']['union_found'], 1)
        self.assertEqual(result['primary']['providers']['netease']['coverage_percent'], 50)
        self.assertEqual(result['edge']['union_found'], 0)


if __name__ == '__main__':
    unittest.main()
