package com.andsi.airlyrics.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AppleMusicTokensTest {

    private fun jwt(exp: Long, issuer: String = "AMPWebPlay"): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"ES256"}""".toByteArray())
        val payload = encoder.encodeToString("""{"iss":"$issuer","exp":$exp}""".toByteArray())
        // Apple tokens are long; keep the fixture long enough for the scanner's minimum length.
        val signature = "s".repeat(96)
        return "$header.$payload.$signature"
    }

    @Test
    fun `reads the media user token from a cookie header`() {
        val cookie = "foo=bar; media-user-token=0.Au3f3VDecnwF50; other=1"
        assertEquals("0.Au3f3VDecnwF50", AppleMusicTokens.parseMediaUserToken(cookie))
    }

    @Test
    fun `returns null when the cookie header has no media user token`() {
        assertNull(AppleMusicTokens.parseMediaUserToken("foo=bar; baz=1"))
        assertNull(AppleMusicTokens.parseMediaUserToken(null))
    }

    @Test
    fun `reads the exp claim from a jwt`() {
        assertEquals(1_800_000_000L, AppleMusicTokens.jwtExpiryEpochSeconds(jwt(1_800_000_000)))
        assertNull(AppleMusicTokens.jwtExpiryEpochSeconds("not-a-jwt"))
    }

    @Test
    fun `treats a token close to expiry as stale`() {
        val token = jwt(2_000_000_000)
        assertTrue(AppleMusicTokens.isJwtFresh(token, nowEpochSeconds = 1_900_000_000))
        assertFalse(AppleMusicTokens.isJwtFresh(token, nowEpochSeconds = 1_999_950_000))
    }

    @Test
    fun `finds the app bundle script in the page html`() {
        val html = """<script src="/assets/index~bc39edf62f.js"></script>"""
        assertEquals("/assets/index~bc39edf62f.js", AppleMusicTokens.pickScriptPath(html))
        assertNull(AppleMusicTokens.pickScriptPath("<html></html>"))
    }

    @Test
    fun `picks the developer token with the longest remaining life`() {
        val now = 1_700_000_000L
        val shortLived = jwt(now + 7_200, issuer = "SHORT")
        val longLived = jwt(now + 5_000_000, issuer = "AMPWebPlay")
        val expired = jwt(now - 10)
        val bundle = "var a=\"$expired\";var b=\"$shortLived\";var c=\"$longLived\";"
        assertEquals(longLived, AppleMusicTokens.pickDeveloperToken(bundle, now))
    }

    @Test
    fun `ignores bundles without usable tokens`() {
        assertNull(AppleMusicTokens.pickDeveloperToken("no tokens here", 1_700_000_000))
    }
}
