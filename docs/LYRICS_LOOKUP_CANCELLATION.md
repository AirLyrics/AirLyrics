# Lyrics Lookup Cancellation

[English](LYRICS_LOOKUP_CANCELLATION.md) · [简体中文](LYRICS_LOOKUP_CANCELLATION.zh-CN.md)

AirLyrics uses a latest-request-wins model for lyrics lookup. This prevents rapid song changes, refresh taps or source switches from delivering stale lyrics to the UI.

## Main class

```text
lyrics/LyricsLookupCancellation.kt
```

`LyricsLookupRunner` owns a single worker executor. Submitting a new lookup cancels the previous active handle before starting the new one.

## Cancellation layers

1. The active `LyricsLookupHandle` is cancelled when a newer request arrives.
2. `Future.cancel(true)` interrupts the worker thread.
3. `LyricsLookupCancellationToken` marks cancellation and is checked between lookup stages.
4. Callback delivery verifies that the token is still active before updating the caller.

## Checked stages

`LyricsRepository` checks cancellation before and after important work:

- Before reading settings.
- Before local provider lookup.
- Before online provider lookup.
- After provider return.
- Before local cache save.
- Before returning the final result.

Native Rust/JNI calls are cooperative at the boundary: a request may only stop once the native call returns or times out, but cancelled results are still blocked from reaching the UI.

## When to cancel

Cancel active lookup on:

- Song change.
- Manual refresh.
- Media source switch.
- Lyrics source switch.
- Local import replacing current lyrics.
- Floating service shutdown.
- Activity/service lifecycle cleanup.

## Rule

Never rely only on comparing old song titles after the result returns. Cancel the work and verify the generation token before callback delivery.
