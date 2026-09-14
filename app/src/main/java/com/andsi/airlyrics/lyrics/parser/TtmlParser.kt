package com.andsi.airlyrics.lyrics.parser

import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

internal enum class TtmlParseFailureReason {
    MALFORMED_XML,
    INVALID_STRUCTURE,
    INVALID_TIMING,
    UNSAFE_XML
}

internal sealed interface TtmlParseResult {
    data class Success(val document: ParsedTtmlDocument) : TtmlParseResult

    data class Failure(
        val reason: TtmlParseFailureReason,
        val message: String,
        val lineNumber: Int? = null,
        val columnNumber: Int? = null
    ) : TtmlParseResult {
        val invalidLineNumbers: List<Int>
            get() = listOfNotNull(lineNumber)
    }
}

internal object TtmlParser {
    fun parse(ttml: String): TtmlParseResult {
        val source = ttml.removePrefix("\uFEFF")
        unsafeXmlDeclarationRegex.find(source)?.let { unsafeDeclaration ->
            val lineNumber = source
                .take(unsafeDeclaration.range.first)
                .count { it == '\n' } + 1
            return TtmlParseResult.Failure(
                reason = TtmlParseFailureReason.UNSAFE_XML,
                message = "DTD and entity declarations are not supported.",
                lineNumber = lineNumber
            )
        }

        return runCatching {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                runCatching { isXIncludeAware = false }
                setFeatureIfSupported(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeatureIfSupported(DISALLOW_DOCTYPE_FEATURE, true)
                setFeatureIfSupported(EXTERNAL_GENERAL_ENTITIES_FEATURE, false)
                setFeatureIfSupported(EXTERNAL_PARAMETER_ENTITIES_FEATURE, false)
                setFeatureIfSupported(LOAD_EXTERNAL_DTD_FEATURE, false)
            }
            val handler = TtmlContentHandler()
            val xmlReader = factory.newSAXParser().xmlReader.apply {
                disableFeatureIfSupported(EXTERNAL_GENERAL_ENTITIES_FEATURE)
                disableFeatureIfSupported(EXTERNAL_PARAMETER_ENTITIES_FEATURE)
                disableFeatureIfSupported(LOAD_EXTERNAL_DTD_FEATURE)
                entityResolver = handler
                contentHandler = handler
                errorHandler = handler
            }
            xmlReader.parse(InputSource(StringReader(source)))
            TtmlParseResult.Success(handler.buildDocument())
        }.getOrElse { error ->
            val abort = error.findTtmlAbort()
            if (abort != null) {
                abort.failure
            } else {
                val parseError = error.findSaxParseException()
                TtmlParseResult.Failure(
                    reason = TtmlParseFailureReason.MALFORMED_XML,
                    message = parseError?.message
                        ?.takeIf { it.isNotBlank() }
                        ?: "Malformed TTML document.",
                    lineNumber = parseError?.lineNumber?.takeIf { it > 0 },
                    columnNumber = parseError?.columnNumber?.takeIf { it > 0 }
                )
            }
        }
    }
}

private class TtmlContentHandler : DefaultHandler() {
    private val frames = ArrayDeque<ElementFrame>()
    private val parsedLines = mutableListOf<ParsedTtmlLine>()
    private val headTranslationsByKey = linkedMapOf<String, MutableList<String>>()

    private var locator: Locator? = null
    private var sawRoot = false
    private var sawBody = false
    private var inHead = false
    private var inBody = false
    private var bodyDurationMs: Long? = null
    private var declaredTimingMode = TtmlTimingMode.UNSPECIFIED
    private var currentLine: MutableTtmlLine? = null
    private var currentHeadText: MutableHeadTranslation? = null
    private var currentInlineTranslation: StringBuilder? = null

    private var translationsContainerDepth = 0
    private var translationTrackDepth = 0
    private var inlineTranslationDepth = 0
    private var backgroundDepth = 0
    private var romanizationDepth = 0
    private var rubyTextDepth = 0

    override fun setDocumentLocator(locator: Locator?) {
        this.locator = locator
    }

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
        fail(
            reason = TtmlParseFailureReason.UNSAFE_XML,
            message = "External XML entities are not supported."
        )
    }

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes
    ) {
        val name = normalizedElementName(localName, qName)
        if (frames.size >= MAX_ELEMENT_DEPTH) {
            fail(TtmlParseFailureReason.INVALID_STRUCTURE, "TTML nesting is too deep.")
        }
        if (!sawRoot) {
            if (name != "tt") {
                fail(TtmlParseFailureReason.INVALID_STRUCTURE, "The TTML root element must be <tt>.")
            }
            sawRoot = true
            declaredTimingMode = parseTimingMode(attributes.attributeValue("timing"))
        }

        val frame = ElementFrame(name = name)
        when (name) {
            "head" -> {
                if (inBody) {
                    fail(TtmlParseFailureReason.INVALID_STRUCTURE, "<head> cannot appear inside <body>.")
                }
                frame.startsHead = true
                inHead = true
            }
            "body" -> {
                if (sawBody || inHead) {
                    fail(TtmlParseFailureReason.INVALID_STRUCTURE, "TTML must contain one <body> element.")
                }
                sawBody = true
                frame.startsBody = true
                inBody = true
                bodyDurationMs = attributes.attributeValue("dur")?.let { rawDuration ->
                    parseTime(rawDuration) ?: failInvalidTime("body dur", rawDuration)
                }
            }
        }

        if (inHead && name == "translations") {
            translationsContainerDepth++
            frame.startsTranslationsContainer = true
        } else if (inHead && name == "translation" && translationsContainerDepth > 0) {
            translationTrackDepth++
            frame.startsTranslationTrack = true
        } else if (
            inHead &&
            name == "text" &&
            translationTrackDepth > 0 &&
            currentHeadText == null
        ) {
            currentHeadText = MutableHeadTranslation(attributes.attributeValue("for"))
            frame.startsHeadText = true
        }

        val role = if (name == "p" || name == "span" || name == "rt") {
            attributes.attributeValue("role")
                ?.trim()
                ?.lowercase(Locale.ROOT)
        } else {
            null
        }
        if (name == "p") {
            when (role) {
                "x-bg" -> {
                    backgroundDepth++
                    frame.startsBackground = true
                }
                "x-roman" -> {
                    romanizationDepth++
                    frame.startsRomanization = true
                }
            }
        }

        if (inBody && name == "p" && backgroundDepth == 0 && romanizationDepth == 0) {
            if (currentLine != null) {
                fail(TtmlParseFailureReason.INVALID_STRUCTURE, "Nested <p> elements are not supported.")
            }
            val lineNumber = currentSourceLine()
            val range = parseTimedRange(
                attributes = attributes,
                elementDescription = "lyric line",
                requireTiming = true,
                allowZeroDuration = false
            )!!
            bodyDurationMs?.let { durationMs ->
                if (range.endMs > durationMs) {
                    fail(
                        TtmlParseFailureReason.INVALID_TIMING,
                        "Lyric line time exceeds the body duration."
                    )
                }
            }
            currentLine = MutableTtmlLine(
                startMs = range.startMs,
                endMs = range.endMs,
                key = attributes.attributeValue("key")?.trim()?.takeIf { it.isNotEmpty() },
                sourceLine = lineNumber
            )
            frame.startsLine = true
        }

        if (name == "span" || name == "rt") {
            when (role) {
                "x-bg" -> {
                    backgroundDepth++
                    frame.startsBackground = true
                }
                "x-roman" -> {
                    romanizationDepth++
                    frame.startsRomanization = true
                }
                "x-translation" -> {
                    if (currentLine != null && backgroundDepth == 0) {
                        if (inlineTranslationDepth == 0) {
                            currentInlineTranslation = StringBuilder()
                        }
                        inlineTranslationDepth++
                        frame.startsInlineTranslation = true
                    }
                }
            }

            val ruby = attributes.attributeValue("ruby")
                ?.trim()
                ?.lowercase(Locale.ROOT)
            if (name == "rt" || ruby in rubyTextValues) {
                rubyTextDepth++
                frame.startsRubyText = true
            }
        }

        if (
            name == "span" &&
            currentLine != null &&
            isMainLyricContext() &&
            declaredTimingMode != TtmlTimingMode.LINE
        ) {
            parseTimedRange(
                attributes = attributes,
                elementDescription = "lyric span",
                requireTiming = false,
                allowZeroDuration = true
            )?.let { range ->
                val line = currentLine!!
                if (line.activeSegment != null) {
                    fail(
                        TtmlParseFailureReason.INVALID_STRUCTURE,
                        "Nested timed lyric spans are not supported."
                    )
                }
                if (range.startMs < line.startMs || range.endMs > line.endMs) {
                    fail(
                        TtmlParseFailureReason.INVALID_TIMING,
                        "Lyric span time must stay within its lyric line."
                    )
                }
                val segment = MutableTtmlSegment(
                    startMs = range.startMs,
                    endMs = range.endMs,
                    sourceLine = currentSourceLine()
                )
                line.segments += segment
                line.activeSegment = segment
                frame.segment = segment
            }
        }

        if (name == "br") {
            appendText(" ")
        }

        frames.addLast(frame)
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        appendText(String(ch, start, length))
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = normalizedElementName(localName, qName)
        val frame = frames.removeLastOrNull()
            ?: fail(TtmlParseFailureReason.INVALID_STRUCTURE, "Unexpected closing element.")
        if (frame.name != name) {
            fail(TtmlParseFailureReason.INVALID_STRUCTURE, "Unexpected closing element </$name>.")
        }

        frame.segment?.let { segment ->
            val line = currentLine
                ?: fail(TtmlParseFailureReason.INVALID_STRUCTURE, "Timed span is outside a lyric line.")
            if (line.activeSegment !== segment) {
                fail(TtmlParseFailureReason.INVALID_STRUCTURE, "Invalid timed span nesting.")
            }
            line.activeSegment = null
        }

        if (frame.startsInlineTranslation) {
            inlineTranslationDepth--
            if (inlineTranslationDepth == 0) {
                normalizeDisplayText(currentInlineTranslation ?: "")
                    .takeIf { it.isNotBlank() }
                    ?.let { currentLine?.translations?.add(it) }
                currentInlineTranslation = null
            }
        }

        if (frame.startsHeadText) {
            val headText = currentHeadText
            currentHeadText = null
            val key = headText?.key?.trim().orEmpty()
            val text = normalizeDisplayText(headText?.text ?: "")
            if (key.isNotBlank() && text.isNotBlank()) {
                headTranslationsByKey.getOrPut(key) { mutableListOf() } += text
            }
        }

        if (frame.startsRubyText) rubyTextDepth--
        if (frame.startsRomanization) romanizationDepth--
        if (frame.startsBackground) backgroundDepth--

        if (frame.startsLine) {
            finishCurrentLine()
        }
        if (frame.startsTranslationTrack) translationTrackDepth--
        if (frame.startsTranslationsContainer) translationsContainerDepth--
        if (frame.startsHead) inHead = false
        if (frame.startsBody) inBody = false
    }

    override fun error(exception: SAXParseException) {
        throw exception
    }

    override fun fatalError(exception: SAXParseException) {
        throw exception
    }

    fun buildDocument(): ParsedTtmlDocument {
        if (!sawRoot) {
            fail(TtmlParseFailureReason.INVALID_STRUCTURE, "The TTML document is empty.")
        }
        if (!sawBody) {
            fail(TtmlParseFailureReason.INVALID_STRUCTURE, "The TTML document must contain <body>.")
        }

        val linesWithHeadTranslations = parsedLines.map { line ->
            val headTranslations = line.key
                ?.let { headTranslationsByKey[it] }
                .orEmpty()
            val effectiveTranslations = (line.translations + headTranslations)
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && it != line.text.trim() }
                .distinct()
                .toList()
            line.copy(translations = effectiveTranslations)
        }
        val effectiveTimingMode = when (declaredTimingMode) {
            TtmlTimingMode.UNSPECIFIED -> {
                if (linesWithHeadTranslations.any { it.segments.isNotEmpty() }) {
                    TtmlTimingMode.WORD
                } else {
                    TtmlTimingMode.LINE
                }
            }
            else -> declaredTimingMode
        }
        return ParsedTtmlDocument(
            lines = linesWithHeadTranslations,
            timingMode = effectiveTimingMode
        )
    }

    private fun appendText(rawText: String) {
        val text = meaningfulTextChunk(rawText) ?: return
        val line = currentLine
        if (line != null) {
            when {
                backgroundDepth > 0 || romanizationDepth > 0 || rubyTextDepth > 0 -> Unit
                inlineTranslationDepth > 0 -> currentInlineTranslation?.append(text)
                else -> {
                    line.text.append(text)
                    (line.activeSegment ?: line.segments.lastOrNull())?.text?.append(text)
                }
            }
            return
        }

        if (
            currentHeadText != null &&
            backgroundDepth == 0 &&
            romanizationDepth == 0 &&
            rubyTextDepth == 0
        ) {
            currentHeadText?.text?.append(text)
        }
    }

    private fun finishCurrentLine() {
        val line = currentLine
            ?: fail(TtmlParseFailureReason.INVALID_STRUCTURE, "Missing lyric line state.")
        val segments = line.segments.mapNotNull { segment ->
            val text = normalizeSegmentText(segment.text.toString())
            if (text.isEmpty()) return@mapNotNull null
            if (segment.endMs == segment.startMs && text.isNotBlank()) {
                fail(
                    reason = TtmlParseFailureReason.INVALID_TIMING,
                    message = "A non-empty lyric span must have a positive duration.",
                    lineNumber = segment.sourceLine
                )
            }
            ParsedTtmlSegment(
                text = text,
                startMs = segment.startMs,
                endMs = segment.endMs
            )
        }
        parsedLines += ParsedTtmlLine(
            startMs = line.startMs,
            endMs = line.endMs,
            text = normalizeDisplayText(line.text.toString()),
            segments = segments,
            translations = line.translations
                .asSequence()
                .map(::normalizeDisplayText)
                .filter { it.isNotBlank() }
                .distinct()
                .toList(),
            key = line.key
        )
        currentLine = null
    }

    private fun parseTimedRange(
        attributes: Attributes,
        elementDescription: String,
        requireTiming: Boolean,
        allowZeroDuration: Boolean
    ): TimedRange? {
        val beginRaw = attributes.attributeValue("begin")
        val endRaw = attributes.attributeValue("end")
        val durationRaw = attributes.attributeValue("dur")
        if (beginRaw == null && endRaw == null && durationRaw == null) {
            if (requireTiming) {
                fail(
                    TtmlParseFailureReason.INVALID_TIMING,
                    "The $elementDescription is missing begin and end timing."
                )
            }
            return null
        }
        if (beginRaw == null) {
            fail(TtmlParseFailureReason.INVALID_TIMING, "The $elementDescription is missing begin timing.")
        }
        if (endRaw == null && durationRaw == null) {
            fail(TtmlParseFailureReason.INVALID_TIMING, "The $elementDescription is missing end or dur timing.")
        }

        val startMs = parseTime(beginRaw) ?: failInvalidTime("begin", beginRaw)
        val explicitEndMs = endRaw?.let { parseTime(it) ?: failInvalidTime("end", it) }
        val durationMs = durationRaw?.let { parseTime(it) ?: failInvalidTime("dur", it) }
        val endMs = explicitEndMs ?: addTime(startMs, durationMs!!)
            ?: fail(TtmlParseFailureReason.INVALID_TIMING, "The $elementDescription time is too large.")
        val validDuration = if (allowZeroDuration) endMs >= startMs else endMs > startMs
        if (!validDuration) {
            fail(
                TtmlParseFailureReason.INVALID_TIMING,
                "The $elementDescription end time must be after its begin time."
            )
        }
        return TimedRange(startMs = startMs, endMs = endMs)
    }

    private fun isMainLyricContext(): Boolean {
        return backgroundDepth == 0 &&
            romanizationDepth == 0 &&
            inlineTranslationDepth == 0 &&
            rubyTextDepth == 0
    }

    private fun currentSourceLine(): Int? = locator?.lineNumber?.takeIf { it > 0 }

    private fun failInvalidTime(attribute: String, value: String): Nothing {
        fail(
            TtmlParseFailureReason.INVALID_TIMING,
            "Invalid $attribute time: $value"
        )
    }

    private fun fail(
        reason: TtmlParseFailureReason,
        message: String,
        lineNumber: Int? = currentSourceLine()
    ): Nothing {
        throw TtmlAbortException(
            TtmlParseResult.Failure(
                reason = reason,
                message = message,
                lineNumber = lineNumber,
                columnNumber = locator?.columnNumber?.takeIf { it > 0 }
            )
        )
    }
}

private data class ElementFrame(
    val name: String,
    var startsHead: Boolean = false,
    var startsBody: Boolean = false,
    var startsLine: Boolean = false,
    var startsTranslationsContainer: Boolean = false,
    var startsTranslationTrack: Boolean = false,
    var startsHeadText: Boolean = false,
    var startsInlineTranslation: Boolean = false,
    var startsBackground: Boolean = false,
    var startsRomanization: Boolean = false,
    var startsRubyText: Boolean = false,
    var segment: MutableTtmlSegment? = null
)

private data class MutableTtmlLine(
    val startMs: Long,
    val endMs: Long,
    val key: String?,
    val sourceLine: Int?,
    val text: StringBuilder = StringBuilder(),
    val segments: MutableList<MutableTtmlSegment> = mutableListOf(),
    val translations: MutableList<String> = mutableListOf(),
    var activeSegment: MutableTtmlSegment? = null
)

private data class MutableTtmlSegment(
    val startMs: Long,
    val endMs: Long,
    val sourceLine: Int?,
    val text: StringBuilder = StringBuilder()
)

private data class MutableHeadTranslation(
    val key: String?,
    val text: StringBuilder = StringBuilder()
)

private data class TimedRange(val startMs: Long, val endMs: Long)

private class TtmlAbortException(
    val failure: TtmlParseResult.Failure
) : SAXException(failure.message)

private fun SAXParserFactory.setFeatureIfSupported(feature: String, enabled: Boolean) {
    runCatching { setFeature(feature, enabled) }
}

private fun org.xml.sax.XMLReader.disableFeatureIfSupported(feature: String) {
    runCatching { setFeature(feature, false) }
}

private fun Attributes.attributeValue(localName: String): String? {
    for (index in 0 until length) {
        val actualLocalName = getLocalName(index)
            .takeIf { it.isNotBlank() }
            ?: getQName(index).substringAfterLast(':')
        if (actualLocalName.equals(localName, ignoreCase = true)) {
            return getValue(index)
        }
    }
    return null
}

private fun normalizedElementName(localName: String?, qName: String?): String {
    return localName
        ?.takeIf { it.isNotBlank() }
        ?.lowercase(Locale.ROOT)
        ?: qName.orEmpty().substringAfterLast(':').lowercase(Locale.ROOT)
}

private fun parseTimingMode(rawValue: String?): TtmlTimingMode {
    return when (rawValue?.trim()?.lowercase(Locale.ROOT)) {
        "line" -> TtmlTimingMode.LINE
        "word" -> TtmlTimingMode.WORD
        else -> TtmlTimingMode.UNSPECIFIED
    }
}

private fun parseTime(rawValue: String): Long? {
    val value = rawValue.trim()
    if (value.isEmpty()) return null

    secondsWithSuffixRegex.matchEntire(value)?.let { match ->
        return runCatching {
            BigDecimal(match.groupValues[1])
                .movePointRight(3)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull()?.takeIf { it >= 0L }
    }

    hoursClockRegex.matchEntire(value)?.let { match ->
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull()?.takeIf { it < 60L } ?: return null
        val seconds = match.groupValues[3].toLongOrNull()?.takeIf { it < 60L } ?: return null
        val millis = fractionToMillis(match.groupValues[4]) ?: return null
        return clockTimeToMillis(hours, minutes, seconds, millis)
    }

    minutesClockRegex.matchEntire(value)?.let { match ->
        val minutes = match.groupValues[1].toLongOrNull()?.takeIf { it < 60L } ?: return null
        val seconds = match.groupValues[2].toLongOrNull()?.takeIf { it < 60L } ?: return null
        val millis = fractionToMillis(match.groupValues[3]) ?: return null
        return clockTimeToMillis(0L, minutes, seconds, millis)
    }

    secondsOnlyRegex.matchEntire(value)?.let { match ->
        val seconds = match.groupValues[1].toLongOrNull() ?: return null
        val millis = fractionToMillis(match.groupValues[2]) ?: return null
        return addTime(seconds.timesWithoutOverflow(1_000L) ?: return null, millis)
    }

    return null
}

private fun fractionToMillis(fraction: String): Long? {
    if (fraction.isEmpty()) return 0L
    if (fraction.length > 3) return null
    return fraction.padEnd(3, '0').toLongOrNull()
}

private fun clockTimeToMillis(hours: Long, minutes: Long, seconds: Long, millis: Long): Long? {
    val hourMs = hours.timesWithoutOverflow(3_600_000L) ?: return null
    val minuteMs = minutes.timesWithoutOverflow(60_000L) ?: return null
    return addTime(addTime(addTime(hourMs, minuteMs) ?: return null, seconds * 1_000L) ?: return null, millis)
}

private fun Long.timesWithoutOverflow(other: Long): Long? {
    return runCatching { Math.multiplyExact(this, other) }.getOrNull()
}

private fun addTime(first: Long, second: Long): Long? {
    return runCatching { Math.addExact(first, second) }.getOrNull()
}

private fun meaningfulTextChunk(rawText: String): String? {
    if (rawText.isEmpty()) return null
    if (rawText.all(Char::isWhitespace)) {
        return if (rawText.any(::isLayoutWhitespace)) null else " "
    }
    return rawText.replace(whitespaceRegex, " ")
}

private fun normalizeDisplayText(text: CharSequence): String {
    return text.toString().replace(whitespaceRegex, " ").trim()
}

private fun normalizeSegmentText(text: String): String {
    if (text.isEmpty()) return ""
    if (text.isBlank()) return " "

    val hasLeadingSpace = text.first().isWhitespace()
    val hasTrailingSpace = text.last().isWhitespace()
    val core = text.replace(whitespaceRegex, " ").trim()
    return buildString {
        if (hasLeadingSpace) append(' ')
        append(core)
        if (hasTrailingSpace) append(' ')
    }
}

private fun isLayoutWhitespace(character: Char): Boolean {
    return character == '\n' || character == '\r' || character == '\t'
}

private fun Throwable.findTtmlAbort(): TtmlAbortException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is TtmlAbortException) return current
        current = current.cause
    }
    return null
}

private fun Throwable.findSaxParseException(): SAXParseException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is SAXParseException) return current
        current = current.cause
    }
    return null
}

private const val MAX_ELEMENT_DEPTH = 256
private const val DISALLOW_DOCTYPE_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl"
private const val EXTERNAL_GENERAL_ENTITIES_FEATURE = "http://xml.org/sax/features/external-general-entities"
private const val EXTERNAL_PARAMETER_ENTITIES_FEATURE = "http://xml.org/sax/features/external-parameter-entities"
private const val LOAD_EXTERNAL_DTD_FEATURE = "http://apache.org/xml/features/nonvalidating/load-external-dtd"

private val unsafeXmlDeclarationRegex = Regex(
    pattern = """<!\s*(?:DOCTYPE|ENTITY)\b""",
    option = RegexOption.IGNORE_CASE
)
private val hoursClockRegex = Regex("""^(\d+):(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?$""")
private val minutesClockRegex = Regex("""^(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?$""")
private val secondsOnlyRegex = Regex("""^(\d+)(?:\.(\d{1,3}))?$""")
private val secondsWithSuffixRegex = Regex("""^(\d+(?:\.\d+)?)s$""", RegexOption.IGNORE_CASE)
private val whitespaceRegex = Regex("\\s+")
private val rubyTextValues = setOf("text", "textcontainer", "delimiter")
