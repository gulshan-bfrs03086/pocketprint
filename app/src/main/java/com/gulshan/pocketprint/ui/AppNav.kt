package com.gulshan.pocketprint.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.gulshan.pocketprint.ui.screens.JobsScreen
import com.gulshan.pocketprint.ui.screens.LabelScreen
import com.gulshan.pocketprint.ui.screens.PrintersScreen
import com.gulshan.pocketprint.ui.screens.SettingsScreen
import com.gulshan.pocketprint.ui.vm.PrintersViewModel

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("printers", "Print", Icons.Filled.Print),
    Tab("labels", "Labels", Icons.Filled.Label),
    Tab("jobs", "Jobs", Icons.Filled.History),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppNav(viewModel: PrintersViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination
    val wide = isWideScreen()

    fun isSelected(tab: Tab) = currentRoute?.hierarchy?.any { it.route == tab.route } == true

    fun go(tab: Tab) {
        navController.navigate(tab.route) {
            // Keep a single copy of each tab on the back stack.
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            // A bottom bar on a tablet puts the controls a hand-span away from
            // where the eye is. Above 600dp the navigation moves to the side,
            // which is where every other Android tablet app keeps it.
            if (!wide) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = isSelected(tab),
                            onClick = { go(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Edge-to-edge means the keyboard covers the window rather than
                // resizing it, so a field near the bottom of a form ends up
                // underneath what is being typed into it.
                .imePadding(),
        ) {
            if (wide) {
                NavigationRail {
                    TABS.forEach { tab ->
                        NavigationRailItem(
                            selected = isSelected(tab),
                            onClick = { go(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
            AdaptiveContent(Modifier.weight(1f)) {
                Host(navController, viewModel)
            }
        }
    }
}

@Composable
private fun Host(navController: NavHostController, viewModel: PrintersViewModel) {
    NavHost(navController = navController, startDestination = "printers") {
        composable("printers") { PrintersScreen(viewModel) }
        composable("labels") { LabelScreen(viewModel) }
        composable("jobs") { JobsScreen(viewModel) }
        composable("settings") { SettingsScreen(viewModel) }
    }
}
