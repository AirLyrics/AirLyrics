#!/usr/bin/env python3
"""Render saved metadata as a Chinese report and per-case comparison CSV."""
import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path
from run import PROVIDERS, load_cases, load_rows, summarize


def render_report(output, cases, manifest):
    providers = tuple(manifest['providers'])
    if not providers or any(provider not in PROVIDERS for provider in providers):
        raise ValueError('Manifest contains an unknown provider')
    rows = load_rows(output, providers)
    case_ids = {case['case_id'] for case in cases}
    for provider in providers:
        ids = [r['case_id'] for r in rows if r['provider'] == provider]
        if len(ids) != len(set(ids)) or not set(ids) <= case_ids:
            raise ValueError(f'Duplicate or unknown case IDs for {provider}')
    summarize(output, cases, providers)
    summary = json.loads((output/'summary.json').read_text())
    labels = dict(netease='网易云', musixmatch='Musixmatch', lrclib='LRCLIB', spicy='Spicy Lyrics')
    lines = ['# 歌词源测试', '',
        f'输入 {len(cases)} 首歌曲，每个选定来源逐首查询一次。', '',
        '命中率 = 返回可解析、非占位歌词的歌曲数 ÷ 输入歌曲数；逐字命中率只统计 Spicy 的有效逐字行。',
        '未完成来源不计算完整列表命中率。错误单列，不能据此判断服务没有歌词。',
        '结果反映当前代码与网络环境；匹配是否正确由人工复核。', '']
    for group, s in summary.items():
        label = '全部样本' if group == 'all' else group
        lines += [f'## {label}（{s["total"]} 首）', '',
                  '| 来源 | 已测试 | 有歌词 | 逐字歌词 | 未命中 | 错误 | 命中率 | 逐字命中率 |',
                  '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
        for p in providers:
            r = s['providers'][p]
            coverage = f'{r["coverage_percent"]:.2f}%' if r['complete'] else '未完成'
            word_coverage = f'{r["word_coverage_percent"]:.2f}%' if p == 'spicy' and r['complete'] else '—'
            word_found = str(r['word_found']) if p == 'spicy' else '—'
            lines.append(f'| {labels[p]} | {r["tested"]}/{r["total"]} | {r["found"]} | {word_found} | '
                         f'{r["not_found"]} | {r["error"]} | {coverage} | {word_coverage} |')
        if s['complete']:
            lines += ['', f'来源合计去重：{s["union_found"]}/{s["total"]}，命中率 {s["union_coverage_percent"]:.2f}%。', '']
        else:
            lines += ['', f'已观察到去重命中 {s["union_found"]} 首；有来源未完成，不计算合计命中率。', '']
    lines += ['## 失败分布', '']
    for p in providers:
        subset = [r for r in rows if r['provider'] == p]
        errors = Counter(r.get('error_type', 'Unknown') for r in subset if r['status'] == 'error')
        misses = Counter(r.get('stage', 'native_not_found') for r in subset if r['status'] == 'not_found')
        lines.append(f'- {labels[p]}：错误 {dict(errors)}；未命中阶段 {dict(misses)}。')
    lines += ['', '## 测试边界', '',
        '- 直接调用当前 Rust JNI 查找与 Kotlin 解析器；不读取本地歌词，不依赖来源排序，也不测试 UI。',
        '- 查询保留数据集指定的歌名、歌手、专辑、时长；不删去翻唱、串烧、纯音乐或多歌手样本。',
        '- 已排除已知的“纯音乐，请欣赏”等占位及 App 署名识别规则确认的署名行。错误版本及同名曲仍需人工复核；覆盖率不等同于匹配准确率。',
        '- 各来源依次运行，每首间隔 1 秒；遇到认证/限流拒绝、HTTP 503 或连续网络错误即停止该来源，其他来源继续。测试器不额外重试。',
        '- 原始 API 返回和歌词正文均未保存。JSONL 记录状态、耗时及命中元数据，CSV 用于逐首人工核对。',
        '- manifest.json 记录运行时间、数据集和当前源文件指纹，包含未提交实现。', '']
    (output/'REPORT.zh-CN.md').write_text('\n'.join(lines))
    by_id = {c['case_id']: c for c in cases}
    fields = ['case_id', 'test_group', 'query_title', 'query_artist', 'album', 'duration_sec', 'provider', 'status',
              'error_type', 'http_status', 'error_summary', 'stage', 'line_count', 'placeholder_line_count', 'credit_line_count',
              'word_found', 'word_line_count', 'lyric_source', 'candidate_count', 'attempted_count',
              'matched_id', 'matched_title', 'matched_artist', 'matched_album', 'matched_duration_ms', 'elapsed_ms']
    with (output/'comparison.csv').open('w', newline='', encoding='utf-8-sig') as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for r in sorted(rows, key=lambda r: (r['case_id'], r['provider'])):
            merged = dict(by_id[r['case_id']], **r)
            merged.update({'matched_'+k: v for k, v in r.get('matched', {}).items()})
            # Prevent library metadata from being interpreted as spreadsheet formulas.
            writer.writerow({k: ("'"+v if isinstance(v, str) and v.startswith(('=', '+', '-', '@')) else v)
                             for k in fields if (v := merged.get(k)) is not None})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--input', '--cases', dest='input', type=Path, required=True)
    args = parser.parse_args()
    cases = load_cases(args.input)
    manifest = json.loads((args.output/'manifest.json').read_text())
    if hashlib.sha256(args.input.read_bytes()).hexdigest() != manifest['fixture_sha256']:
        parser.error('Input file differs from the benchmark run')
    render_report(args.output, cases, manifest)


if __name__ == '__main__':
    main()
