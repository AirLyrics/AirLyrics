package com.andsi.airlyrics.app.interaction

import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONObject

/** Ordered writes ensure that a delayed save cannot recreate a discarded draft. */
internal class LyricsDraftStore(private val directory: File) {
    fun write(id: String, text: String): Future<*> = io.submit {
        directory.mkdirs()
        val file = atomic(id)
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("version", 1).put("text", text).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun read(id: String): Future<String?> = io.submit<String?> {
        runCatching {
            val json = JSONObject(atomic(id).readFully().toString(Charsets.UTF_8))
            if (json.getInt("version") == 1) json.getString("text") else null
        }.getOrNull()
    }

    fun delete(id: String): Future<*> = io.submit { atomic(id).delete() }
    fun prune(keep: String?): Future<*> = io.submit {
        directory.listFiles()?.filter { it.name.substringBefore('.') != keep }?.forEach(File::delete)
    }

    private fun atomic(id: String): AtomicFile {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(directory, "$id.json"))
    }

    companion object {
        private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "AirLyrics-Drafts").apply { isDaemon = true } }
    }
}
