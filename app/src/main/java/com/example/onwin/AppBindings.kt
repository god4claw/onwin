package com.example.onwin

import android.content.Context
import com.example.onwin.data.preferences.WelcomePreferences
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.repository.SessionRepository
import com.example.onwin.domain.repository.WelcomeRepository
import com.example.onwin.ui.notification.ServiceForegroundStarter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppBindings {
    @Provides
    fun container(@ApplicationContext context: Context): AppContainer =
        (context as OnWinApp).container

    @Provides
    fun sessionRepository(container: AppContainer): SessionRepository = container.sessionRepository

    @Provides
    fun processingController(container: AppContainer): ProcessingController = container.processingController

    @Provides
    fun foregroundStarter(container: AppContainer): ServiceForegroundStarter = container.foregroundStarter

    @Provides
    fun welcomeRepository(preferences: WelcomePreferences): WelcomeRepository = preferences
}
