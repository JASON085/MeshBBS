package com.meshtastic.bbs.ui.theme

import androidx.compose.ui.graphics.Color

// ── Material3 Scheme ───────────────────────────────────────────
val Primary              = Color(0xFF00E5FF)   // Electric Cyan
val OnPrimary            = Color(0xFF003640)
val PrimaryContainer     = Color(0xFF004D5F)
val OnPrimaryContainer   = Color(0xFF9EF5FF)

val Secondary            = Color(0xFFFF79C6)   // Hot Pink
val OnSecondary          = Color(0xFF5C0038)
val SecondaryContainer   = Color(0xFF79004D)
val OnSecondaryContainer = Color(0xFFFFD8EE)

val Tertiary             = Color(0xFFFFD060)   // Warm Gold
val OnTertiary           = Color(0xFF3D2D00)
val TertiaryContainer    = Color(0xFF574200)
val OnTertiaryContainer  = Color(0xFFFFE797)

val Background           = Color(0xFF05080F)
val OnBackground         = Color(0xFFC8D3E8)

val Surface              = Color(0xFF0D1525)
val OnSurface            = Color(0xFFC8D3E8)
val SurfaceVariant       = Color(0xFF1A2744)
val OnSurfaceVariant     = Color(0xFF8499BC)
val SurfaceContainer     = Color(0xFF111D35)
val SurfaceContainerHigh = Color(0xFF162240)

val Outline              = Color(0xFF2D4066)
val OutlineVariant       = Color(0xFF1E3355)

val Error                = Color(0xFFFF5449)
val OnError              = Color(0xFF601410)
val ErrorContainer       = Color(0xFF8C1D18)
val OnErrorContainer     = Color(0xFFFFDAD6)

// ── App semantic colors ────────────────────────────────────────
val AuthorGreen   = Color(0xFF4ADE80)   // Author name
val BoardBlue     = Color(0xFF38BDF8)   // Board label
val DateGray      = Color(0xFF64748B)   // Timestamps
val MeshViolet    = Color(0xFFA78BFA)   // Mesh messages
val Separator     = Color(0xFF1E3A5F)   // Dividers

// Push count gradient (0 → 爆)
val PushDim    = Color(0xFF4B5563)   // 0
val PushWhite  = Color(0xFFCBD5E1)   // 1-4
val PushYellow = Color(0xFFFBBF24)   // 5-9
val PushOrange = Color(0xFFF97316)   // 10-19
val PushRed    = Color(0xFFEF4444)   // 20-99
val PushBoom   = Color(0xFFFF1744)   // 100+  爆

// Board card gradient pairs
val BoardGradients = listOf(
    listOf(Color(0xFF1A053A), Color(0xFF3A0F78)),   // Violet
    listOf(Color(0xFF002855), Color(0xFF005AB5)),   // Blue
    listOf(Color(0xFF003322), Color(0xFF006644)),   // Green
    listOf(Color(0xFF3D1200), Color(0xFF7A2800)),   // Orange
    listOf(Color(0xFF3D0000), Color(0xFF780000)),   // Red
    listOf(Color(0xFF00283D), Color(0xFF004E77)),   // Teal
    listOf(Color(0xFF1A002B), Color(0xFF400080)),   // Purple
    listOf(Color(0xFF0D2200), Color(0xFF1E4400)),   // Dark Green
)

fun boardGradient(name: String) =
    BoardGradients[Math.abs(name.hashCode()) % BoardGradients.size]
