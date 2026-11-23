package com.andsi.airlyrics.settings.store

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

internal object FloatingFontFile {
    const val MAX_FACES = 64
    private const val MAX_TABLES = 512
    private const val TTC = 0x74746366
    private const val HEAD = 0x68656164
    private const val MAXP = 0x6d617870
    private const val CMAP = 0x636d6170
    private const val NAME = 0x6e616d65
    private const val FVAR = 0x66766172
    private const val WGHT = 0x77676874

    data class Face(val index: Int, val name: String?)
    data class Inspection(val collection: Boolean, val faces: List<Face>)
    private data class Header(val collection: Boolean, val offsets: List<Long>, val end: Long)
    private data class Table(val offset: Long, val length: Long)
    private data class Directory(val offset: Long, val end: Long, val tables: Map<Int, Table>)

    fun collectionFaceCount(file: File): Long? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 12L || input.readInt() != TTC) return@use null
            input.skipBytes(4)
            input.uint()
        }
    }.getOrNull()

    fun inspect(file: File): Inspection? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val header = header(input) ?: return@use null
            val directories = header.offsets.map { offset ->
                directory(input, offset, header.end) ?: return@use null
            }
            for (directory in directories) {
                val tables = directory.tables
                if ((tables[HEAD]?.length ?: 0L) < 54L ||
                    (tables[MAXP]?.length ?: 0L) < 6L ||
                    (tables[CMAP]?.length ?: 0L) < 4L) return@use null
                if (tables.values.any { table ->
                    table.length > 0L && directories.any { other ->
                        table.offset < other.end && table.offset + table.length > other.offset
                    }
                }) return@use null
            }
            Inspection(header.collection, directories.mapIndexed { index, directory ->
                Face(index, directory.tables[NAME]?.let { faceName(input, it) })
            })
        }
    }.getOrNull()

    fun hasWeightAxis(file: File, index: Int): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val header = header(input) ?: return@use false
            val offset = header.offsets.getOrNull(index) ?: return@use false
            val table = directory(input, offset, header.end)?.tables?.get(FVAR) ?: return@use false
            if (table.length < 16L) return@use false
            input.seek(table.offset + 4L)
            val axesOffset = input.readUnsignedShort()
            input.skipBytes(2)
            val count = input.readUnsignedShort()
            val size = input.readUnsignedShort()
            if (count !in 1..64 || size < 20 || axesOffset < 16) return@use false
            val start = table.offset + axesOffset
            if (start + count.toLong() * size > table.offset + table.length) return@use false
            (0 until count).any { axis ->
                input.seek(start + axis.toLong() * size)
                input.readInt() == WGHT
            }
        }
    }.getOrDefault(false)

    fun containsFace(file: File, index: Int): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val header = header(input) ?: return@use false
            val offset = header.offsets.getOrNull(index) ?: return@use false
            val tables = directory(input, offset, header.end)?.tables ?: return@use false
            tables.keys.containsAll(listOf(HEAD, MAXP, CMAP))
        }
    }.getOrDefault(false)

    private fun header(input: RandomAccessFile): Header? {
        if (input.length() < 12L) return null
        input.seek(0L)
        val signature = input.readInt()
        if (signature != TTC) {
            return if (supported(signature)) Header(false, listOf(0L), 0L) else null
        }
        val version = input.readInt()
        if (version != 0x00010000 && version != 0x00020000) return null
        val count = input.uint()
        if (count !in 1L..MAX_FACES.toLong()) return null
        val end = 12L + count * 4L + if (version == 0x00020000) 12L else 0L
        if (end > input.length()) return null
        val offsets = List(count.toInt()) { input.uint() }
        if (offsets.any { it < end || it > input.length() - 12L }) return null
        if (version == 0x00020000) {
            val tag = input.readInt()
            val length = input.uint()
            val offset = input.uint()
            if (length > 0L && (tag != 0x44534947 || offset < end ||
                    offset > input.length() - length)) return null
        }
        return Header(true, offsets, end)
    }

    private fun directory(input: RandomAccessFile, offset: Long, headerEnd: Long): Directory? {
        if (offset > input.length() - 12L) return null
        input.seek(offset)
        if (!supported(input.readInt())) return null
        val count = input.readUnsignedShort()
        if (count !in 1..MAX_TABLES) return null
        val end = offset + 12L + count * 16L
        if (end > input.length()) return null
        val tables = linkedMapOf<Int, Table>()
        repeat(count) { index ->
            input.seek(offset + 12L + index * 16L)
            val tag = input.readInt()
            input.skipBytes(4)
            val start = input.uint()
            val length = input.uint()
            if (start > input.length() - length ||
                (length > 0L && start < headerEnd) || tag in tables) return null
            tables[tag] = Table(start, length)
        }
        return Directory(offset, end, tables)
    }

    private fun faceName(input: RandomAccessFile, table: Table): String? = runCatching {
        if (table.length < 6L) return@runCatching null
        input.seek(table.offset)
        val format = input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val strings = input.readUnsignedShort()
        if (format !in 0..1 || count !in 1..512 ||
            6L + count * 12L > table.length || strings < 6L + count * 12L) return@runCatching null
        val names = mutableMapOf<Int, Pair<Int, String>>()
        repeat(count) { index ->
            input.seek(table.offset + 6L + index * 12L)
            val platform = input.readUnsignedShort()
            val encoding = input.readUnsignedShort()
            val language = input.readUnsignedShort()
            val id = input.readUnsignedShort()
            val length = input.readUnsignedShort()
            val offset = input.readUnsignedShort()
            if (id !in listOf(1, 2, 4, 16, 17) || length !in 1..1024 ||
                strings.toLong() + offset + length > table.length) return@repeat
            val charset = when {
                platform == 0 || platform == 3 -> {
                    if (length % 2 != 0) return@repeat
                    Charsets.UTF_16BE
                }
                platform == 1 && encoding == 0 -> runCatching { Charset.forName("x-MacRoman") }
                    .getOrDefault(Charsets.US_ASCII)
                else -> return@repeat
            }
            val score = (if (language == 0x0409) 100 else if (language == 0) 70 else 30) +
                (if (platform == 3) 20 else if (platform == 0) 15 else 5)
            if ((names[id]?.first ?: -1) >= score) return@repeat
            input.seek(table.offset + strings + offset)
            val bytes = ByteArray(length)
            input.readFully(bytes)
            val value = String(bytes, charset).filterNot { it.isISOControl() }.trim().take(120)
            if (value.isNotEmpty()) names[id] = score to value
        }
        val family = names[16]?.second ?: names[1]?.second
        val style = names[17]?.second ?: names[2]?.second
        val full = names[4]?.second ?: family ?: return@runCatching null
        if (!style.isNullOrBlank() && !full.contains(style, ignoreCase = true)) "$full $style" else full
    }.getOrNull()

    private fun supported(signature: Int): Boolean = signature in
        listOf(0x00010000, 0x4f54544f, 0x74727565, 0x74797031)

    private fun RandomAccessFile.uint(): Long = readInt().toLong() and 0xffff_ffffL
}
