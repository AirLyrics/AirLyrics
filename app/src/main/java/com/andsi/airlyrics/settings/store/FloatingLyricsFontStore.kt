package com.andsi.airlyrics.settings.store

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.LruCache
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.graphics.TypefaceCompat
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.FloatingLyricsFontWeight
import com.andsi.airlyrics.core.prefs.prefs
import java.io.File
import java.io.RandomAccessFile

/** Imports and resolves fonts used exclusively by the floating lyrics window. */
object FloatingLyricsFontStore {
    private const val PREFS_NAME = "floating_lyrics_font"
    private const val KEY_CUSTOM_FONT_NAME = "custom_font_name"
    private const val FONT_DIRECTORY = "floating_fonts"
    private const val CUSTOM_FONT_FILE = "custom_font"
    private const val TEMP_FONT_FILE = "custom_font.importing"
    private const val MAX_FONT_BYTES = 20L * 1024L * 1024L
    private const val MAX_SFNT_TABLES = 512
    private const val SFNT_HEADER_SIZE = 12L
    private const val SFNT_TABLE_RECORD_SIZE = 16L
    private const val FVAR_HEADER_SIZE = 16L
    private const val FVAR_AXIS_RECORD_MIN_SIZE = 20
    private const val FVAR_TAG = 0x66766172
    private const val WEIGHT_AXIS_TAG = 0x77676874

    sealed class ImportResult {
        data class Success(val displayName: String) : ImportResult()
        object UnsupportedFormat : ImportResult()
        object TooLarge : ImportResult()
        object InvalidFont : ImportResult()
        object ReadFailed : ImportResult()
    }

    private val typefaceCache = LruCache<String, Typeface>(12)

    fun importFont(context: Context, uri: Uri): ImportResult {
        val displayName = documentDisplayName(context, uri)
        if (!isSupportedFontFileName(displayName)) return ImportResult.UnsupportedFormat
        if (documentSize(context, uri)?.let { it > MAX_FONT_BYTES } == true) {
            return ImportResult.TooLarge
        }

        val directory = fontDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) return ImportResult.ReadFailed
        val temporary = File(directory, TEMP_FONT_FILE)

        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return ImportResult.ReadFailed
            input.use { source ->
                temporary.outputStream().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_FONT_BYTES) return ImportResult.TooLarge
                        target.write(buffer, 0, read)
                    }
                }
            }

            if (temporary.length() == 0L || !isValidFontFile(temporary)) {
                return ImportResult.InvalidFont
            }
            if (!replaceCustomFontAtomically(temporary, customFontFile(context))) {
                return ImportResult.ReadFailed
            }

            prefs(context, PREFS_NAME).setString(KEY_CUSTOM_FONT_NAME, displayName)
            typefaceCache.evictAll()
            ImportResult.Success(displayName)
        } catch (_: Exception) {
            ImportResult.ReadFailed
        } finally {
            temporary.delete()
        }
    }

    fun hasCustomFont(context: Context): Boolean {
        return customFontFile(context).let { it.isFile && it.length() > 0L }
    }

    fun customFontDisplayName(context: Context): String? {
        if (!hasCustomFont(context)) return null
        return prefs(context, PREFS_NAME).getString(KEY_CUSTOM_FONT_NAME)
            ?.takeIf { it.isNotBlank() }
    }

    fun resolveTypeface(
        context: Context,
        fontFamily: FloatingLyricsFontFamily,
        fontWeight: Int
    ): Typeface {
        val weight = FloatingLyricsFontWeight.normalize(fontWeight)
        val customFile = customFontFile(context)
        val cacheKey = buildString {
            append(fontFamily.key)
            append(':')
            append(weight)
            if (fontFamily == FloatingLyricsFontFamily.CUSTOM) {
                append(':')
                append(customFile.lastModified())
                append(':')
                append(customFile.length())
            }
        }
        typefaceCache.get(cacheKey)?.let { return it }

        val resolved = if (fontFamily == FloatingLyricsFontFamily.CUSTOM && hasCustomFont(context)) {
            buildCustomTypeface(context, customFile, weight)
                ?: weightedSystemTypeface(context, Typeface.DEFAULT, weight)
        } else {
            val base = when (fontFamily) {
                FloatingLyricsFontFamily.SANS_SERIF -> Typeface.SANS_SERIF
                FloatingLyricsFontFamily.SERIF -> Typeface.SERIF
                FloatingLyricsFontFamily.MONOSPACE -> Typeface.MONOSPACE
                FloatingLyricsFontFamily.SYSTEM_DEFAULT,
                FloatingLyricsFontFamily.CUSTOM -> Typeface.DEFAULT
            }
            weightedSystemTypeface(context, base, weight)
        }
        typefaceCache.put(cacheKey, resolved)
        return resolved
    }

    /** Applies both the requested face and its variable-font axis to rendered lyrics text. */
    fun applyTypeface(
        view: TextView,
        fontFamily: FloatingLyricsFontFamily,
        fontWeight: Int
    ) {
        val weight = FloatingLyricsFontWeight.normalize(fontWeight)
        view.typeface = resolveTypeface(view.context, fontFamily, weight)
        view.setFontVariationSettings("'wght' $weight")
    }

    internal fun isSupportedFontFileName(displayName: String): Boolean {
        val lower = displayName.lowercase()
        return lower.endsWith(".ttf") || lower.endsWith(".otf")
    }

    private fun weightedSystemTypeface(context: Context, base: Typeface, weight: Int): Typeface {
        return TypefaceCompat.create(context, base, weight, false)
    }

    private fun buildCustomTypeface(context: Context, file: File, weight: Int): Typeface? {
        val hasWeightAxis = hasWeightVariationAxis(file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return buildCustomTypefaceWithSystemFallback(file, weight, hasWeightAxis)
        }
        return runCatching {
            val builder = Typeface.Builder(file)
                .setFallback("sans-serif")
            if (hasWeightAxis) {
                builder
                    .setFontVariationSettings("'wght' $weight")
                    .setWeight(weight)
                    .build()
            } else {
                val base = builder.build()
                TypefaceCompat.create(context, base, weight, false)
            }
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildCustomTypefaceWithSystemFallback(
        file: File,
        weight: Int,
        hasWeightAxis: Boolean
    ): Typeface? {
        return runCatching {
            val fontBuilder = Font.Builder(file)
            val font = if (hasWeightAxis) {
                fontBuilder
                    .setFontVariationSettings("'wght' $weight")
                    .setWeight(weight)
                    .build()
            } else {
                fontBuilder.build()
            }
            val family = FontFamily.Builder(font).build()
            Typeface.CustomFallbackBuilder(family)
                .setSystemFallback("sans-serif")
                .setStyle(FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT))
                .build()
        }.getOrNull()
    }

    internal fun hasWeightVariationAxis(file: File): Boolean {
        return runCatching {
            RandomAccessFile(file, "r").use { font ->
                if (font.length() < SFNT_HEADER_SIZE) return@use false
                font.seek(4L)
                val tableCount = font.readUnsignedShort()
                if (tableCount !in 1..MAX_SFNT_TABLES) return@use false

                val directoryEnd = SFNT_HEADER_SIZE + tableCount * SFNT_TABLE_RECORD_SIZE
                if (directoryEnd > font.length()) return@use false

                repeat(tableCount) { index ->
                    font.seek(SFNT_HEADER_SIZE + index * SFNT_TABLE_RECORD_SIZE)
                    if (font.readInt() == FVAR_TAG) {
                        font.skipBytes(4)
                        val offset = font.readUnsignedIntCompat()
                        val length = font.readUnsignedIntCompat()
                        return@use fvarContainsWeightAxis(font, offset, length)
                    }
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun fvarContainsWeightAxis(font: RandomAccessFile, offset: Long, length: Long): Boolean {
        if (length < FVAR_HEADER_SIZE || offset > font.length() - length) return false
        font.seek(offset + 4L)
        val axesArrayOffset = font.readUnsignedShort()
        font.skipBytes(2)
        val axisCount = font.readUnsignedShort()
        val axisSize = font.readUnsignedShort()
        if (axisCount == 0 || axisSize < FVAR_AXIS_RECORD_MIN_SIZE) return false

        val axesStart = offset + axesArrayOffset
        val axesEnd = axesStart + axisCount.toLong() * axisSize
        if (axesStart < offset || axesEnd > offset + length) return false

        repeat(axisCount) { index ->
            font.seek(axesStart + index.toLong() * axisSize)
            if (font.readInt() == WEIGHT_AXIS_TAG) return true
        }
        return false
    }

    private fun RandomAccessFile.readUnsignedIntCompat(): Long {
        return readInt().toLong() and 0xffff_ffffL
    }

    private fun isValidFontFile(file: File): Boolean {
        if (!hasSupportedFontSignature(file)) return false
        return runCatching { Typeface.Builder(file).build() != null }.getOrDefault(false)
    }

    private fun hasSupportedFontSignature(file: File): Boolean {
        val signature = runCatching {
            file.inputStream().use { input ->
                ByteArray(4).also { bytes ->
                    if (input.read(bytes) != bytes.size) return false
                }
            }
        }.getOrNull() ?: return false
        return signature.contentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00)) ||
            signature.contentEquals("OTTO".toByteArray(Charsets.US_ASCII)) ||
            signature.contentEquals("true".toByteArray(Charsets.US_ASCII)) ||
            signature.contentEquals("typ1".toByteArray(Charsets.US_ASCII))
    }

    private fun replaceCustomFontAtomically(source: File, destination: File): Boolean {
        val atomicFile = AtomicFile(destination)
        val output = runCatching { atomicFile.startWrite() }.getOrNull() ?: return false
        return try {
            source.inputStream().use { it.copyTo(output) }
            atomicFile.finishWrite(output)
            true
        } catch (_: Exception) {
            atomicFile.failWrite(output)
            false
        }
    }

    private fun documentDisplayName(context: Context, uri: Uri): String {
        val queriedName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return sanitizeDisplayName(queriedName ?: uri.lastPathSegment.orEmpty())
    }

    private fun documentSize(context: Context, uri: Uri): Long? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun sanitizeDisplayName(value: String): String {
        return value
            .substringAfterLast('/')
            .filterNot { it.isISOControl() }
            .trim()
            .take(120)
    }

    private fun fontDirectory(context: Context): File {
        return File(context.filesDir, FONT_DIRECTORY)
    }

    private fun customFontFile(context: Context): File {
        return File(fontDirectory(context), CUSTOM_FONT_FILE)
    }
}
