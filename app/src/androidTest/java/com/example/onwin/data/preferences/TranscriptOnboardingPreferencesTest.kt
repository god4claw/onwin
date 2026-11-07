package com.example.onwin.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.domain.repository.TranscriptOnboardingStep.COMPLETE
import com.example.onwin.domain.repository.TranscriptOnboardingStep.SPEAKER
import com.example.onwin.domain.repository.TranscriptOnboardingStep.TEXT
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptOnboardingPreferencesTest {
    @get:Rule
    val temp = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test
    fun existingUsersStartAtTextAndCompletedStepsSurviveReopening() = runBlocking {
        val file = File(temp.root, "welcome.preferences_pb")

        suspend fun withPreferences(assertions: suspend (WelcomePreferences) -> Unit) {
            val job = Job()
            try {
                val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
                assertions(WelcomePreferences(store))
            } finally {
                job.cancelAndJoin()
            }
        }

        withPreferences { preferences ->
            preferences.complete()
        }
        withPreferences { preferences ->
            assertEquals(TEXT, preferences.progress.first())
            preferences.advance(SPEAKER)
            assertEquals(TEXT, preferences.progress.first())
            preferences.advance(TEXT)
            preferences.advance(TEXT)
            assertEquals(SPEAKER, preferences.progress.first())
        }
        withPreferences { preferences ->
            assertEquals(SPEAKER, preferences.progress.first())
            preferences.advance(SPEAKER)
        }
        withPreferences { preferences ->
            preferences.advance(TEXT)
            assertEquals(COMPLETE, preferences.progress.first())
            assertTrue(preferences.isCompleted())
        }
    }
}
