package com.example.onwin.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

private val RecordingSlide = tween<IntOffset>(400, easing = FastOutSlowInEasing)
private val WelcomeSlide = tween<IntOffset>(480, easing = FastOutSlowInEasing)

@Composable
internal fun OnWinNavHost(
    navController: NavHostController,
    startDestination: Any,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    // The shared panel changes the host height during the slide. Use a stable travel distance.
    val viewportHeight = LocalNavigationViewport.current?.let { viewport ->
        with(LocalDensity.current) { viewport.height.roundToPx() }
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize().clipToBounds(),
        enterTransition = {
            when {
                movesFromTo<WelcomeDestination, RecordingsDestination>() ->
                    slideInVertically(WelcomeSlide) { viewportHeight ?: it }
                movesFromTo<RecordingsDestination, ActiveRecordingDestination>() ->
                    slideInHorizontally(RecordingSlide) { it }
                else -> EnterTransition.None
            }
        },
        exitTransition = {
            when {
                movesFromTo<WelcomeDestination, RecordingsDestination>() ->
                    slideOutVertically(WelcomeSlide) { -(viewportHeight ?: it) }
                movesFromTo<RecordingsDestination, ActiveRecordingDestination>() ->
                    slideOutHorizontally(RecordingSlide) { -it }
                else -> ExitTransition.None
            }
        },
        popEnterTransition = { recordingPopEnter() },
        popExitTransition = { recordingPopExit() },
        predictivePopEnterTransition = { _ -> recordingPopEnter() },
        predictivePopExitTransition = { _ -> recordingPopExit() },
        builder = builder,
    )
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.recordingPopEnter(): EnterTransition =
    if (movesFromTo<ActiveRecordingDestination, RecordingsDestination>()) {
        slideInHorizontally(RecordingSlide) { -it }
    } else {
        EnterTransition.None
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.recordingPopExit(): ExitTransition =
    if (movesFromTo<ActiveRecordingDestination, RecordingsDestination>()) {
        slideOutHorizontally(RecordingSlide) { it }
    } else {
        ExitTransition.None
    }

private inline fun <reified From : Any, reified To : Any>
    AnimatedContentTransitionScope<NavBackStackEntry>.movesFromTo(): Boolean =
    initialState.destination.hasRoute<From>() && targetState.destination.hasRoute<To>()
