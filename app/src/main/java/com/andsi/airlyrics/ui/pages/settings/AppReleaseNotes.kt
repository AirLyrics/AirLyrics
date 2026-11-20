package com.andsi.airlyrics.ui.pages.settings

import android.content.Context
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal data class AppReleaseNotes(
    val version: String,
    val date: LocalDate? = null,
    val notes: String = ""
)

private val releaseHeader = Regex("""^v(\d+\.\d+\.\d+) - (\d{4}-\d{2}-\d{2})$""", RegexOption.MULTILINE)

internal fun parseAppReleaseNotes(text: String): List<AppReleaseNotes> {
    val headers = releaseHeader.findAll(text).toList()
    return headers.mapIndexed { index, header ->
        val (version, date) = header.destructured
        AppReleaseNotes(
            version,
            try {
                LocalDate.parse(date)
            } catch (_: DateTimeParseException) {
                null
            },
            text.substring(header.range.last + 1, headers.getOrNull(index + 1)?.range?.first ?: text.length).trim()
        )
    }
}

private val markdownHeading = Regex("""^#{1,6}\s+""")

internal fun formatGitHubReleaseNotes(markdown: String): String =
    markdown.lineSequence().joinToString("\n") { line ->
        val text = line.replaceFirst(markdownHeading, "")
        if (text.startsWith("* ")) "- " + text.substring(2) else text
    }.trim()

internal fun Context.loadBundledReleaseNotes(): List<AppReleaseNotes> =
    listOf("changelog.txt", "changelog_current.txt")
        .flatMap { name ->
            try {
                assets.open(name).bufferedReader().use { parseAppReleaseNotes(it.readText()) }
            } catch (_: IOException) {
                emptyList()
            }
        }
        .distinctBy(AppReleaseNotes::version)
