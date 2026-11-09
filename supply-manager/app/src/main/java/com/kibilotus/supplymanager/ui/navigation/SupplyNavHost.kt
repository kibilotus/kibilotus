package com.kibilotus.supplymanager.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kibilotus.supplymanager.domain.MainCategory
import com.kibilotus.supplymanager.ui.detail.ItemDetailScreen
import com.kibilotus.supplymanager.ui.folder.FolderListScreen
import com.kibilotus.supplymanager.ui.item.ItemListScreen
import com.kibilotus.supplymanager.ui.settings.SettingsScreen

@Composable
fun SupplyApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentCategoryKey = backStackEntry?.arguments?.getString(Routes.ARG_CATEGORY_KEY)

    Scaffold(
        bottomBar = {
            NavigationBar {
                BottomTab.ALL.forEach { tab ->
                    val selected = when (tab) {
                        is BottomTab.Category -> currentCategoryKey == tab.category.key
                        is BottomTab.Settings -> currentDestination?.hierarchy?.any {
                            it.route == Routes.SETTINGS
                        } == true
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTab(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SupplyNavHost(navController = navController)
        }
    }
}

@Composable
private fun SupplyNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.category(MainCategory.DEFAULT),
    ) {
        composable(
            route = Routes.CATEGORY_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_CATEGORY_KEY) { type = NavType.StringType },
            ),
        ) { entry ->
            val category = MainCategory.fromKey(
                entry.arguments?.getString(Routes.ARG_CATEGORY_KEY),
            )
            FolderListScreen(
                category = category,
                onFolderClick = { folderId ->
                    navController.navigate(Routes.folder(category, folderId))
                },
            )
        }

        composable(
            route = Routes.FOLDER_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_CATEGORY_KEY) { type = NavType.StringType },
                navArgument(Routes.ARG_FOLDER_ID) { type = NavType.LongType },
            ),
        ) { entry ->
            val category = MainCategory.fromKey(
                entry.arguments?.getString(Routes.ARG_CATEGORY_KEY),
            )
            val folderId = entry.arguments?.getLong(Routes.ARG_FOLDER_ID) ?: 0L
            ItemListScreen(
                category = category,
                folderId = folderId,
                onItemClick = { itemId ->
                    navController.navigate(Routes.item(category, folderId, itemId))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.ITEM_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_CATEGORY_KEY) { type = NavType.StringType },
                navArgument(Routes.ARG_FOLDER_ID) { type = NavType.LongType },
                navArgument(Routes.ARG_ITEM_ID) { type = NavType.LongType },
            ),
        ) { entry ->
            val itemId = entry.arguments?.getLong(Routes.ARG_ITEM_ID) ?: 0L
            ItemDetailScreen(
                itemId = itemId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(route = Routes.SETTINGS) {
            SettingsScreen()
        }
    }
}

private fun NavHostController.navigateToTab(tab: BottomTab) {
    navigate(tab.route) {
        // 탭을 다시 누르면 그 탭의 첫 화면으로 돌아가고, 각 탭의 스크롤·백스택은 보존한다.
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
