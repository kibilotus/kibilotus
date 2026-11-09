package com.kibilotus.supplymanager.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.kibilotus.supplymanager.R
import com.kibilotus.supplymanager.domain.MainCategory

/** 하단 탭 = 대분류(집, 파티룸) + 설정. */
sealed class BottomTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val route: String,
) {
    class Category(val category: MainCategory, icon: ImageVector) : BottomTab(
        labelRes = category.labelRes,
        icon = icon,
        route = Routes.category(category),
    )

    data object Settings : BottomTab(
        labelRes = R.string.tab_settings,
        icon = Icons.Filled.Settings,
        route = Routes.SETTINGS,
    )

    companion object {
        val ALL: List<BottomTab> = listOf(
            Category(MainCategory.HOME, Icons.Filled.Home),
            Category(MainCategory.PARTY_ROOM, Icons.Filled.Celebration),
            Settings,
        )
    }
}
