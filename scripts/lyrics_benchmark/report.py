#!/usr/bin/env python3
"""Render saved metadata as a Chinese report and per-case comparison CSV."""
import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path
from run import PROVIDERS, load_cases, load_rows, summarize


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--input', '--cases', dest='input', type=Path, required=True)
    args = parser.parse_args()
    cases = load_cases(args.input)
    output = args.output
    manifest = json.loads((output/'manifest.json').read_text())
    if hashlib.sha256(args.input.read_bytes()).hexdigest() != manifest['fixture_sha256']:
        parser.error('Input file differs from the benchmark run')
    providers = tuple(manifest['providers'])
    if not providers or any(provider not in PROVIDERS for provider in providers):
        parser.error('Manifest contains an unknown provider')
    rows = load_rows(output, providers)
    for provider in providers:
        ids = [r['case_id'] for r in rows if r['provider'] == provider]
        assert len(ids) == len(set(ids)) == len(cases), f'Incomplete or duplicate results: {provider}'
        assert set(ids) == {c['case_id'] for c in cases}
    summarize(output, cases, providers)
    summary = json.loads((output/'summary.json').read_text())
    labels = dict(netease='网易云', musixmatch='Musixmatch', lrclib='LRCLIB')
    lines = ['# 歌词源测试', '',
        f'输入 {len(cases)} 首歌曲，每个选定来源逐首查询一次。', '',
        '获取率 = 返回可解析、非占位歌词的歌曲数 ÷ 输入歌曲数。错误单列，不能据此判断服务没有歌词。',
        '结果反映当前代码与网络环境；返回的匹配尚未逐首人工确认。', '']
    for group, s in summary.items():
        label = '全部样本' if group == 'all' else group
        lines += [f'## {label}（{s["total"]} 首）', '',
                  '| 来源 | 已测试 | 有歌词 | 未命中 | 错误 | 获取率 |',
                  '| --- | ---: | ---: | ---: | ---: | ---: |']
        for p in providers:
            r = s['providers'][p]
            lines.append(f'| {labels[p]} | {r["tested"]} | {r["found"]} | {r["not_found"]} | {r["error"]} | {r["coverage_percent"]:.2f}% |')
        lines += ['', f'来源合计去重：{s["union_found"]}/{s["total"]}，获取率 {s["union_coverage_percent"]:.2f}%。', '']
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
        '- 各来源依次运行，每首间隔 1 秒；遇到 403、429 或 503 即停止该来源，不生成不完整的覆盖率报告；测试不重试。',
        '- 原始 API 返回和歌词正文均未保存。JSONL 记录状态、耗时及命中元数据，CSV 用于逐首对照。',
        '- manifest.json 记录运行时间、数据集和当前源文件指纹，包含未提交实现。', '']
    (output/'REPORT.zh-CN.md').write_text('\n'.join(lines))
    by_id = {c['case_id']: c for c in cases}
    fields = ['case_id', 'test_group', 'query_title', 'query_artist', 'album', 'duration_sec', 'provider', 'status',
              'error_type', 'http_status', 'error_summary', 'stage', 'line_count', 'placeholder_line_count', 'credit_line_count',
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


if __name__ == '__main__':
    main()
