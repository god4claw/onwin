package com.example.onwin.domain.processing

import com.example.speech.api.TextCorrector
import com.example.speech.api.TranscriptAnalyzer
import com.example.onwin.domain.model.SessionStatus.CONVERTING
import com.example.onwin.domain.model.SessionStatus.DONE
import com.example.onwin.domain.model.SessionStatus.QUEUED
import com.example.onwin.domain.model.SessionStatus.TRANSCRIBING
import com.example.speech.api.SpeechConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Автоматический анализ диалога и ручная коррекция готовых реплик.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CorrectionControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val events = mutableListOf<String>()
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

    @After
    fun wakeLockIsBalanced() {
        wakeLock.assertBalanced()
    }

    @Test
    fun correctionRequiresUserAction_andRunsAfterAsrRelease() = runTest {
        val corrector = FakeTextCorrector(events).apply {
            behavior = { text -> text.replace("Итог", "Итог (исправлено)") }
        }
        val controller = createController(corrector)

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        assertEquals(DONE, repository.session(id).status)
        assertEquals(ScriptedTranscriber.FINAL, repository.segments(id))
        assertEquals(0, corrector.loads)

        controller.correctTranscript(id)
        assertTrue(controller.state.value.correction.running)
        controller.correctTranscript(id)
        advanceUntilIdle()

        assertFalse(controller.state.value.correction.running)
        assertFalse(controller.state.value.correction.failed)
        assertEquals(listOf(QUEUED, CONVERTING, TRANSCRIBING, DONE), repository.history(id))
        assertEquals(
            ScriptedTranscriber.FINAL.map { it.copy(text = it.text.replace("Итог", "Итог (исправлено)")) },
            repository.segments(id),
        )
        // GigaAM и 0,5B-модель не должны оказаться в памяти одновременно:
        // release() движка распознавания идёт раньше загрузки корректора.
        val asrRelease = events.indexOf("release")
        val correctorLoad = events.indexOf("corrector load")
        assertTrue("ASR must be released before the LLM loads", asrRelease in 0 until correctorLoad)
        assertEquals(1, corrector.loads)
        assertEquals(1, corrector.releases)
        assertEquals(1, transcriber.releases)
    }

    @Test
    fun correctionFailure_keepsAsrText_andStillCompletes() = runTest {
        val corrector = FakeTextCorrector(events).apply {
            behavior = { error("model crashed") }
        }
        val controller = createController(corrector)

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()
        controller.correctTranscript(id)
        advanceUntilIdle()

        assertEquals(DONE, repository.session(id).status)
        assertEquals(ScriptedTranscriber.FINAL, repository.segments(id))
        assertTrue(controller.state.value.correction.failed)
        assertFalse(controller.state.value.correction.running)
        assertEquals(1, corrector.loads)
        assertEquals(1, corrector.releases)
    }

    @Test
    fun withoutCorrector_statusNeverBecomesCorrecting() = runTest {
        val controller = createController(corrector = null)

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        assertEquals(listOf(QUEUED, CONVERTING, TRANSCRIBING, DONE), repository.history(id))
    }

    @Test
    fun analysis_savesSummaryAndSuggestedNames() = runTest {
        val analyzer = FakeTranscriptAnalyzer()
        val controller = createController(analyzer = analyzer)

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        assertEquals(DONE, repository.session(id).status)
        assertEquals("Обсудили сроки и договорились о встрече.", repository.summary(id))
        assertEquals(mapOf(0 to "Анна"), repository.suggestedNames(id))
        assertEquals(1, analyzer.loads)
        assertEquals(1, analyzer.releases)
        // Анализ идёт по финальным сегментам расшифровки.
        assertEquals(ScriptedTranscriber.FINAL, analyzer.lastSegments)
    }

    @Test
    fun analysisStartsAutomatically_andCorrectionWaitsForModel() = runTest {
        val corrector = FakeTextCorrector().apply {
            behavior = { text -> text.replace("Итог", "Итог (исправлено)") }
        }
        val finishAnalysis = CompletableDeferred<Unit>()
        val analyzer = FakeTranscriptAnalyzer().apply {
            behavior = {
                finishAnalysis.await()
                null
            }
        }
        val controller = createController(corrector, analyzer)

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        assertEquals(DONE, repository.session(id).status)
        assertEquals(ScriptedTranscriber.FINAL, analyzer.lastSegments)
        controller.correctTranscript(id)
        advanceUntilIdle()
        assertTrue(controller.state.value.correction.running)
        assertEquals(0, corrector.loads)

        finishAnalysis.complete(Unit)
        advanceUntilIdle()

        assertFalse(controller.state.value.correction.running)
        assertEquals(1, corrector.loads)
        assertEquals(1, analyzer.loads)
        assertEquals(ScriptedTranscriber.FINAL, analyzer.lastSegments)
        assertNull(repository.summary(id))
    }

    @Test
    fun analysisFailure_keepsTranscriptAndCompletes() = runTest {
        val analyzer = FakeTranscriptAnalyzer().apply { behavior = { error("model crashed") } }
        val controller = createController(analyzer = analyzer)

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        assertEquals(DONE, repository.session(id).status)
        assertEquals(ScriptedTranscriber.FINAL, repository.segments(id))
        assertNull(repository.summary(id))
        assertEquals(1, analyzer.releases)
    }

    @Test
    fun editDuringCorrection_preservesUserText() = runTest {
        val finishCorrection = CompletableDeferred<Unit>()
        val corrector = FakeTextCorrector(events).apply {
            behavior = { text ->
                finishCorrection.await()
                "$text (исправлено)"
            }
        }
        val controller = createController(corrector)

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()
        controller.correctTranscript(id)
        advanceUntilIdle()

        assertTrue(repository.updateSegmentText(id, 0, "Правка пользователя"))
        finishCorrection.complete(Unit)
        advanceUntilIdle()

        assertEquals(DONE, repository.session(id).status)
        assertEquals("Правка пользователя", repository.segments(id).first().text)
        assertTrue(controller.state.value.correction.failed)
        assertEquals(1, corrector.releases)
    }

    @Test
    fun twoJobs_loadAnalyzerOnceAndReleaseAfterQueueDrains() = runTest {
        val analyzer = FakeTranscriptAnalyzer()
        val controller = createController(analyzer = analyzer)

        val first = controller.enqueueImport("content://test/first.mp3")
        val second = controller.enqueueImport("content://test/second.mp3")
        advanceUntilIdle()

        assertEquals(DONE, repository.session(first).status)
        assertEquals(DONE, repository.session(second).status)
        assertEquals(1, analyzer.loads)
        assertEquals(1, analyzer.releases)
        assertEquals(2, transcriber.loads)
        assertEquals(2, transcriber.releases)
    }

    private fun TestScope.createController(
        corrector: TextCorrector? = null,
        analyzer: TranscriptAnalyzer? = null,
    ) = DefaultProcessingController(
        audio = audio,
        repository = repository,
        transcriber = transcriber,
        speechConfig = SpeechConfig(),
        foreground = foreground,
        wakeLock = wakeLock,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
        log = ::println,
        corrector = corrector,
        analyzer = analyzer,
    )

    private companion object {
        const val SOURCE = "content://test/talk.mp3"
        const val NOW = 1_700_000_000_000L
    }
}
