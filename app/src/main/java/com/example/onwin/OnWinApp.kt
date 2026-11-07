package com.example.onwin

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Приложение: владеет [AppContainer]. */
@HiltAndroidApp
class OnWinApp : Application() {

    /** Зависимости приложения; доступны после `onCreate`. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
