package com.example.onwin.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.onwin.domain.repository.TranscriptOnboardingRepository
import com.example.onwin.domain.repository.TranscriptOnboardingStep
import com.example.onwin.domain.repository.WelcomeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class WelcomePreferences internal constructor(private val store: DataStore<Preferences>) :
    WelcomeRepository, TranscriptOnboardingRepository {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        PreferenceDataStoreFactory.create {
            // Удаление приложения или очистка данных сбрасывают приветствие.
            File(context.noBackupFilesDir, "welcome.preferences_pb")
        }
    )

    override suspend fun isCompleted(): Boolean = store.data.first()[Completed] == true

    override suspend fun complete() {
        store.edit { it[Completed] = true }
    }

    override val progress = store.data.map { it.onboardingStep() }

    override suspend fun advance(from: TranscriptOnboardingStep) {
        val next = when (from) {
            TranscriptOnboardingStep.TEXT -> "speaker"
            TranscriptOnboardingStep.SPEAKER -> "complete"
            TranscriptOnboardingStep.COMPLETE -> return
        }
        // Keep an accepted tap even if leaving the recording cancels the ViewModel.
        withContext(NonCancellable) {
            store.edit { preferences ->
                if (preferences.onboardingStep() == from) preferences[TranscriptOnboarding] = next
            }
        }
    }

    private fun Preferences.onboardingStep() = when (this[TranscriptOnboarding]) {
        null -> TranscriptOnboardingStep.TEXT
        "speaker" -> TranscriptOnboardingStep.SPEAKER
        else -> TranscriptOnboardingStep.COMPLETE
    }

    private companion object {
        val Completed = booleanPreferencesKey("welcome_completed")
        val TranscriptOnboarding = stringPreferencesKey("transcript_onboarding")
    }
}
