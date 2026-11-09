package com.kibilotus.supplymanager.domain

import androidx.annotation.StringRes
import com.kibilotus.supplymanager.R

/**
 * 대분류. 탭으로 노출되는 고정 분류이므로 enum 으로 관리한다.
 * DB 에는 [key] 문자열로 저장되므로, 값을 바꾸면 기존 데이터와 연결이 끊어진다.
 */
enum class MainCategory(
    val key: String,
    @StringRes val labelRes: Int,
) {
    HOME("home", R.string.category_home),
    PARTY_ROOM("party_room", R.string.category_party_room),
    ;

    companion object {
        val DEFAULT: MainCategory = HOME

        fun fromKeyOrNull(key: String?): MainCategory? = entries.firstOrNull { it.key == key }

        fun fromKey(key: String?): MainCategory = fromKeyOrNull(key) ?: DEFAULT
    }
}
