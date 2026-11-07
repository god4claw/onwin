package com.example.onwin.ui.notification

import android.content.Context
import androidx.core.content.ContextCompat
import com.example.onwin.domain.processing.ForegroundStartException
import com.example.onwin.domain.processing.ForegroundStarter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ForegroundStarter] для [ProcessingService].
 *
 * [stop] не вызывает `stopService`: остановка до `startForeground` роняет приложение
 * («did not then call startForeground»), а успеть его вызвать сервис может не всегда — задание бывает
 * короче, чем доставка `onStartCommand`. Поэтому сервис сам следит за [keepForeground] и
 * останавливается через `stopSelf(startId)`, когда флаг сброшен.
 *
 * @param context контекст приложения.
 */
class ServiceForegroundStarter(private val context: Context) : ForegroundStarter {
    private val _keepForeground = MutableStateFlow(false)
    val keepForeground: StateFlow<Boolean> = _keepForeground.asStateFlow()
    private val _request = MutableStateFlow<ForegroundRequest?>(null)
    internal val request: StateFlow<ForegroundRequest?> = _request.asStateFlow()

    @Synchronized
    internal fun currentRequest(): ForegroundRequest? = _request.value

    @Synchronized
    internal fun ownsForegroundRun(applied: ForegroundRequest): Boolean =
        _keepForeground.value && _request.value?.processingReady === applied.processingReady

    @Synchronized
    override fun start(sessionId: String) {
        if (_keepForeground.value) return
        launch(sessionId, ForegroundRequest(microphone = false, processing = true))
    }

    @Synchronized
    override fun startMicrophone(sessionId: String) {
        val existing = _request.value.takeIf { _keepForeground.value }
        launch(
            sessionId,
            ForegroundRequest(
                microphone = true,
                processing = existing?.processing == true,
                processingReady = existing?.processingReady ?: CompletableDeferred(),
            ),
        )
    }

    private fun launch(sessionId: String, request: ForegroundRequest) {
        try {
            ContextCompat.startForegroundService(context, ProcessingService.startIntent(context, sessionId))
        } catch (e: RuntimeException) {
            throw ForegroundStartException(e)
        }
        _request.value = request
        _keepForeground.value = true
    }

    override suspend fun awaitMicrophone() {
        val request = checkNotNull(_request.value)
        check(request.microphone)
        if (withTimeoutOrNull(10_000) { request.microphoneReady.await(); true } != true)
            throw ForegroundStartException(IllegalStateException("Microphone foreground promotion timed out"))
    }

    override suspend fun awaitProcessing() {
        val request = checkNotNull(_request.value)
        check(request.processing)
        if (withTimeoutOrNull(10_000) { request.processingReady.await(); true } != true)
            throw ForegroundStartException(IllegalStateException("Processing foreground promotion timed out"))
    }

    @Synchronized
    override fun finishMicrophone() {
        val existing = _request.value ?: return
        if (!existing.microphone) return
        _request.value = ForegroundRequest(
            microphone = false,
            processing = true,
            processingReady = existing.processingReady,
        )
    }

    @Synchronized
    override fun stop() {
        // Retain the request until a pending onStartCommand has fulfilled startForegroundService.
        _keepForeground.value = false
    }
}

/** Processing readiness survives microphone upgrades and downgrades of the same service run. */
internal class ForegroundRequest(
    val microphone: Boolean,
    val processing: Boolean,
    val processingReady: CompletableDeferred<Unit> = CompletableDeferred(),
) {
    val microphoneReady = CompletableDeferred<Unit>()

    fun promoted() {
        if (microphone) microphoneReady.complete(Unit)
        if (processing) processingReady.complete(Unit)
    }

    fun refused(error: ForegroundStartException) {
        if (microphone) microphoneReady.completeExceptionally(error)
        else processingReady.completeExceptionally(error)
    }
}
