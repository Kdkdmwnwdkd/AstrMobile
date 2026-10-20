package com.astrmobile.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
    const val PluginConfig = "pluginconfig/{pluginId}"

    // 目录名理论上不含特殊字符，但导航参数必须编码，
    // 否则带 / 或空格的名字会把路由切错段。
    fun pluginConfig(pluginId: String) =
        "pluginconfig/${android.net.Uri.encode(pluginId)}"
    const val Web = "web/{port}"

    fun web(port: Int) = "web/$port"
}

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

    NavHost(
        navController = navController,
        startDestination = if (ready) Routes.Home else Routes.Onboarding,
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
                onOpenModels = { navController.navigate(Routes.Models) },
                onOpenPlugins = { navController.navigate(Routes.Plugins) },
                onOpenPersona = { navController.navigate(Routes.Persona) },
                onOpenKnowledge = { navController.navigate(Routes.Knowledge) },
                onOpenDiagnostics = { navController.navigate(Routes.Diagnostics) },
                onOpenBackup = { navController.navigate(Routes.Backup) },
                onOpenConversations = { navController.navigate(Routes.Conversations) },
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
                onBack = { navController.popBackStack() },
                onRestart = {
                    viewModel.restartContainer()
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.Plugins) {
            PluginScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onRestart = {
                    viewModel.restartContainer()
                    navController.popBackStack()
                },
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

        composable(Routes.Console) {
            ConsoleScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(Routes.Settings) {
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
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
