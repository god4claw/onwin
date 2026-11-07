package com.example.onwin.data.audio

import android.Manifest
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

internal class AndroidMicrophone(
    private val context: Context
) : PcmCaptureFactory {
    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

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
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 8192))
                .build()
        } catch (_: IllegalArgumentException) { return null }
        catch (_: UnsupportedOperationException) { return null }
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
                } finally { record.release() }
            }
        }
    }
}
