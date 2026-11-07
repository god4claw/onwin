package com.example.onwin.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.onwin.ui.components.GlassButton
import com.example.onwin.ui.theme.RecordingsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PanelContentMotionTest {
    private val scale = object : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(1f)
    }

    @get:Rule
    val compose = createComposeRule(effectContext = scale)

    private var page by mutableStateOf(Start)
    private var loading by mutableStateOf(false)
    private var tick by mutableStateOf(0)
    private var clicks = 0

    @Test
    fun primarySurvivesWelcomeAndBothRecordingDirections() {
        setContent()
        val button = compose.onNodeWithTag(PanelPrimaryTag)
        val identity = button.fetchSemanticsNode().id
        val wide = button.getUnclippedBoundsInRoot()
        val bottom = compose.onNodeWithTag(NavigationPanelTag).getUnclippedBoundsInRoot().bottom

        compose.runOnIdle { page = ListPanel }
        compose.mainClock.advanceTimeBy(80)
        val narrowing = button.getUnclippedBoundsInRoot()
        assertTrue(narrowing.right - narrowing.left < wide.right - wide.left)
        assertTrue(narrowing.right - narrowing.left > 136.dp)
        assertPrimary(identity, bottom)
        button.assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(240)
        val listBounds = button.getUnclippedBoundsInRoot()
        assertEquals(136.dp, listBounds.right - listBounds.left)

        compose.runOnIdle { page = Player }
        compose.mainClock.advanceTimeBy(80)
        val moving = button.getUnclippedBoundsInRoot()
        assertTrue(moving.top != listBounds.top)
        assertPrimary(identity, bottom)
        compose.onNodeWithText("Import").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(240)
        val playerBounds = button.getUnclippedBoundsInRoot()
        assertTrue(moving.top > minOf(listBounds.top, playerBounds.top))
        assertTrue(moving.top < maxOf(listBounds.top, playerBounds.top))
        button.assertIsEnabled()

        compose.runOnIdle { page = ListPanel }
        compose.mainClock.advanceTimeBy(80)
        assertPrimary(identity, bottom)
        compose.mainClock.advanceTimeBy(240)
        assertEquals(listBounds, button.getUnclippedBoundsInRoot())
        compose.onNodeWithText("Import").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun loadingKeepsContentVisibleAndDisablesActions() {
        page = ListPanel
        setContent()
        val before = compose.onNodeWithTag(PanelPrimaryTag).getUnclippedBoundsInRoot()
        compose.runOnIdle {
            loading = true
            page = Capture
        }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Import").assertIsDisplayed().assertIsNotEnabled().performClick()
        compose.onNodeWithTag(PanelPrimaryTag).assertIsNotEnabled().performClick()
        assertEquals(before, compose.onNodeWithTag(PanelPrimaryTag).getUnclippedBoundsInRoot())
        compose.runOnIdle { assertEquals(0, clicks) }

        compose.runOnIdle { loading = false }
        compose.mainClock.advanceTimeBy(80)
        compose.onAllNodesWithTag(PanelPrimaryTag).assertCountEquals(1)
        compose.onNodeWithTag(PanelPrimaryTag).assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag(PanelPrimaryTag).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun identicalStopSymbolAndPlaybackTicksKeepTheirNodes() {
        page = Capture
        setContent()
        val symbol = compose.onNodeWithText("Stop").fetchSemanticsNode().id
        val primary = compose.onNodeWithTag(PanelPrimaryTag).fetchSemanticsNode().id
        compose.runOnIdle { page = CaptureInList }
        compose.mainClock.advanceTimeBy(80)
        assertEquals(symbol, compose.onNodeWithText("Stop").fetchSemanticsNode().id)
        assertEquals(primary, compose.onNodeWithTag(PanelPrimaryTag).fetchSemanticsNode().id)
        compose.mainClock.advanceTimeBy(240)

        compose.runOnIdle { page = Player }
        compose.mainClock.advanceTimeBy(320)
        val timeline = compose.onNodeWithTag("timeline").fetchSemanticsNode().id
        repeat(4) {
            compose.runOnIdle { tick++ }
            compose.mainClock.advanceTimeByFrame()
            assertEquals(timeline, compose.onNodeWithTag("timeline").fetchSemanticsNode().id)
            compose.onNodeWithTag(PanelPrimaryTag).assertIsEnabled()
        }
    }

    @Test
    fun retryAndRapidReversalFinishWithOnlyCurrentActions() {
        page = ListPanel
        setContent()
        compose.runOnIdle { page = Retry }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag(PanelPrimaryTag).assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag(PanelPrimaryTag).assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsEnabled()

        compose.runOnIdle { page = Capture }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { page = ListPanel }
        compose.mainClock.advanceTimeBy(320)
        compose.onAllNodesWithTag(PanelPrimaryTag).assertCountEquals(1)
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Import").assertIsEnabled()
    }

    @Test
    fun zeroScaleSettlesAllElementsWithoutWaitingForDuration() {
        scale.scaleFactor = 0f
        setContent()
        compose.runOnIdle { page = Player }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Start").assertDoesNotExist()
        compose.onNodeWithTag(PanelPrimaryTag).assertIsEnabled()
        val bounds = compose.onNodeWithTag(PanelPrimaryTag).getUnclippedBoundsInRoot()
        assertEquals(136.dp, bounds.right - bounds.left)
    }

    private fun assertPrimary(identity: Int, bottom: Dp) {
        compose.onAllNodesWithTag(PanelPrimaryTag).assertCountEquals(1)
        assertEquals(identity, compose.onNodeWithTag(PanelPrimaryTag).fetchSemanticsNode().id)
        assertEquals(bottom, compose.onNodeWithTag(NavigationPanelTag).getUnclippedBoundsInRoot().bottom)
    }

    private fun setContent() {
        compose.setContent {
            val panels = remember { NavigationPanels() }
            Box(Modifier.size(300.dp, 600.dp)) {
                OnWinNavigationLayout(panels, page.name) { modifier ->
                    val slot = remember(page.name) { NavigationPanelSlot(panels, page.name) }
                    RecordingsTheme {
                        OnWinScreenLayout(modifier, slot, loading = loading, panel = {
                            testPanel(page, tick) { clicks++ }
                        }) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
}

@Composable
private fun testPanel(page: TestPanel, tick: Int, onClick: () -> Unit) = NavigationPanelContent(
    heading = panelElement(page.name) { Text(page.name) },
    supporting = if (page == Player) panelElement("timeline") {
        Text("Position $tick", Modifier.height(80.dp).testTag("timeline"))
    } else null,
    primary = page.symbol?.let { symbol ->
        PanelPrimary(panelElement(symbol) { Text(symbol) }, onClick, width = if (page == Start) null else 136.dp)
    },
    secondary = page.action?.let { action ->
        panelElement(action) { enabled -> GlassButton(action, onClick, enabled = enabled) }
    },
    topPadding = 20.dp,
    bottomPadding = if (page == Player) 68.dp else 24.dp,
    primaryBottomGap = if (page.action == null) 0.dp else 16.dp,
)

private data class TestPanel(val name: String, val symbol: String?, val action: String?)
private val Start = TestPanel("Welcome", "Start", null)
private val ListPanel = TestPanel("List", "Mic", "Import")
private val Capture = TestPanel("Capture", "Stop", "Cancel")
private val CaptureInList = TestPanel("Capture in list", "Stop", "Import")
private val Player = TestPanel("Player", "Play", null)
private val Retry = TestPanel("Error", null, "Retry")
