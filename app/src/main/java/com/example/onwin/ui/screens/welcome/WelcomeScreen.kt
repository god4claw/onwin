package com.example.onwin.ui.screens.welcome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.onwin.R
import com.example.onwin.ui.navigation.NavigationPanelContent
import com.example.onwin.ui.navigation.PanelPrimary
import com.example.onwin.ui.navigation.panelElement
import com.example.onwin.ui.navigation.NavigationPanelSlot
import com.example.onwin.ui.navigation.OnWinScreenLayout
import com.example.onwin.ui.navigation.isCompact
import com.example.onwin.ui.theme.WelcomeTheme

@Composable
internal fun WelcomeScreen(
    modifier: Modifier = Modifier,
    state: WelcomeState,
    onStart: () -> Unit,
    panelSlot: NavigationPanelSlot? = null,
) {
    WelcomeTheme {
        OnWinScreenLayout(
            modifier = modifier,
            slot = panelSlot,
            panel = { viewport -> welcomePanel(state, viewport.isCompact, onStart) },
        ) { viewport ->
            val titleTop = if (viewport.isCompact) 92.dp else (viewport.height * 0.186f).coerceIn(100.dp, 175.dp)
            val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
            val titleSize = (viewport.width.value * 0.146f).coerceIn(48f, 60f)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.welcome_title),
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(start = 14.dp, end = 14.dp, top = maxOf(titleTop, topInset), bottom = 24.dp)
                        .semantics { heading() },
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = titleSize.sp,
                        lineHeight = (titleSize * 1.07f).sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun welcomePanel(state: WelcomeState, compactHeight: Boolean, onStart: () -> Unit): NavigationPanelContent =
    NavigationPanelContent(
        horizontalPadding = 28.dp,
        topPadding = if (compactHeight) 26.dp else 32.dp,
        bottomPadding = 28.dp,
        heading = panelElement(R.string.welcome_subtitle) {
            Text(
                text = stringResource(R.string.welcome_subtitle),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        },
        supporting = panelElement(listOf(R.string.welcome_description, state == WelcomeState.SaveFailed)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.welcome_description),
                    modifier = Modifier
                        .padding(top = 18.dp, bottom = if (compactHeight) 24.dp else 30.dp)
                        .widthIn(max = 278.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (state == WelcomeState.SaveFailed) {
                    Text(
                        text = stringResource(R.string.welcome_save_failed),
                        modifier = Modifier.padding(bottom = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        primary = PanelPrimary(
            onClick = onStart,
            width = null,
            enabled = state == WelcomeState.Ready || state == WelcomeState.SaveFailed,
            icon = panelElement(R.string.welcome_start) {
                Text(stringResource(R.string.welcome_start), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            },
        ),
    )

@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    WelcomeScreen(state = WelcomeState.Ready, onStart = {})
}
