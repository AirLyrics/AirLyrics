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

from run import PROVIDERS, load_cases, resume_positions, summarize
from compare import compare_runs
from report import render_report


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

    def test_partial_run_reports_progress_without_claiming_full_coverage(self):
        cases = [dict(case_id=f'c{i}', query_title=f'Song {i}', query_artist='Artist',
                      album='', duration_sec=180, test_group='primary') for i in range(3)]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output/'spicy.jsonl').write_text('\n'.join(json.dumps(row) for row in [
                dict(case_id='c0', provider='spicy', status='found', word_found=True, word_line_count=12),
                dict(case_id='c1', provider='spicy', status='error', error_type='RateLimited'),
            ])+'\n')
            with contextlib.redirect_stdout(io.StringIO()):
                render_report(output, cases, {'providers': ['spicy']})
            stats = json.loads((output/'summary.json').read_text())['all']['providers']['spicy']
            report = (output/'REPORT.zh-CN.md').read_text()
            self.assertEqual((stats['tested'], stats['found'], stats['word_found']), (2, 1, 1))
            self.assertIsNone(stats['coverage_percent'])
            self.assertIsNone(stats['word_coverage_percent'])
            self.assertIn('未完成', report)
            self.assertIn('word_found', (output/'comparison.csv').read_text(encoding='utf-8-sig'))

    def test_before_after_comparison_separates_new_hits_from_error_recovery(self):
        cases = [dict(case_id=f'c{i}', query_title=f'Song {i}', query_artist='Artist') for i in range(3)]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before, after = root/'before', root/'after'
            before.mkdir(); after.mkdir()
            old = [('not_found', False), ('error', False), ('found', False)]
            new = [('found', True), ('found', True), ('found', True)]
            for output, values in ((before, old), (after, new)):
                (output/'spicy.jsonl').write_text('\n'.join(json.dumps({
                    'case_id': f'c{i}', 'provider': 'spicy', 'status': status, 'word_found': word,
                    'matched': {'id': str(i)} if status == 'found' else {},
                }) for i, (status, word) in enumerate(values))+'\n')
            summary, details = compare_runs(before, after, cases, ('spicy',))
            stats = summary['spicy']
            self.assertEqual((stats['new_hits_from_miss'], stats['hits_recovered_from_error']), (1, 1))
            self.assertEqual((stats['coverage_delta_points'], stats['word_delta_points']), (66.67, 100.0))
            self.assertEqual(len(details), 3)

    def test_resume_requires_each_provider_results_to_follow_input_order(self):
        cases = [dict(case_id='a'), dict(case_id='b')]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output/'spicy.jsonl').write_text(json.dumps(dict(case_id='a', provider='spicy', status='found'))+'\n')
            self.assertEqual(resume_positions(output, cases, ('netease', 'spicy')),
                             {'netease': 0, 'spicy': 1})
            (output/'spicy.jsonl').write_text(json.dumps(dict(case_id='b', provider='spicy', status='found'))+'\n')
            with self.assertRaisesRegex(ValueError, 'not a prefix'):
                resume_positions(output, cases, ('spicy',))


if __name__ == '__main__':
    unittest.main()
