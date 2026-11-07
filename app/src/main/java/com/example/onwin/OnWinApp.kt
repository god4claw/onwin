package com.example.onwin

import android.app.Application
import com.example.onwin.domain.processing.ProcessingController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OnWinApp : Application() {

    /** Создаём контроллер при старте приложения, чтобы он сразу восстановил незавершённые сессии. */
    @Inject
    internal lateinit var processingController: ProcessingController
}
