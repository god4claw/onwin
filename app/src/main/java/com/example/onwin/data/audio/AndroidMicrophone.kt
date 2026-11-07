package com.example.onwin.data.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.processing.RecordingException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Реализация [PcmCaptureFactory] для взаимодействия с микрофоном Android-устройства.
 *
 * Отвечает за инициализацию [AudioRecord], захват звукового потока в формате PCM 16-бит (моно)
 * с заданной частотой дискретизации и отслеживание статуса глушения записи системой (silenced).
 *
 * @property context Контекст приложения для проверки разрешений и доступа к системным сервисам.
 */
internal class AndroidMicrophone(
    private val context: Context
) : PcmCaptureFactory {
    /**
     * Проверяет наличие разрешения [Manifest.permission.RECORD_AUDIO].
     *
     * @return `true`, если разрешение на запись аудио предоставлено, иначе `false`.
     */
    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Создает и инициализирует объект [PcmCapture] для захвата PCM-аудио с указанной частотой дискретизации.
     *
     * @param sampleRate Частота дискретизации в Гц (например, 16000 или 44100).
     * @return Экземпляр [PcmCapture] для управления записью или `null`, если частота дискретизации не поддерживается
     * или не удалось инициализировать [AudioRecord].
     * @throws SecurityException Если отсутствует разрешение [Manifest.permission.RECORD_AUDIO].
     */
    @SuppressLint("MissingPermission")
    override fun create(sampleRate: Int): PcmCapture? {
        if (!hasRecordPermission()) {
            throw SecurityException("Microphone permission required")
        }

        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) return null

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(maxOf(minimum * 2, 8192))
                .build()
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: UnsupportedOperationException) {
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        return object : PcmCapture {
            override val sampleRate = record.sampleRate
            private val sessionId = record.audioSessionId
            private val isSilenced = AtomicBoolean(false)

            override val silenced: Boolean
                get() = isSilenced.get() || record.activeRecordingConfiguration?.isClientSilenced == true

            private val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    configs.firstOrNull { it.clientAudioSessionId == sessionId }
                        ?.let { isSilenced.set(it.isClientSilenced) }
                }
            }

            private var registered = false

            override fun start() {
                if (!hasRecordPermission()) {
                    throw SecurityException("Microphone permission revoked")
                }
                try {
                    record.registerAudioRecordingCallback(ContextCompat.getMainExecutor(context), callback)
                    registered = true
                    record.startRecording()
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        throw RecordingException(ErrorCode.MICROPHONE_UNAVAILABLE)
                    }
                } catch (e: IllegalStateException) {
                    throw RecordingException(ErrorCode.MICROPHONE_UNAVAILABLE, e)
                }
            }

            override fun read(samples: ShortArray): Int =
                record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)

            override fun close() {
                try {
                    if (registered) record.unregisterAudioRecordingCallback(callback)
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                } finally {
                    record.release()
                }
            }
        }
    }
}
