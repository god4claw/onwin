package com.example.speech.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ModelDownloader(
    context: Context,
    @Suppress("UNUSED_PARAMETER") modelsDir: File,
) {
    private val appContext = context.applicationContext

    fun missingModels(): List<ModelFile> =
        ModelRegistry.required.filterNot { BundledModels.isPresent(appContext, it) }

    fun isReady(): Boolean = BundledModels.isPresent(appContext)

    fun cachedBytes(): Long = ModelRegistry.required.sumOf { model ->
        if (BundledModels.isPresent(appContext, model)) model.sizeBytes else 0L
    }

    suspend fun downloadMissing(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): List<File> = withContext(Dispatchers.IO) {
        BundledModels.requirePresent(appContext)
        val totalBytes = ModelRegistry.totalBytes
        onProgress(totalBytes, totalBytes)
        emptyList()
    }

    suspend fun verifyIntegrity(): Boolean = withContext(Dispatchers.IO) {
        BundledModels.verifyIntegrity(appContext)
    }
}
