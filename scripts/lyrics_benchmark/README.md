# 歌词源命中率观测

用固定的歌曲 JSON 文件逐首查询在线歌词源。只要返回可解析、非占位的时间轴歌词，就计为命中；歌曲版本与歌词内容是否正确，查看 `comparison.csv` 中的匹配元数据后人工核对。Spicy 另统计有有效逐字行的歌曲数。

输入可以是歌曲数组，也可以是 `{"cases": [...]}`。每首至少有 `title`，建议同时提供 `artist`、`album` 和以秒为单位的 `duration_sec`；已有数据可用 `query_title`、`query_artist` 覆盖查询字段。`case_id` 和 `test_group` 可选。

```bash
python3 scripts/lyrics_benchmark/run.py \
  --input /path/to/songs.json \
  --output /path/to/baseline \
  --proxy http://127.0.0.1:7897
```

默认查询网易云、LRCLIB、Spicy。Musixmatch 容易限流，需要测试时显式加入，例如 `--providers netease lrclib spicy musixmatch`。Spicy 使用当前 App 构建中配置的 `sl_pk_` publishable key；缺少配置会在发出请求前报错。每次运行使用新的输出目录。

输出包含 `summary.json`、`REPORT.zh-CN.md`、`comparison.csv` 和各来源的 JSONL。命中率分母是输入歌曲总数；来源中途遇到认证、限流、HTTP 503 或连续网络错误时会停止，该来源的完整列表命中率标为“未完成”，其他来源仍会运行。错误不算成未命中。原始歌词、API 响应和 key 都不会写入报告。运行指纹保存在 `manifest.json`，便于确认前后使用了相同输入和代码版本。

网络恢复后可在原目录从未测曲目继续。输入、来源、代理和匹配代码必须与原运行相同；先前已记录的错误仍作为错误保留。

```bash
python3 scripts/lyrics_benchmark/run.py \
  --resume --input /path/to/songs.json \
  --output /path/to/baseline \
  --proxy http://127.0.0.1:7897
```

修改匹配逻辑后，用同一输入文件再运行到另一个目录，并生成变化报告：

```bash
python3 scripts/lyrics_benchmark/compare.py \
  /path/to/baseline /path/to/after \
  --input /path/to/songs.json \
  --output /path/to/delta
```

`DELTA.zh-CN.md` 给出命中率变化，并把“未命中变命中”与“错误恢复后命中”分开；`changes.csv` 列出每首歌曲前后的状态、匹配 ID 和标题，便于人工复核。若任一轮未完成，报告只列共同测到的变化，不计算完整列表的命中率差值。
