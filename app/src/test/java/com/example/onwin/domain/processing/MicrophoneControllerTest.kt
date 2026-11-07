package com.example.onwin.domain.processing

import com.example.onwin.domain.processing.MicrophoneRecorder
import com.example.onwin.domain.processing.RecordingException
import com.example.onwin.data.audio.WavWriter
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.speech.api.SpeechConfig
import com.example.speech.api.TranscriptionEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val events = mutableListOf<String>()
    private val foreground = object : ForegroundStarter {
        val promoted = CompletableDeferred<Unit>()
        var refuse = false
        override fun start(sessionId: String) { if (refuse) throw ForegroundStartException(SecurityException()) }
        override suspend fun awaitMicrophone() { promoted.await() }
        override suspend fun awaitProcessing() { events += "processing" }
        override fun stop() { events += "stopped" }
    }
    private val wakeLock = FakeWakeLock()
    private val transcriber = ScriptedTranscriber(events)
    private lateinit var repository: FakeSessionRepository
    private lateinit var audio: FakeAudioToWav
    private var captureFailure: ErrorCode? = null
    private val microphone = MicrophoneRecorder { file, stop, started, duration ->
        events += "capture"
        captureFailure?.let { throw RecordingException(it) }
        started()
        WavWriter(file).use { it.writeShorts(ShortArray(16_000) { 100 }) }
        duration(1000)
        stop.await()
        1000L
    }

    private fun TestScope.controller(): DefaultProcessingController {
        val dir = tmp.newFolder()
        repository = FakeSessionRepository(dir)
        audio = FakeAudioToWav(dir)
        return DefaultProcessingController(audio, repository, transcriber, SpeechConfig(),
            foreground, wakeLock, StandardTestDispatcher(testScheduler), log = {}, microphone = microphone,
            recordingTitle = { "Recording" })
    }

    @Test fun stopWaitsForForegroundAndTranscribesBeforeQueuedImport() = runTest {
        val controller = controller()
        val id = controller.startRecording()!!
        assertNull(controller.startRecording())
        val submission = checkNotNull(controller.importAudio("content://test/import.mp3"))
        assertFalse(submission.openDetails)
        val imported = submission.sessionId
        advanceUntilIdle()
        assertFalse(events.contains("capture"))
        assertTrue(audio.conversions.isEmpty())
        assertEquals(SessionStatus.RECORDING, repository.session(id).status)
        foreground.promoted.complete(Unit)
        advanceUntilIdle()
        assertEquals(1000L, controller.state.value.recording?.elapsedMs)
        controller.stopRecording("obsolete")
        assertEquals(RecordingPhase.RECORDING, controller.state.value.recording?.phase)
        controller.stopRecording(id)
        controller.stopRecording(id)
        advanceUntilIdle()
        assertEquals(SessionStatus.DONE, repository.session(id).status)
        assertEquals(SessionStatus.DONE, repository.session(imported).status)
        assertEquals(listOf(id, imported),
            repository.statusLog.filter { it.second == SessionStatus.TRANSCRIBING }.map { it.first })
        assertEquals(SessionSource.MIC, repository.session(id).source)
        assertNull(repository.session(id).sourceUri)
        assertEquals(listOf(SessionStatus.RECORDING, SessionStatus.QUEUED, SessionStatus.TRANSCRIBING, SessionStatus.DONE), repository.history(id))
        assertEquals(1, audio.conversions.size)
        assertTrue(events.indexOf("processing") < events.indexOf("transcribe"))
        assertFalse(controller.state.value.busy)
        wakeLock.assertBalanced()
    }

    @Test fun cancelDuringCaptureDiscardsAudioAndCannotRetry() = runTest {
        val controller = controller()
        foreground.promoted.complete(Unit)
        val id = controller.startRecording()!!
        advanceUntilIdle()
        controller.cancel(id)
        controller.stopRecording(id)
        controller.cancel(id)
        advanceUntilIdle()
        assertEquals(SessionStatus.CANCELLED, repository.session(id).status)
        assertNull(repository.session(id).wavFileName)
        assertFalse(repository.session(id).canRetry)
        assertFalse(repository.wavFile("$id.wav.part").exists())
        controller.retry(id)
        advanceUntilIdle()
        assertEquals(SessionStatus.CANCELLED, repository.session(id).status)
        wakeLock.assertBalanced()
    }

    @Test fun stopBeforeCaptureStartsIsNotLost() = runTest {
        val controller = controller()
        val id = controller.startRecording()!!
        controller.stopRecording(id)
        foreground.promoted.complete(Unit)
        advanceUntilIdle()
        assertEquals(SessionStatus.DONE, repository.session(id).status)
        wakeLock.assertBalanced()
    }

    @Test fun cancelBeforeWorkerStartsAndBeforePromotionDoesNotCapture() = runTest {
        val controller = controller()
        val id = controller.startRecording()!!
        controller.cancel(id)
        advanceUntilIdle()
        assertEquals(SessionStatus.CANCELLED, repository.session(id).status)
        assertFalse(events.contains("capture"))
        assertNull(controller.state.value.recording)
        wakeLock.assertBalanced()
    }

    @Test fun captureFailureAllowsWaitingImportToContinue() = runTest {
        val controller = controller()
        captureFailure = ErrorCode.MICROPHONE_UNAVAILABLE
        foreground.promoted.complete(Unit)
        val id = controller.startRecording()!!
        val imported = controller.enqueueImport("content://test/import.mp3")
        advanceUntilIdle()
        assertEquals(ErrorCode.MICROPHONE_UNAVAILABLE, repository.session(id).errorCode)
        assertEquals(SessionStatus.DONE, repository.session(imported).status)
        assertNull(controller.state.value.recording)
        wakeLock.assertBalanced()
    }

    @Test fun synchronousForegroundRefusalDoesNotCapture() = runTest {
        val controller = controller()
        foreground.refuse = true
        val id = controller.startRecording()!!
        advanceUntilIdle()
        assertEquals(ErrorCode.FOREGROUND_NOT_ALLOWED, repository.session(id).errorCode)
        assertFalse(events.contains("capture"))
        assertNull(controller.state.value.recording)
        wakeLock.assertBalanced()
    }

    @Test fun promotionFailureDoesNotCapture() = runTest {
        val controller = controller()
        foreground.promoted.completeExceptionally(ForegroundStartException(SecurityException()))
        val id = controller.startRecording()!!
        advanceUntilIdle()
        assertEquals(ErrorCode.FOREGROUND_NOT_ALLOWED, repository.session(id).errorCode)
        assertFalse(events.contains("capture"))
        wakeLock.assertBalanced()
    }

    @Test fun cancellingTranscriptionPreservesOriginalAndRetryReusesIt() = runTest {
        val controller = controller()
        foreground.promoted.complete(Unit)
        transcriber.script = { awaitCancellation() }
        val id = controller.startRecording()!!
        advanceUntilIdle()
        controller.stopRecording(id)
        advanceUntilIdle()
        controller.cancel(id)
        advanceUntilIdle()
        val wav = repository.wavFile(repository.session(id).wavFileName!!)
        assertTrue(wav.isFile)
        assertTrue(repository.session(id).canRetry)
        transcriber.script = { emit(com.example.speech.api.TranscriptionEvent.Done(emptyList())) }
        controller.retry(id)
        advanceUntilIdle()
        assertEquals(SessionStatus.DONE, repository.session(id).status)
        assertTrue(audio.conversions.isEmpty())
        assertEquals(1, events.count { it == "capture" })
        wakeLock.assertBalanced()
    }

    @Test fun recordingStartsDuringProcessingAndReservesItsFifoPosition() = runTest {
        val controller = controller()
        val firstDone = CompletableDeferred<Unit>()
        transcriber.script = { call ->
            if (call == 0) firstDone.await()
            emit(TranscriptionEvent.Done(emptyList()))
        }
        val first = controller.enqueueImport("content://test/first.mp3")
        advanceUntilIdle()
        foreground.promoted.complete(Unit)
        val mic = checkNotNull(controller.startRecording())
        val last = checkNotNull(controller.importAudio("content://test/last.mp3")).sessionId
        advanceUntilIdle()
        assertEquals(first, controller.state.value.running?.sessionId)
        assertEquals(RecordingPhase.RECORDING, controller.state.value.recording?.phase)
        assertNull(controller.startRecording())

        firstDone.complete(Unit)
        advanceUntilIdle()
        assertEquals(mic, controller.state.value.recording?.sessionId)
        assertEquals(SessionStatus.DONE, repository.session(first).status)
        assertEquals(SessionStatus.QUEUED, repository.session(last).status)
        assertEquals(0, wakeLock.releases)

        controller.stopRecording(mic)
        advanceUntilIdle()
        assertEquals(listOf(first, mic, last),
            repository.statusLog.filter { it.second == SessionStatus.TRANSCRIBING }.map { it.first })
        assertEquals(1, transcriber.maxConcurrent)
        assertEquals(SessionStatus.DONE, repository.session(last).status)
        advanceUntilIdle()
        wakeLock.assertBalanced()
    }

    @Test fun cancellingCaptureWhileProcessingLeavesTheOtherJobsRunning() = runTest {
        val controller = controller()
        val firstDone = CompletableDeferred<Unit>()
        transcriber.script = { call ->
            if (call == 0) firstDone.await()
            emit(TranscriptionEvent.Done(emptyList()))
        }
        val first = controller.enqueueImport("content://test/first.mp3")
        advanceUntilIdle()
        foreground.promoted.complete(Unit)
        val mic = checkNotNull(controller.startRecording())
        val last = controller.enqueueImport("content://test/last.mp3")
        advanceUntilIdle()
        controller.cancel(mic)
        advanceUntilIdle()

        assertEquals(SessionStatus.CANCELLED, repository.session(mic).status)
        assertEquals(first, controller.state.value.running?.sessionId)
        assertNull(controller.state.value.recording)
        assertEquals(0, wakeLock.releases)
        firstDone.complete(Unit)
        advanceUntilIdle()
        assertEquals(SessionStatus.DONE, repository.session(last).status)
        wakeLock.assertBalanced()
    }

    @Test fun startupRecoveryFinishesBeforeNewCaptureAndDeletionIsBlocked() = runTest {
        val controller = controller()
        repository.put(
            Session(
                id = "old", title = "Old", source = SessionSource.MIC, originalName = "old.wav",
                sourceUri = null, createdAt = 0, updatedAt = 0, status = SessionStatus.RECORDING,
            )
        )
        foreground.promoted.complete(Unit)
        val id = controller.startRecording()!!
        controller.delete(id)
        advanceUntilIdle()
        assertEquals(ErrorCode.INTERRUPTED, repository.session("old").errorCode)
        assertEquals(listOf(setOf("old")), repository.orphanCleanups)
        assertEquals(SessionStatus.RECORDING, repository.session(id).status)
        controller.cancel(id)
        advanceUntilIdle()
        wakeLock.assertBalanced()
    }

    @Test fun failAllBeforeWorkerStartsClearsRecordingReservation() = runTest {
        val controller = controller()
        val id = controller.startRecording()!!
        controller.failAll(ErrorCode.INTERRUPTED)
        advanceUntilIdle()
        assertEquals(ErrorCode.INTERRUPTED, repository.session(id).errorCode)
        assertNull(controller.state.value.recording)
        assertFalse(controller.state.value.busy)
        assertFalse(events.contains("capture"))
        wakeLock.assertBalanced()
    }

    @Test fun concurrentRecordActionsAdmitExactlyOneSession() = runTest {
        val controller = controller()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val ids = try {
            (1..8).map { executor.submit<String?> { controller.startRecording() } }.map { it.get() }.filterNotNull()
        } finally { executor.shutdown() }
        assertEquals(1, ids.size)
        controller.cancel(ids.single())
        advanceUntilIdle()
        wakeLock.assertBalanced()
    }

    @Test fun retryDuringCaptureWaitsAndUsesSavedOriginal() = runTest {
        val controller = controller()
        foreground.promoted.complete(Unit)
        transcriber.script = { emit(com.example.speech.api.TranscriptionEvent.Failed(IllegalStateException("test"))) }
        val first = controller.startRecording()!!
        advanceUntilIdle()
        controller.stopRecording(first)
        advanceUntilIdle()
        assertEquals(SessionStatus.FAILED, repository.session(first).status)
        val original = repository.session(first).wavFileName
        val second = controller.startRecording()!!
        controller.retry(first)
        controller.retry(first)
        advanceUntilIdle()
        assertEquals(SessionStatus.QUEUED, repository.session(first).status)
        assertEquals(1, controller.state.value.queuedCount)
        transcriber.script = { emit(com.example.speech.api.TranscriptionEvent.Done(emptyList())) }
        controller.stopRecording(second)
        advanceUntilIdle()
        assertEquals(SessionStatus.DONE, repository.session(first).status)
        assertEquals(SessionStatus.DONE, repository.session(second).status)
        assertEquals(original, repository.session(first).wavFileName)
        assertTrue(audio.conversions.isEmpty())
        wakeLock.assertBalanced()
    }
}
