package com.example.onwin.data.audio

import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.processing.RecordingException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Input(override val sampleRate: Int, private val stop: CompletableDeferred<Unit>) : PcmCapture {
        var closed = false
        var readResult = sampleRate / 10
        override var silenced = false
        override fun start() {}
        override fun read(samples: ShortArray): Int {
            val count = minOf(readResult, samples.size)
            if (count > 0) samples.fill(1234, 0, count)
            stop.complete(Unit)
            return count
        }
        override fun close() { closed = true }
    }

    @Test fun nativeAndFallbackRatesProduceCanonicalWav() = runTest {
        for (rate in listOf(16_000, 48_000, 44_100)) {
            val stop = CompletableDeferred<Unit>()
            val input = Input(rate, stop)
            val attempted = mutableListOf<Int>()
            val recorder = WavMicrophoneRecorder({
                attempted += it
                if (it == rate) input else null
            }, StandardTestDispatcher(testScheduler))
            val file = tmp.newFile("$rate.wav.part")
            val duration = recorder.record(file, stop, {}, {}, live = false)
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val frames = (minOf(rate / 10, 4096).toLong() * 16_000 + rate - 1) / rate
            assertEquals(44L + frames * 2, file.length())
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals(1, header.getShort(22).toInt())
            assertEquals(16_000, header.getInt(24))
            assertEquals(16, header.getShort(34).toInt())
            assertEquals(bytes.size - 44, header.getInt(40))
            assertEquals(frames * 1000 / 16_000, duration)
            assertEquals(rate, attempted.last())
            assertTrue(input.closed)
        }
    }

    @Test fun emptyReadAndStopRejectsEmptyFile() = runTest {
        val stop = CompletableDeferred<Unit>()
        val input = Input(16_000, stop).apply { readResult = 0 }
        val file = tmp.newFile()
        val recorder = WavMicrophoneRecorder({ input }, StandardTestDispatcher(testScheduler))
        try {
            recorder.record(file, stop, {}, {}, live = false)
            fail("Expected empty recording")
        } catch (e: RecordingException) { assertEquals(ErrorCode.EMPTY_RECORDING, e.code) }
        assertFalse(file.exists())
        assertTrue(input.closed)
    }

    @Test fun readErrorAndSystemSilencingDiscardOutput() = runTest {
        for (silenced in listOf(false, true)) {
            val stop = CompletableDeferred<Unit>()
            val input = Input(16_000, stop).apply { readResult = -6; this.silenced = silenced }
            val file = tmp.newFile()
            val recorder = WavMicrophoneRecorder({ input }, StandardTestDispatcher(testScheduler))
            try {
                recorder.record(file, stop, {}, {}, live = false)
                fail("Expected capture error")
            } catch (e: RecordingException) { assertEquals(ErrorCode.MICROPHONE_UNAVAILABLE, e.code) }
            assertFalse(file.exists())
            assertTrue(input.closed)
        }
    }

    @Test fun permissionRevocationIsReported() = runTest {
        val file = tmp.newFile()
        val recorder = WavMicrophoneRecorder({ throw SecurityException() }, StandardTestDispatcher(testScheduler))
        try {
            recorder.record(file, CompletableDeferred(), {}, {}, live = false)
            fail("Expected denied permission")
        } catch (e: RecordingException) { assertEquals(ErrorCode.MICROPHONE_PERMISSION_DENIED, e.code) }
        assertFalse(file.exists())
    }

    @Test fun cancellationOfNonblockingCaptureClosesInputAndDeletesPartial() = runTest {
        var closed = false
        val input = object : PcmCapture {
            override val sampleRate = 16_000
            override val silenced = false
            override fun start() {}
            override fun read(samples: ShortArray) = 0
            override fun close() { closed = true }
        }
        val file = tmp.newFile()
        val recorder = WavMicrophoneRecorder({ input }, StandardTestDispatcher(testScheduler))
        val job = async { recorder.record(file, CompletableDeferred(), {}, {}, live = false) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(closed)
        assertFalse(file.exists())
    }
}
