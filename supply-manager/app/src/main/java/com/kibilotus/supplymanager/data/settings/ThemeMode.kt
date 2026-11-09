package com.kibilotus.supplymanager.data.settings

import androidx.annotation.StringRes
import com.kibilotus.supplymanager.R

enum class ThemeMode(
    val key: String,
    @StringRes val labelRes: Int,
) {
    SYSTEM("system", R.string.settings_theme_system),
    LIGHT("light", R.string.settings_theme_light),
    DARK("dark", R.string.settings_theme_dark),
    ;

    companion object {
        val DEFAULT: ThemeMode = SYSTEM

        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
