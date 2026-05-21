package com.meshtastic.bbs.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val BbsTypography = Typography(
    headlineLarge  = TextStyle(fontWeight = FontWeight.Bold,       fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold,       fontSize = 26.sp, lineHeight = 34.sp),
    headlineSmall  = TextStyle(fontWeight = FontWeight.SemiBold,   fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge     = TextStyle(fontWeight = FontWeight.Bold,       fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium    = TextStyle(fontWeight = FontWeight.SemiBold,   fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall     = TextStyle(fontWeight = FontWeight.Medium,     fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge      = TextStyle(fontWeight = FontWeight.Normal,     fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.15.sp),
    bodyMedium     = TextStyle(fontWeight = FontWeight.Normal,     fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.25.sp),
    bodySmall      = TextStyle(fontWeight = FontWeight.Normal,     fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp),
    labelLarge     = TextStyle(fontWeight = FontWeight.SemiBold,   fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium    = TextStyle(fontWeight = FontWeight.Medium,     fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall     = TextStyle(fontWeight = FontWeight.Medium,     fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)
