package com.example.onwin.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.onwin.ui.screens.recording.ActiveRecordingRoute
import com.example.onwin.ui.screens.recordings.RecordingsRoute
import com.example.onwin.ui.screens.welcome.WelcomeRoute

@Composable
internal fun NavGraph(
    showWelcome: Boolean,
    pendingSessionId: String?,
    onPendingSessionOpened: () -> Unit,
) {
    val navController = rememberNavController()
    val panels = remember { NavigationPanels() }
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination

    if (destination?.hasRoute<WelcomeDestination>() == true ||
        destination?.hasRoute<RecordingsDestination>() == true ||
        destination?.hasRoute<ActiveRecordingDestination>() == true
    ) {
        OnWinSystemBars()
    }

    OnWinNavigationLayout(panels, activeEntryId = entry?.id) { viewportModifier ->
        OnWinNavHost(
            navController = navController,
            startDestination = if (showWelcome) WelcomeDestination else RecordingsDestination,
            modifier = viewportModifier,
        ) {
            composable<WelcomeDestination> { welcomeEntry ->
                WelcomeRoute(
                    onNavigateToRecordings = navController::showRecordingsFromWelcome,
                    panelSlot = remember(panels, welcomeEntry.id) { NavigationPanelSlot(panels, welcomeEntry.id) },
                )
            }

            composable<RecordingsDestination> { recordingsEntry ->
                RecordingsRoute(
                    panelSlot = remember(panels, recordingsEntry.id) { NavigationPanelSlot(panels, recordingsEntry.id) },
                    onOpen = { id ->
                        if (navController.currentDestination?.hasRoute<RecordingsDestination>() == true) {
                            navController.openRecording(id)
                        }
                    },
                )
            }

            composable<ActiveRecordingDestination> { recordingEntry ->
                ActiveRecordingRoute(
                    panelSlot = remember(panels, recordingEntry.id) { NavigationPanelSlot(panels, recordingEntry.id) },
                    onBack = {
                        if (navController.currentBackStackEntry == recordingEntry) {
                            navController.popBackStack()
                        }
                    },
                )
            }
        }
    }

    LaunchedEffect(pendingSessionId) {
        if (pendingSessionId != null) {
            navController.openRecording(pendingSessionId)
            onPendingSessionOpened()
        }
    }
}

internal fun NavController.showRecordingsFromWelcome() {
    if (currentDestination?.hasRoute<WelcomeDestination>() != true) return
    navigate(RecordingsDestination) {
        popUpTo<WelcomeDestination> { inclusive = true }
    }
}

@Composable
private fun OnWinSystemBars() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current

    DisposableEffect(window, view) {
        val insets = WindowCompat.getInsetsController(window, view)
        val previousStatusBar = insets.isAppearanceLightStatusBars
        val previousNavigationBar = insets.isAppearanceLightNavigationBars
        insets.isAppearanceLightStatusBars = true
        insets.isAppearanceLightNavigationBars = false
        onDispose {
            insets.isAppearanceLightStatusBars = previousStatusBar
            insets.isAppearanceLightNavigationBars = previousNavigationBar
        }
    }
}

private fun NavController.openRecording(sessionId: String) {
    val currentEntry = currentBackStackEntry
    if (currentEntry?.destination?.hasRoute<ActiveRecordingDestination>() == true &&
        currentEntry.toRoute<ActiveRecordingDestination>().sessionId == sessionId
    ) return

    showRecordingsFromWelcome()
    navigate(ActiveRecordingDestination(sessionId)) {
        popUpTo<RecordingsDestination>()
    }
}
