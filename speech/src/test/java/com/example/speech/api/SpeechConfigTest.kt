package com.example.speech.api

import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechConfigTest {

    @Test
    fun `rejects non positive thread count`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechConfig(numThreads = 0)
        }
    }

    @Test
    fun `rejects auto cluster threshold outside zero one`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechConfig(autoClusterThreshold = 1.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechConfig(autoClusterThreshold = -0.1f)
        }
    }

    @Test
    fun `rejects invalid online settings`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechConfig(onlineClusterThreshold = 2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechConfig(maxOnlineSpeakers = 0)
        }
    }

    @Test
    fun `accepts boundary values`() {
        SpeechConfig(numThreads = 1, autoClusterThreshold = 0f, onlineClusterThreshold = 1f)
        SpeechConfig(autoClusterThreshold = 1f, onlineClusterThreshold = 0f)
    }
}
