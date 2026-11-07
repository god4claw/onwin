package com.example.speech.impl

import com.example.speech.api.SpeechConfig
import com.example.speech.api.VadConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigKeysTest {

    @Test
    fun `batch reload ignores streaming-only and runtime fields`() {
        val base = SpeechConfig(numThreads = 4)

        assertTrue(base.batchModelsMatch(SpeechConfig(numThreads = 4, onlineClusterThreshold = 0.3f)))
        assertTrue(base.batchModelsMatch(SpeechConfig(numThreads = 4, clusterPruneMinMs = 1_000)))
        assertTrue(base.batchModelsMatch(SpeechConfig(numThreads = 4, vad = VadConfig(minSilenceMs = 100))))
    }

    @Test
    fun `batch reload depends on model and decoding fields`() {
        val base = SpeechConfig(numThreads = 4)

        assertFalse(base.batchModelsMatch(SpeechConfig(numThreads = 2)))
        assertFalse(base.batchModelsMatch(SpeechConfig(numThreads = 4, autoClusterThreshold = 0.5f)))
    }

    @Test
    fun `streaming reload ignores runtime fields`() {
        val base = SpeechConfig(numThreads = 4)

        assertTrue(base.streamingModelsMatch(SpeechConfig(numThreads = 4, onlineClusterThreshold = 0.3f)))
        assertTrue(base.streamingModelsMatch(SpeechConfig(numThreads = 4, clusterPruneMinMs = 1_000)))
    }

    @Test
    fun `streaming reload depends on embedding and vad`() {
        val base = SpeechConfig(numThreads = 4)

        assertFalse(base.streamingModelsMatch(SpeechConfig(numThreads = 4, vad = VadConfig(minSilenceMs = 100))))
        assertFalse(base.streamingModelsMatch(SpeechConfig(numThreads = 2)))
    }
}
