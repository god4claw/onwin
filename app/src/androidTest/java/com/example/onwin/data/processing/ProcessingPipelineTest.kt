package com.example.onwin.data.processing

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.data.audio.AudioConverter
import com.example.onwin.data.audio.ConverterAudioToWav
import com.example.onwin.data.audio.TestAudioProvider
import com.example.onwin.data.database.AppDatabase
import com.example.onwin.data.repository.RoomSessionRepository
import com.example.onwin.data.transcription.FakeTranscriber
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.DefaultProcessingController
import com.example.onwin.domain.processing.ForegroundStarter
import com.example.onwin.domain.processing.RecordingPhase
import com.example.onwin.ui.notification.ProcessingNotifications
import com.example.speech.api.SpeechConfig
import java.io.File
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Весь конвейер на устройстве: настоящий конвертер и Room, [FakeTranscriber].
 * Foreground-сервис заглушён: у тестового процесса нет видимой Activity, и Android 12+ запуск запретит;
 * сервис проверяется вручную.
 */
@RunWith(AndroidJUnit4::class)
class ProcessingPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var recordings: File
    private lateinit var repository: RoomSessionRepository
    private lateinit var controller: DefaultProcessingController

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        recordings = File(context.filesDir, "pipeline-test-${UUID.randomUUID()}")
        repository = RoomSessionRepository(db.sessionDao(), recordings, Clock.systemUTC())
        controller = DefaultProcessingController(
            audio = ConverterAudioToWav(context, AudioConverter(context, recordings)),
            repository = repository,
            transcriber = FakeTranscriber(loadDelayMs = 100, diarizationMs = 200, realtimeFactor = 0.01),
            speechConfig = SpeechConfig(),
            foreground = NoForeground,
            wakeLock = PowerWakeLockHolder(context),
            microphone = com.example.onwin.domain.processing.MicrophoneRecorder { part, stop, started, duration ->
                part.parentFile!!.mkdirs()
                com.example.onwin.data.audio.WavWriter(part).use { it.writeShorts(ShortArray(16_000) { 1234 }) }
                started()
                duration(1000)
                stop.await()
                1000L
            },
        )
    }

    @After
    fun tearDown() {
        db.close()
        recordings.deleteRecursively()
    }

    @Test
    fun microphone_stopPersistsCanonicalWavAndTranscribesWithoutConversion(): Unit = runBlocking {
        val id = controller.startRecording()!!
        withTimeout(10_000) { controller.state.first { it.recording?.phase == RecordingPhase.RECORDING } }
        val notification = ProcessingNotifications(context).build(controller.state.value)
        assertEquals(listOf(context.getString(com.example.onwin.R.string.action_stop),
            context.getString(com.example.onwin.R.string.action_cancel)), notification.actions.map { it.title.toString() })
        assertTrue(notification.actions.all { it.actionIntent != null })
        assertTrue(notification.contentIntent != null)
        controller.stopRecording(id)
        val detail = withTimeout(10_000) {
            repository.observeSession(id).first { it?.session?.status in FINAL_STATUSES }
        }!!
        assertEquals(SessionStatus.DONE, detail.session.status)
        assertEquals(com.example.onwin.domain.model.SessionSource.MIC, detail.session.source)
        assertEquals(null, detail.session.sourceUri)
        assertEquals(1000L, detail.session.durationMs)
        val wav = repository.wavFile(detail.session.wavFileName!!)
        assertEquals(32_044L, wav.length())
        assertFalse(repository.wavFile("$id.wav.part").exists())
        assertTrue(detail.segments.isNotEmpty())
        withTimeout(10_000) { controller.state.first { !it.busy } }
    }

    @Test
    fun import_reachesDoneWithSegmentsAndWav_thenDeleteRemovesRowsAndFile() = runBlocking {
        val source = TestAudioProvider.uri("file", "mp3_44k_stereo.mp3").toString()
        val id = withTimeout(10_000) { checkNotNull(controller.importAudio(source)).sessionId }
        assertEquals(source, repository.get(id)?.sourceUri)

        val finished = withTimeout(30_000) {
            repository.observeSession(id).first { it?.session?.status in FINAL_STATUSES }
        }!!
        val session = finished.session
        assertEquals("${session.errorCode}: ${session.errorMessage}", SessionStatus.DONE, session.status)
        assertEquals("mp3_44k_stereo", session.title)
        assertEquals(10_000.0, session.durationMs!!.toDouble(), 100.0)
        assertTrue(finished.segments.isNotEmpty())
        assertEquals(finished.segments.indices.toList(), finished.segments.map { it.index })
        assertEquals(session.durationMs, finished.segments.last().endMs)
        val wav = repository.wavFile(session.wavFileName!!)
        assertTrue(wav.isFile)

        controller.delete(id)
        withTimeout(10_000) { repository.observeSession(id).first { it == null } }
        assertFalse(wav.exists())
        assertTrue(recordings.listFiles().orEmpty().isEmpty())
    }

    private object NoForeground : ForegroundStarter {
        override fun start(sessionId: String) = Unit
        override fun stop() = Unit
    }

    private companion object {
        val FINAL_STATUSES = setOf(SessionStatus.DONE, SessionStatus.FAILED, SessionStatus.CANCELLED)
    }
}
