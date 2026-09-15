package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Before

abstract class LyricsStorageTestBase {
    protected lateinit var context: Context

    @Before
    fun setUpLyricsStorageTestBase() {
        context = ApplicationProvider.getApplicationContext()
        resetStorage()
    }

    @After
    fun tearDownLyricsStorageTestBase() {
        resetStorage()
    }

    protected fun writeImportFile(name: String, text: String): Uri {
        val file = File(context.cacheDir, name)
        file.writeText(text)
        return Uri.fromFile(file)
    }

    protected fun writeImportBytes(name: String, bytes: ByteArray): Uri {
        val file = File(context.cacheDir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun resetStorage() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }

    protected val lineTimedTtml =
        """
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions"
            itunes:timing="Line">
            <body>
                <div>
                    <p begin="1.2s" end="3.5s">First <span>line</span><span ttm:role="x-translation">第一行</span></p>
                    <p begin="3.5s" dur="1.5s">Second line</p>
                </div>
            </body>
        </tt>
        """.trimIndent()

    protected val wordTimedTtml =
        """
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions"
            itunes:timing="Word">
            <body>
                <p begin="10s" end="12s"><span begin="10s" end="10.3s">he</span><span begin="10.3s" end="10.6s">llo </span><span begin="10.6s" end="12s">world</span><span ttm:role="x-translation">你好，世界</span></p>
            </body>
        </tt>
        """.trimIndent()

    protected val mixedTimingTtml =
        """
        <tt xmlns="http://www.w3.org/ns/ttml">
            <body>
                <p begin="1s" end="2s"><span begin="1s" end="2s">word timed</span></p>
                <p begin="2s" end="3s">line timed</p>
            </body>
        </tt>
        """.trimIndent()
}
