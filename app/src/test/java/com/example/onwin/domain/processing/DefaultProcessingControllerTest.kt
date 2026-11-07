package com.example.onwin.domain.processing

import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.model.SessionStatus.CANCELLED
import com.example.onwin.domain.model.SessionStatus.CONVERTING
import com.example.onwin.domain.model.SessionStatus.DONE
import com.example.onwin.domain.model.SessionStatus.FAILED
import com.example.onwin.domain.model.SessionStatus.QUEUED
import com.example.onwin.domain.model.SessionStatus.TRANSCRIBING
import com.example.onwin.domain.processing.ScriptedTranscriber.Companion.emitDefault
import com.example.speech.api.Segment
import com.example.speech.api.SpeechConfig
import com.example.speech.api.Stage
import com.example.speech.api.TranscriptionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProcessingControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val events = mutableListOf<String>()
    private val completed = mutableListOf<String>()
    private lateinit var recordings: File
    private lateinit var repository: FakeSessionRepository
    private lateinit var audio: FakeAudioToWav
    private val transcriber = ScriptedTranscriber(events)
    private val foreground = FakeForegroundStarter(events)
    private val wakeLock = FakeWakeLock()

    @Before
    fun setUp() {
        recordings = tmp.newFolder("recordings")
        repository = FakeSessionRepository(recordings)
        audio = FakeAudioToWav(recordings)
    }

    /** Wake lock отпущен на любом пути: успех, ошибка, отмена, прерывание. */
    @After
    fun wakeLockIsBalanced() {
        wakeLock.assertBalanced()
    }

    @Test
    fun happyPath_persistsStatusesAndLiveSegments_andDoneReplacesThem() = runTest {
        val gate = CompletableDeferred<Unit>()
        transcriber.script = {
            emit(TranscriptionEvent.Progress(Stage.RECOGNITION, 0.5f))
            emit(TranscriptionEvent.SegmentReady(DRAFT[0]))
            emit(TranscriptionEvent.SegmentReady(DRAFT[1]))
            gate.await()
            emit(TranscriptionEvent.Done(ScriptedTranscriber.FINAL))
        }
        val controller = createController()

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        // Сегменты в БД по мере готовности, до Done.
        assertEquals(DRAFT, repository.segments(id))
        assertEquals(TRANSCRIBING, repository.session(id).status)
        assertEquals(ProcessingStage.RECOGNITION, controller.state.value.running?.stage)
        assertEquals(0.5f, controller.state.value.running?.fraction)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(QUEUED, CONVERTING, TRANSCRIBING, DONE), repository.history(id))
        assertEquals(ScriptedTranscriber.FINAL, repository.segments(id))
        val session = repository.session(id)
        assertEquals("talk", session.title)
        assertEquals("talk.mp3", session.originalName)
        assertEquals(SOURCE, session.sourceUri)
        assertEquals(30_000L, session.durationMs)
        assertTrue(repository.wavFile(checkNotNull(session.wavFileName)).isFile)
        assertNull(session.errorCode)
        // Право на источник берётся при импорте и отдаётся только на DONE.
        assertEquals(listOf(SOURCE), audio.retained)
        assertEquals(listOf(SOURCE), audio.released)
        assertEquals(ProcessingState(), controller.state.value)
        assertEquals(1, wakeLock.acquires)
        assertEquals(listOf("fg start", "load", "transcribe", "release", "fg stop"), events)
    }

    @Test
    fun importReturnsCreatedSessionAndKeepsBusyImportsInTheList() = runTest {
        val gate = CompletableDeferred<Unit>()
        audio.behavior = { gate.await() }
        val controller = createController()

        val first = checkNotNull(controller.importAudio(SOURCE))
        val id = first.sessionId
        assertTrue(first.openDetails)
        assertEquals(SessionSource.IMPORT, repository.session(id).source)
        assertEquals(SOURCE, repository.session(id).sourceUri)
        val second = checkNotNull(controller.importAudio("content://test/second.mp3"))
        val third = checkNotNull(controller.importAudio("content://test/third.mp3"))
        assertFalse(second.openDetails)
        assertFalse(third.openDetails)
        assertEquals(QUEUED, repository.session(second.sessionId).status)
        assertEquals(QUEUED, repository.session(third.sessionId).status)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(id, second.sessionId, third.sessionId),
            repository.statusLog.filter { it.second == DONE }.map { it.first })
        assertFalse(controller.state.value.busy)
    }

    @Test
    fun importingTheSameUriKeepsAccessUntilTheLastSessionFinishes() = runTest {
        val gate = CompletableDeferred<Unit>()
        transcriber.script = { call ->
            if (call == 1) gate.await()
            emit(TranscriptionEvent.Done(emptyList()))
        }
        val controller = createController()
        val first = controller.enqueueImport(SOURCE)
        val second = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        assertEquals(DONE, repository.session(first).status)
        assertEquals(TRANSCRIBING, repository.session(second).status)
        controller.delete(first)
        advanceUntilIdle()
        assertTrue(audio.released.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(DONE, repository.session(second).status)
        assertEquals(listOf(SOURCE), audio.released)
    }

    @Test
    fun twoJobs_runSequentially_withOneLoadAndOneReleaseAfterQueueDrains() = runTest {
        val controller = createController()

        val first = controller.enqueueImport("content://test/first.mp3")
        val second = controller.enqueueImport("content://test/second.mp3")
        assertEquals(2, controller.state.value.queuedCount)
        advanceUntilIdle()

        assertEquals(DONE, repository.session(first).status)
        assertEquals(DONE, repository.session(second).status)
        assertEquals(listOf(first, second), completed)
        assertEquals(1, transcriber.loads)
        assertEquals(1, transcriber.releases)
        assertEquals(1, transcriber.maxConcurrent)
        // Вторая сессия начинается только после того, как первая закончилась.
        val log = repository.statusLog
        assertTrue(log.indexOf(second to CONVERTING) > log.indexOf(first to DONE))
        assertEquals(
            listOf("fg start", "fg start", "load", "transcribe", "transcribe", "release", "fg stop"),
            events,
        )
        assertEquals(2, wakeLock.acquires)
    }

    @Test
    fun cancelDuringConversion_cancelsWithoutLeftovers() = runTest {
        audio.behavior = { awaitCancellation() }
        val controller = createController()
        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()
        assertEquals(CONVERTING, repository.session(id).status)
        assertEquals(ProcessingStage.CONVERTING, controller.state.value.running?.stage)

        controller.cancel(id)
        advanceUntilIdle()

        assertTrue("CancellationException must reach the converter", audio.cancelled)
        assertEquals(listOf(QUEUED, CONVERTING, CANCELLED), repository.history(id))
        assertTrue(repository.segments(id).isEmpty())
        assertNull(repository.session(id).wavFileName)
        assertTrue(recordings.listFiles()!!.isEmpty())
        assertEquals(0, transcriber.loads)
        assertEquals(1, transcriber.releases)
        assertEquals(1, foreground.stops)
        assertEquals(ProcessingState(), controller.state.value)
    }

    @Test
    fun cancelDuringTranscription_clearsSegmentsAndWav_andNextJobStillRuns() = runTest {
        transcriber.script = { call ->
            if (call == 0) {
                emit(TranscriptionEvent.SegmentReady(DRAFT[0]))
                emit(TranscriptionEvent.SegmentReady(DRAFT[1]))
                awaitCancellation()
            } else {
                emitDefault()
            }
        }
        val controller = createController()
        val cancelled = controller.enqueueImport("content://test/cancelled.mp3")
        val next = controller.enqueueImport("content://test/next.mp3")
        advanceUntilIdle()
        assertEquals(DRAFT, repository.segments(cancelled))
        val wav = repository.wavFile(checkNotNull(repository.session(cancelled).wavFileName))

        controller.cancel(cancelled)
        advanceUntilIdle()

        assertTrue(
            "CancellationException must reach the transcription flow",
            transcriber.completions.first() is CancellationException,
        )
        assertEquals(listOf(QUEUED, CONVERTING, TRANSCRIBING, CANCELLED), repository.history(cancelled))
        assertTrue(repository.segments(cancelled).isEmpty())
        assertNull(repository.session(cancelled).wavFileName)
        assertFalse("WAV created in this run is deleted", wav.exists())
        // Работник пережил отмену и довёл следующее задание.
        assertEquals(DONE, repository.session(next).status)
        assertEquals(1, transcriber.loads)
        assertEquals(1, transcriber.releases)
        assertEquals(1, foreground.stops)
    }

    @Test
    fun cancelQueued_removesFromQueueWithoutProcessing() = runTest {
        val controller = createController()
        val first = controller.enqueueImport("content://test/first.mp3")
        val queued = controller.enqueueImport("content://test/queued.mp3")

        controller.cancel(queued)
        assertEquals(1, controller.state.value.queuedCount)
        advanceUntilIdle()

        assertEquals(listOf(QUEUED, CANCELLED), repository.history(queued))
        assertEquals(listOf("content://test/first.mp3"), audio.conversions)
        assertEquals(DONE, repository.session(first).status)
    }

    @Test
    fun failedEvent_failsWithTranscriptionFailed_keepingPartialSegmentsAndWav() = runTest {
        transcriber.script = {
            emit(TranscriptionEvent.SegmentReady(DRAFT[0]))
            emit(TranscriptionEvent.Failed(RuntimeException("model crashed")))
        }
        val controller = createController()
        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        val session = repository.session(id)
        assertEquals(listOf(QUEUED, CONVERTING, TRANSCRIBING, FAILED), repository.history(id))
        assertEquals(ErrorCode.TRANSCRIPTION_FAILED, session.errorCode)
        assertTrue(session.errorMessage!!.contains("model crashed"))
        assertEquals(listOf(DRAFT[0]), repository.segments(id))
        assertTrue(repository.wavFile(session.wavFileName!!).isFile)
        assertTrue("grant is kept for retry", audio.released.isEmpty())
        assertEquals(1, transcriber.releases)
        assertEquals(1, foreground.stops)
    }

    @Test
    fun transcriberExceptions_failWithTranscriptionFailed_andModelsAreStillReleased() = runTest {
        transcriber.script = { call ->
            when (call) {
                0 -> throw IOException("native crash")
                else -> emit(TranscriptionEvent.SegmentReady(DRAFT[0]))  // завершился без Done
            }
        }
        val controller = createController()
        val thrown = controller.enqueueImport("content://test/thrown.mp3")
        val noDone = controller.enqueueImport("content://test/no-done.mp3")
        advanceUntilIdle()
        transcriber.load = { throw IllegalStateException("models missing") }
        val loadFailed = controller.enqueueImport("content://test/load-failed.mp3")
        advanceUntilIdle()

        for (id in listOf(thrown, noDone, loadFailed)) {
            assertEquals(id, FAILED, repository.session(id).status)
            assertEquals(id, ErrorCode.TRANSCRIPTION_FAILED, repository.session(id).errorCode)
        }
        assertEquals(2, transcriber.releases)  // по одному на каждую пачку
    }

    @Test
    fun conversionFailures_preserveErrorCodeAndCause() = runTest {
        val cause = IOException("Cannot convert audio")
        val cases = listOf(
            ImportConversionException(ErrorCode.SOURCE_UNAVAILABLE, cause) to ErrorCode.SOURCE_UNAVAILABLE,
            ImportConversionException(ErrorCode.NO_AUDIO_TRACK, cause) to ErrorCode.NO_AUDIO_TRACK,
            ImportConversionException(ErrorCode.UNSUPPORTED_FORMAT, cause) to ErrorCode.UNSUPPORTED_FORMAT,
            ImportConversionException(ErrorCode.DECODE_FAILED, cause) to ErrorCode.DECODE_FAILED,
            ImportConversionException(ErrorCode.TOO_LONG, cause) to ErrorCode.TOO_LONG,
            ImportConversionException(ErrorCode.STORAGE_FULL, cause) to ErrorCode.STORAGE_FULL,
            IllegalStateException("bug") to ErrorCode.UNKNOWN,
        )
        val controller = createController()
        for ((error, expected) in cases) {
            audio.behavior = { throw error }
            val id = controller.enqueueImport(SOURCE)
            advanceUntilIdle()
            assertEquals(error.toString(), listOf(QUEUED, CONVERTING, FAILED), repository.history(id))
            assertEquals(error.toString(), expected, repository.session(id).errorCode)
            val expectedMessage = if (error is ImportConversionException) cause.toString() else error.toString()
            assertEquals(expectedMessage, repository.session(id).errorMessage)
        }
        assertEquals(0, transcriber.loads)
    }

    @Test
    fun startup_marksUnfinishedSessionsInterrupted_andTheyCanBeRetried() = runTest {
        repository.put(stored("queued", QUEUED))
        repository.put(stored("converting", CONVERTING), segments = listOf(DRAFT[0]))
        repository.put(stored("transcribing", TRANSCRIBING))
        repository.put(stored("done", DONE))
        repository.put(stored("failed", FAILED, ErrorCode.TRANSCRIPTION_FAILED))

        val controller = createController()
        advanceUntilIdle()

        for (id in listOf("queued", "converting", "transcribing")) {
            assertEquals(id, FAILED, repository.session(id).status)
            assertEquals(id, ErrorCode.INTERRUPTED, repository.session(id).errorCode)
        }
        assertEquals(listOf(DRAFT[0]), repository.segments("converting"))
        assertEquals(DONE, repository.session("done").status)
        assertEquals(ErrorCode.TRANSCRIPTION_FAILED, repository.session("failed").errorCode)
        assertTrue(foreground.starts == 0 && wakeLock.acquires == 0)

        controller.retry("transcribing")
        advanceUntilIdle()
        assertEquals(DONE, repository.session("transcribing").status)
        assertNull(repository.session("transcribing").errorCode)
    }

    @Test
    fun startup_deletesOrphanFilesOnce_beforeNewSessionsAreWritten() = runTest {
        repository.put(stored("old", FAILED, ErrorCode.TRANSCRIPTION_FAILED))

        val controller = createController()
        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        // Новая сессия ещё не создана: её WAV не мог попасть под удаление.
        assertEquals(listOf(setOf("old")), repository.orphanCleanups)
        assertEquals(DONE, repository.session(id).status)
    }

    @Test
    fun retry_reusesExistingWav_andClearsOldSegmentsFirst() = runTest {
        val wav = File(recordings, "old.wav").apply { writeBytes(ByteArray(44 + 32_000)) }
        repository.put(
            stored("s", FAILED, ErrorCode.TRANSCRIPTION_FAILED).copy(wavFileName = wav.name, durationMs = 1_000),
            segments = listOf(Segment(2, 0, 500, "устаревший")),
        )
        var segmentsAtStart: List<Segment>? = null
        transcriber.script = {
            segmentsAtStart = repository.segments("s")
            emitDefault()
        }
        val controller = createController()

        controller.retry("s")
        advanceUntilIdle()

        assertTrue(audio.conversions.isEmpty())
        assertEquals(emptyList<Segment>(), segmentsAtStart)
        assertEquals(listOf(QUEUED, TRANSCRIBING, DONE), repository.history("s"))
        assertEquals(ScriptedTranscriber.FINAL, repository.segments("s"))
        assertEquals(wav.name, repository.session("s").wavFileName)
        assertTrue(wav.isFile)
    }

    @Test
    fun retry_ofDoneSession_isIgnored() = runTest {
        repository.put(stored("done", DONE), segments = ScriptedTranscriber.FINAL)
        val controller = createController()

        controller.retry("done")
        advanceUntilIdle()

        assertEquals(emptyList<SessionStatus>(), repository.history("done"))
        assertEquals(ScriptedTranscriber.FINAL, repository.segments("done"))
        assertEquals(0, transcriber.loads)
    }

    @Test
    fun foregroundStartRefused_failsWithForegroundNotAllowed_withoutCrashing() = runTest {
        foreground.refuse = true
        val controller = createController()

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        assertEquals(listOf(QUEUED, FAILED), repository.history(id))
        assertEquals(ErrorCode.FOREGROUND_NOT_ALLOWED, repository.session(id).errorCode)
        assertTrue(audio.conversions.isEmpty())
        assertEquals(0, wakeLock.acquires)
        assertEquals(ProcessingState(), controller.state.value)

        foreground.refuse = false
        controller.retry(id)
        advanceUntilIdle()
        assertEquals(DONE, repository.session(id).status)
    }

    @Test
    fun failAll_failsRunningAndQueuedAsInterrupted_keepingResults() = runTest {
        transcriber.script = {
            emit(TranscriptionEvent.SegmentReady(DRAFT[0]))
            awaitCancellation()
        }
        val controller = createController()
        val running = controller.enqueueImport("content://test/running.mp3")
        val queued = controller.enqueueImport("content://test/queued.mp3")
        advanceUntilIdle()

        controller.failAll(ErrorCode.INTERRUPTED)
        advanceUntilIdle()

        assertEquals(listOf(QUEUED, CONVERTING, TRANSCRIBING, FAILED), repository.history(running))
        assertEquals(ErrorCode.INTERRUPTED, repository.session(running).errorCode)
        assertEquals(listOf(DRAFT[0]), repository.segments(running))
        assertTrue(repository.wavFile(repository.session(running).wavFileName!!).isFile)
        assertEquals(listOf(QUEUED, FAILED), repository.history(queued))
        assertEquals(ErrorCode.INTERRUPTED, repository.session(queued).errorCode)
        assertEquals(1, audio.conversions.size)
        assertEquals(1, transcriber.releases)
        assertEquals(1, foreground.stops)
    }

    @Test
    fun delete_removesRowsWavAndGrant_butNotWhileRunning() = runTest {
        val controller = createController()
        val done = controller.enqueueImport("content://test/done.mp3")
        advanceUntilIdle()
        val wav = repository.wavFile(repository.session(done).wavFileName!!)

        controller.delete(done)
        advanceUntilIdle()
        assertFalse(repository.exists(done))
        assertFalse(wav.exists())

        audio.behavior = { awaitCancellation() }
        val running = controller.enqueueImport("content://test/running.mp3")
        advanceUntilIdle()
        controller.delete(running)
        advanceUntilIdle()
        assertEquals(CONVERTING, repository.session(running).status)

        controller.cancel(running)
        advanceUntilIdle()
        controller.delete(running)
        advanceUntilIdle()
        assertFalse(repository.exists(running))
        assertEquals(listOf("content://test/done.mp3", "content://test/done.mp3", "content://test/running.mp3"), audio.released)
    }

    private fun TestScope.createController() = DefaultProcessingController(
        audio = audio,
        repository = repository,
        transcriber = transcriber,
        speechConfig = SpeechConfig(),
        foreground = foreground,
        wakeLock = wakeLock,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
        log = ::println,
        onTranscriptionCompleted = { id ->
            assertEquals(DONE, repository.session(id).status)
            completed += id
        },
    )

    private fun stored(id: String, status: SessionStatus, errorCode: ErrorCode? = null) = Session(
        id = id,
        title = id,
        source = SessionSource.IMPORT,
        originalName = "$id.mp3",
        sourceUri = "content://test/$id.mp3",
        createdAt = NOW,
        updatedAt = NOW,
        status = status,
        errorCode = errorCode,
    )

    private companion object {
        const val SOURCE = "content://test/talk.mp3"
        const val NOW = 1_700_000_000_000L
        val DRAFT = listOf(Segment(0, 0, 1_000, "черновик один"), Segment(1, 1_500, 3_000, "черновик два"))
    }
}
