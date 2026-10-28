package com.andsi.airlyrics.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exhaustive snapshot test: every key of the bundled tw2s dictionaries must keep converting to
 * the same text. Regenerate opencc-golden.tsv whenever the dictionaries are updated.
 */
class OpenCCGoldenTest {

    @Test
    fun everyDictionaryEntryConvertsAsSnapshotted() {
        val directory = listOf(
            File("src/main/assets/opencc"),
            File("app/src/main/assets/opencc")
        ).firstOrNull(File::isDirectory) ?: error("dictionary directory not found")

        ChineseText.resetForTest()
        ChineseText.loadFromDirectory(directory)

        val resource = javaClass.classLoader!!.getResourceAsStream("opencc-golden.tsv")
            ?: error("opencc-golden.tsv missing from test resources")

        var checked = 0
        val failures = mutableListOf<String>()
        resource.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.contains('\t') }.forEach { line ->
                val key = line.substringBefore('\t')
                val expected = line.substringAfter('\t')
                val actual = ChineseText.toSimplified(key)
                checked++
                if (actual != expected && failures.size < 20) {
                    failures += "$key -> $actual (expected $expected)"
                }
            }
        }

        assertTrue("golden file looks empty", checked > 5_000)
        assertEquals(failures.joinToString("\n"), emptyList<String>(), failures)
    }
}
