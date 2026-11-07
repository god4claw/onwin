package com.example.onwin.data.processing

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.data.audio.AudioConverter
import com.example.onwin.data.audio.ConverterAudioToWav
import com.example.onwin.data.audio.GrowingWavPcmSource
import com.example.onwin.data.audio.PcmCapture
import com.example.onwin.data.audio.WavMicrophoneRecorder
import com.example.onwin.data.audio.WavWriter
import com.example.onwin.data.database.AppDatabase
import com.example.onwin.data.repository.RoomSessionRepository
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.DefaultProcessingController
import com.example.onwin.domain.processing.ForegroundStarter
import com.example.speech.api.SpeechConfig
import com.example.speech.impl.SherpaVadTranscriber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
class LiveSpeechDeviceTest {
    @Test
    fun speechAppearsBeforeStopAndFinalizesAfterStop(): Unit = runBlocking {
        val sample = InstrumentationRegistry.getArguments().getString("speechSample")
        assumeNotNull(sample)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File.createTempFile("live-speech-", null, context.cacheDir).apply {
            check(delete() && mkdir())
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val source = File(dir, "input.${checkNotNull(sample).substringAfterLast('.')}")
            instrumentation.context.assets.open(sample).use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val converter = AudioConverter(context, dir)
            val audio = converter.toWav(Uri.fromFile(source)).file.readBytes()
            val pcm = ByteBuffer.wrap(audio, WavWriter.HEADER_BYTES, audio.size - WavWriter.HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val microphone = WavMicrophoneRecorder({
                object : PcmCapture {
                    override val sampleRate = 16_000
                    override val silenced = false
                    private var silence = 16_000
                    override fun start() = Unit
                    override fun close() = Unit
                    override fun read(samples: ShortArray): Int {
                        if (pcm.hasRemaining()) {
                            val count = minOf(pcm.remaining(), samples.size)
                            pcm.get(samples, 0, count)
                            return count
                        }
                        val count = minOf(silence, samples.size)
                        samples.fill(0, toIndex = count)
                        silence -= count
                        return count
                    }
                }
            })
            val repository = RoomSessionRepository(db.sessionDao(), File(dir, "recordings"), Clock.systemUTC())
            val controller = DefaultProcessingController(
                audio = ConverterAudioToWav(context, converter),
                repository = repository,
                transcriber = SherpaVadTranscriber(context),
                speechConfig = SpeechConfig(),
                foreground = object : ForegroundStarter {
                    override fun start(sessionId: String) = Unit
                    override fun stop() = Unit
                },
                wakeLock = PowerWakeLockHolder(context),
                microphone = microphone,
                livePcm = GrowingWavPcmSource,
            )
            val id = checkNotNull(controller.startRecording())
            try {
                val draft = withTimeout(120_000.milliseconds) {
                    checkNotNull(repository.observeSession(id).first { it?.segments?.isNotEmpty() == true })
                }
                assertEquals(SessionStatus.RECORDING, draft.session.status)
                controller.stopRecording(id)
                val finished = withTimeout(120_000.milliseconds) {
                    checkNotNull(repository.observeSession(id).first { it != null && !it.session.status.isActive })
                }
                assertEquals(finished.session.errorMessage, SessionStatus.DONE, finished.session.status)
                assertTrue(finished.segments.all { it.text.isNotBlank() && it.startMs < it.endMs })
                assertTrue(finished.segments.isNotEmpty())
            } finally {
                controller.cancel(id)
                withTimeout(30_000.milliseconds) { controller.state.first { !it.busy } }
            }
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }
}
