package com.example.onwin

import android.app.Application
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.ui.notification.ProcessingNotifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OnWinApp : Application() {

    // Accessed on the main thread. A count also handles overlapping activity instances.
    private var visibleActivities = 0
    private var backgroundCompletions = 0

    /** Создаём контроллер при старте приложения, чтобы он сразу восстановил незавершённые сессии. */
    @Inject
    internal lateinit var processingController: ProcessingController

    internal fun onActivityVisibilityChanged(visible: Boolean) {
        visibleActivities += if (visible) 1 else -1
        if (visible) backgroundCompletions = 0
    }

    internal fun onTranscriptionCompleted(sessionId: String) {
        mainExecutor.execute {
            if (visibleActivities == 0) {
                backgroundCompletions++
                ProcessingNotifications(this).showCompletion(sessionId, backgroundCompletions)
            }
        }
    }
}
