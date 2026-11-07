package com.example.onwin.data.audio

import java.io.File

/**
 * Результат [AudioConverter.toWav].
 *
 * @property file WAV 16 кГц, моно, PCM16 с каноническим 44-байтовым заголовком.
 * @property durationMs длительность: записанные сэмплы / 16 000.
 * @property source параметры исходника (для логов и отладочного UI).
 */
data class WavResult(
    val file: File,
    val durationMs: Long,
    val source: SourceInfo
)

/**
 * Параметры исходника по фактическому выходу декодера (а не по контейнеру).
 * При смене формата посреди потока — первый формат.
 *
 * @property mime MIME-тип дорожки, например `audio/mp4a-latm`.
 * @property sampleRate частота дискретизации на выходе декодера, Гц.
 * @property channels число каналов на выходе декодера.
 * @property pcmEncoding кодировка PCM, константа `android.media.AudioFormat.ENCODING_*`.
 */
data class SourceInfo(
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    val pcmEncoding: Int
)
