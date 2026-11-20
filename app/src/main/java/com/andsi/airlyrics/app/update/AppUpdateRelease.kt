package com.andsi.airlyrics.app.update

import java.time.LocalDate

internal data class AppUpdateVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<AppUpdateVersion> {
    override fun compareTo(other: AppUpdateVersion): Int =
        compareValuesBy(this, other, AppUpdateVersion::major, AppUpdateVersion::minor, AppUpdateVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")

        fun parse(value: String): AppUpdateVersion? {
            val (major, minor, patch) = pattern.matchEntire(value)?.destructured ?: return null
            return AppUpdateVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null
            )
        }
    }
}

@ConsistentCopyVisibility
internal data class AppUpdateRelease private constructor(
    val version: AppUpdateVersion,
    val sizeBytes: Long,
    val sha256: String,
    val publishedOn: LocalDate?,
    val notes: String
) {
    companion object {
        private const val MAX_APK_BYTES = 256L * 1024 * 1024
        private val sha256Pattern = Regex("[a-fA-F0-9]{64}")

        fun create(
            version: AppUpdateVersion,
            sizeBytes: Long,
            sha256: String,
            publishedOn: LocalDate? = null,
            notes: String = ""
        ): AppUpdateRelease? {
            if (sizeBytes !in 1..MAX_APK_BYTES || !sha256Pattern.matches(sha256)) return null
            return AppUpdateRelease(version, sizeBytes, sha256.lowercase(), publishedOn, notes.take(20_000).trim())
        }
    }
}
