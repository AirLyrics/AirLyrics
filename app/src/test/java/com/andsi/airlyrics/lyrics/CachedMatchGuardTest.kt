package com.andsi.airlyrics.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedMatchGuardTest {

    @Test
    fun `a cache filled by a different song by the same artist is not trusted`() {
        // Susan Said (《苏三说》 261,826 ms) was once cached from 陶喆《我太傻》 (260,907 ms).
        assertFalse(
            CachedMatchGuard.isTrusted(
                playedTitle = "Susan Said",
                cachedMatchedTitle = "我太傻",
                aliasTitle = null
            )
        )
    }

    @Test
    fun `a learned alias makes the same cache trusted`() {
        assertTrue(
            CachedMatchGuard.isTrusted(
                playedTitle = "Susan Said",
                cachedMatchedTitle = "苏三说",
                aliasTitle = "苏三说"
            )
        )
    }

    @Test
    fun `an identical title is trusted`() {
        assertTrue(CachedMatchGuard.isTrusted("Radio Love Song", "Radio Love Song", null))
    }

    @Test
    fun `a translated title without an alias is re-verified`() {
        assertFalse(
            CachedMatchGuard.isTrusted(
                playedTitle = "Radio Love Song",
                cachedMatchedTitle = "电台情歌",
                aliasTitle = null
            )
        )
        assertTrue(
            CachedMatchGuard.isTrusted(
                playedTitle = "Radio Love Song",
                cachedMatchedTitle = "电台情歌",
                aliasTitle = "电台情歌"
            )
        )
    }

    @Test
    fun `entries from before provenance tracking stay trusted`() {
        assertTrue(CachedMatchGuard.isTrusted("Anything", "", null))
    }

    @Test
    fun `feature credits and punctuation do not break the comparison`() {
        assertTrue(
            CachedMatchGuard.isTrusted(
                playedTitle = "Shouldn't Be (feat. a-mei)",
                cachedMatchedTitle = "不应该 feat. 张惠妹",
                aliasTitle = "不应该"
            )
        )
    }
}
