package com.example.onwin.ui.notification

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.onwin.MainActivity
import com.example.onwin.R
import com.example.onwin.domain.processing.ProcessingState
import com.example.onwin.domain.processing.RecordingPhase
import com.example.onwin.ui.formatClock
import com.example.onwin.ui.stageText
import kotlin.math.roundToInt

/** Канал и уведомление обработки: название сессии, этап с прогрессом, «Отменить», тап открывает сессию. */
internal class ProcessingNotifications(private val context: Context) {

    /** Создаёт тихий канал низкой важности; повторный вызов безопасен. */
    fun createChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_processing))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Уведомление для [state]. */
    fun build(state: ProcessingState): Notification {
        val recording = state.recording
        val running = state.running
        val fraction = running?.fraction
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_processing)
            .setContentTitle(
                recording?.title ?: running?.title?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.notification_title_default),
            )
            .setContentText(running?.let { stageText(context.resources, it) } ?: context.getString(R.string.stage_waiting))
            .setProgress(PROGRESS_MAX, ((fraction ?: 0f) * PROGRESS_MAX).roundToInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent(recording?.sessionId ?: running?.sessionId))
        if (state.queuedCount > 0) builder.setSubText(context.getString(R.string.notification_queued, state.queuedCount))
        if (recording != null) {
            builder.setContentText(context.getString(R.string.recording_elapsed, formatClock(recording.elapsedMs)))
                .setProgress(0, 0, false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
            if (recording.phase != RecordingPhase.STOPPING) builder.addAction(
                0, context.getString(R.string.action_stop),
                PendingIntent.getService(context, 2, ProcessingService.stopRecordingIntent(context, recording.sessionId), PENDING_FLAGS),
            )
            builder.addAction(0, context.getString(R.string.recording_cancel), cancelIntent(recording.sessionId))
        }
        if (running != null) {
            builder.addAction(0, context.getString(R.string.processing_cancel), cancelIntent(running.sessionId))
        }
        return builder.build()
    }

    /** Обновляет уведомление; без разрешения `POST_NOTIFICATIONS` молча пропускает — прогресс есть в приложении. */
    fun show(state: ProcessingState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(state))
    }

    private fun openIntent(sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (sessionId != null) intent.putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PENDING_FLAGS)
    }

    private fun cancelIntent(sessionId: String): PendingIntent =
        PendingIntent.getService(context, REQUEST_CANCEL, ProcessingService.cancelIntent(context, sessionId), PENDING_FLAGS)

    companion object {
        /** id уведомления foreground-сервиса. */
        const val NOTIFICATION_ID = 1

        private const val CHANNEL_ID = "processing"
        private const val PROGRESS_MAX = 100
        private const val REQUEST_OPEN = 0
        private const val REQUEST_CANCEL = 1
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
