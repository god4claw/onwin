package com.example.onwin.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationMotionTest {
    private val motionScale = object : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(1f)
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(effectContext = motionScale)

    private lateinit var controller: NavHostController

    @Test
    fun recordingEntersFromRightAndListReturnsFromLeft() {
        setContent(RecordingsDestination)
        val host = compose.onNodeWithTag(ViewportTag).getUnclippedBoundsInRoot()
        assertFrame(ListColor, ListColor)

        compose.runOnIdle { controller.navigate(ActiveRecordingDestination("test")) }
        compose.mainClock.advanceTimeByFrame()
        assertFrame(ListColor, ListColor)
        compose.mainClock.advanceTimeBy(144)

        val enteringRecording = compose.onNodeWithTag(RecordingTag).getUnclippedBoundsInRoot()
        val leavingList = compose.onNodeWithTag(ListTag).getUnclippedBoundsInRoot()
        val maxParallax = (host.right - host.left) * 0.08f + 1.dp
        assertTrue(enteringRecording.left > host.left && enteringRecording.left < host.right)
        assertTrue(leavingList.left < host.left && leavingList.left >= host.left - maxParallax)
        assertFrame(ListColor, RecordingColor, expectShadow = true)

        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag(ListTag).assertDoesNotExist()
        assertFrame(RecordingColor, RecordingColor)

        compose.runOnIdle { assertTrue(controller.popBackStack()) }
        compose.mainClock.advanceTimeByFrame()
        assertFrame(RecordingColor, RecordingColor)
        compose.mainClock.advanceTimeBy(144)

        val enteringList = compose.onNodeWithTag(ListTag).getUnclippedBoundsInRoot()
        val leavingRecording = compose.onNodeWithTag(RecordingTag).getUnclippedBoundsInRoot()
        assertTrue(enteringList.left < host.left && enteringList.right > host.left)
        assertTrue(leavingRecording.left > host.left && leavingRecording.left < host.right)
        assertTrue(enteringList.right > leavingRecording.left)
        assertFrame(ListColor, RecordingColor, expectShadow = true)

        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag(RecordingTag).assertDoesNotExist()
        assertFrame(ListColor, ListColor)
    }

    @Test
    fun listEntersFromBottomWhenWelcomeIsRemoved() {
        setContent(WelcomeDestination)
        val host = compose.onNodeWithTag(ViewportTag).getUnclippedBoundsInRoot()
        assertFrame(WelcomeColor, WelcomeColor, vertical = true)

        compose.runOnIdle { controller.showRecordingsFromWelcome() }
        compose.mainClock.advanceTimeByFrame()
        assertFrame(WelcomeColor, WelcomeColor, vertical = true)
        compose.mainClock.advanceTimeBy(160)

        val enteringList = compose.onNodeWithTag(ListTag).getUnclippedBoundsInRoot()
        val leavingWelcome = compose.onNodeWithTag(WelcomeTag).getUnclippedBoundsInRoot()
        val maxParallax = (host.bottom - host.top) * 0.05f + 1.dp
        assertTrue(enteringList.top > host.top && enteringList.top < host.bottom)
        assertTrue(leavingWelcome.top < host.top && leavingWelcome.top >= host.top - maxParallax)
        assertFrame(WelcomeColor, ListColor, vertical = true, expectShadow = true)

        compose.mainClock.advanceTimeBy(480)
        compose.onNodeWithTag(WelcomeTag).assertDoesNotExist()
        assertFrame(ListColor, ListColor, vertical = true)
        compose.runOnIdle { assertEquals(null, controller.previousBackStackEntry) }
    }

    @Test
    fun predictiveBackCanCancelOrFinishTheSameSlide() {
        setContent(RecordingsDestination)
        compose.runOnIdle { controller.navigate(ActiveRecordingDestination("test")) }
        compose.mainClock.advanceTimeBy(480)
        val dispatcher = compose.activity.onBackPressedDispatcher

        fun startBackGesture() {
            compose.runOnIdle {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 0f, 0.4f, BackEventCompat.EDGE_LEFT))
            }
            compose.mainClock.advanceTimeBy(64)
            assertFrame(ListColor, RecordingColor, expectShadow = true)
        }

        startBackGesture()
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(480)
        compose.onNodeWithTag(ListTag).assertDoesNotExist()
        assertFrame(RecordingColor, RecordingColor)

        startBackGesture()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(480)
        compose.onNodeWithTag(RecordingTag).assertDoesNotExist()
        assertFrame(ListColor, ListColor)
    }

    @Test
    fun zeroMotionScaleSkipsTheSlide() {
        motionScale.scaleFactor = 0f
        setContent(WelcomeDestination)
        compose.runOnIdle { controller.showRecordingsFromWelcome() }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag(WelcomeTag).assertDoesNotExist()
        assertFrame(ListColor, ListColor, vertical = true)

        compose.runOnIdle { controller.navigate(ActiveRecordingDestination("test")) }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag(ListTag).assertDoesNotExist()
        assertFrame(RecordingColor, RecordingColor)

        compose.runOnIdle { assertTrue(controller.popBackStack()) }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag(RecordingTag).assertDoesNotExist()
        assertFrame(ListColor, ListColor)
    }

    @Test
    fun panelSwitchesWithTheSlideAndRemainsAnchoredAtTheBottom() {
        val recordingLoading = mutableStateOf(true)
        setContentWithPanel(WelcomeDestination, recordingLoading)
        val viewport = compose.onNodeWithTag(ViewportTag).getUnclippedBoundsInRoot()
        val originalHeight = assertPanel(WelcomeTag, viewport.bottom)

        compose.runOnIdle { controller.showRecordingsFromWelcome() }
        compose.mainClock.advanceTimeBy(80)
        val enteringList = compose.onNodeWithTag(ListTag).getUnclippedBoundsInRoot()
        assertTrue(enteringList.top > viewport.top && enteringList.top < viewport.bottom)
        compose.onNodeWithTag(panelAction(WelcomeTag)).assertDoesNotExist()
        val changingHeight = assertPanel(ListTag, viewport.bottom)
        assertTrue(changingHeight > originalHeight)

        compose.mainClock.advanceTimeBy(480)
        compose.onNodeWithTag(WelcomeTag).assertDoesNotExist()
        val listHeight = assertPanel(ListTag, viewport.bottom)
        assertTrue(listHeight > changingHeight)

        compose.runOnIdle { controller.navigate(ActiveRecordingDestination("test")) }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag(panelAction(ListTag)).assertIsDisplayed()
        compose.onNodeWithTag(panelAction(RecordingTag)).assertDoesNotExist()
        val loadingPanel = compose.onNodeWithTag(NavigationPanelTag).getUnclippedBoundsInRoot()
        assertEquals(viewport.bottom, loadingPanel.bottom)
        assertEquals(listHeight, loadingPanel.bottom - loadingPanel.top)

        compose.runOnIdle { recordingLoading.value = false }
        compose.mainClock.advanceTimeBy(64)
        val enteringRecording = compose.onNodeWithTag(RecordingTag).getUnclippedBoundsInRoot()
        assertTrue(enteringRecording.left > viewport.left && enteringRecording.left < viewport.right)
        assertPanel(RecordingTag, viewport.bottom)
        compose.onNodeWithTag(panelAction(ListTag)).assertDoesNotExist()

        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag(ListTag).assertDoesNotExist()
        assertPanel(RecordingTag, viewport.bottom)

        compose.runOnIdle { assertTrue(controller.popBackStack()) }
        compose.mainClock.advanceTimeBy(144)
        assertTrue(compose.onNodeWithTag(ListTag).getUnclippedBoundsInRoot().left < viewport.left)
        assertPanel(ListTag, viewport.bottom)
        compose.onNodeWithTag(panelAction(RecordingTag)).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag(RecordingTag).assertDoesNotExist()
    }

    @Test
    fun predictiveBackChangesThePanelOnlyOnCommit() {
        setContentWithPanel(RecordingsDestination)
        val bottom = compose.onNodeWithTag(ViewportTag).getUnclippedBoundsInRoot().bottom
        compose.runOnIdle { controller.navigate(ActiveRecordingDestination("test")) }
        compose.mainClock.advanceTimeBy(480)
        val dispatcher = compose.activity.onBackPressedDispatcher

        fun startGesture() {
            compose.runOnIdle {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 0f, 0.4f, BackEventCompat.EDGE_LEFT))
            }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag(ListTag).assertExists()
            compose.onNodeWithTag(panelAction(ListTag)).assertDoesNotExist()
            assertPanel(RecordingTag, bottom)
        }

        startGesture()
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(480)
        assertPanel(RecordingTag, bottom)

        startGesture()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(80)
        assertPanel(ListTag, bottom)
        compose.onNodeWithTag(panelAction(RecordingTag)).assertDoesNotExist()
    }

    private fun assertPanel(tag: String, expectedBottom: Dp): Dp {
        val bounds = compose.onNodeWithTag(NavigationPanelTag).getUnclippedBoundsInRoot()
        assertEquals(expectedBottom, bounds.bottom)
        compose.onNodeWithTag(panelAction(tag)).assertIsDisplayed()
        return bounds.bottom - bounds.top
    }

    private fun setContentWithPanel(start: Any, recordingLoading: State<Boolean>? = null) {
        compose.setContent {
            controller = rememberNavController()
            val panels = remember { NavigationPanels() }
            val active by controller.currentBackStackEntryAsState()
            Box(Modifier.size(240.dp).testTag(ViewportTag)) {
                OnWinNavigationLayout(panels, active?.id) { viewport ->
                    OnWinNavHost(controller, start, viewport) {
                        composable<WelcomeDestination> { entry ->
                            ScreenWithPanel(panels, entry.id, WelcomeTag, 36.dp, WelcomeColor)
                        }
                        composable<RecordingsDestination> { entry ->
                            ScreenWithPanel(panels, entry.id, ListTag, 76.dp, ListColor)
                        }
                        composable<ActiveRecordingDestination> { entry ->
                            ScreenWithPanel(
                                panels, entry.id, RecordingTag, 52.dp, RecordingColor,
                                loading = recordingLoading?.value == true,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun setContent(start: Any) {
        compose.setContent {
            controller = rememberNavController()
            Box(Modifier.size(240.dp).background(UncoveredColor).testTag(ViewportTag)) {
                OnWinNavHost(navController = controller, startDestination = start) {
                    composable<WelcomeDestination> { Screen(WelcomeTag, WelcomeColor) }
                    composable<RecordingsDestination> {
                        Screen(ListTag, ListColor, Modifier.navigationEdgeShadow(atTop = true))
                    }
                    composable<ActiveRecordingDestination> {
                        Screen(RecordingTag, RecordingColor, Modifier.navigationEdgeShadow())
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun assertFrame(
        near: Color,
        far: Color,
        vertical: Boolean = false,
        expectShadow: Boolean = false,
    ) {
        val pixels = compose.onNodeWithTag(ViewportTag).captureToImage().toPixelMap()
        val length = if (vertical) pixels.height else pixels.width
        val span = if (vertical) pixels.width else pixels.height
        var sawShadow = false
        for (line in listOf(0, span / 2, span - 1)) {
            fun pixel(index: Int) = if (vertical) pixels[line, index] else pixels[index, line]
            assertEquals(near, pixel(0))
            assertEquals(far, pixel(length - 1))
            for (index in 0 until length) {
                val color = pixel(index)
                assertTrue("Uncovered pixel at $index on line $line", color != UncoveredColor)
                if (color != near && color != far) sawShadow = true
            }
        }
        assertEquals(expectShadow, sawShadow)
    }
}

@Composable
private fun Screen(tag: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(color).testTag(tag))
}

@Composable
private fun ScreenWithPanel(
    panels: NavigationPanels,
    id: String,
    tag: String,
    height: Dp,
    color: Color,
    loading: Boolean = false,
) {
    val slot = remember(panels, id) { NavigationPanelSlot(panels, id) }
    OnWinScreenLayout(
        modifier = Modifier,
        slot = slot,
        loading = loading,
        panel = {
            NavigationPanelContent(
                secondary = panelElement(tag) { Box(Modifier.fillMaxWidth().height(height).testTag(panelAction(tag))) },
            )
        },
    ) { Screen(tag, color) }
}

private fun panelAction(tag: String) = "$tag-action"

private const val ViewportTag = "viewport"
private const val WelcomeTag = "welcome"
private const val ListTag = "recordings"
private const val RecordingTag = "activeRecording"
private val UncoveredColor = Color.Magenta
private val WelcomeColor = Color(0xFFEF642D)
private val ListColor = Color(0xFF225C81)
private val RecordingColor = Color(0xFF157558)
