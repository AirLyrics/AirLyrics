package com.andsi.airlyrics.testutil

import android.content.Context
import java.io.File
import java.nio.ByteBuffer

/** Creates a TTC from existing test fonts, sharing tables when a resource is used twice. */
internal fun fontCollectionFixture(
    context: Context,
    file: File,
    vararg fontResources: Int,
    version: Int = 2
): File {
    val fonts = fontResources.distinct().associateWith { id ->
        context.resources.openRawResource(id).use { it.readBytes() }
    }
    val headerSize = 12 + fontResources.size * 4 + if (version == 2) 12 else 0
    val collection = ByteBuffer.allocate(headerSize + fontResources.sumOf { id ->
        alignToFour(fonts.getValue(id).size)
    })
    collection.putInt(0x74746366) // ttcf
    collection.putInt(version shl 16)
    collection.putInt(fontResources.size)
    collection.position(headerSize)
    val offsets = mutableMapOf<Int, Int>()
    fontResources.forEachIndexed { index, id ->
        val offset = collection.position()
        collection.putInt(12 + index * 4, offset)
        val font = fonts.getValue(id)
        val tableCount = ByteBuffer.wrap(font).getShort(4).toInt() and 0xffff
        val existingOffset = offsets[id]
        if (existingOffset != null) {
            // The directory still points to the tables of the first occurrence.
            collection.put(collection.array(), existingOffset, 12 + tableCount * 16)
        } else {
            offsets[id] = offset
            val member = ByteBuffer.wrap(font.copyOf())
            repeat(tableCount) { table ->
                val record = 12 + table * 16
                val tableOffset = member.getInt(record + 8)
                member.putInt(record + 8, tableOffset + offset)
                if (member.getInt(record) == 0x68656164) {
                    member.putInt(tableOffset + 8, 0) // No per-file checksum adjustment in a TTC.
                }
            }
            collection.put(member.array())
        }
        collection.position(alignToFour(collection.position()))
    }
    file.writeBytes(collection.array().copyOf(collection.position()))
    return file
}

private fun alignToFour(size: Int) = (size + 3) and -4
