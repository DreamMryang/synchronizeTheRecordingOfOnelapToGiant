package com.dreammryang.onelaptogiant.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dreammryang.onelaptogiant.di.AppContainer
import com.dreammryang.onelaptogiant.ui.history.HistoryScreen
import com.dreammryang.onelaptogiant.ui.history.HistoryViewModel
import com.dreammryang.onelaptogiant.ui.history.SessionDetailScreen
import com.dreammryang.onelaptogiant.ui.history.SessionDetailViewModel
import com.dreammryang.onelaptogiant.ui.settings.SettingsScreen
import com.dreammryang.onelaptogiant.ui.settings.SettingsViewModel

private data class TopDest(val route: String, val label: String, val icon: ImageVector)

private val TOP_DESTS = listOf(
    TopDest("history", "历史", Icons.Filled.History),
    TopDest("settings", "设置", Icons.Filled.Settings),
)

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TOP_DESTS.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route ||
                            (dest.route == "history" && currentRoute?.startsWith("session/") == true),
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "history",
            // 外层已按系统栏 inset 加过 padding，消费掉以免内层 Scaffold 再加一遍（顶部双重空白）
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable("history") {
                val vm: HistoryViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        HistoryViewModel(
                            sessions = container.database.sessionDao().observeAll(),
                            configured = container.credentialStore.configured,
                            progress = container.syncEngine.progress,
                            processFailedCount = container.database.recordDao().observeProcessFailedCount(),
                            onSyncRequested = { container.syncScheduler.triggerManual() },
                            deleteSession = {
                                container.database.recordDao().deleteBySession(it)
                                container.database.sessionDao().deleteById(it)
                            },
                        )
                    }
                })
                HistoryScreen(
                    vm,
                    onOpenSession = { id -> navController.navigate("session/$id") },
                    onGoSettings = { navController.navigate("settings") },
                )
            }
            composable(
                route = "session/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) { entry ->
                val sessionId = entry.arguments!!.getLong("sessionId")
                val vm: SessionDetailViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        SessionDetailViewModel(
                            records = container.database.recordDao().observeBySession(sessionId),
                            retry = { recordId -> container.syncEngine.retryRecord(recordId) },
                            deleteRecord = { recordId -> container.database.recordDao().deleteById(recordId) },
                        )
                    }
                })
                SessionDetailScreen(vm)
            }
            composable("settings") {
                val vm: SettingsViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            settings = container.settingsRepository,
                            credentials = container.credentialStore,
                            schedule = { hours, wifiOnly ->
                                container.syncScheduler.schedulePeriodic(hours, wifiOnly)
                            },
                            cancelSchedule = { container.syncScheduler.cancelPeriodic() },
                            clearHistory = {
                                container.database.recordDao().deleteAll()
                                container.database.sessionDao().deleteAll()
                                container.fitDir.deleteRecursively()
                            },
                        )
                    }
                })
                SettingsScreen(vm)
            }
        }
    }
}
