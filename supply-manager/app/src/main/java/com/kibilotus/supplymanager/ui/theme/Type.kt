package com.kibilotus.supplymanager.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

private val Default = Typography()

internal val SupplyTypography = Default.copy(
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.Medium),
    // 금액은 한눈에 읽혀야 하므로 요약 영역 숫자만 조금 더 굵게.
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.Bold),
)
