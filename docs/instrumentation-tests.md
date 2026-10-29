# Android instrumentation suites

PR and push checks build the APK, run lint, JVM tests, and static checks without
starting an emulator. The instrumentation workflow runs `--full` weekly and by
default on manual dispatch. Manual dispatch also offers
`fast`, `stress`, and `hardware` so the two extended groups can be diagnosed on their
own. Every selected suite is blocking for its run, and the report check requires a
minimum number of executed cases with no failures, errors, or skipped cases.

| Suite | Selection | Current cases |
| --- | --- | ---: |
| `--fast` | Core UI navigation, search, storage, JNI, and layout tests | 25 |
| `--stress` | Repeated real rotation; resolution, density, font, RTL, and IME changes | 2 |
| `--hardware` | SpicyLyrics committed hardware frame comparisons | 1 |
| `--full` | All offline tests above | 28 |

On a connected emulator or disposable test device, run:

```bash
bash scripts/run_instrumentation_ci.sh --fast
bash scripts/run_instrumentation_ci.sh --full
```

Use `--stress` or `--hardware` for one extended group. The separate `--live` suite
requires network access and is not part of the offline CI runs. Run with JDK 17; the
script builds the debug and test APKs in one Gradle invocation and builds the Rust JNI
library if it is missing. It writes device state and
logcat to `app/build/test-diagnostics/`. Per-test failure state and screenshots are
included in the connected Android test additional output. Running connected tests may
replace an installed AirLyrics app and its data on the selected device.
