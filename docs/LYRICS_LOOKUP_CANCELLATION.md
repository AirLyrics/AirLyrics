# Lyrics lookup cancellation

AirLyrics no longer starts raw, fire-and-forget `Thread` instances for lyrics lookup.

The floating service now owns a `LyricsLookupRunner`. Each song change, manual refresh, online reload, media-source switch, import, or service shutdown cancels the previous lookup before starting or applying the next state.

Cancellation has two layers:

1. `Future.cancel(true)` interrupts the worker thread.
2. `LyricsLookupCancellationToken` is checked before local lookup, before online lookup, after provider return, and before local cache save.

JNI/Rust provider calls can still be blocking while native code is executing, so cancellation is cooperative around the native boundary rather than magic mid-call termination. The important behavior is guaranteed: cancelled lookup results are not delivered to the UI and cancelled lookups do not save stale lyrics after a newer request has replaced them.
