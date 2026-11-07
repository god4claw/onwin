package com.example.onwin.domain.processing

import com.example.onwin.domain.model.ErrorCode
import java.io.File

/**
 * Входной этап обработки: доступ к исходнику и конвертация в WAV.
 * Источник — строка URI, чтобы контроллер тестировался на JVM без `android.net.Uri`.
 */
interface AudioToWav {
    /** Имя файла для показа; `null`, если провайдер его не сообщил. */
    suspend fun displayName(source: String): String?

    /** Сохраняет право читать [source] после перезапуска процесса, если провайдер это поддерживает. */
    fun retainAccess(source: String)

    /** Отдаёт право, взятое [retainAccess]; если его нет — ничего не делает. */
    fun releaseAccess(source: String)

    /**
     * Конвертирует [source] в WAV 16 кГц моно PCM16 в папке записей.
     *
     * @param onProgress 0..1, из фонового потока.
     * @throws ImportConversionException если сконвертировать не удалось.
     */
    suspend fun convert(
        source: String,
        onProgress: (Float) -> Unit
    ): ConvertedWav
}

/** Готовый WAV и его длительность. */
data class ConvertedWav(
    val file: File,
    val durationMs: Long
)

/** Конвертация не удалась; [code] — причина для UI. */
class ImportConversionException(
    val code: ErrorCode,
    override val cause: Throwable
) : Exception(cause)
