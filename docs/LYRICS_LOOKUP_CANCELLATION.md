# Lyrics lookup cancellation

AirLyrics uses a latest-request-wins lookup model for song changes, manual refreshes, online reloads, media-source switches, imports, and service shutdown.

`LyricsLookupRunner` now owns a single-worker executor instead of an unbounded cached thread pool. This keeps lookup concurrency bounded to one worker, prevents rapid song/source changes from creating a pile of background threads, and makes queued stale requests cancellable before they start.

Cancellation has four layers:

1. Replacing a lookup cancels the previous `LyricsLookupHandle`.
2. `Future.cancel(true)` interrupts the worker thread.
3. `LyricsLookupCancellationToken` carries a request key plus generation number and is checked before local lookup, before online lookup, after provider return, and before local cache save.
4. Rust/JNI provider calls are wrapped in native-side timeouts, currently 12 seconds for NetEase and 15 seconds for Musixmatch, so native network work cannot block indefinitely under normal provider behavior.

The Rust/JNI boundary is still cooperative: Java cannot forcefully kill native code in the middle of a blocking operation. The important guarantees are:

- cancelled lookup results are never delivered to the UI;
- cancelled lookups do not save stale lyrics after a newer request has replaced them;
- rapid refresh/source/song changes do not create unbounded lookup threads;
- queued stale requests are cancelled before they run;
- native provider calls have bounded lookup windows.

`LyricsLookupRunnerTest` covers the two core behaviors: a running lookup is replaced without delivering its result, and a queued lookup is cancelled before it starts when a newer request arrives.
