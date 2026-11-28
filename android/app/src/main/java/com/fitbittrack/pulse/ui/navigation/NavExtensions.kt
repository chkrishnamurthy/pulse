package com.fitbittrack.pulse.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/*
 * The small set of navigation moves every page uses. Pages never call navigate() with their own options,
 * so Back, tabs and double taps behave the same everywhere.
 */

/** One screen. It has its own solid background, so the screen below never shows through while one replaces the other. */
internal inline fun <reified T : Any> NavGraphBuilder.page(noinline content: @Composable (NavBackStackEntry) -> Unit) =
    composable<T> { entry ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(entry) }
    }

/**
 * A tab tap always shows that tab's first page: every page opened on top is closed, and nothing is saved to be
 * put back later. (Saving and restoring made Today come back as the page opened from it.)
 * Today stays at the bottom, so Back from any tab goes to Today, then leaves the app.
 */
internal fun NavHostController.openTab(route: Any) {
    if (route == TodayRoute) {
        if (!popBackStack<TodayRoute>(inclusive = false)) navigate(TodayRoute) { launchSingleTop = true }
    } else {
        navigate(route) {
            popUpTo<TodayRoute>()
            launchSingleTop = true
        }
    }
}

/** Opens a page on top of the current one. Double taps open it once. */
internal fun NavHostController.open(route: Any) = navigate(route) { launchSingleTop = true }

/** Opens [route] in place of the page of type [T], so Back skips that page. */
internal inline fun <reified T : Any> NavHostController.replace(route: Any) = navigate(route) {
    popUpTo<T> { inclusive = true }
    launchSingleTop = true
}

/** Back arrow. A second quick tap can't empty the stack. */
internal fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
}

/** Go to Today and remove Connect from the back stack. */
internal fun NavHostController.goHome() = replace<ConnectRoute>(TodayRoute)

/** A page whose data is still loading. */
@Composable
internal fun LoadingPage(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
