package com.example.onwin.data.audio

import com.example.onwin.domain.processing.LivePcmSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.milliseconds

/** Reads canonical PCM from the recording file so recognition cannot block microphone capture. */
internal object GrowingWavPcmSource : LivePcmSource {
    override fun read(part: File, wav: File, committed: Deferred<Boolean>) = flow {
        val input = try {
            RandomAccessFile(part, "r")
        } catch (_: FileNotFoundException) {
            if (!committed.await()) return@flow
            RandomAccessFile(wav, "r")
        }
        input.use {
            it.seek(WavWriter.HEADER_BYTES.toLong())
            val bytes = ByteArray(8_192)
            val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (true) {
                val finished = committed.isCompleted
                if (finished && !committed.await()) break
                val samples = ((it.length() - it.filePointer) / Short.SIZE_BYTES)
                    .coerceIn(0, bytes.size.toLong() / Short.SIZE_BYTES).toInt()
                if (samples == 0) {
                    if (finished) break
                    delay(40.milliseconds)
                    continue
                }
                it.readFully(bytes, 0, samples * Short.SIZE_BYTES)
                pcm.rewind()
                emit(FloatArray(samples) { pcm.short / 32_768f })
            }
        }
    }.flowOn(Dispatchers.IO)
}
