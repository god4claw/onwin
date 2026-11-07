package com.example.speech.impl

import com.k2fsa.sherpa.onnx.OfflineRecognizer

internal class AsrRunner(
    private val recognizer: OfflineRecognizer,
    private val sampleRate: Int = SAMPLE_RATE,
) {
    private val maxChunkSamples = MAX_CHUNK_SEC * sampleRate
    private val quietSearchSamples = QUIET_SEARCH_SEC * sampleRate
    private val frameSamples = sampleRate / 50

    fun transcribe(samples: FloatArray): String =
        transcribeRange(samples, 0, samples.size)

    fun transcribeRange(samples: FloatArray, startSample: Int, endSample: Int): String {
        require(startSample in 0..samples.size) {
            "startSample=$startSample вне диапазона 0..${samples.size}"
        }
        require(endSample in startSample..samples.size) {
            "endSample=$endSample вне диапазона $startSample..${samples.size}"
        }

        val builder = StringBuilder()
        var chunkStart = startSample

        while (chunkStart < endSample) {
            val maxEnd = minOf(chunkStart + maxChunkSamples, endSample)
            val chunkEnd = if (maxEnd >= endSample) {
                endSample
            } else {
                findQuietBoundary(samples, chunkStart, maxEnd)
            }

            val text = transcribeChunk(recognizer, samples, chunkStart, chunkEnd)
            if (text.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append(text)
            }
            chunkStart = chunkEnd
        }

        return builder.toString().trim()
    }

    private fun transcribeChunk(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
        startSample: Int,
        endSample: Int,
    ): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(
                samples.copyOfRange(startSample, endSample),
                sampleRate,
            )
            recognizer.decode(stream)
            WHITESPACE.replace(recognizer.getResult(stream).text.trim(), " ")
        } finally {
            stream.release()
        }
    }

    private fun findQuietBoundary(
        samples: FloatArray,
        chunkStart: Int,
        targetEnd: Int,
    ): Int {
        val searchStart = maxOf(chunkStart + frameSamples, targetEnd - quietSearchSamples)
        val searchEnd = targetEnd - frameSamples
        if (searchEnd <= searchStart) return targetEnd

        var bestPosition = targetEnd
        var bestEnergy = Float.MAX_VALUE
        var position = searchStart

        while (position <= searchEnd) {
            var energy = 0f
            var i = position
            val limit = position + frameSamples
            while (i < limit) {
                val sample = samples[i]
                energy += sample * sample
                i++
            }
            if (energy < bestEnergy) {
                bestEnergy = energy
                bestPosition = position + frameSamples / 2
            }
            position += frameSamples
        }

        return bestPosition.coerceIn(chunkStart + frameSamples, targetEnd)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_CHUNK_SEC = 45
        const val QUIET_SEARCH_SEC = 2
        val WHITESPACE = Regex("\\s+")
    }
}
