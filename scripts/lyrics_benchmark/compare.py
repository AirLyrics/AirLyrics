#!/usr/bin/env python3
"""Compare two metadata-only benchmark runs over the same input file."""
import argparse
import csv
import hashlib
import json
from pathlib import Path

from run import load_cases, load_rows


def compare_runs(before_dir, after_dir, cases, providers):
    before = {(row['provider'], row['case_id']): row for row in load_rows(before_dir, providers)}
    after = {(row['provider'], row['case_id']): row for row in load_rows(after_dir, providers)}
    details = []
    summary = {}
    for provider in providers:
        gained = lost = error_recovered = new_errors = word_gained = word_lost = changed_match = 0
        before_found = after_found = before_words = after_words = 0
        before_tested = after_tested = paired = 0
        for case in cases:
            case_id = case['case_id']
            old = before.get((provider, case_id))
            new = after.get((provider, case_id))
            before_tested += old is not None
            after_tested += new is not None
            before_found += old is not None and old['status'] == 'found'
            after_found += new is not None and new['status'] == 'found'
            before_words += old is not None and old.get('word_found', False)
            after_words += new is not None and new.get('word_found', False)
            if old is not None and new is not None:
                paired += 1
                gained += old['status'] == 'not_found' and new['status'] == 'found'
                lost += old['status'] == 'found' and new['status'] == 'not_found'
                error_recovered += old['status'] == 'error' and new['status'] == 'found'
                new_errors += old['status'] != 'error' and new['status'] == 'error'
                word_gained += not old.get('word_found', False) and new.get('word_found', False)
                word_lost += old.get('word_found', False) and not new.get('word_found', False)
                changed_match += old.get('matched', {}).get('id') != new.get('matched', {}).get('id') and (
                    old['status'] == 'found' and new['status'] == 'found')
            details.append({
                'case_id': case_id, 'query_title': case['query_title'], 'query_artist': case['query_artist'],
                'provider': provider, 'before_status': old['status'] if old else 'untested',
                'after_status': new['status'] if new else 'untested',
                'before_error': old.get('error_type', '') if old else '',
                'after_error': new.get('error_type', '') if new else '',
                'before_word_found': old.get('word_found', False) if old else '',
                'after_word_found': new.get('word_found', False) if new else '',
                'before_matched_id': old.get('matched', {}).get('id', '') if old else '',
                'after_matched_id': new.get('matched', {}).get('id', '') if new else '',
                'before_matched_title': old.get('matched', {}).get('title', '') if old else '',
                'after_matched_title': new.get('matched', {}).get('title', '') if new else '',
                'before_matched_artist': old.get('matched', {}).get('artist', '') if old else '',
                'after_matched_artist': new.get('matched', {}).get('artist', '') if new else '',
            })
        total = len(cases)
        before_complete = before_tested == total
        after_complete = after_tested == total
        summary[provider] = {
            'total': total, 'paired': paired,
            'before_tested': before_tested, 'after_tested': after_tested,
            'before_found': before_found, 'after_found': after_found,
            'before_coverage_percent': round(100 * before_found / total, 2) if before_complete else None,
            'after_coverage_percent': round(100 * after_found / total, 2) if after_complete else None,
            'coverage_delta_points': round(100 * (after_found - before_found) / total, 2)
                if before_complete and after_complete else None,
            'new_hits_from_miss': gained, 'lost_hits_to_miss': lost,
            'hits_recovered_from_error': error_recovered, 'new_errors': new_errors,
            'changed_matched_id': changed_match,
        }
        if provider == 'spicy':
            summary[provider].update(
                before_word_found=before_words, after_word_found=after_words,
                before_word_coverage_percent=round(100 * before_words / total, 2) if before_complete else None,
                after_word_coverage_percent=round(100 * after_words / total, 2) if after_complete else None,
                word_delta_points=round(100 * (after_words - before_words) / total, 2)
                    if before_complete and after_complete else None,
                new_word_hits=word_gained, lost_word_hits=word_lost)
    return summary, details


def write_comparison(output, summary, details):
    output.mkdir(parents=True, exist_ok=False)
    (output/'delta.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
    fields = list(details[0])
    with (output/'changes.csv').open('w', newline='', encoding='utf-8-sig') as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        for row in details:
            writer.writerow({key: "'"+value if isinstance(value, str) and value.startswith(('=', '+', '-', '@')) else value
                             for key, value in row.items()})
    lines = ['# 歌词命中率变化', '', '只比较相同输入文件和共同选择的来源；匹配正确性仍需人工核对。', '']
    for provider, result in summary.items():
        lines += [f'## {provider}', '',
                  f'共同测试 {result["paired"]}/{result["total"]} 首；新增命中 {result["new_hits_from_miss"]}，'
                  f'丢失命中 {result["lost_hits_to_miss"]}，错误恢复为命中 {result["hits_recovered_from_error"]}，'
                  f'新增错误 {result["new_errors"]}。', '']
        if result['coverage_delta_points'] is None:
            lines += ['至少有一轮未完成，不能计算完整列表命中率变化。', '']
        else:
            lines += [f'命中率 {result["before_coverage_percent"]:.2f}% → '
                      f'{result["after_coverage_percent"]:.2f}% '
                      f'（{result["coverage_delta_points"]:+.2f} 个百分点）。', '']
        if provider == 'spicy':
            if result['word_delta_points'] is None:
                lines += [f'共同测试中新增逐字命中 {result["new_word_hits"]}，丢失 {result["lost_word_hits"]}；完整列表逐字率不可计算。', '']
            else:
                lines += [f'逐字命中率 {result["before_word_coverage_percent"]:.2f}% → '
                          f'{result["after_word_coverage_percent"]:.2f}% '
                          f'（{result["word_delta_points"]:+.2f} 个百分点）。', '']
        lines += [f'已命中但匹配 ID 改变 {result["changed_matched_id"]} 首，详见 changes.csv。', '']
    (output/'DELTA.zh-CN.md').write_text('\n'.join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before', type=Path)
    parser.add_argument('after', type=Path)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    cases = load_cases(args.input)
    fixture_hash = hashlib.sha256(args.input.read_bytes()).hexdigest()
    before_manifest = json.loads((args.before/'manifest.json').read_text())
    after_manifest = json.loads((args.after/'manifest.json').read_text())
    if any(manifest['fixture_sha256'] != fixture_hash for manifest in (before_manifest, after_manifest)):
        parser.error('Both runs must use this exact input file')
    providers = tuple(p for p in before_manifest['providers'] if p in after_manifest['providers'])
    if not providers:
        parser.error('Runs have no provider in common')
    summary, details = compare_runs(args.before, args.after, cases, providers)
    write_comparison(args.output, summary, details)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
