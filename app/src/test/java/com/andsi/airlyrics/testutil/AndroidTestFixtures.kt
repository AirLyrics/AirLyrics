package com.andsi.airlyrics.testutil

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.storage.LyricsFileNaming
import com.andsi.airlyrics.lyrics.storage.LyricsFileStore
import com.andsi.airlyrics.lyrics.storage.LyricsIndexEntry
import com.andsi.airlyrics.lyrics.storage.LyricsIndexStore
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PlainLyricsStorageOps
import com.andsi.airlyrics.lyrics.storage.WordByWordLyricsJsonCodec
import com.andsi.airlyrics.lyrics.storage.isSameSong
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowLooper

internal fun descendants(view: View): List<View> = listOf(view) +
    if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    else emptyList()

internal fun awaitUiCondition(message: String = "Timed out waiting for UI work", condition: () -> Boolean) {
    awaitCondition(message, drainMain = true, condition)
}

internal fun awaitBackgroundCondition(condition: () -> Boolean) {
    awaitCondition("Timed out waiting for background work", drainMain = false, condition)
}

private fun awaitCondition(message: String, drainMain: Boolean, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        if (drainMain) ShadowLooper.idleMainLooper()
        if (condition()) return
        Thread.sleep(10)
    }
    if (drainMain) ShadowLooper.idleMainLooper()
    assertTrue(message, condition())
}

internal fun resetLyricsStorage(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
}

internal fun seedPlainLyrics(
    context: Context,
    title: String,
    artist: String,
    duration: Long,
    plainLrc: String,
    album: String = "",
    plainSource: String = LyricsStorage.SOURCE_DOWNLOADED,
    plainProvider: String = "local",
    matchedTitle: String = "",
    matchedArtist: String = "",
    matchedDurationMs: Long = 0L,
    isUntimed: Boolean = false,
    untimedTranslation: String? = null,
): Boolean = LyricsStorage.withStorageLock {
    PlainLyricsStorageOps.savePlainLyrics(
        context, title, artist, duration, plainLrc, album, plainSource, plainProvider,
        matchedTitle = matchedTitle,
        matchedArtist = matchedArtist,
        matchedDurationMs = matchedDurationMs,
        isUntimed = isUntimed,
        untimedTranslation = untimedTranslation,
    ).also { if (it) LyricsStorage.markContentChanged() }
}

internal fun seedLegacyWordLyrics(
    context: Context,
    title: String,
    artist: String,
    duration: Long,
    wordByWordLines: List<WordByWordLine>,
    album: String = "",
    wordByWordProvider: String = "local",
    metadataLines: List<String> = emptyList(),
): Boolean = LyricsStorage.withStorageLock {
    val song = SongIdentity(title, artist, album, duration)
    val key = song.storageKey()
    val file = LyricsFileNaming.managedWordByWordFileName(song)
    val payload = WordByWordLyricsJsonCodec.wordByWordLinesToJson(wordByWordLines, metadataLines)
    if (!LyricsFileStore.writeManagedLyrics(context, file, payload)) return@withStorageLock false

    val now = System.currentTimeMillis()
    val existing = LyricsIndexStore.find(context, title, artist, duration)
    val entry = existing ?: LyricsIndexEntry(
        key, title, artist, album, duration,
        plainFile = "",
        plainSource = LyricsStorage.SOURCE_DOWNLOADED,
        plainProvider = "local",
        createdAt = now,
        updatedAt = now,
    )
    val updated = entry.copy(
        key = key,
        wordByWordFile = LyricsFileNaming.managedRelativePath(file),
        wordByWordProvider = wordByWordProvider,
        updatedAt = now,
    )
    val entries = LyricsIndexStore.read(context).filterNot { it.key == key || it.isSameSong(song) }
    LyricsIndexStore.write(context, entries + updated).also { if (it) LyricsStorage.markContentChanged() }
}

internal fun seedLegacyFloatingPosition(context: Context, x: Int, y: Int) {
    context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE).edit()
        .putInt("pos_x", x)
        .putInt("pos_y", y)
        .commit()
}
