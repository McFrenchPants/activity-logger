package com.mcfrenchpants.activityledger.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.ui.explore.ExploreScreen
import com.mcfrenchpants.activityledger.ui.history.HistoryScreen
import com.mcfrenchpants.activityledger.ui.log.LogScreen
import com.mcfrenchpants.activityledger.ui.tags.TagsScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/** Type-safe route of the Log destination (the start destination). */
@Serializable
data object LogRoute

/** Type-safe route of the History destination. */
@Serializable
data object HistoryRoute

/** Type-safe route of the Tags destination (rename and merge subjects and actions). */
@Serializable
data object TagsRoute

/**
 * Type-safe route of the Explore destination: one screen to ask about, search and count the
 * logged history (it replaced the Ask tab).
 */
@Serializable
data object ExploreRoute

/**
 * The top-level destinations shown in the navigation bar, in order: Log, History, Tags, Explore
 * (UX_VISUAL_SPEC D1).
 */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    LOG(LogRoute, LogRoute::class, R.string.nav_log, R.drawable.ic_nav_log),
    HISTORY(HistoryRoute, HistoryRoute::class, R.string.nav_history, R.drawable.ic_nav_history),
    TAGS(TagsRoute, TagsRoute::class, R.string.nav_tags, R.drawable.ic_nav_tags),
    EXPLORE(ExploreRoute, ExploreRoute::class, R.string.nav_explore, R.drawable.ic_nav_explore),
}

/**
 * The app shell: a [Scaffold] with an M3 [NavigationBar] (labels always visible) over a
 * [NavHost]. Each top-level destination keeps its own back stack (saveState/restoreState), and
 * because every tab switch pops back to the start destination, system Back from History, Tags
 * or Explore returns to Log (Explore first steps back through its own earlier filters).
 */
@Composable
fun LedgerNavigation(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.hasRoute(destination.routeClass) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTopLevel(destination) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = LogRoute,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<LogRoute> {
                LogScreen(onOpenHistory = { navController.navigateToTopLevel(TopLevelDestination.HISTORY) })
            }
            composable<HistoryRoute> { HistoryScreen() }
            composable<TagsRoute> { TagsScreen() }
            composable<ExploreRoute> { ExploreScreen() }
        }
    }
}

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
