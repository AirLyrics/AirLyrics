package com.andsi.airlyrics.ui.pages.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaPlayerEntranceMotionTest {
    @Test
    fun onlyNewPlayerPackagesEnterAcrossRefreshAndMediaUpdates() {
        val motion = MediaPlayerEntranceMotion()

        assertEquals(setOf("player.a"), motion.updatePackages(setOf("player.a")))
        assertEquals(emptySet<String>(), motion.updatePackages(setOf("player.a")))
        assertEquals(setOf("player.b"), motion.updatePackages(setOf("player.a", "player.b")))
        assertEquals(emptySet<String>(), motion.updatePackages(setOf("player.a", "player.b")))
        assertEquals(emptySet<String>(), motion.updatePackages(setOf("player.b")))
        assertEquals(setOf("player.a"), motion.updatePackages(setOf("player.a", "player.b")))
    }
}
