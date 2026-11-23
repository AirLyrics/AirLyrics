package com.andsi.airlyrics.apple

import java.util.Base64

object AppleMusicTokens {

    private val JWT_PATTERN = Regex("""eyJ[A-Za-z0-9_.-]{80,}""")
    private val SCRIPT_SRC_PATTERN = Regex("""src="(/assets/[^"]+\.js)"""")
    private val EXP_PATTERN = Regex(""""exp"\s*:\s*(\d+)""")

    fun parseMediaUserToken(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        return cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("media-user-token=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun jwtExpiryEpochSeconds(jwt: String): Long? {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        val decoded = runCatching {
            Base64.getUrlDecoder().decode(payload + "=".repeat((4 - payload.length % 4) % 4))
        }.getOrNull() ?: return null
        return EXP_PATTERN.find(decoded.toString(Charsets.UTF_8))?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    fun isJwtFresh(jwt: String, nowEpochSeconds: Long, minRemainingSeconds: Long = 86_400): Boolean {
        val expiry = jwtExpiryEpochSeconds(jwt) ?: return false
        return expiry - nowEpochSeconds > minRemainingSeconds
    }

    fun pickScriptPath(html: String): String? = SCRIPT_SRC_PATTERN.find(html)?.groupValues?.getOrNull(1)

    fun pickDeveloperToken(bundle: String, nowEpochSeconds: Long): String? {
        val candidates = JWT_PATTERN.findAll(bundle)
            .map { it.value }
            .distinct()
            .mapNotNull { token ->
                val expiry = jwtExpiryEpochSeconds(token) ?: return@mapNotNull null
                if (expiry - nowEpochSeconds < 3_600) return@mapNotNull null
                token to expiry
            }
            .toList()
        return candidates.maxByOrNull { it.second }?.first
    }
}
