package com.example.onwin.data.transcription

import com.example.speech.api.SpeechConfig
import com.example.speech.api.Stage
import com.example.speech.api.TranscriptionEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class FakeTranscriberTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val config by lazy { SpeechConfig() }

    @Test
    fun followsContractOrder_withSegmentsSpreadOverWavDuration() = runTest {
        val transcriber = FakeTranscriber()
        transcriber.load(config)
        val events = transcriber.transcribe(wav(durationMs = 30_000)).toList()

        val diarization = events.takeWhile { it is TranscriptionEvent.Progress && it.stage == Stage.DIARIZATION }
            .map { (it as TranscriptionEvent.Progress).fraction }
        assertTrue(diarization.size > 1)
        assertEquals(diarization.sorted(), diarization)
        assertEquals(1f, diarization.last())
        assertTrue("diarization takes ~2 s, took $currentTime ms", currentTime >= 2_000)

        val rest = events.drop(diarization.size)
        val done = rest.last() as TranscriptionEvent.Done
        val middle = rest.dropLast(1)
        assertTrue(middle.all { it is TranscriptionEvent.SegmentReady || it.isRecognitionProgress() })
        val segments = middle.filterIsInstance<TranscriptionEvent.SegmentReady>().map { it.segment }
        assertEquals(segments, done.segments)

        assertTrue(segments.size > 3)
        assertEquals(0L, segments.first().startMs)
        assertEquals(30_000L, segments.last().endMs)
        segments.zipWithNext().forEach { (previous, next) -> assertTrue(next.startMs >= previous.endMs) }
        assertTrue(segments.all { it.endMs > it.startMs && it.text.isNotBlank() })
        assertEquals(List(segments.size) { it % 3 }, segments.map { it.speaker })
    }

    @Test
    fun failAfterSegments_emitsFailedAndNoDone() = runTest {
        val transcriber = FakeTranscriber(failAfterSegments = 2)
        transcriber.load(config)
        val events = transcriber.transcribe(wav(durationMs = 30_000)).toList()

        assertTrue(events.last() is TranscriptionEvent.Failed)
        assertEquals(2, events.count { it is TranscriptionEvent.SegmentReady })
        assertFalse(events.any { it is TranscriptionEvent.Done })
    }

    @Test
    fun cancellingCollector_stopsTranscription_andNextOneCanStart() = runTest {
        val transcriber = FakeTranscriber()
        transcriber.load(config)
        val wav = wav(durationMs = 60_000)
        val received = mutableListOf<TranscriptionEvent>()
        val job = launch { transcriber.transcribe(wav).collect { received += it } }
        advanceTimeBy(1_000)
        job.cancel()
        job.join()

        assertTrue(received.isNotEmpty())
        assertFalse(received.any { it is TranscriptionEvent.Done })
        assertTrue(transcriber.transcribe(wav).toList().last() is TranscriptionEvent.Done)
    }

    @Test
    fun transcribeWithoutLoad_isRejected() = runTest {
        val error = runCatching { FakeTranscriber().transcribe(wav(durationMs = 1_000)).toList() }.exceptionOrNull()
        assertTrue("$error", error is IllegalStateException)
    }

    private fun TranscriptionEvent.isRecognitionProgress() =
        this is TranscriptionEvent.Progress && stage == Stage.RECOGNITION

    /** Канонический заголовок WAV 16 кГц моно PCM16; сами сэмплы фейку не нужны. */
    private fun wav(durationMs: Long): File {
        val dataBytes = (durationMs * 32).toInt()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataBytes)
        return tmp.newFile().apply { writeBytes(header.array()) }
    }
}
