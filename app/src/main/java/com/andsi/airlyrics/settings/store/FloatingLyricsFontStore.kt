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
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.TypefaceCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.FloatingFont
import com.andsi.airlyrics.core.model.FloatingFontCollection
import com.andsi.airlyrics.core.model.FloatingFontSource
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.FloatingLyricsFontWeight
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object FloatingLyricsFontStore {
    private const val PREFS_NAME = "floating_lyrics_font"
    private const val KEY_CUSTOM_FONT_NAME = "custom_font_name"
    private const val FONT_DIRECTORY = "floating_fonts"
    private const val CUSTOM_FONT_FILE = "custom_font"
    private const val CATALOG_FILE = "catalog.json"
    private const val MAX_FONT_BYTES = 32L * 1024L * 1024L
    internal val mutationLock = Any()
    private val typefaceCache = LruCache<String, Typeface>(32)
    private val version = MutableStateFlow(0L)
    val changes = version.asStateFlow()
    private val downloads = mutableSetOf<String>()
    private val epochs = mutableMapOf<String, Long>()

    sealed class ImportResult {
        data class Success(val displayName: String, val id: String = "custom") : ImportResult()
        object UnsupportedFormat : ImportResult()
        object TooLarge : ImportResult()
        object TooManyFaces : ImportResult()
        object InvalidFont : ImportResult()
        object ReadFailed : ImportResult()
    }

    private data class Imported(
        val id: String, val name: String, val fileName: String,
        val ttcIndex: Int = 0, val collectionId: String? = null, val collectionName: String? = null
    )
    private data class Catalog(val serifRemoved: Boolean = false, val imports: List<Imported> = emptyList())
    private data class CachedCatalog(val modified: Long, val length: Long, val catalog: Catalog)
    private val catalogCache = mutableMapOf<String, CachedCatalog>()

    internal val remoteFonts = listOf(
        FloatingFontDownloadSpec(
            "swei_gothic_sc",
            "https://raw.githubusercontent.com/max32002/swei-gothic/c363a1d845aa782a71b7a068b5db0aff3f83ee86/CJK%20SC/SweiGothicCJKsc-Regular.ttf",
            16_312_184L,
            "c2a7116ac53c3921f8656e8ede999d41802e2e0406a184a1924f0ef4f47279fc"
        ),
        FloatingFontDownloadSpec(
            "wenkai",
            "https://github.com/lxgw/LxgwWenKai/releases/download/v1.530/LXGWWenKai-Regular.ttf",
            25_928_940L,
            "2d73eea94ad808999d90c0000c239a84a33b09d4bc6ab6c5b8bbf1eda8a7fc8b"
        )
    )

    fun fonts(context: Context): List<FloatingFont> = synchronized(mutationLock) {
        val catalog = readCatalog(context)
        buildList {
            add(FloatingFont("system_default", FloatingLyricsFontFamily.SYSTEM_DEFAULT, null,
                FloatingFontSource.SYSTEM, true))
            if (!catalog.serifRemoved) add(FloatingFont("serif", FloatingLyricsFontFamily.SERIF, null,
                FloatingFontSource.PRESET, true))
            listOf(FloatingLyricsFontFamily.ROUNDED, FloatingLyricsFontFamily.HANDWRITING).forEach { family ->
                val id = family.key
                add(FloatingFont(id, family, null, FloatingFontSource.DOWNLOAD,
                    fontFile(context, id).isUsableFile(),
                    sizeBytes = remoteFonts.first { it.id == id }.bytes))
            }
            val fileSizes = mutableMapOf<String, Long>()
            catalog.imports.forEach { imported ->
                val bytes = fileSizes.getOrPut(imported.fileName) {
                    val file = File(fontDirectory(context), imported.fileName)
                    if (file.isFile) file.length() else 0L
                }
                if (bytes > 0L) add(FloatingFont(
                    imported.id, FloatingLyricsFontFamily.CUSTOM, imported.name,
                    FloatingFontSource.IMPORTED, available = true,
                    sizeBytes = bytes.takeIf { imported.collectionId == null },
                    collectionId = imported.collectionId
                ))
            }
        }
    }

    internal fun removeRetiredFontFiles(context: Context) = synchronized(mutationLock) {
        var removed = false
        for (id in listOf("casual", "rounded", "cursive")) {
            if (fontFile(context, id).delete()) removed = true
        }
        if (removed) invalidateTypefaces()
    }

    fun scheduleRetiredFontCleanup(context: Context) {
        val app = context.applicationContext
        Thread({
            runCatching { removeRetiredFontFiles(app) }
        }, "AirLyrics-FontCleanup").apply { isDaemon = true }.start()
    }

    fun font(context: Context, id: String): FloatingFont? = fonts(context).firstOrNull { it.id == id }

    fun collections(context: Context): List<FloatingFontCollection> = synchronized(mutationLock) {
        readCatalog(context).imports.filter { it.collectionId != null }
            .distinctBy { it.collectionId }.mapNotNull { entry ->
                val file = File(fontDirectory(context), entry.fileName)
                if (!file.isUsableFile()) null else FloatingFontCollection(
                    requireNotNull(entry.collectionId), requireNotNull(entry.collectionName), file.length()
                )
            }
    }

    fun isAvailable(context: Context, id: String): Boolean = font(context, id)?.available == true

    fun normalizeStyle(context: Context, style: FloatingLyricsStyle): FloatingLyricsStyle =
        synchronized(mutationLock) {
            val id = if (style.fontFamily == FloatingLyricsFontFamily.CUSTOM && style.customFontId == null) {
                // Only the old single-file import had no explicit ID. Never substitute a newer import.
                "custom"
            } else style.fontId
            val entry = font(context, id)
            if (entry?.available != true) style.copy(
                fontFamily = FloatingLyricsFontFamily.SYSTEM_DEFAULT, customFontId = null
            ) else style.copy(
                fontFamily = entry.family,
                customFontId = entry.id.takeIf { entry.source == FloatingFontSource.IMPORTED }
            )
        }

    fun selectFont(context: Context, id: String): Boolean = synchronized(mutationLock) {
        val entry = font(context, id)?.takeIf { it.available } ?: return@synchronized false
        val previousStyle = FloatingLyricsStyleStore.getStyle(context)
        if (entry.source == FloatingFontSource.IMPORTED) {
            val record = readCatalog(context).imports.firstOrNull { it.id == id } ?: return@synchronized false
            val file = File(fontDirectory(context), record.fileName)
            val weight = previousStyle.fontWeight
            val key = typefaceKey(context, entry.id, file, weight, record.ttcIndex)
            val face = typefaceCache.get(key) ?: buildCustomTypeface(context, file, weight, record.ttcIndex)
                ?: return@synchronized false
            typefaceCache.put(key, face)
        }
        FloatingLyricsStyleStore.setStyle(context, previousStyle.copy(
            fontFamily = entry.family,
            customFontId = entry.id.takeIf { entry.source == FloatingFontSource.IMPORTED }
        ))
        true
    }

    fun removeFont(context: Context, id: String): Boolean = synchronized(mutationLock) {
        val entry = font(context, id) ?: return@synchronized false
        if (entry.source == FloatingFontSource.SYSTEM) return@synchronized false
        val token = downloadKey(context, id)
        epochs[token] = (epochs[token] ?: 0L) + 1L
        if (!entry.available) return@synchronized false
        val previous = readCatalog(context)
        val next = when (entry.source) {
            FloatingFontSource.PRESET -> previous.copy(serifRemoved = true)
            FloatingFontSource.IMPORTED -> previous.copy(imports = previous.imports.filterNot { it.id == id })
            else -> previous
        }
        val file = storedFile(context, entry)?.takeUnless { candidate ->
            entry.source == FloatingFontSource.IMPORTED && next.imports.any { it.fileName == candidate.name }
        }
        commitRemoval(context, previous, next, file, setOf(id))
        true
    }

    fun removeCollection(context: Context, id: String): Boolean = synchronized(mutationLock) {
        val previous = readCatalog(context)
        val members = previous.imports.filter { it.collectionId == id }
        if (members.isEmpty()) return@synchronized false
        val next = previous.copy(imports = previous.imports.filterNot { it.collectionId == id })
        val file = File(fontDirectory(context), members.first().fileName)
        commitRemoval(context, previous, next, file, members.map { it.id }.toSet())
        true
    }

    private fun commitRemoval(
        context: Context, previous: Catalog, next: Catalog, file: File?, removedIds: Set<String>
    ) {
        val previousStyle = FloatingLyricsStyleStore.getStyle(context)
        val tombstone = file?.takeIf { it.exists() }?.let { source ->
            File(fontDirectory(context), "removing_${UUID.randomUUID()}").also {
                if (!source.renameTo(it)) throw IOException("Could not remove font file")
            }
        }
        try {
            writeCatalog(context, next)
            if (tombstone != null && !tombstone.delete()) throw IOException("Could not delete font file")
        } catch (failure: Exception) {
            runCatching { writeCatalog(context, previous) }
            tombstone?.renameTo(file)
            throw failure
        }
        invalidateTypefaces()
        if (previousStyle.fontId in removedIds) FloatingLyricsStyleStore.setStyle(context, previousStyle.copy(
            fontFamily = FloatingLyricsFontFamily.SYSTEM_DEFAULT, customFontId = null
        ))
    }

    fun importFont(context: Context, uri: Uri): ImportResult {
        val displayName = documentDisplayName(context, uri)
        if (!isSupportedFontFileName(displayName)) return ImportResult.UnsupportedFormat
        if (documentSize(context, uri)?.let { it > MAX_FONT_BYTES } == true) return ImportResult.TooLarge
        val id = "import_${UUID.randomUUID().toString().replace("-", "")}"
        val temporary = try { newTemporaryFile(context, id) } catch (_: IOException) {
            return ImportResult.ReadFailed
        }
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return ImportResult.ReadFailed
            input.use { source ->
                temporary.outputStream().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > MAX_FONT_BYTES) return ImportResult.TooLarge
                        target.write(buffer, 0, count)
                    }
                    target.fd.sync()
                }
            }
            if ((FloatingFontFile.collectionFaceCount(temporary) ?: 0L) > FloatingFontFile.MAX_FACES) {
                return ImportResult.TooManyFaces
            }
            val inspection = FloatingFontFile.inspect(temporary) ?: return ImportResult.InvalidFont
            val faces = inspection.faces.filter { face ->
                buildCustomTypeface(context, temporary, FloatingLyricsFontWeight.DEFAULT, face.index) != null
            }
            if (faces.isEmpty()) return ImportResult.InvalidFont
            val collectionId = if (inspection.collection) id.replaceFirst("import_", "collection_") else null
            val names = faces.map { it.name ?: displayName }
            val records = faces.mapIndexed { position, face ->
                val name = if (inspection.collection) {
                    val base = names[position]
                    if (names.count { it == base } > 1) "$base #${face.index + 1}" else base
                } else displayName
                Imported(
                    if (position == 0) id else "import_${UUID.randomUUID().toString().replace("-", "")}",
                    name, "${collectionId ?: id}.font", face.index, collectionId,
                    displayName.takeIf { inspection.collection }
                )
            }
            synchronized(mutationLock) {
                val catalog = readCatalog(context)
                val destination = File(fontDirectory(context), records.first().fileName)
                if (destination.exists() || !temporary.renameTo(destination)) {
                    throw IOException("Could not store font")
                }
                try {
                    writeCatalog(context, catalog.copy(imports = catalog.imports + records))
                } catch (failure: Exception) {
                    destination.delete()
                    throw failure
                }
                invalidateTypefaces()
            }
            ImportResult.Success(displayName, id)
        } catch (_: Exception) {
            ImportResult.ReadFailed
        } finally {
            temporary.delete()
        }
    }

    fun customFontDisplayName(context: Context, id: String? = null): String? = synchronized(mutationLock) {
        (id ?: preferredCustomId(context))?.let { font(context, it)?.displayName }
    }

    internal fun preferredCustomId(context: Context): String? {
        val available = fonts(context).filter { it.source == FloatingFontSource.IMPORTED }
        val saved = context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE)
            .getString("custom_font_id", null)
        return available.firstOrNull { it.id == saved }?.id
            ?: available.firstOrNull { it.id == "custom" }?.id
            ?: available.lastOrNull()?.id
    }

    suspend fun downloadFont(context: Context, id: String, onProgress: (Int) -> Unit) {
        downloadFont(context, id, onProgress, FloatingFontDownloader())
    }

    internal suspend fun downloadFont(
        context: Context,
        id: String,
        onProgress: (Int) -> Unit,
        downloader: FloatingFontDownloader,
        spec: FloatingFontDownloadSpec = remoteFonts.firstOrNull { it.id == id }
            ?: throw IOException("Unknown downloadable font")
    ) {
        val key = downloadKey(context, id)
        val epoch = synchronized(mutationLock) {
            if (!downloads.add(key)) throw IOException("Font is already downloading")
            epochs[key] ?: 0L
        }
        var temporary: File? = null
        try {
            val pending = withContext(Dispatchers.IO) { newTemporaryFile(context, id) }
            temporary = pending
            onProgress(0)
            downloader.fetch(spec, pending, onProgress)
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                synchronized(mutationLock) {
                    if ((epochs[key] ?: 0L) != epoch) throw IOException("Font was removed during download")
                    if (!installDownloadedFont(context, id, pending)) throw IOException("Invalid downloaded font")
                }
            }
            currentCoroutineContext().ensureActive()
            onProgress(100)
        } finally {
            temporary?.delete()
            synchronized(mutationLock) { downloads.remove(key) }
        }
    }

    /** Called only after transport size/hash checks; also useful for local test font fixtures. */
    internal fun installDownloadedFont(context: Context, id: String, temporary: File): Boolean =
        synchronized(mutationLock) {
            if (remoteFonts.none { it.id == id } || !isValidFontFile(temporary) ||
                temporary.length() > MAX_FONT_BYTES ||
                buildCustomTypeface(context, temporary, FloatingLyricsFontWeight.DEFAULT) == null) {
                return@synchronized false
            }
            if (!replaceCustomFontAtomically(temporary, fontFile(context, id))) return@synchronized false
            invalidateTypefaces()
            true
        }

    fun resolvePreviewTypeface(context: Context, id: String, fontWeight: Int): Typeface {
        val entry = font(context, id) ?: return weightedSystemTypeface(
            context, Typeface.DEFAULT, FloatingLyricsFontWeight.normalize(fontWeight)
        )
        if (entry.source == FloatingFontSource.DOWNLOAD && !entry.available) {
            val resource = when (entry.family) {
                FloatingLyricsFontFamily.ROUNDED -> R.font.font_preview_swei_gothic_sc
                FloatingLyricsFontFamily.HANDWRITING -> R.font.font_preview_wenkai
                else -> error("Not a downloadable preview")
            }
            return weightedSystemTypeface(context, requireNotNull(ResourcesCompat.getFont(context, resource)),
                FloatingLyricsFontWeight.normalize(fontWeight))
        }
        return resolveTypeface(context, entry.family, fontWeight,
            entry.id.takeIf { entry.source == FloatingFontSource.IMPORTED })
    }

    fun resolveTypeface(
        context: Context,
        fontFamily: FloatingLyricsFontFamily,
        fontWeight: Int,
        customFontId: String? = null
    ): Typeface = synchronized(mutationLock) {
        val weight = FloatingLyricsFontWeight.normalize(fontWeight)
        val id = if (fontFamily == FloatingLyricsFontFamily.CUSTOM) {
            customFontId ?: preferredCustomId(context)
        } else fontFamily.key
        val entry = id?.let { font(context, it) }?.takeIf { it.available }
        val file = entry?.let { storedFile(context, it) }
        val ttcIndex = entry?.takeIf { it.source == FloatingFontSource.IMPORTED }?.let { font ->
            readCatalog(context).imports.firstOrNull { it.id == font.id }?.ttcIndex
        } ?: 0
        val cacheKey = typefaceKey(context, entry?.id ?: "system_default", file, weight, ttcIndex)
        typefaceCache.get(cacheKey)?.let { return@synchronized it }
        val face = when {
            file != null -> buildCustomTypeface(context, file, weight, ttcIndex)
                ?: weightedSystemTypeface(context, Typeface.DEFAULT, weight)
            entry?.family == FloatingLyricsFontFamily.SERIF ->
                weightedSystemTypeface(context, Typeface.SERIF, weight)
            else -> weightedSystemTypeface(context, Typeface.DEFAULT, weight)
        }
        typefaceCache.put(cacheKey, face)
        face
    }

    fun applyTypeface(
        view: TextView,
        fontFamily: FloatingLyricsFontFamily,
        fontWeight: Int,
        customFontId: String? = null
    ) {
        val weight = FloatingLyricsFontWeight.normalize(fontWeight)
        view.typeface = resolveTypeface(view.context, fontFamily, weight, customFontId)
        view.fontVariationSettings = "'wght' $weight"
    }

    private fun storedFile(context: Context, font: FloatingFont): File? = when (font.source) {
        FloatingFontSource.DOWNLOAD -> fontFile(context, font.id)
        FloatingFontSource.IMPORTED -> readCatalog(context).imports.firstOrNull { it.id == font.id }
            ?.let { File(fontDirectory(context), it.fileName) }
        else -> null
    }

    private fun typefaceKey(context: Context, id: String, file: File?, weight: Int, ttcIndex: Int) =
        "${context.filesDir.absolutePath}:$id:$weight:$ttcIndex:${file?.lastModified()}:${file?.length()}"

    private fun invalidateTypefaces() {
        typefaceCache.evictAll()
        version.value += 1L
    }

    private fun readCatalog(context: Context): Catalog {
        val directory = fontDirectory(context)
        val file = File(directory, CATALOG_FILE)
        val atomic = AtomicFile(file)
        val backup = File(directory, "$CATALOG_FILE.bak")
        val key = directory.absolutePath
        val cached = catalogCache[key]
        if (file.exists() && !backup.exists() && cached?.modified == file.lastModified() &&
            cached.length == file.length()) return cached.catalog
        catalogCache.remove(key)
        if (!file.exists() && !backup.exists()) {
            val legacy = File(directory, CUSTOM_FONT_FILE)
            if (!legacy.isUsableFile()) return Catalog()
            val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_FONT_NAME, null)?.takeIf { it.isNotBlank() } ?: "Custom font"
            return Catalog(imports = listOf(Imported("custom", name, CUSTOM_FONT_FILE))).also {
                writeCatalog(context, it)
            }
        }
        val catalog = runCatching {
            val json = atomic.openRead().bufferedReader().use { JSONObject(it.readText()) }
            val schema = json.optInt("version", 1)
            if (schema !in 1..2) throw IOException("Unsupported font catalogue")
            val array = json.optJSONArray("imports") ?: JSONArray()
            val seenMembers = mutableSetOf<Pair<String, Int>>()
            val collectionNames = mutableMapOf<String, String>()
            val imports = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name")
                    val path = item.optString("file")
                    val collection = if (schema >= 2) item.optString("collection").takeIf { it.isNotEmpty() } else null
                    val collectionName = item.optString("collectionName").takeIf { it.isNotBlank() }
                    val ttcIndex = if (schema >= 2) item.optInt("ttcIndex", 0) else 0
                    val expectedFile = if (id == "custom") CUSTOM_FONT_FILE else "$id.font"
                    if ((id != "custom" && !id.matches(Regex("import_[a-f0-9]{32}"))) || name.isBlank()) continue
                    if (collection == null) {
                        if (path != expectedFile || ttcIndex != 0) continue
                    } else {
                        if (id == "custom" || !collection.matches(Regex("collection_[a-f0-9]{32}")) ||
                            path != "$collection.font" || collectionName == null ||
                            ttcIndex !in 0 until FloatingFontFile.MAX_FACES) continue
                        if (collectionNames.getOrPut(collection) { collectionName } != collectionName ||
                            !seenMembers.add(collection to ttcIndex)) continue
                    }
                    add(Imported(id, name, path, ttcIndex, collection, collectionName))
                }
            }.distinctBy { it.id }
            Catalog(json.optBoolean("serifRemoved"), imports)
        }.getOrDefault(Catalog(serifRemoved = true))
        catalogCache[key] = CachedCatalog(file.lastModified(), file.length(), catalog)
        return catalog
    }

    private fun writeCatalog(context: Context, catalog: Catalog) {
        val directory = fontDirectory(context)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create font directory")
        val atomic = AtomicFile(File(directory, CATALOG_FILE))
        val json = JSONObject().put("version", 2).put("serifRemoved", catalog.serifRemoved)
            .put("imports", JSONArray().apply {
                catalog.imports.forEach { entry ->
                    put(JSONObject().put("id", entry.id).put("name", entry.name).put("file", entry.fileName)
                        .put("ttcIndex", entry.ttcIndex).apply {
                            if (entry.collectionId != null) {
                                put("collection", entry.collectionId)
                                put("collectionName", entry.collectionName)
                            }
                        })
                }
            }).toString().toByteArray(Charsets.UTF_8)
        val output = atomic.startWrite()
        try {
            output.write(json)
            atomic.finishWrite(output)
            val file = File(directory, CATALOG_FILE)
            catalogCache[directory.absolutePath] = CachedCatalog(file.lastModified(), file.length(), catalog)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            catalogCache.remove(directory.absolutePath)
            throw failure
        }
    }

    private fun newTemporaryFile(context: Context, id: String): File {
        val directory = fontDirectory(context)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create font directory")
        return File.createTempFile("pending_${id}_", ".font", directory)
    }

    private fun fontDirectory(context: Context) = File(context.filesDir, FONT_DIRECTORY)
    private fun fontFile(context: Context, id: String) = File(fontDirectory(context), "$id.font")
    private fun File.isUsableFile() = isFile && length() > 0L
    private fun downloadKey(context: Context, id: String) = "${context.filesDir.absolutePath}:$id"

    internal fun isSupportedFontFileName(displayName: String): Boolean {
        val lower = displayName.lowercase()
        return lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")
    }

    private fun weightedSystemTypeface(context: Context, base: Typeface, weight: Int): Typeface {
        return TypefaceCompat.create(context, base, weight, false)
    }

    private fun buildCustomTypeface(context: Context, file: File, weight: Int, ttcIndex: Int = 0): Typeface? {
        if (!FloatingFontFile.containsFace(file, ttcIndex)) return null
        val hasWeightAxis = FloatingFontFile.hasWeightAxis(file, ttcIndex)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return buildCustomTypefaceWithSystemFallback(file, weight, hasWeightAxis, ttcIndex)
        }
        return runCatching {
            val builder = Typeface.Builder(file).setTtcIndex(ttcIndex)
            if (hasWeightAxis) {
                builder
                    .setFontVariationSettings("'wght' $weight")
                    .setWeight(weight)
                    .build()
            } else {
                val base = builder.build()
                TypefaceCompat.create(context, base, weight, base.isItalic)
            }
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildCustomTypefaceWithSystemFallback(
        file: File,
        weight: Int,
        hasWeightAxis: Boolean,
        ttcIndex: Int
    ): Typeface? {
        return runCatching {
            val fontBuilder = Font.Builder(file).setTtcIndex(ttcIndex)
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
                .setStyle(FontStyle(weight, font.style.slant))
                .build()
        }.getOrNull()
    }

    internal fun hasWeightVariationAxis(file: File): Boolean = FloatingFontFile.hasWeightAxis(file, 0)

    private fun isValidFontFile(file: File): Boolean {
        val inspection = FloatingFontFile.inspect(file) ?: return false
        return !inspection.collection && runCatching { Typeface.Builder(file).build() != null }.getOrDefault(false)
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


}
