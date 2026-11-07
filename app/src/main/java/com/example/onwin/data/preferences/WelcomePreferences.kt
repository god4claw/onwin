package com.example.onwin.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.example.onwin.domain.repository.WelcomeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WelcomePreferences internal constructor(
    private val store: DataStore<Preferences>
) : WelcomeRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        PreferenceDataStoreFactory.create {
            // Удаление приложения или очистка данных сбрасывают приветствие.
            File(context.noBackupFilesDir, "welcome.preferences_pb")
        }
    )

    override suspend fun isCompleted(): Boolean = store.data.first()[Completed] == true

    override suspend fun complete() {
        store.edit { it[Completed] = true }
    }

    private companion object {
        val Completed = booleanPreferencesKey("welcome_completed")
    }
}
