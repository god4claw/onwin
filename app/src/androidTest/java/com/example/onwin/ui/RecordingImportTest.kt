package com.example.onwin.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.data.database.AppDatabase
import com.example.onwin.data.repository.RoomSessionRepository
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.processing.ImportSubmission
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.processing.ProcessingState
import com.example.onwin.ui.navigation.NavigationPanelSlot
import com.example.onwin.ui.navigation.NavigationPanels
import com.example.onwin.ui.navigation.OnWinNavigationLayout
import com.example.onwin.ui.screens.recordings.RecordingsRoute
import com.example.onwin.ui.screens.recordings.RecordingsViewModel
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingImportTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pickerCancelsCleanlyAndSelectedAudioOpensOnlyAfterCreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = RoomSessionRepository(database.sessionDao(), context.cacheDir, Clock.systemUTC())
        val controller = ImportController()
        val viewModel = RecordingsViewModel(repository, controller, androidx.lifecycle.SavedStateHandle())
        val picker = TestPicker(context)
        val opened = mutableStateOf<String?>(null)
        try {
            compose.setContent {
                val panels = remember { NavigationPanels() }
                val slot = remember(panels) { NavigationPanelSlot(panels, "recordings") }
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    OnWinNavigationLayout(panels, "recordings") { viewport ->
                        RecordingsRoute(
                            modifier = viewport,
                            viewModel = viewModel,
                            panelSlot = slot,
                            onOpen = { opened.value = it },
                        )
                    }
                }
            }
            val importButton = compose.onNodeWithText("Импортировать с устройства")
            val loader = compose.onNodeWithContentDescription("Подготовка файла…")
            compose.waitUntil(timeoutMillis = 5_000) { importButton.isDisplayed() }
            importButton.performClick()
            compose.runOnIdle {
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.action)
                assertTrue(picker.intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertTrue("audio/*" in picker.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES).orEmpty())
                assertFalse(picker.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                picker.finish(Activity.RESULT_CANCELED)
            }
            loader.assertDoesNotExist()
            compose.runOnIdle { assertTrue(controller.sources.isEmpty()) }

            val uri = Uri.parse("content://documents/audio/talk.mp3")
            importButton.performClick()
            compose.runOnIdle { picker.finish(Activity.RESULT_OK, Intent().setData(uri)) }
            loader.assertIsDisplayed()
            importButton.assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(listOf(uri.toString()), controller.sources)
                assertNull(opened.value)
                controller.created.complete("new-recording")
            }
            compose.waitUntil(timeoutMillis = 5_000) { opened.value == "new-recording" }
            compose.runOnIdle {
                opened.value = null
                controller.created = CompletableDeferred()
                controller.state.value = ProcessingState(busy = true)
            }
            importButton.performClick()
            compose.runOnIdle { picker.finish(Activity.RESULT_OK, Intent().setData(uri)) }
            loader.assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(listOf(uri.toString(), uri.toString()), controller.sources)
                controller.created.complete("queued-recording")
            }
            compose.waitUntil(timeoutMillis = 5_000) { !loader.isDisplayed() }
            compose.runOnIdle { assertNull(opened.value) }
        } finally {
            viewModel.viewModelScope.cancel()
            database.close()
        }
    }

    private class TestPicker(private val context: Context) : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        lateinit var intent: Intent
            private set
        private var requestCode = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            intent = contract.createIntent(context, input)
        }

        fun finish(resultCode: Int, data: Intent? = null) {
            check(dispatchResult(requestCode, resultCode, data))
        }
    }

    private class ImportController : ProcessingController {
        override val state = MutableStateFlow(ProcessingState())
        val sources = mutableListOf<String>()
        var created = CompletableDeferred<String>()

        override suspend fun importAudio(source: String): ImportSubmission {
            sources += source
            val openDetails = !state.value.busy
            return ImportSubmission(created.await(), openDetails)
        }

        override fun startRecording(liveTranscription: Boolean): String = error("Unexpected microphone action")
        override fun stopRecording(sessionId: String) = Unit
        override fun enqueueImport(source: String): String = error("Unexpected queued import")
        override fun cancel(sessionId: String) = Unit
        override fun retry(sessionId: String) = Unit
        override fun delete(sessionId: String) = Unit
        override fun failAll(errorCode: ErrorCode) = Unit
    }
}
