package com.example.onwin.domain.processing

import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.repository.SessionRepository
import com.example.speech.api.Segment
import com.example.speech.api.SpeechConfig
import com.example.speech.api.Stage
import com.example.speech.api.StreamingEvent
import com.example.speech.api.StreamingTranscriber
import com.example.speech.api.TextCorrector
import com.example.speech.api.Transcriber
import com.example.speech.api.TranscriptionEvent
import com.example.speech.api.TranscriptAnalyzer
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * [ProcessingController] с очередью в памяти и статусами в [SessionRepository].
 *
 * Устройство:
 * - очередь и текущее задание защищены `lock`; под ним же вызываются [ForegroundStarter.start] и
 *   [ForegroundStarter.stop], поэтому запуски и остановки сервиса идут в том же порядке, что и изменения очереди;
 * - очередь разбирает один работник на пачку заданий. Модели грузятся перед первой расшифровкой пачки,
 *   [Transcriber.release] вызывается, когда очередь опустела, и в `finally`. Новый работник появляется,
 *   только когда прежний закончил, поэтому двух расшифровок одновременно не бывает;
 * - захват микрофона выполняется независимо и резервирует место в FIFO. Живой режим читает
 *   растущий WAV с начала файла, когда очередь доходит до записи;
 * - каждое задание — отдельная корутина. Её отмена не задевает работника, а итог отмены (`CANCELLED` или
 *   `FAILED`) записывает работник — даже если задание отменили до старта.
 *
 * Structure informed by ProtocolVoice LongTaskRunner, Apache-2.0 (долгие задачи в scope приложения).
 *
 * @param audio входной этап: доступ к исходнику и конвертация.
 * @param repository статусы и сегменты.
 * @param transcriber расшифровка; используется строго по одному заданию.
 * @param speechConfig конфигурация для [Transcriber.load].
 * @param foreground запуск и остановка foreground-сервиса.
 * @param wakeLock держит CPU, пока идёт задание.
 * @param dispatcher где выполняется работа.
 * @param clock время создания сессий.
 * @param log журнал с одним тегом; по умолчанию отключён.
 * @param livePcm источник PCM для живого режима. Живой и файловый режимы используют один транскрибер.
 * @param corrector необязательная постобработка текста локальной LLM. Запускается после расшифровки
 *   и после [Transcriber.release]: ASR и LLM не должны держать память одновременно.
 * @param analyzer необязательный разбор диалога той же LLM: имена спикеров и резюме.
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
    private val recordingTitle: (Long) -> String = {
        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
    },
    private val livePcm: LivePcmSource? = null,
    private val onTranscriptionCompleted: (String) -> Unit = {},
    private val corrector: TextCorrector? = null,
    private val analyzer: TranscriptAnalyzer? = null,
) : ProcessingController {

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log("Unexpected error: $e") },
    )

    private val lock = Any()
    private val queue = ArrayDeque<QueuedJob>()  // под lock
    private var current: ActiveJob? = null       // под lock
    private var capture: ActiveCapture? = null  // под lock
    private var workerActive = false             // под lock
    private var wakeLockUsers = 0                // под lock
    private val sourceAccess = Mutex()
    private val streaming = transcriber as? StreamingTranscriber

    private val _state = MutableStateFlow(ProcessingState())
    override val state: StateFlow<ProcessingState> = _state.asStateFlow()

    /**
     * Сессии, брошенные умершим процессом, становятся `INTERRUPTED`, а его осиротевшие WAV и `.part` удаляются.
     * Новые строки пишутся и конвертация начинается только после этого.
     */
    private val recovery: Job = scope.launch {
        val count = repository.failUnfinished(ErrorCode.INTERRUPTED)
        if (count > 0) log("Marked $count unfinished session(s) as INTERRUPTED")
        val deleted = repository.deleteOrphanFiles()
        if (deleted > 0) log("Deleted $deleted orphan file(s) from recordings")
    }

    override fun startRecording(liveTranscription: Boolean): String? = synchronized(lock) {
        if (capture != null || microphone == null) return null
        val live = liveTranscription && streaming != null && livePcm != null
        val id = UUID.randomUUID().toString()
        val createdAt = clock.millis()
        val title = recordingTitle(createdAt)
        val prepared = scope.async {
            recovery.join()
            repository.create(
                Session(
                    id = id, title = title, source = SessionSource.MIC,
                    originalName = "$id.wav", sourceUri = null,
                    createdAt = createdAt, updatedAt = createdAt, status = SessionStatus.RECORDING,
                )
            )
            true
        }
        try {
            foreground.startMicrophone(id, processing = live)
        } catch (e: ForegroundStartException) {
            recordForegroundRefusal(QueuedJob(id, prepared), e)
            return id
        }
        val active = ActiveCapture(id, prepared, live)
        active.job = scope.launch(start = CoroutineStart.LAZY) {
            if (prepared.await() && captureAudio(active)) active.saved = true
        }
        capture = active
        _state.update { it.copy(recording = RecordingState(id, title)) }
        acquireWakeLock()
        addToQueue(if (live) QueuedJob(id, prepared, active) else QueuedJob(id, active.ready))
        scope.launch { finishCapture(active) }
        id
    }

    override fun stopRecording(sessionId: String): Unit = synchronized(lock) {
        val state = _state.value.recording ?: return
        if (state.sessionId != sessionId || state.phase == RecordingPhase.STOPPING) return
        _state.update { it.copy(recording = state.copy(phase = RecordingPhase.STOPPING)) }
        capture?.stop?.complete(Unit)
    }

    override fun enqueueImport(source: String): String = submitImport(source).sessionId

    override suspend fun importAudio(source: String): ImportSubmission? {
        val (job, openDetails) = synchronized(lock) {
            val idle = !workerActive
            submitImport(source) to idle
        }
        return if (job.awaitPrepared()) ImportSubmission(job.sessionId, openDetails) else null
    }

    private fun submitImport(source: String): QueuedJob {
        val id = UUID.randomUUID().toString()
        val createdAt = clock.millis()
        val prepared = scope.async {
            recovery.join()
            val name = audio.displayName(source)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
            sourceAccess.withLock {
                audio.retainAccess(source)
                try {
                    repository.create(
                        Session(
                            id = id,
                            title = name.substringBeforeLast('.').ifBlank { name },
                            source = SessionSource.IMPORT,
                            originalName = name,
                            sourceUri = source,
                            createdAt = createdAt,
                            updatedAt = createdAt,
                            status = SessionStatus.QUEUED,
                        )
                    )
                } catch (e: Exception) {
                    releaseUnusedSource(source)
                    throw e
                }
            }
            true
        }
        return QueuedJob(id, prepared).also(::submit)
    }

    override fun retry(sessionId: String): Unit = synchronized(lock) {
        if (current?.sessionId == sessionId || queue.any { it.sessionId == sessionId }) return
        val prepared = scope.async {
            recovery.join()
            val session = repository.get(sessionId)
            if (session == null || !session.canRetry || (session.source == SessionSource.MIC &&
                    session.wavFileName?.let(repository::wavFile)
                        ?.let { it.isFile && it.length() > WAV_HEADER_BYTES } != true)
            ) {
                log("Retry of $sessionId ignored: status ${session?.status}")
                return@async false
            }
            repository.clearSegments(sessionId)
            repository.clearAnalysis(sessionId)
            repository.updateStatus(sessionId, SessionStatus.QUEUED)
            true
        }
        submit(QueuedJob(sessionId, prepared))
    }

    override fun cancel(sessionId: String) {
        val removed = synchronized(lock) {
            capture?.takeIf { it.sessionId == sessionId }?.let {
                it.requestStop(StopRequest(SessionStatus.CANCELLED))
                return
            }
            current?.takeIf { it.sessionId == sessionId }?.let { active ->
                active.requestStop(StopRequest(SessionStatus.CANCELLED))
                return
            }
            queue.removeQueued(sessionId)
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
            sourceAccess.withLock {
                val source = repository.get(sessionId)?.sourceUri
                repository.delete(sessionId)
                source?.let { releaseUnusedSource(it) }
            }
            log("Deleted $sessionId")
        }
    }

    override fun failAll(errorCode: ErrorCode) {
        val dropped = synchronized(lock) {
            val stop = StopRequest(SessionStatus.FAILED, errorCode)
            capture?.requestStop(stop)
            current?.takeUnless { it.sessionId == capture?.sessionId }?.requestStop(stop)
            queue.filterNot { it.sessionId == capture?.sessionId }.also {
                val recording = queue.firstOrNull { it.sessionId == capture?.sessionId }
                queue.clear()
                // Keep the reservation until capture cleanup, so the service and worker stay alive.
                recording?.let(queue::addLast)
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
                foreground.start(job.sessionId)
            } catch (e: ForegroundStartException) {
                return@synchronized e
            }
            addToQueue(job)
            null
        } ?: return
        recordForegroundRefusal(job, refusal)
    }

    private fun addToQueue(job: QueuedJob) {
        queue.addLast(job)
        publishQueueSize()
        if (!workerActive) {
            workerActive = true
            _state.update { it.copy(busy = true) }
            scope.launch { runWorker() }
        }
    }

    private fun recordForegroundRefusal(job: QueuedJob, refusal: ForegroundStartException) {
        log("Foreground start refused for ${job.sessionId}: ${refusal.cause}")
        scope.launch {
            if (job.awaitPrepared()) {
                repository.updateStatus(
                    job.sessionId,
                    SessionStatus.FAILED,
                    ErrorCode.FOREGROUND_NOT_ALLOWED,
                    refusal.cause.toString(),
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
        releaseLlm(batch)
        releaseTranscriber(batch)
    }

    /** Выгружает ASR: перед LLM и в конце пачки. */
    private fun releaseTranscriber(batch: Batch) {
        if (!batch.needsRelease) return
        batch.needsRelease = false
        batch.loaded = false
        transcriber.release()
    }

    private suspend fun execute(active: ActiveJob) {
        synchronized(lock) { acquireWakeLock() }
        try {
            active.job.start()
            active.job.join()
            if (active.job.isCancelled && !active.done) withContext(NonCancellable) { recordStop(active) }
        } finally {
            // Работника отменили посреди задания: дождаться его, чтобы release не пришёлся на расшифровку.
            if (active.job.isActive) withContext(NonCancellable) { active.job.cancelAndJoin() }
            synchronized(lock) {
                current = null
                _state.update { it.copy(running = null) }
                releaseWakeLock()
            }
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
                repository.clearAnalysis(id)
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

    private suspend fun process(active: ActiveJob, batch: Batch) {
        val id = active.sessionId
        if (!active.queued.awaitPrepared()) return
        val session = repository.get(id) ?: return
        active.title = session.title
        val metrics = JobMetrics()
        try {
            val liveCapture = active.queued.liveCapture
            if (liveCapture != null) {
                if (!transcribeLive(active, batch, liveCapture, metrics)) return
            } else {
                awaitProcessing()
                val wav = obtainWav(active, session, metrics)
                transcribe(active, batch, wav, metrics)
            }
            correct(active, batch, metrics)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                repository.updateStatus(id, SessionStatus.DONE)
                active.done = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = e as? StepFailure ?: StepFailure(ErrorCode.UNKNOWN, e)
            log("Job $id failed: ${failure.code}, ${failure.cause}")
            repository.updateStatus(id, SessionStatus.FAILED, failure.code, failure.cause.toString())
            return
        }
        onTranscriptionCompleted(id)
        session.sourceUri?.let { source ->
            sourceAccess.withLock { releaseUnusedSource(source) }
        }
        log(metrics.summary(id))
    }

    private suspend fun finishCapture(active: ActiveCapture) {
        active.job.start()
        active.job.join()
        try {
            val stop = synchronized(lock) {
                active.stopRequest.also { request ->
                    if (request == null) completeCapture(active, ready = active.saved)
                }
            }
            if (stop != null && active.prepared.await()) {
                repository.updateStatus(active.sessionId, stop.status, stop.errorCode)
            }
        } finally {
            synchronized(lock) {
                if (capture === active) completeCapture(active, ready = false)
            }
        }
    }

    /** Atomically hand the saved WAV to the worker, or tell it to skip this reservation. */
    private fun completeCapture(active: ActiveCapture, ready: Boolean) {
        foreground.finishMicrophone()
        capture = null
        _state.update { it.copy(recording = null) }
        releaseWakeLock()
        active.started.complete(false)
        active.ready.complete(ready)
    }

    private fun acquireWakeLock() {
        if (wakeLockUsers++ == 0) wakeLock.acquire()
    }

    private fun releaseWakeLock() {
        if (--wakeLockUsers == 0) wakeLock.release()
    }

    /** Caller holds sourceAccess, so a new import cannot retain a grant just before we release it. */
    private suspend fun releaseUnusedSource(source: String) {
        try {
            if (!repository.hasImportNeedingSource(source)) audio.releaseAccess(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Cannot release import access: $e")
        }
    }

    private suspend fun captureAudio(active: ActiveCapture): Boolean {
        val id = active.sessionId
        val part = repository.wavFile("$id.wav.part")
        val wav = repository.wavFile("$id.wav")
        var committed = false
        try {
            try {
                foreground.awaitMicrophone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw StepFailure(ErrorCode.FOREGROUND_NOT_ALLOWED, e)
            }
            val duration = checkNotNull(microphone).record(part, active.stop, {
                active.started.complete(true)
                _state.update { state ->
                    state.copy(recording = state.recording?.let {
                        if (it.phase == RecordingPhase.STARTING) it.copy(phase = RecordingPhase.RECORDING) else it
                    })
                }
            }, { duration ->
                _state.update { it.copy(recording = it.recording?.copy(elapsedMs = duration)) }
            }, active.live)
            currentCoroutineContext().ensureActive()
            // Once committed, the microphone WAV is an original and cancellation must preserve it.
            withContext(NonCancellable) {
                if (!part.renameTo(wav)) throw java.io.IOException("Cannot commit recording")
                repository.setWav(id, wav.name, duration)
                committed = true
                repository.updateStatus(id, SessionStatus.QUEUED)
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = when (e) {
                is RecordingException -> e.code
                is StepFailure -> e.code
                is java.io.IOException -> ErrorCode.STORAGE_FULL
                else -> ErrorCode.UNKNOWN
            }
            repository.updateStatus(id, SessionStatus.FAILED, code, e.toString())
            return false
        } finally {
            part.delete()
            if (!committed) wav.delete()
        }
    }

    /** Существующий WAV (повтор) или новый из исходника. */
    private suspend fun obtainWav(active: ActiveJob, session: Session, metrics: JobMetrics): File {
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

    private suspend fun transcribe(active: ActiveJob, batch: Batch, wav: File, metrics: JobMetrics) {
        val id = active.sessionId
        repository.updateStatus(id, SessionStatus.TRANSCRIBING)
        try {
            loadModels(active, batch, metrics)
            report(active, ProcessingStage.RECOGNITION, null)
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
                        active.finalSegments = event.segments
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

    private suspend fun awaitProcessing() {
        try {
            foreground.awaitProcessing()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StepFailure(ErrorCode.FOREGROUND_NOT_ALLOWED, e)
        }
    }

    private suspend fun loadModels(active: ActiveJob, batch: Batch, metrics: JobMetrics) {
        if (batch.loaded) return
        report(active, ProcessingStage.LOADING_MODELS, null)
        val mark = TimeSource.Monotonic.markNow()
        batch.needsRelease = true
        transcriber.load(speechConfig)
        batch.loaded = true
        metrics.load = mark.elapsedNow()
    }

    private suspend fun transcribeLive(
        active: ActiveJob,
        batch: Batch,
        capture: ActiveCapture,
        metrics: JobMetrics,
    ): Boolean = coroutineScope {
        val id = active.sessionId
        if (!capture.started.await() || (capture.ready.isCompleted && !capture.ready.await())) {
            return@coroutineScope false
        }
        val result = async {
            try {
                awaitProcessing()
                loadModels(active, batch, metrics)
                report(active, ProcessingStage.RECOGNITION, null)
                val mark = TimeSource.Monotonic.markNow()
                val input = checkNotNull(livePcm).read(
                    repository.wavFile("$id.wav.part"), repository.wavFile("$id.wav"),
                    capture.ready,
                )
                var index = 0
                var finalSegments: List<Segment>? = null
                checkNotNull(streaming).transcribeStream(input).collect { event ->
                    when (event) {
                        is StreamingEvent.SpeechStarted -> Unit
                        is StreamingEvent.Utterance -> repository.appendSegment(id, index++, event.segment)
                        is StreamingEvent.Done -> finalSegments = event.segments
                        is StreamingEvent.Failed -> throw StepFailure(ErrorCode.TRANSCRIPTION_FAILED, event.cause)
                    }
                }
                metrics.transcription = mark.elapsedNow()
                Result.success(checkNotNull(finalSegments) { "Streaming completed without Done" })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(
                    e as? StepFailure
                        ?: StepFailure(ErrorCode.TRANSCRIPTION_FAILED, e)
                )
            }
        }
        if (!capture.ready.await()) {
            result.cancelAndJoin()
            if (repository.get(id)?.status == SessionStatus.CANCELLED) repository.clearSegments(id)
            return@coroutineScope false
        }
        repository.updateStatus(id, SessionStatus.TRANSCRIBING)
        metrics.audioMs = repository.get(id)?.durationMs ?: 0
        val segments = result.await().getOrThrow()
        active.finalSegments = segments
        repository.replaceSegments(id, segments)
        true
    }

    /**
     * Необязательный проход LLM по готовой расшифровке: правка реплик и разбор
     * диалога (имена спикеров, резюме).
     *
     * Порядок важен: сначала выгружаются модели ASR, потом грузится LLM — иначе
     * GigaAM, embedding-модель и 0,5B-модель окажутся в памяти одновременно, и
     * на 4-ГБ телефоне процесс убьют прямо во время обработки.
     *
     * Оба шага — улучшение, а не обязательный этап: любая их ошибка оставляет
     * исходный текст ASR, который всегда валиден. Отмена (пользователь нажал
     * «Отменить») пробрасывается наверх и обрабатывается как обычно.
     */
    private suspend fun correct(active: ActiveJob, batch: Batch, metrics: JobMetrics) {
        val corrector = corrector
        val analyzer = analyzer
        if (corrector == null && analyzer == null) return
        val source = active.finalSegments ?: return
        if (source.isEmpty()) return
        val id = active.sessionId
        try {
            releaseTranscriber(batch)
            repository.updateStatus(id, SessionStatus.CORRECTING)
            report(active, ProcessingStage.CORRECTING, 0f)

            var segments = source
            if (corrector != null) {
                loadLlm(batch)
                val mark = TimeSource.Monotonic.markNow()
                segments = correctSegments(corrector, source) { fraction ->
                    report(active, ProcessingStage.CORRECTING, fraction * CORRECTION_SHARE)
                }
                metrics.correction = mark.elapsedNow()
                if (segments != source) repository.replaceSegments(id, segments)
            }

            if (analyzer != null) {
                loadLlm(batch)
                report(active, ProcessingStage.CORRECTING, CORRECTION_SHARE)
                val mark = TimeSource.Monotonic.markNow()
                val notes = analyzer.analyze(segments)
                metrics.analysis = mark.elapsedNow()
                if (notes != null) repository.saveAnalysis(id, notes.summary, notes.speakerNames)
                report(active, ProcessingStage.CORRECTING, 1f)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("LLM post-processing of $id failed: $e")
        }
    }

    /** Правит текст реплик, не трогая спикеров и метки. */
    private suspend fun correctSegments(
        corrector: TextCorrector,
        segments: List<Segment>,
        onProgress: (Float) -> Unit,
    ): List<Segment> = segments.mapIndexed { index, segment ->
        val text = corrector.correct(segment.text)
        onProgress((index + 1f) / segments.size)
        if (text == segment.text) segment else segment.copy(text = text)
    }

    /** Один генератор на корректор и анализатор: грузим один раз на пачку. */
    private suspend fun loadLlm(batch: Batch) {
        if (batch.llmLoaded) return
        corrector?.load()
        analyzer?.load()
        batch.llmLoaded = true
    }

    private fun releaseLlm(batch: Batch) {
        if (!batch.llmLoaded) return
        batch.llmLoaded = false
        corrector?.release()
        analyzer?.release()
    }

    private fun report(active: ActiveJob, stage: ProcessingStage, fraction: Float?) {
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
        val sessionId: String, val prepared: Deferred<Boolean>,
        val liveCapture: ActiveCapture? = null,
    )

    private class ActiveCapture(val sessionId: String, val prepared: Deferred<Boolean>, val live: Boolean) {
        val stop = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Boolean>()
        val ready = CompletableDeferred<Boolean>()
        lateinit var job: Job
        var saved = false
        var stopRequest: StopRequest? = null

        fun requestStop(request: StopRequest) {
            if (stopRequest == null) stopRequest = request
            job.cancel()
        }
    }

    /** Задание в работе. Поля, кроме [stopRequest], пишет корутина задания, читает работник после `join`. */
    private class ActiveJob(val queued: QueuedJob) {
        val sessionId: String get() = queued.sessionId
        lateinit var job: Job
        var title: String = ""
        var createdWav: File? = null
        var done = false

        /** Финальные сегменты из `Done`: вход для LLM-коррекции. */
        var finalSegments: List<Segment>? = null
        var stopRequest: StopRequest? = null  // под lock

        /** Вызывать под lock. Первая причина остановки побеждает. */
        fun requestStop(request: StopRequest) {
            if (stopRequest == null) stopRequest = request
            job.cancel()
        }
    }

    private data class StopRequest(val status: SessionStatus, val errorCode: ErrorCode? = null)

    /** Состояние моделей в пределах одной пачки заданий. */
    private class Batch {
        var loaded = false

        // Контракт требует всегда вызывать release: в конце пачки он вызывается, даже если load не понадобился.
        var needsRelease = true

        /** LLM загружена в этой пачке: один генератор на корректор и анализатор. */
        var llmLoaded = false
    }

    private class StepFailure(val code: ErrorCode, override val cause: Throwable) : Exception(cause)

    private class JobMetrics {
        private val started = TimeSource.Monotonic.markNow()
        var audioMs = 0L
        var conversion: Duration? = null
        var load: Duration? = null
        var transcription: Duration? = null
        var correction: Duration? = null
        var analysis: Duration? = null

        fun summary(id: String): String {
            val totalMs = started.elapsedNow().inWholeMilliseconds
            val transcriptionMs = transcription?.inWholeMilliseconds ?: 0
            return String.format(
                Locale.ROOT,
                "Job %s done: audio=%d ms, conversion=%s, load=%s, transcription=%d ms, correction=%s, " +
                    "analysis=%s, total=%d ms, RTF total=%.3f, RTF transcription=%.3f",
                id, audioMs, conversion?.inWholeMilliseconds?.let { "$it ms" } ?: "reused",
                load?.inWholeMilliseconds?.let { "$it ms" } ?: "cached",
                transcriptionMs, correction?.inWholeMilliseconds?.let { "$it ms" } ?: "skipped",
                analysis?.inWholeMilliseconds?.let { "$it ms" } ?: "skipped",
                totalMs, totalMs.toDouble() / audioMs, transcriptionMs.toDouble() / audioMs,
            )
        }
    }

    private companion object {
        const val DEFAULT_NAME = "audio"
        const val WAV_HEADER_BYTES = 44L

        /** Доля прогресса этапа `CORRECTING`, отведённая правке реплик (остальное — анализ). */
        const val CORRECTION_SHARE = 0.7f
    }
}
