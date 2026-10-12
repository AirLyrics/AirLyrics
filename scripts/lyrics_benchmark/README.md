# Lyrics provider benchmark

Test lyric availability with a song list and compare results before and after changes. A hit means usable timed lyrics were returned; verify the recording and content manually.

## Setup

Requires Python 3, JDK 17, Rust and the Android build environment. The script currently uses `/usr/lib/jvm/java-17-openjdk` and defaults to `/mnt/data/dev-cache/gradle` for the Gradle cache (override with `GRADLE_USER_HOME`).

Save songs as a JSON array or `{"cases": [...]}`. Only `title` is required; duration is in seconds:

```json
[
  {"title": "Song title", "artist": "Artist", "album": "Album", "duration_sec": 240}
]
```

## Run

From the project root, use a new output directory for each run:

```bash
python3 scripts/lyrics_benchmark/run.py \
  --input /path/to/songs.json \
  --output /path/to/baseline
```

- Defaults to NetEase, LRCLIB and Spicy. Select sources with `--providers netease lrclib spicy musixmatch`.
- Spicy needs a [configured publishable key](../../docs/ARCHITECTURE.md#spicy-lyrics). Use `--providers netease lrclib` to skip it.
- Add `--proxy http://127.0.0.1:7897` if needed.
- Add `--resume` to the original command to continue an interrupted run. Keep the input, providers, proxy and matching code unchanged.

Read `REPORT.zh-CN.md` for the summary and `comparison.csv` for individual results. Errors are recorded separately; incomplete runs have no full-list coverage percentage.

## Compare

Run the same song list into another directory after changing the matching code, then compare:

```bash
python3 scripts/lyrics_benchmark/compare.py \
  /path/to/baseline /path/to/after \
  --input /path/to/songs.json \
  --output /path/to/delta
```

Read `DELTA.zh-CN.md` and `changes.csv`, and manually review newly matched songs. Report filenames are unchanged.
