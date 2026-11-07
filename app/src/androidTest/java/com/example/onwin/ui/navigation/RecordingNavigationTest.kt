package com.example.onwin.ui.navigation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.MainActivity
import com.example.onwin.ProcessingTestEntryPoint
import com.example.onwin.R
import com.example.onwin.domain.model.Session
import com.example.onwin.domain.model.SessionSource
import com.example.onwin.domain.model.SessionStatus
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingNavigationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun notificationEntry_opensSavedRecordingAndReturnsToList() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val repository = EntryPointAccessors.fromApplication(app, ProcessingTestEntryPoint::class.java)
            .sessionRepository()
        val id = UUID.randomUUID().toString()
        val title = "Navigation test $id"
        val now = System.currentTimeMillis()
        runBlocking {
            repository.create(
                Session(
                    id = id,
                    title = title,
                    source = SessionSource.MIC,
                    originalName = "$id.wav",
                    sourceUri = null,
                    createdAt = now,
                    updatedAt = now,
                    status = SessionStatus.DONE,
                ),
            )
        }
        try {
            val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, id)
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
                }
                val insidePanel = hasAnyAncestor(hasTestTag(NavigationPanelTag))
                compose.onNode(insidePanel and hasText(app.getString(R.string.playback_heading))).assertIsDisplayed()
                compose.onNodeWithText(app.getString(R.string.action_back)).performClick()
                compose.onNodeWithText(app.getString(R.string.sessions_title)).assertIsDisplayed()
                compose.onNode(insidePanel and hasText(app.getString(R.string.recordings_import_device))).assertIsDisplayed()
            }
        } finally {
            runBlocking { repository.delete(id) }
        }
    }
}
