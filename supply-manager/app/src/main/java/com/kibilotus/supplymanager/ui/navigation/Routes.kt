package com.kibilotus.supplymanager.ui.navigation

import com.kibilotus.supplymanager.domain.MainCategory

/**
 * 라우트는 대분류 → 중분류 → 소분류 계층을 그대로 경로에 담는다.
 * 깊은 화면에서도 categoryKey 를 알 수 있어 하단 탭 선택 상태를 유지할 수 있다.
 */
object Routes {

    const val ARG_CATEGORY_KEY = "categoryKey"
    const val ARG_FOLDER_ID = "folderId"
    const val ARG_ITEM_ID = "itemId"

    const val CATEGORY_PATTERN = "category/{$ARG_CATEGORY_KEY}"
    const val FOLDER_PATTERN = "category/{$ARG_CATEGORY_KEY}/folder/{$ARG_FOLDER_ID}"
    const val ITEM_PATTERN =
        "category/{$ARG_CATEGORY_KEY}/folder/{$ARG_FOLDER_ID}/item/{$ARG_ITEM_ID}"
    const val SETTINGS = "settings"

    fun category(category: MainCategory): String = "category/${category.key}"

    fun folder(category: MainCategory, folderId: Long): String =
        "category/${category.key}/folder/$folderId"

    fun item(category: MainCategory, folderId: Long, itemId: Long): String =
        "category/${category.key}/folder/$folderId/item/$itemId"
}
