package com.example.onwin.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.onwin.data.preferences.WelcomePreferences
import com.example.onwin.ui.navigation.LaunchViewModel
import com.example.onwin.ui.screens.welcome.WelcomeState
import com.example.onwin.ui.screens.welcome.WelcomeViewModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeFlowTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `new launch skips welcome after Start is saved to disk`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val file = File(temp.root, "welcome.preferences_pb")
        val firstStore = Job()
        val reopenedStore = Job()
        try {
            val preferences = WelcomePreferences(
                PreferenceDataStoreFactory.create(scope = CoroutineScope(dispatcher + firstStore)) { file }
            )
            val firstLaunch = LaunchViewModel(preferences)
            assertEquals(true, firstLaunch.showWelcome.first { it != null })
            assertFalse(preferences.isCompleted())

            val screen = WelcomeViewModel(preferences)
            screen.onStart()
            screen.navigateToRecordings.first()
            assertEquals(WelcomeState.Completed, screen.state.value)
            assertTrue(preferences.isCompleted())
            assertEquals(true, firstLaunch.showWelcome.value)
            firstStore.cancelAndJoin()

            val reopened = WelcomePreferences(
                PreferenceDataStoreFactory.create(scope = CoroutineScope(dispatcher + reopenedStore)) { file }
            )
            assertEquals(false, LaunchViewModel(reopened).showWelcome.first { it != null })
        } finally {
            firstStore.cancelAndJoin()
            reopenedStore.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed save can retry and rapid taps emit one navigation effect`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = RetryDataStore()
            val screen = WelcomeViewModel(WelcomePreferences(store))
            val effects = mutableListOf<Unit>()
            backgroundScope.launch { screen.navigateToRecordings.take(2).toList(effects) }

            screen.onStart()
            runCurrent()
            assertEquals(WelcomeState.SaveFailed, screen.state.value)
            assertTrue(effects.isEmpty())

            screen.onStart()
            screen.onStart()
            runCurrent()
            screen.onStart()
            runCurrent()
            assertEquals(WelcomeState.Completed, screen.state.value)
            assertEquals(2, store.attempts)
            assertEquals(1, effects.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class RetryDataStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        var attempts = 0
            private set

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            attempts++
            if (attempts == 1) throw IOException("Write failed")
            return transform(data.value).also { data.value = it }
        }
    }
}
