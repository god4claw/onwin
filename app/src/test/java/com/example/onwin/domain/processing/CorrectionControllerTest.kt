package com.example.onwin.domain.processing

import com.example.speech.api.TextCorrector
import com.example.onwin.domain.model.SessionStatus.CANCELLED
import com.example.onwin.domain.model.SessionStatus.CONVERTING
import com.example.onwin.domain.model.SessionStatus.CORRECTING
import com.example.onwin.domain.model.SessionStatus.DONE
import com.example.onwin.domain.model.SessionStatus.QUEUED
import com.example.onwin.domain.model.SessionStatus.TRANSCRIBING
import com.example.speech.api.SpeechConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * LLM-коррекция в очереди обработки: порядок с падением ASR, отказ модели,
 * отмена и общая загрузка модели на пачку.
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
    fun correctionRewritesFinalText_andRunsAfterAsrRelease() = runTest {
        val corrector = FakeTextCorrector(events).apply {
            behavior = { text -> text.replace("Итог", "Итог (исправлено)") }
        }
        val controller = createController(corrector)

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()

        assertEquals(DONE, repository.session(id).status)
        assertEquals(listOf(QUEUED, CONVERTING, TRANSCRIBING, CORRECTING, DONE), repository.history(id))
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

        assertEquals(DONE, repository.session(id).status)
        assertEquals(ScriptedTranscriber.FINAL, repository.segments(id))
        assertEquals(listOf(QUEUED, CONVERTING, TRANSCRIBING, CORRECTING, DONE), repository.history(id))
        // Модель загрузилась, но ответ упал: текст остался исходным, пачка всё равно выгрузила LLM.
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
    fun cancelDuringCorrection_discardsTextAndWav() = runTest {
        val corrector = FakeTextCorrector(events).apply { behavior = { awaitCancellation() } }
        val controller = createController(corrector)

        val id = controller.enqueueImport(SOURCE)
        advanceUntilIdle()
        assertEquals(CORRECTING, repository.session(id).status)

        controller.cancel(id)
        advanceUntilIdle()

        assertEquals(listOf(QUEUED, CONVERTING, TRANSCRIBING, CORRECTING, CANCELLED), repository.history(id))
        assertTrue(repository.segments(id).isEmpty())
        assertNull(repository.session(id).wavFileName)
        assertEquals(1, corrector.releases)
    }

    @Test
    fun twoJobs_loadCorrectorOnceAndReleaseAfterQueueDrains() = runTest {
        val corrector = FakeTextCorrector(events)
        val controller = createController(corrector)

        val first = controller.enqueueImport("content://test/first.mp3")
        val second = controller.enqueueImport("content://test/second.mp3")
        advanceUntilIdle()

        assertEquals(DONE, repository.session(first).status)
        assertEquals(DONE, repository.session(second).status)
        assertEquals(1, corrector.loads)
        assertEquals(1, corrector.releases)
        // Модель распознавания выгружается перед каждой коррекцией и грузится заново.
        assertEquals(2, transcriber.loads)
        assertEquals(2, transcriber.releases)
    }

    private fun TestScope.createController(corrector: TextCorrector?) = DefaultProcessingController(
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
    )

    private companion object {
        const val SOURCE = "content://test/talk.mp3"
        const val NOW = 1_700_000_000_000L
    }
}
