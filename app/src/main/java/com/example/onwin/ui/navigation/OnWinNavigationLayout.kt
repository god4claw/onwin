package com.example.onwin.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.onwin.ui.components.onWinDialogBackdrop
import com.example.onwin.ui.theme.OnWinPanelShape
import com.example.onwin.ui.theme.onWinAtmosphere
import com.example.onwin.ui.theme.onWinPanelBackground

internal const val NavigationPanelTag = "navigation-bottom-panel"
internal const val ScreenControlsMotionMillis = 320
internal val LocalNavigationViewport = staticCompositionLocalOf<DpSize?> { null }
internal val DpSize.isCompact get() = height <= 740.dp

internal class NavigationPanels {
    internal val entries = mutableStateMapOf<String, State<NavigationPanel>>()
}

internal data class NavigationPanel(
    val loading: Boolean,
    val dimmed: Boolean,
    val content: NavigationPanelContent?,
    val anchoredContent: (@Composable () -> Unit)?,
    val overlay: (@Composable () -> Unit)?,
    val bottomPanelVisible: Boolean,
)

internal class NavigationPanelSlot(private val panels: NavigationPanels, private val entryId: String) {
    @Composable
    fun Publish(
        loading: Boolean,
        dimmed: Boolean,
        content: NavigationPanelContent?,
        anchoredContent: (@Composable () -> Unit)? = null,
        overlay: (@Composable () -> Unit)? = null,
        bottomPanelVisible: Boolean = true,
    ) {
        val latest = key(panels, entryId) {
            rememberUpdatedState(NavigationPanel(loading, dimmed, content, anchoredContent, overlay, bottomPanelVisible))
        }
        SideEffect {
            panels.entries[entryId] = latest
        }
        DisposableEffect(panels, entryId) {
            onDispose { panels.entries.remove(entryId) }
        }
    }
}

private data class ReadyPanel(val owner: String?, val content: NavigationPanelContent)

@Composable
internal fun OnWinNavigationLayout(
    panels: NavigationPanels,
    activeEntryId: String?,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().onWinAtmosphere()) {
        val viewport = DpSize(maxWidth, maxHeight)
        CompositionLocalProvider(LocalNavigationViewport provides viewport) {
            val panel = activeEntryId?.let(panels.entries::get)?.value
            Column(Modifier.fillMaxSize().behindOverlay(panel?.overlay != null)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content(Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.BottomEnd)) {
                        panel?.anchoredContent?.invoke()
                    }
                }
                var lastReady by remember { mutableStateOf(ReadyPanel(null, NavigationPanelContent())) }
                val displayed = if (panel != null && !panel.loading) {
                    ReadyPanel(activeEntryId, panel.content ?: NavigationPanelContent())
                } else lastReady
                SideEffect { lastReady = displayed }
                val panelModifier = Modifier.testTag(NavigationPanelTag)
                    .then(if (panel?.dimmed == true) Modifier.onWinDialogBackdrop() else Modifier)
                val panelVisible = panel?.bottomPanelVisible != false
                ScreenControls(visible = panelVisible, anchor = Alignment.Bottom) {
                    OnWinPanelSurface(panelModifier) {
                        NavigationPanelContents(
                            model = displayed.content,
                            owner = displayed.owner,
                            enabled = panelVisible && panel != null && !panel.loading && displayed.owner == activeEntryId,
                        )
                    }
                }
            }
            panel?.overlay?.invoke()
        }
    }
}

/** The same screen can render in the graph or on its own in a preview or UI test. */
@Composable
internal fun OnWinScreenLayout(
    modifier: Modifier,
    slot: NavigationPanelSlot?,
    loading: Boolean = false,
    dimmed: Boolean = false,
    panel: (@Composable (DpSize) -> NavigationPanelContent)?,
    anchoredContent: (@Composable () -> Unit)? = null,
    overlay: (@Composable () -> Unit)? = null,
    bottomPanelVisible: Boolean = true,
    content: @Composable (DpSize) -> Unit,
) {
    val backdrop = if (dimmed) Modifier.onWinDialogBackdrop() else Modifier
    if (slot != null) {
        val viewport = checkNotNull(LocalNavigationViewport.current)
        slot.Publish(loading, dimmed, panel?.invoke(viewport), anchoredContent, overlay, bottomPanelVisible)
        // Корневой OnWinNavigationLayout уже рисует атмосферу на весь экран,
        // повторная заливка здесь — лишний полноэкранный overdraw.
        Box(modifier.fillMaxSize().then(backdrop)) { content(viewport) }
    } else {
        BoxWithConstraints(modifier.fillMaxSize().then(backdrop).onWinAtmosphere()) {
            val viewport = DpSize(maxWidth, maxHeight)
            Column(Modifier.fillMaxSize().behindOverlay(overlay != null)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content(viewport)
                    Box(Modifier.align(Alignment.BottomEnd)) {
                        anchoredContent?.invoke()
                    }
                }
                if (panel != null) {
                    ScreenControls(visible = bottomPanelVisible, anchor = Alignment.Bottom) {
                        OnWinPanelSurface {
                            NavigationPanelContents(panel(viewport), owner = null, enabled = bottomPanelVisible && !loading)
                        }
                    }
                }
            }
            overlay?.invoke()
        }
    }
}

@Composable
internal fun ScreenControls(
    visible: Boolean,
    modifier: Modifier = Modifier,
    anchor: Alignment.Vertical = Alignment.Top,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(tween(ScreenControlsMotionMillis), expandFrom = anchor) +
            fadeIn(tween(ScreenControlsMotionMillis)),
        exit = shrinkVertically(tween(ScreenControlsMotionMillis), shrinkTowards = anchor) +
            fadeOut(tween(150)),
    ) { content() }
}

private fun Modifier.behindOverlay(covered: Boolean): Modifier =
    if (covered) clearAndSetSemantics { } else this

@Composable
internal fun OnWinPanelSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(OnWinPanelShape).onWinPanelBackground(),
        contentAlignment = Alignment.BottomCenter,
    ) { PanelInsets(content) }
}

@Composable
private fun PanelInsets(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) { content() }
}
