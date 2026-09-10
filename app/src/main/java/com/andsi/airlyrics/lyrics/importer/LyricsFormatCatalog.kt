package com.andsi.airlyrics.lyrics.importer

import java.util.Locale

internal object LyricsFormatCatalog {
    private const val CONTENT_PROBE_LIMIT = 16 * 1024

    private data class FormatDefinition(
        val format: LyricsDocumentFormat,
        val extensions: Set<String>,
        val mimeTypes: Set<String>,
        val matchesContent: (String) -> Boolean
    )

    // Structured formats are checked before permissive text formats.
    private val definitions = listOf(
        FormatDefinition(
            format = LyricsDocumentFormat.TTML,
            extensions = setOf("ttml"),
            mimeTypes = setOf("application/ttml+xml"),
            matchesContent = { content -> ttmlRootRegex.containsMatchIn(content) }
        ),
        FormatDefinition(
            format = LyricsDocumentFormat.LRC,
            extensions = setOf("lrc"),
            mimeTypes = setOf(
                "application/x-lrc",
                "application/lrc",
                "text/lrc"
            ),
            matchesContent = { content -> lrcTimedLineRegex.containsMatchIn(content) }
        )
    )

    private val ambiguousLyricsMimeTypes = listOf(
        "text/plain",
        "text/*",
        "application/xml",
        "text/xml",
        "application/octet-stream"
    )

    fun formatFromFileName(fileName: String?): LyricsDocumentFormat? {
        val normalizedName = fileName.normalizedFileName() ?: return null
        return definitions.firstOrNull { definition ->
            definition.extensions.any { extension ->
                normalizedName.endsWith(".$extension")
            }
        }?.format
    }

    fun formatFromMimeType(mimeType: String?): LyricsDocumentFormat? {
        val normalizedMimeType = mimeType.normalizedMimeType() ?: return null
        return definitions.firstOrNull { normalizedMimeType in it.mimeTypes }?.format
    }

    fun formatFromContent(content: CharSequence): LyricsDocumentFormat {
        val probe = content.subSequence(0, minOf(content.length, CONTENT_PROBE_LIMIT)).toString()
        return definitions.firstOrNull { it.matchesContent(probe) }?.format
            ?: LyricsDocumentFormat.UNKNOWN
    }

    fun containsSupportedExtension(value: String?): Boolean {
        val normalizedValue = value.normalizedFileName() ?: return false
        return definitions.any { definition ->
            definition.extensions.any { extension -> normalizedValue.contains(".$extension") }
        }
    }

    fun isPotentialLyricsMimeType(mimeType: String?): Boolean {
        val normalizedMimeType = mimeType.normalizedMimeType() ?: return true
        return definitions.any { normalizedMimeType in it.mimeTypes } ||
            normalizedMimeType in ambiguousLyricsMimeTypes ||
            normalizedMimeType.startsWith("text/")
    }

    fun pickerMimeTypes(): Array<String> {
        return buildList {
            add("*/*")
            definitions.forEach { definition -> addAll(definition.mimeTypes) }
            addAll(ambiguousLyricsMimeTypes)
        }.distinct().toTypedArray()
    }

    private fun String?.normalizedFileName(): String? {
        return this
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun String?.normalizedMimeType(): String? {
        return this
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
    }

    private val lrcTimedLineRegex = Regex(
        pattern = """(?m)^[\t \uFEFF]*\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]"""
    )

    private val ttmlRootRegex = Regex(
        pattern = """^(?:[\s\uFEFF]|<\?.*?\?>|<!--.*?-->)*<(?:[A-Za-z_][\w.-]*:)?tt(?:\s|/?>)""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
}
