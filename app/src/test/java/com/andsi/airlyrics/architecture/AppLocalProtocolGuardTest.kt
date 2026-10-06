package com.andsi.airlyrics.architecture

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLocalProtocolGuardTest {
    @Test
    fun appLocalProtocolActions_areOwnedByProtocolObjects() {
        val policy = JSONObject(File(projectRoot(), "scripts/architecture/policy.json").readText())
        val namespace = policy.getString("namespace")
        val roots = policy.getJSONArray("source_roots")
        val sourceRoots = (0 until roots.length()).map { roots.getString(it) }
        val owners = policy.getJSONArray("protocol_owners")
        val ownerPaths = (0 until owners.length()).map {
            namespace.replace('.', '/') + "/" + owners.getString(it)
        }.flatMap { relative -> sourceRoots.map { root -> "$root/$relative" } }.toSet()
        val actionPattern = Regex("\"" + Regex.escape(namespace) + "\\.[^\"]+\"")
        val violations = mainSourceFiles(sourceRoots)
            .filterNot { it.relativePath in ownerPaths }
            .flatMap { findAppLocalActionStrings(it, actionPattern) }
            .toList()

        assertTrue(
            "App-local protocol action strings must be owned by protocol objects:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty()
        )
    }

    private fun mainSourceFiles(sourceRoots: List<String>): Sequence<SourceFile> {
        val root = projectRoot()
        return sourceRoots.asSequence()
            .flatMap { File(root, it).walkTopDown() }
            .filter { it.isFile && it.extension in SOURCE_EXTENSIONS }
            .map { file ->
                SourceFile(
                    file = file,
                    relativePath = file.relativeTo(root).invariantSeparatorsPath
                )
            }
    }

    private fun findAppLocalActionStrings(sourceFile: SourceFile, actionPattern: Regex): List<String> {
        val text = sourceFile.file.readText()
        return actionPattern.findAll(text)
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
        private val SOURCE_EXTENSIONS = setOf("kt", "java")
    }
}
