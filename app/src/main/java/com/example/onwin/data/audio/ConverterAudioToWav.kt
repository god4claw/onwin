package com.example.onwin.data.audio

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import com.example.onwin.domain.processing.AudioToWav
import com.example.onwin.domain.processing.ConvertedWav
import com.example.onwin.domain.processing.ImportConversionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AudioToWav] поверх [AudioConverter] и [ContentResolver].
 *
 * @param context для [ContentResolver].
 * @param converter конвертер, который пишет в папку записей.
 */
class ConverterAudioToWav(
    private val context: Context,
    private val converter: AudioConverter,
) : AudioToWav {

    override suspend fun displayName(source: String): String? = withContext(Dispatchers.IO) {
        val uri = source.toUri()
        val queried = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            } catch (e: Exception) {
                // Провайдер мог исчезнуть или отказать — имя возьмём из URI.
                Log.w(TAG, "Cannot query display name of $source", e)
                null
            }
        } else {
            null
        }
        queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
    }

    override fun retainAccess(source: String) {
        val uri = source.toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Провайдер не выдал постоянного права: повтор сработает, только пока жив процесс.
            Log.i(TAG, "No persistable permission for $source")
        }
    }

    override fun releaseAccess(source: String) {
        val uri = source.toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Права не было: провайдер его не давал.
        }
    }

    override suspend fun convert(source: String, onProgress: (Float) -> Unit): ConvertedWav = try {
        val result = converter.toWav(source.toUri(), onProgress)
        ConvertedWav(result.file, result.durationMs)
    } catch (error: AudioConversionException) {
        throw ImportConversionException(error.toErrorCode(), error)
    }

    private companion object {
        const val TAG = "AudioToWav"
    }
}
