package com.example.onwin.ui.navigation

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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.onwin.ui.components.onWinDialogBackdrop
import com.example.onwin.ui.theme.OnWinPanelShape
import com.example.onwin.ui.theme.onWinAtmosphere
import com.example.onwin.ui.theme.onWinPanelBackground

internal const val NavigationPanelTag = "navigation-bottom-panel"
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
)

internal class NavigationPanelSlot(private val panels: NavigationPanels, private val entryId: String) {
    @Composable
    fun Publish(
        loading: Boolean,
        dimmed: Boolean,
        content: NavigationPanelContent?,
        anchoredContent: (@Composable () -> Unit)? = null,
    ) {
        val latest = key(panels, entryId) { rememberUpdatedState(NavigationPanel(loading, dimmed, content, anchoredContent)) }
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
            Column(Modifier.fillMaxSize()) {
                val panel = activeEntryId?.let(panels.entries::get)?.value
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
                OnWinPanelSurface(panelModifier) {
                    NavigationPanelContents(
                        model = displayed.content,
                        owner = displayed.owner,
                        enabled = panel != null && !panel.loading && displayed.owner == activeEntryId,
                    )
                }
            }
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
    content: @Composable (DpSize) -> Unit,
) {
    val backdrop = if (dimmed) Modifier.onWinDialogBackdrop() else Modifier
    if (slot != null) {
        val viewport = checkNotNull(LocalNavigationViewport.current)
        slot.Publish(loading, dimmed, panel?.invoke(viewport), anchoredContent)
        // Корневой OnWinNavigationLayout уже рисует атмосферу на весь экран,
        // повторная заливка здесь — лишний полноэкранный overdraw.
        Box(modifier.fillMaxSize().then(backdrop)) { content(viewport) }
    } else {
        BoxWithConstraints(modifier.fillMaxSize().then(backdrop).onWinAtmosphere()) {
            val viewport = DpSize(maxWidth, maxHeight)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content(viewport)
                    Box(Modifier.align(Alignment.BottomEnd)) {
                        anchoredContent?.invoke()
                    }
                }
                if (panel != null) OnWinPanelSurface {
                    NavigationPanelContents(panel(viewport), owner = null, enabled = !loading)
                }
            }
        }
    }
}

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
