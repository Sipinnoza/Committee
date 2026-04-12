package com.committee.investing.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.committee.investing.domain.model.AgentRole
import com.committee.investing.ui.screen.*
import com.committee.investing.ui.theme.*
import com.committee.investing.ui.viewmodel.AgentChatViewModel
import com.committee.investing.ui.viewmodel.MeetingViewModel
import dagger.hilt.android.AndroidEntryPoint

sealed class NavRoute(val route: String, val label: String, val icon: ImageVector) {
    object Home     : NavRoute("home",     "会议",  Icons.Default.Groups)
    object History  : NavRoute("history",  "历史",  Icons.Default.History)
    object Log      : NavRoute("log",      "日志",  Icons.Default.Terminal)
    object Settings : NavRoute("settings", "设置",  Icons.Default.Settings)
    object Agents   : NavRoute("agents",   "成员",  Icons.Default.SmartToy)
}

private val bottomNavItems = listOf(NavRoute.Home, NavRoute.Agents, NavRoute.History, NavRoute.Log, NavRoute.Settings)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CommitteeTheme {
                CommitteeApp()
            }
        }
    }
}

@Composable
fun CommitteeApp() {
    val navController = rememberNavController()
    val viewModel: MeetingViewModel = hiltViewModel()
    val agentChatViewModel: AgentChatViewModel = hiltViewModel()

    // Track if we're on a detail/config screen (hide bottom bar)
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route
    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        containerColor = SurfaceDark,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = SurfaceCard, tonalElevation = 0.dp) {
                    val currentDest = navBackStack?.destination
                    bottomNavItems.forEach { screen ->
                        val selected = currentDest?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = {
                                Text(screen.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CommitteeGold,
                                selectedTextColor = CommitteeGold,
                                indicatorColor = CommitteeGold.copy(alpha = 0.12f),
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(NavRoute.Home.route)     { HomeScreen(viewModel) }
            composable(NavRoute.Agents.route)   {
                AgentsScreen(
                    onAgentClick = { role ->
                        navController.navigate("agent_chat/${role.id}")
                    }
                )
            }
            composable(NavRoute.History.route)  {
                HistoryScreen(
                    viewModel = viewModel,
                    onSessionClick = { session ->
                        navController.navigate("session_detail/${session.traceId}")
                    },
                )
            }
            composable(NavRoute.Log.route)      { LogScreen(viewModel) }
            composable(NavRoute.Settings.route) { SettingsScreen(viewModel) }

            // Session detail route
            composable(
                route = "session_detail/{traceId}",
                arguments = listOf(navArgument("traceId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val traceId = backStackEntry.arguments?.getString("traceId") ?: ""
                val sessions by viewModel.uiState.collectAsState()
                val session = sessions.sessions.find { it.traceId == traceId }
                val speeches by viewModel.sessionSpeeches.collectAsState()
                val sessionSpeeches = speeches[traceId] ?: emptyList()
                if (session != null) {
                    SessionDetailScreen(
                        session = session,
                        speeches = sessionSpeeches,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // Agent chat route
            composable(
                route = "agent_chat/{agentId}",
                arguments = listOf(navArgument("agentId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
                val role = AgentRole.fromId(agentId) ?: AgentRole.ANALYST
                AgentConfigChatScreen(
                    role = role,
                    viewModel = agentChatViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
