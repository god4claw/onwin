package com.example.speech.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TranscriberDefaultTest {

    @Test
    fun `single argument transcribe delegates with null expected speakers`() = runBlocking {
        val transcriber = RecordingTranscriber()

        transcriber.transcribe(File("audio.wav")).toList()

        assertEquals(listOf<Int?>(null), transcriber.expectedSpeakers)
    }

    @Test
    fun `two argument transcribe keeps explicit expected speakers`() = runBlocking {
        val transcriber = RecordingTranscriber()

        transcriber.transcribe(File("audio.wav"), expectedSpeakers = 2).toList()

        assertEquals(listOf<Int?>(2), transcriber.expectedSpeakers)
    }

    private class RecordingTranscriber : Transcriber {
        val expectedSpeakers = ArrayList<Int?>()

        override suspend fun load(config: SpeechConfig) = Unit

        override fun transcribe(
            wav: File,
            expectedSpeakers: Int?,
        ): Flow<TranscriptionEvent> = flow {
            this@RecordingTranscriber.expectedSpeakers += expectedSpeakers
        }

        override fun release() = Unit
    }
}
