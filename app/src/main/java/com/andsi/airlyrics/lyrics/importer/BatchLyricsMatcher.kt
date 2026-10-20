package com.andsi.airlyrics.lyrics.importer

import com.andsi.airlyrics.core.model.SongIdentity

/** Matches only inside the explicit selection. Never guesses from list order or fuzzy titles. */
internal object BatchLyricsMatcher {
    data class Target(val key: String, val song: SongIdentity)
    data class Document(val name: String, val title: String = "", val artist: String = "", val targetKey: String = "")
    enum class Problem { NO_MATCH, AMBIGUOUS, DUPLICATE }
    data class Match(val target: Target? = null, val problem: Problem? = null)

    fun match(documents: List<Document>, targets: List<Target>): List<Match> {
        val eligible = targets.filter { it.song.title.isNotBlank() }.distinctBy { it.key }
        val matches = documents.map { document ->
            val candidates = if (document.targetKey.isNotBlank()) eligible.filter { it.key == document.targetKey }
            else if (documents.size == 1 && eligible.size == 1) eligible else {
                val title = normalize(document.title)
                val artist = normalize(document.artist)
                val stem = normalize(document.name.substringBeforeLast('.'))
                eligible.filter { target ->
                    val songTitle = normalize(target.song.title)
                    val songArtist = normalize(target.song.artist)
                    // Metadata, when present, must agree; a filename cannot override a conflicting tag.
                    (title.isBlank() || title == songTitle) && (artist.isBlank() || artist == songArtist) &&
                        (title.isNotBlank() || stem == songTitle ||
                            (songArtist.isNotBlank() && stem in setOf("$songArtist - $songTitle", "$songTitle - $songArtist")))
                }
            }
            when (candidates.size) {
                0 -> Match(problem = Problem.NO_MATCH)
                1 -> Match(target = candidates.single())
                else -> Match(problem = Problem.AMBIGUOUS)
            }
        }
        val duplicates = matches.mapNotNull { it.target?.key }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return matches.map { if (it.target?.key in duplicates) Match(problem = Problem.DUPLICATE) else it }
    }

    private fun normalize(text: String) = SongIdentity.normalizeText(text)
}
