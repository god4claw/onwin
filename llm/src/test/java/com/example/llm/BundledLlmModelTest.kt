package com.example.llm

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledLlmModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val files = mapOf("model/genai_config.json" to "config", "model/model.onnx.data" to "weights")
    private val manifest = files.entries.joinToString("\n") { (name, content) ->
        val hash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
        "$hash  $name"
    }
    private val destination get() = File(temporaryFolder.root, "v1")
    private val staging get() = File(temporaryFolder.root, "v1.tmp")

    @Test
    fun concurrentRequestsReuseVerifiedModelAndRecoverStaleStaging() = runBlocking {
        staging.mkdir()
        File(staging, "partial").writeText("previous interrupted installation")
        var opens = 0
        val installer = BundledLlmModel(
            destination,
            openArchive = { opens++; zip(files).inputStream() },
            openManifest = { manifest.byteInputStream() },
        )

        val installed = List(3) { async { installer.prepare() } }.awaitAll()

        assertTrue(installed.all { it == destination })
        assertEquals(1, opens)
        assertEquals("weights", File(destination, "model.onnx.data").readText())
        assertFalse(staging.exists())
    }

    @Test
    fun incompleteOrCorruptArchiveDoesNotPublishModel() = runBlocking {
        val badArchives = listOf(
            files - "model/model.onnx.data",
            files + ("model/model.onnx.data" to "corrupt"),
            files + ("../unexpected" to "unexpected"),
        )
        for (entries in badArchives) {
            val result = runCatching { installer(entries).prepare() }
            assertTrue(result.exceptionOrNull() is IOException)
            assertFalse(destination.exists())
            assertFalse(staging.exists())
        }

        assertEquals(destination, installer(files).prepare())
    }

    @Test
    fun cancellationCleansStagingAndAllowsRetry() = runBlocking {
        val interrupted = BundledLlmModel(
            destination,
            openArchive = { throw CancellationException("interrupted") },
            openManifest = { manifest.byteInputStream() },
        )
        val result = runCatching { interrupted.prepare() }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(destination.exists())
        assertFalse(staging.exists())
        assertEquals(destination, installer(files).prepare())
    }

    private fun installer(entries: Map<String, String>) = BundledLlmModel(
        destination,
        openArchive = { zip(entries).inputStream() },
        openManifest = { manifest.byteInputStream() },
    )

    private fun zip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, value) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
