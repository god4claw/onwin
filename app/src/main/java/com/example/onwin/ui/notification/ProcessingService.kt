package com.example.onwin.ui.notification

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.processing.ForegroundStartException
import com.example.onwin.domain.processing.ProcessingController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Тонкая foreground-оболочка над [ProcessingController]: держит процесс на переднем плане,
 * показывает состояние контроллера в уведомлении и передаёт ему «Отменить» из уведомления.
 * Логики обработки здесь нет.
 *
 * Тип при записи: `microphone`; при параллельной обработке сервис использует оба типа.
 * Тип при обработке: `mediaProcessing` на API 35+ (долгая обработка медиафайлов), `dataSync` на API 29–34,
 * где `mediaProcessing` ещё нет. Сервис останавливается сам, когда [ServiceForegroundStarter.keepForeground]
 * сброшен, — так `stopSelf(startId)` не обгоняет `startForeground`.
 *
 * Structure informed by ProtocolVoice ProcessingService, Apache-2.0 (типизированный FGS, START_NOT_STICKY).
 */
@AndroidEntryPoint
class ProcessingService : Service() {

    private val scope = MainScope()
    @Inject lateinit var controller: ProcessingController
    @Inject lateinit var starter: ServiceForegroundStarter
    private lateinit var notifications: ProcessingNotifications
    private var lastStartId = 0
    private var observing = false
    private var foregroundEntered = false
    private var appliedRequest: ForegroundRequest? = null

    override fun onCreate() {
        super.onCreate()
        notifications = ProcessingNotifications(this).apply { createChannel() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Сразу: после startForegroundService у сервиса на это считанные секунды.
        val request = starter.currentRequest()
        if (request == null) { stopSelf(startId); return START_NOT_STICKY }
        if (!applyRequest(request)) {
            controller.failAll(ErrorCode.FOREGROUND_NOT_ALLOWED)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            intent.getStringExtra(EXTRA_SESSION_ID)?.let(controller::stopRecording)
            notifications.show(controller.state.value)
        }
        if (intent?.action == ACTION_CANCEL) {
            val state = controller.state.value
            val id = intent.getStringExtra(EXTRA_SESSION_ID)
            if (id != null && state.running?.sessionId == id && state.recording?.sessionId != id) {
                controller.cancel(id)
            }
        }
        if (!observing) {
            observing = true
            observe()
        }
        // Задание могло закончиться раньше, чем дошла эта команда.
        if (!starter.keepForeground.value) stopSelf(startId)
        return START_NOT_STICKY
    }

    /** Android 15+: лимит 6 ч в сутки на `dataSync`/`mediaProcessing`. Остановиться нужно за секунды. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground time limit reached, type=$fgsType")
        controller.failAll(ErrorCode.INTERRUPTED)
        stopSelf()
    }

    override fun onDestroy() {
        if (appliedRequest?.let(starter::ownsForegroundRun) == true) {
            controller.failAll(ErrorCode.INTERRUPTED)
        }
        scope.cancel()
        NotificationManagerCompat.from(this).cancel(ProcessingNotifications.NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun applyRequest(request: ForegroundRequest): Boolean {
        if (appliedRequest === request) return true
        val entered = enterForeground(request)
        // A denied microphone upgrade leaves an existing processing foreground service running.
        if (!entered && !(request.microphone && foregroundEntered)) return false
        foregroundEntered = true
        appliedRequest = request
        return true
    }

    private fun enterForeground(request: ForegroundRequest): Boolean = try {
        val microphoneType = if (request.microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        val processingType = when {
            !request.processing -> 0
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // minSdk is 29: use the platform overload directly. ServiceCompat masks out
        // mediaProcessing even on API 35+, turning that type into NONE.
        startForeground(
            ProcessingNotifications.NOTIFICATION_ID,
            notifications.build(controller.state.value),
            microphoneType or processingType,
        )
        request.promoted()
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException: приложение в фоне или исчерпан лимит времени.
        request.refused(ForegroundStartException(e))
        Log.w(TAG, "startForeground refused", e)
        false
    } catch (e: SecurityException) {
        request.refused(ForegroundStartException(e))
        Log.e(TAG, "startForeground failed: missing foreground service permission", e)
        false
    }

    private fun observe() {
        scope.launch {
            starter.request.collect { request ->
                if (request != null && !applyRequest(request)) {
                    controller.failAll(ErrorCode.FOREGROUND_NOT_ALLOWED)
                    stopSelf()
                }
            }
        }
        scope.launch {
            controller.state.collect { state ->
                notifications.show(state)
                // Не чаще раза в секунду; StateFlow после паузы отдаст последнее состояние.
                delay(NOTIFICATION_INTERVAL_MS)
            }
        }
        scope.launch {
            starter.keepForeground.collect { keep -> if (!keep) stopSelf(lastStartId) }
        }
    }

    companion object {
        private const val TAG = "ProcessingService"
        private const val ACTION_START = "com.example.onwin.action.START_PROCESSING"
        private const val ACTION_STOP = "com.example.onwin.action.STOP_RECORDING"
        private const val ACTION_CANCEL = "com.example.onwin.action.CANCEL_PROCESSING"
        private const val EXTRA_SESSION_ID = "com.example.onwin.extra.SESSION_ID"
        private const val NOTIFICATION_INTERVAL_MS = 1_000L

        /** Intent для `startForegroundService`. */
        fun startIntent(context: Context, sessionId: String): Intent =
            Intent(context, ProcessingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)

        fun stopRecordingIntent(context: Context, sessionId: String): Intent =
            sessionActionIntent(context, ACTION_STOP, sessionId)

        /** Intent действия «Отменить» для сессии [sessionId]. */
        fun cancelIntent(context: Context, sessionId: String): Intent =
            sessionActionIntent(context, ACTION_CANCEL, sessionId)

        private fun sessionActionIntent(context: Context, action: String, sessionId: String): Intent =
            Intent(context, ProcessingService::class.java)
                .setAction(action)
                .setData(Uri.fromParts("onwin-session", sessionId, null))
                .putExtra(EXTRA_SESSION_ID, sessionId)
    }
}
