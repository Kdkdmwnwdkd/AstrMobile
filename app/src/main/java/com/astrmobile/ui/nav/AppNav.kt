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
import com.astrmobile.ui.dashboard.DashboardScreen
import com.astrmobile.ui.onboarding.OnboardingScreen
import com.astrmobile.ui.settings.SettingsScreen
import com.astrmobile.ui.webui.WebUiScreen

object Routes {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Console = "console"
    const val Settings = "settings"
    const val Web = "web/{port}"

    fun web(port: Int) = "web/$port"
}

@Composable
fun AppNav(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val ready by viewModel.environmentReady.collectAsStateWithLifecycle()

    LaunchedEffect(ready) {
        if (ready && navController.currentDestination?.route == Routes.Onboarding) {
            navController.navigate(Routes.Home) {
                popUpTo(Routes.Onboarding) { inclusive = true }
                launchSingleTop = true
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
                onRepair = { navController.navigate(Routes.Onboarding) },
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
