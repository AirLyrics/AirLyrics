package com.andsi.airlyrics.lyrics.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlParserTest {
    @Test
    fun parse_lineTiming_buildsPlainLyricsAndInlineTranslation() {
        val document = parseSuccess(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions"
                itunes:timing="Line">
                <body>
                    <div>
                        <p begin="00:01.200" end="00:03.500">
                            A <span>styled</span><br/>line
                            <span ttm:role="x-translation">一行翻译</span>
                        </p>
                    </div>
                </body>
            </tt>
            """.trimIndent()
        )

        assertEquals(TtmlTimingMode.LINE, document.timingMode)
        assertEquals(1, document.lines.size)
        assertEquals("A styled line", document.lines.single().text)
        assertEquals(emptyList<ParsedTtmlSegment>(), document.lines.single().segments)
        assertEquals(listOf("一行翻译"), document.lines.single().translations)
        assertEquals("[00:01.20]A styled line / 一行翻译", document.toPlainLrc())
        assertTrue(document.toParsedWordByWordLyrics().wordByWordLines.isEmpty())
    }

    @Test
    fun parse_preservesWordSeparationAcrossFormattedLineTextElements() {
        val document = parseSuccess(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <p begin="1s" end="2s">
                        Hello
                        <span>formatted</span>
                        world
                    </p>
                </body>
            </tt>
            """.trimIndent()
        )

        assertEquals("Hello formatted world", document.lines.single().text)
    }

    @Test
    fun parse_wordTiming_preservesMeaningfulInterSpanWhitespaceAndExactTimes() {
        val document = parseSuccess(
            "\uFEFF" +
                """
                <tt xmlns="http://www.w3.org/ns/ttml"
                    xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                    xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions"
                    itunes:timing="Word">
                    <body dur="00:20.000">
                        <p begin="10.0s" end="12.0s" itunes:key="L1">
                            <span begin="10.0s" dur="0.3s">he</span><span begin="10.3" end="10.6">llo</span> <span begin="00:10.600" end="00:12.000">world</span>
                            <span ttm:role="x-translation">你好，世界</span>
                        </p>
                    </body>
                </tt>
                """.trimIndent()
        )

        val line = document.lines.single()
        assertEquals(TtmlTimingMode.WORD, document.timingMode)
        assertEquals(10_000L, line.startMs)
        assertEquals(12_000L, line.endMs)
        assertEquals("hello world", line.text)
        assertEquals(listOf("he", "llo ", "world"), line.segments.map { it.text })
        assertEquals(listOf(10_000L, 10_300L, 10_600L), line.segments.map { it.startMs })
        assertEquals(listOf(10_300L, 10_600L, 12_000L), line.segments.map { it.endMs })

        val converted = document.toParsedWordByWordLyrics()
        assertEquals("hello world", converted.wordByWordLines.single().text)
        assertEquals(listOf("he", "llo ", "world"), converted.wordByWordLines.single().segments.map { it.text })
        assertEquals("[00:10.00]hello world / 你好，世界", converted.plainLrc)
        assertTrue(converted.hasTranslation)
    }

    @Test
    fun parse_supportsClockSecondsAndDurationTimeExpressions() {
        val document = parseSuccess(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <p begin="01:02:03.004" dur="1.5s">hours</p>
                    <p begin="02:03.45" end="02:04.5">minutes</p>
                    <p begin="95.5" end="96">seconds</p>
                    <p begin="100s" dur="0.1255s">suffix</p>
                </body>
            </tt>
            """.trimIndent()
        )

        assertEquals(
            listOf(3_723_004L, 123_450L, 95_500L, 100_000L),
            document.lines.map { it.startMs }
        )
        assertEquals(
            listOf(3_724_504L, 124_500L, 96_000L, 100_126L),
            document.lines.map { it.endMs }
        )
    }

    @Test
    fun parse_mergesAppleHeadTranslationsByItunesKey() {
        val document = parseSuccess(
            """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="urn:example:itunes-extension">
                <head>
                    <metadata>
                        <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                            <translations>
                                <translation type="subtitle" xml:lang="zh-Hans-CN">
                                    <text for="L1">黄金首饰 闪亮耀眼</text>
                                    <text for="L2"><span begin="12s" end="12.5s">第二</span> <span begin="12.5s" end="13s">行</span></text>
                                </translation>
                                <translation type="subtitle" xml:lang="en">
                                    <text for="L1">Gold jewelry shining bright</text>
                                </translation>
                            </translations>
                        </iTunesMetadata>
                    </metadata>
                </head>
                <body>
                    <p begin="10s" end="12s" itunes:key="L1">
                        第一行
                        <span ttm:role="x-translation">黄金首饰 闪亮耀眼</span>
                    </p>
                    <p begin="12s" end="14s" itunes:key="L2">Second line</p>
                </body>
            </tt>
            """.trimIndent()
        )

        assertEquals(
            listOf("黄金首饰 闪亮耀眼", "Gold jewelry shining bright"),
            document.lines[0].translations
        )
        assertEquals(listOf("第二 行"), document.lines[1].translations)
        assertEquals(
            "[00:10.00]第一行 / 黄金首饰 闪亮耀眼 / Gold jewelry shining bright\n" +
                "[00:12.00]Second line / 第二 行",
            document.toPlainLrc()
        )
    }

    @Test
    fun parse_excludesBackgroundRomanizationAndRubyReadingFromMainLyrics() {
        val document = parseSuccess(
            """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:tts="http://www.w3.org/ns/ttml#styling">
                <body>
                    <p begin="1s" end="4s">
                        <span begin="1s" end="2s" tts:ruby="container"><span tts:ruby="base">漢</span><span tts:ruby="text">かん</span></span>
                        <span begin="2s" end="3s">字</span>
                        <span ttm:role="x-roman">kanji</span>
                        <span ttm:role="x-bg" begin="3s" end="4s"><span begin="3s" end="4s">background</span></span>
                    </p>
                </body>
            </tt>
            """.trimIndent()
        )

        val line = document.lines.single()
        assertEquals("漢字", line.text)
        assertEquals(listOf("漢", "字"), line.segments.map { it.text })
    }

    @Test
    fun parse_lineModeIgnoresInnerSpanTiming() {
        val document = parseSuccess(
            """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions"
                itunes:timing="Line">
                <body>
                    <p begin="1s" end="3s"><span begin="bad" end="also-bad">line text</span></p>
                </body>
            </tt>
            """.trimIndent()
        )

        assertEquals("line text", document.lines.single().text)
        assertTrue(document.lines.single().segments.isEmpty())
    }

    @Test
    fun converters_rejectDocumentsWithoutVisibleMainLyrics() {
        val document = parseSuccess(
            """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                <body>
                    <p begin="1s" end="2s"><span ttm:role="x-bg" begin="1s" end="2s">background only</span></p>
                </body>
            </tt>
            """.trimIndent()
        )

        assertEquals("", document.toPlainLrc())
        assertTrue(document.toParsedWordByWordLyrics().wordByWordLines.isEmpty())
    }

    @Test
    fun converters_rejectMixedLineAndWordTimingForWordByWordLyrics() {
        val document = parseSuccess(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <p begin="1s" end="2s"><span begin="1s" end="2s">word timed</span></p>
                    <p begin="2s" end="3s">line timed</p>
                </body>
            </tt>
            """.trimIndent()
        )

        assertEquals(
            "[00:01.00]word timed\n[00:02.00]line timed",
            document.toPlainLrc()
        )
        assertTrue(document.toParsedWordByWordLyrics().wordByWordLines.isEmpty())
    }

    @Test
    fun parse_ignoresBackgroundParagraphsWithoutAddingEmptyPlainLines() {
        val document = parseSuccess(
            """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                <body>
                    <p begin="1s" end="2s"><span begin="1s" end="2s">main</span></p>
                    <p begin="2s" end="3s" ttm:role="x-bg">background paragraph</p>
                    <p begin="3s" end="4s"><span ttm:role="x-bg" begin="3s" end="4s">background span</span></p>
                </body>
            </tt>
            """.trimIndent()
        )

        assertEquals("[00:01.00]main", document.toPlainLrc())
        assertEquals(1, document.toParsedWordByWordLyrics().wordByWordLines.size)
    }

    @Test
    fun parse_reportsInvalidTimingWithSourceLine() {
        val result = TtmlParser.parse(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <p begin="00:75.000" end="00:76.000">bad</p>
                </body>
            </tt>
            """.trimIndent()
        )

        val failure = result as TtmlParseResult.Failure
        assertEquals(TtmlParseFailureReason.INVALID_TIMING, failure.reason)
        assertEquals(3, failure.lineNumber)
        assertEquals(listOf(3), failure.invalidLineNumbers)
    }

    @Test
    fun parse_reportsMalformedXmlWithSourceLine() {
        val result = TtmlParser.parse(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <p begin="1s" end="2s">broken</div>
                </body>
            </tt>
            """.trimIndent()
        )

        val failure = result as TtmlParseResult.Failure
        assertEquals(TtmlParseFailureReason.MALFORMED_XML, failure.reason)
        assertEquals(3, failure.lineNumber)
    }

    @Test
    fun parse_rejectsDoctypeAndExternalEntitiesBeforeXmlParsing() {
        val result = TtmlParser.parse(
            """
            <?xml version="1.0"?>
            <!DOCTYPE tt [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body><p begin="1s" end="2s">&secret;</p></body>
            </tt>
            """.trimIndent()
        )

        val failure = result as TtmlParseResult.Failure
        assertEquals(TtmlParseFailureReason.UNSAFE_XML, failure.reason)
        assertEquals(2, failure.lineNumber)
    }

    @Test
    fun parse_rejectsLineAndSpanTimesOutsideTheirAllowedRange() {
        val bodyFailure = TtmlParser.parse(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body dur="2s">
                    <p begin="1s" end="3s">too late</p>
                </body>
            </tt>
            """.trimIndent()
        ) as TtmlParseResult.Failure
        val spanFailure = TtmlParser.parse(
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <p begin="1s" end="3s"><span begin="0.5s" end="2s">too early</span></p>
                </body>
            </tt>
            """.trimIndent()
        ) as TtmlParseResult.Failure

        assertEquals(TtmlParseFailureReason.INVALID_TIMING, bodyFailure.reason)
        assertEquals(TtmlParseFailureReason.INVALID_TIMING, spanFailure.reason)
        assertEquals(3, bodyFailure.lineNumber)
        assertEquals(3, spanFailure.lineNumber)
    }

    private fun parseSuccess(ttml: String): ParsedTtmlDocument {
        return when (val result = TtmlParser.parse(ttml)) {
            is TtmlParseResult.Success -> result.document
            is TtmlParseResult.Failure -> error(
                "Unexpected TTML parse failure at ${result.lineNumber}:${result.columnNumber}: ${result.message}"
            )
        }
    }
}
