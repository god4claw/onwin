package com.example.onwin.domain.processing

import com.example.onwin.domain.repository.SessionRepository
import com.example.onwin.domain.model.TranscriptSegment
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.SessionDetail
import com.example.onwin.domain.model.SessionListItem
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.model.SpeakerName
import com.example.speech.api.Segment
import com.example.speech.api.SpeechConfig
import com.example.speech.api.Stage
import com.example.speech.api.StreamingEvent
import com.example.speech.api.StreamingTranscriber
import com.example.speech.api.TextCorrector
import com.example.speech.api.Transcriber
import com.example.speech.api.TranscriptAnalyzer
import com.example.speech.api.DialogueNotes
import com.example.speech.api.TranscriptionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.File
import java.util.UUID

/** Хранилище в памяти; помнит историю статусов каждой сессии и общий порядок смен статусов. */
class FakeSessionRepository(private val recordingsDir: File) : SessionRepository {

    private val sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    private val speakerNames = MutableStateFlow<Map<String, Map<Int, String>>>(emptyMap())
    private val summaries = MutableStateFlow<Map<String, String>>(emptyMap())
    private val suggestions = MutableStateFlow<Map<String, Map<Int, String>>>(emptyMap())
    private val segments = mutableMapOf<String, MutableList<Segment>>()
    private val history = mutableMapOf<String, MutableList<SessionStatus>>()

    /** Все смены статусов по порядку: (id, статус). */
    val statusLog = mutableListOf<Pair<String, SessionStatus>>()

    /** Вызовы [deleteOrphanFiles]: какие сессии уже были в хранилище в момент вызова. */
    val orphanCleanups = mutableListOf<Set<String>>()

    /** Кладёт сессию как есть, без записи в историю (состояние «до запуска»). */
    fun put(session: Session, segments: List<Segment> = emptyList()) {
        sessions.value += session.id to session
        this.segments[session.id] = segments.toMutableList()
    }

    fun session(id: String): Session = sessions.value.getValue(id)

    fun exists(id: String): Boolean = id in sessions.value

    fun segments(id: String): List<Segment> = segments[id].orEmpty().toList()

    fun history(id: String): List<SessionStatus> = history[id].orEmpty().toList()

    override fun observeSessions(): Flow<List<SessionListItem>> =
        sessions.map { all -> all.values.map { SessionListItem(it, segments(it.id).size, 0) } }

    override fun observeSession(id: String): Flow<SessionDetail?> =
        combine(sessions, speakerNames, summaries, suggestions) { all, names, notes, suggested ->
            all[id]?.let { session ->
                SessionDetail(
                    session,
                    segments(id).mapIndexed { i, s -> TranscriptSegment(i, s.speaker, s.startMs, s.endMs, s.text) },
                    names[id].orEmpty().map { (speaker, name) -> SpeakerName(speaker, name) },
                    summary = notes[id],
                    suggestedNames = suggested[id].orEmpty().map { (speaker, name) -> SpeakerName(speaker, name) },
                )
            }
        }

    override suspend fun get(id: String): Session? = sessions.value[id]

    override suspend fun hasImportNeedingSource(sourceUri: String): Boolean =
        sessions.value.values.any { it.sourceUri == sourceUri && it.status != SessionStatus.DONE }

    override suspend fun create(session: Session) {
        check(session.id !in sessions.value) { "Duplicate session ${session.id}" }
        put(session)
        record(session.id, session.status)
    }

    override suspend fun rename(id: String, title: String): Boolean {
        if (!exists(id)) return false
        update(id) { it.copy(title = title) }
        return true
    }

    override suspend fun setSpeakerName(id: String, speaker: Int, name: String?) {
        check(exists(id)) { "Missing session $id" }
        val names = speakerNames.value[id].orEmpty()
        val updated = if (name == null) names - speaker else names + (speaker to name)
        speakerNames.value += id to updated
    }

    override suspend fun updateSegmentText(id: String, index: Int, text: String): Boolean =
        error("Unexpected transcript edit in processing test")

    override suspend fun updateStatus(id: String, status: SessionStatus, errorCode: ErrorCode?, errorMessage: String?) {
        update(id) { it.copy(status = status, errorCode = errorCode, errorMessage = errorMessage) }
        record(id, status)
    }

    override suspend fun setWav(id: String, wavFileName: String?, durationMs: Long?) =
        update(id) { it.copy(wavFileName = wavFileName, durationMs = durationMs) }

    override suspend fun appendSegment(id: String, index: Int, segment: Segment) {
        val list = segments.getValue(id)
        check(index == list.size) { "Segment index $index, expected ${list.size}" }
        list += segment
    }

    override suspend fun replaceSegments(id: String, segments: List<Segment>) {
        this.segments[id] = segments.toMutableList()
    }

    override suspend fun clearSegments(id: String) {
        segments[id]?.clear()
    }

    override suspend fun saveAnalysis(id: String, summary: String, speakerNames: Map<Int, String>) {
        check(exists(id)) { "Missing session $id" }
        if (summary.isBlank()) summaries.value -= id else summaries.value += id to summary
        if (speakerNames.isEmpty()) suggestions.value -= id else suggestions.value += id to speakerNames
    }

    override suspend fun clearAnalysis(id: String) {
        summaries.value -= id
        suggestions.value -= id
    }

    /** Заметки LLM в памяти: для проверок без Flow. */
    fun summary(id: String): String? = summaries.value[id]

    /** Предложенные имена в памяти: для проверок без Flow. */
    fun suggestedNames(id: String): Map<Int, String> = suggestions.value[id].orEmpty()

    override suspend fun failUnfinished(errorCode: ErrorCode): Int {
        val unfinished = sessions.value.values.filter { it.status.isActive }
        unfinished.forEach { updateStatus(it.id, SessionStatus.FAILED, errorCode) }
        return unfinished.size
    }

    override suspend fun deleteOrphanFiles(): Int {
        orphanCleanups += sessions.value.keys
        return 0
    }

    override suspend fun delete(id: String) {
        sessions.value[id]?.wavFileName?.let { wavFile(it).delete() }
        sessions.value -= id
        segments -= id
        speakerNames.value -= id
        summaries.value -= id
        suggestions.value -= id
    }

    override fun wavFile(fileName: String): File = File(recordingsDir, fileName)

    private fun update(id: String, change: (Session) -> Session) {
        sessions.value += id to change(sessions.value.getValue(id))
    }

    private fun record(id: String, status: SessionStatus) {
        history.getOrPut(id) { mutableListOf() } += status
        statusLog += id to status
    }
}

/** Конвертер: пишет пустой WAV нужной длительности; [behavior] может зависнуть или бросить. */
class FakeAudioToWav(private val recordingsDir: File) : AudioToWav {

    var durationMs = 30_000L
    var behavior: suspend (onProgress: (Float) -> Unit) -> Unit = { it(0.5f) }

    val conversions = mutableListOf<String>()
    val retained = mutableListOf<String>()
    val released = mutableListOf<String>()

    /** Конвертацию отменили через [CancellationException]. */
    var cancelled = false

    override suspend fun displayName(source: String): String = source.substringAfterLast('/')

    override fun retainAccess(source: String) {
        retained += source
    }

    override fun releaseAccess(source: String) {
        released += source
    }

    override suspend fun convert(source: String, onProgress: (Float) -> Unit): ConvertedWav {
        conversions += source
        try {
            behavior(onProgress)
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        }
        onProgress(1f)
        val file = File(recordingsDir, "${UUID.randomUUID()}.wav")
        file.writeBytes(ByteArray(44 + 3_200))
        return ConvertedWav(file, durationMs)
    }
}

/** [Transcriber] по сценарию [script]; пишет события в общий [events]. */
class ScriptedTranscriber(private val events: MutableList<String>) : Transcriber, StreamingTranscriber {

    var loads = 0
    var releases = 0
    var maxConcurrent = 0
    private var running = 0
    private var calls = 0

    /** С чем завершился каждый Flow (`null` — успешно). */
    val completions = mutableListOf<Throwable?>()

    var load: suspend () -> Unit = {}
    var script: suspend FlowCollector<TranscriptionEvent>.(call: Int) -> Unit = { emitDefault() }
    var liveScript: suspend FlowCollector<StreamingEvent>.(Flow<FloatArray>) -> Unit =
        { error("Unexpected streaming session") }

    override fun transcribeStream(audio: Flow<FloatArray>): Flow<StreamingEvent> = flow {
        events += "stream"
        liveScript(audio)
    }

    override suspend fun load(config: SpeechConfig) {
        loads++
        events += "load"
        load()
    }

    override fun transcribe(
        wav: File,
        @Suppress("UNUSED_PARAMETER") expectedSpeakers: Int?,
    ): Flow<TranscriptionEvent> {
        val call = calls++
        return flow {
            running++
            maxConcurrent = maxOf(maxConcurrent, running)
            events += "transcribe"
            try {
                script(call)
            } finally {
                running--
            }
        }.onCompletion { completions += it }
    }

    override fun release() {
        releases++
        events += "release"
    }

    companion object {
        val FINAL = listOf(Segment(0, 0, 1_000, "Итог один."), Segment(1, 1_500, 3_000, "Итог два."))

        /** Прогресс, два черновых сегмента, `Done` с [FINAL]. */
        suspend fun FlowCollector<TranscriptionEvent>.emitDefault() {
            emit(TranscriptionEvent.Progress(Stage.DIARIZATION, 1f))
            emit(TranscriptionEvent.SegmentReady(Segment(0, 0, 1_000, "черновик один")))
            emit(TranscriptionEvent.Progress(Stage.RECOGNITION, 0.5f))
            emit(TranscriptionEvent.SegmentReady(Segment(1, 1_500, 3_000, "черновик два")))
            emit(TranscriptionEvent.Done(FINAL))
        }
    }
}

/** Запоминает запуски и остановки; [refuse] имитирует запрет системы. */
class FakeForegroundStarter(private val events: MutableList<String>) : ForegroundStarter {

    var refuse = false
    var starts = 0
    var stops = 0

    override fun start(sessionId: String) {
        if (refuse) throw ForegroundStartException(IllegalStateException("App is in background"))
        starts++
        events += "fg start"
    }

    override fun stop() {
        stops++
        events += "fg stop"
    }
}

/** Считает захваты и освобождения. */
class FakeWakeLock : WakeLockHolder {
    var acquires = 0
    var releases = 0
    private var held = false

    override fun acquire() {
        acquires++
        held = true
    }

    override fun release() {
        releases++
        held = false
    }

    fun assertBalanced() {
        assertEquals("acquire/release", acquires, releases)
        assertFalse("still held", held)
    }
}

/**
 * [TextCorrector] по сценарию [behavior]; пишет загрузки и освобождения в общий
 * [events], чтобы тесты видели порядок относительно release() движка.
 */
class FakeTextCorrector(private val events: MutableList<String> = mutableListOf()) : TextCorrector {

    var loads = 0
    var releases = 0

    /** Сценарий правки; по умолчанию возвращает текст как есть. */
    var behavior: suspend (String) -> String = { it }

    override suspend fun load() {
        loads++
        events += "corrector load"
    }

    override fun release() {
        releases++
        events += "corrector release"
    }

    override suspend fun correct(text: String): String = behavior(text)
}

/**
 * [TranscriptAnalyzer] по сценарию [behavior]; по умолчанию возвращает имя и
 * резюме. Запоминает, какие реплики получил: тест проверяет, что анализ идёт
 * уже по исправленному тексту.
 */
class FakeTranscriptAnalyzer : TranscriptAnalyzer {

    var loads = 0
    var releases = 0
    var lastSegments: List<Segment>? = null
    var behavior: suspend (List<Segment>) -> DialogueNotes? = {
        DialogueNotes(speakerNames = mapOf(0 to "Анна"), summary = "Обсудили сроки и договорились о встрече.")
    }

    override suspend fun load() {
        loads++
    }

    override suspend fun analyze(segments: List<Segment>): DialogueNotes? {
        lastSegments = segments
        return behavior(segments)
    }

    override fun release() {
        releases++
    }
}

