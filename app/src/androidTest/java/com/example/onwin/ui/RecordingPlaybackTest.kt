package com.example.onwin.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.onwin.data.audio.WavWriter
import com.example.onwin.ui.screens.recording.RecordingPlayback
import com.example.onwin.ui.screens.recording.RecordingPlaybackPanel
import com.example.onwin.ui.screens.recording.rememberRecordingPlayback
import com.example.onwin.ui.theme.ActiveRecordingTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingPlaybackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun savedWavPlaysFromSelectedPositionAndCanPause() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File.createTempFile("recording-playback", ".wav", context.cacheDir)
        try {
            WavWriter(wav).use { it.writeShorts(ShortArray(12 * 16_000)) }
            lateinit var player: RecordingPlayback
            compose.setContent {
                val playback = rememberRecordingPlayback(wav)
                SideEffect { player = playback }
                ActiveRecordingTheme {
                    RecordingPlaybackPanel(false, playback.state, playback.positionState, 12_000L, playback::toggle, playback::seekTo)
                }
            }

            compose.waitUntil(10_000L) { player.state.ready }
            compose.onNodeWithContentDescription("Шкала воспроизведения")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
            compose.waitUntil(5_000L) { player.positionMs == 6_000L }
            // The player requests frames continuously. Do not let an idle wait run it to EOF.
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Воспроизвести аудио").performClick()
            compose.waitUntil(8_000L) {
                compose.mainClock.advanceTimeByFrame()
                player.state.playing && player.positionMs >= 6_350L
            }
            compose.onNodeWithContentDescription("Приостановить аудио").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription("Воспроизвести аудио").assertIsDisplayed()
            compose.runOnIdle { assertTrue(player.positionMs in 6_350L until 12_000L) }
        } finally {
            wav.delete()
        }
    }
}
