package com.astrmobile.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.astrmobile.MainViewModel
import com.astrmobile.ui.console.ConsoleScreen
import com.astrmobile.ui.conversations.ConversationsScreen
import com.astrmobile.ui.dashboard.DashboardScreen
import com.astrmobile.ui.models.ModelScreen
import com.astrmobile.ui.backup.BackupScreen
import com.astrmobile.ui.diagnostics.DiagnosticsScreen
import com.astrmobile.ui.knowledge.KnowledgeScreen
import com.astrmobile.ui.persona.PersonaScreen
import com.astrmobile.ui.plugins.PluginConfigScreen
import com.astrmobile.ui.plugins.PluginScreen
import com.astrmobile.ui.onboarding.OnboardingScreen
import com.astrmobile.ui.qrcode.QrCodeScreen
import com.astrmobile.ui.screen.ScreenReaderScreen
import com.astrmobile.ui.settings.SettingsScreen
import com.astrmobile.ui.webui.WebUiScreen

object Routes {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Console = "console"
    const val Settings = "settings"
    const val QrCode = "qrcode"
    const val Models = "models"
    const val Plugins = "plugins"
    const val Persona = "persona"
    const val Knowledge = "knowledge"
    const val Diagnostics = "diagnostics"
    const val Backup = "backup"
    const val Conversations = "conversations"
    const val ScreenReader = "screen"
    const val PluginConfig = "pluginconfig/{pluginId}"

    // 目录名理论上不含特殊字符，但导航参数必须编码，
    // 否则带 / 或空格的名字会把路由切错段。
    fun pluginConfig(pluginId: String) =
        "pluginconfig/${android.net.Uri.encode(pluginId)}"
    const val Web = "web/{port}"

    fun web(port: Int) = "web/$port"
}

/** 底部四个主入口。其余页面都是二级页面，从控制台进去。 */
private data class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val mainTabs = listOf(
    MainTab(Routes.Home, "控制台", Icons.Rounded.Dashboard),
    MainTab(Routes.Models, "模型", Icons.Rounded.SmartToy),
    MainTab(Routes.Plugins, "插件", Icons.Rounded.Extension),
    MainTab(Routes.Settings, "设置", Icons.Rounded.Settings),
)

@Composable
fun AppNav(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val ready by viewModel.environmentReady.collectAsStateWithLifecycle()

    LaunchedEffect(ready) {
        val current = navController.currentDestination?.route
        when {
            // 环境就绪且仍在向导页 → 进入控制台
            ready && current == Routes.Onboarding -> {
                navController.navigate(Routes.Home) {
                    popUpTo(Routes.Onboarding) { inclusive = true }
                    launchSingleTop = true
                }
            }
            // 环境被清除（或首次未安装）却停留在其它页面 → 回到向导
            !ready && current != null && current != Routes.Onboarding -> {
                navController.navigate(Routes.Onboarding) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = mainTabs.any { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    mainTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        // 四个 tab 平级：回到根再切换，保留各自的滚动位置
                                        popUpTo(Routes.Home) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (ready) Routes.Home else Routes.Onboarding,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.Onboarding) {
                OnboardingScreen(
                    viewModel = viewModel,
                    onFinished = {
                        navController.navigate(Routes.Home) {
                            popUpTo(Routes.Onboarding) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.Home) {
                DashboardScreen(
                    viewModel = viewModel,
                    onOpenConsole = { navController.navigate(Routes.Console) },
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                    onOpenWeb = { port -> navController.navigate(Routes.web(port)) },
                    onOpenQr = { navController.navigate(Routes.QrCode) },
                    onOpenPersona = { navController.navigate(Routes.Persona) },
                    onOpenKnowledge = { navController.navigate(Routes.Knowledge) },
                    onOpenDiagnostics = { navController.navigate(Routes.Diagnostics) },
                    onOpenBackup = { navController.navigate(Routes.Backup) },
                    onOpenConversations = { navController.navigate(Routes.Conversations) },
                    onOpenScreen = { navController.navigate(Routes.ScreenReader) },
                    onRepair = {
                        viewModel.prepareReinstall()
                        navController.navigate(Routes.Onboarding)
                    },
                )
            }

            composable(Routes.QrCode) {
                QrCodeScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenWebFallback = { navController.navigate(Routes.web(6099)) },
                )
            }

            composable(Routes.Models) {
                ModelScreen(
                    viewModel = viewModel,
                    showBack = false,
                    onBack = { navController.popBackStack() },
                    onRestart = { viewModel.restartContainer() },
                )
            }

            composable(Routes.Plugins) {
                PluginScreen(
                    viewModel = viewModel,
                    showBack = false,
                    onBack = { navController.popBackStack() },
                    onRestart = { viewModel.restartContainer() },
                    onOpenConfig = { pluginId ->
                        navController.navigate(Routes.pluginConfig(pluginId))
                    },
                )
            }

            composable(Routes.Persona) {
                PersonaScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onRestart = {
                        viewModel.restartContainer()
                        navController.popBackStack()
                    },
                )
            }

            composable(Routes.Knowledge) {
                KnowledgeScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onRestart = {
                        viewModel.restartContainer()
                        navController.popBackStack()
                    },
                )
            }

            composable(Routes.Diagnostics) {
                DiagnosticsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.PluginConfig,
                arguments = listOf(navArgument("pluginId") { type = NavType.StringType }),
            ) { entry ->
                PluginConfigScreen(
                    viewModel = viewModel,
                    pluginId = entry.arguments?.getString("pluginId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onRestart = {
                        viewModel.restartContainer()
                        navController.popBackStack()
                    },
                )
            }

            composable(Routes.Backup) {
                BackupScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.Conversations) {
                ConversationsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ScreenReader) {
                ScreenReaderScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.Console) {
                ConsoleScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }

            composable(Routes.Settings) {
                SettingsScreen(
                    viewModel = viewModel,
                    showBack = false,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.Web,
                arguments = listOf(navArgument("port") { type = NavType.IntType }),
            ) { entry ->
                val port = entry.arguments?.getInt("port") ?: 6185
                WebUiScreen(
                    initialPort = port,
                    astrbotPort = viewModel.containerPorts().first,
                    napcatPort = viewModel.containerPorts().second,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
