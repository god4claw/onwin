package com.example.onwin.ui.screens.welcome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.onwin.ui.navigation.NavigationPanelSlot

@Composable
internal fun WelcomeRoute(
    modifier: Modifier = Modifier,
    onNavigateToRecordings: () -> Unit,
    viewModel: WelcomeViewModel = hiltViewModel(),
    panelSlot: NavigationPanelSlot? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val navigate by rememberUpdatedState(onNavigateToRecordings)

    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.navigateToRecordings.collect { navigate() }
        }
    }

    WelcomeScreen(
        modifier = modifier,
        state = state,
        onStart = viewModel::onStart,
        panelSlot = panelSlot,
    )
}
