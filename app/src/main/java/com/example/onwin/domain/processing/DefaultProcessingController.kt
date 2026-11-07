package com.example.onwin.domain.processing

import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.time.Clock
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * [ProcessingController] с очередью в памяти и статусами в [SessionRepository].
 *
 * - очередь и текущее задание защищены `lock`; под ним же вызываются [ForegroundStarter.start] и
 *   [ForegroundStarter.stop], поэтому сервис стартует и останавливается в том же порядке, что и очередь;
 * - очередь разбирает один работник на пачку заданий: модели грузятся перед первой расшифровкой пачки,
 *   [Transcriber.release] вызывается при опустевшей очереди и в `finally`; двух расшифровок одновременно не бывает;
 * - каждое задание — отдельная корутина: её отмена не задевает работника, а итог (`CANCELLED`/`FAILED`)
 *   записывает работник, даже если задание отменили до старта.
 *
 * Устройство опирается на ProtocolVoice LongTaskRunner, Apache-2.0 (долгие задачи в scope приложения).
 *
 * @param audio входной этап: доступ к исходнику и конвертация.
 * @param repository статусы и сегменты.
 * @param transcriber расшифровка; строго одно задание за раз.
 * @param speechConfig конфигурация для [Transcriber.load].
 * @param foreground запуск и остановка foreground-сервиса.
 * @param wakeLock держит CPU, пока идёт задание.
 * @param dispatcher где выполняется работа.
 * @param clock время создания сессий.
 * @param log журнал с одним тегом; по умолчанию отключён.
 */
class DefaultProcessingController(
    private val audio: AudioToWav,
    private val repository: SessionRepository,
    private val transcriber: Transcriber,
    private val speechConfig: SpeechConfig,
    private val foreground: ForegroundStarter,
    private val wakeLock: WakeLockHolder,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock.systemUTC(),
    private val log: (String) -> Unit = {},
    private val microphone: MicrophoneRecorder? = null,
    private val recordingTitle: (Long) -> String = { DateFormat.getDateTimeInstance().format(Date(it)) }
) : ProcessingController {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log("Unexpected error: $e") }
    )

    private val lock = Any()
    private val queue = ArrayDeque<QueuedJob>()  // под lock
    private var current: ActiveJob? = null       // под lock
    private var workerActive = false             // под lock

    private val _state = MutableStateFlow(ProcessingState())
    override val state: StateFlow<ProcessingState> = _state.asStateFlow()

    /**
     * Сессии, брошенные умершим процессом, становятся `INTERRUPTED`, их осиротевшие WAV и `.part` удаляются.
     * Новые строки и конвертация начинаются только после этого.
     */
    private val recovery: Job = scope.launch {
        val count = repository.failUnfinished(ErrorCode.INTERRUPTED)
        if (count > 0) log("Marked $count unfinished session(s) as INTERRUPTED")
        val deleted = repository.deleteOrphanFiles()
        if (deleted > 0) log("Deleted $deleted orphan file(s) from recordings")
    }

    override fun startRecording(): String? = synchronized(lock) {
        if (workerActive || queue.isNotEmpty() || microphone == null) return null
        val id = UUID.randomUUID().toString()
        val createdAt = clock.millis()
        val title = recordingTitle(createdAt)
        val prepared = scope.async {
            recovery.join()
            repository.create(
                Session(
                    id = id,
                    title = title,
                    source = SessionSource.MIC,
                    originalName = "$id.wav",
                    sourceUri = null,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                    status = SessionStatus.RECORDING
                )
            )
            true
        }
        _state.update { it.copy(recording = RecordingState(id, title)) }
        submit(QueuedJob(id, prepared, CompletableDeferred()))
        id
    }

    override fun stopRecording(sessionId: String): Unit = synchronized(lock) {
        val state = _state.value.recording ?: return
        if (state.sessionId != sessionId || state.phase == RecordingPhase.STOPPING) return
        val queued = current?.takeIf { it.sessionId == sessionId }?.queued
            ?: queue.firstOrNull { it.sessionId == sessionId }
            ?: return
        queued.recordingStop?.complete(Unit)
        _state.update { it.copy(recording = state.copy(phase = RecordingPhase.STOPPING)) }
    }

    override fun enqueueImport(source: String): String = submitImport(source).sessionId

    override suspend fun importIfIdle(source: String): String? {
        val job = synchronized(lock) {
            if (workerActive || queue.isNotEmpty()) return null
            submitImport(source)
        }
        return if (job.awaitPrepared()) job.sessionId else null
    }

    private fun submitImport(source: String): QueuedJob {
        val id = UUID.randomUUID().toString()
        val createdAt = clock.millis()
        val prepared = scope.async {
            recovery.join()
            audio.retainAccess(source)
            val name = audio.displayName(source)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
            repository.create(
                Session(
                    id = id,
                    title = name.substringBeforeLast('.').ifBlank { name },
                    source = SessionSource.IMPORT,
                    originalName = name,
                    sourceUri = source,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                    status = SessionStatus.QUEUED
                )
            )
            true
        }
        return QueuedJob(id, prepared).also(::submit)
    }

    override fun retry(sessionId: String): Unit = synchronized(lock) {
        if (current?.sessionId == sessionId || queue.any { it.sessionId == sessionId }) return
        val prepared = scope.async {
            recovery.join()
            val session = repository.get(sessionId)
            val hasWav = session?.wavFileName
                ?.let(repository::wavFile)
                ?.let { it.isFile && it.length() > WAV_HEADER_BYTES } == true
            if (session == null || !session.canRetry || (session.source == SessionSource.MIC && !hasWav)) {
                log("Retry of $sessionId ignored: status ${session?.status}")
                return@async false
            }
            repository.clearSegments(sessionId)
            repository.updateStatus(sessionId, SessionStatus.QUEUED)
            true
        }
        submit(QueuedJob(sessionId, prepared))
    }

    override fun cancel(sessionId: String) {
        val removed = synchronized(lock) {
            current?.takeIf { it.sessionId == sessionId }?.let { active ->
                active.requestStop(StopRequest(SessionStatus.CANCELLED))
                return
            }
            queue.removeQueued(sessionId)?.also {
                if (it.recordingStop != null) _state.update { state -> state.copy(recording = null) }
            }
        } ?: return
        scope.launch {
            if (removed.awaitPrepared()) repository.updateStatus(sessionId, SessionStatus.CANCELLED)
        }
    }

    override fun delete(sessionId: String) {
        val removed = synchronized(lock) {
            if (current?.sessionId == sessionId || _state.value.recording?.sessionId == sessionId) {
                log("Delete of running $sessionId ignored")
                return
            }
            queue.removeQueued(sessionId)
        }
        scope.launch {
            removed?.awaitPrepared()
            recovery.join()
            repository.get(sessionId)?.sourceUri?.let(audio::releaseAccess)
            repository.delete(sessionId)
            log("Deleted $sessionId")
        }
    }

    override fun failAll(errorCode: ErrorCode) {
        val dropped = synchronized(lock) {
            current?.requestStop(StopRequest(SessionStatus.FAILED, errorCode))
            queue.toList().also {
                queue.clear()
                if (it.any { queued -> queued.recordingStop != null }) {
                    _state.update { state -> state.copy(recording = null) }
                }
                publishQueueSize()
            }
        }
        log("Failing running job and ${dropped.size} queued: $errorCode")
        if (dropped.isEmpty()) return
        scope.launch {
            for (job in dropped) {
                if (job.awaitPrepared()) repository.updateStatus(job.sessionId, SessionStatus.FAILED, errorCode)
            }
        }
    }

    /** Ставит задание в очередь и синхронно запускает сервис; при отказе системы сессия падает. */
    private fun submit(job: QueuedJob) {
        val refusal = synchronized(lock) {
            try {
                if (job.recordingStop != null) {
                    foreground.startMicrophone(job.sessionId)
                } else {
                    foreground.start(job.sessionId)
                }
            } catch (e: ForegroundStartException) {
                return@synchronized e
            }
            queue.addLast(job)
            publishQueueSize()
            if (!workerActive) {
                workerActive = true
                _state.update { it.copy(busy = true) }
                scope.launch { runWorker() }
            }
            null
        } ?: return
        _state.update { if (it.recording?.sessionId == job.sessionId) it.copy(recording = null) else it }
        log("Foreground start refused for ${job.sessionId}: ${refusal.cause}")
        scope.launch {
            if (job.awaitPrepared()) {
                repository.updateStatus(
                    job.sessionId,
                    SessionStatus.FAILED,
                    ErrorCode.FOREGROUND_NOT_ALLOWED,
                    refusal.cause.toString()
                )
            }
        }
    }

    private suspend fun runWorker() {
        val batch = Batch()
        var finished = false
        try {
            while (true) {
                val active = takeNext(batch)
                if (active != null) {
                    execute(active)
                    continue
                }
                releaseModels(batch)
                // Пока выгружались модели, могли прийти новые задания — тогда пачка продолжается.
                if (finishIfIdle()) {
                    finished = true
                    return
                }
            }
        } finally {
            releaseModels(batch)
            if (!finished) {
                synchronized(lock) {
                    workerActive = false
                    _state.update { it.copy(busy = false) }
                    foreground.stop()
                }
            }
        }
    }

    private fun takeNext(batch: Batch): ActiveJob? {
        synchronized(lock) {
            val queued = queue.removeFirstOrNull() ?: return null
            publishQueueSize()
            // LAZY: отмена между этой строкой и стартом не потеряется — итог запишет recordStop.
            val active = ActiveJob(queued)
            active.job = scope.launch(start = CoroutineStart.LAZY) { process(active, batch) }
            current = active
            return active
        }
    }

    private fun finishIfIdle(): Boolean {
        synchronized(lock) {
            if (queue.isNotEmpty()) return false
            workerActive = false
            _state.update { it.copy(busy = false) }
            foreground.stop()
            return true
        }
    }

    private fun releaseModels(batch: Batch) {
        if (!batch.needsRelease) return
        batch.needsRelease = false
        batch.loaded = false
        transcriber.release()
    }

    private suspend fun execute(active: ActiveJob) {
        wakeLock.acquire()
        try {
            active.job.start()
            active.job.join()
            if (active.job.isCancelled) withContext(NonCancellable) { recordStop(active) }
        } finally {
            // Работника отменили посреди задания: дождаться его, чтобы release не пришёлся на расшифровку.
            if (active.job.isActive) withContext(NonCancellable) { active.job.cancelAndJoin() }
            synchronized(lock) { current = null }
            _state.update { it.copy(running = null, recording = null) }
            wakeLock.release()
        }
    }

    /** Записывает итог отменённого задания: `CANCELLED` с очисткой или `FAILED` с сохранением результатов. */
    private suspend fun recordStop(active: ActiveJob) {
        val id = active.sessionId
        val stop = synchronized(lock) { active.stopRequest } ?: StopRequest(SessionStatus.FAILED, ErrorCode.UNKNOWN)
        try {
            if (!active.queued.awaitPrepared()) return
            if (stop.status == SessionStatus.CANCELLED) {
                repository.clearSegments(id)
                active.createdWav?.let { wav ->
                    wav.delete()
                    repository.setWav(id, null, null)
                }
                repository.updateStatus(id, SessionStatus.CANCELLED)
            } else {
                repository.updateStatus(id, SessionStatus.FAILED, stop.errorCode)
            }
            log("Job $id stopped: ${stop.status} ${stop.errorCode ?: ""}")
        } catch (e: Exception) {
            log("Cannot record stop of $id: $e")
        }
    }

    private suspend fun process(
        active: ActiveJob,
        batch: Batch
    ) {
        val id = active.sessionId
        if (!active.queued.awaitPrepared()) return
        val session = repository.get(id) ?: return
        active.title = session.title
        val metrics = JobMetrics()
        try {
            if (active.queued.recordingStop != null) capture(active)
            try { foreground.awaitProcessing() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw StepFailure(ErrorCode.FOREGROUND_NOT_ALLOWED, e) }
            val wav = obtainWav(active, repository.get(id) ?: return, metrics)
            transcribe(active, batch, wav, metrics)
            repository.updateStatus(id, SessionStatus.DONE)
            session.sourceUri?.let(audio::releaseAccess)
            log(metrics.summary(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = e as? StepFailure ?: StepFailure(ErrorCode.UNKNOWN, e)
            log("Job $id failed: ${failure.code}, ${failure.cause}")
            repository.updateStatus(id, SessionStatus.FAILED, failure.code, failure.cause.toString())
        }
    }

    private suspend fun capture(active: ActiveJob) {
        val id = active.sessionId
        val part = repository.wavFile("$id.wav.part")
        val wav = repository.wavFile("$id.wav")
        var committed = false
        try {
            try { foreground.awaitMicrophone() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw StepFailure(ErrorCode.FOREGROUND_NOT_ALLOWED, e) }
            val duration = checkNotNull(microphone).record(part, checkNotNull(active.queued.recordingStop), {
                _state.update { state -> state.copy(recording = state.recording?.let {
                    if (it.phase == RecordingPhase.STARTING) it.copy(phase = RecordingPhase.RECORDING) else it
                }) }
            }, { duration ->
                _state.update { it.copy(recording = it.recording?.copy(elapsedMs = duration)) }
            })
            currentCoroutineContext().ensureActive()
            // После фиксации WAV — оригинал: отмена не должна его удалять.
            withContext(NonCancellable) {
                if (!part.renameTo(wav)) throw IOException("Cannot commit recording")
                repository.setWav(id, wav.name, duration)
                committed = true
                repository.updateStatus(id, SessionStatus.QUEUED)
            }
        } catch (e: RecordingException) {
            throw StepFailure(e.code, e)
        } catch (e: IOException) {
            throw StepFailure(ErrorCode.STORAGE_FULL, e)
        } finally {
            part.delete()
            if (!committed) wav.delete()
            _state.update { it.copy(recording = null) }
        }
    }

    /** Существующий WAV (повтор) или новый из исходника. */
    private suspend fun obtainWav(
        active: ActiveJob,
        session: Session,
        metrics: JobMetrics
    ): File {
        val existing = session.wavFileName?.let(repository::wavFile)
        if (existing != null) {
            if (session.durationMs != null && existing.isFile && existing.length() > WAV_HEADER_BYTES) {
                log("Job ${session.id}: reusing ${existing.name}")
                metrics.audioMs = session.durationMs
                return existing
            }
            existing.delete()
        }
        val source = session.sourceUri
            ?: throw StepFailure(ErrorCode.SOURCE_UNAVAILABLE, IllegalStateException("Session has no source URI"))
        repository.updateStatus(session.id, SessionStatus.CONVERTING)
        report(active, ProcessingStage.CONVERTING, null)
        val mark = TimeSource.Monotonic.markNow()
        val result = try {
            audio.convert(source) { fraction -> report(active, ProcessingStage.CONVERTING, fraction) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportConversionException) {
            throw StepFailure(e.code, e.cause)
        } catch (e: Exception) {
            throw StepFailure(ErrorCode.UNKNOWN, e)
        }
        active.createdWav = result.file
        metrics.conversion = mark.elapsedNow()
        metrics.audioMs = result.durationMs
        repository.setWav(session.id, result.file.name, result.durationMs)
        return result.file
    }

    private suspend fun transcribe(
        active: ActiveJob,
        batch: Batch,
        wav: File,
        metrics: JobMetrics
    ) {
        val id = active.sessionId
        repository.updateStatus(id, SessionStatus.TRANSCRIBING)
        try {
            if (!batch.loaded) {
                report(active, ProcessingStage.LOADING_MODELS, null)
                val mark = TimeSource.Monotonic.markNow()
                batch.needsRelease = true
                transcriber.load(speechConfig)
                batch.loaded = true
                metrics.load = mark.elapsedNow()
            }
            report(active, ProcessingStage.DIARIZATION, 0f)
            val mark = TimeSource.Monotonic.markNow()
            var index = 0
            var done = false
            transcriber.transcribe(wav).collect { event ->
                when (event) {
                    is TranscriptionEvent.Progress -> report(active, event.stage.toProcessingStage(), event.fraction)
                    // Каждый сегмент сразу в БД: виден в UI и переживает смерть процесса.
                    is TranscriptionEvent.SegmentReady -> repository.appendSegment(id, index++, event.segment)
                    is TranscriptionEvent.Done -> {
                        repository.replaceSegments(id, event.segments)
                        done = true
                    }
                    is TranscriptionEvent.Failed -> throw StepFailure(ErrorCode.TRANSCRIPTION_FAILED, event.cause)
                }
            }
            if (!done) throw IllegalStateException("Transcription completed without Done")
            metrics.transcription = mark.elapsedNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StepFailure) {
            throw e
        } catch (e: Exception) {
            throw StepFailure(ErrorCode.TRANSCRIPTION_FAILED, e)
        }
    }

    private fun report(
        active: ActiveJob,
        stage: ProcessingStage,
        fraction: Float?
    ) {
        _state.update { it.copy(running = RunningJob(active.sessionId, active.title, stage, fraction)) }
    }

    /** Вызывать под lock. */
    private fun publishQueueSize() {
        _state.update { it.copy(queuedCount = queue.size) }
    }

    /** Вызывать под lock. */
    private fun ArrayDeque<QueuedJob>.removeQueued(sessionId: String): QueuedJob? =
        firstOrNull { it.sessionId == sessionId }?.also {
            remove(it)
            publishQueueSize()
        }

    /** `true`, если строка сессии готова к обработке; ошибка подготовки пишется в журнал. */
    private suspend fun QueuedJob.awaitPrepared(): Boolean = try {
        prepared.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Cannot prepare $sessionId: $e")
        false
    }

    private fun Stage.toProcessingStage(): ProcessingStage = when (this) {
        Stage.DIARIZATION -> ProcessingStage.DIARIZATION
        Stage.RECOGNITION -> ProcessingStage.RECOGNITION
    }

    /** Задание в очереди; [prepared] — строка в БД готова (`false` — обрабатывать нечего). */
    private class QueuedJob(
        val sessionId: String,
        val prepared: Deferred<Boolean>,
        val recordingStop: CompletableDeferred<Unit>? = null
    )

    /** Задание в работе. Поля, кроме [stopRequest], пишет корутина задания, читает работник после `join`. */
    private class ActiveJob(
        val queued: QueuedJob
    ) {
        val sessionId: String get() = queued.sessionId
        lateinit var job: Job
        var title: String = ""
        var createdWav: File? = null
        var stopRequest: StopRequest? = null  // под lock

        /** Вызывать под lock. Первая причина остановки побеждает. */
        fun requestStop(request: StopRequest) {
            if (stopRequest == null) stopRequest = request
            job.cancel()
        }
    }

    private data class StopRequest(
        val status: SessionStatus,
        val errorCode: ErrorCode? = null
    )

    /** Состояние моделей в пределах одной пачки заданий. */
    private class Batch {
        var loaded = false

        // Контракт требует release всегда: в конце пачки он вызывается, даже если load не понадобился.
        var needsRelease = true
    }

    private class StepFailure(
        val code: ErrorCode,
        override val cause: Throwable
    ) : Exception(cause)

    private class JobMetrics {
        private val started = TimeSource.Monotonic.markNow()
        var audioMs = 0L
        var conversion: Duration? = null
        var load: Duration? = null
        var transcription: Duration? = null

        fun summary(id: String): String {
            val totalMs = started.elapsedNow().inWholeMilliseconds
            val transcriptionMs = transcription?.inWholeMilliseconds ?: 0
            return String.format(
                Locale.ROOT,
                "Job %s done: audio=%d ms, conversion=%s, load=%s, transcription=%d ms, total=%d ms, " +
                    "RTF total=%.3f, RTF transcription=%.3f",
                id, audioMs, conversion?.inWholeMilliseconds?.let { "$it ms" } ?: "reused",
                load?.inWholeMilliseconds?.let { "$it ms" } ?: "cached",
                transcriptionMs, totalMs, totalMs.toDouble() / audioMs, transcriptionMs.toDouble() / audioMs
            )
        }
    }

    private companion object {
        const val DEFAULT_NAME = "audio"
        const val WAV_HEADER_BYTES = 44L
    }
}
