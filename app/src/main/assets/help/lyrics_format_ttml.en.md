Line-timed lyrics
Put begin and end on <p>:

<p begin="00:12.340" end="00:15.600">This is a line</p>

Word-timed lyrics
Add begin and end to each <span>:

<p begin="00:15.600" end="00:18.000"><span begin="00:15.600" end="00:16.000">Ly</span><span begin="00:16.000" end="00:18.000">rics</span></p>

Plain import accepts either form and converts word timing to line timing. Word-by-word import requires timed spans.

Common inline and Apple Music head translations are supported. Imported TTML is converted to editable lyrics; AirLyrics does not store an editable XML copy.

Remove existing plain lyrics before importing word-by-word lyrics. Plain lyrics will be generated automatically and kept in sync.
