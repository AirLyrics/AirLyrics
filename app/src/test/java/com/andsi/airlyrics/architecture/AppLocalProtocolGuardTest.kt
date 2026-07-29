package com.andsi.airlyrics.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLocalProtocolGuardTest {
    @Test
    fun appLocalProtocolActions_areOwnedByProtocolObjects() {
        val violations = mainSourceFiles()
            .filterNot { it.relativePath in PROTOCOL_OWNER_FILES }
            .flatMap(::findAppLocalActionStrings)
            .toList()

        assertTrue(
            "App-local protocol action strings must be owned by protocol objects:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty()
        )
    }

    private fun mainSourceFiles(): Sequence<SourceFile> {
        val root = projectRoot()
        val sourceDir = File(root, "app/src/main/java")
        return sourceDir.walkTopDown()
            .filter { it.isFile && it.extension in SOURCE_EXTENSIONS }
            .map { file ->
                SourceFile(
                    file = file,
                    relativePath = file.relativeTo(root).invariantSeparatorsPath
                )
            }
    }

    private fun findAppLocalActionStrings(sourceFile: SourceFile): List<String> {
        val text = sourceFile.file.readText()
        return APP_LOCAL_ACTION_STRING.findAll(text)
            .map { match ->
                val line = text.lineNumberAt(match.range.first)
                "${sourceFile.relativePath}:$line ${match.value}"
            }
            .toList()
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is not set")
        return generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }

    private fun String.lineNumberAt(offset: Int): Int {
        return substring(0, offset).count { it == '\n' } + 1
    }

    private data class SourceFile(
        val file: File,
        val relativePath: String
    )

    private companion object {
        private val APP_LOCAL_ACTION_STRING = Regex("\"com\\.andsi\\.airlyrics\\.[^\"]+\"")
        private val SOURCE_EXTENSIONS = setOf("kt", "java")

        private val PROTOCOL_OWNER_FILES = setOf(
            "app/src/main/java/com/andsi/airlyrics/floating/FloatingServiceCommand.kt",
            "app/src/main/java/com/andsi/airlyrics/floating/FloatingWindowStateBroadcast.kt",
            "app/src/main/java/com/andsi/airlyrics/lyrics/LyricsChangedBroadcast.kt",
            "app/src/main/java/com/andsi/airlyrics/media/CurrentMediaBroadcast.kt"
        )
    }
}
