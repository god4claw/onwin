package com.example.onwin.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import kotlin.math.roundToInt

private val RecordingSlide = tween<IntOffset>(400, easing = FastOutSlowInEasing)
private val WelcomeSlide = tween<IntOffset>(480, easing = FastOutSlowInEasing)
private val ReturningListSlide = tween<IntOffset>(400, easing = Easing { fraction ->
    val progress = FastOutSlowInEasing.transform(fraction)
    // The list runs slightly ahead of the upper layer so their edges overlap throughout the pop.
    progress + 0.24f * progress * (1f - progress)
})

@Composable
internal fun OnWinNavHost(
    navController: NavHostController,
    startDestination: Any,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    // The shared footer resizes the host mid-transition. Use the window height so
    // that each new footer measurement cannot retarget the vertical slide.
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
                    slideOutVertically(WelcomeSlide) { (-0.05f * (viewportHeight ?: it)).roundToInt() }
                movesFromTo<RecordingsDestination, ActiveRecordingDestination>() ->
                    slideOutHorizontally(RecordingSlide) { (-0.08f * it).roundToInt() }
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
        slideInHorizontally(ReturningListSlide) { -it }
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

internal fun Modifier.navigationEdgeShadow(atTop: Boolean = false): Modifier = drawWithCache {
    val depth = 16.dp.toPx()
    val shadow = Color(0x400A2032)
    val edge = if (atTop) Offset(0f, -depth) else Offset(-depth, 0f)
    val bounds = if (atTop) Size(size.width, depth) else Size(depth, size.height)
    val brush = Brush.linearGradient(listOf(shadow.copy(alpha = 0f), shadow), edge, Offset.Zero)

    // The shadow sits outside the screen and is clipped by the host when the screen is at rest.
    onDrawBehind { drawRect(brush, topLeft = edge, size = bounds) }
}
