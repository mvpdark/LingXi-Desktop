package top.mvpdark.lx.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.koin.compose.viewmodel.koinViewModel
import top.mvpdark.lx.ui.auth.AuthViewModel
import top.mvpdark.lx.ui.components.LoadingIndicator
import top.mvpdark.lx.ui.screens.ChatScreen
import top.mvpdark.lx.ui.screens.HomeScreen
import top.mvpdark.lx.ui.screens.ImageEditScreen
import top.mvpdark.lx.ui.screens.LoginScreen
import top.mvpdark.lx.ui.screens.PlaceholderScreen

/**
 * 应用导航图。
 *
 * - startDestination 根据 token 状态决定 login 或 home
 * - composable 路由定义：LOGIN / HOME / CHAT/{sessionId} / IMAGE_EDIT / SETTINGS
 */
@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = koinViewModel()
    val authState by authViewModel.uiState.collectAsState()

    // 初次启动检测本地登录态期间显示加载页
    if (!authState.initialCheckComplete) {
        LoadingIndicator(
            message = "灵犀启动中...",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val startDestination = if (authState.isLoggedIn) Routes.HOME else Routes.LOGIN

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                authViewModel = authViewModel,
                onNavigateHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                authViewModel = authViewModel,
                onNavigateChat = { sessionId ->
                    navController.navigate(Routes.chat(sessionId))
                },
                onNavigateImageEdit = {
                    navController.navigate(Routes.IMAGE_EDIT) {
                        launchSingleTop = true
                    }
                },
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument(Routes.SESSION_ID_ARG) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val sessionId: String = backStackEntry.arguments?.getString(Routes.SESSION_ID_ARG) ?: ""
            ChatScreen(sessionId = sessionId)
        }

        // ImageEditViewModel 通过 koinViewModel() 注入，生命周期绑定到 IMAGE_EDIT back stack entry
        composable(Routes.IMAGE_EDIT) {
            ImageEditScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            PlaceholderScreen(title = "设置", subtitle = "应用设置（即将上线）")
        }
    }
}
