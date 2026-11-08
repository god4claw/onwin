package com.example.llm

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Распаковывает проверенный комплект ONNX GenAI в закрытый каталог приложения. */
internal class BundledLlmModel(
    private val directory: File,
    private val openArchive: () -> InputStream,
    private val openManifest: () -> InputStream,
) {
    suspend fun prepare(): File = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        installationLock.withLock {
            val expected = openManifest().bufferedReader().useLines { lines ->
                lines.map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .associate { line ->
                        val (hash, path) = line.split(Regex("\\s+"), limit = 2)
                        path to hash
                    }
            }
            check(expected.isNotEmpty()) { "Список файлов встроенной LLM пуст." }
            if (expected.keys.all { File(directory, it.substringAfterLast('/')).isFile }) {
                return@withLock directory
            }

            val staging = File(directory.parentFile, "${directory.name}.tmp")
            try {
                if (!staging.deleteRecursively() || !staging.mkdirs()) {
                    throw IOException("Не удалось создать временный каталог LLM.")
                }
                val remaining = expected.toMutableMap()
                val buffer = ByteArray(128 * 1024)
                ZipInputStream(openArchive().buffered()).use { archive ->
                    while (true) {
                        coroutine.ensureActive()
                        val entry = archive.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val hash = remaining.remove(entry.name)
                            ?: throw IOException("Лишний или повторный файл в архиве LLM: ${entry.name}")
                        val digest = MessageDigest.getInstance("SHA-256")
                        File(staging, entry.name.substringAfterLast('/')).outputStream().use { output ->
                            while (true) {
                                coroutine.ensureActive()
                                val count = archive.read(buffer)
                                if (count == -1) break
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (actual != hash) throw IOException("Повреждён файл LLM: ${entry.name}")
                    }
                }
                if (remaining.isNotEmpty()) throw IOException("В архиве отсутствуют файлы LLM: ${remaining.keys}")
                coroutine.ensureActive()
                if (!directory.deleteRecursively()) throw IOException("Не удалось заменить неполный комплект LLM.")
                Files.move(staging.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE)
                directory
            } catch (error: IOException) {
                throw IOException("Не удалось подготовить встроенную LLM. Проверьте свободное место.", error)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    companion object {
        private const val ARCHIVE = "llm/llm-model-v1.zip"
        private const val MANIFEST = "llm/models.sha256"
        private val installationLock = Mutex()

        fun isAvailable(context: Context): Boolean = try {
            context.assets.open(ARCHIVE).use { true }
        } catch (_: FileNotFoundException) {
            false
        }

        fun from(context: Context): BundledLlmModel = BundledLlmModel(
            // Веса можно восстановить из APK, поэтому их не нужно копировать в бэкап.
            directory = File(context.noBackupFilesDir, "llm/v1"),
            openArchive = { context.assets.open(ARCHIVE) },
            openManifest = { context.assets.open(MANIFEST) },
        )
    }
}
