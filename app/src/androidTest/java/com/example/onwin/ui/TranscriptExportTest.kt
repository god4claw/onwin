package com.example.onwin.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.data.database.AppDatabase
import com.example.onwin.data.repository.RoomSessionRepository
import com.example.onwin.data.transcription.TranscriptJsonExporter
import com.example.onwin.domain.model.ErrorCode
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import com.example.onwin.domain.processing.ImportSubmission
import com.example.onwin.domain.processing.ProcessingController
import com.example.onwin.domain.processing.ProcessingState
import com.example.onwin.ui.screens.recording.ActiveRecordingRoute
import com.example.onwin.ui.screens.recording.RecordingViewModel
import com.example.speech.api.Segment
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptExportTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completedTranscriptCanBeExportedAfterCancellationAndWriteFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = RoomSessionRepository(database.sessionDao(), context.cacheDir, Clock.systemUTC())
        val session = Session(
            id = "export-session",
            title = "Встреча: план/факт",
            source = SessionSource.IMPORT,
            originalName = "meeting.wav",
            sourceUri = null,
            createdAt = 1,
            updatedAt = 1,
            status = SessionStatus.TRANSCRIBING,
        )
        val viewModel = RecordingViewModel(
            SavedStateHandle(mapOf("sessionId" to session.id)),
            repository,
            IdleController,
            TranscriptJsonExporter(context),
        )
        val picker = TestPicker(context)
        val resolver = context.contentResolver
        var destination: Uri? = null
        try {
            runBlocking {
                repository.create(session)
                repository.appendSegment(session.id, 0, Segment(2, 0, 1_000, "Черновик"))
            }
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    ActiveRecordingRoute(onBack = {}, viewModel = viewModel)
                }
            }
            val button = compose.onNodeWithText("Сохранить JSON")
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onNodeWithText("Черновик").isDisplayed()
            }
            button.assertDoesNotExist()

            runBlocking {
                repository.replaceSegments(
                    session.id,
                    listOf(
                        Segment(0, 1_250, 4_380, "Добрый день!"),
                        Segment(1, 4_500, 6_000, "Привет!"),
                    ),
                )
                repository.setSpeakerName(session.id, 0, "Анна")
                repository.updateStatus(session.id, SessionStatus.DONE)
            }
            compose.waitUntil(timeoutMillis = 5_000) { button.isDisplayed() }
            button.performClick()
            button.assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.action)
                assertEquals("application/json", picker.intent.type)
                assertEquals("Встреча_ план_факт.json", picker.intent.getStringExtra(Intent.EXTRA_TITLE))
                picker.finish()
            }
            button.assertIsEnabled()

            button.performClick()
            compose.runOnIdle {
                picker.finish(Uri.parse("content://com.example.onwin.missing/transcript.json"))
                assertTrue(viewModel.isExporting.value)
            }
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.isExporting.value }
            button.assertIsEnabled()

            val uri = checkNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "onwin-transcript-test-${UUID.randomUUID()}.json")
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                ),
            )
            destination = uri
            button.performClick()
            compose.runOnIdle { picker.finish(uri) }
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.isExporting.value }
            button.assertIsEnabled()

            val actual = checkNotNull(resolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val expected = """
                {"transcript":[
                    {"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"},
                    {"speaker_id":"speaker_2","start_ms":4500,"end_ms":6000,"text":"Привет!"}
                ]}
            """.trimIndent()
            assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
        } finally {
            viewModel.viewModelScope.cancel()
            destination?.let { resolver.delete(it, null, null) }
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

        fun finish(uri: Uri? = null) {
            val result = if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK
            check(dispatchResult(requestCode, result, uri?.let { Intent().setData(it) }))
        }
    }

    private object IdleController : ProcessingController {
        override val state = MutableStateFlow(ProcessingState())
        override fun startRecording(): String? = null
        override suspend fun importAudio(source: String): ImportSubmission = error("Unexpected import")
        override fun stopRecording(sessionId: String) = Unit
        override fun enqueueImport(source: String): String = error("Unexpected import")
        override fun cancel(sessionId: String) = Unit
        override fun retry(sessionId: String) = Unit
        override fun delete(sessionId: String) = Unit
        override fun failAll(errorCode: ErrorCode) = Unit
    }
}
