Plain LRC format:
[00:12.34]This is a lyric line
[00:15.60]Original lyric / Translation

Same-timestamp translation lines are also supported when importing plain LRC:
[00:15.60]Original lyric
[00:15.60]Translation

Word-by-word lyrics format:
[00:12.34]<00:12.34>T<00:12.50>ext

Word-by-word lyrics translation lines can use the same line timestamp:
[00:12.34]<00:12.34>T<00:12.50>ext
[00:12.34]Translation

Word-by-word lyrics are used for word highlighting. Remove existing plain lyrics for the song before importing word-by-word lyrics. After import, AirLyrics generates a plain LRC fallback for normal display and keeps it in sync when you edit the word-by-word lyrics.
