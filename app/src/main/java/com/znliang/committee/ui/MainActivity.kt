package com.znliang.committee.ui

import android.content.res.Resources
import android.os.Bundle
import javax.inject.Inject
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.znliang.committee.domain.model.AgentRole
import com.znliang.committee.domain.model.MeetingPresetConfig
import com.znliang.committee.ui.screen.AgentConfigChatScreen
import com.znliang.committee.ui.screen.HistoryScreen
import com.znliang.committee.ui.screen.HomeScreen
import com.znliang.committee.ui.screen.LogScreen
import com.znliang.committee.ui.screen.SessionDetailScreen
import com.znliang.committee.ui.screen.SettingsScreen
import com.znliang.committee.ui.screen.SkillManagementScreen
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.znliang.committee.R
import com.znliang.committee.data.repository.AppConfigRepository
import com.znliang.committee.domain.model.AppLanguage
import com.znliang.committee.ui.theme.CommitteeGold
import com.znliang.committee.ui.theme.CommitteeTheme
import com.znliang.committee.ui.theme.SurfaceCard
import com.znliang.committee.ui.theme.SurfaceDark
import com.znliang.committee.ui.theme.TextMuted
import com.znliang.committee.ui.viewmodel.AgentChatViewModel
import com.znliang.committee.ui.viewmodel.MeetingViewModel
import com.znliang.committee.ui.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import java.util.Locale

sealed class NavRoute(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    object Home     : NavRoute("home",     R.string.nav_meeting,  Icons.Default.Groups)
    object History  : NavRoute("history",  R.string.nav_history,  Icons.Default.History)
    object Log      : NavRoute("log",      R.string.nav_logs,     Icons.Default.Terminal)
    object Settings : NavRoute("settings", R.string.nav_settings, Icons.Default.Settings)
}

private val bottomNavItems = listOf(NavRoute.Home, NavRoute.History, NavRoute.Log, NavRoute.Settings)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var presetConfig: MeetingPresetConfig

    @Inject lateinit var appConfigRepository: AppConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = runBlocking { appConfigRepository.getConfig() }
        applyLanguage(config.selectedLanguage)
        enableEdgeToEdge()
        setContent {
            CommitteeTheme {
                CommitteeApp(presetConfig)
            }
        }
    }

    private fun applyLanguage(language: AppLanguage) {
        val locale = when (language) {
            AppLanguage.SYSTEM -> Resources.getSystem().configuration.locales[0]
            else -> Locale(language.code)
        }
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    fun restartSelf() {
        finish()
        startActivity(intent)
    }
}

@Composable
fun CommitteeApp(presetConfig: MeetingPresetConfig) {
    val navController = rememberNavController()
    val viewModel: MeetingViewModel = hiltViewModel()
    val agentChatViewModel: AgentChatViewModel = hiltViewModel()

    // Track if we're on a detail/config screen (hide bottom bar)
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route
    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        containerColor = SurfaceDark,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = SurfaceCard, tonalElevation = 2.dp) {
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
                            icon = { Icon(screen.icon, contentDescription = stringResource(screen.labelRes)) },
                            label = {
                                Text(stringResource(screen.labelRes), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
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
            enterTransition = { fadeIn(tween(250)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start) },
            exitTransition = { fadeOut(tween(150)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start) },
            popEnterTransition = { fadeIn(tween(250)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End) },
            popExitTransition = { fadeOut(tween(150)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End) },
        ) {
            composable(NavRoute.Home.route)     {
                HomeScreen(
                    viewModel = viewModel,
                    presetConfig = presetConfig,
                    onNavigateToSettings = { navController.navigate(NavRoute.Settings.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    } },
                    onAgentClick = { role -> navController.navigate("agent_chat/${role.id}") },
                )
            }
            composable(NavRoute.History.route)  {
                HistoryScreen(
                    viewModel = viewModel,
                    onSessionClick = { session ->
                        viewModel.loadSessionSpeeches(session.traceId)
                        navController.navigate("session_detail/${session.traceId}")
                    },
                )
            }
            composable(NavRoute.Log.route)      { LogScreen(viewModel) }
            composable(NavRoute.Settings.route) {
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                val context = LocalContext.current
                SettingsScreen(
                    viewModel = viewModel,
                    settingsViewModel = settingsViewModel,
                    presetConfig = presetConfig,
                    onManageSkills = { navController.navigate("skill_management") },
                    onRestartApp = { (context as MainActivity).restartSelf() },
                )
            }

            // Skill management route
            composable("skill_management") {
                SkillManagementScreen(onBack = { navController.popBackStack() })
            }

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
