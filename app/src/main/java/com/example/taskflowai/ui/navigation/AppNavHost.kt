package com.example.taskflowai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.taskflowai.ui.screens.HomeScreen
import com.example.taskflowai.ui.screens.SettingsScreen
import com.example.taskflowai.ui.screens.TaskDetailScreen

/** 应用导航图:home(任务中心) ⇄ task/{taskId}(任务详情步骤条)⇄ settings(设置) */
@Composable
fun AppNavHost(modifier: Modifier = Modifier, prefillInstruction: String? = null) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier,
    ) {
        composable("home") {
            HomeScreen(
                onOpenTask = { taskId -> navController.navigate("task/$taskId") },
                onOpenSettings = { navController.navigate("settings") },
                prefillInstruction = prefillInstruction,
            )
        }

        composable(
            route = "task/{taskId}",
            arguments = listOf(navArgument("taskId") { type = NavType.LongType }),
        ) {
            TaskDetailScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
