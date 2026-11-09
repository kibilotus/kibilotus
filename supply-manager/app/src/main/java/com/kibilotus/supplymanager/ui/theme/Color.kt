package com.kibilotus.supplymanager.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Green40 = Color(0xFF2E6F5E)
private val Green80 = Color(0xFF9CD6C3)
private val GreenContainerLight = Color(0xFFB9F0DC)
private val GreenContainerDark = Color(0xFF14503F)
private val Sand40 = Color(0xFF6E5B3E)
private val Sand80 = Color(0xFFDCC49C)
private val Error40 = Color(0xFFB3261E)
private val Error80 = Color(0xFFF2B8B5)

internal val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = GreenContainerLight,
    onPrimaryContainer = Color(0xFF00281D),
    secondary = Sand40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E3C2),
    onSecondaryContainer = Color(0xFF261A04),
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    error = Error40,
    onError = Color.White,
)

internal val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Color(0xFF00382A),
    primaryContainer = GreenContainerDark,
    onPrimaryContainer = GreenContainerLight,
    secondary = Sand80,
    onSecondary = Color(0xFF3C2F14),
    secondaryContainer = Color(0xFF554429),
    onSecondaryContainer = Color(0xFFF7E3C2),
    background = Color(0xFF191C1B),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF191C1B),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    outline = Color(0xFF899390),
    error = Error80,
    onError = Color(0xFF601410),
)
