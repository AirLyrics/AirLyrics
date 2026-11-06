package com.andsi.airlyrics.core.model

import org.junit.Assert.*
import org.junit.Test

class SpotifyTrackIdTest {
    private val id = "5l94Up12BJSjhH7Mhr2ji4"

    @Test fun acceptsTrackUrisOfficialLinksAndBareIds() {
        for (input in listOf(
            id, " $id ", "spotify:track:$id",
            "https://open.spotify.com/track/$id?si=share",
            "https://open.spotify.com/intl-zh/track/$id/",
        )) {
            assertEquals(input, id, SpotifyTrackId.parse(input))
        }
    }

    @Test fun rejectsOtherContentAndMisleadingLinks() {
        for (input in listOf("", "spotify:episode:$id", "spotify:local:$id",
            "https://open.spotify.com/episode/$id", "https://open.spotify.com.evil/track/$id",
            "https://open.spotify.com@evil.test/track/$id", "http://open.spotify.com/track/$id",
            "https://evil.test/track/$id", "https://open.spotify.com/track/$id/extra",
            "https://open.spotify.com:123/track/$id", id.dropLast(1), id + "a",
            "一".repeat(22), "_".repeat(22))) {
            assertNull(input, SpotifyTrackId.parse(input))
        }
    }

    @Test fun mediaIdentityIsSpotifyOnlyAndFallsBackToUri() {
        assertEquals(id, SpotifyTrackId.fromMediaSession(SpotifyTrackId.PACKAGE_NAME, "invalid", "spotify:track:$id"))
        assertNull(SpotifyTrackId.fromMediaSession("other.player", id, "spotify:track:$id"))
        assertNull(SpotifyTrackId.fromMediaSession(SpotifyTrackId.PACKAGE_NAME, "spotify:episode:$id", null))
    }
}
