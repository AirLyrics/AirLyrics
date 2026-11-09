# Manual lyric catalogue and version selection

This feature is manual-only. It supports NetEase, Kugou and QQ Music and uses the
existing Rust/JNI cancellation boundary, HTTP clients, lyric models, transactional
selection store and song-target checks. QQ automatic matching is unchanged.

## Query and target

The search form and the song receiving the selection are separate identities.
The word search dialog starts with the current search title and artist, or the
previous word query. Album and duration start empty; users may supply them under
Album and duration (optional). Duration uses min:sec, with a short explanation
expanded by its inline info icon. There is no separate restore-current-song action.
All draft fields, accordion state and inline help survive recreation.

Editing the query never changes the apply target. Switching the target cancels
catalogue, detail and download tasks. Dialog results for a different
target are ignored. Only the explicit apply action writes storage and publishes
the lyric-change event.

## Read operations

- `word_catalog`: title/artist catalogue search, 20 requested recordings per page.
  The response contains stable provider IDs/hashes, metadata, relevance group,
  confirmation hints, the last successful keyword, page and has_more.
  No lyric downloads occur. Metadata sorts recordings; unrelated titles remain
  available behind Show less related results. No top-three recording limit.
  When the first query yields no related title, up to two alternate queries from
  the shared query planner may run. Subsequent pages use the returned keyword.
- `word_options`: exact selected recording. NetEase requests lyrics using its song
  ID and returns independently selectable line and verified YRC versions from the
  same response. Kugou queries lyrics using the selected hash and lists every
  returned valid candidate, preserving lyric ID, access key, creator, rating,
  explicit title/artist/duration and confirmation hints. It downloads none.
  QQ requests lyrics for the selected numeric song ID and returns a verified QRC
  version, or a line version if the response only contains line timing.
- `word_download`: one selected Kugou lyric ID/access key. KRC is decoded and
  verified before being labelled word-synced. A timed line-only KRC is a separate
  line choice; plain text is reported as lacking a synced version.

NetEase catalogue uses the existing ncmapi client with explicit limit/offset.
The manual EAPI lyric request asks for lyric versions with -1 parameters. It
preserves LRC when YRC or its translation cannot be parsed. Original LRC uses
tlyric; YRC uses ytlrc from the same response. They are never mixed across sources.
Kugou catalogue uses the signed v2 search endpoint with an HTTPS legacy fallback;
both request and pagination calculations use 20. Rate limits do not trigger a
second catalogue endpoint.

QQ uses musicu.fcg with anonymous mobile parameters. Session bootstrap is optional,
limited to three seconds, and successful identities are cached for one hour.
The Mobile search method has one Lite compatibility fallback, with explicit page
numbers and 20 songs per page. Rate limits return a source error immediately;
there is no minute-long sleep or background retry loop. Numeric song IDs are kept
unchanged through selection, request and response validation. Subtitle/version
information is preserved in the displayed title. GetPlayLyricInfo requests QRC and
translation from that same recording; search does not fetch any lyrics.

QRC cloud payloads use modified DES tables and zlib. Input and decompressed size
are capped at 2 MiB. XML extraction preserves LyricContent newlines and unescapes
entities without allowing DTDs. QRC word tags follow their text and contain
absolute start/duration values. Validated segments retain punctuation and line
end times; provider type flags alone never grant a word label. Plain LRC remains
a line option, empty/plain text is absence of synced lyrics, and malformed
ciphertext/timing is a failure. Invalid translation leaves valid original lyrics
available with a partial error.

Protocol references: [LDDC v0.9.2 qm.py](https://github.com/chenmozhijin/LDDC/blob/v0.9.2/LDDC/core/api/lyrics/qm.py)
and [Lyrics-Helper 39e5883](https://github.com/ChouChiu/Lyrics-Helper/tree/39e58839bd52a881b6011be8f8d19c34380adf8e).
Only the Apache-2.0 Lyrics-Helper crypto implementation is adapted; its license,
original attribution and modification notice ship in
`app/src/main/assets/third_party/lyrics-helper/`.

## UI and failure isolation

The result page lists recordings before lyric IO. Selecting one lists its lyric
versions/candidates, and selecting a version previews it before apply.
All three sources are selected from the catalogue; a recording detail only shows that recording's lyric choices.
Uncertain recordings show an info icon that expands a brief hint in the same row.
Catalogue pagination is one More songs action at the end of the scrolling list.
Source failures and other results also live in the catalogue footer;
the top toolbar only edits the query or returns to the previous search step.

Each source has independent loading, empty, error and pagination state. Successful
rows survive another source/page failure. Each Kugou lyric download has its own
state and retry; one failing lyric cannot hide the other candidates. NetEase
line results survive malformed YRC. A single malformed version does not discard
other valid versions in the JNI response. QQ source failures also leave NetEase
and Kugou results available, and malformed QQ translation preserves its original.

Requests use 10-second direct HTTP timeouts, a 25-second JNI operation budget,
bounded responses/decompression, and cancellation tokens that reject stale
callbacks. Catalogue sources may run in parallel; only one selected lyric
download runs at a time. Detail cache holds at most 16 recordings per session;
a new query/target clears it. Cancelled loading states reset before reuse.

Stable keys include provider, recording/lyric identity and timing kind. Different
providers or lyric IDs remain independent, even if displayed titles are equal.
Persistence keeps the actual provider and the existing word timeline format.

## Verification

Offline Rust fixtures cover YRC absolute/KRC relative word times, malformed timing,
zero-duration punctuation, line rounding, KRC decompression and translation, and
independent line/YRC results with partial failure. QQ fixtures also cover numeric
song-ID parameters, pagination/subtitles, fixed cloud ciphertext, XML entities and
newlines, postfix QRC timing, line-only/plain/empty responses, translation failure,
zero-duration credit lines, and bounded decompression.
Kotlin tests cover lazy catalogue IO, pagination, low relevance expansion,
source/page failure, exact recording requests, selected
Kugou candidates beyond the old cap, cancellation, cache reuse, explicit query
conditions, recreation, three-source error isolation, QQ source labels, target
changes after QQ preview, and persisted provider/word timeline recovery for all
three providers.

Provider availability and real-song matching are left for user device testing;
offline fixtures and successful builds do not establish a live hit-rate change.
