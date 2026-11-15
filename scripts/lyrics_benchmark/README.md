# Lyrics provider benchmark

Test lyric availability with a song list and compare results before and after changes. A hit means usable timed lyrics were returned; verify the recording and content manually.

## Setup

Requires Python 3, JDK 17, Rust and the Android build environment. Set `JAVA_HOME` to your JDK 17 installation, or make Java 17 available on `PATH`. The script checks the selected JDK and uses its Java compiler.

Gradle resolves the benchmark dependencies from the project configuration and exports their classpath under `app/build/lyrics-benchmark/`. It honors `GRADLE_USER_HOME`; no cache location or dependency version is hard-coded.

Keep personal song lists, results, logs and run manifests under `scripts/lyrics_benchmark/.local/`, which Git ignores. Manifests retain the explicit proxy setting for resuming a run. Review song metadata, proxy addresses and error details before sharing any output.

Create `scripts/lyrics_benchmark/.local/` and save your songs there as `cases.json`. Use a JSON array or `{"cases": [...]}`. Only `title` is required; duration is in seconds:

```json
[
  {"title": "Song title", "artist": "Artist", "album": "Album", "duration_sec": 240}
]
```

## Run

From the project root, use a new output directory for each run:

```bash
python3 scripts/lyrics_benchmark/run.py \
  --input scripts/lyrics_benchmark/.local/cases.json \
  --output scripts/lyrics_benchmark/.local/results/baseline
```

- Defaults to NetEase, LRCLIB and Spicy. Select sources with `--providers netease lrclib spicy musixmatch`.
- Spicy needs a [configured publishable key](../../docs/ARCHITECTURE.md#spicy-lyrics). Use `--providers netease lrclib` to skip it.
- Add `--proxy <proxy-url>` if needed. Use an HTTP(S) URL without credentials.
- Add `--prepare-only --providers netease lrclib` to check the build and input without querying lyrics APIs.
- Add `--resume` to the original command to continue an interrupted run. Keep the input, providers, proxy and matching code unchanged.

Read `REPORT.zh-CN.md` for the summary and `comparison.csv` for individual results. Errors are recorded separately; incomplete runs have no full-list coverage percentage.

## Compare

Run the same song list into another directory after changing the matching code, then compare:

```bash
python3 scripts/lyrics_benchmark/compare.py \
  scripts/lyrics_benchmark/.local/results/baseline \
  scripts/lyrics_benchmark/.local/results/after \
  --input scripts/lyrics_benchmark/.local/cases.json \
  --output scripts/lyrics_benchmark/.local/results/delta
```

Read `DELTA.zh-CN.md` and `changes.csv`, and manually review newly matched songs. Report filenames are unchanged.
